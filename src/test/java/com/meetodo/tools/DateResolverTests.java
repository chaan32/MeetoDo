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
}
