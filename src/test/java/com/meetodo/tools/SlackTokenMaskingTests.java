package com.meetodo.tools;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.meetodo.meeting.Meeting;
import com.meetodo.meeting.MeetingSource;
import com.meetodo.meeting.ToolCallLog;
import com.meetodo.meeting.ToolCallLogRepository;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class SlackTokenMaskingTests {
    // 봇 토큰은 인자·결과·모델 반환값 어디에도 원문으로 남지 않는다.
    @Test void redactsBotTokenFromArgumentsAndResult() { assertMasked("xoxb-123456789-123456789-secretTokenValue"); }

    // 앱 토큰도 기존 API 키와 같은 공통 실행 경계에서 마스킹한다.
    @Test void redactsAppTokenFromArgumentsAndResult() { assertMasked("xapp-1-A123456-123456-secretTokenValue"); }

    private void assertMasked(String token) {
        var logs = mock(ToolCallLogRepository.class);
        var execution = new ToolExecutionService(logs, new ObjectMapper());
        var result = execution.execute(Meeting.create("token masking test", MeetingSource.API_TEXT), "probe",
                java.util.Map.of("text", token), () -> ToolResult.success("ok", token));
        var captor = org.mockito.ArgumentCaptor.forClass(ToolCallLog.class);
        verify(logs).save(captor.capture());
        assertThat(captor.getValue().getArguments()).doesNotContain(token).contains("[REDACTED]");
        assertThat(captor.getValue().getResult()).doesNotContain(token).contains("[REDACTED]");
        assertThat(result.data()).doesNotContain(token).contains("[REDACTED]");
    }
}
