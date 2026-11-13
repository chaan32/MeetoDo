package com.meetodo.member.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

@Schema(description = "팀원 등록·수정 입력. 수정도 성·이름·팀·Slack ID 전체를 전달합니다.")
public record MemberRequest(
        @NotBlank @Size(max = 80) @Schema(example = "남궁", description = "성을 직접 입력합니다. 이름에서 추측하지 않습니다.") String familyName,
        @NotBlank @Size(max = 80) @Schema(example = "민수", description = "성을 제외한 이름을 직접 입력합니다.") String givenName,
        @NotBlank @Size(max = 80) @Schema(example = "백엔드팀") String team,
        @NotBlank @Size(max = 80) @Pattern(regexp = "[UW][A-Z0-9]{1,79}", message = "실제 Slack 사용자 ID를 선택하세요.")
        @Schema(example = "U0123456789", description = "Slack 사용자 선택 메뉴가 전달하는 실제 사용자 ID") String slackUserId) { }
