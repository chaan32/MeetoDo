package com.meetodo.slack;

import com.meetodo.meeting.Meeting;
import jakarta.persistence.*;
import java.time.LocalDate;

@Entity
@Table(name = "slack_message_references", uniqueConstraints = @UniqueConstraint(name = "uk_slack_message_channel_ts", columnNames = {"channel_id", "message_ts"}))
public class SlackMessageReference {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY) private Long id;
    @Column(name = "channel_id", nullable = false, length = 80) private String channelId;
    @Column(name = "message_ts", nullable = false, length = 80) private String messageTs;
    @ManyToOne(fetch = FetchType.LAZY) @JoinColumn(name = "meeting_id") private Meeting meeting;
    @Column(name = "digest_date") private LocalDate digestDate;
    @Column(name = "task_ids", nullable = false, columnDefinition = "text") private String taskIds;
    protected SlackMessageReference() { }
    public SlackMessageReference(String channelId, String messageTs, Meeting meeting, LocalDate digestDate, String taskIds) {
        this.channelId = channelId; this.messageTs = messageTs; this.meeting = meeting;
        this.digestDate = digestDate; this.taskIds = taskIds;
    }
    public String getChannelId() { return channelId; }
    public String getMessageTs() { return messageTs; }
    public Long getMeetingId() { return meeting == null ? null : meeting.getId(); }
    public LocalDate getDigestDate() { return digestDate; }
    public String getTaskIds() { return taskIds; }
}
