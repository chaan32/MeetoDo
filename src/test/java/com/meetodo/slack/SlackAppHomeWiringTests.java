package com.meetodo.slack;

import com.slack.api.methods.MethodsClient;
import com.slack.api.methods.request.views.ViewsPublishRequest;
import com.slack.api.socket_mode.SocketModeClient;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.mock.env.MockEnvironment;
import static org.mockito.Mockito.*;

class SlackAppHomeWiringTests {
    // 실제 Socket 이벤트가 앱 홈 핸들러로 연결되어 사용자 전용 Home을 게시한다.
    @SuppressWarnings("unchecked")
    @Test void homeOpenedEventPublishesHomeView() throws Exception {
        var client = mock(MethodsClient.class);
        var bot = new SlackBotService(client,"C1",mock(SlackMessageReferenceRepository.class),
                mock(com.meetodo.task.TaskRepository.class),mock(com.meetodo.task.TaskService.class),
                mock(com.meetodo.meeting.MeetingResults.class),mock(com.meetodo.meeting.MeetingRepository.class),
                new ObjectMapper(),mock(org.springframework.transaction.PlatformTransactionManager.class));
        var home = new SlackHomeService(client, mock(com.meetodo.member.MemberRepository.class),
                mock(com.meetodo.task.TaskRepository.class),mock(com.meetodo.task.TaskService.class),
                new com.meetodo.member.AdminAuthorization(""),java.time.Clock.systemUTC());
        var app = new SlackBotConfiguration().slackBoltApp(new MockEnvironment().withProperty("SLACK_BOT_TOKEN","test-token"),
                bot,mock(ObjectProvider.class),mock(SlackTaskEditService.class),home,mock(SlackMemberManagementService.class),mock(ObjectProvider.class),mock(ObjectProvider.class),mock(ObjectProvider.class));
        // Bolt's default middleware calls auth.test; this test covers handler routing with mock transports only.
        org.springframework.test.util.ReflectionTestUtils.setField(app,"middlewareList",new java.util.ArrayList<>());
        var response = new com.slack.api.methods.response.views.ViewsPublishResponse(); response.setOk(true);
        when(client.viewsPublish(any(ViewsPublishRequest.class))).thenReturn(response);
        var socket=mock(SocketModeClient.class);
        new SlackSocketDispatch(app,Runnable::run).receive(socket,"""
                {"type":"events_api","envelope_id":"home-event-1","payload":{"type":"event_callback",
                 "team_id":"T1","api_app_id":"A1","event":{"type":"app_home_opened","user":"U1","tab":"home"}}}
                """);
        verify(client).viewsPublish(any(ViewsPublishRequest.class));
    }
    // Home 완료 액션은 채널 좌표를 요구하는 핸들러 대신 Home 공통 서비스로 보낸다.
    @SuppressWarnings("unchecked")
    @Test void homeCompletionRoutesToHomeService() {
        var home=mock(SlackHomeService.class); var bot=mock(SlackBotService.class);
        var app=new SlackBotConfiguration().slackBoltApp(new MockEnvironment().withProperty("SLACK_BOT_TOKEN","test-token"),
                bot,mock(ObjectProvider.class),mock(SlackTaskEditService.class),home,mock(SlackMemberManagementService.class),mock(ObjectProvider.class),mock(ObjectProvider.class),mock(ObjectProvider.class));
        org.springframework.test.util.ReflectionTestUtils.setField(app,"middlewareList",new java.util.ArrayList<>());
        new SlackSocketDispatch(app,Runnable::run).receive(mock(SocketModeClient.class),"""
                {"type":"interactive","envelope_id":"home-complete-1","payload":{"type":"block_actions",
                 "team":{"id":"T1"},"api_app_id":"A1","user":{"id":"U1"},
                 "container":{"type":"view","view_id":"VH1"},"view":{"id":"VH1","type":"home"},
                 "actions":[{"type":"button","action_id":"meetodo_complete_task","value":"7"}]}}
                """);
        verify(home).complete("U1","7"); verifyNoInteractions(bot);
    }

    // Messages 탭 열기는 Home 갱신 이벤트로 오인하지 않는다.
    @SuppressWarnings("unchecked")
    @Test void messagesTabDoesNotPublishHome() {
        var home=mock(SlackHomeService.class);
        var app=new SlackBotConfiguration().slackBoltApp(new MockEnvironment().withProperty("SLACK_BOT_TOKEN","test-token"),
                mock(SlackBotService.class),mock(ObjectProvider.class),mock(SlackTaskEditService.class),home,mock(SlackMemberManagementService.class),mock(ObjectProvider.class),mock(ObjectProvider.class),mock(ObjectProvider.class));
        org.springframework.test.util.ReflectionTestUtils.setField(app,"middlewareList",new java.util.ArrayList<>());
        new SlackSocketDispatch(app,Runnable::run).receive(mock(SocketModeClient.class),"""
                {"type":"events_api","envelope_id":"messages-tab-1","payload":{"type":"event_callback",
                 "team_id":"T1","api_app_id":"A1","event":{"type":"app_home_opened","user":"U1","tab":"messages"}}}
                """);
        verifyNoInteractions(home);
    }

}
