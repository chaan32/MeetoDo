package com.meetodo.audio;

import com.meetodo.meeting.dto.MeetingResponse;

public record AudioMeetingResponse(String transcript, String sttModel, long transcriptionTimeMs,
                                   MeetingResponse meeting) {
}
