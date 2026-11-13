package com.meetodo.slack;

import com.slack.api.bolt.App;
import com.slack.api.bolt.AppConfig;
import com.slack.api.socket_mode.SocketModeClient;
import java.util.ArrayList;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class SlackSocketDispatchTests {
    @Test void wireAckIsSentBeforeWorkCanBeExecuted() {
        // ctx.ack() 반환 시점이 아닌 실제 소켓 ACK 전송 이후에만 작업을 큐에 넣는다.
        var order = new ArrayList<String>();
        var queue = new ArrayList<Runnable>();
        var client = mock(SocketModeClient.class);
        doAnswer(call -> { order.add("ack"); return null; }).when(client).sendSocketModeResponse(any(com.slack.api.socket_mode.response.SocketModeResponse.class));
        var app = new App(AppConfig.builder().singleTeamBotToken(String.join("", "xox", "b-test")).build());
        var dispatch = new SlackSocketDispatch(app, job -> { order.add("queued"); queue.add(job); });
        dispatch.receive(client, "{\"type\":\"interactive\",\"envelope_id\":\"envelope-1\",\"accepts_response_payload\":false,\"payload\":{\"type\":\"block_actions\",\"user\":{\"id\":\"U1\"},\"api_app_id\":\"A1\",\"token\":\"ignored\",\"container\":{\"type\":\"message\",\"channel_id\":\"C1\",\"message_ts\":\"1.2\"},\"actions\":[{\"action_id\":\"meetodo_complete_task\",\"value\":\"1\",\"type\":\"button\"}]}}");
        assertThat(order).containsExactly("ack", "queued");
        assertThat(queue).hasSize(1);
    }
    @Test void slashCommandOpensModalWithoutWaitingBehindActionQueue() throws Exception {
        // 3초 trigger가 기존 버튼 작업 대기열에서 만료되지 않도록 ACK 후 명령을 바로 처리한다.
        var order=new ArrayList<String>(); var queue=new ArrayList<Runnable>();
        var client=mock(SocketModeClient.class);
        doAnswer(call -> { order.add("ack"); return null; }).when(client).sendSocketModeResponse(any(com.slack.api.socket_mode.response.SocketModeResponse.class));
        var app=mock(App.class);
        when(app.config()).thenReturn(AppConfig.builder().singleTeamBotToken(String.join("", "xox", "b-test")).build());
        when(app.run(any())).thenAnswer(call -> { order.add("opened"); return com.slack.api.bolt.response.Response.builder().statusCode(200).build(); });
        var dispatch=new SlackSocketDispatch(app,job -> { order.add("queued"); queue.add(job); });
        dispatch.receive(client,"{\"type\":\"slash_commands\",\"envelope_id\":\"command-1\",\"payload\":{\"command\":\"/meetodo\",\"trigger_id\":\"trigger-1\",\"channel_id\":\"C1\",\"user_id\":\"U1\"}}");
        assertThat(order).containsExactly("ack","opened"); assertThat(queue).isEmpty();
    }

    @Test void managementMenuOpensPrivateModalImmediatelyAfterWireAck() throws Exception {
        // 개인 관리 모달의 trigger도 ACK 직후 대기열을 건너뛴다.
        assertMenuDispatch("manage:7", true);
    }

    @Test void titleMenuOpensModalImmediatelyAfterWireAck() throws Exception {
        // 제목 수정의 실제 overflow 페이로드는 ACK 후 기존 완료 대기열을 건너뛴다.
        assertMenuDispatch("title:7", true);
    }

    @Test void dueDateMenuOpensModalImmediatelyAfterWireAck() throws Exception {
        // 마감일 수정 trigger도 ACK 후 모달 호출까지 대기열에서 기다리지 않는다.
        assertMenuDispatch("due:7", true);
    }

    @Test void deleteMenuOpensConfirmationImmediatelyAfterWireAck() throws Exception {
        // 삭제 확인 모달의 trigger도 완료 작업 대기열에서 만료되지 않는다.
        assertMenuDispatch("delete:7", true);
    }

    @Test void reopenMenuStaysQueuedAfterWireAck() throws Exception {
        // trigger를 사용하지 않는 재개 DB 작업은 ACK 뒤 일반 작업 대기열에 넣는다.
        assertMenuDispatch("reopen:7", false);
    }

    @Test void modalSubmissionStaysQueuedAfterWireAck() throws Exception {
        // 모달 제출은 실제 소켓 ACK가 나간 뒤에만 수정 작업을 실행한다.
        var order = new ArrayList<String>(); var queue = new ArrayList<Runnable>();
        var client = mock(SocketModeClient.class);
        doAnswer(call -> { order.add("ack"); return null; }).when(client).sendSocketModeResponse(any(com.slack.api.socket_mode.response.SocketModeResponse.class));
        var app = mock(App.class);
        when(app.config()).thenReturn(AppConfig.builder().singleTeamBotToken("test-token").build());
        when(app.run(any())).thenAnswer(call -> { order.add("mutated"); return com.slack.api.bolt.response.Response.builder().statusCode(200).build(); });
        var dispatch = new SlackSocketDispatch(app, job -> { order.add("queued"); queue.add(job); });
        dispatch.receive(client, """
                {"type":"interactive","envelope_id":"submit-1","payload":{"type":"view_submission",
                 "user":{"id":"U1"},"view":{"id":"V1","callback_id":"meetodo_task_edit","type":"modal",
                 "state":{"values":{"task_value":{"value":{"type":"plain_text_input","value":"수정 제목"}}}}}}}
                """);
        assertThat(order).containsExactly("ack", "queued");
        verify(app, never()).run(any());
        assertThat(queue).hasSize(1); queue.getFirst().run();
        assertThat(order).containsExactly("ack", "queued", "mutated");
    }

    private void assertMenuDispatch(String operation, boolean immediate) throws Exception {
        var order = new ArrayList<String>(); var queue = new ArrayList<Runnable>();
        var client = mock(SocketModeClient.class);
        doAnswer(call -> { order.add("ack"); return null; }).when(client).sendSocketModeResponse(any(com.slack.api.socket_mode.response.SocketModeResponse.class));
        var app = mock(App.class);
        when(app.config()).thenReturn(AppConfig.builder().singleTeamBotToken("test-token").build());
        when(app.run(any())).thenAnswer(call -> { order.add("handled"); return com.slack.api.bolt.response.Response.builder().statusCode(200).build(); });
        var dispatch = new SlackSocketDispatch(app, job -> { order.add("queued"); queue.add(job); });
        dispatch.receive(client, """
                {"type":"interactive","envelope_id":"menu-1","payload":{"type":"block_actions","trigger_id":"trigger-1",
                 "user":{"id":"U1"},"container":{"type":"message","channel_id":"C1","message_ts":"1.2"},
                 "actions":[{"action_id":"meetodo_task_menu","type":"overflow",
                 "selected_option":{"text":{"type":"plain_text","text":"선택"},"value":"%s"}}]}}
                """.formatted(operation));
        if (immediate) {
            assertThat(order).containsExactly("ack", "handled"); assertThat(queue).isEmpty();
        } else {
            assertThat(order).containsExactly("ack", "queued"); verify(app, never()).run(any());
            assertThat(queue).hasSize(1); queue.getFirst().run();
            assertThat(order).containsExactly("ack", "queued", "handled");
        }
    }

}
