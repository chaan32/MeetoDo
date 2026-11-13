package com.meetodo.task;

import jakarta.persistence.*;
import java.time.Instant;

@Entity
@Table(name = "task_events")
public class TaskEvent {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY) private Long id;
    @ManyToOne(fetch = FetchType.LAZY) @JoinColumn(name = "task_id", nullable = false) private Task task;
    @Column(nullable = false, length = 40) private String type;
    @Column(columnDefinition = "text") private String beforeValue;
    @Column(columnDefinition = "text") private String afterValue;
    @Column(length = 80) private String bySlackUserId;
    @Column(nullable = false) private Instant at;
    protected TaskEvent() {}
    public TaskEvent(Task task, String type, String beforeValue, String afterValue, String bySlackUserId, Instant at) {
        this.task = task; this.type = type; this.beforeValue = beforeValue; this.afterValue = afterValue;
        this.bySlackUserId = bySlackUserId; this.at = at;
    }
    public Long getId() { return id; }
    public Task getTask() { return task; }
    public String getType() { return type; }
    public String getBeforeValue() { return beforeValue; }
    public String getAfterValue() { return afterValue; }
    public String getBySlackUserId() { return bySlackUserId; }
    public Instant getAt() { return at; }
}
