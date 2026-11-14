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
            Runnable work = () -> {
                try { app.run(request.getBoltRequest()); }
                catch (Exception exception) { log.warn("Slack action processing failed"); }
            };
            // Slash trigger IDs expire in three seconds; never wait behind completion HTTP calls.
            if ("slash_commands".equals(request.getEnvelope().getType()) || opensModal(request.getBoltRequest())) work.run();
            else executor.execute(work);
        } catch (Exception exception) { log.warn("Slack socket event could not be handled"); }
    }
    private boolean opensModal(com.slack.api.bolt.request.Request<?> request) {
        if (!(request instanceof com.slack.api.bolt.request.builtin.BlockActionRequest action)) return false;
        var actions = action.getPayload().getActions();
        if (actions == null || actions.isEmpty()) return false;
        var first = actions.getFirst();
        if ("meetodo_manage_members".equals(first.getActionId()) || "meetodo_member_new".equals(first.getActionId())
                || "meetodo_member_select".equals(first.getActionId())
                || SlackMemberRegistrationService.REQUEST.equals(first.getActionId())
                || SlackMemberRegistrationService.EDIT.equals(first.getActionId())
                || SlackMemberRegistrationService.REJECT.equals(first.getActionId())) return true;
        if (!SlackBotRenderer.TASK_MENU_ACTION.equals(first.getActionId()) || first.getSelectedOption() == null) return false;
        String value = first.getSelectedOption().getValue();
        return value != null && (value.startsWith("manage:") || value.startsWith("reassign:") || value.startsWith("title:") || value.startsWith("due:") || value.startsWith("delete:"));
    }

}
