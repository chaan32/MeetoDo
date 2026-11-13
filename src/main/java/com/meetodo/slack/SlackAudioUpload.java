package com.meetodo.slack;

import jakarta.persistence.*;
import java.time.Instant;

@Entity
@Table(name = "slack_audio_uploads")
public class SlackAudioUpload {
    public enum Status { PROCESSING, DONE, FAILED }

    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    @Column(name = "file_id", nullable = false, unique = true, length = 80)
    private String fileId;
    @Enumerated(EnumType.STRING) @Column(nullable = false, length = 20)
    private Status status = Status.PROCESSING;
    @Column(name = "meeting_id") private Long meetingId;
    @Column(name = "error_code", length = 80) private String errorCode;
    @Column(name = "created_at", nullable = false) private Instant createdAt;

    protected SlackAudioUpload() { }
    SlackAudioUpload(String fileId, Instant createdAt) {
        this.fileId = fileId; this.createdAt = createdAt;
    }
    void finish(Long meetingId, String errorCode) {
        this.meetingId = meetingId; this.errorCode = errorCode;
        this.status = errorCode == null ? Status.DONE : Status.FAILED;
    }
    public Long getMeetingId() { return meetingId; }
    public Status getStatus() { return status; }
    public String getErrorCode() { return errorCode; }
}
