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
        var app = new App(AppConfig.builder().singleTeamBotToken("xoxb-test").build());
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
        when(app.config()).thenReturn(AppConfig.builder().singleTeamBotToken("xoxb-test").build());
        when(app.run(any())).thenAnswer(call -> { order.add("opened"); return com.slack.api.bolt.response.Response.builder().statusCode(200).build(); });
        var dispatch=new SlackSocketDispatch(app,job -> { order.add("queued"); queue.add(job); });
        dispatch.receive(client,"{\"type\":\"slash_commands\",\"envelope_id\":\"command-1\",\"payload\":{\"command\":\"/meetodo\",\"trigger_id\":\"trigger-1\",\"channel_id\":\"C1\",\"user_id\":\"U1\"}}");
        assertThat(order).containsExactly("ack","opened"); assertThat(queue).isEmpty();
    }

}
