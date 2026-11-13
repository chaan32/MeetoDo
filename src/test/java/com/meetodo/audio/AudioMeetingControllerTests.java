package com.meetodo.audio;

import com.meetodo.meeting.MeetingStatus;
import com.meetodo.meeting.dto.MeetingResponse;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

class AudioMeetingControllerTests {
    AudioMeetingService service = mock(AudioMeetingService.class);
    MockMvc mvc;
    @BeforeEach void setUp() {
        mvc = MockMvcBuilders.standaloneSetup(new AudioMeetingController(service))
                .setControllerAdvice(new AudioMeetingExceptionHandler()).build();
    }

    // 검증: multipart 응답은 전사문·STT 측정값·기존 회의 결과를 함께 돌려준다.
    @Test void returnsTranscriptAndMeetingResult() throws Exception {
        MeetingResponse meeting = completedMeeting();
        when(service.submit(any())).thenReturn(new AudioMeetingResponse("회의록", "gpt-4o-mini-transcribe", 12L, meeting));
        mvc.perform(multipart("/api/meetings/audio").file(file()))
                .andExpect(status().isOk()).andExpect(jsonPath("$.transcript").value("회의록"))
                .andExpect(jsonPath("$.sttModel").value("gpt-4o-mini-transcribe"))
                .andExpect(jsonPath("$.transcriptionTimeMs").value(12));
    }

    // 검증: 필수 file 파트 누락은 일관된 400 오류로 응답한다.
    @Test void rejectsMissingFile() throws Exception {
        mvc.perform(multipart("/api/meetings/audio"))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("INVALID_AUDIO"));
        verifyNoInteractions(service);
    }

    // 검증: 전사 오류는 일반화한 502 코드와 메시지로 응답한다.
    @Test void returnsGeneralizedTranscriptionError() throws Exception {
        when(service.submit(any())).thenThrow(new AudioMeetingException(HttpStatus.BAD_GATEWAY,
                "AUDIO_TRANSCRIPTION_FAILED", "음성 전사를 완료하지 못했습니다."));
        mvc.perform(multipart("/api/meetings/audio").file(file()))
                .andExpect(status().isBadGateway()).andExpect(jsonPath("$.code").value("AUDIO_TRANSCRIPTION_FAILED"))
                .andExpect(jsonPath("$.message").value("음성 전사를 완료하지 못했습니다."));
    }

    // 서블릿의 업로드 크기 거절도 400 INVALID_AUDIO로 일반화한다.
    @Test void uploadLimitFailureReturnsBadRequestWithoutProviderDetails() throws Exception {
        when(service.submit(any())).thenThrow(new org.springframework.web.multipart.MaxUploadSizeExceededException(25_000_000));
        mvc.perform(multipart("/api/meetings/audio").file(file()))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("INVALID_AUDIO"))
                .andExpect(jsonPath("$.fieldErrors.file").exists());
    }

    private MeetingResponse completedMeeting() {
        MeetingResponse meeting = mock(MeetingResponse.class);
        when(meeting.status()).thenReturn(MeetingStatus.DONE);
        return meeting;
    }

    private MockMultipartFile file() { return new MockMultipartFile("file", "meeting.wav", "audio/wav", new byte[]{1}); }
}
