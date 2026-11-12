package com.meetodo.tools;

import java.time.Clock;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.time.format.ResolverStyle;
import java.util.Map;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.meetodo.member.Member;
import com.meetodo.member.MemberRepository;
import com.meetodo.meeting.Meeting;
import com.meetodo.task.TaskService;
import com.meetodo.task.UnresolvedAssignee;
import com.meetodo.task.TaskMentionService;
import com.meetodo.task.dto.TaskResponse;
import org.springframework.ai.chat.model.ToolContext;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.stereotype.Component;

@Component
public class CreateTaskTool {

    private static final int MAX_TITLE_LENGTH = 200;
    private static final DateTimeFormatter DATE_FORMAT = DateTimeFormatter.ofPattern("uuuu-MM-dd")
            .withResolverStyle(ResolverStyle.STRICT);

    private final MemberRepository members;
    private final TaskService taskService;
    private final TaskMentionService mentions;
    private final ToolExecutionService execution;
    private final Clock clock;
    private final ObjectMapper mapper = new ObjectMapper();

    public CreateTaskTool(MemberRepository members, TaskService taskService,
                          TaskMentionService mentions, ToolExecutionService execution, Clock clock) {
        this.members = members;
        this.taskService = taskService;
        this.mentions = mentions;
        this.execution = execution;
        this.clock = clock;
    }

    public ToolResult createTask(String title, String assignee, String dueDate, Meeting meeting) {
        return createTask(title, assignee, dueDate, null, meeting);
    }

    public ToolResult createTask(String title, String assignee, String dueDate, Integer lookupCallOrder, Meeting meeting) {
        Map<String, Object> arguments = new java.util.LinkedHashMap<>();
        arguments.put("lookupCallOrder", lookupCallOrder);
        arguments.putAll(Map.of(
                "title", title == null ? "" : title,
                "assignee", assignee == null ? "" : assignee,
                "dueDate", dueDate == null ? "" : dueDate));
        return execution.execute(meeting, "createTask", arguments, () -> createValidated(title, assignee, dueDate, lookupCallOrder, meeting));
    }

    @Tool(name = "createTask", description = "할 일을 등록한다. assignee는 findMember 후보의 member:<id> 또는 unassigned:AMBIGUOUS / unassigned:NOT_FOUND / unassigned:TEAM_MISMATCH / unassigned:NONE 중 하나다. AMBIGUOUS/NOT_FOUND/TEAM_MISMATCH 미정은 해당 findMember 결과의 lookupCallOrder가 필수이며 NONE은 필요 없다. 서버가 TODO 중복을 재검사하여 CREATED/EXISTING을 반환한다.")
    public ToolResult invokeCreateTask(@ToolParam(description = "제목 1~200자") String title,
                                       @ToolParam(description = "배정 member:<id>, 동명이인 unassigned:AMBIGUOUS, 미등록 unassigned:NOT_FOUND, 팀 불일치 unassigned:TEAM_MISMATCH, 담당자 언급 없음 unassigned:NONE") String assignee,
                                       @ToolParam(description = "마감일 yyyy-MM-dd. 없으면 빈 값") String dueDate,
                                       @ToolParam(description = "미정 사유 AMBIGUOUS/NOT_FOUND/TEAM_MISMATCH를 확인한 findMember 결과의 lookupCallOrder. NONE이나 member 배정은 생략", required = false) Integer lookupCallOrder,
                                       ToolContext toolContext) {
        return createTask(title, assignee, dueDate, lookupCallOrder, execution.meetingFrom(toolContext));
    }

    private ToolResult createValidated(String title, String assignee, String dueDate, Integer lookupCallOrder, Meeting meeting) {
        if (title == null || title.isBlank()) return ToolResult.error("INVALID_TITLE", "제목을 입력해야 합니다.");
        String normalizedTitle = title.trim();
        if (normalizedTitle.length() > MAX_TITLE_LENGTH)
            return ToolResult.error("TITLE_TOO_LONG", "제목은 200자 이하여야 합니다.");

        LocalDate parsedDueDate = null;
        boolean pastDateFallback = false;
        if (dueDate != null && !dueDate.isBlank()) {
            try {
                parsedDueDate = LocalDate.parse(dueDate.trim(), DATE_FORMAT);
            } catch (DateTimeParseException exception) {
                return ToolResult.error("INVALID_DUE_DATE", "마감일은 yyyy-MM-dd 형식이어야 합니다.");
            }
            LocalDate today = LocalDate.now(clock);
            if (parsedDueDate.isBefore(today)) {
                if (execution.hasPastDueFailure(meeting, normalizedTitle, assignee)) {
                    parsedDueDate = null;
                    pastDateFallback = true;
                } else {
                    return ToolResult.error("PAST_DUE_DATE", "마감일은 오늘 또는 미래 날짜여야 합니다.");
                }
            }
        }

        Member member = null;
        String reason = "";
        String warning = "";
        if (assignee != null && assignee.matches("member:[1-9][0-9]*")) {
            Long id;
            try { id = Long.valueOf(assignee.substring("member:".length())); }
            catch (NumberFormatException invalidId) { return invalidAssignee(); }
            member = members.findById(id).orElse(null);
            if (member != null && execution.hasConflictingTeamLookup(meeting, member))
                return ToolResult.error("INVALID_ASSIGNEE_TEAM", ToolExecutionService.TEAM_MISMATCH_MESSAGE);
            if (member == null || !execution.isMemberAuthorized(meeting, id))
                return ToolResult.error("INVALID_ASSIGNEE", "현재 회의의 성공한 단일 후보 조회 ID만 배정할 수 있습니다. 후보 목록은 배정 권한이 아닙니다. 회의록에 팀이 없고 동명이인이면 unassigned:AMBIGUOUS로 등록하세요.");
        } else if ("unassigned:AMBIGUOUS".equals(assignee)) {
            reason = "AMBIGUOUS";
            warning = "동명이인 확인 필요";
        } else if ("unassigned:NOT_FOUND".equals(assignee)) {
            reason = "NOT_FOUND";
            warning = "등록되지 않은 담당자";
        } else if ("unassigned:TEAM_MISMATCH".equals(assignee)) {
            reason = "TEAM_MISMATCH";
            warning = "팀 불일치 확인 필요";
        } else if ("unassigned:NONE".equals(assignee)) {
            reason = "NONE";
        } else return invalidAssignee();
        UnresolvedAssignee unresolved = null;
        if (!reason.isEmpty() && !"NONE".equals(reason)) {
            unresolved = execution.unresolvedAssignee(meeting, lookupCallOrder, reason);
            if (unresolved == null) return ToolResult.error("INVALID_LOOKUP_REFERENCE", "findMember 결과에 있는 lookupCallOrder 값을 그대로 넣으세요");
            warning = unresolved.warning();
        }
        TaskService.ToolTaskResult creation = taskService.createFromToolIdempotently(
                normalizedTitle, member, parsedDueDate, meeting, unresolved);
        TaskResponse created = creation.task();
        if (created.id() != null && meeting.getId() != null)
            mentions.record(created.id(), meeting.getId());
        try {
            Map<String, Object> response = new java.util.LinkedHashMap<>();
            response.put("id", created.id());
            response.put("title", created.title());
            response.put("assignee", created.assignee() == null ? "담당자 미정" : created.assignee().name());
            response.put("dueDate", created.dueDate() == null ? "" : created.dueDate().toString());
            response.put("status", created.status().name());
            response.put("meetingId", created.meetingId());
            response.put("resultType", creation.created() ? "CREATED" : "EXISTING");
            response.put("warning", warning);
            response.put("unassignedReason", reason);
            response.put("unresolvedAssignee", unresolved);
            String message = pastDateFallback
                    ? "입력된 마감일이 과거라 마감일 없이 등록했습니다. 최종 답변에서 사용자에게 경고하세요."
                    : creation.created() ? "할 일을 등록했습니다. (CREATED)"
                    : "기존 할 일과 같음 (EXISTING). 기존 작업을 재사용했습니다.";
            if (!warning.isEmpty()) message += " " + warning + ": 담당자를 미정으로 등록했습니다.";
            return ToolResult.success(message, mapper.writeValueAsString(response));
        } catch (Exception exception) {
            return ToolResult.error("TOOL_EXECUTION_FAILED", "등록 결과를 만들지 못했습니다.");
        }
    }
    private ToolResult invalidAssignee() {
        return ToolResult.error("INVALID_ASSIGNEE", "배정과 미정 중 하나만 선택하세요. 회의록에 팀이 언급되면 findMember(name, team)로 조회한 뒤 assignee=member:<id>를 사용하세요. 미정은 unassigned:AMBIGUOUS / unassigned:NOT_FOUND / unassigned:TEAM_MISMATCH / unassigned:NONE 중 하나로 명시하세요.");
    }

}
