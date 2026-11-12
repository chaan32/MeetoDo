package com.meetodo.notification;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.meetodo.meeting.*;
import com.meetodo.task.*;
import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@ExtendWith(OutputCaptureExtension.class)
class MeetingNotificationTests {
    @Autowired MockMvc mvc;
    @Autowired ObjectMapper mapper;
    @Autowired MeetingRepository meetings;
    @Autowired ToolCallLogRepository logs;
    @Autowired TaskRepository tasks;
    @Autowired TaskMentionRepository mentions;
    @Autowired TaskMentionService mentionService;
    @Autowired MeetingCompletionService completion;
    @Autowired PlatformTransactionManager txManager;
    @MockitoBean MeetingAgent agent;
    @MockitoBean Notifier notifier;
    HttpServer server;
    ExecutorService executor;

    @BeforeEach void prepare() {
        mentions.deleteAll(); logs.deleteAll(); tasks.deleteAll(); meetings.deleteAll();
        when(notifier.type()).thenReturn("discord");
        when(agent.process(any())).thenReturn(new AgentResult("모델 원문 summary 사용 금지", 1, 0, 0));
    }
    @AfterEach void closeServer() {
        if (server != null) server.stop(0);
        if (executor != null) executor.shutdownNow();
    }

    // 검증: 별도 트랜잭션에서 DONE이 조회된 후 전송하고 SENT를 별도 커밋해 응답한다.
    @Test void sendsOnlyAfterDoneIsCommittedAndPersistsSent() throws Exception {
        doAnswer(call -> {
            NotificationMessage message = call.getArgument(0);
            TransactionTemplate separate = new TransactionTemplate(txManager);
            separate.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
            MeetingStatus persisted = separate.execute(status -> meetings.findById(message.meetingId()).orElseThrow().getStatus());
            assertThat(persisted).isEqualTo(MeetingStatus.DONE);
            assertThat(message.summaryLines()).noneMatch(line -> line.contains("모델 원문"));
            return null;
        }).when(notifier).send(any());
        submit("커밋 확인").andExpect(status().isOk())
                .andExpect(jsonPath("$.notificationStatus").value("SENT"))
                .andExpect(jsonPath("$.notificationType").value("discord"))
                .andExpect(jsonPath("$.notifiedAt").isNotEmpty());
    }

    // 검증: 완료 저장이 롤백되면 AFTER_COMMIT 리스너는 전송하지 않는다.
    @Test void rollbackDoesNotSendNotification() {
        assertThatThrownBy(() -> new TransactionTemplate(txManager).executeWithoutResult(status -> {
            Meeting meeting = completed("롤백 회의");
            meetings.saveAndFlush(meeting);
            completion.finish(meeting);
            throw new IllegalStateException("rollback");
        })).isInstanceOf(IllegalStateException.class);
        verify(notifier, never()).send(any());
        assertThat(meetings.count()).isZero();
    }

    // 검증: 같은 본문 재전송은 기존 SENT 결과를 반환하고 새 알림을 보내지 않는다.
    @Test void exactResubmissionDoesNotSendAgain() throws Exception {
        submit("동일 본문").andExpect(status().isOk()).andExpect(jsonPath("$.notificationStatus").value("SENT"));
        submit("동일 본문").andExpect(status().isOk()).andExpect(jsonPath("$.notificationStatus").value("SENT"));
        verify(notifier, times(1)).send(any());
        assertThat(meetings.count()).isEqualTo(1);
    }

    // 검증: 공급자 5xx는 실제 mock HTTP 한 번만 호출하고 DONE·FAILED 알림 상태로 응답한다.
    @Test void httpFailureKeepsMeetingDoneAndHidesWebhook(CapturedOutput output) throws Exception {
        AtomicInteger attempts = new AtomicInteger();
        String url = server(503, 0, attempts);
        doAnswer(call -> { new DiscordNotifier(url, new WebhookHttpClient(mapper), new DiscordPayloadRenderer())
                .send(call.getArgument(0)); return null; }).when(notifier).send(any());
        String response = submit("전송 실패").andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("DONE"))
                .andExpect(jsonPath("$.notificationStatus").value("FAILED"))
                .andExpect(jsonPath("$.notificationErrorCode").value("HTTP_ERROR"))
                .andReturn().getResponse().getContentAsString();
        assertThat(attempts.get()).isEqualTo(1);
        assertThat(response).doesNotContain(url, "secret-webhook-token");
        assertThat(output).doesNotContain(url, "secret-webhook-token");
    }

    // 검증: 타임아웃은 재시도하지 않고 DONE 유지·전송 불명 오류 코드로 기록한다.
    @Test void timeoutKeepsMeetingDoneAndDoesNotRetry(CapturedOutput output) throws Exception {
        AtomicInteger attempts = new AtomicInteger();
        String url = server(200, 500, attempts);
        WebhookHttpClient http = new WebhookHttpClient(mapper, Duration.ofSeconds(1), Duration.ofMillis(100));
        doAnswer(call -> { new DiscordNotifier(url, http, new DiscordPayloadRenderer()).send(call.getArgument(0)); return null; })
                .when(notifier).send(any());
        submit("타임아웃 확인").andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("DONE"))
                .andExpect(jsonPath("$.notificationStatus").value("FAILED"))
                .andExpect(jsonPath("$.notificationErrorCode").value("TIMEOUT_DELIVERY_UNKNOWN"));
        assertThat(attempts.get()).isEqualTo(1);
        assertThat(output).doesNotContain(url, "secret-webhook-token");
    }

    @Test void slackMissingScopeIsPersistedInMeetingResponse() throws Exception {
        // Slack의 알려진 오류 코드가 실제 상태 저장 경계와 API 응답을 통과한다.
        doThrow(new NotificationDeliveryException("missing_scope")).when(notifier).send(any());
        submit("Slack 권한 실패 기록").andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("DONE"))
                .andExpect(jsonPath("$.notificationStatus").value("FAILED"))
                .andExpect(jsonPath("$.notificationErrorCode").value("missing_scope"));
    }

    @Test void unsafeSlackErrorIsNeverPersistedOrLogged(CapturedOutput output) throws Exception {
        // 공급자가 오류 필드에 토큰을 넣더라도 API·저장값·로그에 노출하지 않는다.
        doThrow(new NotificationDeliveryException(String.join("", "invalid_auth xox", "b-synthetic-secret"))).when(notifier).send(any());
        String response = submit("Slack 비밀 오류 거부").andExpect(status().isOk())
                .andExpect(jsonPath("$.notificationErrorCode").value("INTERNAL_ERROR"))
                .andReturn().getResponse().getContentAsString();
        assertThat(response).doesNotContain(String.join("", "xox", "b-synthetic-secret"));
        assertThat(output).doesNotContain(String.join("", "xox", "b-synthetic-secret"));
        assertThat(meetings.findAll()).allSatisfy(m ->
                assertThat(m.getNotificationErrorCode()).doesNotContain(String.join("", "xox", "b-synthetic-secret")));
    }

    // 검증: 예상 밖 전송 예외의 URL·원문도 로그나 API로 내보내지 않는다.
    @Test void unexpectedExceptionIsGeneralized(CapturedOutput output) throws Exception {
        doThrow(new IllegalStateException(String.join("", "https", "://discord.com/api/webhooks/secret-webhook-token"))).when(notifier).send(any());
        String response = submit("예상 밖 전송 예외").andExpect(status().isOk())
                .andExpect(jsonPath("$.notificationErrorCode").value("INTERNAL_ERROR"))
                .andReturn().getResponse().getContentAsString();
        assertThat(response).doesNotContain("secret-webhook-token");
        assertThat(output).doesNotContain("secret-webhook-token");
    }

    // 검증: 에이전트 실패 상태는 유지하면서 일반화된 짧은 실패 알림을 전송한다.
    @Test void failedMeetingSendsGenericFailureNotification() throws Exception {
        when(agent.process(any())).thenThrow(new MeetingAgentException("LLM_ERROR", 1, 0));
        submit("민감한 실패 회의록").andExpect(status().isBadGateway());
        Meeting saved = meetings.findAll().getFirst();
        assertThat(saved.getStatus()).isEqualTo(MeetingStatus.FAILED);
        assertThat(saved.getNotificationStatus()).isEqualTo(NotificationStatus.SENT);
        var captor = org.mockito.ArgumentCaptor.forClass(NotificationMessage.class);
        verify(notifier).send(captor.capture());
        assertThat(captor.getValue().toString()).doesNotContain("민감한 실패 회의록", "LLM_ERROR");
        assertThat(captor.getValue().taskLines()).isEmpty();
    }

    // 검증: 같은 작업 묶음의 새 회의는 원본 ID를 포함한 짧은 알림 한 건을 보낸다.
    @Test void duplicateTaskSetSendsShortNewMeetingNotice() throws Exception {
        Meeting source = meetings.saveAndFlush(completed("원본 회의"));
        Task existing = tasks.saveAndFlush(Task.create("API 수정", null, null, source));
        when(agent.process(any())).thenAnswer(call -> {
            Meeting current = call.getArgument(0);
            mentionService.record(existing.getId(), current.getId());
            String data = mapper.writeValueAsString(java.util.List.of(Map.of("id", existing.getId(), "resultType", "EXISTING")));
            logs.save(ToolCallLog.success(current, "findTasks", "{}",
                    mapper.writeValueAsString(Map.of("data", data)), 0, 1));
            return new AgentResult("기존 작업", 1, 1, 0);
        });
        submit("같은 작업을 확인한 새 회의").andExpect(status().isOk())
                .andExpect(jsonPath("$.duplicateOfMeetingId").value(source.getId()))
                .andExpect(jsonPath("$.notificationStatus").value("SENT"));
        var captor = org.mockito.ArgumentCaptor.forClass(NotificationMessage.class);
        verify(notifier).send(captor.capture());
        assertThat(captor.getValue().taskLines()).isEmpty();
        assertThat(String.join(" ", captor.getValue().summaryLines())).contains(source.getId().toString());
    }

    private org.springframework.test.web.servlet.ResultActions submit(String text) throws Exception {
        return mvc.perform(post("/api/meetings").contentType(MediaType.APPLICATION_JSON)
                .content(mapper.writeValueAsString(Map.of("rawText", text))));
    }
    private Meeting completed(String text) {
        Meeting m = Meeting.create(text, MeetingSource.API_TEXT);
        m.startProcessing(Instant.parse("2026-11-11T00:00:00Z"));
        m.complete("완료", Instant.parse("2026-11-11T00:00:01Z"), 1, 0, 0);
        return m;
    }
    private String server(int status, long delay, AtomicInteger count) throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        executor = Executors.newCachedThreadPool(); server.setExecutor(executor);
        server.createContext("/secret-webhook-token", exchange -> {
            count.incrementAndGet();
            try {
                exchange.getRequestBody().readAllBytes();
                if (delay > 0) Thread.sleep(delay);
                byte[] response = "secret-webhook-token".getBytes(java.nio.charset.StandardCharsets.UTF_8);
                exchange.sendResponseHeaders(status, response.length);
                exchange.getResponseBody().write(response);
            } catch (Exception ignored) { }
            finally { exchange.close(); }
        });
        server.start();
        return "http://127.0.0.1:" + server.getAddress().getPort() + "/secret-webhook-token";
    }
}
