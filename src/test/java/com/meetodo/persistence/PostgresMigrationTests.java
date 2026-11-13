package com.meetodo.persistence;

import com.meetodo.digest.*;
import com.meetodo.task.*;
import com.meetodo.notification.Notifier;
import com.meetodo.slack.SlackAudioUpload;
import com.meetodo.slack.SlackAudioUploadStore;
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
    @Autowired SlackAudioUploadStore audioUploads;
    @MockitoBean Notifier notifier;
    @MockitoBean Clock clock;

    @BeforeEach void clean() {
        jdbc.execute("TRUNCATE slack_audio_uploads, task_events, slack_message_references, task_mentions, tool_call_logs, tasks, daily_digests, meetings, members RESTART IDENTITY CASCADE");
        Clock fixed = Clock.fixed(Instant.parse("2026-11-11T15:00:00Z"), ZoneId.of("Asia/Seoul"));
        when(clock.withZone(ZoneId.of("Asia/Seoul"))).thenReturn(fixed);
        when(clock.instant()).thenReturn(fixed.instant());
    }

    // 검증: 빈 PostgreSQL에 V1/V2/V3/V4/V5/V6/V7/V8/V9/V10을 적용하고 Hibernate가 엔티티 스키마를 검증한다.
    @Test void appliesInitialMigrationAndValidatesEntities() {
        assertThat(jdbc.queryForObject("SELECT version FROM flyway_schema_history WHERE success AND version = '1'", String.class)).isEqualTo("1");
        assertThat(jdbc.queryForList("SELECT table_name FROM information_schema.tables WHERE table_schema = 'public'", String.class))
                .contains("members", "meetings", "tasks", "tool_call_logs", "task_mentions", "daily_digests", "slack_message_references", "slack_audio_uploads");
        assertThat(jdbc.queryForObject("SELECT version FROM flyway_schema_history WHERE success AND version = '2'", String.class)).isEqualTo("2");
        assertThat(jdbc.queryForObject("SELECT version FROM flyway_schema_history WHERE success AND version = '3'", String.class)).isEqualTo("3");
        assertThat(jdbc.queryForObject("SELECT version FROM flyway_schema_history WHERE success AND version = '4'", String.class)).isEqualTo("4");
        assertThat(jdbc.queryForObject("SELECT version FROM flyway_schema_history WHERE success AND version = '5'", String.class)).isEqualTo("5");
        assertThat(jdbc.queryForObject("SELECT version FROM flyway_schema_history WHERE success AND version = '6'", String.class)).isEqualTo("6");
        assertThat(jdbc.queryForObject("SELECT version FROM flyway_schema_history WHERE success AND version = '7'", String.class)).isEqualTo("7");
        assertThat(jdbc.queryForObject("SELECT version FROM flyway_schema_history WHERE success AND version = '8'", String.class)).isEqualTo("8");
        assertThat(jdbc.queryForObject("SELECT version FROM flyway_schema_history WHERE success AND version = '9'", String.class)).isEqualTo("9");
        assertThat(jdbc.queryForObject("SELECT version FROM flyway_schema_history WHERE success AND version = '10'", String.class)).isEqualTo("10");
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

    // soft delete는 전체 Task 목록에서 제외한다.
    @Test void deletedTaskIsExcludedFromTaskList() {
        Long id = deletedTask(null, TODAY);
        assertThat(tasks.findAllByOrderByCreatedAtDesc()).noneMatch(task -> task.getId().equals(id));
    }

    // 담당자별 목록에서도 삭제한 Task를 제외한다.
    @Test void deletedTaskIsExcludedFromAssigneeList() {
        var member = members.saveAndFlush(new com.meetodo.member.Member("전", "해찬", "백엔드팀", "UDELETED"));
        Long id = deletedTask(member, TODAY);
        assertThat(tasks.findAllByAssigneeOrderByCreatedAtDesc(member)).noneMatch(task -> task.getId().equals(id));
    }

    // 미정 TODO 중복 후보에 삭제한 Task를 넣지 않는다.
    @Test void deletedTaskIsExcludedFromUnassignedDuplicateCandidates() {
        Long id = deletedTask(null, TODAY);
        assertThat(tasks.findAllByAssigneeIsNullAndStatusOrderByCreatedAtDesc(TaskStatus.TODO)).noneMatch(task -> task.getId().equals(id));
    }

    // 지정 TODO 중복 후보도 같은 soft delete 조건을 쓴다.
    @Test void deletedTaskIsExcludedFromAssignedDuplicateCandidates() {
        var member = members.saveAndFlush(new com.meetodo.member.Member("전", "해찬", "백엔드팀", "UDELETED"));
        Long id = deletedTask(member, TODAY);
        assertThat(tasks.findAllByAssigneeAndStatusOrderByCreatedAtDesc(member,TaskStatus.TODO)).noneMatch(task -> task.getId().equals(id));
    }

    // 삭제된 마감 Task는 다이제스트 대상에서 제외한다.
    @Test void deletedTaskIsExcludedFromDigest() {
        Long id = deletedTask(null, TODAY);
        assertThat(tasks.findDigestTasks(TaskStatus.TODO,TODAY.plusDays(3))).noneMatch(task -> task.getId().equals(id));
    }

    // 삭제된 기한 없는 Task는 제외 건수에도 넣지 않는다.
    @Test void deletedTaskIsExcludedFromNoDueDateCount() {
        deletedTask(null,null);
        assertThat(tasks.countByStatusAndDueDateIsNull(TaskStatus.TODO)).isZero();
    }

    // 과거 메시지를 다시 그릴 때는 삭제 행을 불러 tombstone으로 표시할 수 있다.
    @Test void deletedTaskRemainsAvailableForHistoricalMessageRendering() {
        Long id = deletedTask(null,TODAY);
        assertThat(tasks.findAllWithAssigneeByIdIn(List.of(id))).extracting(Task::getId).containsExactly(id);
    }

    // Task 변경과 이력이 같은 PostgreSQL 트랜잭션에서 저장되어 별도 조회로도 읽힌다.
    @Test void taskEditsAndAuditEventRoundTripTogether() {
        Long id = tasks.saveAndFlush(Task.create("수정 전 제목",null,TODAY)).getId();
        taskService.edit(id,"수정 후 제목",false,null,"UACTOR");
        assertThat(jdbc.queryForMap("SELECT type,before_value,after_value,by_slack_user_id FROM task_events WHERE task_id=?",id))
                .containsEntry("type","TITLE_CHANGED").containsEntry("before_value","수정 전 제목")
                .containsEntry("after_value","수정 후 제목").containsEntry("by_slack_user_id","UACTOR");
        assertThat(tasks.findById(id).orElseThrow().getTitle()).isEqualTo("수정 후 제목");
    }

    // 과거 Task INSERT 형식에도 soft delete 기본값 false가 적용된다.
    @Test void deletedDefaultsToFalseForLegacyTaskInsert() {
        jdbc.update("INSERT INTO tasks(title,status,created_at) VALUES ('이전 형식','TODO',now())");
        assertThat(jdbc.queryForObject("SELECT deleted FROM tasks",Boolean.class)).isFalse();
    }

    // 거절된 제목 변경은 Task와 변경 이력을 모두 그대로 둔다.
    @Test void rejectedDuplicateEditDoesNotPersistTaskOrAuditEvent() {
        tasks.saveAndFlush(Task.create("중복 제목",null,TODAY));
        Long id=tasks.saveAndFlush(Task.create("원래 제목",null,TODAY)).getId();
        assertThatThrownBy(() -> taskService.edit(id,"중복 제목",false,null,"UACTOR"))
                .isInstanceOf(com.meetodo.task.exception.DuplicateTaskException.class);
        assertThat(tasks.findById(id).orElseThrow().getTitle()).isEqualTo("원래 제목");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM task_events",Long.class)).isZero();
    }

    // 존재하지 않는 Task에 대한 변경 이력은 외래 키에서 거절한다.
    @Test void auditEventCannotReferenceMissingTask() {
        assertThatThrownBy(() -> jdbc.update("INSERT INTO task_events(task_id,type,at) VALUES (999,'DELETED',now())"))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    private Long deletedTask(com.meetodo.member.Member member, LocalDate date) {
        Long id = tasks.saveAndFlush(Task.create("삭제한 업무",member,date)).getId();
        jdbc.update("UPDATE tasks SET deleted=true WHERE id=?",id);
        return id;
    }

    // AUDIO 출처·모델·전사 시간은 변환 텍스트와 함께 커밋 후에도 조회된다.
    @Test void audioMetadataRoundTripsWithoutPersistingOriginalFile() {
        var meeting=com.meetodo.meeting.Meeting.create("전사된 회의 내용",com.meetodo.meeting.MeetingSource.AUDIO);
        meeting.recordTranscription("test-transcribe",123L);
        Long id=meetings.saveAndFlush(meeting).getId();
        var reloaded=meetings.findById(id).orElseThrow();
        assertThat(reloaded.getSource()).isEqualTo(com.meetodo.meeting.MeetingSource.AUDIO);
        assertThat(reloaded.getRawText()).isEqualTo("전사된 회의 내용");
        assertThat(reloaded.getSttModel()).isEqualTo("test-transcribe");
        assertThat(reloaded.getTranscriptionTimeMs()).isEqualTo(123L);
        assertThat(jdbc.queryForList("SELECT column_name FROM information_schema.columns WHERE table_name='meetings'",String.class))
                .doesNotContain("audio_bytes","audio_file","audio_path");
    }

    // V7의 새 메타데이터는 기존 텍스트 회의에서 null로 보존된다.
    @Test void legacyTextMeetingKeepsNullTranscriptionMetadata() {
        meeting(1,"legacy-audio-migration");
        assertThat(jdbc.queryForMap("SELECT stt_model,transcription_time_ms FROM meetings WHERE id=1"))
                .containsEntry("stt_model",null).containsEntry("transcription_time_ms",null);
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
                .schemas("slack_v2_upgrade").target("3").load().migrate();
        assertThat(jdbc.queryForMap("SELECT channel_id, message_ts, task_ids, message_type, delivery_status FROM slack_v2_upgrade.slack_message_references"))
                .containsEntry("channel_id", "C123ABC").containsEntry("message_ts", "1.000001")
                .containsEntry("task_ids", "[1,2]").containsEntry("message_type", "CHANNEL").containsEntry("delivery_status", "SENT");
    }

    // 날짜 경고는 실제 PostgreSQL 컬럼에 저장되고 새 조회에서도 보존된다.
    @Test void dueDateWarningRoundTripsThroughPostgres() {
        Task task = Task.create("날짜 확인", null, null);
        task.setDueDateWarning("마감일을 확인해 주세요.");
        Long id = tasks.saveAndFlush(task).getId();
        assertThat(jdbc.queryForObject("SELECT due_date_warning FROM tasks WHERE id=?", String.class, id))
                .isEqualTo("마감일을 확인해 주세요.");
        assertThat(tasks.findById(id).orElseThrow().getDueDateWarning()).isEqualTo("마감일을 확인해 주세요.");
    }

    // 알림 종류를 생략하는 기존 INSERT도 ASSIGNMENT 기본값과 null 변경자를 유지한다.
    @Test void legacyReferenceInsertKeepsAssignmentNotificationDefault() {
        jdbc.update("INSERT INTO slack_message_references(channel_id,message_ts,task_ids) VALUES ('CLEGACY','8.000001','[1]')");
        assertThat(jdbc.queryForMap("SELECT notification_kind,notification_actor_slack_user_id FROM slack_message_references"))
                .containsEntry("notification_kind", "ASSIGNMENT")
                .containsEntry("notification_actor_slack_user_id", null);
    }

    // 새 담당자 DM의 재지정 종류와 변경자를 JPA와 실제 PostgreSQL 사이에서 보존한다.
    @Test void reassignmentNotificationMetadataRoundTripsThroughPostgres() {
        messageReferences.saveAndFlush(SlackMessageReference.dm("DNEXT1", "8.000002", null, "[7]", "UNEXT1",
                SlackMessageReference.DeliveryStatus.SENT, null, null)
                .notification(SlackMessageReference.NotificationKind.REASSIGNMENT, "UADMIN1"));
        var reference = messageReferences.findByChannelIdAndMessageTs("DNEXT1", "8.000002").orElseThrow();
        assertThat(reference.getNotificationKind()).isEqualTo(SlackMessageReference.NotificationKind.REASSIGNMENT);
        assertThat(reference.getNotificationActorSlackUserId()).isEqualTo("UADMIN1");
        assertThat(jdbc.queryForMap("SELECT notification_kind,notification_actor_slack_user_id FROM slack_message_references"))
                .containsEntry("notification_kind", "REASSIGNMENT")
                .containsEntry("notification_actor_slack_user_id", "UADMIN1");
    }

    // 이전 담당자의 인계 알림도 종류·변경자·대상자를 구별해 저장한다.
    @Test void handoffNotificationMetadataRoundTripsThroughPostgres() {
        messageReferences.saveAndFlush(SlackMessageReference.dm("DPREV1", "8.000003", null, "[7]", "UPREV1",
                SlackMessageReference.DeliveryStatus.SENT, null, null)
                .notification(SlackMessageReference.NotificationKind.HANDOFF, "UADMIN1"));
        var reference = messageReferences.findByChannelIdAndMessageTs("DPREV1", "8.000003").orElseThrow();
        assertThat(reference.getNotificationKind()).isEqualTo(SlackMessageReference.NotificationKind.HANDOFF);
        assertThat(reference.getNotificationActorSlackUserId()).isEqualTo("UADMIN1");
        assertThat(reference.getTargetSlackUserId()).isEqualTo("UPREV1");
    }

    // V7 데이터에 V8을 적용해도 원본 Task·채널·실패 DM은 보존되고 재실행은 건너뛴다.
    @Test void upgradingV7PreservesLegacyTasksAndNotificationReferences() {
        String schema = "reassignment_v7_upgrade";
        Flyway.configure().dataSource(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword())
                .schemas(schema).target("7").load().migrate();
        jdbc.update("INSERT INTO reassignment_v7_upgrade.tasks(title,status,created_at) VALUES ('기존 업무','TODO',now())");
        jdbc.update("INSERT INTO reassignment_v7_upgrade.slack_message_references(channel_id,message_ts,task_ids) VALUES ('CLEGACY','7.000001','[1]')");
        jdbc.update("INSERT INTO reassignment_v7_upgrade.slack_message_references(task_ids,message_type,target_slack_user_id,delivery_status,error_code) VALUES ('[1]','DM','ULEGACY','FAILED','missing_scope')");
        var upgrade = Flyway.configure().dataSource(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword())
                .schemas(schema).target("8").load();
        assertThat(upgrade.migrate().migrationsExecuted).isEqualTo(1);
        assertThat(upgrade.migrate().migrationsExecuted).isZero();
        assertThat(jdbc.queryForMap("SELECT title,due_date_warning FROM reassignment_v7_upgrade.tasks"))
                .containsEntry("title", "기존 업무").containsEntry("due_date_warning", null);
        assertThat(jdbc.queryForMap("SELECT channel_id,message_ts,task_ids,notification_kind,notification_actor_slack_user_id FROM reassignment_v7_upgrade.slack_message_references WHERE message_type='CHANNEL'"))
                .containsEntry("channel_id", "CLEGACY").containsEntry("message_ts", "7.000001")
                .containsEntry("task_ids", "[1]").containsEntry("notification_kind", "ASSIGNMENT")
                .containsEntry("notification_actor_slack_user_id", null);
        assertThat(jdbc.queryForMap("SELECT channel_id,message_ts,target_slack_user_id,delivery_status,error_code,notification_kind FROM reassignment_v7_upgrade.slack_message_references WHERE message_type='DM'"))
                .containsEntry("channel_id", null).containsEntry("message_ts", null)
                .containsEntry("target_slack_user_id", "ULEGACY").containsEntry("delivery_status", "FAILED")
                .containsEntry("error_code", "missing_scope").containsEntry("notification_kind", "ASSIGNMENT");
    }

    // V9는 파일 식별자와 처리 상태만 저장하고 원본 파일·URL·토큰을 보관하지 않는다.
    @Test void audioUploadRoundTripsWithoutPrivateFileData() {
        assertThat(audioUploads.claim("FPOSTGRES1")).isTrue();
        audioUploads.finish("FPOSTGRES1", 77L, "MEETING_PROCESSING_FAILED");
        var upload = audioUploads.find("FPOSTGRES1").orElseThrow();
        assertThat(upload.getStatus()).isEqualTo(SlackAudioUpload.Status.FAILED);
        assertThat(upload.getMeetingId()).isEqualTo(77L);
        assertThat(upload.getErrorCode()).isEqualTo("MEETING_PROCESSING_FAILED");
        assertThat(jdbc.queryForObject("SELECT created_at FROM slack_audio_uploads WHERE file_id='FPOSTGRES1'", java.sql.Timestamp.class)).isNotNull();
        assertThat(jdbc.queryForList("SELECT column_name FROM information_schema.columns WHERE table_schema='public' AND table_name='slack_audio_uploads'", String.class))
                .containsExactlyInAnyOrder("id", "file_id", "status", "meeting_id", "error_code", "created_at");
    }

    // PostgreSQL의 서로 다른 연결에서도 같은 파일은 하나의 요청만 실행 권한을 얻는다.
    @Test void concurrentAudioUploadClaimsHaveOneWinnerInPostgres() throws Exception {
        ExecutorService executor = Executors.newFixedThreadPool(2);
        CountDownLatch start = new CountDownLatch(1);
        try {
            Callable<Boolean> claim = () -> { start.await(); return audioUploads.claim("FPOSTGRES2"); };
            var first = executor.submit(claim); var second = executor.submit(claim); start.countDown();
            assertThat(List.of(first.get(15, TimeUnit.SECONDS), second.get(15, TimeUnit.SECONDS)))
                    .containsExactlyInAnyOrder(true, false);
            assertThat(jdbc.queryForObject("SELECT count(*) FROM slack_audio_uploads", Long.class)).isEqualTo(1L);
            assertThat(audioUploads.find("FPOSTGRES2").orElseThrow().getStatus()).isEqualTo(SlackAudioUpload.Status.PROCESSING);
        } finally { executor.shutdownNow(); }
    }

    // V10 입력 DM 좌표와 기존 결과 채널 좌표가 PostgreSQL에서도 독립적으로 저장된다.
    @Test void inputDmAndResultChannelCoordinatesRoundTripSeparately() {
        var meeting = com.meetodo.meeting.Meeting.create("입력 DM 위치", com.meetodo.meeting.MeetingSource.SLACK_AUDIO);
        meeting.recordSlackInput("DINPUT1", "10.000001");
        meeting.recordSlackMessage("CRESULT1", "10.000002");
        Long id = meetings.saveAndFlush(meeting).getId();
        var saved = meetings.findById(id).orElseThrow();
        assertThat(saved.getSlackInputChannelId()).isEqualTo("DINPUT1");
        assertThat(saved.getSlackInputMessageTs()).isEqualTo("10.000001");
        assertThat(saved.getSlackChannelId()).isEqualTo("CRESULT1");
        assertThat(saved.getSlackMessageTs()).isEqualTo("10.000002");
    }

    // 기존 회의에는 입력 DM을 추정해 채우지 않고 결과 위치와 본문을 보존한다.
    @Test void upgradingV9KeepsLegacyResultCoordinatesAndNullInputCoordinates() {
        String schema = "input_dm_v9_upgrade";
        Flyway.configure().dataSource(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword())
                .schemas(schema).target("9").load().migrate();
        jdbc.update("INSERT INTO input_dm_v9_upgrade.meetings(raw_text,content_hash,source,status,llm_call_count,tool_call_count,validation_failure_count,created_at,slack_channel_id,slack_message_ts) VALUES ('기존 회의','legacy-input-dm','API_TEXT','DONE',1,0,0,now(),'CLEGACY','9.000001')");
        var upgrade = Flyway.configure().dataSource(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword())
                .schemas(schema).target("10").load();
        assertThat(upgrade.migrate().migrationsExecuted).isEqualTo(1);
        assertThat(upgrade.migrate().migrationsExecuted).isZero();
        assertThat(jdbc.queryForMap("SELECT raw_text,slack_channel_id,slack_message_ts,slack_input_channel_id,slack_input_message_ts FROM input_dm_v9_upgrade.meetings"))
                .containsEntry("raw_text", "기존 회의").containsEntry("slack_channel_id", "CLEGACY")
                .containsEntry("slack_message_ts", "9.000001").containsEntry("slack_input_channel_id", null)
                .containsEntry("slack_input_message_ts", null);
    }

    private void meeting(long id, String hash) {
        jdbc.update("INSERT INTO meetings(id, raw_text, content_hash, source, status, llm_call_count, tool_call_count, validation_failure_count, created_at) VALUES (?, 'meeting', ?, 'API_TEXT', 'PENDING', 0, 0, 0, now())", id, hash);
    }
}
