package com.meetodo.meeting;

import java.time.Duration;
import com.meetodo.notification.NotificationStatus;
import java.time.Instant;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.FetchType;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;

@Entity
@Table(name = "meetings")
public class Meeting {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "raw_text", nullable = false, length = 20000)
    private String rawText;

    @Column(name = "content_hash", nullable = false, unique = true, length = 64)
    private String contentHash;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 30)
    private MeetingSource source;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private MeetingStatus status;

    @Column(length = 4000)
    private String summary;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "duplicate_of_meeting_id")
    private Meeting duplicateOfMeeting;

    @Column(name = "started_at")
    private Instant startedAt;

    @Column(name = "completed_at")
    private Instant completedAt;

    @Column(name = "processing_time_ms")
    private Long processingTimeMs;

    @Column(name = "llm_call_count", nullable = false)
    private int llmCallCount;

    @Column(name = "tool_call_count", nullable = false)
    private int toolCallCount;

    @Column(name = "validation_failure_count", nullable = false)
    private int validationFailureCount;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Enumerated(EnumType.STRING)
    @Column(name = "notification_status", length = 20)
    private NotificationStatus notificationStatus;

    @Column(name = "notification_type", length = 20)
    private String notificationType;

    @Column(name = "notification_error_code", length = 40)
    private String notificationErrorCode;

    @Column(name = "notified_at")
    private Instant notifiedAt;

    @Column(name = "slack_channel_id", length = 80)
    private String slackChannelId;

    @Column(name = "slack_message_ts", length = 80)
    private String slackMessageTs;

    @Column(name = "stt_model", length = 100)
    private String sttModel;
    @Column(name = "transcription_time_ms")
    private Long transcriptionTimeMs;

    public void recordTranscription(String model, long elapsedMs) {
        if (model == null || !model.matches("[A-Za-z0-9][A-Za-z0-9._-]{0,99}") || elapsedMs < 0)
            throw new IllegalArgumentException("Invalid transcription metadata");
        this.sttModel = model;
        this.transcriptionTimeMs = elapsedMs;
    }
    public String getSttModel() { return sttModel; }
    public Long getTranscriptionTimeMs() { return transcriptionTimeMs; }

    public void recordSlackMessage(String channelId, String messageTs) {
        this.slackChannelId = channelId;
        this.slackMessageTs = messageTs;
    }

    public String getSlackChannelId() { return slackChannelId; }
    public String getSlackMessageTs() { return slackMessageTs; }

    protected Meeting() {
    }

    private Meeting(String rawText, String contentHash, MeetingSource source) {
        this.rawText = rawText;
        this.contentHash = contentHash;
        this.source = source;
        this.status = MeetingStatus.PENDING;
    }

    public static Meeting create(String rawText, MeetingSource source) {
        if (rawText == null || rawText.isBlank()) throw new IllegalArgumentException("rawText is required");
        if (source == null) throw new IllegalArgumentException("source is required");
        return new Meeting(rawText, hashContent(rawText), source);
    }

    public static String hashContent(String rawText) {
        String normalized = rawText.strip().replace("\r\n", "\n").replace('\r', '\n');
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(normalized.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is unavailable", exception);
        }
    }

    @PrePersist
    void setCreatedAt() {
        if (createdAt == null) createdAt = Instant.now();
    }

    public void startProcessing(Instant startedAt) {
        requireStatus(MeetingStatus.PENDING);
        this.startedAt = startedAt;
        this.status = MeetingStatus.PROCESSING;
    }

    public void complete(String summary, Instant completedAt, int llmCalls,
                         int toolCalls, int validationFailures) {
        finish(MeetingStatus.DONE, summary, completedAt, llmCalls, toolCalls, validationFailures);
    }

    public void fail(Instant completedAt, int llmCalls, int toolCalls, int validationFailures) {
        finish(MeetingStatus.FAILED, null, completedAt, llmCalls, toolCalls, validationFailures);
    }

    public void markDuplicateOf(Meeting original) {
        if (original == null || (id != null && id.equals(original.id)))
            throw new IllegalArgumentException("A duplicate meeting must reference another meeting");
        this.duplicateOfMeeting = original;
    }

    private void finish(MeetingStatus finalStatus, String summary, Instant completedAt,
                        int llmCalls, int toolCalls, int validationFailures) {
        requireStatus(MeetingStatus.PROCESSING);
        if (completedAt.isBefore(startedAt)) throw new IllegalArgumentException("completedAt precedes startedAt");
        this.status = finalStatus;
        this.summary = summary;
        this.completedAt = completedAt;
        this.processingTimeMs = Duration.between(startedAt, completedAt).toMillis();
        this.llmCallCount = nonNegative(llmCalls);
        this.toolCallCount = nonNegative(toolCalls);
        this.validationFailureCount = nonNegative(validationFailures);
    }

    private int nonNegative(int count) {
        if (count < 0) throw new IllegalArgumentException("call counts must not be negative");
        return count;
    }

    private void requireStatus(MeetingStatus expected) {
        if (status != expected) throw new IllegalStateException("Meeting must be " + expected);
    }

    public void prepareNotification(String type) {
        if (status != MeetingStatus.DONE && status != MeetingStatus.FAILED)
            throw new IllegalStateException("Meeting must be terminal before notification");
        if (notificationStatus != null) throw new IllegalStateException("Notification already prepared");
        notificationType = type;
        notificationStatus = type == null ? NotificationStatus.SKIPPED : NotificationStatus.PENDING;
        notificationErrorCode = type == null ? "NOT_CONFIGURED" : null;
    }

    public void notificationSent(Instant at) {
        if (notificationStatus != NotificationStatus.PENDING) throw new IllegalStateException("Notification must be pending");
        notificationStatus = NotificationStatus.SENT;
        notifiedAt = at;
        notificationErrorCode = null;
    }

    public void notificationFailed(String code) {
        if (notificationStatus != NotificationStatus.PENDING) throw new IllegalStateException("Notification must be pending");
        notificationStatus = NotificationStatus.FAILED;
        notificationErrorCode = code;
    }

    public NotificationStatus getNotificationStatus() { return notificationStatus == null ? NotificationStatus.SKIPPED : notificationStatus; }
    public String getNotificationType() { return notificationType; }
    public String getNotificationErrorCode() { return notificationStatus == null ? "NOT_ATTEMPTED" : notificationErrorCode; }
    public Instant getNotifiedAt() { return notifiedAt; }

    public Long getId() { return id; }
    public String getRawText() { return rawText; }
    public String getContentHash() { return contentHash; }
    public MeetingSource getSource() { return source; }
    public MeetingStatus getStatus() { return status; }
    public String getSummary() { return summary; }
    public Meeting getDuplicateOfMeeting() { return duplicateOfMeeting; }
    public Long getDuplicateOfMeetingId() { return duplicateOfMeeting == null ? null : duplicateOfMeeting.getId(); }
    public Instant getStartedAt() { return startedAt; }
    public Instant getCompletedAt() { return completedAt; }
    public Long getProcessingTimeMs() { return processingTimeMs; }
    public int getLlmCallCount() { return llmCallCount; }
    public int getToolCallCount() { return toolCallCount; }
    public int getValidationFailureCount() { return validationFailureCount; }
    public Instant getCreatedAt() { return createdAt; }
}
