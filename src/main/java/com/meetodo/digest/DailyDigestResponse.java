package com.meetodo.digest;

import java.time.LocalDate;

public record DailyDigestResponse(LocalDate digestDate, String status, long overdueCount,
                                  long todayCount, long upcomingCount, long noDueDateCount) {
    static DailyDigestResponse from(DailyDigest digest, String status) {
        return new DailyDigestResponse(digest.getDigestDate(), status, digest.getOverdueCount(),
                digest.getTodayCount(), digest.getUpcomingCount(), digest.getNoDueDateCount());
    }
}
