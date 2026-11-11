package com.meetodo.notification;

import java.net.URI;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.stream.Collectors;

public class DiscordNotifier implements Notifier {
    private final String webhookUrl;
    private final WebhookHttpClient http;
    private final DiscordPayloadRenderer renderer;

    public DiscordNotifier(String webhookUrl, WebhookHttpClient http, DiscordPayloadRenderer renderer) {
        this.webhookUrl = webhookUrl;
        this.http = http;
        this.renderer = renderer;
    }

    @Override
    public String type() {
        return "discord";
    }

    @Override
    public void send(NotificationMessage message) {
        http.post(acknowledgedWebhookUrl(), renderer.render(message));
    }

    private String acknowledgedWebhookUrl() {
        try {
            URI uri = URI.create(webhookUrl);
            String query = uri.getRawQuery() == null ? "" : Arrays.stream(uri.getRawQuery().split("&"))
                    .filter(part -> !part.isEmpty())
                    .filter(part -> !"wait".equals(URLDecoder.decode(part.split("=", 2)[0], StandardCharsets.UTF_8)))
                    .collect(Collectors.joining("&"));
            String base = webhookUrl.split("[?#]", 2)[0];
            return base + "?" + (query.isEmpty() ? "" : query + "&") + "wait=true";
        } catch (IllegalArgumentException | NullPointerException exception) {
            throw new NotificationDeliveryException("CONFIGURATION_ERROR");
        }
    }
}
