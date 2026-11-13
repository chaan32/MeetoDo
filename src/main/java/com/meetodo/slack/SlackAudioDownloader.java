package com.meetodo.slack;

import com.meetodo.audio.AudioMeetingException;
import com.meetodo.audio.AudioMeetingService;
import com.slack.api.methods.MethodsClient;
import com.slack.api.methods.request.files.FilesInfoRequest;
import com.slack.api.model.File;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URI;
import java.nio.file.Files;
import java.util.Locale;
import org.springframework.http.HttpStatus;
import org.springframework.web.multipart.MultipartFile;

public class SlackAudioDownloader {
    private final MethodsClient client;
    private final String botToken;
    private final ConnectionFactory connections;

    public SlackAudioDownloader(MethodsClient client, String botToken) {
        this(client, botToken, uri -> (HttpURLConnection) uri.toURL().openConnection());
    }

    SlackAudioDownloader(MethodsClient client, String botToken, ConnectionFactory connections) {
        this.client = client;
        this.botToken = botToken;
        this.connections = connections;
    }

    public MultipartFile download(String fileId) {
        HttpURLConnection connection = null;
        try {
            var response = client.filesInfo(FilesInfoRequest.builder().file(fileId).build());
            if (response == null || !response.isOk() || response.getFile() == null) throw downloadFailed();
            File file = response.getFile();
            if (file.isExternal() || "external".equalsIgnoreCase(file.getMode())
                    || "remote".equalsIgnoreCase(file.getFiletype())) throw invalidAudio();
            String filename = filename(file);
            String extension = AudioMeetingService.validateFilename(filename, file.getSize() == null ? 0 : file.getSize());
            String url = file.getUrlPrivateDownload();
            if (url == null || url.isBlank()) url = file.getUrlPrivate();
            if (url == null) throw downloadFailed();
            URI uri = URI.create(url);
            // Remote files can supply arbitrary URLs; credentials must never leave this exact HTTPS host.
            if (!"https".equalsIgnoreCase(uri.getScheme()) || !"files.slack.com".equalsIgnoreCase(uri.getHost())
                    || uri.getRawUserInfo() != null || (uri.getPort() != -1 && uri.getPort() != 443)) throw downloadFailed();
            connection = connections.open(uri);
            connection.setInstanceFollowRedirects(false);
            connection.setConnectTimeout(3000);
            connection.setReadTimeout(30000);
            connection.setRequestMethod("GET");
            connection.setRequestProperty("Authorization", "Bearer " + botToken);
            if (connection.getResponseCode() != HttpURLConnection.HTTP_OK) throw downloadFailed();
            byte[] bytes;
            try (InputStream stream = connection.getInputStream()) {
                bytes = stream.readNBytes(AudioMeetingService.MAX_BYTES + 1);
            }
            AudioMeetingService.validateFilename(filename, bytes.length);
            return new DownloadedAudio("audio." + extension, bytes);
        } catch (AudioMeetingException failure) {
            throw failure;
        } catch (Exception failure) {
            throw downloadFailed();
        } finally {
            if (connection != null) {
                try { connection.disconnect(); }
                catch (RuntimeException failure) { throw downloadFailed(); }
            }
        }
    }

    private String filename(File file) {
        if (file.getName() != null && file.getName().contains(".")) return file.getName();
        String type = file.getFiletype();
        if (type != null && !type.isBlank()) {
            try {
                AudioMeetingService.validateFilename("audio." + type.toLowerCase(Locale.ROOT), 1);
                return "audio." + type.toLowerCase(Locale.ROOT);
            } catch (AudioMeetingException ignored) {
                // Slack clips may only provide a MIME type usable for the same supported formats.
            }
        }
        String mime = file.getMimetype() == null ? "" : file.getMimetype().toLowerCase(Locale.ROOT);
        String extension = switch (mime) {
            case "audio/flac", "audio/x-flac" -> "flac";
            case "audio/mpeg", "audio/mp3" -> "mp3";
            case "audio/mp4", "audio/x-m4a" -> "m4a";
            case "video/mp4" -> "mp4";
            case "audio/ogg", "application/ogg" -> "ogg";
            case "audio/wav", "audio/x-wav", "audio/wave" -> "wav";
            case "audio/webm", "video/webm" -> "webm";
            default -> throw invalidAudio();
        };
        return "audio." + extension;
    }

    private AudioMeetingException invalidAudio() {
        return new AudioMeetingException(HttpStatus.BAD_REQUEST, "INVALID_AUDIO", "지원하는 음성 파일을 25MB 이하로 첨부하세요.");
    }

    private AudioMeetingException downloadFailed() {
        return new AudioMeetingException(HttpStatus.BAD_GATEWAY, "SLACK_FILE_DOWNLOAD_FAILED", "Slack 음성 파일을 다운로드하지 못했습니다.");
    }

    @FunctionalInterface
    interface ConnectionFactory {
        HttpURLConnection open(URI uri) throws IOException;
    }

    private record DownloadedAudio(String filename, byte[] bytes) implements MultipartFile {
        @Override public String getName() { return "file"; }
        @Override public String getOriginalFilename() { return filename; }
        @Override public String getContentType() { return "application/octet-stream"; }
        @Override public boolean isEmpty() { return bytes.length == 0; }
        @Override public long getSize() { return bytes.length; }
        @Override public byte[] getBytes() { return bytes.clone(); }
        @Override public InputStream getInputStream() { return new ByteArrayInputStream(bytes); }
        @Override public void transferTo(java.io.File destination) throws IOException { Files.write(destination.toPath(), bytes); }
    }
}
