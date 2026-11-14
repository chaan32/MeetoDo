package com.meetodo.rag.api;
import com.meetodo.member.AdminAuthorization;
import com.meetodo.rag.index.*;
import com.meetodo.task.TaskApiExceptionHandler;
import java.util.*;
import java.util.concurrent.Executor;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.support.StaticListableBeanFactory;
import org.springframework.test.web.servlet.MockMvc;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
import static org.springframework.test.web.servlet.setup.MockMvcBuilders.standaloneSetup;
class RagDevControllerTests {
 RagIndexer indexer=mock(RagIndexer.class); List<Runnable> queue=new ArrayList<>(); MockMvc mvc;
 @BeforeEach void setup() { mvc=build(indexer); }
 MockMvc build(RagIndexer supplied) {
  var factory=new StaticListableBeanFactory(); if(supplied!=null) factory.addBean("indexer",supplied); factory.addBean("executor",(Executor)queue::add);
  return standaloneSetup(new RagDevController(new AdminAuthorization("UADMIN"),factory.getBeanProvider(RagIndexer.class),factory.getBeanProvider(Executor.class),new RagReindexJobs()))
   .setControllerAdvice(new RagApiExceptionHandler(),new TaskApiExceptionHandler()).build();
 }
 @Test void adminReceives202AndQueuedJob() throws Exception {
  // 관리자는 비동기 접수 ID를 받는다.
  mvc.perform(post("/api/dev/rag/reindex").header("X-Slack-User-Id","UADMIN")).andExpect(status().isAccepted()).andExpect(jsonPath("$.jobId").isString()).andExpect(jsonPath("$.status").value("QUEUED")); verifyNoInteractions(indexer);
 }
 @Test void nonAdminCannotStartReindex() throws Exception {
  // 회원이라도 관리자가 아니면 거부한다.
  mvc.perform(post("/api/dev/rag/reindex").header("X-Slack-User-Id","U1")).andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value("ADMIN_REQUIRED")); verifyNoInteractions(indexer);
 }
 @Test void missingActorCannotReadStatus() throws Exception {
  // 조회에도 관리자 권한이 필요하다.
  mvc.perform(get("/api/dev/rag/index-status")).andExpect(status().isForbidden()); verifyNoInteractions(indexer);
 }
 @Test void nonAdminCannotReadJobs() throws Exception {
  // 작업 ID를 알아도 일반 회원은 조회할 수 없다.
  mvc.perform(get("/api/dev/rag/reindex/unknown").header("X-Slack-User-Id","U1")).andExpect(status().isForbidden());
 }
 @Test void authorizationPrecedesDisabledCheck() throws Exception {
  // 비활성 기능의 존재 여부보다 권한을 먼저 검사한다.
  build(null).perform(post("/api/dev/rag/reindex")).andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value("ADMIN_REQUIRED"));
 }
 @Test void absentIndexerReturnsRagDisabledForAdmin() throws Exception {
  // 관리자는 비활성 상태를 명시적으로 확인한다.
  build(null).perform(post("/api/dev/rag/reindex").header("X-Slack-User-Id","UADMIN")).andExpect(status().isNotFound()).andExpect(jsonPath("$.code").value("RAG_DISABLED"));
 }
 @Test void indexStatusSerializesReadiness() throws Exception {
  // ready는 계산 결과를 명시적인 DTO 필드로 보낸다.
  when(indexer.status()).thenReturn(new RagIndexStatus(3,1,1,1,"namespace"));
  mvc.perform(get("/api/dev/rag/index-status").header("X-Slack-User-Id","UADMIN")).andExpect(status().isOk()).andExpect(jsonPath("$.eligible").value(3)).andExpect(jsonPath("$.ready").value(false)).andExpect(jsonPath("$.namespace").value("namespace"));
 }
 @Test void unknownJobReturns404() throws Exception {
  // 만료되거나 없는 작업 ID는 404다.
  mvc.perform(get("/api/dev/rag/reindex/unknown").header("X-Slack-User-Id","UADMIN")).andExpect(status().isNotFound()).andExpect(jsonPath("$.code").value("REINDEX_JOB_NOT_FOUND"));
 }
}
