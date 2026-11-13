package com.meetodo.audio;

import com.meetodo.meeting.MeetingStatus;
import org.springframework.context.annotation.Profile;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

@RestController
@Profile("openai")
@RequestMapping("/api/meetings/audio")
public class AudioMeetingController {
    private final AudioMeetingService audio;

    public AudioMeetingController(AudioMeetingService audio) { this.audio = audio; }

    @PostMapping(consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<AudioMeetingResponse> submit(@RequestPart("file") MultipartFile file) {
        AudioMeetingResponse response = audio.submit(file);
        MeetingStatus state = response.meeting().status();
        return ResponseEntity.status(state == MeetingStatus.PROCESSING || state == MeetingStatus.PENDING
                ? HttpStatus.ACCEPTED : HttpStatus.OK).body(response);
    }
}
