package com.meetodo.slack;

import com.slack.api.bolt.socket_mode.SocketModeApp;
import org.slf4j.LoggerFactory;

final class SlackBotSocketRuntime {
    private final SocketModeApp socket;
    SlackBotSocketRuntime(SocketModeApp socket) { this.socket = socket; }
    public void start() {
        try { socket.startAsync(); }
        catch (Exception exception) { throw new IllegalStateException("Slack socket startup failed"); }
    }
    public void close() {
        try { socket.close(); }
        catch (Exception exception) { LoggerFactory.getLogger(getClass()).warn("Slack socket shutdown failed"); }
    }
}
