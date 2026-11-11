package com.meetodo.task;

import java.time.Instant;

import com.meetodo.meeting.Meeting;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;

@Entity
@Table(name = "task_mentions", uniqueConstraints =
        @UniqueConstraint(name = "uk_task_mentions_task_meeting", columnNames = {"task_id", "meeting_id"}))
public class TaskMention {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "task_id", nullable = false)
    private Task task;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "meeting_id", nullable = false)
    private Meeting meeting;

    @Column(name = "mentioned_at", nullable = false, updatable = false)
    private Instant mentionedAt;

    protected TaskMention() {
    }

    private TaskMention(Task task, Meeting meeting, Instant mentionedAt) {
        if (task == null || meeting == null || mentionedAt == null)
            throw new IllegalArgumentException("task, meeting and mentionedAt are required");
        this.task = task;
        this.meeting = meeting;
        this.mentionedAt = mentionedAt;
    }

    public static TaskMention create(Task task, Meeting meeting, Instant mentionedAt) {
        return new TaskMention(task, meeting, mentionedAt);
    }

    public Long getId() { return id; }
    public Task getTask() { return task; }
    public Meeting getMeeting() { return meeting; }
    public Instant getMentionedAt() { return mentionedAt; }
}
