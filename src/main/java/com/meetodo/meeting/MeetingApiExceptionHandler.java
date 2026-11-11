package com.meetodo.meeting;

import com.meetodo.meeting.dto.MeetingProcessingError;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@RestControllerAdvice
public class MeetingApiExceptionHandler {

    @ExceptionHandler(MeetingAgentDisabledException.class)
    ResponseEntity<MeetingProcessingError> disabled() {
        return ResponseEntity.status(HttpStatus.NOT_FOUND).body(new MeetingProcessingError(
                "MEETING_AGENT_DISABLED", "회의 처리 기능은 local,openai 프로필에서만 사용할 수 있습니다.", null));
    }

    @ExceptionHandler(MeetingNotFoundException.class)
    ResponseEntity<MeetingProcessingError> notFound() {
        return ResponseEntity.status(HttpStatus.NOT_FOUND).body(new MeetingProcessingError(
                "MEETING_NOT_FOUND", "회의를 찾을 수 없습니다.", null));
    }

    @ExceptionHandler(MeetingProcessingException.class)
    ResponseEntity<MeetingProcessingError> processingFailed(MeetingProcessingException exception) {
        return ResponseEntity.status(HttpStatus.BAD_GATEWAY).body(new MeetingProcessingError(
                "MEETING_PROCESSING_FAILED", "회의 처리를 완료하지 못했습니다.", exception.getMeetingId()));
    }
}
