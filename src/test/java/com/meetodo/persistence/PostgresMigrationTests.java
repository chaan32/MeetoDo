package com.meetodo.persistence;

import com.meetodo.digest.*;
import com.meetodo.task.*;
import com.meetodo.notification.Notifier;
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
    @Autowired DailyDigestService digests;
    @MockitoBean Notifier notifier;
    @MockitoBean Clock clock;

    @BeforeEach void clean() {
        jdbc.execute("TRUNCATE task_mentions, tool_call_logs, tasks, daily_digests, meetings, members RESTART IDENTITY CASCADE");
        Clock fixed = Clock.fixed(Instant.parse("2026-11-11T15:00:00Z"), ZoneId.of("Asia/Seoul"));
        when(clock.withZone(ZoneId.of("Asia/Seoul"))).thenReturn(fixed);
        when(clock.instant()).thenReturn(fixed.instant());
    }

    // 검증: 빈 PostgreSQL에 V1을 적용하고 Hibernate가 여섯 엔티티의 스키마를 검증한다.
    @Test void appliesInitialMigrationAndValidatesEntities() {
        assertThat(jdbc.queryForObject("SELECT version FROM flyway_schema_history WHERE success AND version = '1'", String.class)).isEqualTo("1");
        assertThat(jdbc.queryForList("SELECT table_name FROM information_schema.tables WHERE table_schema = 'public'", String.class))
                .contains("members", "meetings", "tasks", "tool_call_logs", "task_mentions", "daily_digests");
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

    private void meeting(long id, String hash) {
        jdbc.update("INSERT INTO meetings(id, raw_text, content_hash, source, status, llm_call_count, tool_call_count, validation_failure_count, created_at) VALUES (?, 'meeting', ?, 'API_TEXT', 'PENDING', 0, 0, 0, now())", id, hash);
    }
}
