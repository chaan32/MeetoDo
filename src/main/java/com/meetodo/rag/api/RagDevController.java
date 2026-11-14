package com.meetodo.rag.api;

import com.meetodo.config.DevEndpointsCondition;
import com.meetodo.member.AdminAuthorization;
import com.meetodo.rag.index.*;
import com.meetodo.rag.question.RagQuestionException;
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
    private final RagReindexJobs jobs;
    public RagDevController(AdminAuthorization admin,ObjectProvider<RagIndexer> indexer,
            @Qualifier("ragIndexExecutor") ObjectProvider<Executor> executor,RagReindexJobs jobs) {
        this.admin=admin; this.indexer=indexer; this.executor=executor; this.jobs=jobs;
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

    private RagIndexer requireIndexer() {
        RagIndexer available=indexer.getIfAvailable();
        if(available==null) throw disabled();
        return available;
    }
    private RagQuestionException disabled() {
        return new RagQuestionException(404,"RAG_DISABLED","회의 기록 검색 기능이 비활성화되어 있습니다.");
    }
}
