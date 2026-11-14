package com.meetodo.rag.question;

import com.meetodo.usage.*;
import jakarta.persistence.*;
import java.time.Instant;

@Entity @Table(name="rag_questions")
public class Question {
    @Id @GeneratedValue(strategy=GenerationType.IDENTITY) private Long id;
    @Column(nullable=false,length=2000) private String question;
    @Column(name="actor_slack_id",nullable=false,length=80) private String actorSlackId;
    @Enumerated(EnumType.STRING) @Column(nullable=false,length=30) private QuestionStatus status;
    @Column(columnDefinition="text") private String answer;
    @Column(columnDefinition="text",nullable=false) private String sources="[]";
    @Column(name="current_tasks",columnDefinition="text",nullable=false) private String currentTasks="[]";
    @Column(name="retrieved_documents",columnDefinition="text",nullable=false) private String retrievedDocuments="[]";
    @Convert(converter=UsageReportConverter.class) @Column(name="usage_report",columnDefinition="text") private UsageReport usageReport;
    @Column(name="llm_call_count",nullable=false) private int llmCallCount;
    @Column(name="embedding_call_count",nullable=false) private int embeddingCallCount;
    @Column(name="processing_time_ms",nullable=false) private long processingTimeMs;
    @Column(name="created_at",nullable=false) private Instant createdAt;
    @Column(name="completed_at") private Instant completedAt;
    protected Question() { }
    Question(String question,String actorSlackId,Instant at) {
        this.question=question; this.actorSlackId=actorSlackId; createdAt=at; status=QuestionStatus.PROCESSING;
    }
    void finish(QuestionResponse response,String sources,String currentTasks,String retrieved,Instant at) {
        status=response.status(); answer=response.answer(); this.sources=sources; this.currentTasks=currentTasks;
        retrievedDocuments=retrieved; usageReport=response.usage(); llmCallCount=response.llmCallCount();
        embeddingCallCount=response.embeddingCallCount(); processingTimeMs=response.processingTimeMs(); completedAt=at;
    }
    public Long getId() { return id; }
    public String getQuestion() { return question; }
    public String getActorSlackId() { return actorSlackId; }
    public QuestionStatus getStatus() { return status; }
    public String getAnswer() { return answer; }
    public String getSources() { return sources; }
    public String getCurrentTasks() { return currentTasks; }
    public String getRetrievedDocuments() { return retrievedDocuments; }
    public UsageReport getUsageReport() { return usageReport == null ? UsageReport.empty() : usageReport; }
    public int getLlmCallCount() { return llmCallCount; }
    public int getEmbeddingCallCount() { return embeddingCallCount; }
    public long getProcessingTimeMs() { return processingTimeMs; }
    public Instant getCreatedAt() { return createdAt; }
    public Instant getCompletedAt() { return completedAt; }
}
