package com.meetodo.task.dto;

import java.time.LocalDate;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import io.swagger.v3.oas.annotations.media.Schema;

@Schema(description = "할 일 생성 요청. 허용 필드: title, assigneeId, assigneeName, dueDate")
public record CreateTaskRequest(
        @Schema(description = "할 일 제목 (최대 200자)", example = "회의실 예약 확인")
        @NotBlank @Size(max = 200) String title,
        @Schema(description = "담당자 ID. 지정 시 이름보다 우선합니다.", example = "1")
        Long assigneeId,
        @Schema(description = "담당자 이름. 동명이인이면 400 AMBIGUOUS_MEMBER를 반환합니다.", example = "지영")
        @Size(max = 80) String assigneeName,
        @Schema(description = "마감일 (yyyy-MM-dd)", example = "2026-11-21")
        LocalDate dueDate) {
    public CreateTaskRequest(String title, String assigneeName, LocalDate dueDate) {
        this(title, null, assigneeName, dueDate);
    }
}
