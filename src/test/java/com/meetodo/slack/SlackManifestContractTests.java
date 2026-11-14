package com.meetodo.slack;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;
import org.yaml.snakeyaml.Yaml;
import static org.assertj.core.api.Assertions.assertThat;

class SlackManifestContractTests {
    private Map<String,Object> manifest() throws Exception {
        return new Yaml().load(Files.readString(Path.of("slack-app-manifest.yml")));
    }
    @SuppressWarnings("unchecked") private Map<String,Object> map(Map<String,Object> parent,String key) { return (Map<String,Object>)parent.get(key); }
    private String sources() throws Exception {
        StringBuilder text = new StringBuilder();
        try (var paths = Files.walk(Path.of("src/main/java/com/meetodo/slack"))) {
            for (Path path : paths.filter(p -> p.toString().endsWith(".java")).toList()) text.append(Files.readString(path));
        }
        return text.toString();
    }

    @Test void manifestEnablesSocketModalHomeAndWritableMessagesWithoutPublicUrl() throws Exception {
        // 셀프 호스팅은 공개 URL 없이 모달·홈·DM 입력을 모두 사용할 수 있어야 한다.
        var manifest = manifest(); var settings = map(manifest,"settings");
        assertThat(settings).containsEntry("socket_mode_enabled",true);
        assertThat(map(settings,"interactivity")).containsEntry("is_enabled",true).doesNotContainKey("request_url");
        var features = map(manifest,"features");
        assertThat(map(features,"app_home")).containsEntry("home_tab_enabled",true)
                .containsEntry("messages_tab_enabled",true).containsEntry("messages_tab_read_only_enabled",false);
        var commands = (List<Map<String,Object>>)features.get("slash_commands");
        assertThat(commands).hasSize(1);
        assertThat(commands.getFirst()).containsEntry("command","/meetodo").doesNotContainKey("url");
        assertThat(sources()).contains("app.command(\"/meetodo\"");
    }

    @Test void manifestEventsMatchRegisteredSdkEventClasses() throws Exception {
        // 코드에 새 이벤트 핸들러가 추가되면 매니페스트 구독도 함께 검토하도록 고정한다.
        Map<String,String> names = Map.of("AppHomeOpenedEvent","app_home_opened", "MessageEvent","message.im",
                "MessageFileShareEvent","message.im", "AppMentionEvent","app_mention");
        var matcher = Pattern.compile("app\\.event\\((?:com\\.slack\\.api\\.model\\.event\\.)?([A-Za-z]+Event)\\.class").matcher(sources());
        Set<String> required = new HashSet<>();
        while (matcher.find()) { assertThat(names).containsKey(matcher.group(1)); required.add(names.get(matcher.group(1))); }
        assertThat(required).hasSize(3);
        var subscribed = (List<String>)map(map(manifest(),"settings"),"event_subscriptions").get("bot_events");
        assertThat(subscribed).containsExactlyInAnyOrderElementsOf(required);
    }

    @Test void manifestScopesMatchUsedBotOperationsWithoutWebhookPermission() throws Exception {
        // 사용하는 SDK 작업과 슬래시·이벤트를 기준으로 필요한 봇 권한을 비교한다.
        String code = sources(); Set<String> required = new HashSet<>();
        Map<String,String> operations = Map.of(".chatPostMessage(","chat:write", ".chatUpdate(","chat:write",
                ".chatDelete(","chat:write", ".chatPostEphemeral(","chat:write", ".conversationsOpen(","im:write", ".filesInfo(","files:read");
        operations.forEach((operation,scope) -> { if (code.contains(operation)) required.add(scope); });
        if (code.contains("app.command(")) required.add("commands");
        if (code.contains("app.event(MessageEvent.class") || code.contains("app.event(MessageFileShareEvent.class")) required.add("im:history");
        if (code.contains("app.event(AppMentionEvent.class")) required.add("app_mentions:read");
        var scopes = (List<String>)map(map(manifest(),"oauth_config"),"scopes").get("bot");
        assertThat(scopes).containsExactlyInAnyOrderElementsOf(required).doesNotContain("incoming-webhook", "chat:write.public", "users:read");
    }
}
