package com.meetodo.meeting.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record MeetingRequest(
        @NotBlank(message = "회의록은 비어 있을 수 없습니다.")
        @Size(max = 20000, message = "회의록은 20000자 이하여야 합니다.")
        String rawText) {
}
