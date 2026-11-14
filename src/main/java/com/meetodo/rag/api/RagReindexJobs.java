package com.meetodo.rag.api;

import com.meetodo.rag.index.RagIndexer;
import com.meetodo.rag.index.RagIndexRun;
import com.meetodo.rag.question.RagQuestionException;
import java.util.LinkedHashMap;
import java.util.UUID;
import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionException;
import org.springframework.stereotype.Component;

@Component
public class RagReindexJobs {
    public record Job(String jobId,String status,String errorCode) { }
    // shortcut: 작업 접수 이력과 mutex는 프로세스 단위다. 다중 인스턴스 운영 시 공유 작업 저장소로 전환한다.
    private final LinkedHashMap<String,Job> history=new LinkedHashMap<>();
    private boolean active;

    public synchronized Job submit(RagIndexer indexer,Executor executor) {
        if (active) throw new RagQuestionException(409,"REINDEX_IN_PROGRESS","이미 재색인 작업이 진행 중입니다.");
        String id=UUID.randomUUID().toString();
        Job job=new Job(id,"QUEUED",null);
        active=true;
        history.put(id,job);
        try { executor.execute(()->run(id,indexer)); }
        catch (RejectedExecutionException rejected) {
            active=false;
            history.remove(id);
            throw new RagQuestionException(503,"REINDEX_QUEUE_FULL","재색인 대기열이 가득 찼습니다. 잠시 후 다시 시도하세요.");
        }
        while(history.size()>20) history.remove(history.keySet().iterator().next());
        return job;
    }

    public synchronized Job get(String id) {
        Job job=history.get(id);
        if (job==null) throw new RagQuestionException(404,"REINDEX_JOB_NOT_FOUND","재색인 작업을 찾을 수 없습니다.");
        return job;
    }

    private void run(String id,RagIndexer indexer) {
        update(id,"RUNNING",null);
        try {
            boolean failed=indexer.reindexAll().stream().anyMatch(run->run.getStatus()!=RagIndexRun.Status.INDEXED);
            update(id,failed ? "FAILED" : "COMPLETED",failed ? "REINDEX_FAILED" : null);
        } catch (RuntimeException failure) {
            update(id,"FAILED","REINDEX_FAILED");
        } finally {
            synchronized(this) { active=false; }
        }
    }

    private synchronized void update(String id,String status,String errorCode) {
        history.put(id,new Job(id,status,errorCode));
    }
}
