package com.meetodo.tools;

import java.time.LocalDate;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;

class DateResolverTests {
    private final LocalDate today = LocalDate.of(2026, 11, 13);
    private void resolves(String expression, String expected) {
        var result = DateResolver.resolve(expression, today);
        assertThat(result.errorCode()).isNull();
        assertThat(result.warning()).isEmpty();
        assertThat(result.date()).isEqualTo(LocalDate.parse(expected));
    }
    // 검증: 다음 주 금요일 표현의 고정 서울 기준일 계산을 확인한다.
    @Test void nextFriday() { resolves("다음 주 금요일", "2026-11-20"); }
    // 검증: 이번 주 안에 표현의 고정 서울 기준일 계산을 확인한다.
    @Test void thisWeekEnd() { resolves("이번 주 안에", "2026-11-15"); }
    // 검증: 다음 주 월요일 표현의 고정 서울 기준일 계산을 확인한다.
    @Test void nextMonday() { resolves("다음 주 월요일", "2026-11-16"); }
    // 검증: 내일 표현의 고정 서울 기준일 계산을 확인한다.
    @Test void tomorrow() { resolves("내일", "2026-11-14"); }
    // 검증: 이번 주 금요일 표현의 고정 서울 기준일 계산을 확인한다.
    @Test void thisFriday() { resolves("이번 주 금요일", "2026-11-13"); }
    // 검증: 모레 표현의 고정 서울 기준일 계산을 확인한다.
    @Test void dayAfterTomorrow() { resolves("모레", "2026-11-15"); }
    // 검증: 다음 주 안에 표현의 고정 서울 기준일 계산을 확인한다.
    @Test void nextWeekEnd() { resolves("다음 주 안에", "2026-11-22"); }
    // 검증: 이번 주 월요일 표현의 고정 서울 기준일 계산을 확인한다.
    @Test void thisMondayStaysPast() { resolves("이번 주 월요일", "2026-11-09"); }
    // 검증: 11-20 표현의 고정 서울 기준일 계산을 확인한다.
    @Test void shortHyphen() { resolves("11-20", "2026-11-20"); }
    // 검증: 11/20 표현의 고정 서울 기준일 계산을 확인한다.
    @Test void shortSlash() { resolves("11/20", "2026-11-20"); }
    // 검증: 11월 20일 표현의 고정 서울 기준일 계산을 확인한다.
    @Test void shortKorean() { resolves("11월 20일", "2026-11-20"); }
    // 검증: 11-12 표현의 고정 서울 기준일 계산을 확인한다.
    @Test void pastYearlessRollsYear() { resolves("11-12", "2027-11-12"); }
    // 검증: 20일 표현의 고정 서울 기준일 계산을 확인한다.
    @Test void dayOnly() { resolves("20일", "2026-11-20"); }
    // 검증: 12일 표현의 고정 서울 기준일 계산을 확인한다.
    @Test void pastDayRollsMonth() { resolves("12일", "2026-12-12"); }
    // 검증: 2/29 표현의 고정 서울 기준일 계산을 확인한다.
    @Test void leapDay() { resolves("2/29", "2028-02-29"); }
    // 검증: 31일 표현의 고정 서울 기준일 계산을 확인한다.
    @Test void day31SkipsNovember() { resolves("31일", "2026-12-31"); }
    // 검증: 다 음 주 금 요 일 표현의 고정 서울 기준일 계산을 확인한다.
    @Test void spaceVariations() { resolves("다 음 주 금 요 일", "2026-11-20"); }
    // 검증: 오늘 표현의 고정 서울 기준일 계산을 확인한다.
    @Test void today() { resolves("오늘", "2026-11-13"); }
    // 검증: 2026-11-12 표현의 고정 서울 기준일 계산을 확인한다.
    @Test void explicitPastStaysPast() { resolves("2026-11-12", "2026-11-12"); }
    // 검증: 존재하지 않거나 형식이 잘못된 날짜를 거절한다.
    @Test void invalidFebruary() { assertThat(DateResolver.resolve("2/30", today).errorCode()).isEqualTo("INVALID_DUE_DATE"); }
    // 검증: 존재하지 않거나 형식이 잘못된 날짜를 거절한다.
    @Test void invalidDay() { assertThat(DateResolver.resolve("32일", today).errorCode()).isEqualTo("INVALID_DUE_DATE"); }
    // 검증: 존재하지 않거나 형식이 잘못된 날짜를 거절한다.
    @Test void invalidZeroDay() { assertThat(DateResolver.resolve("0일", today).errorCode()).isEqualTo("INVALID_DUE_DATE"); }
    // 검증: 존재하지 않거나 형식이 잘못된 날짜를 거절한다.
    @Test void invalidMonth() { assertThat(DateResolver.resolve("13-20", today).errorCode()).isEqualTo("INVALID_DUE_DATE"); }
    // 검증: 존재하지 않거나 형식이 잘못된 날짜를 거절한다.
    @Test void invalidIso() { assertThat(DateResolver.resolve("2026-02-30", today).errorCode()).isEqualTo("INVALID_DUE_DATE"); }
    // 검증: 존재하지 않거나 형식이 잘못된 날짜를 거절한다.
    @Test void nonPaddedIso() { assertThat(DateResolver.resolve("2026-1-02", today).errorCode()).isEqualTo("INVALID_DUE_DATE"); }
    // 검증: 모호한 원문은 추측하지 않고 별도 날짜 경고로 돌려준다.
    @Test void unknownExpression() {
        var result = DateResolver.resolve("월말쯤", today);
        assertThat(result.date()).isNull();
        assertThat(result.errorCode()).isNull();
        assertThat(result.warning()).isEqualTo(DateResolver.UNKNOWN_WARNING).doesNotContain("월말쯤");
    }
    // 검증: 빈 날짜는 경고도 없다.
    @Test void emptyExpression() { assertThat(DateResolver.resolve(" ", today)).isEqualTo(new DateResolver.Result(null, "", null)); }
    // 검증: 부분 일자를 실제 원문 날짜로 인정하지 않는다.
    @Test void rejectsPartialDay() { assertThat(DateResolver.occursInTranscript("11-2", "11-20까지 마감")).isFalse(); }
    // 검증: 연도를 제거한 날짜는 원문 그대로가 아니다.
    @Test void rejectsStrippedYear() { assertThat(DateResolver.occursInTranscript("11-20", "2026-11-20까지 마감")).isFalse(); }
    // 검증: 월을 제거한 날짜도 원문 그대로가 아니다.
    @Test void rejectsStrippedMonth() { assertThat(DateResolver.occursInTranscript("20일", "11월20일까지 마감")).isFalse(); }
    // 검증: 한국식 연도를 제거해도 원문 그대로로 인정하지 않는다.
    @Test void rejectsStrippedKoreanYear() { assertThat(DateResolver.occursInTranscript("11월20일", "2026년11월20일 마감")).isFalse(); }
    // 검증: 날짜 구분자를 포함한 접미 부분을 원문으로 인정하지 않는다.
    @Test void rejectsIsoPrefix() { assertThat(DateResolver.occursInTranscript("2026-11-2", "2026-11-20까지")).isFalse(); }
    // 검증: 공백과 조사 차이는 원표현 포함을 방해하지 않는다.
    @Test void allowsSpacedOriginalWithParticle() { assertThat(DateResolver.occursInTranscript("다음주 금요일", "다음 주 금요일까지 마감")).isTrue(); }
    // 검증: 연말의 내일은 다음 해로 넘어간다.
    @Test void tomorrowAcrossYear() { assertThat(DateResolver.resolve("내일", LocalDate.of(2026, 12, 31)).date()).isEqualTo(LocalDate.of(2027, 1, 1)); }
    // 검증: 실제 G-내일 로그처럼 조사까지 들어와도 내일로 계산한다.
    @Test void tomorrowUntil() { resolves("내일까지", "2026-11-14"); }
    // 검증: 다음 주 금요일도 동일한 접미사 정규화를 사용한다.
    @Test void nextFridayUntil() { resolves("다음 주 금요일까지", "2026-11-20"); }
    // 검증: 원래 표현 자체의 안에는 제거하지 않는다.
    @Test void preservesEndOfWeekExpressionWithUntil() { resolves("이번 주 안에까지", "2026-11-15"); }
    // 검증: 짧은 숫자 날짜도 같은 정규화 경계를 사용한다.
    @Test void shortDateUntil() { resolves("11-20까지", "2026-11-20"); }
    // 검증: 한국어 월일 표기의 일은 보존한다.
    @Test void koreanMonthDayUntil() { resolves("11월 20일까지", "2026-11-20"); }
    // 검증: 모레의 조사도 제거한다.
    @Test void dayAfterTomorrowUntil() { resolves("모레까지", "2026-11-15"); }
    // 검증: 까지는을 가장 긴 접미사로 한 번만 제거한다.
    @Test void tomorrowUntilTopic() { resolves("내일까지는", "2026-11-14"); }
    // 검증: 일만 있는 짧은 날짜의 조사도 정리한다.
    @Test void dayOnlyUntil() { resolves("20일까지", "2026-11-20"); }
    // 검증: ISO 날짜의 조사도 동일하게 정리한다.
    @Test void isoUntil() { resolves("2026-11-20까지", "2026-11-20"); }
    // 검증: 전까지를 내일 당일 마감으로 추측하지 않는다.
    @Test void beforeTomorrowUntilStaysUnsupported() {
        assertThat(DateResolver.resolve("내일 전까지", today))
                .isEqualTo(new DateResolver.Result(null, DateResolver.UNKNOWN_WARNING, null));
    }
    // 검증: 전까지는도 의미 변경 없이 미지원 경고로 남긴다.
    @Test void beforeTomorrowUntilTopicStaysUnsupported() {
        assertThat(DateResolver.resolve("내일 전까지는", today).warning()).isEqualTo(DateResolver.UNKNOWN_WARNING);
    }
    // 검증: 조사뿐인 입력을 빈 마감일로 취급하지 않는다.
    @Test void suffixAloneIsNotAnEmptyDate() {
        assertThat(DateResolver.resolve("까지", today).warning()).isEqualTo(DateResolver.UNKNOWN_WARNING);
    }
    // 검증: 중복 접미사까지 반복 제거하며 잘못된 입력을 숨기지 않는다.
    @Test void repeatedSuffixStaysUnsupported() {
        assertThat(DateResolver.resolve("내일까지까지", today).warning()).isEqualTo(DateResolver.UNKNOWN_WARNING);
    }
    // 검증: 인자에만 조사가 있어도 실제 날짜 표현이 원문에 있으면 대조한다.
    @Test void normalizedArgumentMatchesBareTranscriptDate() {
        assertThat(DateResolver.occursInTranscript("내일까지는", "내일 자료 검토")).isTrue();
    }
    // 검증: 조사 제거 후에도 없는 날짜를 만들어 넣을 수 없다.
    @Test void inventedDateWithSuffixIsRejected() {
        assertThat(DateResolver.occursInTranscript("11-20까지", "11-21까지 검토")).isFalse();
    }
    // 검증: 짧은 표기를 정리한 뒤에도 연도를 잘라낼 수 없다.
    @Test void normalizedShortDateCannotStripYear() {
        assertThat(DateResolver.occursInTranscript("11-20까지", "2026-11-20까지 검토")).isFalse();
    }
    // 검증: 원문 전까지를 모델이 단순 내일로 바꿔도 배타적 의미를 잃지 않는다.
    @Test void cannotStripBeforeModifierFromTranscript() {
        assertThat(DateResolver.occursInTranscript("내일", "내일 전까지 자료 검토")).isFalse();
    }

    // 검증: 숫자 날짜의 전까지도 형식 오류로 혼동하지 않고 미지원 의미 경고로 남긴다.
    @Test void numericBeforeUntilStaysUnsupported() {
        assertThat(DateResolver.resolve("11-20 전까지", today))
                .isEqualTo(new DateResolver.Result(null, DateResolver.UNKNOWN_WARNING, null));
    }

}
