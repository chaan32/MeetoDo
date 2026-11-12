package com.meetodo.meeting;

import com.meetodo.meeting.dto.MeetingRequest;
import com.meetodo.notification.*;
import com.meetodo.task.*;
import com.meetodo.slack.SlackMessageReferenceRepository;
import java.util.ArrayList;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@SpringBootTest(properties="spring.datasource.url=jdbc:h2:mem:slack-submit-test;DB_CLOSE_DELAY=-1")
@ActiveProfiles("test")
class SlackMeetingSubmissionTests {
    @Autowired MeetingApplicationService service;
    @Autowired MeetingRepository meetings;
    @Autowired ToolCallLogRepository logs;
    @Autowired TaskRepository tasks;
    @Autowired TaskMentionRepository mentions;
    @Autowired SlackMessageReferenceRepository references;
    @MockitoBean MeetingAgent agent;
    @MockitoBean Notifier notifier;
    @BeforeEach void setup() {
        references.deleteAll(); mentions.deleteAll(); logs.deleteAll(); tasks.deleteAll(); meetings.deleteAll();
        when(notifier.type()).thenReturn("slack-bot");
        when(agent.process(any())).thenReturn(new AgentResult("완료",1,0,0));
    }
    @Test void newSlackSubmissionStartsMessageBeforeAgentAndKeepsSlackSource() {
        // 진행 게시 콜백은 Meeting 저장 전에 실행되고 게시 성공 후에만 LLM을 호출한다.
        var order = new ArrayList<String>();
        when(agent.process(any())).thenAnswer(call -> {
            Meeting meeting=call.getArgument(0);
            assertThat(meeting.getSource()).isEqualTo(MeetingSource.SLACK_TEXT);
            order.add("agent"); return new AgentResult("완료",1,0,0);
        });
        service.submitFromSlack(new MeetingRequest("새 Slack 회의"), m -> {
            assertThat(m.getId()).isNull(); assertThat(meetings.count()).isZero(); order.add("started");
        },m -> { });
        assertThat(order).containsExactly("started","agent");
    }
    @Test void exactDuplicateDoesNotStartMessageOrAgentAgain() {
        // API에서 등록한 동일 본문도 Slack에서는 재처리나 새 진행 알림을 만들지 않는다.
        var original=service.submit(new MeetingRequest("같은 회의"));
        var callbacks=new ArrayList<Long>();
        var repeated=service.submitFromSlack(new MeetingRequest("같은 회의"), m -> callbacks.add(m.getId()),m -> { });
        assertThat(repeated.meetingId()).isEqualTo(original.meetingId());
        assertThat(callbacks).isEmpty(); assertThat(meetings.count()).isEqualTo(1);
        verify(agent,times(1)).process(any()); verify(notifier,times(1)).send(any());
    }
    @Test void progressPostFailureDoesNotCreateMeetingOrCallAgent() {
        // 봇 미초대 게시 실패는 해시를 저장하지 않아 같은 본문 재시도를 막지 않는다.
        assertThatThrownBy(() -> service.submitFromSlack(new MeetingRequest("게시 실패"), m -> {
            throw new NotificationDeliveryException("not_in_channel");
        },m -> { })).isInstanceOf(NotificationDeliveryException.class);
        verifyNoInteractions(agent); verify(notifier,never()).send(any());
        assertThat(meetings.count()).isZero();
        assertThat(logs.count()).isZero();
        assertThat(references.count()).isZero();
    }
    @Test void identicalBodyCanBeProcessedAfterBotIsInvited() {
        // 미초대 실패 후 초대된 상태의 같은 본문은 새 회의로 정상 처리한다.
        var request=new MeetingRequest("초대 후 같은 본문");
        assertThatThrownBy(() -> service.submitFromSlack(request,m -> {
            throw new NotificationDeliveryException("not_in_channel");
        },m -> { })).isInstanceOf(NotificationDeliveryException.class);
        var response=service.submitFromSlack(request,m -> { },m -> { });
        assertThat(response.status()).isEqualTo(MeetingStatus.DONE);
        assertThat(meetings.count()).isEqualTo(1);
        verify(agent,times(1)).process(any());
    }

    @Test void simultaneousIdenticalSubmissionsPostBeforeSaveButProcessOnlyOnce() throws Exception {
        // 두 요청 모두 게시한 경합에서도 해시 제약으로 Meeting·LLM·완료 알림은 하나만 남긴다.
        var bothPosted=new java.util.concurrent.CountDownLatch(2);
        var discarded=new java.util.concurrent.atomic.AtomicInteger();
        java.util.function.Consumer<Meeting> post=m -> {
            assertThat(m.getId()).isNull(); bothPosted.countDown();
            try { assertThat(bothPosted.await(5,java.util.concurrent.TimeUnit.SECONDS)).isTrue(); }
            catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); throw new IllegalStateException(); }
        };
        var executor=java.util.concurrent.Executors.newFixedThreadPool(2);
        try {
            var request=new MeetingRequest("동시 Slack 본문");
            var first=executor.submit(() -> service.submitFromSlack(request,post,m -> discarded.incrementAndGet()));
            var second=executor.submit(() -> service.submitFromSlack(request,post,m -> discarded.incrementAndGet()));
            var a=first.get(10,java.util.concurrent.TimeUnit.SECONDS); var b=second.get(10,java.util.concurrent.TimeUnit.SECONDS);
            assertThat(a.meetingId()).isEqualTo(b.meetingId());
            assertThat(meetings.count()).isEqualTo(1); assertThat(discarded.get()).isEqualTo(1);
            verify(agent,times(1)).process(any()); verify(notifier,times(1)).send(any());
        } finally { executor.shutdownNow(); }
    }

}
