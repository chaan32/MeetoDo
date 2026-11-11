package com.meetodo.meeting;

import java.time.Instant;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.meetodo.meeting.dto.MeetingRequest;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.LoggerContext;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import org.slf4j.LoggerFactory;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class MeetingApiTests {

    @Autowired MockMvc mvc;
    @Autowired ObjectMapper mapper;
    @Autowired MeetingRepository meetings;
    @Autowired ToolCallLogRepository logs;
    @Autowired com.meetodo.task.TaskRepository tasks;
    @MockitoBean MeetingAgent agent;

    @BeforeEach
    void cleanDatabase() {
        logs.deleteAll();
        tasks.deleteAll();
        meetings.deleteAll();
    }

    // 검증: processesMeetingAndReturnsTheStoredResultAndMetrics 시나리오의 기대 동작을 확인한다.
    @Test
    void processesMeetingAndReturnsTheStoredResultAndMetrics() throws Exception {
        when(agent.process(any(Meeting.class))).thenReturn(new AgentResult("요약", 2, 3, 1));

        String body = mvc.perform(post("/api/meetings")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"rawText\":\"민수는 로그인 API를 금요일까지 수정한다.\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("DONE"))
                .andExpect(jsonPath("$.summary").value("요약"))
                .andExpect(jsonPath("$.tasks").isArray())
                .andExpect(jsonPath("$.unassignedCount").value(0))
                .andExpect(jsonPath("$.processingTimeMs").isNumber())
                .andExpect(jsonPath("$.llmCallCount").value(2))
                .andExpect(jsonPath("$.toolCallCount").value(3))
                .andExpect(jsonPath("$.validationFailureCount").value(1))
                .andReturn().getResponse().getContentAsString();
        long id = mapper.readTree(body).get("meetingId").asLong();

        mvc.perform(get("/api/meetings/{id}", id))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.meetingId").value(id))
                .andExpect(jsonPath("$.status").value("DONE"))
                .andExpect(jsonPath("$.summary").value("요약"));
    }

    // 검증: rejectsBlankAndOversizedMeetingTextAsValidationErrors 시나리오의 기대 동작을 확인한다.
    @Test
    void rejectsBlankAndOversizedMeetingTextAsValidationErrors() throws Exception {
        mvc.perform(post("/api/meetings").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"rawText\":\"   \"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"));

        String oversized = "x".repeat(20001);
        mvc.perform(post("/api/meetings").contentType(MediaType.APPLICATION_JSON)
                        .content(mapper.writeValueAsString(new MeetingRequest(oversized))))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"));

        verify(agent, never()).process(any(Meeting.class));
        assertThat(meetings.count()).isZero();
    }

    // 검증: returnsNotFoundForUnknownMeetingId 시나리오의 기대 동작을 확인한다.
    @Test
    void returnsNotFoundForUnknownMeetingId() throws Exception {
        mvc.perform(get("/api/meetings/{id}", 999999L))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("MEETING_NOT_FOUND"));
    }

    // 검증: returnsToolLogsInCallOrder 시나리오의 기대 동작을 확인한다.
    @Test
    void returnsToolLogsInCallOrder() throws Exception {
        Meeting meeting = meetings.saveAndFlush(Meeting.create("내용", MeetingSource.API_TEXT));
        logs.save(ToolCallLog.success(meeting, "second", "{}", "ok", 2, 2));
        logs.save(ToolCallLog.failure(meeting, "first", "{}", "MEMBER_NOT_FOUND", "not found", 1, 1));

        mvc.perform(get("/api/meetings/{id}/logs", meeting.getId()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].callOrder").value(1))
                .andExpect(jsonPath("$[0].toolName").value("first"))
                .andExpect(jsonPath("$[0].success").value(false))
                .andExpect(jsonPath("$[1].callOrder").value(2))
                .andExpect(jsonPath("$[1].toolName").value("second"))
                .andExpect(jsonPath("$[1].latencyMs").value(2));
    }

    // 검증: returnsExistingMeetingAndDoesNotProcessTheSameTextTwice 시나리오의 기대 동작을 확인한다.
    @Test
    void returnsExistingMeetingAndDoesNotProcessTheSameTextTwice() throws Exception {
        when(agent.process(any(Meeting.class))).thenReturn(new AgentResult("기존 결과", 1, 0, 0));
        String request = "{\"rawText\":\"같은 회의록\"}";

        String first = mvc.perform(post("/api/meetings").contentType(MediaType.APPLICATION_JSON).content(request))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        String second = mvc.perform(post("/api/meetings").contentType(MediaType.APPLICATION_JSON).content(request))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();

        assertThat(mapper.readTree(first).get("meetingId").asLong())
                .isEqualTo(mapper.readTree(second).get("meetingId").asLong());
        verify(agent).process(any(Meeting.class));
        assertThat(meetings.count()).isEqualTo(1);
    }

    // 검증: marksMeetingFailedAndHidesProviderErrorDetails 시나리오의 기대 동작을 확인한다.
    @Test
    void marksMeetingFailedAndHidesProviderErrorDetails() throws Exception {
        when(agent.process(any(Meeting.class))).thenThrow(
                new MeetingAgentException("LLM_ERROR", 2, 1));

        String response = mvc.perform(post("/api/meetings").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"rawText\":\"민감한 원문\"}"))
                .andExpect(status().isBadGateway())
                .andExpect(jsonPath("$.code").value("MEETING_PROCESSING_FAILED"))
                .andExpect(jsonPath("$.message").value("회의 처리를 완료하지 못했습니다."))
                .andExpect(jsonPath("$.meetingId").isNumber())
                .andReturn().getResponse().getContentAsString();
        assertThat(response).doesNotContain("민감한 원문").doesNotContain("LLM_ERROR");
        assertThat(meetings.findAll().getFirst().getStatus()).isEqualTo(MeetingStatus.FAILED);
        assertThat(meetings.findAll().getFirst().getLlmCallCount()).isEqualTo(2);
        assertThat(meetings.findAll().getFirst().getToolCallCount()).isEqualTo(1);
    }

    // 검증: returnsExistingMeetingWhileTheFirstRequestIsStillProcessing 시나리오의 기대 동작을 확인한다.
    @Test
    void returnsExistingMeetingWhileTheFirstRequestIsStillProcessing() throws Exception {
        Meeting meeting = Meeting.create("ongoing meeting", MeetingSource.API_TEXT);
        meeting.startProcessing(Instant.parse("2026-11-11T00:00:00Z"));
        Meeting saved = meetings.saveAndFlush(meeting);

        mvc.perform(post("/api/meetings").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"rawText\":\"ongoing meeting\"}"))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.meetingId").value(saved.getId()))
                .andExpect(jsonPath("$.status").value("PROCESSING"));

        verify(agent, never()).process(any(Meeting.class));
    }

    // 검증: concurrentRequestsForTheSameMeetingProcessOnlyOnce 시나리오의 기대 동작을 확인한다.
    @Test
    @Timeout(10)
    void concurrentRequestsForTheSameMeetingProcessOnlyOnce() throws Exception {
        CountDownLatch agentStarted = new CountDownLatch(1);
        CountDownLatch allowAgentToFinish = new CountDownLatch(1);
        when(agent.process(any(Meeting.class))).thenAnswer(invocation -> {
            agentStarted.countDown();
            if (!allowAgentToFinish.await(5, TimeUnit.SECONDS)) throw new IllegalStateException("test timed out");
            return new AgentResult("완료", 1, 0, 0);
        });
        ExecutorService executor = Executors.newSingleThreadExecutor();
        String request = "{\"rawText\":\"동시 요청 회의록\"}";
        try {
            var first = executor.submit(() -> mvc.perform(post("/api/meetings")
                    .contentType(MediaType.APPLICATION_JSON).content(request)).andReturn());
            assertThat(agentStarted.await(5, TimeUnit.SECONDS)).isTrue();

            mvc.perform(post("/api/meetings").contentType(MediaType.APPLICATION_JSON).content(request))
                    .andExpect(status().isAccepted())
                    .andExpect(jsonPath("$.status").value("PROCESSING"));
            allowAgentToFinish.countDown();
            assertThat(first.get(5, TimeUnit.SECONDS).getResponse().getStatus()).isEqualTo(200);
            assertThat(meetings.count()).isEqualTo(1);
            verify(agent).process(any(Meeting.class));
        } finally {
            allowAgentToFinish.countDown();
            executor.shutdownNow();
        }
    }

    // 검증: 회의 본문과 비밀값이 애플리케이션 로그에 남지 않는지 확인한다.
    @Test
    void doesNotWriteMeetingTextOrSecretLikeValuesToApplicationLogs() throws Exception {
        String transcript = "FULL_MEETING_TRANSCRIPT_SENTINEL sk-live-abcdefghijklmnopqrstuvwxyz Authorization: Bearer token-secret-value";
        when(agent.process(any(Meeting.class))).thenReturn(new AgentResult("완료", 1, 0, 0));
        LoggerContext loggerContext = (LoggerContext) LoggerFactory.getILoggerFactory();
        Logger root = loggerContext.getLogger(org.slf4j.Logger.ROOT_LOGGER_NAME);
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.setContext(loggerContext);
        appender.start();
        root.addAppender(appender);
        try {
            mvc.perform(post("/api/meetings").contentType(MediaType.APPLICATION_JSON)
                            .content(mapper.writeValueAsString(new MeetingRequest(transcript))))
                    .andExpect(status().isOk());
        } finally {
            root.detachAppender(appender);
            appender.stop();
        }
        String output = appender.list.stream().map(ILoggingEvent::getFormattedMessage)
                .reduce("", (left, right) -> left + "\n" + right);
        assertThat(output).doesNotContain("FULL_MEETING_TRANSCRIPT_SENTINEL")
                .doesNotContain("sk-live-abcdefghijklmnopqrstuvwxyz")
                .doesNotContain("token-secret-value");
    }

    // 검증: reportsElapsedTimeAsNonNegative 시나리오의 기대 동작을 확인한다.
    @Test
    void reportsElapsedTimeAsNonNegative() throws Exception {
        when(agent.process(any(Meeting.class))).thenReturn(new AgentResult("ok", 1, 0, 0));

        mvc.perform(post("/api/meetings").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"rawText\":\"elapsed time\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.processingTimeMs").isNumber());
    }

    // 검증: 최근 회의 API는 최신 5건과 회의 본문·Task를 생성 시각 내림차순으로 반환한다.
    @Test
    void returnsOnlyTheFiveMostRecentlyCreatedMeetingsWithTheirTasks() throws Exception {
        Meeting newest = null;
        for (int index = 1; index <= 7; index++) {
            Meeting meeting = Meeting.create("회의록 " + index, MeetingSource.API_TEXT);
            org.springframework.test.util.ReflectionTestUtils.setField(meeting, "createdAt",
                    Instant.parse("2026-11-11T00:00:00Z").plusSeconds(index));
            newest = meetings.saveAndFlush(meeting);
        }
        tasks.saveAndFlush(com.meetodo.task.Task.create("최근 회의 할 일", null, null, newest));

        mvc.perform(get("/api/meetings"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", org.hamcrest.Matchers.hasSize(5)))
                .andExpect(jsonPath("$[0].rawText").value("회의록 7"))
                .andExpect(jsonPath("$[0].tasks[0].title").value("최근 회의 할 일"))
                .andExpect(jsonPath("$[4].rawText").value("회의록 3"));
    }
}
