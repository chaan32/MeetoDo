package com.meetodo.tools;

import java.time.DateTimeException;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.MonthDay;
import java.time.YearMonth;
import java.time.format.DateTimeFormatter;
import java.time.format.ResolverStyle;
import java.time.temporal.TemporalAdjusters;
import java.util.regex.Pattern;

/** Deterministic date interpretation; extracting the original expression remains the model's job. */
public final class DateResolver {
    public static final String UNKNOWN_WARNING = "날짜 해석 확인 필요: 지원하지 않는 날짜 표현입니다.";
    public static final String PAST_WARNING = "과거 마감일 확인 필요";
    private static final DateTimeFormatter ISO = DateTimeFormatter.ofPattern("uuuu-MM-dd")
            .withResolverStyle(ResolverStyle.STRICT);
    private static final Pattern WEEK = Pattern.compile("(이번|다음)주([월화수목금토일])요일");
    private static final Pattern MONTH_DAY = Pattern.compile("([0-9]{1,2})[-/]([0-9]{1,2})|([0-9]{1,2})월([0-9]{1,2})일");
    private static final Pattern DAY = Pattern.compile("([0-9]{1,2})일");

    private DateResolver() { }

    public record Result(LocalDate date, String warning, String errorCode) { }

    public static Result resolve(String expression, LocalDate referenceDate) {
        String value = normalizeExpression(expression);
        if (value.isEmpty()) return new Result(null, "", null);
        if (value.endsWith("전까지") || value.endsWith("전까지는")) return new Result(null, UNKNOWN_WARNING, null);
        try {
            LocalDate date;
            if (value.matches("[0-9]{4}-[0-9]{2}-[0-9]{2}")) date = LocalDate.parse(value, ISO);
            else if (value.equals("오늘")) date = referenceDate;
            else if (value.equals("내일")) date = referenceDate.plusDays(1);
            else if (value.equals("모레")) date = referenceDate.plusDays(2);
            else if (value.equals("이번주안에") || value.equals("다음주안에")) {
                date = referenceDate.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY))
                        .plusDays(value.startsWith("다음") ? 13 : 6);
            } else {
                var week = WEEK.matcher(value);
                var monthDay = MONTH_DAY.matcher(value);
                var day = DAY.matcher(value);
                if (week.matches()) {
                    date = referenceDate.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY))
                            .plusWeeks(week.group(1).equals("다음") ? 1 : 0)
                            .plusDays("월화수목금토일".indexOf(week.group(2)));
                } else if (monthDay.matches()) {
                    int month = Integer.parseInt(monthDay.group(1) == null ? monthDay.group(3) : monthDay.group(1));
                    int dayOfMonth = Integer.parseInt(monthDay.group(2) == null ? monthDay.group(4) : monthDay.group(2));
                    MonthDay requested = MonthDay.of(month, dayOfMonth);
                    int year = referenceDate.getYear();
                    while (!requested.isValidYear(year) || requested.atYear(year).isBefore(referenceDate)) year++;
                    date = requested.atYear(year);
                } else if (day.matches()) {
                    int dayOfMonth = Integer.parseInt(day.group(1));
                    if (dayOfMonth < 1 || dayOfMonth > 31) return invalid();
                    YearMonth month = YearMonth.from(referenceDate);
                    while (dayOfMonth > month.lengthOfMonth() || month.atDay(dayOfMonth).isBefore(referenceDate)) month = month.plusMonths(1);
                    date = month.atDay(dayOfMonth);
                } else if (value.matches("[0-9]+[-/].*") || value.matches("[0-9]+월[0-9]+일") || value.matches("[0-9]+일")) return invalid();
                else return new Result(null, UNKNOWN_WARNING, null);
            }
            return new Result(date, "", null);
        } catch (DateTimeException exception) {
            return invalid();
        }
    }

    public static boolean occursInTranscript(String expression, String rawText) {
        String value = normalizeExpression(expression);
        if (value.isEmpty()) return true;
        // Numeric boundaries reject partial days and stripping a year/month from another date.
        return Pattern.compile("(?<![0-9/월년-])" + Pattern.quote(value) + "(?!전까지)(?![0-9/월일-])")
                .matcher(compact(rawText)).find();
    }

    private static String normalizeExpression(String expression) {
        String value = compact(expression);
        // 전까지 changes the deadline boundary; do not silently turn it into an inclusive date.
        if (value.endsWith("전까지") || value.endsWith("전까지는")) return value;
        String stripped = value.replaceFirst("(?:까지는|까지)$", "");
        return stripped.isEmpty() ? value : stripped;
    }

    private static Result invalid() { return new Result(null, "", "INVALID_DUE_DATE"); }
    private static String compact(String value) { return value == null ? "" : value.replaceAll("\\s+", ""); }
}
