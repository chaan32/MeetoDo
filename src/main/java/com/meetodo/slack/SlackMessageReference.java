package com.meetodo.slack;

import com.meetodo.meeting.Meeting;
import jakarta.persistence.*;
import java.time.LocalDate;

@Entity
@Table(name = "slack_message_references", uniqueConstraints = @UniqueConstraint(name = "uk_slack_message_channel_ts", columnNames = {"channel_id", "message_ts"}))
public class SlackMessageReference {
    public enum MessageType { CHANNEL, DM }
    public enum DeliveryStatus { SENT, FAILED }
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY) private Long id;
    @Column(name = "channel_id", length = 80) private String channelId;
    @Column(name = "message_ts", length = 80) private String messageTs;
    @ManyToOne(fetch = FetchType.LAZY) @JoinColumn(name = "meeting_id") private Meeting meeting;
    @Column(name = "digest_date") private LocalDate digestDate;
    @Column(name = "task_ids", nullable = false, columnDefinition = "text") private String taskIds;
    @Enumerated(EnumType.STRING) @Column(name = "message_type", nullable = false, length = 20)
    private MessageType messageType = MessageType.CHANNEL;
    @Column(name = "target_slack_user_id", length = 80) private String targetSlackUserId;
    @Enumerated(EnumType.STRING) @Column(name = "delivery_status", nullable = false, length = 20)
    private DeliveryStatus deliveryStatus = DeliveryStatus.SENT;
    @Column(name = "error_code", length = 80) private String errorCode;
    @Column(name = "permalink", length = 2048) private String permalink;
    protected SlackMessageReference() { }
    public SlackMessageReference(String channelId, String messageTs, Meeting meeting, LocalDate digestDate, String taskIds) {
        this.channelId = channelId; this.messageTs = messageTs; this.meeting = meeting;
        this.digestDate = digestDate; this.taskIds = taskIds;
    }
    public static SlackMessageReference dm(String channelId, String messageTs, Meeting meeting, String taskIds,
                                            String targetSlackUserId, DeliveryStatus status, String errorCode, String permalink) {
        SlackMessageReference reference = new SlackMessageReference(channelId, messageTs, meeting, null, taskIds);
        reference.messageType = MessageType.DM;
        reference.targetSlackUserId = targetSlackUserId;
        reference.deliveryStatus = status;
        reference.errorCode = errorCode;
        reference.permalink = permalink;
        return reference;
    }
    public String getChannelId() { return channelId; }
    public String getMessageTs() { return messageTs; }
    public Long getMeetingId() { return meeting == null ? null : meeting.getId(); }
    public LocalDate getDigestDate() { return digestDate; }
    public String getTaskIds() { return taskIds; }
    public MessageType getMessageType() { return messageType; }
    public String getTargetSlackUserId() { return targetSlackUserId; }
    public DeliveryStatus getDeliveryStatus() { return deliveryStatus; }
    public String getErrorCode() { return errorCode; }
    public String getPermalink() { return permalink; }
}
