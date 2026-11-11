package com.meetodo.notification;

import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.time.Duration;
import java.util.Map;

public class WebhookHttpClient {
    private final ObjectMapper objectMapper;
    private final HttpClient client;
    private final Duration requestTimeout;

    public WebhookHttpClient(ObjectMapper objectMapper) {
        this(objectMapper, Duration.ofSeconds(1), Duration.ofSeconds(3));
    }

    WebhookHttpClient(ObjectMapper objectMapper, Duration connectTimeout, Duration requestTimeout) {
        this.objectMapper = objectMapper;
        this.requestTimeout = requestTimeout;
        this.client = HttpClient.newBuilder()
                .connectTimeout(connectTimeout)
                .followRedirects(HttpClient.Redirect.NEVER)
                .build();
    }

    public void post(String webhookUrl, Map<String, Object> payload) {
        try {
            URI uri = URI.create(webhookUrl);
            if (!("https".equalsIgnoreCase(uri.getScheme()) || "http".equalsIgnoreCase(uri.getScheme()))
                    || uri.getHost() == null || uri.getUserInfo() != null) {
                throw new NotificationDeliveryException("CONFIGURATION_ERROR");
            }
            HttpRequest request = HttpRequest.newBuilder(uri)
                    .timeout(requestTimeout)
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(objectMapper.writeValueAsString(payload)))
                    .build();
            HttpResponse<Void> response = client.send(request, HttpResponse.BodyHandlers.discarding());
            if (response.statusCode() < 200 || response.statusCode() >= 300) {
                throw new NotificationDeliveryException("HTTP_ERROR");
            }
        } catch (HttpTimeoutException exception) {
            throw new NotificationDeliveryException("TIMEOUT_DELIVERY_UNKNOWN");
        } catch (IllegalArgumentException | NullPointerException exception) {
            throw new NotificationDeliveryException("CONFIGURATION_ERROR");
        } catch (IOException exception) {
            throw new NotificationDeliveryException("TRANSPORT_ERROR");
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new NotificationDeliveryException("TRANSPORT_ERROR");
        }
    }

    Duration connectTimeout() {
        return client.connectTimeout().orElseThrow();
    }

    Duration requestTimeout() {
        return requestTimeout;
    }
}
