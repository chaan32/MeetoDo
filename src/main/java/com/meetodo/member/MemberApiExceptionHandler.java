package com.meetodo.member;

import com.meetodo.task.TaskApiExceptionHandler.ApiError;
import jakarta.validation.ConstraintViolationException;
import java.util.Map;
import java.util.TreeMap;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@RestControllerAdvice(assignableTypes = MemberController.class)
public class MemberApiExceptionHandler {
    @ExceptionHandler(MemberManagementException.class)
    ResponseEntity<ApiError> management(MemberManagementException exception) {
        return ResponseEntity.status(exception.getStatus()).body(new ApiError(exception.getCode(), exception.getMessage(), Map.of()));
    }
    @ExceptionHandler(ConstraintViolationException.class)
    ResponseEntity<ApiError> validation(ConstraintViolationException exception) {
        Map<String,String> fields = new TreeMap<>();
        exception.getConstraintViolations().forEach(violation -> {
            String path = violation.getPropertyPath().toString();
            fields.putIfAbsent(path.substring(path.lastIndexOf('.') + 1), violation.getMessage());
        });
        return ResponseEntity.badRequest().body(new ApiError("VALIDATION_ERROR", "Request validation failed.", fields));
    }
}
