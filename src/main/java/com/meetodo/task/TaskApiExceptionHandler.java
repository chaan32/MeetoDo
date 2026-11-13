package com.meetodo.task;

import java.util.Map;
import java.util.TreeMap;

import com.meetodo.task.exception.ResourceNotFoundException;
import com.meetodo.task.exception.AmbiguousMemberException;
import com.meetodo.task.exception.DuplicateTaskException;
import com.meetodo.task.exception.InvalidTaskTitleException;
import com.fasterxml.jackson.databind.exc.UnrecognizedPropertyException;
import jakarta.validation.ConstraintViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@RestControllerAdvice
public class TaskApiExceptionHandler {

    @ExceptionHandler(MethodArgumentNotValidException.class)
    ResponseEntity<ApiError> validation(MethodArgumentNotValidException exception) {
        Map<String, String> fields = new TreeMap<>();
        for (FieldError error : exception.getBindingResult().getFieldErrors()) {
            fields.putIfAbsent(error.getField(), error.getDefaultMessage());
        }
        return ResponseEntity.badRequest().body(new ApiError(
                "VALIDATION_ERROR", "Request validation failed.", fields));
    }

    @ExceptionHandler(InvalidTaskTitleException.class)
    ResponseEntity<ApiError> invalidTitle(InvalidTaskTitleException exception) {
        return ResponseEntity.badRequest().body(new ApiError("VALIDATION_ERROR", exception.getMessage(),
                Map.of("title", exception.getMessage())));
    }

    @ExceptionHandler(HttpMessageNotReadableException.class)
    ResponseEntity<ApiError> unreadable(HttpMessageNotReadableException exception) {
        UnrecognizedPropertyException unknown = findUnknownProperty(exception);
        if (unknown != null) return ResponseEntity.badRequest().body(new ApiError(
                "VALIDATION_ERROR", "Unknown request field.", Map.of(unknown.getPropertyName(), "알 수 없는 필드입니다."), null, null));
        String unknownField = findUnknownRequestField(exception);
        if (unknownField != null) return ResponseEntity.badRequest().body(new ApiError(
                "VALIDATION_ERROR", "Unknown request field.", Map.of(unknownField, "알 수 없는 필드입니다."), null, null));
        return ResponseEntity.badRequest().body(new ApiError(
                "INVALID_REQUEST", "Request body contains an invalid value.", Map.of(), null, null));
    }

    private String findUnknownRequestField(Throwable exception) {
        for (Throwable current = exception; current != null; current = current.getCause()) {
            String message = current.getMessage();
            if (message != null && message.startsWith("Unknown request field: "))
                return message.substring("Unknown request field: ".length()).trim();
        }
        return null;
    }

    private UnrecognizedPropertyException findUnknownProperty(Throwable exception) {
        for (Throwable current = exception; current != null; current = current.getCause())
            if (current instanceof UnrecognizedPropertyException unknown) return unknown;
        return null;
    }

    @ExceptionHandler(TaskAssignmentException.class)
    ResponseEntity<ApiError> assignment(TaskAssignmentException exception) {
        return ResponseEntity.badRequest().body(new ApiError(exception.getCode(), exception.getMessage(), Map.of()));
    }

    @ExceptionHandler(ResourceNotFoundException.class)
    ResponseEntity<ApiError> notFound(ResourceNotFoundException exception) {
        return ResponseEntity.status(HttpStatus.NOT_FOUND).body(new ApiError(
                exception.getCode(), exception.getMessage(), Map.of(), null, null));
    }

    @ExceptionHandler(AmbiguousMemberException.class)
    ResponseEntity<ApiError> ambiguous(AmbiguousMemberException exception) {
        return ResponseEntity.badRequest().body(new ApiError("AMBIGUOUS_MEMBER", exception.getMessage(),
                Map.of(), null, exception.getCandidates()));
    }

    @ExceptionHandler(DuplicateTaskException.class)
    ResponseEntity<ApiError> duplicate(DuplicateTaskException exception) {
        return ResponseEntity.status(HttpStatus.CONFLICT).body(new ApiError("DUPLICATE_TASK", exception.getMessage(),
                Map.of(), exception.getExistingTaskId(), null));
    }

    public record ApiError(String code, String message, Map<String, String> fieldErrors,
                           Long existingTaskId, Object candidates) {
        public ApiError(String code, String message, Map<String, String> fieldErrors) {
            this(code, message, fieldErrors, null, null);
        }
    }
}
