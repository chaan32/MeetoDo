package com.meetodo.meeting;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.boot.test.autoconfigure.orm.jpa.TestEntityManager;
import org.springframework.test.context.ActiveProfiles;
import static org.assertj.core.api.Assertions.assertThat;

@DataJpaTest
@ActiveProfiles("test")
class MeetingSlackInputRepositoryTests {
    @Autowired MeetingRepository meetings;
    @Autowired TestEntityManager entityManager;

    @Test void persistsInputDmAndResultChannelSeparately() {
        // 입력 DM과 팀 채널 결과 위치는 서로 다른 컬럼에서 재조회된다.
        Meeting meeting = Meeting.create("입력과 결과 분리", MeetingSource.SLACK_AUDIO);
        meeting.recordSlackInput("DINPUT1", "11.000001");
        meeting.recordSlackMessage("CRESULT1", "12.000002");
        Long id = meetings.saveAndFlush(meeting).getId();
        entityManager.clear();
        Meeting saved = meetings.findById(id).orElseThrow();
        assertThat(saved.getSlackInputChannelId()).isEqualTo("DINPUT1");
        assertThat(saved.getSlackInputMessageTs()).isEqualTo("11.000001");
        assertThat(saved.getSlackChannelId()).isEqualTo("CRESULT1");
        assertThat(saved.getSlackMessageTs()).isEqualTo("12.000002");
    }

    @Test void resultMessageUpdatePreservesInputDm() {
        // 결과 메시지를 다시 기록해도 최초 입력 DM 위치를 덮어쓰지 않는다.
        Meeting meeting = Meeting.create("결과 위치 수정", MeetingSource.SLACK_AUDIO);
        meeting.recordSlackInput("DINPUT1", "11.000001");
        meeting.recordSlackMessage("CRESULT1", "12.000002");
        Long id = meetings.saveAndFlush(meeting).getId();
        meeting.recordSlackMessage("CRESULT2", "13.000003");
        meetings.flush(); entityManager.clear();
        Meeting saved = meetings.findById(id).orElseThrow();
        assertThat(saved.getSlackInputChannelId()).isEqualTo("DINPUT1");
        assertThat(saved.getSlackInputMessageTs()).isEqualTo("11.000001");
        assertThat(saved.getSlackChannelId()).isEqualTo("CRESULT2");
        assertThat(saved.getSlackMessageTs()).isEqualTo("13.000003");
    }

    @Test void meetingWithoutSlackInputKeepsNullableCoordinates() {
        // 기존 API 입력 경로는 입력 DM 정보 없이도 회의를 저장한다.
        Long id = meetings.saveAndFlush(Meeting.create("API 입력 회의", MeetingSource.API_TEXT)).getId();
        entityManager.clear();
        Meeting saved = meetings.findById(id).orElseThrow();
        assertThat(saved.getSlackInputChannelId()).isNull();
        assertThat(saved.getSlackInputMessageTs()).isNull();
    }
}
