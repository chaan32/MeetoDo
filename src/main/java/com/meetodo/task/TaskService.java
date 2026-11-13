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
    private final java.time.Clock clock;

    public TaskService(TaskRepository tasks, MemberRepository members, TaskMentionService mentions, java.time.Clock clock) {
        this.tasks = tasks;
        this.members = members;
        this.mentions = mentions;
        this.clock = clock;
    }

    private com.meetodo.member.AdminAuthorization admins = new com.meetodo.member.AdminAuthorization("");
    private TaskEventRepository history;

    @org.springframework.beans.factory.annotation.Autowired
    public void setAdmins(com.meetodo.member.AdminAuthorization admins) { this.admins = admins; }
    @org.springframework.beans.factory.annotation.Autowired
    public void setHistory(TaskEventRepository history) { this.history = history; }

    private org.springframework.context.ApplicationEventPublisher events = event -> {};

    @org.springframework.beans.factory.annotation.Autowired
    public void setEventPublisher(org.springframework.context.ApplicationEventPublisher events) {
        this.events = events;
    }

    public TaskResponse create(CreateTaskRequest request) {
        String title = TaskTitles.validateAndClean(request.title());
        Member assignee = resolveAssignee(request.assigneeId(), request.assigneeName());
        Task existing = findExistingTodo(title, assignee);
        if (existing != null) throw new DuplicateTaskException(existing.getId());
        Task saved = tasks.save(Task.create(title, assignee, request.dueDate()));
        events.publishEvent(new TaskCreatedEvent(saved.getId()));
        return TaskResponse.from(saved);
    }

    private Member resolveAssignee(Long assigneeId, String assigneeName) {
        if (assigneeId != null) {
            Member byId = members.findById(assigneeId).filter(Member::isActive).orElseThrow(() -> new ResourceNotFoundException(
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
        return findExistingTodo(title, assignee, null);
    }

    private Task findExistingTodo(String title, Member assignee, Long excludedId) {
        List<Task> candidates = assignee == null
                ? tasks.findAllByAssigneeIsNullAndStatusOrderByCreatedAtDesc(TaskStatus.TODO)
                : tasks.findAllByAssigneeAndStatusOrderByCreatedAtDesc(assignee, TaskStatus.TODO);
        return candidates.stream().filter(task -> !task.isDeleted() && (excludedId == null || !excludedId.equals(task.getId()))).filter(task -> Task.normalizeTitle(task.getTitle())
                .equals(Task.normalizeTitle(title))).findFirst().orElse(null);
    }

    public TaskResponse createFromTool(String title, Member assignee, LocalDate dueDate, Meeting meeting) {
        return createFromToolIdempotently(title, assignee, dueDate, meeting).task();
    }

    public ToolTaskResult createFromToolIdempotently(String title, Member assignee, LocalDate dueDate, Meeting meeting) {
        return createFromToolIdempotently(title, assignee, dueDate, meeting, null);
    }

    public ToolTaskResult createFromToolIdempotently(String title, Member assignee, LocalDate dueDate, Meeting meeting, UnresolvedAssignee unresolved) {
        return createFromToolIdempotently(title, assignee, dueDate, meeting, unresolved, null);
    }

    public ToolTaskResult createFromToolIdempotently(String title, Member assignee, LocalDate dueDate, Meeting meeting,
                                                    UnresolvedAssignee unresolved, String dueDateWarning) {
        if (assignee != null && !assignee.isActive()) throw new ResourceNotFoundException("MEMBER_NOT_FOUND", "Active assignee was not found.");
        String normalizedTitle = TaskTitles.validateAndClean(title);
        Task existing = findExistingTodo(normalizedTitle, assignee);
        if (existing != null) return new ToolTaskResult(TaskResponse.from(existing), false);
        Task task = Task.create(normalizedTitle, assignee, dueDate, meeting, unresolved);
        task.setDueDateWarning(dueDateWarning);
        Task saved = tasks.save(task);
        events.publishEvent(new TaskCreatedEvent(saved.getId()));
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

    @Transactional(readOnly = true)
    public TaskResponse authorizeMutation(Long id, String actor) {
        validateActor(actor);
        Task task = tasks.findById(id).filter(value -> !value.isDeleted()).orElseThrow(() ->
                new ResourceNotFoundException("TASK_NOT_FOUND", "Task was not found."));
        requirePermission(task, actor);
        return response(task);
    }

    public TaskResponse update(Long id, com.meetodo.task.dto.UpdateTaskRequest request, String actor) {
        if ((!request.isTitleProvided() && !request.isDueDateProvided() && !request.isStatusProvided())
                || request.isTitleProvided() && request.getTitle() == null
                || request.isStatusProvided() && request.getStatus() == null)
            throw invalid("VALIDATION_ERROR", "수정할 제목·마감일·상태를 올바르게 입력하세요.");
        return mutate(id, request.getTitle(), request.isDueDateProvided(), request.getDueDate(), request.getStatus(), actor);
    }

    public TaskResponse edit(Long id, String title, boolean dueDateProvided, LocalDate dueDate, String actor) {
        return mutate(id, title, dueDateProvided, dueDate, null, actor);
    }

    public TaskResponse reopen(Long id, String actor) {
        return mutate(id, null, false, null, TaskStatus.TODO, actor);
    }

    private TaskResponse mutate(Long id, String title, boolean dueDateProvided, LocalDate dueDate, TaskStatus status, String actor) {
        validateActor(actor);
        Task task = lockedTask(id);
        if (status == TaskStatus.DONE) requireCompletionPermission(task, actor);
        requirePermission(task, actor);
        String nextTitle = title == null ? task.getTitle() : TaskTitles.validateAndClean(title);
        LocalDate nextDate = dueDateProvided ? dueDate : task.getDueDate();
        TaskStatus nextStatus = status == null ? task.getStatus() : status;
        if (dueDateProvided && dueDate != null && dueDate.isBefore(LocalDate.now(clock.withZone(java.time.ZoneId.of("Asia/Seoul")))))
            throw invalid("INVALID_DUE_DATE", "마감일은 오늘 이후여야 합니다.");
        boolean titleChanged = !java.util.Objects.equals(nextTitle, task.getTitle());
        boolean dateChanged = !java.util.Objects.equals(nextDate, task.getDueDate());
        boolean statusChanged = nextStatus != task.getStatus();
        boolean warningCleared = dueDateProvided && task.getDueDateWarning() != null;
        if (nextStatus == TaskStatus.TODO && (titleChanged || statusChanged)) {
            Task duplicate = findExistingTodo(nextTitle, task.getAssignee(), task.getId());
            if (duplicate != null) throw new DuplicateTaskException(duplicate.getId());
        }
        if (titleChanged) record(task, "TITLE_CHANGED", task.getTitle(), nextTitle, actor);
        if (dateChanged) record(task, "DUE_DATE_CHANGED", text(task.getDueDate()), text(nextDate), actor);
        if (statusChanged) record(task, "STATUS_CHANGED", task.getStatus().name(), nextStatus.name(), actor);
        if (warningCleared) record(task, "DUE_DATE_WARNING_CLEARED", task.getDueDateWarning(), "MANUAL_DUE_DATE", actor);
        if (titleChanged || dateChanged || statusChanged || warningCleared) {
            if (warningCleared) task.setDueDateWarning(null);
            task.edit(nextTitle, nextDate);
            task.changeStatus(nextStatus, actor, clock.instant());
            tasks.save(task);
            events.publishEvent(new TaskChangedEvent(task.getId()));
        }
        return response(task);
    }

    public TaskResponse delete(Long id, String actor) {
        validateActor(actor);
        Task task = lockedTask(id);
        requirePermission(task, actor);
        record(task, "DELETED", "false", "true", actor);
        task.softDelete();
        tasks.save(task);
        events.publishEvent(new TaskChangedEvent(task.getId()));
        return response(task);
    }

    private Task lockedTask(Long id) {
        return tasks.findByIdForUpdate(id).filter(task -> !task.isDeleted()).orElseThrow(() ->
                new ResourceNotFoundException("TASK_NOT_FOUND", "Task was not found."));
    }

    private void validateActor(String actor) {
        if (actor == null || !actor.matches("[UW][A-Z0-9]{1,79}"))
            throw invalid("INVALID_SLACK_USER_ID", "A valid Slack user ID is required.");
    }

    private void requirePermission(Task task, String actor) {
        if (task.getAssignee() != null && !actor.equals(task.getAssignee().getSlackUserId()) && !admins.isAdmin(actor))
            throw new TaskMutationException(org.springframework.http.HttpStatus.FORBIDDEN, "TASK_MUTATION_FORBIDDEN", "담당자 또는 관리자만 변경할 수 있어요.");
    }

    private TaskMutationException invalid(String code, String message) {
        return new TaskMutationException(org.springframework.http.HttpStatus.BAD_REQUEST, code, message);
    }

    private TaskResponse response(Task task) {
        return TaskResponse.from(task, countsFor(List.of(task)).getOrDefault(task.getId(), 0L));
    }

    private String text(Object value) { return value == null ? null : value.toString(); }

    private void record(Task task, String type, String before, String after, String actor) {
        if (history != null) history.save(new TaskEvent(task, type, before, after, actor, clock.instant()));
    }

    private void changeStatus(Task task, TaskStatus status, String actor) {
        if (task.getStatus() == status) return;
        if (status == TaskStatus.TODO) {
            Task duplicate = findExistingTodo(task.getTitle(), task.getAssignee(), task.getId());
            if (duplicate != null) throw new DuplicateTaskException(duplicate.getId());
        }
        record(task, "STATUS_CHANGED", task.getStatus().name(), status.name(), actor);
        task.changeStatus(status, actor, clock.instant());
        tasks.save(task);
        events.publishEvent(new TaskChangedEvent(task.getId()));
    }

    public TaskResponse assign(Long id, Long memberId, String actorSlackUserId) {
        if (actorSlackUserId == null || !actorSlackUserId.matches("[UW][A-Z0-9]{1,79}"))
            throw new TaskAssignmentException("INVALID_SLACK_USER_ID", "A valid Slack user ID is required.");
        Task task = lockedTask(id);
        if (task.getAssignee() != null || task.getStatus() == TaskStatus.DONE)
            return TaskResponse.from(task, countsFor(List.of(task)).getOrDefault(task.getId(), 0L));
        Member member = assignmentMember(task, memberId);
        record(task, "ASSIGNED", null, member.getId().toString(), actorSlackUserId);
        task.assign(member, actorSlackUserId, clock.instant());
        tasks.save(task);
        events.publishEvent(new TaskAssignedEvent(task.getId(), actorSlackUserId));
        return TaskResponse.from(task, countsFor(List.of(task)).getOrDefault(task.getId(), 0L));
    }

    public TaskResponse assignOrReassign(Long id, Long memberId, String actorSlackUserId) {
        validateActor(actorSlackUserId);
        Task task = lockedTask(id);
        if (task.getStatus() != TaskStatus.TODO)
            throw new TaskMutationException(org.springframework.http.HttpStatus.CONFLICT,
                    "TASK_NOT_ASSIGNABLE", "미완료 업무만 담당자를 지정할 수 있어요.");
        Member previous = task.getAssignee();
        if (previous == null) return assign(id, memberId, actorSlackUserId);
        if (!admins.isAdmin(actorSlackUserId))
            throw new TaskMutationException(org.springframework.http.HttpStatus.FORBIDDEN,
                    "TASK_REASSIGNMENT_FORBIDDEN", "관리자만 담당자를 변경할 수 있어요.");
        if (memberId == null || memberId <= 0)
            throw new TaskAssignmentException("VALIDATION_ERROR", "assigneeId must be positive.");
        Member member = resolveAssignee(memberId, null);
        if (previous.getId().equals(memberId)) return response(task);
        Task duplicate = findExistingTodo(task.getTitle(), member, task.getId());
        if (duplicate != null) throw new DuplicateTaskException(duplicate.getId());
        record(task, "REASSIGNED", previous.getId().toString(), memberId.toString(), actorSlackUserId);
        task.assign(member, actorSlackUserId, clock.instant());
        tasks.save(task);
        events.publishEvent(new TaskReassignedEvent(task.getId(), previous.getId(), previous.getSlackUserId(),
                memberId, actorSlackUserId));
        return response(task);
    }

    private Member assignmentMember(Task task, Long memberId) {
        if (memberId == null || memberId <= 0)
            throw new TaskAssignmentException("VALIDATION_ERROR", "assigneeId must be positive.");
        Member member = resolveAssignee(memberId, null);
        UnresolvedAssignee snapshot = task.getUnresolvedAssignee();
        if (snapshot != null && ("AMBIGUOUS".equals(snapshot.reason()) || "TEAM_MISMATCH".equals(snapshot.reason()))
                && snapshot.candidates().stream().noneMatch(candidate -> memberId.equals(candidate.id())))
            throw new TaskAssignmentException("ASSIGNEE_NOT_CANDIDATE", "저장된 후보 중에서 담당자를 선택하세요.");
        Task duplicate = findExistingTodo(task.getTitle(), member, task.getId());
        if (duplicate != null) throw new DuplicateTaskException(duplicate.getId());
        return member;
    }

    private void requireCompletionPermission(Task task, String actor) {
        if (task.getAssignee() != null && !actor.equals(task.getAssignee().getSlackUserId()))
            throw new TaskCompletionForbiddenException();
    }

    public TaskResponse completeFromSlack(Long id, String actorSlackUserId) {
        if (actorSlackUserId == null || !actorSlackUserId.matches("[UW][A-Z0-9]{1,79}"))
            throw new IllegalArgumentException("A valid Slack user ID is required");
        Task task = lockedTask(id);
        requireCompletionPermission(task, actorSlackUserId);
        changeStatus(task, TaskStatus.DONE, actorSlackUserId);
        return TaskResponse.from(task, countsFor(List.of(task)).getOrDefault(task.getId(), 0L));
    }

    private Map<Long, Long> countsFor(List<Task> taskList) {
        Map<Long, Long> counts = mentions.counts(taskList.stream().map(Task::getId).toList());
        return counts == null ? Map.of() : counts;
    }
}
