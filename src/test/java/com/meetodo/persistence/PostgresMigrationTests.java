package com.meetodo.persistence;

import com.meetodo.digest.*;
import com.meetodo.task.*;
import com.meetodo.notification.Notifier;
import com.meetodo.slack.SlackMessageReference;
import com.meetodo.slack.SlackMessageReferenceRepository;
import java.time.*;
import java.util.List;
import java.util.concurrent.*;
import org.junit.jupiter.api.*;
import org.flywaydb.core.Flyway;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@SpringBootTest(properties = {"spring.flyway.enabled=true", "spring.jpa.hibernate.ddl-auto=validate"})
@ActiveProfiles("test")
@Testcontainers
class PostgresMigrationTests {
    @Container static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16-alpine");
    static final LocalDate TODAY = LocalDate.of(2026, 11, 12);
    @DynamicPropertySource static void database(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
        registry.add("spring.datasource.driver-class-name", () -> "org.postgresql.Driver");
    }
    @Autowired JdbcTemplate jdbc;
    @Autowired Flyway flyway;
    @Autowired TaskRepository tasks;
    @Autowired TaskService taskService;
    @Autowired com.meetodo.member.MemberRepository members;
    @Autowired com.meetodo.meeting.MeetingRepository meetings;
    @Autowired DailyDigestService digests;
    @Autowired SlackMessageReferenceRepository messageReferences;
    @MockitoBean Notifier notifier;
    @MockitoBean Clock clock;

    @BeforeEach void clean() {
        jdbc.execute("TRUNCATE slack_message_references, task_mentions, tool_call_logs, tasks, daily_digests, meetings, members RESTART IDENTITY CASCADE");
        Clock fixed = Clock.fixed(Instant.parse("2026-11-11T15:00:00Z"), ZoneId.of("Asia/Seoul"));
        when(clock.withZone(ZoneId.of("Asia/Seoul"))).thenReturn(fixed);
        when(clock.instant()).thenReturn(fixed.instant());
    }

    // 검증: 빈 PostgreSQL에 V1/V2/V3/V4/V5를 적용하고 Hibernate가 엔티티 스키마를 검증한다.
    @Test void appliesInitialMigrationAndValidatesEntities() {
        assertThat(jdbc.queryForObject("SELECT version FROM flyway_schema_history WHERE success AND version = '1'", String.class)).isEqualTo("1");
        assertThat(jdbc.queryForList("SELECT table_name FROM information_schema.tables WHERE table_schema = 'public'", String.class))
                .contains("members", "meetings", "tasks", "tool_call_logs", "task_mentions", "daily_digests", "slack_message_references");
        assertThat(jdbc.queryForObject("SELECT version FROM flyway_schema_history WHERE success AND version = '2'", String.class)).isEqualTo("2");
        assertThat(jdbc.queryForObject("SELECT version FROM flyway_schema_history WHERE success AND version = '3'", String.class)).isEqualTo("3");
        assertThat(jdbc.queryForObject("SELECT version FROM flyway_schema_history WHERE success AND version = '4'", String.class)).isEqualTo("4");
        assertThat(jdbc.queryForObject("SELECT version FROM flyway_schema_history WHERE success AND version = '5'", String.class)).isEqualTo("5");
    }

    @Test void memberActiveDefaultsToTrueForExistingInsertContract() {
        // 기존 Member 등록 SQL에도 DB 기본값이 적용돼 활성 상태를 유지한다.
        jdbc.update("INSERT INTO members(family_name,given_name,team,slack_user_id) VALUES ('김','민수','백엔드팀','UDEFAULT')");
        assertThat(jdbc.queryForObject("SELECT active FROM members WHERE slack_user_id='UDEFAULT'",Boolean.class)).isTrue();
    }
    @Test void slackUserCannotBeLinkedToTwoMembersInPostgres() {
        // 같은 Slack ID는 실제 PostgreSQL 유니크 제약에서도 두 팀원에 연결할 수 없다.
        jdbc.update("INSERT INTO members(family_name,given_name,team,slack_user_id) VALUES ('김','민수','백엔드팀','UUNIQUE')");
        assertThatThrownBy(() -> jdbc.update("INSERT INTO members(family_name,given_name,team,slack_user_id) VALUES ('이','지영','디자인팀','UUNIQUE')"))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    // V5는 기존 Task를 보존하면서 지정자와 지정 시각을 nullable 컬럼으로 추가한다.
    @Test void assignmentMetadataColumnsPreserveLegacyTasks() {
        Long id = tasks.saveAndFlush(Task.create("담당자 지정 확인", null, TODAY)).getId();
        assertThat(jdbc.queryForMap("SELECT assigned_by_slack_user_id, assigned_at FROM tasks WHERE id=?", id))
                .containsEntry("assigned_by_slack_user_id", null).containsEntry("assigned_at", null);
        jdbc.update("UPDATE tasks SET assigned_by_slack_user_id='UACTOR', assigned_at=now() WHERE id=?", id);
        assertThat(jdbc.queryForObject("SELECT assigned_by_slack_user_id FROM tasks WHERE id=?", String.class, id)).isEqualTo("UACTOR");
    }

    // 지정 스냅샷은 숨기더라도 최초 미정 이유와 지정자·시각은 DB에서 보존한다.
    @Test void assignmentMetadataAndOriginalEvidenceRoundTrip() {
        var member = members.saveAndFlush(new com.meetodo.member.Member("전", "해찬", "백엔드팀", "UASSIGNEE"));
        var snapshot = new UnresolvedAssignee("NOT_FOUND", "해찬", null, List.of(), 0);
        Long id = tasks.saveAndFlush(Task.create("지정 이력 확인", null, TODAY, null, snapshot)).getId();
        var response = taskService.assign(id, member.getId(), "UACTOR");
        assertThat(response.warning()).isNull();
        assertThat(jdbc.queryForObject("SELECT assigned_by_slack_user_id FROM tasks WHERE id=?", String.class, id)).isEqualTo("UACTOR");
        assertThat(tasks.findById(id).orElseThrow().getUnresolvedAssignee()).isEqualTo(snapshot);
    }

    // 같은 미정 Task에 동시에 지정해도 첫 담당자와 지정자가 한 쌍으로 보존된다.
    @Test void concurrentAssignmentsKeepOneFirstAssignee() throws Exception {
        var firstMember = members.saveAndFlush(new com.meetodo.member.Member("김", "민수", "백엔드팀", "UFIRST"));
        var secondMember = members.saveAndFlush(new com.meetodo.member.Member("전", "해찬", "백엔드팀", "USECOND"));
        Long id = tasks.saveAndFlush(Task.create("동시 지정 확인", null, TODAY)).getId();
        ExecutorService executor = Executors.newFixedThreadPool(2);
        CountDownLatch start = new CountDownLatch(1);
        try {
            var first = executor.submit(() -> { start.await(); return taskService.assign(id, firstMember.getId(), "UFIRST"); });
            var second = executor.submit(() -> { start.await(); return taskService.assign(id, secondMember.getId(), "USECOND"); });
            start.countDown();
            var a = first.get(15, TimeUnit.SECONDS); var b = second.get(15, TimeUnit.SECONDS);
            assertThat(a.assignee().id()).isEqualTo(b.assignee().id());
            assertThat(a.assignedBySlackUserId()).isEqualTo(b.assignedBySlackUserId());
            assertThat(a.assignedBySlackUserId()).isEqualTo(a.assignee().slackUserId());
        } finally { executor.shutdownNow(); }
    }

    // 검증: 다시 시작해도 적용된 V1은 재실행하지 않는다.
    @Test void doesNotReapplySuccessfulMigration() {
        assertThat(flyway.migrate().migrationsExecuted).isZero();
    }

    // 검증: 커밋 후 새 조회에서 담당자 미정 JSON을 autocommit 연결로 읽을 수 있다.
    @Test void unresolvedAssigneeRoundTripsAcrossCommittedTransactions() {
        var unresolved = new UnresolvedAssignee("AMBIGUOUS", "김지영", "개발팀",
                List.of(new UnresolvedAssignee.Candidate(42L, "김지영", "개발팀")), 1);
        tasks.saveAndFlush(Task.create("담당자 확인", null, TODAY, null, unresolved));
        assertThat(tasks.findDigestTasks(TaskStatus.TODO, TODAY)).singleElement()
                .satisfies(task -> assertThat(task.getUnresolvedAssignee()).isEqualTo(unresolved));
    }

    // 검증: 같은 본문 해시는 실제 DB 고유 제약으로 거절한다.
    @Test void rejectsDuplicateMeetingHash() {
        meeting(1, "same");
        assertThatThrownBy(() -> meeting(2, "same")).isInstanceOf(DataIntegrityViolationException.class);
    }

    // 검증: 같은 작업과 회의의 재언급은 실제 DB 고유 제약으로 거절한다.
    @Test void rejectsDuplicateTaskMention() {
        meeting(1, "first");
        Long task = tasks.saveAndFlush(Task.create("작업", null, null)).getId();
        jdbc.update("INSERT INTO task_mentions(task_id, meeting_id, mentioned_at) VALUES (?, 1, now())", task);
        assertThatThrownBy(() -> jdbc.update("INSERT INTO task_mentions(task_id, meeting_id, mentioned_at) VALUES (?, 1, now())", task))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    // 검증: 같은 날짜 다이제스트는 실제 DB 고유 제약으로 거절한다.
    @Test void rejectsDuplicateDigestDate() {
        jdbc.update("INSERT INTO daily_digests(digest_date, status, overdue_count, today_count, upcoming_count, no_due_date_count) VALUES (?, 'PENDING', 0, 0, 0, 0)", TODAY);
        assertThatThrownBy(() -> jdbc.update("INSERT INTO daily_digests(digest_date, status, overdue_count, today_count, upcoming_count, no_due_date_count) VALUES (?, 'PENDING', 0, 0, 0, 0)", TODAY))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    // 검증: 존재하지 않는 담당자 ID는 외래 키가 거절한다.
    @Test void rejectsMissingAssignee() {
        assertThatThrownBy(() -> jdbc.update("INSERT INTO tasks(title, assignee_id, status, created_at) VALUES ('task', 999, 'TODO', now())"))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    // 검증: 존재하지 않는 작업의 원본 회의는 외래 키가 거절한다.
    @Test void rejectsMissingTaskMeeting() {
        assertThatThrownBy(() -> jdbc.update("INSERT INTO tasks(title, meeting_id, status, created_at) VALUES ('task', 999, 'TODO', now())"))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    // 검증: 존재하지 않는 도구 호출 회의는 외래 키가 거절한다.
    @Test void rejectsMissingLogMeeting() {
        assertThatThrownBy(() -> jdbc.update("INSERT INTO tool_call_logs(meeting_id, tool_name, success, latency_ms, call_order, created_at) VALUES (999, 'tool', true, 0, 1, now())"))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    // 검증: 존재하지 않는 중복 원본 회의는 자기 참조 외래 키가 거절한다.
    @Test void rejectsMissingDuplicateMeeting() {
        meeting(1, "first");
        assertThatThrownBy(() -> jdbc.update("UPDATE meetings SET duplicate_of_meeting_id = 999 WHERE id = 1"))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    // 검증: 언급의 작업 ID가 없어도 회의가 유효하면 작업 외래 키가 거절한다.
    @Test void rejectsMissingMentionTask() {
        meeting(1, "first");
        assertThatThrownBy(() -> jdbc.update("INSERT INTO task_mentions(task_id, meeting_id, mentioned_at) VALUES (999, 1, now())"))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    // 검증: 언급의 회의 ID가 없어도 작업이 유효하면 회의 외래 키가 거절한다.
    @Test void rejectsMissingMentionMeeting() {
        Long task = tasks.saveAndFlush(Task.create("작업", null, null)).getId();
        assertThatThrownBy(() -> jdbc.update("INSERT INTO task_mentions(task_id, meeting_id, mentioned_at) VALUES (?, 999, now())", task))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    // 검증: 서로 다른 연결에서 같은 날짜를 실행해도 PostgreSQL 선점으로 한 번만 전송한다.
    @Test void concurrentDigestRunsSendOnce() throws Exception {
        tasks.saveAndFlush(Task.create("오늘 작업", null, TODAY));
        ExecutorService executor = Executors.newFixedThreadPool(2);
        CountDownLatch start = new CountDownLatch(1);
        try {
            Callable<DailyDigestResponse> run = () -> { start.await(); return digests.run(null, false); };
            Future<DailyDigestResponse> first = executor.submit(run), second = executor.submit(run);
            start.countDown();
            assertThat(List.of(first.get(15, TimeUnit.SECONDS).status(), second.get(15, TimeUnit.SECONDS).status()))
                    .containsExactlyInAnyOrder("SENT", "ALREADY_SENT");
            verify(notifier, times(1)).send(any());
            assertThat(jdbc.queryForObject("SELECT count(*) FROM daily_digests", Long.class)).isEqualTo(1);
        } finally { executor.shutdownNow(); }
    }

    // V2의 완료 정보와 Slack 메시지 위치는 커밋 후 새 조회에서도 유지된다.
    @Test void completionAndMessageLocationRoundTrip() {
        var meeting = com.meetodo.meeting.Meeting.create("Slack 저장 확인", com.meetodo.meeting.MeetingSource.API_TEXT);
        meeting.recordSlackMessage("C123ABC", "1234567890.123456");
        meeting = meetings.saveAndFlush(meeting);
        Long id = tasks.saveAndFlush(Task.create("완료 확인", null, TODAY, meeting)).getId();
        taskService.completeFromSlack(id, "U123ABC");
        Task reloaded = tasks.findById(id).orElseThrow();
        assertThat(reloaded.getCompletedAt()).isEqualTo(Instant.parse("2026-11-11T15:00:00Z"));
        assertThat(reloaded.getCompletedBySlackUserId()).isEqualTo("U123ABC");
        var reloadedMeeting = meetings.findById(meeting.getId()).orElseThrow();
        assertThat(reloadedMeeting.getSlackChannelId()).isEqualTo("C123ABC");
        assertThat(reloadedMeeting.getSlackMessageTs()).isEqualTo("1234567890.123456");
    }

    // 담당자 지연 로딩도 Task 잠금 트랜잭션 안에서 안전하게 읽는다.
    @Test void completesAssignedTaskUnderPostgresLock() {
        var member = members.saveAndFlush(new com.meetodo.member.Member("전", "해찬", "백엔드팀", "U123ABC"));
        Long id = tasks.saveAndFlush(Task.create("담당자 완료", member, TODAY)).getId();
        assertThat(taskService.completeFromSlack(id, "U123ABC").completedBySlackUserId()).isEqualTo("U123ABC");
    }

    // 동시에 완료해도 Task 행 잠금으로 한 명의 완료 정보만 남긴다.
    @Test void concurrentCompletionsPreserveOneFirstActor() throws Exception {
        Long id = tasks.saveAndFlush(Task.create("동시 완료", null, TODAY)).getId();
        ExecutorService executor = Executors.newFixedThreadPool(2);
        CountDownLatch start = new CountDownLatch(1);
        try {
            Callable<com.meetodo.task.dto.TaskResponse> firstRun = () -> { start.await(); return taskService.completeFromSlack(id, "U123ABC"); };
            Callable<com.meetodo.task.dto.TaskResponse> secondRun = () -> { start.await(); return taskService.completeFromSlack(id, "U456DEF"); };
            var first = executor.submit(firstRun);
            var second = executor.submit(secondRun);
            start.countDown();
            var firstResult = first.get(15, TimeUnit.SECONDS);
            var secondResult = second.get(15, TimeUnit.SECONDS);
            assertThat(firstResult.completedBySlackUserId()).isIn("U123ABC", "U456DEF");
            assertThat(secondResult.completedBySlackUserId()).isEqualTo(firstResult.completedBySlackUserId());
            assertThat(secondResult.completedAt()).isEqualTo(firstResult.completedAt());
        } finally { executor.shutdownNow(); }
    }

    // 원본 메시지의 작업 목록을 저장하며 같은 메시지 위치는 중복 등록하지 않는다.
    @Test void rejectsDuplicateSlackMessageReference() {
        meeting(1, "slack-reference");
        jdbc.update("INSERT INTO slack_message_references(channel_id, message_ts, meeting_id, task_ids) VALUES ('C123ABC', '1.000001', 1, '[1,2]')");
        assertThat(jdbc.queryForObject("SELECT task_ids FROM slack_message_references", String.class)).isEqualTo("[1,2]");
        assertThatThrownBy(() -> jdbc.update("INSERT INTO slack_message_references(channel_id, message_ts, task_ids) VALUES ('C123ABC', '1.000001', '[]')"))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    // 검증: 기존 형식으로 저장한 채널 메시지는 V3 기본값으로 CHANNEL/SENT가 된다.
    @Test void legacyChannelReferenceDefaultsToSentChannel() {
        jdbc.update("INSERT INTO slack_message_references(channel_id, message_ts, task_ids) VALUES ('C123ABC', '1.000001', '[]')");
        assertThat(jdbc.queryForMap("SELECT message_type, delivery_status FROM slack_message_references"))
                .containsEntry("message_type", "CHANNEL").containsEntry("delivery_status", "SENT");
    }

    // 검증: DM 대화 열기에 실패해 위치가 없어도 대상 사용자와 실패 코드가 남는다.
    @Test void failedDirectMessagePersistsWithoutMessageLocation() {
        meeting(1, "failed-dm");
        jdbc.update("INSERT INTO slack_message_references(meeting_id, task_ids, message_type, target_slack_user_id, delivery_status, error_code) VALUES (1, '[1,2]', 'DM', 'U123ABC', 'FAILED', 'missing_scope')");
        assertThat(jdbc.queryForMap("SELECT channel_id, message_ts, target_slack_user_id, delivery_status, error_code FROM slack_message_references"))
                .containsEntry("channel_id", null).containsEntry("message_ts", null)
                .containsEntry("target_slack_user_id", "U123ABC").containsEntry("delivery_status", "FAILED")
                .containsEntry("error_code", "missing_scope");
    }

    // 검증: 성공 DM의 원본 회의·대상 사용자·작업·링크가 커밋 후에도 보존된다.
    @Test void sentDirectMessageRoundTripsThroughJpa() {
        var meeting = meetings.saveAndFlush(com.meetodo.meeting.Meeting.create("새 배정", com.meetodo.meeting.MeetingSource.API_TEXT));
        messageReferences.saveAndFlush(SlackMessageReference.dm("D123ABC", "1.000001", meeting, "[1,2]", "U123ABC",
                SlackMessageReference.DeliveryStatus.SENT, null, "https://workspace.slack.com/archives/C123ABC/p1000001"));
        var reference = messageReferences.findByChannelIdAndMessageTs("D123ABC", "1.000001").orElseThrow();
        assertThat(reference.getMessageType()).isEqualTo(SlackMessageReference.MessageType.DM);
        assertThat(reference.getDeliveryStatus()).isEqualTo(SlackMessageReference.DeliveryStatus.SENT);
        assertThat(reference.getMeetingId()).isEqualTo(meeting.getId());
        assertThat(reference.getTargetSlackUserId()).isEqualTo("U123ABC");
        assertThat(reference.getTaskIds()).isEqualTo("[1,2]");
        assertThat(reference.getPermalink()).isEqualTo("https://workspace.slack.com/archives/C123ABC/p1000001");
        assertThat(reference.getErrorCode()).isNull();
    }

    // 검증: 실패 DM 여러 건은 메시지 위치가 null이어도 각각 저장할 수 있다.
    @Test void multipleFailedDirectMessagesRoundTripThroughJpa() {
        var meeting = meetings.saveAndFlush(com.meetodo.meeting.Meeting.create("DM 실패", com.meetodo.meeting.MeetingSource.API_TEXT));
        messageReferences.saveAndFlush(SlackMessageReference.dm(null, null, meeting, "[1]", "U123ABC",
                SlackMessageReference.DeliveryStatus.FAILED, "missing_scope", null));
        messageReferences.saveAndFlush(SlackMessageReference.dm(null, null, meeting, "[2]", "U456DEF",
                SlackMessageReference.DeliveryStatus.FAILED, "cannot_dm_bot", null));
        assertThat(messageReferences.findAll()).hasSize(2).allSatisfy(reference -> {
            assertThat(reference.getMessageType()).isEqualTo(SlackMessageReference.MessageType.DM);
            assertThat(reference.getDeliveryStatus()).isEqualTo(SlackMessageReference.DeliveryStatus.FAILED);
            assertThat(reference.getMeetingId()).isEqualTo(meeting.getId());
            assertThat(reference.getChannelId()).isNull();
            assertThat(reference.getMessageTs()).isNull();
            assertThat(reference.getErrorCode()).isIn("missing_scope", "cannot_dm_bot");
        });
    }

    // 검증: V2 운영 데이터가 있는 상태에서 V3를 적용해도 기존 채널 위치를 보존한다.
    @Test void upgradingV2PreservesExistingChannelReference() {
        var v2 = Flyway.configure().dataSource(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword())
                .schemas("slack_v2_upgrade").target("2").load();
        v2.migrate();
        jdbc.update("INSERT INTO slack_v2_upgrade.slack_message_references(channel_id, message_ts, task_ids) VALUES ('C123ABC', '1.000001', '[1,2]')");
        Flyway.configure().dataSource(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword())
                .schemas("slack_v2_upgrade").load().migrate();
        assertThat(jdbc.queryForMap("SELECT channel_id, message_ts, task_ids, message_type, delivery_status FROM slack_v2_upgrade.slack_message_references"))
                .containsEntry("channel_id", "C123ABC").containsEntry("message_ts", "1.000001")
                .containsEntry("task_ids", "[1,2]").containsEntry("message_type", "CHANNEL").containsEntry("delivery_status", "SENT");
    }

    private void meeting(long id, String hash) {
        jdbc.update("INSERT INTO meetings(id, raw_text, content_hash, source, status, llm_call_count, tool_call_count, validation_failure_count, created_at) VALUES (?, 'meeting', ?, 'API_TEXT', 'PENDING', 0, 0, 0, now())", id, hash);
    }
}
