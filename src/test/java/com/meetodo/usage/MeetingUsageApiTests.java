package com.meetodo.usage;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.meetodo.meeting.*;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest(properties={"spring.datasource.url=jdbc:h2:mem:meeting-usage;DB_CLOSE_DELAY=-1",
        "MEETODO_PRICE_CHAT_MODEL=chat-model", "MEETODO_PRICE_CHAT_INPUT_PER_MILLION=2", "MEETODO_PRICE_CHAT_OUTPUT_PER_MILLION=8"})
@AutoConfigureMockMvc
@ActiveProfiles("test")
class MeetingUsageApiTests {
    @Autowired MockMvc mvc;
    @Autowired ObjectMapper mapper;
    @Autowired MeetingRepository meetings;
    @MockitoBean MeetingAgent agent;

    @Test void postAndGetReturnPersistedUsageAndEstimatedCost() throws Exception {
        // POST와 재조회가 같은 저장된 호출별 사용량·합계·단가 snapshot을 반환한다.
        var usage = new UsageReport(List.of(UsageCall.raw(1, "chat-model", 100L, 20L, 120L, null, "tokens")), null);
        when(agent.process(any())).thenReturn(new AgentResult("완료", 1, 0, 0, usage));
        String json = mvc.perform(post("/api/meetings").contentType(MediaType.APPLICATION_JSON)
                .content("{\"rawText\":\"사용량 저장 API 검증 회의\"}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.usage.inputTokens").value(100))
                .andExpect(jsonPath("$.usage.outputTokens").value(20))
                .andExpect(jsonPath("$.usage.estimatedCostUsd").value(0.00036))
                .andExpect(jsonPath("$.usage.costStatus").value("ESTIMATED"))
                .andReturn().getResponse().getContentAsString();
        long id = mapper.readTree(json).path("meetingId").asLong();
        mvc.perform(get("/api/meetings/{id}", id)).andExpect(status().isOk())
                .andExpect(jsonPath("$.usage.totalTokens").value(120))
                .andExpect(jsonPath("$.usage.chatCalls[0].model").value("chat-model"))
                .andExpect(jsonPath("$.usage.estimatedCostUsd").value(0.00036));
        assertThat(meetings.findById(id).orElseThrow().getUsageReport().estimatedCostUsd()).isEqualByComparingTo("0.00036");
    }
    @Test void failedMeetingRetainsReportedCallsAndMarksCostIncomplete() throws Exception {
        // LLM 실패 뒤에도 앞서 확인한 사용량은 남고 실패 호출 때문에 전체 비용은 확정하지 않는다.
        var usage = new UsageReport(List.of(UsageCall.raw(1, "chat-model", 100L, 20L, 120L, null, "tokens"),
                UsageCall.raw(2, null, null, null, null, null, "unavailable")), null);
        when(agent.process(any())).thenThrow(new MeetingAgentException("LLM_ERROR", 2, 1, usage));
        mvc.perform(post("/api/meetings").contentType(MediaType.APPLICATION_JSON)
                .content("{\"rawText\":\"사용량 실패 보존 API 검증 회의\"}"))
                .andExpect(status().is5xxServerError());
        Meeting failed = meetings.findByContentHash(Meeting.hashContent("사용량 실패 보존 API 검증 회의")).orElseThrow();
        assertThat(failed.getStatus()).isEqualTo(MeetingStatus.FAILED);
        mvc.perform(get("/api/meetings/{id}", failed.getId())).andExpect(status().isOk())
                .andExpect(jsonPath("$.usage.inputTokens").value(100))
                .andExpect(jsonPath("$.usage.chatCalls.length()").value(2))
                .andExpect(jsonPath("$.usage.estimatedCostUsd").isEmpty())
                .andExpect(jsonPath("$.usage.costStatus").value("PARTIAL_USAGE"));
    }
    @Test void oldMeetingWithoutUsageReturnsUnavailableWithoutBackfill() throws Exception {
        // 기존 데이터는 토큰·비용을 지어내지 않고 미제공 상태로 조회된다.
        Meeting legacy = Meeting.create("기존 회의 사용량 미제공", MeetingSource.API_TEXT);
        legacy.startProcessing(java.time.Instant.parse("2026-11-14T00:00:00Z"));
        legacy.complete("완료", java.time.Instant.parse("2026-11-14T00:00:01Z"), 1, 0, 0);
        long id = meetings.saveAndFlush(legacy).getId();
        mvc.perform(get("/api/meetings/{id}", id)).andExpect(status().isOk())
                .andExpect(jsonPath("$.usage.inputTokens").isEmpty())
                .andExpect(jsonPath("$.usage.estimatedCostUsd").isEmpty())
                .andExpect(jsonPath("$.usage.usageStatus").value("UNAVAILABLE"));
    }
}
