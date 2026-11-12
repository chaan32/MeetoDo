package com.meetodo.digest;

import com.meetodo.member.*;
import com.meetodo.notification.*;
import com.meetodo.task.*;
import java.time.*;
import java.util.List;
import java.util.concurrent.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.system.*;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@SpringBootTest
@ActiveProfiles("test")
@ExtendWith(OutputCaptureExtension.class)
class DailyDigestServiceTests {
    static final LocalDate TODAY = LocalDate.of(2026, 11, 12);
    @Autowired DailyDigestService service;
    @Autowired DailyDigestRepository digests;
    @Autowired TaskRepository tasks;
    @Autowired TaskMentionRepository mentions;
    @Autowired MemberRepository members;
    @Autowired PlatformTransactionManager manager;
    @MockitoBean Notifier notifier;
    @MockitoBean Clock clock;

    @BeforeEach void prepare() {
        mentions.deleteAll(); tasks.deleteAll(); digests.deleteAll(); members.deleteAll();
        Clock fixed = Clock.fixed(Instant.parse("2026-11-11T15:00:00Z"), ZoneOffset.UTC);
        when(clock.withZone(ZoneId.of("Asia/Seoul"))).thenReturn(fixed.withZone(ZoneId.of("Asia/Seoul")));
        when(clock.instant()).thenReturn(fixed.instant());
    }

    // 검증: 미완료 작업만 날짜 경계에 따라 집계하고 기한 없는 작업은 본문에서 제외한다.
    @Test void classifiesDateBoundariesAndExcludesDoneAndFarFuture() {
        add("overdue", null, -1); add("today", null, 0); add("tomorrow", null, 1);
        add("third day", null, 3); add("fourth day", null, 4);
        tasks.saveAndFlush(Task.create("no due", null, null));
        Task done = add("done", null, -2); done.changeStatus(TaskStatus.DONE); tasks.saveAndFlush(done);
        DailyDigestResponse result = service.run(TODAY, false);
        assertThat(result.status()).isEqualTo("SENT");
        assertThat(result.overdueCount()).isEqualTo(1);
        assertThat(result.todayCount()).isEqualTo(1);
        assertThat(result.upcomingCount()).isEqualTo(2);
        assertThat(result.noDueDateCount()).isEqualTo(1);
        var message = org.mockito.ArgumentCaptor.forClass(NotificationMessage.class);
        verify(notifier).send(message.capture());
        assertThat(message.getValue().taskLines()).hasSize(4).noneMatch(s -> s.contains("fourth day") || s.contains("no due") || s.contains("done"));
        assertThat(message.getValue().footer()).contains("1", "2026-11-12");
        assertThat(digests.findByDigestDate(TODAY).orElseThrow().getSentAt()).isNotNull();
    }

    // 검증: 어제 마감 작업은 지연 집계에 포함된다.
    @Test void yesterdayIsOverdue() {
        add("yesterday", null, -1);
        assertThat(service.run(TODAY, false).overdueCount()).isEqualTo(1);
    }

    // 검증: 오늘 마감 작업은 오늘 집계에 포함된다.
    @Test void todayIsDueToday() {
        add("today", null, 0);
        assertThat(service.run(TODAY, false).todayCount()).isEqualTo(1);
    }

    // 검증: 내일 마감 작업은 임박 집계에 포함된다.
    @Test void tomorrowIsUpcoming() {
        add("tomorrow", null, 1);
        assertThat(service.run(TODAY, false).upcomingCount()).isEqualTo(1);
    }

    // 검증: 3일 후 경계는 임박 집계에 포함된다.
    @Test void thirdDayIsIncluded() {
        add("third", null, 3);
        assertThat(service.run(TODAY, false).upcomingCount()).isEqualTo(1);
    }

    // 검증: 4일 후 경계는 알림 대상에서 제외된다.
    @Test void fourthDayIsExcluded() {
        add("fourth", null, 4);
        assertThat(service.run(TODAY, false).status()).isEqualTo("SKIPPED");
        verify(notifier, never()).send(any());
    }

    // 검증: 기한 없는 TODO는 제외 수에만 포함된다.
    @Test void noDueDateIsCountedOnlyInFooter() {
        tasks.saveAndFlush(Task.create("no due", null, null));
        var result = service.run(TODAY, false);
        assertThat(result.noDueDateCount()).isEqualTo(1);
        assertThat(result.status()).isEqualTo("SKIPPED");
    }

    // 검증: 완료 작업은 기한이 지났어도 알림에서 제외된다.
    @Test void doneTaskIsExcluded() {
        Task done = add("done", null, -1);
        done.changeStatus(TaskStatus.DONE); tasks.saveAndFlush(done);
        assertThat(service.run(TODAY, false).status()).isEqualTo("SKIPPED");
        verify(notifier, never()).send(any());
    }

    // 검증: 전송 시점에는 날짜 선점이 별도 트랜잭션에서도 조회된다.
    @Test void claimIsCommittedBeforeNotifierCall() {
        add("today", null, 0);
        doAnswer(call -> {
            TransactionTemplate separate = new TransactionTemplate(manager);
            separate.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
            DailyDigest claimed = separate.execute(status -> digests.findByDigestDate(TODAY).orElseThrow());
            assertThat(claimed.getStatus()).isEqualTo(NotificationStatus.PENDING);
            assertThat(claimed.getTodayCount()).isEqualTo(1);
            return null;
        }).when(notifier).send(any());
        assertThat(service.run(TODAY, false).status()).isEqualTo("SENT");
    }

    // 검증: 같은 이름도 회원 ID로 구분하고 미정 담당자의 경고를 보존한다.
    @Test void groupsByMemberIdAndSortsBucketsWithUnassignedLast() {
        Member first = members.saveAndFlush(new Member("김", "지영", "백엔드팀", "d1"));
        Member second = members.saveAndFlush(new Member("김", "지영", "디자인팀", "d2"));
        add("first soon", first, 3); add("second overdue", second, -2); add("first today", first, 0); add("first overdue", first, -1);
        tasks.saveAndFlush(Task.create("unassigned", null, TODAY, null,
                new UnresolvedAssignee("NOT_FOUND", "민수", null, List.of(), 0)));
        service.run(TODAY, false);
        var message = org.mockito.ArgumentCaptor.forClass(NotificationMessage.class);
        verify(notifier).send(message.capture());
        assertThat(message.getValue().taskLines()).satisfiesExactly(
                line -> assertThat(line).contains("김지영(백엔드팀)", "first overdue", "⏰ 마감 지남"),
                line -> assertThat(line).contains("first today", "📌 오늘 마감"),
                line -> assertThat(line).contains("first soon", "📅 3일 이내 마감"),
                line -> assertThat(line).contains("김지영(디자인팀)", "second overdue"),
                line -> assertThat(line).contains("담당자 미정", "등록되지 않은 담당자", "민수"));
    }

    // 검증: 제목 개행은 한 줄로 평탄화해 작업 수와 렌더링 구조를 보존한다.
    @Test void multilineTitleStaysOnOneTaskLine() {
        add("first\nsecond\r\nthird", null, 0);
        service.run(TODAY, false);
        var message = org.mockito.ArgumentCaptor.forClass(NotificationMessage.class);
        verify(notifier).send(message.capture());
        assertThat(message.getValue().taskLines()).singleElement()
                .satisfies(line -> assertThat(line).contains("first second third").doesNotContain("\n", "\r"));
    }

    // 검증: 대상이 없으면 전송 없이 SKIPPED로 남긴다.
    @Test void emptyDigestIsSkipped() {
        assertThat(service.run(TODAY, false).status()).isEqualTo("SKIPPED");
        verify(notifier, never()).send(any());
    }

    // 검증: 같은 날짜 재실행은 기존 집계를 반환하고 다시 전송하지 않는다.
    @Test void repeatedDateIsNotSentAgain() {
        add("today", null, 0);
        service.run(TODAY, false);
        add("later addition", null, 0);
        DailyDigestResponse repeated = service.run(TODAY, false);
        assertThat(repeated.status()).isEqualTo("ALREADY_SENT");
        assertThat(repeated.todayCount()).isEqualTo(1);
        verify(notifier, times(1)).send(any());
    }

    // 검증: 서로 다른 스레드의 같은 날짜 실행도 고유 제약 선점으로 한 번만 전송한다.
    @Test void concurrentRunsSendOnce() throws Exception {
        add("today", null, 0);
        ExecutorService executor = Executors.newFixedThreadPool(2);
        CountDownLatch start = new CountDownLatch(1);
        try {
            Callable<DailyDigestResponse> run = () -> { start.await(); return service.run(TODAY, false); };
            Future<DailyDigestResponse> first = executor.submit(run), second = executor.submit(run);
            start.countDown();
            assertThat(List.of(first.get(10, TimeUnit.SECONDS).status(), second.get(10, TimeUnit.SECONDS).status()))
                    .containsExactlyInAnyOrder("SENT", "ALREADY_SENT");
            verify(notifier, times(1)).send(any());
            assertThat(digests.count()).isEqualTo(1);
        } finally { executor.shutdownNow(); }
    }

    // 검증: 강제 실행만 같은 날짜 기록을 갱신하면서 중복 전송을 허용한다.
    @Test void forceUpdatesSameRowAndSendsAgain() {
        add("today", null, 0); service.run(TODAY, false);
        add("another", null, 0);
        assertThat(service.run(TODAY, true).todayCount()).isEqualTo(2);
        verify(notifier, times(2)).send(any());
        assertThat(digests.count()).isEqualTo(1);
    }

    // 검증: 전송 실패는 일반화된 코드만 저장하고 자동 재시도하지 않는다.
    @Test void failureIsSafeAndNotRetried(CapturedOutput output) {
        add("today", null, 0);
        doThrow(new IllegalStateException("https://secret.example/token")).when(notifier).send(any());
        assertThat(service.run(TODAY, false).status()).isEqualTo("FAILED");
        assertThat(service.run(TODAY, false).status()).isEqualTo("ALREADY_SENT");
        assertThat(digests.findByDigestDate(TODAY).orElseThrow().getErrorCode()).isEqualTo("NOTIFICATION_FAILED");
        verify(notifier, times(1)).send(any());
        assertThat(output).doesNotContain("secret.example", "/token");
    }

    // 검증: 주입 Clock이 UTC여도 자정 경계는 서울 날짜로 계산한다.
    @Test void defaultDateUsesSeoulEvenWhenClockIsUtc() {
        assertThat(service.run(null, false).digestDate()).isEqualTo(TODAY);
    }

    private Task add(String title, Member member, int offset) {
        return tasks.saveAndFlush(Task.create(title, member, TODAY.plusDays(offset)));
    }
}
