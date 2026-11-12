package com.meetodo.tools;

import java.util.List;
import java.util.Map;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.meetodo.member.Member;
import com.meetodo.member.MemberNames;
import com.meetodo.member.MemberRepository;
import com.meetodo.meeting.Meeting;
import com.meetodo.task.Task;
import com.meetodo.task.TaskRepository;
import com.meetodo.task.TaskMentionService;
import com.meetodo.task.TaskStatus;
import org.springframework.ai.chat.model.ToolContext;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.stereotype.Component;

@Component
public class FindTasksTool {

    private final MemberRepository members;
    private final TaskRepository tasks;
    private final ToolExecutionService execution;
    private final TaskMentionService mentions;
    private final ObjectMapper mapper = new ObjectMapper();

    public FindTasksTool(MemberRepository members, TaskRepository tasks, ToolExecutionService execution,
                         TaskMentionService mentions) {
        this.members = members;
        this.tasks = tasks;
        this.execution = execution;
        this.mentions = mentions;
    }

    @Tool(name = "findTasks", description = "모든 회의에서 같은 담당자 ID와 정규화 제목의 미완료(TODO) 할 일을 찾는다. 담당자 미정은 assigneeId를 null로 전달한다.")
    public ToolResult invokeFindTasks(@ToolParam(description = "findMember 후보의 ID. 담당자 미정이면 null") Long assigneeId,
                                     @ToolParam(description = "담당자 이름. 미정이면 빈 값") String assigneeName,
                                     @ToolParam(description = "createTask와 같은 짧은 명사형 업무 제목. 예: API 명세 정리, 배포 로그 정리. 담당자·마감일·문장 어미·끝 마침표 제외") String title,
                                     @ToolParam(description = "담당자 미정인 경우 AMBIGUOUS / NOT_FOUND / TEAM_MISMATCH / NONE. 배정된 경우 생략", required = false) String unassignedReason,
                                     ToolContext toolContext) {
        return findTasks(assigneeId, assigneeName, title, unassignedReason, execution.meetingFrom(toolContext));
    }

    public ToolResult findTasks(String assignee, String title, Meeting meeting) {
        return findTasks(null, assignee, title, meeting);
    }

    public ToolResult findTasks(Long assigneeId, String assigneeName, String title, Meeting meeting) {
        return findTasks(assigneeId, assigneeName, title, null, meeting);
    }

    public ToolResult findTasks(Long assigneeId, String assigneeName, String title, String unassignedReason, Meeting meeting) {
        final Long selectedId = assigneeId;
        final String name = assigneeName;
        return execution.execute(meeting, "findTasks", Map.of(
                "assigneeId", selectedId == null ? "" : selectedId,
                "assigneeName", name == null ? "" : name, "title", title == null ? "" : title,
                "unassignedReason", unassignedReason == null ? "" : unassignedReason), () -> {
            if (title == null || title.isBlank())
                return ToolResult.error("INVALID_TITLE", "중복 확인에는 할 일 제목이 필요합니다.");
            if (unassignedReason != null && !unassignedReason.isBlank()
                    && (!java.util.Set.of("AMBIGUOUS", "NOT_FOUND", "TEAM_MISMATCH", "NONE").contains(unassignedReason)
                        || selectedId != null || (name != null && !name.isBlank())))
                return ToolResult.error("INVALID_ASSIGNEE", "배정과 미정 중 하나만 선택하세요. 미정 조회에는 ID·이름을 비우고 AMBIGUOUS / NOT_FOUND / TEAM_MISMATCH / NONE 사유를 전달하세요.");
            Member member = null;
            if (selectedId != null) {
                member = members.findById(selectedId).orElse(null);
                if (member == null || (name != null && !name.isBlank() && MemberNames.find(members, name).stream().noneMatch(candidate -> candidate.getId().equals(selectedId))))
                    return ToolResult.error("INVALID_ASSIGNEE", "findMember 후보의 ID와 이름을 확인하세요.");
            } else if (name != null && !name.isBlank()) {
                List<Member> found = MemberNames.find(members, name);
                if (found.isEmpty()) return ToolResult.error("MEMBER_NOT_FOUND", "등록된 팀원을 찾지 못했습니다.");
                if (found.size() > 1) return ToolResult.error("AMBIGUOUS_MEMBER", "담당자 ID로 지정해야 합니다.");
                member = found.getFirst();
            }
            if (member != null && execution.hasConflictingTeamLookup(meeting, member))
                return ToolResult.error("INVALID_ASSIGNEE_TEAM", ToolExecutionService.TEAM_MISMATCH_MESSAGE);
            if (member != null && !execution.isMemberAuthorized(meeting, member.getId()))
                return ToolResult.error("INVALID_ASSIGNEE", "현재 회의에서 단일 후보로 확인된 ID만 담당자 조회에 사용할 수 있습니다. 동명이인을 확정하지 못하면 미정으로 조회하세요.");
            List<Task> candidates = member == null
                    ? tasks.findAllByAssigneeIsNullAndStatusOrderByCreatedAtDesc(TaskStatus.TODO)
                    : tasks.findAllByAssigneeAndStatusOrderByCreatedAtDesc(member, TaskStatus.TODO);
            List<Task> matches = candidates.stream()
                    .filter(task -> Task.normalizeTitle(task.getTitle()).equals(Task.normalizeTitle(title))).toList();
            matches.stream().filter(task -> task.getId() != null)
                    .forEach(task -> mentions.record(task.getId(), meeting.getId()));
            try {
                return ToolResult.success(matches.isEmpty() ? "중복된 미완료 할 일이 없습니다."
                                : "기존 할 일과 같음 (EXISTING). 미완료 작업을 재사용하세요.",
                        mapper.writeValueAsString(matches.stream().map(task -> TaskView.from(task, unassignedReason)).toList()));
            } catch (Exception exception) {
                return ToolResult.error("TOOL_EXECUTION_FAILED", "조회 결과를 만들지 못했습니다.");
            }
        });
    }

    private record TaskView(Long id, String title, Long assigneeId, String assignee, String team,
                            String dueDate, String status, String resultType, String unassignedReason, String warning) {
        static TaskView from(Task task, String reason) {
            return new TaskView(task.getId(), task.getTitle(),
                    task.getAssignee() == null ? null : task.getAssignee().getId(),
                    task.getAssignee() == null ? null : task.getAssignee().getName(),
                    task.getAssignee() == null ? null : task.getAssignee().getTeam(),
                    task.getDueDate() == null ? null : task.getDueDate().toString(), task.getStatus().name(), "EXISTING", task.getAssignee() == null ? reason : null,
                    task.getAssignee() != null || reason == null ? "" : switch (reason) {
                        case "AMBIGUOUS" -> "동명이인 확인 필요";
                        case "NOT_FOUND" -> "등록되지 않은 담당자";
                        case "TEAM_MISMATCH" -> "팀 불일치 확인 필요";
                        default -> "";
                    });
        }
    }
}
