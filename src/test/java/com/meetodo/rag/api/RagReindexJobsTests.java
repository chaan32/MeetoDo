package com.meetodo.rag.api;

import com.meetodo.rag.index.*;
import com.meetodo.rag.question.RagQuestionException;
import java.util.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class RagReindexJobsTests {
 RagIndexer indexer=mock(RagIndexer.class);
 RagReindexJobs jobs=new RagReindexJobs();
 @org.junit.jupiter.api.BeforeEach void setup() { when(indexer.reindexAll()).thenReturn(List.of()); }
 List<Runnable> queue=new ArrayList<>();
 @Test void enqueueDoesNotRunIndexSynchronously() {
  // 접수 시에는 색인 작업을 큐에만 넣는다.
  var job=jobs.submit(indexer,queue::add); assertThat(job.status()).isEqualTo("QUEUED"); verifyNoInteractions(indexer);
  queue.getFirst().run(); assertThat(jobs.get(job.jobId()).status()).isEqualTo("COMPLETED"); verify(indexer).reindexAll();
 }
 @Test void secondConcurrentJobIsRejected() {
  // 같은 프로세스에서 재색인 두 개가 동시에 대기하지 않는다.
  jobs.submit(indexer,queue::add);
  assertThatThrownBy(()->jobs.submit(indexer,queue::add)).isInstanceOfSatisfying(RagQuestionException.class,e->assertThat(e.code()).isEqualTo("REINDEX_IN_PROGRESS"));
 }
 @Test void failureIsSafeAndReleasesMutex() {
  // 공급자 예외를 숨기며 다음 작업을 허용한다.
  when(indexer.reindexAll()).thenThrow(new IllegalStateException("secret-provider"));
  var job=jobs.submit(indexer,queue::add); queue.getFirst().run();
  assertThat(jobs.get(job.jobId())).isEqualTo(new RagReindexJobs.Job(job.jobId(),"FAILED","REINDEX_FAILED"));
  assertThat(jobs.submit(indexer,queue::add).status()).isEqualTo("QUEUED");
 }
 @Test void failedRunMarksWholeJobFailed() {
  // indexer가 예외 없이 실패 행을 반환해도 작업은 실패다.
  var failed=mock(RagIndexRun.class); when(failed.getStatus()).thenReturn(RagIndexRun.Status.FAILED); when(indexer.reindexAll()).thenReturn(List.of(failed));
  var job=jobs.submit(indexer,queue::add); queue.getFirst().run(); assertThat(jobs.get(job.jobId()).status()).isEqualTo("FAILED");
 }
 @Test void fullQueueRejectsSafelyAndReleasesMutex() {
  // 실행기 포화 후 재시도가 가능하다.
  assertThatThrownBy(()->jobs.submit(indexer,task->{throw new RejectedExecutionException("secret");})).isInstanceOfSatisfying(RagQuestionException.class,e->assertThat(e.code()).isEqualTo("REINDEX_QUEUE_FULL"));
  assertThat(jobs.submit(indexer,queue::add).status()).isEqualTo("QUEUED");
 }
 @Test void completedHistoryIsBoundedToTwentyJobs() {
  // 완료 작업 ID는 최근 20개만 보관한다.
  var first=jobs.submit(indexer,Runnable::run);
  for(int i=0;i<20;i++) jobs.submit(indexer,Runnable::run);
  assertThatThrownBy(()->jobs.get(first.jobId())).isInstanceOfSatisfying(RagQuestionException.class,e->assertThat(e.code()).isEqualTo("REINDEX_JOB_NOT_FOUND"));
 }
 @Test void unknownJobReturnsSafe404() {
  // 없는 작업은 명시적인 404다.
  assertThatThrownBy(()->jobs.get("unknown")).isInstanceOfSatisfying(RagQuestionException.class,e->assertThat(e.status()).isEqualTo(404));
 }
}
