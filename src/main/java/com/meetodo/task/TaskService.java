package com.meetodo.task;

import java.util.List;
import java.time.LocalDate;
import java.util.Map;

import com.meetodo.member.Member;
import com.meetodo.member.MemberNames;
import com.meetodo.member.MemberRepository;
import com.meetodo.meeting.Meeting;
import com.meetodo.task.dto.CreateTaskRequest;
import com.meetodo.task.dto.TaskResponse;
import com.meetodo.task.dto.UpdateTaskStatusRequest;
import com.meetodo.task.exception.ResourceNotFoundException;
import com.meetodo.task.exception.AmbiguousMemberException;
import com.meetodo.task.exception.DuplicateTaskException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

@Service
@Transactional
public class TaskService {

    private final TaskRepository tasks;
    private final MemberRepository members;
    private final TaskMentionService mentions;

    public TaskService(TaskRepository tasks, MemberRepository members, TaskMentionService mentions) {
        this.tasks = tasks;
        this.members = members;
        this.mentions = mentions;
    }

    public TaskResponse create(CreateTaskRequest request) {
        Member assignee = resolveAssignee(request.assigneeId(), request.assigneeName());
        Task existing = findExistingTodo(request.title(), assignee);
        if (existing != null) throw new DuplicateTaskException(existing.getId());
        return TaskResponse.from(tasks.save(Task.create(request.title().trim(), assignee, request.dueDate())));
    }

    private Member resolveAssignee(Long assigneeId, String assigneeName) {
        if (assigneeId != null) {
            Member byId = members.findById(assigneeId).orElseThrow(() -> new ResourceNotFoundException(
                    "MEMBER_NOT_FOUND", "Assignee was not found."));
            return byId;
        }
        if (!StringUtils.hasText(assigneeName)) return null;
        List<Member> matches = MemberNames.find(members, assigneeName);
        if (matches.isEmpty()) throw new ResourceNotFoundException("MEMBER_NOT_FOUND", "Assignee was not found.");
        if (matches.size() > 1) throw new AmbiguousMemberException(matches);
        return matches.getFirst();
    }

    private Task findExistingTodo(String title, Member assignee) {
        List<Task> candidates = assignee == null
                ? tasks.findAllByAssigneeIsNullAndStatusOrderByCreatedAtDesc(TaskStatus.TODO)
                : tasks.findAllByAssigneeAndStatusOrderByCreatedAtDesc(assignee, TaskStatus.TODO);
        return candidates.stream().filter(task -> Task.normalizeTitle(task.getTitle())
                .equals(Task.normalizeTitle(title))).findFirst().orElse(null);
    }

    public TaskResponse createFromTool(String title, Member assignee, LocalDate dueDate, Meeting meeting) {
        return createFromToolIdempotently(title, assignee, dueDate, meeting).task();
    }

    public ToolTaskResult createFromToolIdempotently(String title, Member assignee, LocalDate dueDate, Meeting meeting) {
        return createFromToolIdempotently(title, assignee, dueDate, meeting, null);
    }

    public ToolTaskResult createFromToolIdempotently(String title, Member assignee, LocalDate dueDate, Meeting meeting, UnresolvedAssignee unresolved) {
        String normalizedTitle = title.trim();
        Task existing = findExistingTodo(normalizedTitle, assignee);
        if (existing != null) return new ToolTaskResult(TaskResponse.from(existing), false);
        Task saved = tasks.save(Task.create(normalizedTitle, assignee, dueDate, meeting, unresolved));
        return new ToolTaskResult(TaskResponse.from(saved), true);
    }

    public record ToolTaskResult(TaskResponse task, boolean created) {}

    @Transactional(readOnly = true)
    public List<TaskResponse> findAll(String assigneeName, Long assigneeId) {
        List<Task> result;
        if (assigneeId != null) {
            Member assignee = members.findById(assigneeId).orElseThrow(() -> new ResourceNotFoundException(
                    "MEMBER_NOT_FOUND", "Assignee was not found."));
            if (StringUtils.hasText(assigneeName) && MemberNames.find(members, assigneeName).stream().noneMatch(candidate -> candidate.getId().equals(assignee.getId())))
                throw new ResourceNotFoundException("ASSIGNEE_MISMATCH", "assigneeId와 assignee 이름이 일치하지 않습니다.");
            result = tasks.findAllByAssigneeOrderByCreatedAtDesc(assignee);
        } else if (!StringUtils.hasText(assigneeName)) {
            result = tasks.findAllByOrderByCreatedAtDesc();
        } else {
            List<Member> matchedMembers = MemberNames.find(members, assigneeName);
            if (matchedMembers.isEmpty()) throw new ResourceNotFoundException("MEMBER_NOT_FOUND", "Assignee was not found.");
            result = matchedMembers.stream().flatMap(member -> tasks.findAllByAssigneeOrderByCreatedAtDesc(member).stream()).toList();
        }
        Map<Long, Long> counts = countsFor(result);
        return result.stream().map(task -> TaskResponse.from(task, counts.getOrDefault(task.getId(), 0L))).toList();
    }

    public List<TaskResponse> findAll(String assigneeName) { return findAll(assigneeName, null); }

    public TaskResponse updateStatus(Long id, UpdateTaskStatusRequest request) {
        Task task = tasks.findById(id).orElseThrow(() ->
                new ResourceNotFoundException("TASK_NOT_FOUND", "Task was not found."));
        task.changeStatus(request.status());
        task = tasks.save(task);
        return TaskResponse.from(task, countsFor(List.of(task)).getOrDefault(task.getId(), 0L));
    }

    private Map<Long, Long> countsFor(List<Task> taskList) {
        Map<Long, Long> counts = mentions.counts(taskList.stream().map(Task::getId).toList());
        return counts == null ? Map.of() : counts;
    }
}
