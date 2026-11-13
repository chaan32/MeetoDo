package com.meetodo.audio;

import java.util.Map;
import com.meetodo.task.TaskApiExceptionHandler.ApiError;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.multipart.MaxUploadSizeExceededException;
import org.springframework.web.multipart.support.MissingServletRequestPartException;

@RestControllerAdvice
public class AudioMeetingExceptionHandler {
    @ExceptionHandler(AudioMeetingException.class)
    ResponseEntity<ApiError> audio(AudioMeetingException exception) {
        return ResponseEntity.status(exception.getStatus()).body(new ApiError(
                exception.getCode(), exception.getMessage(), Map.of()));
    }

    @ExceptionHandler({MissingServletRequestPartException.class, MaxUploadSizeExceededException.class})
    ResponseEntity<ApiError> invalidUpload(Exception exception) {
        return ResponseEntity.badRequest().body(new ApiError("INVALID_AUDIO",
                "지원하는 음성 파일을 25MB 이하로 첨부하세요.", Map.of("file", "음성 파일을 확인하세요.")));
    }
}
