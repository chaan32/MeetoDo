package com.meetodo.audio;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.mock.web.MockMultipartFile;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles({"test","openai"})
class AudioEndpointContractTests {
    @Autowired MockMvc mvc;
    @org.springframework.test.context.bean.override.mockito.MockitoBean SpeechTranscriber transcriber;
    @org.springframework.test.context.bean.override.mockito.MockitoBean com.meetodo.meeting.MeetingAgent agent;
    @org.junit.jupiter.api.BeforeEach void setup() {
        org.mockito.Mockito.when(transcriber.model()).thenReturn("test-transcribe");
        org.mockito.Mockito.when(transcriber.transcribeWithUsage(org.mockito.ArgumentMatchers.any(),org.mockito.ArgumentMatchers.anyString()))
                .thenReturn(new TranscriptionResult("제가 배포 로그를 정리할게요.", com.meetodo.usage.UsageCall.raw(1, "test-transcribe", 14L, 45L, 59L, null, "tokens")));
        org.mockito.Mockito.when(agent.process(org.mockito.ArgumentMatchers.any()))
                .thenReturn(new com.meetodo.meeting.AgentResult("음성 회의 처리 완료",1,0,0));
    }
    // openai 프로필의 multipart 오디오 입력을 회의 처리 진입점으로 제공한다.
    @Test void acceptsAudioMultipartAsMeetingInput() throws Exception {
        mvc.perform(multipart("/api/meetings/audio").file(new MockMultipartFile("file","meeting.wav","audio/wav",new byte[]{1,2,3})))
                .andExpect(status().isOk())
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath("$.transcript").value("제가 배포 로그를 정리할게요."))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath("$.meeting.source").value("AUDIO"))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath("$.meeting.sttModel").value("test-transcribe"))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath("$.meeting.usage.transcription.inputTokens").value(14))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath("$.meeting.usage.transcription.outputTokens").value(45));
    }
}
