package com.meetodo.digest;

import com.meetodo.notification.NotificationStatus;
import jakarta.persistence.*;
import java.time.Instant;
import java.time.LocalDate;

@Entity
@Table(name = "daily_digests", uniqueConstraints = @UniqueConstraint(columnNames = "digest_date"))
public class DailyDigest {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    @Column(name = "digest_date", nullable = false)
    private LocalDate digestDate;
    @Enumerated(EnumType.STRING) @Column(nullable = false, length = 20)
    private NotificationStatus status;
    private Instant sentAt;
    private long overdueCount;
    private long todayCount;
    private long upcomingCount;
    private long noDueDateCount;
    @Column(length = 40)
    private String errorCode;

    protected DailyDigest() {}
    DailyDigest(LocalDate date) { digestDate = date; status = NotificationStatus.PENDING; }
    void start(long overdue, long today, long upcoming, long noDueDate) {
        status = NotificationStatus.PENDING; sentAt = null; errorCode = null;
        overdueCount = overdue; todayCount = today; upcomingCount = upcoming; noDueDateCount = noDueDate;
    }
    void finish(NotificationStatus result, Instant sentAt) {
        status = result; this.sentAt = sentAt;
        errorCode = result == NotificationStatus.FAILED ? "NOTIFICATION_FAILED" : null;
    }
    public LocalDate getDigestDate() { return digestDate; }
    public NotificationStatus getStatus() { return status; }
    public Instant getSentAt() { return sentAt; }
    public long getOverdueCount() { return overdueCount; }
    public long getTodayCount() { return todayCount; }
    public long getUpcomingCount() { return upcomingCount; }
    public long getNoDueDateCount() { return noDueDateCount; }
    public String getErrorCode() { return errorCode; }
}
