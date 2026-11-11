package com.meetodo.meeting;

import java.util.List;

import com.meetodo.meeting.dto.MeetingRequest;
import com.meetodo.meeting.dto.MeetingHistoryResponse;
import com.meetodo.meeting.dto.MeetingResponse;
import com.meetodo.meeting.dto.ToolCallLogResponse;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/meetings")
public class MeetingController {

    private final MeetingApplicationService meetings;

    public MeetingController(MeetingApplicationService meetings) {
        this.meetings = meetings;
    }

    @PostMapping
    public ResponseEntity<MeetingResponse> submit(@Valid @RequestBody MeetingRequest request) {
        MeetingResponse response = meetings.submit(request);
        HttpStatus status = response.status() == MeetingStatus.PROCESSING || response.status() == MeetingStatus.PENDING
                ? HttpStatus.ACCEPTED : HttpStatus.OK;
        return ResponseEntity.status(status).body(response);
    }

    @GetMapping
    public List<MeetingHistoryResponse> recent() {
        return meetings.recent();
    }

    @GetMapping("/{id}")
    public MeetingResponse get(@PathVariable Long id) {
        return meetings.get(id);
    }

    @GetMapping("/{id}/logs")
    public List<ToolCallLogResponse> logs(@PathVariable Long id) {
        return meetings.logs(id);
    }
}
