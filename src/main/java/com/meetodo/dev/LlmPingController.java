package com.meetodo.dev;

import java.util.concurrent.TimeUnit;

import io.swagger.v3.oas.annotations.Operation;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.context.annotation.Profile;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/dev")
@Profile("local & openai & !test")
public class LlmPingController {

    private final ChatClient chatClient;

    public LlmPingController(ChatClient.Builder builder) {
        this.chatClient = builder.build();
    }

    @GetMapping("/llm-ping")
    @Operation(summary = "개발용 OpenAI 연결 확인",
            description = "고정 질문으로 실제 OpenAI를 호출합니다. 호출 비용이 발생할 수 있습니다.")
    public ResponseEntity<?> ping() {
        long startedAt = System.nanoTime();
        try {
            String text = chatClient.prompt()
                    .user("Reply with OK only.")
                    .call()
                    .content();
            long elapsedMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startedAt);
            if (text == null || text.isBlank()) {
                return failed();
            }
            return ResponseEntity.ok().cacheControl(CacheControl.noStore())
                    .body(new PingResponse(text, elapsedMs));
        } catch (RuntimeException exception) {
            // Provider exceptions can contain response bodies or credential fragments.
            // Do not log or return the exception message or its cause.
            return failed();
        }
    }

    private ResponseEntity<PingError> failed() {
        return ResponseEntity.status(HttpStatus.BAD_GATEWAY).cacheControl(CacheControl.noStore())
                .body(new PingError("OpenAI connection check failed."));
    }

    public record PingResponse(String text, long elapsedMs) {
    }

    public record PingError(String error) {
    }
}
