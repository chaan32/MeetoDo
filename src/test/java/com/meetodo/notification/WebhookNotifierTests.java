package com.meetodo.notification;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.net.InetSocketAddress;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class WebhookNotifierTests {
    private final ObjectMapper mapper = new ObjectMapper();
    private final AtomicReference<byte[]> received = new AtomicReference<>();
    private final AtomicReference<String> query = new AtomicReference<>();
    private final AtomicInteger requests = new AtomicInteger();
    private final NotificationMessage message = mock(NotificationMessage.class);
    private HttpServer server;
    private int status = 200;

    @BeforeEach
    void startServer() throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            requests.incrementAndGet();
            received.set(exchange.getRequestBody().readAllBytes());
            query.set(exchange.getRequestURI().getRawQuery());
            exchange.sendResponseHeaders(status, -1);
            exchange.close();
        });
        server.start();
    }

    @AfterEach
    void stopServer() {
        server.stop(0);
    }

    @Test
    void slackSendsRenderedTextAndBlocks() throws Exception {
        // Slack receives the renderer's fallback text and structured Block Kit body.
        SlackPayloadRenderer renderer = mock(SlackPayloadRenderer.class);
        Map<String, Object> payload = Map.of("text", "회의 결과", "blocks", List.of(Map.of("type", "divider")));
        when(renderer.render(message)).thenReturn(payload);
        Notifier notifier = new SlackNotifier(url(), new WebhookHttpClient(mapper), renderer);
        notifier.send(message);
        assertThat(notifier.type()).isEqualTo("slack");
        assertThat(mapper.readTree(received.get())).isEqualTo(mapper.valueToTree(payload));
        assertThat(requests.get()).isEqualTo(1);
    }

    @Test
    void discordSendsRenderedEmbedAndDisablesMentions() throws Exception {
        // Discord keeps explicit mention suppression and acknowledges message persistence.
        DiscordPayloadRenderer renderer = discordRenderer();
        Notifier notifier = new DiscordNotifier(url(), new WebhookHttpClient(mapper), renderer);
        notifier.send(message);
        assertThat(notifier.type()).isEqualTo("discord");
        assertThat(query.get()).isEqualTo("wait=true");
        assertThat(mapper.readTree(received.get())).isEqualTo(mapper.valueToTree(discordPayload()));
        assertThat(requests.get()).isEqualTo(1);
    }

    @Test
    void discordPreservesQueryAndReplacesExistingWaitFlags() {
        // An existing wait=false must not downgrade acknowledgement to fire-and-forget.
        new DiscordNotifier(url() + "?thread_id=42&wait=false&wait=true&name=a%20b",
                new WebhookHttpClient(mapper), discordRenderer()).send(message);
        assertThat(query.get()).isEqualTo("thread_id=42&name=a%20b&wait=true");
        assertThat(requests.get()).isEqualTo(1);
    }

    @Test
    void discordRejectsMalformedUrlWithoutLeakingToken() {
        // Discord query manipulation must sanitize URI errors before the HTTP client sees them.
        assertThatThrownBy(() -> new DiscordNotifier("https://[sentinel-secret",
                new WebhookHttpClient(mapper), discordRenderer()).send(message))
                .isInstanceOfSatisfying(NotificationDeliveryException.class, exception -> {
                    assertThat(exception.getCode()).isEqualTo("CONFIGURATION_ERROR");
                    assertThat(exception.getMessage()).doesNotContain("sentinel-secret");
                    assertThat(exception.getCause()).isNull();
                });
        assertThat(requests.get()).isZero();
    }

    @Test
    void discordHttpFailureDoesNotRetry() {
        // Discord HTTP failures remain a single attempt, even with acknowledgement enabled.
        status = 500;
        assertThatThrownBy(() -> new DiscordNotifier(url(), new WebhookHttpClient(mapper), discordRenderer()).send(message))
                .isInstanceOfSatisfying(NotificationDeliveryException.class,
                        exception -> assertThat(exception.getCode()).isEqualTo("HTTP_ERROR"));
        assertThat(requests.get()).isEqualTo(1);
    }

    private DiscordPayloadRenderer discordRenderer() {
        DiscordPayloadRenderer renderer = mock(DiscordPayloadRenderer.class);
        when(renderer.render(message)).thenReturn(discordPayload());
        return renderer;
    }

    private Map<String, Object> discordPayload() {
        return Map.of("embeds", List.of(Map.of("title", "회의 결과", "description", "할 일 목록")),
                "allowed_mentions", Map.of("parse", List.of()));
    }

    private String url() {
        return "http://127.0.0.1:" + server.getAddress().getPort() + "/webhooks/sentinel-token";
    }
}
