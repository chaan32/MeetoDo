package com.meetodo.slack;

import com.slack.api.bolt.App;
import com.slack.api.bolt.socket_mode.request.SocketModeRequestParser;
import com.slack.api.socket_mode.SocketModeClient;
import com.slack.api.socket_mode.response.AckResponse;
import java.util.concurrent.Executor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

final class SlackSocketDispatch {
    private static final Logger log = LoggerFactory.getLogger(SlackSocketDispatch.class);
    private final App app;
    private final SocketModeRequestParser parser;
    private final Executor executor;
    SlackSocketDispatch(App app, Executor executor) {
        this.app = app; this.parser = new SocketModeRequestParser(app.config()); this.executor = executor;
    }
    void receive(SocketModeClient client, String message) {
        try {
            var request = parser.parse(message);
            if (request == null) return;
            // Bolt's default adapter sends the ACK only after app.run returns; send it before queued DB/HTTP work.
            client.sendSocketModeResponse(new AckResponse(request.getEnvelope().getEnvelopeId()));
            executor.execute(() -> {
                try { app.run(request.getBoltRequest()); }
                catch (Exception exception) { log.warn("Slack action processing failed"); }
            });
        } catch (Exception exception) { log.warn("Slack socket event could not be handled"); }
    }
}
