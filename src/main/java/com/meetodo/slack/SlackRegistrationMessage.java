package com.meetodo.slack;

import jakarta.persistence.*;

@Entity
@Table(name = "member_registration_messages", uniqueConstraints = @UniqueConstraint(
        name = "uk_registration_message_admin", columnNames = {"request_id", "admin_slack_user_id"}))
public class SlackRegistrationMessage {
    public enum Status { SENT, FAILED }
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY) private Long id;
    @Column(name = "request_id", nullable = false) private Long requestId;
    @Column(name = "admin_slack_user_id", nullable = false, length = 80) private String adminSlackUserId;
    @Column(name = "channel_id", length = 80) private String channelId;
    @Column(name = "message_ts", length = 80) private String messageTs;
    @Enumerated(EnumType.STRING) @Column(nullable = false, length = 20) private Status status;
    @Column(name = "error_code", length = 80) private String errorCode;

    protected SlackRegistrationMessage() { }
    public SlackRegistrationMessage(Long requestId, String adminSlackUserId, String channelId, String messageTs,
                                    String errorCode) {
        this.requestId = requestId; this.adminSlackUserId = adminSlackUserId;
        this.channelId = channelId; this.messageTs = messageTs; result(errorCode);
    }
    public void result(String errorCode) {
        this.errorCode = errorCode; this.status = errorCode == null ? Status.SENT : Status.FAILED;
    }
    public Long getRequestId() { return requestId; }
    public String getAdminSlackUserId() { return adminSlackUserId; }
    public String getChannelId() { return channelId; }
    public String getMessageTs() { return messageTs; }
    public Status getStatus() { return status; }
    public String getErrorCode() { return errorCode; }
}
