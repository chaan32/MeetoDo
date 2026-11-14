package com.meetodo.rag.api;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.meetodo.member.*;
import com.meetodo.meeting.MeetingRepository;
import com.meetodo.rag.config.RagProperties;
import com.meetodo.rag.embedding.EmbeddingUsageCollector;
import com.meetodo.rag.index.*;
import com.meetodo.rag.question.*;
import com.meetodo.rag.search.RagRetriever;
import com.meetodo.task.*;
import com.meetodo.usage.UsagePricing;
import java.time.*;
import java.util.*;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.support.StaticListableBeanFactory;
import org.springframework.http.MediaType;
import org.springframework.mock.env.MockEnvironment;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.SimpleTransactionStatus;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
import static org.springframework.test.web.servlet.setup.MockMvcBuilders.standaloneSetup;

class QuestionHttpServiceBoundaryTests {
 MemberRepository members=mock(MemberRepository.class); RagIndexer indexer=mock(RagIndexer.class);
 RagRetriever retriever=mock(RagRetriever.class); RagAnswerClient client=mock(RagAnswerClient.class);
 TaskRepository tasks=mock(TaskRepository.class); QuestionRepository questions=mock(QuestionRepository.class); MockMvc mvc;
 @BeforeEach void setup() {
  var manager=mock(PlatformTransactionManager.class); when(manager.getTransaction(any())).thenReturn(new SimpleTransactionStatus());
  var service=new QuestionService(members,indexer,mock(RagIndexDocuments.class),retriever,client,questions,tasks,mock(MeetingRepository.class),new EmbeddingUsageCollector(),new UsagePricing(new MockEnvironment()),manager,Clock.fixed(Instant.EPOCH,ZoneId.of("Asia/Seoul")),new RagProperties(new MockEnvironment()),new ObjectMapper().findAndRegisterModules());
  var factory=new StaticListableBeanFactory(); factory.addBean("service",service);
  mvc=standaloneSetup(new QuestionController(factory.getBeanProvider(QuestionService.class))).setControllerAdvice(new RagApiExceptionHandler(),new TaskApiExceptionHandler()).build();
 }
 @Test void unknownActorCannotReachAnyExternalCall() throws Exception {
  // 실제 서비스가 미등록 호출자를 검색 전에 거부한다.
  reject("UUNKNOWN"); verifyNoInteractions(indexer,retriever,client,tasks,questions);
 }
 @Test void pendingRegistrationCannotAskUntilMemberApproval() throws Exception {
  // 신청만 한 사용자는 active Member 조회 결과가 없어 거부한다.
  when(members.findBySlackUserIdAndActiveTrue("UPENDING")).thenReturn(Optional.empty());
  reject("UPENDING"); verify(members).findBySlackUserIdAndActiveTrue("UPENDING"); verifyNoInteractions(indexer,retriever,client,tasks,questions);
 }
 @Test void inactiveMemberCannotAsk() throws Exception {
  // 비활성 팀원은 active 조회에 포함되지 않는다.
  when(members.findBySlackUserIdAndActiveTrue("UINACTIVE")).thenReturn(Optional.empty());
  reject("UINACTIVE"); verify(members).findBySlackUserIdAndActiveTrue("UINACTIVE"); verifyNoInteractions(indexer,retriever,client,tasks,questions);
 }
 @Test void activeMemberQuestionNeverCreatesTask() throws Exception {
  // 실제 질문 경로는 색인이 비어 있어도 Task 저장을 하지 않는다.
  when(members.findBySlackUserIdAndActiveTrue("UACTIVE")).thenReturn(Optional.of(new Member("김","지영","개발","UACTIVE")));
  when(indexer.status()).thenReturn(new RagIndexStatus(0,0,0,0,"ns")); when(questions.saveAndFlush(any())).thenAnswer(call->call.getArgument(0));
  mvc.perform(post("/api/questions").header("X-Slack-User-Id","UACTIVE").contentType(MediaType.APPLICATION_JSON).content("{\"question\":\"질문\"}"))
   .andExpect(status().isOk()).andExpect(jsonPath("$.status").value("NOT_FOUND")); verifyNoInteractions(tasks,retriever,client);
 }
 void reject(String actor) throws Exception {
  mvc.perform(post("/api/questions").header("X-Slack-User-Id",actor).contentType(MediaType.APPLICATION_JSON).content("{\"question\":\"질문\"}"))
   .andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value("MEMBER_REQUIRED"))
   .andExpect(jsonPath("$.message").value(org.hamcrest.Matchers.containsString("내 정보 등록")))
   .andExpect(jsonPath("$.message").value(org.hamcrest.Matchers.containsString("승인")));
 }
}
