package com.meetodo.task;

import com.meetodo.task.exception.InvalidTaskTitleException;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;

class TaskTitlesTests {
    @Test void rejectsSentenceEndingWithoutPeriod() {
        // 다로 끝나는 문장형 제목은 자동 변환하지 않고 거절한다.
        assertFormatRejected("배포 로그를 정리한다");
    }
    @Test void rejectsSentenceEndingWithPeriod() {
        // 실제 보고된 문장형 제목을 명사형 안내와 함께 거절한다.
        assertFormatRejected("배포 로그를 정리한다.");
    }
    @Test void rejectsSentenceEndingWithExclamationMark() {
        // 문장 뒤 느낌표로 다 어미 검증을 우회하지 못한다.
        assertFormatRejected("배포 로그를 정리한다!");
    }
    @Test void rejectsFormalSentenceEnding() {
        // 합니다도 다로 끝나는 문장형이므로 같은 정책으로 거절한다.
        assertFormatRejected("API 명세를 정리합니다.");
    }
    @Test void rejectsHamFollowedByPeriod() {
        // 정책에 포함된 함. 표현은 마침표를 지우기 전에 거절한다.
        assertFormatRejected("배포 로그 정리함.");
    }
    @Test void rejectsHamWithSpacedRepeatedPeriods() {
        // 함 뒤 공백·여러 마침표가 있어도 문장형 검증을 우회하지 못한다.
        assertFormatRejected("배포 로그 정리함 . . ");
    }
    @Test void rejectsHalGeotWithSpace() {
        // 할 것 형태의 지시문은 명사형 이름으로 다시 제출하게 한다.
        assertFormatRejected("배포 로그 정리할 것");
    }
    @Test void rejectsHalGeotWithoutSpace() {
        // 붙여 쓴 할것도 동일한 문장형 정책을 따른다.
        assertFormatRejected("배포 로그 정리할것.");
    }
    @Test void acceptsShortNominalTaskName() {
        // 짧은 명사형 업무 이름은 의미나 대소문자를 바꾸지 않는다.
        assertThat(TaskTitles.validateAndClean("로그인 API 오류 수정")).isEqualTo("로그인 API 오류 수정");
    }
    @Test void acceptsNounEndingInHamWithoutPeriod() {
        // 함 전체를 추측해 제거하거나 거절하지 않아 보관함 같은 명사를 보존한다.
        assertThat(TaskTitles.validateAndClean("업무 보관함")).isEqualTo("업무 보관함");
    }
    @Test void cleansOnlyWhitespaceAndTrailingPeriods() {
        // 단어를 바꾸지 않고 공백 축약·끝 마침표 정리만 수행한다.
        assertThat(TaskTitles.validateAndClean("  API  명세 정리. .  ")).isEqualTo("API 명세 정리");
    }
    @Test void normalizesUnicodeWhitespace() {
        // Unicode 공백도 일반 공백으로 통일해 비교·저장 경로를 맞춘다.
        assertThat(TaskTitles.validateAndClean("\u2003API\u00a0\u2003명세\t정리.\u2003")).isEqualTo("API 명세 정리");
    }
    @Test void preservesPeriodsInsideTaskName() {
        // 버전 번호 같은 내부 마침표는 업무 의미이므로 지우지 않는다.
        assertThat(TaskTitles.validateAndClean("API v1.2 명세 정리.")).isEqualTo("API v1.2 명세 정리");
    }
    @Test void rejectsNullTitle() {
        // HTTP 검증을 거치지 않은 호출도 null 제목을 저장하지 않는다.
        assertThatThrownBy(() -> TaskTitles.validateAndClean(null))
                .isInstanceOfSatisfying(InvalidTaskTitleException.class, failure -> assertThat(failure.getCode()).isEqualTo("INVALID_TITLE"));
    }
    @Test void rejectsBlankTitle() {
        // 빈 제목을 공백 정리로 유효한 이름처럼 만들지 않는다.
        assertThatThrownBy(() -> TaskTitles.validateAndClean(" \t "))
                .isInstanceOfSatisfying(InvalidTaskTitleException.class, failure -> assertThat(failure.getCode()).isEqualTo("INVALID_TITLE"));
    }
    @Test void rejectsTitleContainingOnlyPeriods() {
        // 마침표 정리 후 내용이 없어지면 빈 제목으로 거절한다.
        assertThatThrownBy(() -> TaskTitles.validateAndClean(" . . "))
                .isInstanceOfSatisfying(InvalidTaskTitleException.class, failure -> assertThat(failure.getCode()).isEqualTo("INVALID_TITLE"));
    }
    @Test void acceptsTwoHundredCharacterBoundary() {
        // 기존 제목 상한인 200자는 그대로 허용한다.
        assertThat(TaskTitles.validateAndClean("가".repeat(200))).hasSize(200);
    }
    @Test void rejectsTitleLongerThanTwoHundredCharacters() {
        // 형식 검증을 추가해도 기존 TITLE_TOO_LONG 오류 코드는 유지한다.
        assertThatThrownBy(() -> TaskTitles.validateAndClean("가".repeat(201)))
                .isInstanceOfSatisfying(InvalidTaskTitleException.class, failure -> assertThat(failure.getCode()).isEqualTo("TITLE_TOO_LONG"));
    }
    @Test void comparisonIgnoresCaseWhitespaceAndTrailingPeriods() {
        // 저장된 TODO와 조회 제목은 같은 정규화 규칙으로 비교한다.
        assertThat(Task.normalizeTitle(" API  명세 정리. ")).isEqualTo(Task.normalizeTitle("api 명세 정리"));
    }
    @Test void comparisonOfLegacySentenceDoesNotRunNewInputValidation() {
        // 백필하지 않은 과거 문장형 데이터를 조회·비교할 때 예외를 내지 않는다.
        assertThat(Task.normalizeTitle("배포 로그를 정리한다.")).isEqualTo("배포 로그를 정리한다");
    }
    @Test void knownLimitRejectsNounEndingInDa() {
        // 알려진 한계: 단순 다 접미사 검사는 람다 같은 명사를 구별하지 않는다.
        assertFormatRejected("AWS 람다");
    }

    private void assertFormatRejected(String title) {
        assertThatThrownBy(() -> TaskTitles.validateAndClean(title))
                .isInstanceOfSatisfying(InvalidTaskTitleException.class, failure -> {
                    assertThat(failure.getCode()).isEqualTo("INVALID_TITLE_FORMAT");
                    assertThat(failure.getMessage()).isEqualTo("제목은 명사형 업무 이름으로 넣으세요. 예: '배포 로그를 정리한다.' → '배포 로그 정리'");
                });
    }
}
