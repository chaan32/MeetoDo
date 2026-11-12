package com.meetodo.notification;

import java.util.List;
import java.time.LocalDate;

public record NotificationMessage(Long meetingId, String title, List<String> summaryLines,
                                  List<String> taskLines, String footer, Type type, LocalDate digestDate) {
    public enum Type { MEETING, DAILY_DIGEST }

    public NotificationMessage(Long meetingId, String title, List<String> summaryLines,
                               List<String> taskLines, String footer) {
        this(meetingId, title, summaryLines, taskLines, footer, Type.MEETING, null);
    }

    public NotificationMessage(Long meetingId, String title, List<String> summaryLines,
                               List<String> taskLines, String footer, Type type) {
        this(meetingId, title, summaryLines, taskLines, footer, type, null);
    }

    public String omission(int count) {
        return "외 " + count + "건은 " + (type == Type.DAILY_DIGEST
                ? "GET /api/tasks" : "GET /api/meetings/" + meetingId) + "에서 확인";
    }
}
