package com.meetodo.rag.question;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.meetodo.member.MemberRepository;
import com.meetodo.meeting.MeetingRepository;
import com.meetodo.task.TaskRepository;
import com.meetodo.rag.config.*;
import com.meetodo.rag.embedding.EmbeddingUsageCollector;
import com.meetodo.rag.index.*;
import com.meetodo.rag.search.RagRetriever;
import com.meetodo.usage.*;
import java.time.*;
import java.util.*;
import org.springframework.ai.chat.metadata.EmptyUsage;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.document.Document;
import org.springframework.ai.openai.api.OpenAiApi;
import org.springframework.context.annotation.Conditional;
import org.springframework.stereotype.Service;
import org.springframework.transaction.*;
import org.springframework.transaction.support.TransactionTemplate;
import static com.meetodo.rag.question.QuestionResponse.*;

@Service @Conditional(RagEnabled.class)
public class QuestionService {
    private final MemberRepository members;
    private final RagIndexer indexer;
    private final RagIndexDocuments documents;
    private final RagRetriever retriever;
    private final RagAnswerClient client;
    private final QuestionRepository questions;
    private final TaskRepository tasks;
    private final MeetingRepository meetings;
    private final EmbeddingUsageCollector collector;
    private final UsagePricing pricing;
    private final TransactionTemplate tx;
    private final Clock clock;
    private final RagProperties properties;
    private final ObjectMapper json;
    private final EvidenceVerifier verifier;
    public QuestionService(MemberRepository members,RagIndexer indexer,RagIndexDocuments documents,RagRetriever retriever,
            RagAnswerClient client,QuestionRepository questions,TaskRepository tasks,MeetingRepository meetings,
            EmbeddingUsageCollector collector,UsagePricing pricing,PlatformTransactionManager manager,Clock clock,
            RagProperties properties,ObjectMapper json) {
        this.members=members; this.indexer=indexer; this.documents=documents; this.retriever=retriever; this.client=client;
        this.questions=questions; this.tasks=tasks; this.meetings=meetings; this.collector=collector; this.pricing=pricing;
        this.clock=clock; this.properties=properties; this.json=json; verifier=new EvidenceVerifier(json);
        tx=new TransactionTemplate(manager); tx.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    }

    public QuestionResponse ask(String question,String actorSlackId,boolean diagnostic) {
        if (actorSlackId == null || actorSlackId.length()>80 || tx.execute(status -> members.findBySlackUserIdAndActiveTrue(actorSlackId).isEmpty()))
            throw new RagQuestionException(403,"MEMBER_REQUIRED","등록된 팀원만 질문할 수 있습니다. Slack 앱 홈의 '내 정보 등록'에서 신청한 뒤 관리자 승인을 기다리세요.");
        if (question == null || EvidenceVerifier.whitespace(question).isEmpty() || question.length()>2000)
            throw new RagQuestionException(400,"VALIDATION_ERROR","질문은 1~2000자로 입력하세요.");
        long started=System.nanoTime();
        Question row=tx.execute(status -> questions.saveAndFlush(new Question(question,actorSlackId,clock.instant())));
        QuestionStatus result=QuestionStatus.FAILED;
        String answer="질문 처리에 실패했습니다. 잠시 후 다시 시도하세요.";
        List<Source> sources=List.of(); List<CurrentTask> current=List.of();
        List<RetrievedDocument> retrieved=List.of(); List<UsageCall> chats=new ArrayList<>();
        UsageReport embedding;
        try (var capture=collector.open()) {
            try {
                RagIndexStatus state=indexer.status();
                if (!state.ready()) { result=QuestionStatus.INDEX_NOT_READY; answer="검색 색인이 준비되지 않았습니다."; }
                else if (state.eligible()==0) { result=QuestionStatus.NOT_FOUND; answer="기록에 없습니다"; }
                else {
                    List<Document> hits=retriever.retrieve(question,diagnostic);
                    List<RagSourceSnapshot> canonical=new ArrayList<>();
                    List<RetrievedDocument> scores=new ArrayList<>();
                    for (Document hit:hits) {
                        RagSourceSnapshot source=canonical(hit);
                        canonical.add(source);
                        scores.add(new RetrievedDocument(source.documentId(),source.meetingId(),source.taskId(),source.kind(),hit.getScore()));
                    }
                    retrieved=List.copyOf(scores);
                    if (diagnostic) { result=QuestionStatus.DIAGNOSTIC; answer="검색 진단 결과"; }
                    else if (canonical.isEmpty()) { result=QuestionStatus.NOT_FOUND; answer="기록에 없습니다"; }
                    else {
                        ChatResponse response;
                        try { response=client.answer(question,List.copyOf(canonical)); }
                        catch (RuntimeException failure) { chats.add(usage(null)); throw failure; }
                        chats.add(usage(response));
                        String content=response == null || response.getResult()==null || response.getResult().getOutput()==null
                                ? null : response.getResult().getOutput().getText();
                        List<EvidenceVerifier.Evidence> evidence=verifier.verify(content,canonical);
                        if (evidence.isEmpty()) { result=QuestionStatus.NOT_FOUND; answer="기록에 없습니다"; }
                        else {
                            sources=evidence.stream().map(item -> source(item,canonical)).toList();
                            List<Long> taskIds=evidence.stream().map(EvidenceVerifier.Evidence::taskId).filter(Objects::nonNull).distinct().toList();
                            current=currentFacts(taskIds);
                            result=QuestionStatus.ANSWERED;
                            answer=render(sources,current);
                        }
                    }
                }
            } catch (EvidenceVerifier.InvalidEvidence invalidEvidence) {
                result=QuestionStatus.UNVERIFIED; answer="기록에서 확인되지 않음"; sources=List.of(); current=List.of();
            } catch (RuntimeException failure) {
                result=QuestionStatus.FAILED; answer="질문 처리에 실패했습니다. 잠시 후 다시 시도하세요."; sources=List.of(); current=List.of();
            }
            embedding=capture.report();
        }
        UsageReport report=pricing.price(new UsageReport(chats,null,embedding.embeddingCalls()));
        var response=new QuestionResponse(row.getId(),result,answer,sources,current,retrieved,report,chats.size(),
                embedding.embeddingCalls().size(),Math.max(0,(System.nanoTime()-started)/1_000_000),properties.similarityThreshold(),true);
        row.finish(response,write(sources),write(current),write(retrieved),clock.instant());
        tx.executeWithoutResult(status -> questions.saveAndFlush(row));
        return response;
    }

    private RagSourceSnapshot canonical(Document hit) {
        Object meeting=hit.getMetadata().get("meetingId");
        if (!(meeting instanceof Number id) || !documents.namespace().equals(hit.getMetadata().get("namespace"))) throw new EvidenceVerifier.InvalidEvidence();
        RagSourceSnapshot source=documents.sourceSnapshot(hit.getId(),id.longValue()).orElseThrow(EvidenceVerifier.InvalidEvidence::new);
        if (!source.namespace().equals(documents.namespace()) || !source.kind().equals(hit.getMetadata().get("kind"))) throw new EvidenceVerifier.InvalidEvidence();
        if ("TASK".equals(source.kind())) {
            Object task=hit.getMetadata().get("taskId");
            if (!(task instanceof Number taskId) || !source.taskId().equals(taskId.longValue())) throw new EvidenceVerifier.InvalidEvidence();
        } else if (!"MEETING".equals(source.kind())) throw new EvidenceVerifier.InvalidEvidence();
        return source;
    }
    private Source source(EvidenceVerifier.Evidence item,List<RagSourceSnapshot> canonical) {
        var source=canonical.stream().filter(value -> value.documentId().equals(item.documentId())).findFirst().orElseThrow();
        String[] slack=tx.execute(status -> meetings.findById(source.meetingId()).map(meeting ->
                new String[]{meeting.getSlackChannelId(),meeting.getSlackMessageTs()}).orElse(new String[]{null,null}));
        return new Source(source.documentId(),source.meetingId(),source.kind(),"MEETING".equals(source.kind()) ? item.quote() : null,
                source.registeredAt().atZone(ZoneId.of("Asia/Seoul")).toLocalDate(),slack[0],slack[1],"/api/meetings/"+source.meetingId());
    }
    private List<CurrentTask> currentFacts(List<Long> ids) {
        if (ids.isEmpty()) return List.of();
        return tx.execute(status -> tasks.findAllWithAssigneeByIdIn(ids).stream().filter(task -> !task.isDeleted()).map(task ->
                new CurrentTask(task.getId(),task.getTitle(),task.getAssignee()==null ? null : task.getAssignee().getFullName(),
                task.getAssignee()==null ? null : task.getAssignee().getTeam(),task.getDueDate(),task.getStatus().name(),task.getWarning())).toList());
    }
    private String render(List<Source> sources,List<CurrentTask> current) {
        List<String> lines=new ArrayList<>();
        sources.stream().filter(source -> "MEETING".equals(source.kind())).forEach(source ->
                lines.add("당시 기록 (등록일 "+source.registeredDate()+", 회의 #"+source.meetingId()+"): "+source.quote()));
        current.forEach(task -> lines.add("현재 상태: "+task.title()+" / 담당자: "+Objects.toString(task.assigneeName(),"미정")
                +(task.assigneeTeam()==null ? "" : " ("+task.assigneeTeam()+")")+" / 마감일: "+Objects.toString(task.dueDate(),"없음")
                +" / 상태: "+task.status()+(task.warning()==null || task.warning().isBlank() ? "" : " / "+task.warning())));
        return lines.isEmpty() ? "현재 조회 가능한 할 일이 없습니다." : String.join("\n",lines);
    }
    private String write(Object value) {
        try { return json.writeValueAsString(value); }
        catch (JsonProcessingException ignored) { throw new IllegalStateException("Cannot encode question result"); }
    }
    private UsageCall usage(ChatResponse response) {
        var metadata=response==null ? null : response.getMetadata();
        String model=metadata==null ? null : metadata.getModel();
        var usage=metadata==null ? null : metadata.getUsage();
        if (usage==null || usage instanceof EmptyUsage) return UsageCall.raw(1,model,null,null,null,null,"unavailable");
        if (usage.getNativeUsage() instanceof OpenAiApi.Usage nativeUsage)
            return UsageCall.raw(1,model,count(nativeUsage.promptTokens()),count(nativeUsage.completionTokens()),count(nativeUsage.totalTokens()),null,"tokens");
        return UsageCall.raw(1,model,count(usage.getPromptTokens()),count(usage.getCompletionTokens()),count(usage.getTotalTokens()),null,"tokens");
    }
    private Long count(Integer count) { return count==null ? null : count.longValue(); }
}
