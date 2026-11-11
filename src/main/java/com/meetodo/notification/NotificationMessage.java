package com.meetodo.notification;

import java.util.List;

public record NotificationMessage(Long meetingId, String title, List<String> summaryLines,
                                  List<String> taskLines, String footer) {
}
