package com.meetodo.task;

import java.time.Instant;
import java.time.LocalDate;
import java.util.Locale;

import com.meetodo.member.Member;
import com.meetodo.meeting.Meeting;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;

@Entity
@Table(name = "tasks")
public class Task {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, length = 200)
    private String title;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "assignee_id")
    private Member assignee;

    @Column(columnDefinition = "text")
    @jakarta.persistence.Convert(converter = UnresolvedAssignee.JsonConverter.class)
    private UnresolvedAssignee unresolvedAssignee;

    @Column(name = "due_date")
    private LocalDate dueDate;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private TaskStatus status;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "meeting_id")
    private Meeting meeting;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "completed_by_slack_user_id", length = 80)
    private String completedBySlackUserId;

    @Column(name = "completed_at")
    private Instant completedAt;

    protected Task() {
    }

    private Task(String title, Member assignee, LocalDate dueDate) {
        this.title = title;
        this.assignee = assignee;
        this.dueDate = dueDate;
        this.status = TaskStatus.TODO;
    }

    public static Task create(String title, Member assignee, LocalDate dueDate) {
        return create(title, assignee, dueDate, null);
    }

    public static Task create(String title, Member assignee, LocalDate dueDate, Meeting meeting) {
        Task task = new Task(title, assignee, dueDate);
        task.meeting = meeting;
        return task;
    }

    public static Task create(String title, Member assignee, LocalDate dueDate, Meeting meeting, UnresolvedAssignee unresolved) {
        Task task = create(title, assignee, dueDate, meeting);
        task.unresolvedAssignee = assignee == null ? unresolved : null;
        return task;
    }

    public UnresolvedAssignee getUnresolvedAssignee() { return unresolvedAssignee; }

    @PrePersist
    void setCreatedAt() {
        if (createdAt == null) createdAt = Instant.now();
    }

    public void changeStatus(TaskStatus status) {
        changeStatus(status, null, Instant.now());
    }

    public void changeStatus(TaskStatus status, String actorSlackUserId, Instant at) {
        if (this.status == status) return;
        this.status = status;
        completedBySlackUserId = status == TaskStatus.DONE ? actorSlackUserId : null;
        completedAt = status == TaskStatus.DONE ? java.util.Objects.requireNonNull(at) : null;
    }

    public String getCompletedBySlackUserId() { return completedBySlackUserId; }
    public Instant getCompletedAt() { return completedAt; }

    // Used by focused service tests to represent a persisted row.
    Task withId(Long id, Instant createdAt) {
        this.id = id;
        this.createdAt = createdAt;
        return this;
    }

    public Long getId() { return id; }
    public static String normalizeTitle(String title) {
        return title == null ? "" : title.strip().replaceAll("\\s+", " ").toLowerCase(Locale.ROOT);
    }
    public String getTitle() { return title; }
    public Member getAssignee() { return assignee; }
    public LocalDate getDueDate() { return dueDate; }
    public TaskStatus getStatus() { return status; }
    public Meeting getMeeting() { return meeting; }
    public Long getMeetingId() { return meeting == null ? null : meeting.getId(); }
    public Instant getCreatedAt() { return createdAt; }
}
