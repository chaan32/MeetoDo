package com.meetodo.rag.api;

import com.meetodo.rag.question.*;
import io.swagger.v3.oas.annotations.Parameter;
import jakarta.validation.Valid;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/questions")
public class QuestionController {
    private final ObjectProvider<QuestionService> service;
    public QuestionController(ObjectProvider<QuestionService> service) { this.service=service; }

    @PostMapping
    public QuestionResponse ask(@Valid @RequestBody QuestionRequest request,
            @Parameter(description="Swagger 확인용 Slack ID이며 실제 인증을 대체하지 않습니다.")
            @RequestHeader(value="X-Slack-User-Id",required=false) String actor) {
        QuestionService questions=service.getIfAvailable();
        if (questions==null) throw new RagQuestionException(404,"RAG_DISABLED","회의 기록 검색 기능이 비활성화되어 있습니다.");
        return questions.ask(request.question(),actor,request.diagnostic());
    }
}
