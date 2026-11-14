package com.meetodo.rag.api;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.meetodo.StrictHttpRequestBodyAdvice;
import com.meetodo.rag.question.*;
import com.meetodo.task.TaskApiExceptionHandler;
import java.util.*;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.support.StaticListableBeanFactory;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
import static org.springframework.test.web.servlet.setup.MockMvcBuilders.standaloneSetup;

class QuestionControllerTests {
 QuestionService service=mock(QuestionService.class);
 MockMvc mvc;
 @BeforeEach void setup() { mvc=build(service); }
 MockMvc build(QuestionService supplied) {
  var factory=new StaticListableBeanFactory(); if(supplied!=null) factory.addBean("questions",supplied);
  return standaloneSetup(new QuestionController(factory.getBeanProvider(QuestionService.class)))
   .setControllerAdvice(new RagApiExceptionHandler(),new TaskApiExceptionHandler(),new StrictHttpRequestBodyAdvice(new ObjectMapper())).build();
 }
 @Test void validQuestionDelegatesActorAndDefaultDiagnostic() throws Exception {
  // 요청자와 기본 진단값을 공통 서비스에 전달한다.
  when(service.ask("질문","U1",false)).thenReturn(result(QuestionStatus.NOT_FOUND));
  mvc.perform(post("/api/questions").header("X-Slack-User-Id","U1").contentType(MediaType.APPLICATION_JSON).content("{\"question\":\"질문\"}"))
   .andExpect(status().isOk()).andExpect(jsonPath("$.status").value("NOT_FOUND"));
  verify(service).ask("질문","U1",false);
 }
 @Test void diagnosticDelegatesToSameService() throws Exception {
  // 진단 모드도 회원 검증을 포함한 같은 경로를 따른다.
  when(service.ask("질문","U1",true)).thenReturn(result(QuestionStatus.DIAGNOSTIC));
  mvc.perform(post("/api/questions").header("X-Slack-User-Id","U1").contentType(MediaType.APPLICATION_JSON).content("{\"question\":\"질문\",\"diagnostic\":true}"))
   .andExpect(status().isOk()).andExpect(jsonPath("$.status").value("DIAGNOSTIC"));
 }
 @Test void blankQuestionIsRejectedBeforeService() throws Exception {
  // 빈 질문은 HTTP 검증에서 거부한다.
  mvc.perform(post("/api/questions").contentType(MediaType.APPLICATION_JSON).content("{\"question\":\"  \"}"))
   .andExpect(status().isBadRequest()).andExpect(jsonPath("$.fieldErrors.question").exists()); verifyNoInteractions(service);
 }
 @Test void oversizedQuestionIsRejectedBeforeService() throws Exception {
  // 2001자는 경계 밖이다.
  mvc.perform(post("/api/questions").contentType(MediaType.APPLICATION_JSON).content(new ObjectMapper().writeValueAsString(Map.of("question","a".repeat(2001)))))
   .andExpect(status().isBadRequest()).andExpect(jsonPath("$.fieldErrors.question").exists()); verifyNoInteractions(service);
 }
 @Test void maximumQuestionIsAccepted() throws Exception {
  // 2000자는 허용한다.
  when(service.ask("a".repeat(2000),null,false)).thenReturn(result(QuestionStatus.NOT_FOUND));
  mvc.perform(post("/api/questions").contentType(MediaType.APPLICATION_JSON).content(new ObjectMapper().writeValueAsString(Map.of("question","a".repeat(2000)))))
   .andExpect(status().isOk()); verify(service).ask("a".repeat(2000),null,false);
 }
 @Test void unknownFieldIsRejected() throws Exception {
  // 외부 요청의 여분 필드를 거부한다.
  mvc.perform(post("/api/questions").contentType(MediaType.APPLICATION_JSON).content("{\"question\":\"질문\",\"extra\":true}"))
   .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("VALIDATION_ERROR")).andExpect(jsonPath("$.fieldErrors.extra").exists()); verifyNoInteractions(service);
 }
 @Test void absentServiceReturnsDisabled() throws Exception {
  // RAG가 꺼져 있어도 명시적인 404를 반환한다.
  build(null).perform(post("/api/questions").contentType(MediaType.APPLICATION_JSON).content("{\"question\":\"질문\"}"))
   .andExpect(status().isNotFound()).andExpect(jsonPath("$.code").value("RAG_DISABLED"));
 }
 @Test void missingActorIsPassedToServiceAndForbidden() throws Exception {
  // 헤더 없는 호출을 컨트롤러가 우회 허용하지 않는다.
  when(service.ask("질문",null,false)).thenThrow(new RagQuestionException(403,"MEMBER_REQUIRED","등록 신청 후 승인을 기다리세요."));
  mvc.perform(post("/api/questions").contentType(MediaType.APPLICATION_JSON).content("{\"question\":\"질문\"}"))
   .andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value("MEMBER_REQUIRED"));
 }
 @Test void failedQuestionResultRemainsHttp200() throws Exception {
  // 처리된 실패 결과와 측정값은 HTTP 200 응답으로 유지한다.
  when(service.ask("질문",null,false)).thenReturn(result(QuestionStatus.FAILED));
  mvc.perform(post("/api/questions").contentType(MediaType.APPLICATION_JSON).content("{\"question\":\"질문\"}"))
   .andExpect(status().isOk()).andExpect(jsonPath("$.status").value("FAILED")).andExpect(jsonPath("$.llmCallCount").value(0));
 }
 QuestionResponse result(QuestionStatus status) { return new QuestionResponse(1L,status,"응답",List.of(),List.of(),List.of(),null,0,0,0,0.75,true); }
}
