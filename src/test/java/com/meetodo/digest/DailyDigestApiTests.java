package com.meetodo.digest;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.BeforeEach;
import com.meetodo.task.TaskRepository;
import com.meetodo.task.TaskMentionRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles({"local", "test"})
class DailyDigestApiTests {
    @Autowired MockMvc mvc;
    @Autowired DailyDigestRepository digests;
    @Autowired TaskRepository tasks;
    @Autowired TaskMentionRepository mentions;
    @BeforeEach void clean() { mentions.deleteAll(); tasks.deleteAll(); digests.deleteAll(); }

    // 검증: 개발 수동 실행은 OpenAI나 스케줄 활성화 없이 실행할 수 있다.
    @Test void manualRunWorksWithoutOpenaiOrScheduling() throws Exception {
        mvc.perform(post("/api/dev/digest/run").param("date", "2026-11-12"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.status").value("SKIPPED"))
                .andExpect(jsonPath("$.digestDate").value("2026-11-12"));
    }

    // 검증: 날짜 형식 오류는 공급자 예외를 거치지 않고 검증 응답으로 반환한다.
    @Test void invalidDateIsValidationError() throws Exception {
        mvc.perform(post("/api/dev/digest/run").param("date", "2026-99-99"))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("VALIDATION_ERROR"));
    }
}
