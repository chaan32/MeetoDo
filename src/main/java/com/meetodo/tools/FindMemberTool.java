package com.meetodo.tools;

import java.util.Map;
import java.util.List;
import java.util.LinkedHashMap;

import com.meetodo.member.Member;
import com.meetodo.member.MemberRepository;
import com.meetodo.meeting.Meeting;
import org.springframework.ai.chat.model.ToolContext;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.stereotype.Component;

@Component
public class FindMemberTool {

    private final MemberRepository members;
    private final ToolExecutionService execution;

    public FindMemberTool(MemberRepository members, ToolExecutionService execution) {
        this.members = members;
        this.execution = execution;
    }

    @Tool(name = "findMember", description = "팀원을 이름과 선택 팀으로 조회한다. 회의록에 팀 이름이 실제로 있으면 첫 호출부터 team을 함께 전달한다. 팀 접미사·대소문자·공백 차이는 허용하며 역할만으로 팀을 추측하지 않는다. 미정으로 등록할 때는 이 결과의 lookupCallOrder 값을 createTask에 그대로 전달한다.")
    public ToolResult invokeFindMember(@ToolParam(description = "조회할 팀원의 이름") String name,
                                       @ToolParam(description = "회의록에 명시된 팀. 없으면 필드 생략. 빈 문자열·공백은 오류", required = false) String team,
                                       ToolContext toolContext) {
        return findMember(name, team, execution.meetingFrom(toolContext));
    }

    public ToolResult findMember(String name, Meeting meeting) {
        return findMember(name, null, meeting);
    }

    public ToolResult findMember(String name, String team, Meeting meeting) {
        Map<String, String> arguments = new java.util.LinkedHashMap<>();
        arguments.put("name", name == null ? "" : name);
        if (team != null) arguments.put("team", team);
        return execution.execute(meeting, "findMember", arguments, () -> {
            if (name == null || name.isBlank())
                return ToolResult.error("INVALID_MEMBER_NAME", "팀원 이름을 입력해야 합니다.");
            if (team != null && team.isBlank())
                return ToolResult.error("INVALID_MEMBER_TEAM", "team은 빈 문자열이나 공백일 수 없습니다. 팀 조건이 없으면 필드를 생략하세요.");
            if (team != null && TeamNames.normalize(team).isEmpty())
                return ToolResult.error("INVALID_MEMBER_TEAM", "팀 이름을 입력해야 합니다. 팀 조건이 없으면 필드를 생략하세요.");
            if (team != null && !TeamNames.mentionedIn(team, meeting.getRawText()))
                return ToolResult.error("TEAM_NOT_IN_TRANSCRIPT", "회의록에 언급되지 않은 팀으로 조회할 수 없습니다. 팀 언급이 없으면 담당자 미정으로 처리하세요.");
            String normalized = name.trim();
            var candidates = members.findAllByNameOrderByIdAsc(normalized);
            if (candidates.isEmpty()) return ToolResult.error("MEMBER_NOT_FOUND", "등록된 팀원을 찾지 못했습니다.");
            if (team != null && !team.isBlank()) {
                var matchingTeam = candidates.stream().filter(m -> TeamNames.normalize(m.getTeam()).equals(TeamNames.normalize(team))).toList();
                if (matchingTeam.isEmpty()) return new ToolResult(false, "MEMBER_TEAM_NOT_FOUND",
                        "등록된 " + normalized + "의 팀은 " + candidates.stream().map(Member::getTeam).distinct().collect(java.util.stream.Collectors.joining(", "))
                                + "입니다. 회의록의 팀(" + team.trim() + ")과 다릅니다. 같은 사람인지 확신할 수 없으므로 unassigned:TEAM_MISMATCH로 등록하세요.",
                        candidates.stream().map(this::toData).collect(java.util.stream.Collectors.joining(",", "[", "]")));
                candidates = matchingTeam;
            }
            String data = candidates.stream().map(this::toData).collect(java.util.stream.Collectors.joining(",", "[", "]"));
            if (candidates.size() > 1) return ToolResult.success(team == null
                    ? ambiguousMessage(candidates, meeting.getRawText())
                    : "AMBIGUOUS: 동명이인이 있습니다. 같은 팀에서도 여러 명이면 확정하지 마세요. unassigned:AMBIGUOUS로 등록하세요.", data);
            return ToolResult.success(candidates.getFirst().getName() + " 팀원을 찾았습니다.", data);
        });
    }

    private String ambiguousMessage(List<Member> candidates, String transcript) {
        Map<String, String> teams = new LinkedHashMap<>();
        for (Member candidate : candidates)
            teams.putIfAbsent(TeamNames.normalize(candidate.getTeam()), candidate.getTeam());
        List<String> mentioned = teams.values().stream()
                .filter(team -> TeamNames.mentionedIn(team, transcript)).toList();
        if (mentioned.isEmpty())
            return "AMBIGUOUS: 회의록에서 후보 팀(" + String.join(", ", teams.values())
                    + ")이 언급되지 않았습니다. unassigned:AMBIGUOUS로 등록하세요.";
        if (mentioned.size() == 1)
            return "AMBIGUOUS: 회의록에 " + mentioned.getFirst() + "이 언급되어 있습니다. team="
                    + mentioned.getFirst() + "으로 재조회하세요.";
        return "AMBIGUOUS: 회의록에 여러 후보 팀이 언급되어 있습니다. 문맥으로 확실히 구분할 수 없으면 unassigned:AMBIGUOUS로 등록하세요.";
    }

    private String toData(Member member) {
        return "{\"id\":" + member.getId() + ",\"name\":\"" + escape(member.getName()) + "\",\"team\":\""
                + escape(member.getTeam()) + "\",\"slackUserId\":"
                + (member.getSlackUserId() == null ? "null" : "\"" + escape(member.getSlackUserId()) + "\"") + "}";
    }

    private String escape(String value) {
        return value.replace("\\", "\\\\").replace("\"", "\\\"");
    }
}
