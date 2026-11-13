package com.meetodo.slack;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.meetodo.meeting.MeetingRepository;
import com.meetodo.meeting.MeetingApplicationService;
import java.time.Clock;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Qualifier;
import com.meetodo.meeting.MeetingResults;
import com.meetodo.notification.Notifier;
import com.meetodo.notification.NotificationMessage;
import com.meetodo.task.TaskRepository;
import com.meetodo.task.TaskService;
import com.slack.api.Slack;
import com.slack.api.bolt.App;
import com.slack.api.bolt.AppConfig;
import com.slack.api.bolt.socket_mode.SocketModeApp;
import com.slack.api.methods.MethodsClient;
import com.slack.api.socket_mode.SocketModeClient;
import org.springframework.context.annotation.*;
import org.springframework.core.env.Environment;
import org.springframework.core.type.AnnotatedTypeMetadata;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.transaction.PlatformTransactionManager;

@Configuration(proxyBeanMethods = false)
@Profile("(local | docker) & !test")
@Conditional(SlackBotConfiguration.BotConfigured.class)
public class SlackBotConfiguration {
    @Bean MethodsClient slackMethodsClient(Environment env) {
        return Slack.getInstance().methods(env.getProperty("SLACK_BOT_TOKEN"));
    }
    @Bean SlackBotService slackBotService(MethodsClient client, Environment env, SlackMessageReferenceRepository refs,
            TaskRepository tasks, TaskService service, MeetingResults results, MeetingRepository meetings,
            ObjectMapper mapper, PlatformTransactionManager manager, com.meetodo.member.MemberRepository members) {
        SlackBotService bot = new SlackBotService(client, env.getProperty("SLACK_CHANNEL_ID"), refs, tasks, service, results, meetings, mapper, manager);
        bot.setMembers(members);
        bot.setDmEnabled(env.getProperty("MEETODO_SLACK_DM_ENABLED", Boolean.class, false));
        return bot;
    }
    @Bean @Conditional(BotNotifierConfigured.class)
    Notifier slackBotNotifier(SlackBotService service) {
        return new Notifier() {
            public String type() { return "slack-bot"; }
            public void send(NotificationMessage message) { service.send(message); }
        };
    }
    @Bean ThreadPoolTaskExecutor slackActionExecutor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(1); executor.setMaxPoolSize(1); executor.setQueueCapacity(100);
        executor.setThreadNamePrefix("slack-action-");
        executor.setWaitForTasksToCompleteOnShutdown(false);
        return executor;
    }
    @Bean @Conditional(BotNotifierConfigured.class)
    ThreadPoolTaskExecutor slackMeetingExecutor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(1); executor.setMaxPoolSize(1); executor.setQueueCapacity(10);
        executor.setThreadNamePrefix("slack-meeting-");
        executor.setWaitForTasksToCompleteOnShutdown(false);
        return executor;
    }
    @Bean @Conditional(BotNotifierConfigured.class)
    SlackMeetingInputService slackMeetingInputService(MethodsClient client, MeetingApplicationService meetings, SlackBotService bot,
            @Qualifier("slackMeetingExecutor") ThreadPoolTaskExecutor executor, Clock clock) {
        return new SlackMeetingInputService(client, meetings, bot, executor, clock);
    }
    @Bean SlackHomeService slackHomeService(MethodsClient client, com.meetodo.member.MemberRepository members,
            TaskRepository tasks, TaskService service, com.meetodo.member.AdminAuthorization admins, Clock clock) {
        return new SlackHomeService(client, members, tasks, service, admins, clock);
    }
    @Bean SlackMemberManagementService slackMemberManagementService(MethodsClient client,
            com.meetodo.member.MemberService members, com.meetodo.member.AdminAuthorization admins, Clock clock, SlackHomeService homes) {
        return new SlackMemberManagementService(client, members, admins, clock, homes);
    }
    @Bean SlackTaskEditService slackTaskEditService(MethodsClient client, SlackBotService bot, TaskService tasks, Clock clock, SlackHomeService homes, com.meetodo.member.AdminAuthorization admins, com.meetodo.member.MemberRepository members) {
        var edits = new SlackTaskEditService(client, bot, tasks, clock);
        edits.setHomes(homes);
        edits.setManagement(admins, members);
        return edits;
    }
    @Bean App slackBoltApp(Environment env, SlackBotService service, ObjectProvider<SlackMeetingInputService> inputs, SlackTaskEditService edits, SlackHomeService homes, SlackMemberManagementService memberManagement) {
        App app = new App(AppConfig.builder().singleTeamBotToken(env.getProperty("SLACK_BOT_TOKEN")).build());
        app.event(com.slack.api.model.event.AppHomeOpenedEvent.class, (request, context) -> {
            var ack = context.ack();
            if ("home".equals(request.getEvent().getTab())) homes.publish(request.getEvent().getUser());
            return ack;
        });
        app.blockAction(SlackBotRenderer.COMPLETE_ACTION, (request, context) -> {
            var ack = context.ack(); var payload = request.getPayload();
            if (payload.getContainer() != null && payload.getUser() != null && payload.getActions() != null && !payload.getActions().isEmpty()) {
                String value = payload.getActions().getFirst().getValue();
                if (home(payload)) homes.complete(payload.getUser().getId(), value);
                else service.complete(payload.getContainer().getChannelId(), payload.getContainer().getMessageTs(), payload.getUser().getId(), value);
            }
            return ack;
        });
        app.blockAction(java.util.regex.Pattern.compile(SlackBotRenderer.ASSIGN_CANDIDATE_ACTION + "_[0-9]+"), (request, context) -> {
            var ack = context.ack(); var payload = request.getPayload();
            if (payload.getContainer() != null && payload.getUser() != null && payload.getActions() != null && !payload.getActions().isEmpty()) {
                String value = payload.getActions().getFirst().getValue();
                if (home(payload)) homes.assignCandidate(payload.getUser().getId(), value);
                else service.assignCandidate(payload.getContainer().getChannelId(), payload.getContainer().getMessageTs(), payload.getUser().getId(), value);
            }
            return ack;
        });
        app.blockAction(SlackBotRenderer.ASSIGN_SELECT_ACTION, (request, context) -> {
            var ack = context.ack(); var payload = request.getPayload();
            if (payload.getContainer() != null && payload.getUser() != null && payload.getActions() != null && !payload.getActions().isEmpty()) {
                var action = payload.getActions().getFirst();
                String value = action.getSelectedOption() == null ? null : action.getSelectedOption().getValue();
                if (home(payload)) homes.assignSelection(payload.getUser().getId(), action.getBlockId(), value);
                else service.assignSelection(payload.getContainer().getChannelId(), payload.getContainer().getMessageTs(), payload.getUser().getId(), action.getBlockId(), value);
            }
            return ack;
        });
        app.blockAction(SlackBotRenderer.ASSIGN_USER_ACTION, (request, context) -> {
            var ack = context.ack(); var payload = request.getPayload();
            if (payload.getContainer() != null && payload.getUser() != null && payload.getActions() != null && !payload.getActions().isEmpty()) {
                var action = payload.getActions().getFirst();
                if (home(payload)) homes.assignUser(payload.getUser().getId(), action.getBlockId(), action.getSelectedUser());
                else service.assignUser(payload.getContainer().getChannelId(), payload.getContainer().getMessageTs(), payload.getUser().getId(), action.getBlockId(), action.getSelectedUser());
            }
            return ack;
        });
        memberManagement.register(app);
        edits.register(app);
        SlackMeetingInputService input = inputs.getIfAvailable();
        if (input != null) input.register(app);
        return app;
    }
    private static boolean home(com.slack.api.app_backend.interactive_components.payload.BlockActionPayload payload) {
        return payload.getView() != null && "home".equals(payload.getView().getType()) && "view".equals(payload.getContainer().getType());
    }
    @Bean(destroyMethod = "") SocketModeClient slackSocketClient(Environment env) {
        try { return Slack.getInstance().socketMode(env.getProperty("SLACK_APP_TOKEN")); }
        catch (Exception exception) { throw new IllegalStateException("Slack socket initialization failed"); }
    }
    @Bean(initMethod = "start", destroyMethod = "close")
    SlackBotSocketRuntime slackSocketRuntime(SocketModeClient client, App app, @Qualifier("slackActionExecutor") ThreadPoolTaskExecutor slackActionExecutor) {
        SlackSocketDispatch dispatch = new SlackSocketDispatch(app, slackActionExecutor);
        client.addWebSocketMessageListener(message -> dispatch.receive(client, message));
        return new SlackBotSocketRuntime(new SocketModeApp(() -> client, app));
    }
    static class BotConfigured implements Condition {
        @Override public boolean matches(ConditionContext context, AnnotatedTypeMetadata metadata) { return enabled(context.getEnvironment()); }
    }
    static class BotNotifierConfigured implements Condition {
        @Override public boolean matches(ConditionContext context, AnnotatedTypeMetadata metadata) {
            Environment env = context.getEnvironment();
            return "slack-bot".equalsIgnoreCase(env.getProperty("NOTIFIER_TYPE", "").trim())
                    && !env.getProperty("SLACK_CHANNEL_ID", "").isBlank();
        }
    }
    static boolean enabled(Environment env) {
        return "true".equalsIgnoreCase(env.getProperty("MEETODO_SLACK_BOT_ENABLED", "false"))
                && env.getProperty("SLACK_APP_TOKEN", "").startsWith("xapp-")
                && env.getProperty("SLACK_APP_TOKEN", "").length() > 5
                && env.getProperty("SLACK_BOT_TOKEN", "").startsWith("xoxb-")
                && env.getProperty("SLACK_BOT_TOKEN", "").length() > 5;
    }
}
