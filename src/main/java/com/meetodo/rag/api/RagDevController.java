package com.meetodo.rag.api;

import com.meetodo.config.DevEndpointsCondition;
import com.meetodo.member.AdminAuthorization;
import com.meetodo.rag.index.*;
import com.meetodo.rag.question.RagQuestionException;
import com.meetodo.rag.question.QuestionRepository;
import com.meetodo.rag.question.QuestionStatus;
import com.meetodo.rag.question.VerificationDiagnostics;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.core.JsonProcessingException;
import java.util.concurrent.Executor;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.annotation.Conditional;
import org.springframework.context.annotation.Profile;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;

@RestController
@Profile("!test")
@Conditional(DevEndpointsCondition.class)
@RequestMapping("/api/dev/rag")
public class RagDevController {
    public record IndexStatus(int eligible,int indexed,int failed,int pending,String namespace,boolean ready) { }
    private final AdminAuthorization admin;
    private final ObjectProvider<RagIndexer> indexer;
    private final ObjectProvider<Executor> executor;
    public record QuestionDiagnosticResponse(Long questionId,QuestionStatus status,VerificationDiagnostics diagnostics) { }
    private final ObjectProvider<QuestionRepository> questions;
    private final ObjectMapper json;
    private final RagReindexJobs jobs;
    public RagDevController(AdminAuthorization admin,ObjectProvider<RagIndexer> indexer,
            @Qualifier("ragIndexExecutor") ObjectProvider<Executor> executor,RagReindexJobs jobs,
            ObjectProvider<QuestionRepository> questions,ObjectMapper json) {
        this.admin=admin; this.indexer=indexer; this.executor=executor; this.jobs=jobs; this.questions=questions; this.json=json;
    }

    @PostMapping("/reindex")
    @ResponseStatus(HttpStatus.ACCEPTED)
    public RagReindexJobs.Job reindex(@RequestHeader(value="X-Slack-User-Id",required=false) String actor) {
        admin.requireAdmin(actor);
        RagIndexer available=requireIndexer();
        Executor worker=executor.getIfAvailable();
        if(worker==null) throw disabled();
        return jobs.submit(available,worker);
    }

    @GetMapping("/reindex/{jobId}")
    public RagReindexJobs.Job job(@PathVariable String jobId,
            @RequestHeader(value="X-Slack-User-Id",required=false) String actor) {
        admin.requireAdmin(actor);
        requireIndexer();
        return jobs.get(jobId);
    }

    @GetMapping("/index-status")
    public IndexStatus status(@RequestHeader(value="X-Slack-User-Id",required=false) String actor) {
        admin.requireAdmin(actor);
        RagIndexStatus status=requireIndexer().status();
        return new IndexStatus(status.eligible(),status.indexed(),status.failed(),status.pending(),status.namespace(),status.ready());
    }

    @GetMapping("/questions/{questionId}/diagnostics")
    public QuestionDiagnosticResponse diagnostics(@PathVariable Long questionId,
            @RequestHeader(value="X-Slack-User-Id",required=false) String actor) {
        admin.requireAdmin(actor);
        requireIndexer();
        QuestionRepository repository=questions.getIfAvailable();
        if (repository==null) throw disabled();
        var question=repository.findById(questionId).orElseThrow(() ->
                new RagQuestionException(404,"QUESTION_NOT_FOUND","질문 기록이 없습니다."));
        try {
            var diagnostic=question.getVerificationDiagnostics()==null ? null
                    : json.readValue(question.getVerificationDiagnostics(),VerificationDiagnostics.class);
            return new QuestionDiagnosticResponse(question.getId(),question.getStatus(),diagnostic);
        } catch (JsonProcessingException invalid) {
            throw new RagQuestionException(500,"DIAGNOSTICS_UNAVAILABLE","진단 기록을 읽을 수 없습니다.");
        }
    }

    private RagIndexer requireIndexer() {
        RagIndexer available=indexer.getIfAvailable();
        if(available==null) throw disabled();
        return available;
    }
    private RagQuestionException disabled() {
        return new RagQuestionException(404,"RAG_DISABLED","회의 기록 검색 기능이 비활성화되어 있습니다.");
    }
}
