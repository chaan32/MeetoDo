package com.meetodo.slack;

import com.meetodo.meeting.MeetingApplicationService;
import com.meetodo.rag.question.*;
import com.slack.api.app_backend.slash_commands.payload.SlashCommandPayload;
import com.slack.api.app_backend.slash_commands.response.SlashCommandResponse;
import com.slack.api.bolt.App;
import com.slack.api.bolt.context.builtin.SlashCommandContext;
import com.slack.api.bolt.handler.builtin.SlashCommandHandler;
import com.slack.api.bolt.request.builtin.SlashCommandRequest;
import com.slack.api.methods.MethodsClient;
import com.slack.api.model.block.SectionBlock;
import java.time.Clock;
import java.util.*;
import java.util.concurrent.RejectedExecutionException;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(OutputCaptureExtension.class)
class SlackQuestionInputTests {
    final MethodsClient client = mock(MethodsClient.class);
    final QuestionService questions = mock(QuestionService.class);
    final List<Runnable> queue = new ArrayList<>();
    final List<SlashCommandResponse> replies = new ArrayList<>();
    final SlackQuestionInputService input = new SlackQuestionInputService(questions, client, queue::add);

    @Test void questionIsQueuedBeforeCallingSharedReadOnlyService() {
        // 질문은 접수 시 모델이나 공통 서비스를 실행하지 않는다.
        input.submit("무슨 일?", "U1", replies::add);
        assertThat(queue).hasSize(1); verifyNoInteractions(questions, client);
        when(questions.ask("무슨 일?", "U1", false)).thenReturn(response("기록에 없습니다", List.of()));
        queue.getFirst().run();
        verify(questions).ask("무슨 일?", "U1", false);
        assertThat(replies).singleElement().extracting(SlashCommandResponse::getResponseType).isEqualTo("ephemeral");
    }
    @Test void emptyQuestionGivesUsageWithoutQueue() {
        // 빈 질문은 모달이나 LLM 대신 비공개 사용법을 돌려준다.
        input.submit("\u2003\u00a0", "U1", replies::add);
        assertThat(queue).isEmpty(); verifyNoInteractions(questions);
        assertThat(replies).singleElement().extracting(SlashCommandResponse::getText).asString().contains("/meetodo 질문");
    }
    @Test void unregisteredMemberGetsSelfRegistrationGuide() {
        // 등록 오류의 공급자 메시지는 쓰지 않고 앱 홈의 본인 등록을 안내한다.
        when(questions.ask(anyString(), anyString(), eq(false))).thenThrow(new RagQuestionException(403,"MEMBER_REQUIRED","private"));
        input.submit("질문", "U1", replies::add); queue.getFirst().run();
        assertThat(replies.getFirst().getText()).contains("앱 홈", "내 정보 등록").doesNotContain("private", "팀원 관리");
    }
    @Test void unexpectedFailureDoesNotLeakProviderMessage() {
        // 예외 원문과 응답 URL은 사용자 응답에 포함하지 않는다.
        when(questions.ask(anyString(), anyString(), eq(false))).thenThrow(new IllegalStateException("https://hooks.slack.com/secret xoxb-secret"));
        input.submit("질문", "U1", replies::add); queue.getFirst().run();
        assertThat(replies.getFirst().getText()).doesNotContain("hooks", "xoxb", "secret");
    }
    @Test void rejectedQueueReturnsPrivateBusyMessage() {
        // 큐 포화 시 동기 모델 호출로 우회하지 않는다.
        new SlackQuestionInputService(questions, client, job -> { throw new RejectedExecutionException("private"); }).submit("질문","U1",replies::add);
        assertThat(replies.getFirst().getResponseType()).isEqualTo("ephemeral"); verifyNoInteractions(questions, client);
    }
    @Test void routeRequiresExactQuestionWordIncludingUnicodeWhitespace() {
        // 접두어가 같은 회의 입력을 질문으로 오분류하지 않는다.
        for (String text : List.of("질문", "질문 내용", "질문\t내용", "질문\u2003내용", "질문\u00a0내용"))
            assertThat(SlackQuestionInputService.isQuestionCommand(text)).as(text).isTrue();
        for (String text : List.of("", "질문가", "회의 질문", " 질문 내용"))
            assertThat(SlackQuestionInputService.isQuestionCommand(text)).as(text).isFalse();
    }
    @Test void commandAcknowledgesBeforeQueuingAndNeverOpensModal() throws Exception {
        // 실제 단일 명령 핸들러가 ack 후 질문 큐를 사용한다.
        List<String> order = new ArrayList<>();
        var meeting = new SlackMeetingInputService(client,mock(MeetingApplicationService.class),mock(SlackBotService.class),queue::add,Clock.systemUTC());
        meeting.setQuestions(new SlackQuestionInputService(questions,client,job -> { order.add("queue"); queue.add(job); }));
        var context = dispatch(meeting,"질문 내용",order);
        assertThat(order).containsExactly("ack","queue"); verifyNoInteractions(client, questions);
        verify(context, never()).respond(any(SlashCommandResponse.class));
    }
    @Test void disabledQuestionNeverOpensMeetingModal() throws Exception {
        // RAG 비활성도 질문을 회의록 입력으로 돌리지 않는다.
        var meeting = new SlackMeetingInputService(client,mock(MeetingApplicationService.class),mock(SlackBotService.class),queue::add,Clock.systemUTC());
        var context = dispatch(meeting,"질문 내용",new ArrayList<>());
        var reply = ArgumentCaptor.forClass(SlashCommandResponse.class); verify(context).respond(reply.capture());
        assertThat(reply.getValue().getResponseType()).isEqualTo("ephemeral");
        assertThat(reply.getValue().getText()).contains("RAG", "비활성"); verifyNoInteractions(client);
    }
    @Test void emptyQuestionCommandNeverOpensModalEvenWhenDisabled() throws Exception {
        // 질문 단어만 입력하면 비활성 상태에서도 사용법을 비공개로 안내한다.
        var meeting = new SlackMeetingInputService(client,mock(MeetingApplicationService.class),mock(SlackBotService.class),queue::add,Clock.systemUTC());
        var context=dispatch(meeting,"질문\u00a0",new ArrayList<>());
        var reply=ArgumentCaptor.forClass(SlashCommandResponse.class); verify(context).respond(reply.capture());
        assertThat(reply.getValue().getText()).contains("사용법", "/meetodo 질문"); verifyNoInteractions(client);
    }
    @Test void maximumQuestionLengthExcludesCommandSeparator() {
        // 명령과 본문의 구분 공백 때문에 최대 길이 질문을 잘못 거절하지 않는다.
        input.submit("\u2003"+"가".repeat(2000),"U1",replies::add);
        assertThat(queue).hasSize(1); assertThat(replies).isEmpty();
    }
    @Test void tooLongQuestionDoesNotInvokeSharedService() {
        // 2,000자를 넘는 본문은 큐나 모델에 보내지 않는다.
        input.submit("가".repeat(2001),"U1",replies::add);
        assertThat(queue).isEmpty(); verifyNoInteractions(questions); assertThat(replies).hasSize(1);
    }
    @Test void privateDeliveryFailureNeverLogsResponseUrl(CapturedOutput output) throws Exception {
        // 응답 URL을 담은 SDK 예외가 발생해도 로그에는 일반화한 실패만 남긴다.
        var meeting = new SlackMeetingInputService(client,mock(MeetingApplicationService.class),mock(SlackBotService.class),queue::add,Clock.systemUTC());
        meeting.setQuestions(input); var context=dispatch(meeting,"질문 내용",new ArrayList<>());
        when(questions.ask("내용","U1",false)).thenReturn(response("기록에 없습니다",List.of()));
        doThrow(new java.io.IOException("https://hooks.slack.com/commands/private-response-secret")).when(context).respond(any(SlashCommandResponse.class));
        queue.getFirst().run();
        verify(questions).ask("내용","U1",false); assertThat(output).doesNotContain("private-response-secret", "hooks.slack.com");
        verifyNoInteractions(client);
    }
    @Test void renderUsesPlainTextAndKeepsCitationWhenAnswerTruncated() {
        // 사용자 텍스트는 plain_text로 제한하고 긴 답변에도 출처를 남긴다.
        var source = new QuestionResponse.Source("doc",1L,"MEETING","인용",java.time.LocalDate.of(2026,11,14),null,null,"/api/meetings/1");
        var rendered = new SlackQuestionRenderer(client).render(response("<!everyone> <@U1> https://hooks.slack.com/secret "+"가".repeat(5000),List.of(source)));
        assertThat(rendered.getText()).doesNotContain("<!everyone>","<@U1>","hooks.slack.com");
        String joined = rendered.getBlocks().stream().map(block -> ((SectionBlock)block).getText().getText()).reduce("",String::concat);
        assertThat(joined).contains("/api/meetings/1").doesNotContain("hooks.slack.com");
        assertThat(joined.length()).isLessThanOrEqualTo(3000); assertThat(rendered.getBlocks()).hasSizeLessThanOrEqualTo(50);
        assertThat(((SectionBlock)rendered.getBlocks().getFirst()).getText()).isInstanceOf(com.slack.api.model.block.composition.PlainTextObject.class);
    }
    @Test void permalinkUsesDatabaseCoordinatesAndActualSlackResponse() throws Exception {
        // 링크는 DB 출처 좌표로 조회한 Slack 응답을 그대로 사용한다.
        var result = new com.slack.api.methods.response.chat.ChatGetPermalinkResponse(); result.setOk(true);
        result.setPermalink("https://example.slack.com/archives/C1/p123");
        when(client.chatGetPermalink(any(com.slack.api.methods.request.chat.ChatGetPermalinkRequest.class))).thenReturn(result);
        var source = new QuestionResponse.Source("doc",1L,"MEETING","기록",java.time.LocalDate.of(2026,11,14),"C1","123.0","/api/meetings/1");
        var rendered = new SlackQuestionRenderer(client).render(response("현재 상태: DONE",List.of(source)));
        var request = ArgumentCaptor.forClass(com.slack.api.methods.request.chat.ChatGetPermalinkRequest.class);
        verify(client).chatGetPermalink(request.capture());
        assertThat(request.getValue().getChannel()).isEqualTo("C1"); assertThat(request.getValue().getMessageTs()).isEqualTo("123.0");
        assertThat(rendered.getBlocks().toString()).contains(result.getPermalink());
    }
    @Test void unsafePermalinkFallsBackToApiPath() throws Exception {
        // Slack 응답이라도 안전하지 않은 호스트는 클릭 링크로 만들지 않는다.
        var result = new com.slack.api.methods.response.chat.ChatGetPermalinkResponse(); result.setOk(true); result.setPermalink("https://evil.example/secret");
        when(client.chatGetPermalink(any(com.slack.api.methods.request.chat.ChatGetPermalinkRequest.class))).thenReturn(result);
        var source = new QuestionResponse.Source("doc",1L,"TASK",null,java.time.LocalDate.of(2026,11,14),"C1","123.0","/api/meetings/1");
        var rendered = new SlackQuestionRenderer(client).render(response("현재 상태: DONE",List.of(source)));
        String visible = rendered.getBlocks().stream().map(block -> ((SectionBlock)block).getText().getText()).reduce("",String::concat);
        assertThat(visible).contains("/api/meetings/1").doesNotContain("evil.example", "null");
    }
    @Test void blankCommandKeepsExistingMeetingModal() throws Exception {
        // 인자가 없는 기존 명령은 질문 서비스가 있어도 회의록 모달을 유지한다.
        var meeting = new SlackMeetingInputService(client,mock(MeetingApplicationService.class),mock(SlackBotService.class),queue::add,Clock.systemUTC());
        meeting.setQuestions(input); dispatch(meeting,"",new ArrayList<>());
        verify(client).viewsOpen(any(com.slack.api.methods.request.views.ViewsOpenRequest.class));
        verifyNoInteractions(questions); assertThat(queue).isEmpty();
    }
    private SlashCommandContext dispatch(SlackMeetingInputService meeting,String text,List<String> order) throws Exception {
        var app=mock(App.class); meeting.register(app);
        var handler=ArgumentCaptor.forClass(SlashCommandHandler.class); verify(app).command(eq("/meetodo"),handler.capture());
        var request=mock(SlashCommandRequest.class); var payload=new SlashCommandPayload(); payload.setText(text); payload.setUserId("U1");
        payload.setChannelId("C1"); payload.setTriggerId("trigger");
        when(request.getPayload()).thenReturn(payload); var context=mock(SlashCommandContext.class);
        doAnswer(call -> { order.add("ack"); return null; }).when(context).ack();
        handler.getValue().apply(request,context); return context;
    }
    private QuestionResponse response(String answer,List<QuestionResponse.Source> sources) {
        return new QuestionResponse(1L,QuestionStatus.ANSWERED,answer,sources,List.of(),List.of(),null,1,1,10,0.75,true);
    }
}
