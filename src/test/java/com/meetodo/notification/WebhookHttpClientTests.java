package com.meetodo.notification;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowableOfType;

class WebhookHttpClientTests {
    private static final String TOKEN = "sentinel-webhook-secret";
    private final ObjectMapper mapper = new ObjectMapper();
    private final AtomicInteger requests = new AtomicInteger();
    private final AtomicReference<String> body = new AtomicReference<>();
    private final ListAppender<ILoggingEvent> logs = new ListAppender<>();
    private HttpServer server;
    private int status = 200;
    private long delayMs;
    private String contentType;
    private String method;

    @BeforeEach
    void startServer() throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            requests.incrementAndGet();
            method = exchange.getRequestMethod();
            contentType = exchange.getRequestHeaders().getFirst("Content-Type");
            body.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            try {
                Thread.sleep(delayMs);
                if (status == 302) exchange.getResponseHeaders().set("Location", url() + "/redirect");
                exchange.sendResponseHeaders(status, -1);
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
            } finally {
                exchange.close();
            }
        });
        server.start();
        logs.start();
        ((Logger) LoggerFactory.getLogger(Logger.ROOT_LOGGER_NAME)).addAppender(logs);
    }

    @AfterEach
    void stopServer() {
        server.stop(0);
        ((Logger) LoggerFactory.getLogger(Logger.ROOT_LOGGER_NAME)).detachAppender(logs);
        logs.stop();
    }

    @Test
    void postsJsonBodyExactlyOnce() throws Exception {
        // Payload maps must reach the provider unchanged as UTF-8 JSON.
        Map<String, Object> payload = Map.of("text", "회의 알림", "blocks", java.util.List.of(Map.of("type", "divider")));
        new WebhookHttpClient(mapper).post(url(), payload);
        assertThat(requests.get()).isEqualTo(1);
        assertThat(method).isEqualTo("POST");
        assertThat(contentType).isEqualTo("application/json");
        assertThat(mapper.readTree(body.get())).isEqualTo(mapper.valueToTree(payload));
    }

    @Test
    void serverFailureIsSanitizedAndNotRetried() {
        // A provider failure must not expose webhook credentials or trigger duplicate delivery.
        status = 503;
        assertSafeFailure(new WebhookHttpClient(mapper), url(), "HTTP_ERROR");
        assertThat(requests.get()).isEqualTo(1);
    }

    @Test
    void redirectsAreNotFollowed() {
        // Redirects could forward a secret-bearing webhook URL to an untrusted endpoint.
        status = 302;
        assertSafeFailure(new WebhookHttpClient(mapper), url(), "HTTP_ERROR");
        assertThat(requests.get()).isEqualTo(1);
    }

    @Test
    void timeoutReportsUnknownDeliveryWithoutRetry() {
        // Once sent, a timed-out request may already have been accepted by the provider.
        delayMs = 500;
        assertSafeFailure(new WebhookHttpClient(mapper, Duration.ofSeconds(1), Duration.ofMillis(100)),
                url(), "TIMEOUT_DELIVERY_UNKNOWN");
        assertThat(requests.get()).isEqualTo(1);
    }

    @Test
    void unreachableEndpointIsSanitized() {
        // Connection errors can contain the URL in their original cause.
        String unreachable = url();
        server.stop(0);
        assertSafeFailure(new WebhookHttpClient(mapper), unreachable, "TRANSPORT_ERROR");
    }

    @Test
    void invalidUrlIsSanitized() {
        // URL parser exception messages must not leak credentials.
        assertSafeFailure(new WebhookHttpClient(mapper), "https://[" + TOKEN, "CONFIGURATION_ERROR");
    }

    @Test
    void unsupportedSchemeIsRejected() {
        // Only HTTP protocols are meaningful webhook destinations.
        assertSafeFailure(new WebhookHttpClient(mapper), "file:///" + TOKEN, "CONFIGURATION_ERROR");
    }

    @Test
    void defaultTimeoutsAreOneAndThreeSeconds() {
        // Keep the approved connection and delivery bounds explicit.
        WebhookHttpClient client = new WebhookHttpClient(mapper);
        assertThat(client.connectTimeout()).isEqualTo(Duration.ofSeconds(1));
        assertThat(client.requestTimeout()).isEqualTo(Duration.ofSeconds(3));
    }

    private String url() {
        return "http://127.0.0.1:" + server.getAddress().getPort() + "/" + TOKEN;
    }

    private void assertSafeFailure(WebhookHttpClient client, String target, String code) {
        NotificationDeliveryException failure = catchThrowableOfType(NotificationDeliveryException.class,
                () -> client.post(target, Map.of("text", "safe")));
        assertThat(failure).isNotNull();
        assertThat(failure.getCode()).isEqualTo(code);
        assertThat(failure.getMessage()).doesNotContain(TOKEN, target);
        assertThat(failure.getCause()).isNull();
        assertThat(logs.list).allSatisfy(event -> {
            assertThat(event.getFormattedMessage()).doesNotContain(TOKEN);
            assertThat(event.getThrowableProxy()).isNull();
        });
    }
}
