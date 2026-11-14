package com.meetodo.rag.api;

import com.meetodo.member.MemberManagementException;
import com.meetodo.rag.question.RagQuestionException;
import com.meetodo.task.TaskApiExceptionHandler.ApiError;
import java.util.Map;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestControllerAdvice(assignableTypes={QuestionController.class,RagDevController.class})
public class RagApiExceptionHandler {
    @ExceptionHandler(RagQuestionException.class)
    public ResponseEntity<ApiError> question(RagQuestionException exception) {
        return ResponseEntity.status(exception.status()).body(new ApiError(exception.code(),exception.getMessage(),Map.of()));
    }
    @ExceptionHandler(MemberManagementException.class)
    public ResponseEntity<ApiError> admin(MemberManagementException exception) {
        return ResponseEntity.status(exception.getStatus()).body(new ApiError(exception.getCode(),exception.getMessage(),Map.of()));
    }
}
