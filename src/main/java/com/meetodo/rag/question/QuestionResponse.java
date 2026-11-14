package com.meetodo.rag.question;
import com.meetodo.usage.UsageReport;
import java.time.LocalDate;
import java.util.List;
public record QuestionResponse(Long questionId, QuestionStatus status, String answer, List<Source> sources,
 List<CurrentTask> currentTasks, List<RetrievedDocument> retrievedDocuments, UsageReport usage,
 int llmCallCount, int embeddingCallCount, long processingTimeMs, double similarityThreshold, boolean temporaryThreshold) {
 public record Source(String documentId, Long meetingId, String kind, String quote, LocalDate registeredDate,
 String channelId, String messageTs, String apiPath) { }
 public record CurrentTask(Long taskId, String title, String assigneeName, String assigneeTeam, LocalDate dueDate,
 String status, String warning) { }
 public record RetrievedDocument(String documentId, Long meetingId, Long taskId, String kind, Double score) { }
}
