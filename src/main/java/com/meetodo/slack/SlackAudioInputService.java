package com.meetodo.slack;

import com.meetodo.audio.AudioMeetingException;
import com.meetodo.audio.AudioMeetingService;
import com.meetodo.meeting.MeetingProcessingException;
import com.meetodo.notification.NotificationDeliveryException;
import com.slack.api.bolt.App;
import com.slack.api.methods.MethodsClient;
import com.slack.api.methods.request.chat.ChatPostEphemeralRequest;
import com.slack.api.methods.request.chat.ChatPostMessageRequest;
import com.slack.api.methods.request.chat.ChatUpdateRequest;
import com.slack.api.model.File;
import com.slack.api.model.event.AppMentionEvent;
import com.slack.api.model.event.MessageEvent;
import com.slack.api.model.event.MessageFileShareEvent;
import java.util.List;
import java.util.Set;
import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class SlackAudioInputService {
    private static final Logger log = LoggerFactory.getLogger(SlackAudioInputService.class);
    private static final Set<String> SAFE_CODES = Set.of("INVALID_AUDIO", "SLACK_FILE_DOWNLOAD_FAILED",
            "AUDIO_TRANSCRIPTION_DISABLED", "INVALID_TRANSCRIPT", "AUDIO_TRANSCRIPTION_FAILED");
    private final MethodsClient client;
    private final SlackAudioDownloader downloader;
    private final AudioMeetingService audio;
    private final SlackAudioUploadStore store;
    private final Executor executor;
    private final SlackBotService bot;

    public SlackAudioInputService(MethodsClient client, SlackAudioDownloader downloader, AudioMeetingService audio,
                                  SlackAudioUploadStore store, Executor executor, SlackBotService bot) {
        this.client = client; this.downloader = downloader; this.audio = audio; this.store = store; this.executor = executor; this.bot = bot;
    }

    public void register(App app) {
        app.event(MessageEvent.class, (payload, context) -> { var ack = context.ack(); accept(payload.getEvent()); return ack; });
        // Bolt dispatches message/file_share to its own event class, separate from ordinary message events.
        app.event(MessageFileShareEvent.class, (payload, context) -> { var ack = context.ack(); accept(payload.getEvent()); return ack; });
        app.event(AppMentionEvent.class, (payload, context) -> { var ack = context.ack(); accept(payload.getEvent()); return ack; });
    }

    public void accept(MessageEvent event) {
        if (event == null || event.getBotId() != null || event.getBotProfile() != null || event.getEdited() != null
                || !"im".equals(event.getChannelType()) || !channel(event.getChannel(), "D")) return;
        enqueue(event.getChannel(), event.getUser(), event.getThreadTs(), event.getTs(), event.getFiles());
    }

    public void accept(MessageFileShareEvent event) {
        if (event == null || Boolean.TRUE.equals(event.getDisplayAsBot()) || !"im".equals(event.getChannelType())
                || !channel(event.getChannel(), "D")) return;
        enqueue(event.getChannel(), event.getUser(), event.getThreadTs(), event.getTs(), event.getFiles());
    }

    public void accept(AppMentionEvent event) {
        if (event == null || event.getBotId() != null || event.getBotProfile() != null || event.getEdited() != null
                || Boolean.TRUE.equals(event.getDisplayAsBot()) || !channel(event.getChannel(), "CG")
                || (event.getSubtype() != null && !"file_share".equals(event.getSubtype()))) return;
        enqueue(event.getChannel(), event.getUser(), event.getThreadTs(), event.getTs(), event.getFiles());
    }

    private void enqueue(String channel, String user, String threadTs, String ts, List<File> files) {
        if (user == null || !user.matches("[UW][A-Z0-9]{1,79}") || files == null) return;
        String thread = threadTs == null || threadTs.isBlank() ? ts : threadTs;
        if (thread == null || !thread.matches("[0-9]+\\.[0-9]+")) return;
        files.stream().filter(java.util.Objects::nonNull).map(File::getId)
                .filter(id -> id != null && id.matches("F[A-Z0-9]{1,79}")).distinct().forEach(id -> {
                    try { executor.execute(() -> process(channel, user, thread, id)); }
                    catch (RuntimeException ignored) { log.warn("Slack audio input failed: code=QUEUE_REJECTED"); }
                });
    }

    private void process(String channel, String user, String thread, String fileId) {
        String progress;
        try {
            var posted = client.chatPostMessage(ChatPostMessageRequest.builder().channel(channel).threadTs(thread)
                    .text("⏳ 음성 변환 중...").build());
            if (posted == null || !posted.isOk() || posted.getTs() == null || posted.getTs().isBlank()) {
                String code = posted == null ? null : posted.getError();
                if ("not_in_channel".equals(code) || "channel_not_found".equals(code)) invite(channel, user);
                else log.warn("Slack audio input failed: code=PROGRESS_POST_FAILED");
                return;
            }
            progress = posted.getTs();
        } catch (Exception ignored) { log.warn("Slack audio input failed: code=PROGRESS_POST_FAILED"); return; }

        boolean claimed = false;
        var pendingPublication = new AtomicReference<SlackBotService.InputPublication>();
        try {
            var existing = store.find(fileId);
            if (existing.isPresent()) { update(channel, progress, previous(existing.get())); return; }
            var publication = bot.prepareInput(channel, progress);
            pendingPublication.set(publication);
            if (!store.claim(fileId)) {
                discard(pendingPublication);
                update(channel, progress, store.find(fileId).map(this::previous).orElse("이미 처리 중인 음성입니다.")); return;
            }
            claimed = true;
            var file = downloader.download(fileId);
            AtomicBoolean created = new AtomicBoolean();
            var response = audio.submitFromSlack(file, meeting -> {
                publication.attach(meeting);
                created.set(true);
            }, candidate -> {
                created.set(false);
                discard(pendingPublication);
            });
            Long meetingId = response.meeting().meetingId();
            finish(fileId, meetingId, null);
            if (!created.get()) {
                discard(pendingPublication);
                update(channel, progress, "이미 정리된 음성입니다 (회의 " + meetingId + ")");
            }
        } catch (NotificationDeliveryException failure) {
            discard(pendingPublication);
            String code = com.meetodo.notification.NotificationStateService.safeCode(failure.getCode());
            log.warn("Slack audio input failed: code={}", code);
            update(channel, progress, "not_in_channel".equals(code) || "channel_not_found".equals(code)
                    ? "/invite @MeetoDo 후 다시 시도해 주세요."
                    : "음성 처리를 시작하지 못했어요. 잠시 후 다시 시도해 주세요.");
        } catch (MeetingProcessingException failure) {
            if (claimed) finish(fileId, failure.getMeetingId(), "MEETING_PROCESSING_FAILED");
            // The shared AFTER_COMMIT notifier updates this progress message and sends any assignee DMs.
        } catch (Exception failure) {
            discard(pendingPublication);
            String code = failure instanceof AudioMeetingException audioFailure && SAFE_CODES.contains(audioFailure.getCode())
                    ? audioFailure.getCode() : "SLACK_AUDIO_FAILED";
            if (claimed) finish(fileId, null, code);
            log.warn("Slack audio input failed: code={}", code);
            update(channel, progress, failureText(code));
        }
    }

    private void discard(AtomicReference<SlackBotService.InputPublication> pending) {
        var publication = pending.getAndSet(null);
        if (publication != null) bot.discardInputPublication(publication);
    }

    private void finish(String fileId, Long meetingId, String errorCode) {
        try { store.finish(fileId, meetingId, errorCode); }
        catch (RuntimeException ignored) { log.warn("Slack audio input failed: code=UPLOAD_FINISH_FAILED"); }
    }

    private String previous(SlackAudioUpload upload) {
        return switch (upload.getStatus()) {
            case DONE -> "이미 정리된 음성입니다 (회의 " + upload.getMeetingId() + ")";
            case PROCESSING -> "이미 처리 중인 음성입니다.";
            case FAILED -> "이 음성의 이전 처리에 실패했어요. 자동으로 다시 처리하지 않습니다.";
        };
    }

    private String failureText(String code) {
        return switch (code) {
            case "INVALID_AUDIO" -> "지원하는 음성 파일을 25MB 이하로 첨부해 주세요.";
            case "AUDIO_TRANSCRIPTION_DISABLED" -> "음성 변환 기능이 비활성화되어 있어요.";
            case "INVALID_TRANSCRIPT" -> "음성에서 유효한 회의록을 추출하지 못했어요.";
            case "SLACK_FILE_DOWNLOAD_FAILED" -> "음성 파일을 다운로드하지 못했어요.";
            default -> "음성 처리를 완료하지 못했어요. 잠시 후 다시 시도해 주세요.";
        };
    }

    private void update(String channel, String ts, String text) {
        try {
            var response = client.chatUpdate(ChatUpdateRequest.builder().channel(channel).ts(ts).text(text).build());
            if (response == null || !response.isOk()) log.warn("Slack audio input failed: code=PROGRESS_UPDATE_FAILED");
        }
        catch (Exception ignored) { log.warn("Slack audio input failed: code=PROGRESS_UPDATE_FAILED"); }
    }

    private void invite(String channel, String user) {
        try { client.chatPostEphemeral(ChatPostEphemeralRequest.builder().channel(channel).user(user)
                .text("/invite @MeetoDo 후 다시 시도해 주세요.").build()); }
        catch (Exception ignored) { log.warn("Slack audio input failed: code=INVITE_NOTICE_FAILED"); }
    }

    private boolean channel(String id, String prefixes) {
        return id != null && id.matches("[" + prefixes + "][A-Z0-9]{1,79}");
    }
}
