package com.meetodo.rag.index;

import com.meetodo.usage.UsageReport;
import com.meetodo.usage.UsageReportConverter;
import jakarta.persistence.*;
import java.time.Instant;

@Entity
@Table(name = "rag_index_runs", indexes = @Index(name = "idx_rag_index_meeting", columnList = "meeting_id,id"))
public class RagIndexRun {
    public enum Status { INDEXING, INDEXED, FAILED }
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY) private Long id;
    @Column(name = "meeting_id", nullable = false) private Long meetingId;
    @Enumerated(EnumType.STRING) @Column(nullable = false, length = 20) private Status status;
    @Column(nullable = false, length = 200) private String namespace;
    @Column(name = "document_ids", nullable = false, columnDefinition = "text") private String documentIds = "[]";
    @Convert(converter = UsageReportConverter.class) @Column(name = "usage_report", columnDefinition = "text") private UsageReport usageReport;
    @Column(name = "error_code", length = 40) private String errorCode;
    @Column(name = "created_at", nullable = false) private Instant createdAt;
    @Column(name = "finished_at") private Instant finishedAt;
    protected RagIndexRun() { }
    public RagIndexRun(Long meetingId, String namespace, Instant at) {
        this.meetingId = meetingId; this.namespace = namespace; this.createdAt = at; this.status = Status.INDEXING;
    }
    void finish(Status status, String documentIds, UsageReport usage, String errorCode, Instant at) {
        this.status = status; this.documentIds = documentIds; this.usageReport = usage;
        this.errorCode = errorCode; this.finishedAt = at;
    }
    public Long getId() { return id; }
    public Long getMeetingId() { return meetingId; }
    public Status getStatus() { return status; }
    public String getNamespace() { return namespace; }
    public String getDocumentIds() { return documentIds; }
    public UsageReport getUsageReport() { return usageReport == null ? UsageReport.empty() : usageReport; }
    public String getErrorCode() { return errorCode; }
    public Instant getCreatedAt() { return createdAt; }
    public Instant getFinishedAt() { return finishedAt; }
}
