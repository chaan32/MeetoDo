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
    @Bean App slackBoltApp(Environment env, SlackBotService service, ObjectProvider<SlackMeetingInputService> inputs) {
        App app = new App(AppConfig.builder().singleTeamBotToken(env.getProperty("SLACK_BOT_TOKEN")).build());
        app.blockAction(SlackBotRenderer.COMPLETE_ACTION, (request, context) -> {
            var ack = context.ack();
            var payload = request.getPayload();
            if (payload.getContainer() != null && payload.getUser() != null && payload.getActions() != null && !payload.getActions().isEmpty())
                service.complete(payload.getContainer().getChannelId(), payload.getContainer().getMessageTs(),
                        payload.getUser().getId(), payload.getActions().getFirst().getValue());
            return ack;
        });
        app.blockAction(java.util.regex.Pattern.compile(SlackBotRenderer.ASSIGN_CANDIDATE_ACTION + "_[0-9]+"), (request, context) -> {
            var ack = context.ack(); var payload = request.getPayload();
            if (payload.getContainer() != null && payload.getUser() != null && payload.getActions() != null && !payload.getActions().isEmpty())
                service.assignCandidate(payload.getContainer().getChannelId(), payload.getContainer().getMessageTs(),
                        payload.getUser().getId(), payload.getActions().getFirst().getValue());
            return ack;
        });
        app.blockAction(SlackBotRenderer.ASSIGN_SELECT_ACTION, (request, context) -> {
            var ack = context.ack(); var payload = request.getPayload();
            if (payload.getContainer() != null && payload.getUser() != null && payload.getActions() != null && !payload.getActions().isEmpty()) {
                var action = payload.getActions().getFirst();
                service.assignSelection(payload.getContainer().getChannelId(), payload.getContainer().getMessageTs(),
                        payload.getUser().getId(), action.getBlockId(), action.getSelectedOption() == null ? null : action.getSelectedOption().getValue());
            }
            return ack;
        });
        app.blockAction(SlackBotRenderer.ASSIGN_USER_ACTION, (request, context) -> {
            var ack = context.ack(); var payload = request.getPayload();
            if (payload.getContainer() != null && payload.getUser() != null && payload.getActions() != null && !payload.getActions().isEmpty()) {
                var action = payload.getActions().getFirst();
                service.assignUser(payload.getContainer().getChannelId(), payload.getContainer().getMessageTs(),
                        payload.getUser().getId(), action.getBlockId(), action.getSelectedUser());
            }
            return ack;
        });
        SlackMeetingInputService input = inputs.getIfAvailable();
        if (input != null) input.register(app);
        return app;
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
