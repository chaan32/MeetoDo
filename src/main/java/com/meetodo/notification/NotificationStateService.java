package com.meetodo.notification;

import com.meetodo.meeting.Meeting;
import com.meetodo.meeting.MeetingRepository;
import java.time.Clock;
import java.time.Instant;
import java.util.Set;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Service
@Transactional(propagation = Propagation.REQUIRES_NEW)
public class NotificationStateService {
    private final MeetingRepository meetings;
    private final Clock clock;
    public NotificationStateService(MeetingRepository meetings, Clock clock) { this.meetings = meetings; this.clock = clock; }

    public boolean pending(Long id) {
        return meetings.findById(id).map(m -> m.getNotificationStatus() == NotificationStatus.PENDING).orElse(false);
    }

    public void sent(Long id) {
        Meeting meeting = meetings.findById(id).orElseThrow();
        if (meeting.getNotificationStatus() == NotificationStatus.PENDING) meeting.notificationSent(Instant.now(clock));
    }

    public void failed(Long id, String code) {
        Meeting meeting = meetings.findById(id).orElseThrow();
        if (meeting.getNotificationStatus() == NotificationStatus.PENDING) meeting.notificationFailed(safeCode(code));
    }

    public static String safeCode(String code) {
        return code != null && Set.of("HTTP_ERROR", "TIMEOUT_DELIVERY_UNKNOWN", "TRANSPORT_ERROR", "CONFIGURATION_ERROR",
                "missing_scope", "not_in_channel", "channel_not_found", "invalid_auth", "not_authed", "token_revoked",
                "account_inactive", "user_not_found", "users_not_found", "cannot_dm_bot", "cannot_dm_self",
                "user_disabled", "restricted_action", "access_denied", "rate_limited", "ratelimited",
                "msg_too_long", "invalid_blocks", "invalid_arguments", "message_not_found", "cant_update_message",
                "is_archived", "channel_disabled", "not_allowed_token_type", "no_permission",
                "request_timeout", "internal_error", "fatal_error",
                "accesslimited", "app_cannot_join_channel", "deprecated_endpoint", "ekm_access_denied",
                "enterprise_is_restricted", "invalid_arg_name", "invalid_array_arg", "invalid_charset",
                "invalid_form_data", "invalid_post_type", "invalid_user_combination", "method_deprecated",
                "method_not_supported_for_channel_type", "missing_argument", "missing_post_type",
                "not_enough_users", "org_login_required", "service_unavailable", "team_access_not_granted",
                "team_added_to_org", "token_expired", "too_many_users", "two_factor_setup_required",
                "user_not_visible", "users_list_not_supplied", "agent_prompt_display_not_allowed",
                "app_access_restricted", "as_user_not_supported", "attachment_payload_limit_exceeded",
                "cannot_reply_to_message", "draft_already_deleted", "draft_already_sent", "draft_has_conflict",
                "draft_not_found", "duplicate_channel_not_found", "duplicate_message_not_found",
                "invalid_blocks_format", "invalid_metadata_format", "invalid_metadata_schema",
                "markdown_text_conflict", "message_limit_exceeded", "messages_tab_disabled",
                "metadata_must_be_sent_from_app", "metadata_too_large", "missing_file_data",
                "msg_blocks_too_long", "no_text", "posting_to_channel_denied",
                "restricted_action_non_threadable_channel", "restricted_action_read_only_channel",
                "restricted_action_thread_locked", "restricted_action_thread_only_channel", "send_on_behalf_not_allowed",
                "slack_connect_canvas_sharing_blocked", "slack_connect_file_link_sharing_blocked",
                "slack_connect_lists_sharing_blocked", "team_not_found", "too_many_attachments", "too_many_contact_cards").contains(code)
                ? code : "INTERNAL_ERROR";
    }
}
