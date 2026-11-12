package com.meetodo.task.dto;

import java.time.LocalDate;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import io.swagger.v3.oas.annotations.media.Schema;

@Schema(description = "할 일 생성 요청. 허용 필드: title, assigneeId, assigneeName, dueDate")
public record CreateTaskRequest(
        @Schema(description = "짧은 명사형 업무 이름 (1~200자). 담당자·마감일·문장 어미를 넣지 않습니다. 문장형은 400 VALIDATION_ERROR, 끝 마침표·공백은 정리합니다.", example = "회의실 예약 확인")
        @NotBlank @Size(max = 200) String title,
        @Schema(description = "담당자 ID. 지정 시 이름보다 우선합니다.", example = "1")
        Long assigneeId,
        @Schema(description = "담당자 성명 또는 이름. 성명 정확 일치 우선, 없으면 이름 조회 후 끝의 님·씨 한 개를 제거해 재조회합니다. 동명이인이면 400 AMBIGUOUS_MEMBER를 반환합니다.", example = "이지영")
        @Size(max = 80) String assigneeName,
        @Schema(description = "마감일 (yyyy-MM-dd)", example = "2026-11-21")
        LocalDate dueDate) {
    public CreateTaskRequest(String title, String assigneeName, LocalDate dueDate) {
        this(title, null, assigneeName, dueDate);
    }
}
