package com.meetodo;

import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.model.ChatModel;
import com.meetodo.meeting.MeetingAgent;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class ApplicationSmokeTests {

    @Autowired
    MockMvc mvc;

    @Autowired
    JdbcTemplate jdbc;

    @Autowired
    ApplicationContext context;

    // 검증: startsWithoutAnAiModelOrApiKey 시나리오의 기대 동작을 확인한다.
    @Test
    void startsWithoutAnAiModelOrApiKey() {
        assertThat(context.getBeansOfType(ChatModel.class)).isEmpty();
        assertThat(context.getBeansOfType(MeetingAgent.class)).isEmpty();
    }

    // 검증: reportsMeetingProcessingAsDisabledInTestProfile 시나리오의 기대 동작을 확인한다.
    @Test
    void reportsMeetingProcessingAsDisabledInTestProfile() throws Exception {
        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post("/api/meetings")
                        .contentType(org.springframework.http.MediaType.APPLICATION_JSON)
                        .content("{\"rawText\":\"회의 내용\"}"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("MEETING_AGENT_DISABLED"));
    }

    // 검증: healthReportsUp 시나리오의 기대 동작을 확인한다.
    @Test
    void healthReportsUp() throws Exception {
        mvc.perform(get("/actuator/health"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("UP"));
    }

    // 검증: servesOpenApiDocument 시나리오의 기대 동작을 확인한다.
    @Test
    void servesOpenApiDocument() throws Exception {
        mvc.perform(get("/v3/api-docs"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.openapi").exists());
    }

    // 검증: servesSwaggerUi 시나리오의 기대 동작을 확인한다.
    @Test
    void servesSwaggerUi() throws Exception {
        mvc.perform(get("/swagger-ui/index.html"))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("Swagger UI")));
    }

    // 검증: connectsToTheIsolatedTestDatabase 시나리오의 기대 동작을 확인한다.
    @Test
    void connectsToTheIsolatedTestDatabase() {
        assertThat(jdbc.queryForObject("select 1", Integer.class)).isEqualTo(1);
        assertThat(context.getEnvironment().getProperty("spring.datasource.url"))
                .startsWith("jdbc:h2:mem:");
    }
}
