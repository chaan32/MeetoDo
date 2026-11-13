package com.meetodo.audio;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import com.meetodo.member.Member;
import com.meetodo.member.MemberRepository;
import com.meetodo.meeting.MeetingApplicationService;
import com.meetodo.meeting.dto.MeetingRequest;
import com.meetodo.meeting.dto.MeetingResponse;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.data.domain.Sort;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

@Service
public class AudioMeetingService {
    private static final int MAX_BYTES = 25_000_000;
    private static final Set<String> EXTENSIONS = Set.of("flac", "mp3", "mp4", "mpeg", "mpga", "m4a", "ogg", "wav", "webm");
    private final ObjectProvider<SpeechTranscriber> transcribers;
    private final MemberRepository members;
    private final MeetingApplicationService meetings;

    public AudioMeetingService(ObjectProvider<SpeechTranscriber> transcribers, MemberRepository members,
                               MeetingApplicationService meetings) {
        this.transcribers = transcribers;
        this.members = members;
        this.meetings = meetings;
    }

    public AudioMeetingResponse submit(MultipartFile file) {
        String extension = extension(file);
        SpeechTranscriber transcriber = transcribers.getIfAvailable();
        if (transcriber == null) throw new AudioMeetingException(HttpStatus.NOT_FOUND,
                "AUDIO_TRANSCRIPTION_DISABLED", "음성 전사 기능이 비활성화되어 있습니다.");
        Path temporary = null;
        try {
            byte[] bytes;
            try (InputStream stream = file.getInputStream()) { bytes = stream.readNBytes(MAX_BYTES + 1); }
            if (bytes.length == 0 || bytes.length > MAX_BYTES) throw invalidAudio();
            temporary = Files.createTempFile("meetodo-audio-", "." + extension);
            Files.write(temporary, bytes);
            String transcript;
            String hint = nameHint();
            long started = System.nanoTime();
            try { transcript = transcriber.transcribe(temporary, hint); }
            catch (RuntimeException failure) { throw transcriptionFailed(); }
            long elapsed = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started);
            Files.deleteIfExists(temporary);
            temporary = null;
            if (transcript == null || transcript.isBlank() || transcript.length() > 20_000)
                throw new AudioMeetingException(HttpStatus.BAD_REQUEST, "INVALID_TRANSCRIPT",
                        "전사 결과는 비어 있지 않은 20000자 이하의 회의록이어야 합니다.");
            transcript = transcript.strip();
            String model = transcriber.model();
            MeetingResponse meeting = meetings.submitAudio(new MeetingRequest(transcript), model, elapsed);
            return new AudioMeetingResponse(transcript, model, elapsed, meeting);
        } catch (IOException failure) {
            throw transcriptionFailed();
        } finally {
            if (temporary != null) {
                try { Files.deleteIfExists(temporary); }
                catch (IOException failure) { throw transcriptionFailed(); }
            }
        }
    }

    private String extension(MultipartFile file) {
        if (file == null || file.isEmpty() || file.getSize() > MAX_BYTES) throw invalidAudio();
        String filename = file.getOriginalFilename();
        int dot = filename == null ? -1 : filename.lastIndexOf('.');
        if (dot <= 0) throw invalidAudio();
        String extension = filename.substring(dot + 1).toLowerCase(Locale.ROOT);
        if (!EXTENSIONS.contains(extension)) throw invalidAudio();
        return extension;
    }

    private String nameHint() {
        StringBuilder hint = new StringBuilder();
        for (Member member : members.findAll(Sort.by("id"))) {
            if (!member.isActive()) continue;
            String name = member.getFullName().replaceAll("\\p{Cntrl}", "").strip();
            if (name.isEmpty()) continue;
            if (hint.length() + name.length() + 2 > 2000) break;
            if (!hint.isEmpty()) hint.append(", ");
            hint.append(name);
        }
        return hint.toString();
    }

    private AudioMeetingException invalidAudio() {
        return new AudioMeetingException(HttpStatus.BAD_REQUEST, "INVALID_AUDIO", "지원하는 음성 파일을 25MB 이하로 첨부하세요.");
    }

    private AudioMeetingException transcriptionFailed() {
        return new AudioMeetingException(HttpStatus.BAD_GATEWAY, "AUDIO_TRANSCRIPTION_FAILED", "음성 전사를 완료하지 못했습니다.");
    }
}
