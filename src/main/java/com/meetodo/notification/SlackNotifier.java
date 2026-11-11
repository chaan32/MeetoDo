package com.meetodo.notification;

public class SlackNotifier implements Notifier {
    private final String webhookUrl;
    private final WebhookHttpClient http;
    private final SlackPayloadRenderer renderer;

    public SlackNotifier(String webhookUrl, WebhookHttpClient http, SlackPayloadRenderer renderer) {
        this.webhookUrl = webhookUrl;
        this.http = http;
        this.renderer = renderer;
    }

    @Override
    public String type() {
        return "slack";
    }

    @Override
    public void send(NotificationMessage message) {
        http.post(webhookUrl, renderer.render(message));
    }
}
