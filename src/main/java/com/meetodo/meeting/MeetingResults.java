package com.meetodo.meeting;

import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.meetodo.meeting.dto.MeetingHistoryResponse;
import com.meetodo.meeting.dto.MeetingResponse;
import com.meetodo.meeting.dto.ToolCallLogResponse;
import com.meetodo.task.Task;
import com.meetodo.task.TaskMentionService;
import com.meetodo.task.TaskRepository;
import org.springframework.stereotype.Service;

@Service
public class MeetingResults {
    private final MeetingRepository meetings;
    private final TaskRepository tasks;
    private final TaskMentionService toolMentions;
    private final ToolCallLogRepository logs;
    private final ObjectMapper mapper;

    public MeetingResults(MeetingRepository meetings, TaskRepository tasks, TaskMentionService toolMentions,
                          ToolCallLogRepository logs, ObjectMapper mapper) {
        this.meetings = meetings; this.tasks = tasks; this.toolMentions = toolMentions;
        this.logs = logs; this.mapper = mapper;
    }

    public MeetingResponse get(Long id) {
        Meeting meeting = meetings.findById(id).orElseThrow(MeetingNotFoundException::new);
        return response(meeting);
    }

    public List<MeetingHistoryResponse> recent() {
        return meetings.findTop5ByOrderByCreatedAtDesc().stream()
                .map(meeting -> MeetingHistoryResponse.from(meeting, response(meeting)))
                .toList();
    }

    public List<ToolCallLogResponse> logs(Long id) {
        if (!meetings.existsById(id)) throw new MeetingNotFoundException();
        return logs.findAllByMeetingIdOrderByCallOrderAsc(id).stream().map(ToolCallLogResponse::from).toList();
    }

    private MeetingResponse response(Meeting meeting) {
        Map<Long, Task> meetingTasks = new LinkedHashMap<>();
        Map<Long, String> resultTypes = new HashMap<>();
        tasks.findAllByMeeting_IdOrderByCreatedAtDesc(meeting.getId()).forEach(task -> {
            meetingTasks.put(task.getId(), task);
            resultTypes.put(task.getId(), "CREATED");
        });
        toolMentions.findAllForMeeting(meeting.getId()).forEach(mention -> {
            Task task = mention.getTask();
            meetingTasks.putIfAbsent(task.getId(), task);
            resultTypes.putIfAbsent(task.getId(),
                    meeting.getId().equals(task.getMeetingId()) ? "CREATED" : "EXISTING");
        });
        Set<Long> reusedTaskIds = existingTaskIds(meeting.getId());
        if (!reusedTaskIds.isEmpty()) {
            tasks.findAllWithAssigneeByIdIn(reusedTaskIds).forEach(task -> {
                meetingTasks.putIfAbsent(task.getId(), task);
                resultTypes.putIfAbsent(task.getId(), "EXISTING");
            });
        }
        Map<Long, Long> mentionCounts = toolMentions.counts(meetingTasks.keySet());
        Map<Long, String> warnings = new HashMap<>();
        Map<Long, String> reasons = new HashMap<>();
        for (ToolCallLog log : logs.findAllByMeetingIdOrderByCallOrderAsc(meeting.getId())) {
            if (!log.getSuccess() || (!"createTask".equals(log.getToolName()) && !"findTasks".equals(log.getToolName()))) continue;
            try {
                JsonNode data = toolData(log);
                Iterable<JsonNode> items = data.isArray() ? data : List.of(data);
                for (JsonNode item : items) {
                    String warning = item.path("warning").asText("");
                    String reason = item.path("unassignedReason").asText("");
                    if (item.path("id").canConvertToLong()) {
                        if (!reason.isBlank()) reasons.put(item.path("id").asLong(), reason);
                        if (!warning.isBlank()) warnings.put(item.path("id").asLong(), warning);
                    }
                }
            } catch (Exception ignored) { }
        }
        return MeetingResponse.from(meeting, List.copyOf(meetingTasks.values()), resultTypes, mentionCounts, warnings, reasons);
    }

    private Set<Long> existingTaskIds(Long meetingId) {
        Set<Long> ids = new HashSet<>();
        for (ToolCallLog log : logs.findAllByMeetingIdOrderByCallOrderAsc(meetingId)) {
            if (!log.getSuccess() || log.getResult() == null) continue;
            try {
                JsonNode data = toolData(log);
                if ("findTasks".equals(log.getToolName()) && data.isArray()) {
                    data.forEach(task -> addExistingTaskId(task, ids));
                } else if ("createTask".equals(log.getToolName())) {
                    addExistingTaskId(data, ids);
                }
            } catch (Exception ignored) {
                // A malformed or truncated tool result should not make meeting retrieval fail.
            }
        }
        return ids;
    }

    Meeting existingTaskSourceMeeting(Long meetingId) {
        Set<Long> existingIds = new HashSet<>();
        boolean sawTaskLookup = false;
        for (ToolCallLog log : logs.findAllByMeetingIdOrderByCallOrderAsc(meetingId)) {
            String toolName = log.getToolName();
            if (!"findTasks".equals(toolName) && !"createTask".equals(toolName)) continue;
            sawTaskLookup = true;
            if (!log.getSuccess() || log.getResult() == null) return null;
            try {
                JsonNode data = toolData(log);
                if ("createTask".equals(toolName) && "CREATED".equals(data.path("resultType").asText()))
                    return null;
                if ("findTasks".equals(toolName) && data.isArray()) {
                    data.forEach(task -> addExistingTaskId(task, existingIds));
                } else if ("createTask".equals(toolName)) {
                    addExistingTaskId(data, existingIds);
                }
            } catch (Exception ignored) {
                return null;
            }
        }
        if (!sawTaskLookup || existingIds.isEmpty()) return null;

        List<Task> existingTasks = tasks.findAllWithAssigneeByIdIn(existingIds);
        if (existingTasks.size() != existingIds.size()) return null;
        Set<Long> sourceMeetingIds = new HashSet<>();
        existingTasks.stream().map(Task::getMeetingId).forEach(sourceMeetingIds::add);
        if (sourceMeetingIds.size() != 1 || sourceMeetingIds.contains(null)) return null;

        Long sourceMeetingId = sourceMeetingIds.iterator().next();
        Set<Long> sourceTaskIds = tasks.findAllByMeeting_IdOrderByCreatedAtDesc(sourceMeetingId).stream()
                .map(Task::getId).collect(java.util.stream.Collectors.toSet());
        if (!sourceTaskIds.equals(existingIds)) return null;
        return meetings.findById(sourceMeetingId).orElse(null);
    }

    private JsonNode toolData(ToolCallLog log) throws Exception {
        JsonNode result = mapper.readTree(log.getResult());
        return mapper.readTree(result.path("data").asText());
    }

    private void addExistingTaskId(JsonNode task, Set<Long> ids) {
        if ("EXISTING".equals(task.path("resultType").asText())
                && task.path("id").canConvertToLong()) {
            ids.add(task.path("id").asLong());
        }
    }

}
