package com.meetodo.member;

import com.meetodo.member.dto.MemberRequest;
import com.meetodo.member.dto.MemberResponse;
import io.swagger.v3.oas.annotations.Parameter;
import jakarta.validation.Valid;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/members")
public class MemberController {
    private final MemberService service;
    public MemberController(MemberService service) { this.service = service; }
    @GetMapping public List<MemberResponse> findAll() { return service.findAll(); }
    @PostMapping @ResponseStatus(HttpStatus.CREATED)
    public MemberResponse create(@Valid @RequestBody MemberRequest request,
            @Parameter(description = "로컬 Swagger 확인용 호출자 Slack ID. 실제 인증을 대체하지 않습니다.")
            @RequestHeader(value = "X-Slack-User-Id", required = false) String actor) {
        return service.create(request, actor);
    }
    @PatchMapping("/{id}")
    public MemberResponse update(@PathVariable Long id, @Valid @RequestBody MemberRequest request,
            @RequestHeader(value = "X-Slack-User-Id", required = false) String actor) {
        return service.update(id, request, actor);
    }
    @PostMapping("/{id}/deactivate")
    public MemberResponse deactivate(@PathVariable Long id,
            @RequestHeader(value = "X-Slack-User-Id", required = false) String actor) {
        return service.deactivate(id, actor);
    }
}
