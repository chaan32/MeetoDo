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
}
