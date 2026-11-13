package com.meetodo.audio;

import java.io.ByteArrayInputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import com.meetodo.member.Member;
import com.meetodo.member.MemberRepository;
import com.meetodo.meeting.MeetingApplicationService;
import com.meetodo.meeting.dto.MeetingRequest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.data.domain.Sort;
import org.springframework.mock.web.MockMultipartFile;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class AudioMeetingServiceTests {
    SpeechTranscriber transcriber = mock(SpeechTranscriber.class);
    ObjectProvider<SpeechTranscriber> providers = mock(ObjectProvider.class);
    MemberRepository members = mock(MemberRepository.class);
    MeetingApplicationService meetings = mock(MeetingApplicationService.class);
    AudioMeetingService service = new AudioMeetingService(providers, members, meetings);
    AtomicReference<Path> temporary = new AtomicReference<>();

    @BeforeEach void setUp() {
        when(providers.getIfAvailable()).thenReturn(transcriber);
        when(transcriber.model()).thenReturn("gpt-4o-mini-transcribe");
        when(members.findAll(any(Sort.class))).thenReturn(List.of());
        doAnswer(call -> {
            Path path = call.getArgument(0); temporary.set(path);
            assertThat(Files.readAllBytes(path)).containsExactly(1, 2, 3);
            return "  김민수가 API를 수정한다.  ";
        }).when(transcriber).transcribe(any(), anyString());
    }

    // 검증: 전사 결과를 기존 회의 처리로 전달하고 성공 후 원본 임시 파일을 지운다.
    @Test void submitsTranscriptAndDeletesTemporaryAudio() {
        AudioMeetingResponse result = service.submit(audio("meeting.wav", new byte[]{1,2,3}));
        assertThat(result.transcript()).isEqualTo("김민수가 API를 수정한다.");
        assertThat(result.sttModel()).isEqualTo("gpt-4o-mini-transcribe");
        assertThat(result.transcriptionTimeMs()).isNotNegative();
        verify(meetings).submitAudio(eq(new MeetingRequest(result.transcript())), eq(result.sttModel()), eq(result.transcriptionTimeMs()));
        assertThat(temporary.get()).doesNotExist();
    }

    // 검증: Slack 음성도 기존 전사를 재사용하되 진행 메시지 연결 콜백을 Slack 회의 생성에 전달한다.
    @Test void slackAudioPassesProgressCallbackAfterDeletingTemporaryFile() {
        java.util.function.Consumer<com.meetodo.meeting.Meeting> beforeSave = meeting -> meeting.recordSlackMessage("D123", "1.2");
        when(meetings.submitAudioFromSlack(any(), anyString(), anyLong(), eq(beforeSave), any())).thenAnswer(call -> {
            assertThat(temporary.get()).doesNotExist();
            return null;
        });
        service.submitFromSlack(audio("meeting.wav", new byte[]{1,2,3}), beforeSave);
        verify(meetings).submitAudioFromSlack(eq(new MeetingRequest("김민수가 API를 수정한다.")),
                eq("gpt-4o-mini-transcribe"), anyLong(), eq(beforeSave), any());
        verify(meetings, never()).submitAudio(any(), anyString(), anyLong());
    }

    // 검증: 활성 팀원 성명만 전달하며 팀·Slack ID·비활성 이름은 힌트에 넣지 않는다.
    @Test void hintsContainOnlyActiveFullNames() {
        Member active = new Member("김", "민수", "비밀팀", "SECRET_SLACK_ID");
        Member inactive = new Member("이", "지영", "팀", "U2"); inactive.deactivate();
        when(members.findAll(any(Sort.class))).thenReturn(List.of(active, inactive));
        service.submit(audio("meeting.mp3", new byte[]{1,2,3}));
        verify(transcriber).transcribe(any(), argThat(hint -> hint.contains("김민수") && !hint.contains("지영") && !hint.contains("비밀팀") && !hint.contains("SECRET_SLACK_ID")));
    }

    // 검증: 빈 업로드는 외부 전사나 회의 생성을 시작하지 않는다.
    @Test void rejectsEmptyAudio() {
        assertCode(() -> service.submit(audio("empty.wav", new byte[0])), "INVALID_AUDIO");
        verifyNoInteractions(transcriber, meetings);
    }

    // 검증: 허용하지 않은 확장자는 전사 전에 거부한다.
    @Test void rejectsUnsupportedExtension() {
        assertCode(() -> service.submit(audio("meeting.exe", new byte[]{1})), "INVALID_AUDIO");
        verifyNoInteractions(transcriber, meetings);
    }

    // 검증: 25MB를 넘는 파일은 전사 전에 거부한다.
    @Test void rejectsOversizedAudio() {
        assertCode(() -> service.submit(audio("meeting.wav", new byte[25_000_001])), "INVALID_AUDIO");
        verifyNoInteractions(transcriber, meetings);
    }

    // 검증: 신고 크기가 작아도 실제 스트림이 상한을 넘으면 거부한다.
    @Test void rejectsActualStreamLargerThanDeclaredSize() {
        MockMultipartFile file = new MockMultipartFile("file", "meeting.wav", "audio/wav", new byte[]{1}) {
            @Override public java.io.InputStream getInputStream() { return new ByteArrayInputStream(new byte[25_000_001]); }
        };
        assertCode(() -> service.submit(file), "INVALID_AUDIO");
        verifyNoInteractions(transcriber, meetings);
    }

    // 검증: 전사 공급자 실패 시 원문 예외를 감추고 임시 파일을 삭제한다.
    @Test void hidesTranscriptionFailureAndDeletesTemporaryAudio() {
        doAnswer(call -> {
            temporary.set(call.getArgument(0)); throw new IllegalStateException("secret provider detail");
        }).when(transcriber).transcribe(any(), anyString());
        assertThatThrownBy(() -> service.submit(audio("private.wav", new byte[]{1})))
                .isInstanceOf(AudioMeetingException.class).hasMessageNotContaining("secret");
        assertThat(temporary.get()).doesNotExist();
        verifyNoInteractions(meetings);
    }

    // 검증: 빈 전사는 회의를 저장하지 않으며 원본 파일을 삭제한다.
    @Test void rejectsBlankTranscriptAndDeletesTemporaryAudio() {
        invalidTranscript(" \n ");
    }

    // 검증: 20,000자 초과 전사는 회의를 저장하지 않으며 원본 파일을 삭제한다.
    @Test void rejectsOversizedTranscriptAndDeletesTemporaryAudio() {
        invalidTranscript("x".repeat(20_001));
    }

    // 검증: 회의 처리 실패 후에도 음성 임시 파일을 삭제한다.
    @Test void deletesTemporaryAudioWhenMeetingProcessingFails() {
        when(meetings.submitAudio(any(), anyString(), anyLong())).thenThrow(new IllegalStateException("meeting failed"));
        assertThatThrownBy(() -> service.submit(audio("meeting.wav", new byte[]{1,2,3}))).hasMessage("meeting failed");
        assertThat(temporary.get()).doesNotExist();
    }

    // 검증: 전사 빈이 없으면 외부 호출과 회의 저장 없이 비활성 오류를 돌려준다.
    @Test void rejectsDisabledTranscription() {
        when(providers.getIfAvailable()).thenReturn(null);
        assertCode(() -> service.submit(audio("meeting.wav", new byte[]{1})), "AUDIO_TRANSCRIPTION_DISABLED");
        verifyNoInteractions(transcriber, meetings);
    }

    // 검증: LLM 회의 처리를 시작하기 전에 원본 음성 임시 파일을 삭제한다.
    @Test void deletesAudioBeforeMeetingAgentStarts() {
        when(meetings.submitAudio(any(), anyString(), anyLong())).thenAnswer(call -> {
            assertThat(temporary.get()).doesNotExist();
            return null;
        });
        service.submit(audio("meeting.wav", new byte[]{1,2,3}));
    }

    // 검증: 확장자 없는 파일 이름이 허용 형식과 같아도 거부한다.
    @Test void rejectsFilenameWithoutExtension() {
        assertCode(() -> service.submit(audio("wav", new byte[]{1,2,3})), "INVALID_AUDIO");
        verifyNoInteractions(transcriber, meetings);
    }

    // 검증: 지원 형식의 대소문자 확장자를 허용한다.
    @ParameterizedTest(name = "supports extension {0}")
    @ValueSource(strings = {"flac", "mp3", "mp4", "mpeg", "mpga", "m4a", "ogg", "wav", "webm", "WAV"})
    void acceptsSupportedExtensions(String extension) {
        service.submit(audio("meeting." + extension, new byte[]{1,2,3}));
        assertThat(temporary.get()).doesNotExist();
    }

    // 검증: 25MB 경계값은 허용하고 전사 후 파일을 삭제한다.
    @Test void acceptsExactly25MillionBytes() {
        doAnswer(call -> {
            temporary.set(call.getArgument(0));
            assertThat(Files.size(temporary.get())).isEqualTo(25_000_000);
            return "회의록";
        }).when(transcriber).transcribe(any(), anyString());
        service.submit(audio("meeting.wav", new byte[25_000_000]));
        assertThat(temporary.get()).doesNotExist();
    }

    // 검증: 20,000자 전사 경계값을 허용한다.
    @Test void acceptsExactly20ThousandTranscriptCharacters() {
        doAnswer(call -> {
            temporary.set(call.getArgument(0)); return "x".repeat(20_000);
        }).when(transcriber).transcribe(any(), anyString());
        assertThat(service.submit(audio("meeting.wav", new byte[]{1})).transcript()).hasSize(20_000);
        assertThat(temporary.get()).doesNotExist();
    }

    // 검증: 전사 힌트는 성명 제어문자를 지우고 2,000자를 넘지 않는다.
    @Test void boundsAndSanitizesNameHints() {
        Member member = new Member("김\n", "민수", "팀", null);
        java.util.ArrayList<Member> names = new java.util.ArrayList<>();
        for (int i = 0; i < 600; i++) names.add(member);
        when(members.findAll(any(Sort.class))).thenReturn(names);
        service.submit(audio("meeting.wav", new byte[]{1,2,3}));
        verify(transcriber).transcribe(any(), argThat(hint -> hint.length() <= 2000 && !hint.contains("\n") && hint.contains("김민수")));
    }

    // 검증: null 전사 결과도 회의 생성 없이 거부한다.
    @Test void rejectsNullTranscript() {
        invalidTranscript(null);
    }

    private void invalidTranscript(String text) {
        doAnswer(call -> { temporary.set(call.getArgument(0)); return text; }).when(transcriber).transcribe(any(), anyString());
        assertCode(() -> service.submit(audio("meeting.wav", new byte[]{1})), "INVALID_TRANSCRIPT");
        assertThat(temporary.get()).doesNotExist();
        verifyNoInteractions(meetings);
    }
    private void assertCode(org.assertj.core.api.ThrowableAssert.ThrowingCallable action, String code) {
        assertThatThrownBy(action).isInstanceOfSatisfying(AudioMeetingException.class, error -> assertThat(error.getCode()).isEqualTo(code));
    }
    private MockMultipartFile audio(String name, byte[] bytes) { return new MockMultipartFile("file", name, "audio/wav", bytes); }
}
