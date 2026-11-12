package com.meetodo.tools;

import java.util.regex.Pattern;
import java.util.function.Supplier;
import java.util.ArrayList;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.meetodo.meeting.Meeting;
import com.meetodo.member.Member;
import com.meetodo.task.UnresolvedAssignee;
import com.meetodo.meeting.ToolCallLog;
import com.meetodo.meeting.ToolCallLogRepository;
import org.springframework.ai.chat.model.ToolContext;
import org.springframework.stereotype.Service;

/** Shared timing, sanitization and persistence boundary for all meeting tools. */
@Service
public class ToolExecutionService {

    private static final Pattern OPENAI_KEY = Pattern.compile("(?i)\\bsk-[A-Za-z0-9_-]{12,}\\b");
    private static final Pattern BEARER = Pattern.compile("(?i)Bearer\\s+[A-Za-z0-9._~+/-]+=*");
    private static final Pattern AUTH_FIELD = Pattern.compile("(?i)(\\\"?(?:authorization|api[_-]?key)\\\"?\\s*[:=]\\s*\\\")[^\\\"]*(\\\")");
    private static final String REDACTED = "[REDACTED]";
    private static final Pattern WEBHOOK = Pattern.compile("(?i)https?://(?:hooks\\.slack\\.com/services|(?:canary\\.|ptb\\.)?discord(?:app)?\\.com/api(?:/v\\d+)?/webhooks)/[^\\s\"<>\\\\]+");
    public static final String MEETING_CONTEXT_KEY = "meeting";

    private final ToolCallLogRepository logs;
    private final ObjectMapper mapper;
    public ToolExecutionService(ToolCallLogRepository logs, ObjectMapper mapper) {
        this.logs = logs;
        this.mapper = mapper;
    }

    public Meeting meetingFrom(ToolContext toolContext) {
        Object contextMeeting = toolContext == null ? null
                : toolContext.getContext().get(MEETING_CONTEXT_KEY);
        if (contextMeeting instanceof Meeting meeting) return meeting;
        throw new IllegalStateException("Meeting is missing from the internal tool context");
    }

    public int validationFailureCount(Long meetingId) {
        return logs.findAllByMeetingIdOrderByCallOrderAsc(meetingId).stream()
                .filter(log -> !log.getSuccess() && isValidationError(log.getErrorCode()))
                .mapToInt(ignored -> 1).sum();
    }

    public static final String TEAM_MISMATCH_MESSAGE = "회의록의 팀과 등록된 팀이 다릅니다. 같은 사람인지 확신할 수 없으므로 unassigned:TEAM_MISMATCH로 등록하세요.";

    public boolean hasConflictingTeamLookup(Meeting meeting, Member member) {
        // A later name-only lookup must not erase the earlier explicit team constraint.
        for (ToolCallLog log : logs.findAllByMeetingIdOrderByCallOrderAsc(meeting.getId())) {
            if (!"findMember".equals(log.getToolName()) || !"MEMBER_TEAM_NOT_FOUND".equals(log.getErrorCode())) continue;
            try {
                JsonNode arguments = mapper.readTree(log.getArguments());
                String name = arguments.path("name").asText("").trim();
                String team = TeamNames.normalize(arguments.path("team").asText(""));
                if (name.equals(member.getName()) && !team.isEmpty() && !team.equals(TeamNames.normalize(member.getTeam()))) return true;
            } catch (JsonProcessingException ignored) { /* Results never authorize an ID by themselves. */ }
        }
        return false;
    }

    public boolean isMemberAuthorized(Meeting meeting, Long memberId) {
        for (ToolCallLog log : logs.findAllByMeetingIdOrderByCallOrderAsc(meeting.getId())) {
            if (!log.getSuccess() || !"findMember".equals(log.getToolName()) || log.getResult() == null) continue;
            try {
                JsonNode candidates = mapper.readTree(mapper.readTree(log.getResult()).path("data").asText());
                // Multiple candidates inform the model; only a resolved single candidate grants assignment authority.
                if (candidates != null && candidates.isArray() && candidates.size() == 1) {
                    JsonNode candidate = candidates.get(0);
                    if (candidate.path("id").canConvertToLong() && candidate.path("id").asLong() == memberId) return true;
                }
            } catch (JsonProcessingException ignored) { /* Malformed/truncated results cannot authorize assignment. */ }
        }
        return false;
    }

    public UnresolvedAssignee unresolvedAssignee(Meeting meeting, Integer lookupCallOrder, String reason) {
        if (lookupCallOrder == null || lookupCallOrder <= 0) return null;
        for (ToolCallLog log : logs.findAllByMeetingIdOrderByCallOrderAsc(meeting.getId())) {
            if (log.getCallOrder() != lookupCallOrder || !"findMember".equals(log.getToolName())) continue;
            if (log.getResult() == null) return null;
            try {
                JsonNode result = mapper.readTree(log.getResult());
                JsonNode data = result.path("data").isTextual() ? mapper.readTree(result.path("data").asText()) : null;
                boolean matches = switch (reason) {
                    case "AMBIGUOUS" -> log.getSuccess() && data != null && data.isArray() && data.size() > 1;
                    case "NOT_FOUND" -> "MEMBER_NOT_FOUND".equals(log.getErrorCode());
                    case "TEAM_MISMATCH" -> "MEMBER_TEAM_NOT_FOUND".equals(log.getErrorCode());
                    default -> false;
                };
                if (!matches) return null;
                JsonNode args = mapper.readTree(log.getArguments());
                var candidates = new ArrayList<UnresolvedAssignee.Candidate>();
                if (data != null && data.isArray()) {
                    for (JsonNode candidate : data) {
                        if (candidates.size() == 20) break;
                        candidates.add(new UnresolvedAssignee.Candidate(candidate.path("id").isNumber() ? candidate.path("id").asLong() : null,
                                snapshotText(candidate.path("name").asText(), meeting), snapshotText(candidate.path("team").asText(), meeting)));
                    }
                }
                return new UnresolvedAssignee(reason, snapshotText(args.path("name").asText(), meeting),
                        args.has("team") ? snapshotText(args.path("team").asText(), meeting) : null,
                        candidates, data != null && data.isArray() ? Math.max(0, data.size() - candidates.size()) : 0);
            } catch (JsonProcessingException ignored) { return null; }
        }
        return null;
    }

    private String snapshotText(String value, Meeting meeting) {
        return sanitize(value, meeting.getRawText());
    }

    public boolean hasPastDueFailure(Meeting meeting, String title, String assignee) {
        return logs.findAllByMeetingIdOrderByCallOrderAsc(meeting.getId()).stream()
                .filter(log -> "PAST_DUE_DATE".equals(log.getErrorCode()))
                .anyMatch(log -> matchesTaskArguments(log.getArguments(), title, assignee));
    }

    private boolean matchesTaskArguments(String arguments, String title, String assignee) {
        if (arguments == null) return false;
        try {
            JsonNode json = mapper.readTree(arguments);
            String priorTitle = json.path("title").asText("").trim();
            String priorAssignee = json.path("assigneeName").asText(json.path("assignee").asText("")).trim();
            String currentAssignee = assignee == null ? "" : assignee.trim();
            return priorTitle.equalsIgnoreCase(title.trim()) && priorAssignee.equalsIgnoreCase(currentAssignee);
        } catch (JsonProcessingException exception) {
            return false;
        }
    }

    private boolean isValidationError(String errorCode) {
        return errorCode != null && (errorCode.startsWith("INVALID_")
                || errorCode.equals("TITLE_TOO_LONG")
                || errorCode.equals("PAST_DUE_DATE")
                || errorCode.equals("MEMBER_NOT_FOUND")
                || errorCode.equals("TEAM_NOT_IN_TRANSCRIPT"));
    }

    public ToolResult execute(Meeting meeting, String toolName, Object arguments, Supplier<ToolResult> action) {
        long started = System.nanoTime();
        ToolResult result;
        try {
            result = action.get();
            if (result == null) result = ToolResult.error("EMPTY_TOOL_RESULT", "도구가 결과를 반환하지 않았습니다.");
        } catch (Exception exception) {
            // Never expose provider, database or secret-bearing exception messages to the model or log row.
            result = ToolResult.error("TOOL_EXECUTION_FAILED", "도구 실행 중 오류가 발생했습니다.");
        }
        long latencyMs = Math.max(0, (System.nanoTime() - started) / 1_000_000);
        String rawText = meeting.getRawText();
        String safeArguments = sanitize(toJson(arguments), rawText);
        int callOrder = logs.findTopByMeeting_IdOrderByCallOrderDesc(meeting.getId())
                .map(previous -> previous.getCallOrder() + 1).orElse(1);
        result = new ToolResult(result.success(), result.errorCode(), sanitize(result.message(), rawText),
                sanitize(result.data(), rawText), "findMember".equals(toolName) ? Integer.valueOf(callOrder) : result.lookupCallOrder());
        String safeResult = toJson(result);
        if (result.success()) {
            logs.save(ToolCallLog.success(meeting, toolName, safeArguments, safeResult, latencyMs, callOrder));
        } else {
            logs.save(ToolCallLog.failure(meeting, toolName, safeArguments, safeResult, result.errorCode(),
                    truncate(sanitize(result.message(), rawText), 500), latencyMs, callOrder));
        }
        return result;
    }

    private String toJson(Object value) {
        try {
            return mapper.writeValueAsString(value);
        } catch (JsonProcessingException exception) {
            return "{}";
        }
    }

    private String sanitize(String value, String meetingText) {
        if (value == null) return null;
        if (meetingText != null && !meetingText.isBlank()) value = value.replace(meetingText, REDACTED);
        String redacted = WEBHOOK.matcher(value).replaceAll(REDACTED);
        redacted = OPENAI_KEY.matcher(redacted).replaceAll(REDACTED);
        redacted = BEARER.matcher(redacted).replaceAll("Bearer " + REDACTED);
        return AUTH_FIELD.matcher(redacted).replaceAll("$1" + REDACTED + "$2");
    }

    private String truncate(String value, int maxLength) {
        if (value == null || value.length() <= maxLength) return value;
        return value.substring(0, maxLength);
    }
}
