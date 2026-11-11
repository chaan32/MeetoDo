package com.meetodo;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.model.ChatModel;
import com.meetodo.meeting.MeetingAgent;
import org.springframework.boot.test.context.ConfigDataApplicationContextInitializer;
import org.springframework.boot.test.context.runner.WebApplicationContextRunner;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class LlmPingProfileTests {

    // 검증: loadsOpenAiMeetingAgentOnlyWithLocalAndOpenAiProfiles 시나리오의 기대 동작을 확인한다.
    @Test
    void loadsOpenAiMeetingAgentOnlyWithLocalAndOpenAiProfiles() {
        new WebApplicationContextRunner()
                .withInitializer(new ConfigDataApplicationContextInitializer())
                .withUserConfiguration(MeetoDoApplication.class)
                .withPropertyValues(
                        "spring.profiles.active=local,openai",
                        "OPENAI_API_KEY=integration-test-key",
                        "spring.datasource.url=jdbc:h2:mem:openai-profile-test",
                        "spring.jpa.hibernate.ddl-auto=none")
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(context).hasSingleBean(ChatModel.class);
                    assertThat(context).hasSingleBean(MeetingAgent.class);
                });
    }

    // 검증: hidesEndpointAndOpenAiOutsideDevProfiles 시나리오의 기대 동작을 확인한다.
    @ParameterizedTest
    @ValueSource(strings = {"", "local", "openai", "test", "test,openai",
            "local,openai,test", "test,openai,local"})
    void hidesEndpointAndOpenAiOutsideDevProfiles(String profiles) {
        new WebApplicationContextRunner()
                .withInitializer(new ConfigDataApplicationContextInitializer())
                .withUserConfiguration(MeetoDoApplication.class)
                .withPropertyValues(
                        "spring.profiles.active=" + profiles,
                        "spring.datasource.url=jdbc:h2:mem:profile-test",
                        "spring.jpa.hibernate.ddl-auto=none")
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(context).doesNotHaveBean(ChatModel.class);
                    assertThat(context).doesNotHaveBean(MeetingAgent.class);
                    assertThat(context).doesNotHaveBean("llmPingController");
                    var mvc = MockMvcBuilders.webAppContextSetup(context).build();
                    mvc.perform(get("/api/dev/llm-ping")).andExpect(status().isNotFound());
                    mvc.perform(get("/v3/api-docs"))
                            .andExpect(status().isOk())
                            .andExpect(jsonPath("$.paths['/api/dev/llm-ping']").doesNotExist());
                });
    }
}
