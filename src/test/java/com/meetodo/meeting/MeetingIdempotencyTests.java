package com.meetodo.meeting;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class MeetingIdempotencyTests {

    // 검증: normalizesLineEndingsAndBoundaryWhitespaceBeforeHashingMeetingText 시나리오의 기대 동작을 확인한다.
    @Test
    void normalizesLineEndingsAndBoundaryWhitespaceBeforeHashingMeetingText() {
        Meeting first = Meeting.create("  회의 내용\r\n다음 줄  ", MeetingSource.API_TEXT);
        Meeting repeated = Meeting.create("회의 내용\n다음 줄", MeetingSource.API_TEXT);
        Meeting different = Meeting.create("회의 내용\n다른 줄", MeetingSource.API_TEXT);

        assertThat(first.getContentHash()).isEqualTo(repeated.getContentHash());
        assertThat(first.getContentHash()).isNotEqualTo(different.getContentHash());
    }
}
