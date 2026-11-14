package com.meetodo.rag.question;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.meetodo.member.*;
import com.meetodo.meeting.MeetingRepository;
import com.meetodo.task.*;
import com.meetodo.rag.config.RagProperties;
import com.meetodo.rag.embedding.EmbeddingUsageCollector;
import com.meetodo.rag.index.*;
import com.meetodo.rag.search.RagRetriever;
import com.meetodo.usage.UsagePricing;
import java.time.*;
import java.util.*;
import org.junit.jupiter.api.*;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.model.*;
import org.springframework.ai.document.Document;
import org.springframework.mock.env.MockEnvironment;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.SimpleTransactionStatus;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.any;

class QuestionServiceTests {
 MemberRepository members = mock(MemberRepository.class);
 RagIndexer indexer = mock(RagIndexer.class);
 RagIndexDocuments documents = mock(RagIndexDocuments.class);
 RagRetriever retriever = mock(RagRetriever.class);
 RagAnswerClient client = mock(RagAnswerClient.class);
 QuestionRepository questions = mock(QuestionRepository.class);
 TaskRepository tasks = mock(TaskRepository.class);
 MeetingRepository meetings = mock(MeetingRepository.class);
 QuestionService service;
 RagSourceSnapshot source = new RagSourceSnapshot("doc", "MEETING", 1L, null, 0, "지영은 문서를 작성한다.", Instant.EPOCH, "", "ns");
 @BeforeEach void setup() {
  var manager = mock(PlatformTransactionManager.class);
  when(manager.getTransaction(any())).thenReturn(new SimpleTransactionStatus());
  when(members.findBySlackUserIdAndActiveTrue("U1")).thenReturn(Optional.of(new Member("김", "지영", "개발", "U1")));
  when(indexer.status()).thenReturn(new RagIndexStatus(1,1,0,0,"ns"));
  when(documents.namespace()).thenReturn("ns");
  when(documents.sourceSnapshot("doc",1L)).thenReturn(Optional.of(source));
  when(retriever.retrieve(anyString(),anyBoolean())).thenReturn(List.of(Document.builder().id("doc").text("stale").metadata(Map.of("meetingId",1L,"kind","MEETING","namespace","ns")).score(0.9).build()));
  when(questions.saveAndFlush(any())).thenAnswer(call -> call.getArgument(0));
  when(client.answer(anyString(),anyList())).thenReturn(response("{\"found\":true,\"evidence\":[{\"documentId\":\"doc\",\"meetingId\":1,\"quote\":\"지영은 문서를 작성한다.\"}]}"));
  service = new QuestionService(members,indexer,documents,retriever,client,questions,tasks,meetings,new EmbeddingUsageCollector(),new UsagePricing(new MockEnvironment()),manager,Clock.fixed(Instant.EPOCH,ZoneId.of("Asia/Seoul")),new RagProperties(new MockEnvironment()),new ObjectMapper().findAndRegisterModules());
 }
 ChatResponse response(String text) { return new ChatResponse(List.of(new Generation(new AssistantMessage(text)))); }
 @Test void validEvidenceUsesCanonicalTextAndNeverCreatesTask() {
  // 모델 문장이 아닌 서버 검증 인용만 표시하며 질문은 할 일을 만들지 않는다.
  var result=service.ask("누가 문서를 작성하나요?","U1",false);
  assertThat(result.status()).isEqualTo(QuestionStatus.ANSWERED);
  assertThat(result.sources()).hasSize(1);
  assertThat(result.answer()).contains("당시 기록","지영은 문서를 작성한다.");
  verify(client).answer(anyString(),eq(List.of(source)));
  verify(tasks,never()).save(any());
 }
 @Test void unauthorizedDoesNotSearchOrCallModel() {
  // 등록 회원 확인은 검색·LLM보다 먼저 수행한다.
  assertThatThrownBy(() -> service.ask("질문","unknown",false)).isInstanceOfSatisfying(RagQuestionException.class,e -> assertThat(e.code()).isEqualTo("MEMBER_REQUIRED"));
  verifyNoInteractions(retriever,client,questions,indexer);
 }
 @Test void blankQuestionDoesNotCallApis() {
  // 빈 질문을 경계에서 거부한다.
  assertThatThrownBy(() -> service.ask("　 ","U1",false)).isInstanceOf(RagQuestionException.class);
  verifyNoInteractions(retriever,client);
 }
 @Test void oversizedQuestionDoesNotCallApis() {
  // 2000자를 넘는 질문을 거부한다.
  assertThatThrownBy(() -> service.ask("a".repeat(2001),"U1",false)).isInstanceOf(RagQuestionException.class);
  verifyNoInteractions(retriever,client);
 }
 @Test void notReadyDoesNotCallApis() {
  // 부분 색인은 기록 없음과 구분하며 외부 호출하지 않는다.
  when(indexer.status()).thenReturn(new RagIndexStatus(1,0,0,1,"ns"));
  assertThat(service.ask("질문","U1",false).status()).isEqualTo(QuestionStatus.INDEX_NOT_READY);
  verifyNoInteractions(retriever,client);
 }
 @Test void emptyEligibleSetDoesNotCallApis() {
  // 대상 회의가 없으면 임베딩 호출도 하지 않는다.
  when(indexer.status()).thenReturn(new RagIndexStatus(0,0,0,0,"ns"));
  assertThat(service.ask("질문","U1",false).status()).isEqualTo(QuestionStatus.NOT_FOUND);
  verifyNoInteractions(retriever,client);
 }
 @Test void noHitsDoesNotCallAnswerModel() {
  // 점수를 통과한 문서가 없으면 LLM 호출 수는 0이다.
  when(retriever.retrieve(anyString(),anyBoolean())).thenReturn(List.of());
  var result=service.ask("질문","U1",false);
  assertThat(result.status()).isEqualTo(QuestionStatus.NOT_FOUND);
  assertThat(result.llmCallCount()).isZero();
  verifyNoInteractions(client);
 }
 @Test void diagnosticsNeverCallsAnswerModel() {
  // 진단은 임계값을 무시한 점수만 돌려주고 답변하지 않는다.
  var result=service.ask("질문","U1",true);
  assertThat(result.status()).isEqualTo(QuestionStatus.DIAGNOSTIC);
  verify(retriever).retrieve("질문",true);
  verifyNoInteractions(client);
 }
 @Test void invalidModelCitationIsUnverifiedWithoutRetry() {
  // 검색 밖 인용은 재시도 없이 확인 불가 처리한다.
  when(client.answer(anyString(),anyList())).thenReturn(response("{\"found\":true,\"evidence\":[{\"documentId\":\"outside\",\"meetingId\":1,\"quote\":\"사실\"}]}"));
  assertThat(service.ask("질문","U1",false).status()).isEqualTo(QuestionStatus.UNVERIFIED);
  verify(client,times(1)).answer(anyString(),anyList());
 }
 @Test void providerFailurePersistsMissingAttemptAndSafeMessage() {
  // 실패한 호출도 집계하며 공급자 원문은 응답에 남기지 않는다.
  when(client.answer(anyString(),anyList())).thenThrow(new IllegalStateException("secret-provider-body"));
  var result=service.ask("질문","U1",false);
  assertThat(result.status()).isEqualTo(QuestionStatus.FAILED);
  assertThat(result.llmCallCount()).isOne();
  assertThat(result.usage().chatCalls()).hasSize(1);
  assertThat(result.answer()).doesNotContain("secret-provider");
  verify(questions,times(2)).saveAndFlush(any());
 }
 @Test void taskFactsAreRereadAfterModelChangesAssignmentAndStatus() {
  // 색인 내용 대신 모델 호출 후 DB의 담당자·완료 상태를 읽는다.
  var task=Task.create("문서 작성",null,null);
  org.springframework.test.util.ReflectionTestUtils.setField(task,"id",9L);
  var taskSource=new RagSourceSnapshot("doc","TASK",1L,9L,0,"현재 할 일: 문서 작성 / 담당자: 미정",Instant.EPOCH,"","ns");
  when(documents.sourceSnapshot("doc",1L)).thenReturn(Optional.of(taskSource));
  when(tasks.findAllWithAssigneeByIdIn(any())).thenReturn(List.of(task));
  when(retriever.retrieve(anyString(),anyBoolean())).thenReturn(List.of(Document.builder().id("doc").text("old").metadata(Map.of("meetingId",1L,"taskId",9L,"kind","TASK","namespace","ns")).score(0.9).build()));
  when(client.answer(anyString(),anyList())).thenAnswer(call -> {
   task.assign(new Member("박","민수","운영","U2"),"U1",Instant.EPOCH);
   task.changeStatus(TaskStatus.DONE);
   return response("{\"found\":true,\"evidence\":[{\"documentId\":\"doc\",\"meetingId\":1,\"taskId\":9,\"quote\":\"문서 작성\"}]}");
  });
  var result=service.ask("질문","U1",false);
  assertThat(result.currentTasks()).hasSize(1);
  assertThat(result.sources().getFirst().quote()).isNull();
  assertThat(result.currentTasks().getFirst().assigneeName()).isEqualTo("박민수");
  assertThat(result.currentTasks().getFirst().status()).isEqualTo("DONE");
 }
 @Test void deletedTaskIsExcludedFromCurrentFacts() {
  // 검색 후 삭제된 할 일은 현재 상태 목록에 노출하지 않는다.
  var task=Task.create("문서 작성",null,null); task.softDelete();
  org.springframework.test.util.ReflectionTestUtils.setField(task,"id",9L);
  when(documents.sourceSnapshot("doc",1L)).thenReturn(Optional.of(new RagSourceSnapshot("doc","TASK",1L,9L,0,"문서 작성",Instant.EPOCH,"","ns")));
  when(tasks.findAllWithAssigneeByIdIn(any())).thenReturn(List.of(task));
  when(retriever.retrieve(anyString(),anyBoolean())).thenReturn(List.of(Document.builder().id("doc").text("old").metadata(Map.of("meetingId",1L,"taskId",9L,"kind","TASK","namespace","ns")).score(0.9).build()));
  when(client.answer(anyString(),anyList())).thenReturn(response("{\"found\":true,\"evidence\":[{\"documentId\":\"doc\",\"meetingId\":1,\"taskId\":9,\"quote\":\"문서 작성\"}]}"));
  assertThat(service.ask("질문","U1",false).currentTasks()).isEmpty();
 }

}
