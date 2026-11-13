package com.meetodo.slack;

import java.util.List;
import java.util.concurrent.*;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import static org.assertj.core.api.Assertions.*;

@SpringBootTest
@ActiveProfiles("test")
class SlackAudioUploadStoreTests {
    @Autowired SlackAudioUploadStore store;
    @Autowired SlackAudioUploadRepository uploads;
    @Autowired PlatformTransactionManager transactions;

    @BeforeEach void clean() { uploads.deleteAll(); }

    @Test void firstClaimCommitsProcessingWithoutMeeting() {
        // 최초 선점은 회의 생성 전에도 PROCESSING 상태로 영구 저장된다.
        assertThat(store.claim("FFIRST1")).isTrue();
        var upload = store.find("FFIRST1").orElseThrow();
        assertThat(upload.getStatus()).isEqualTo(SlackAudioUpload.Status.PROCESSING);
        assertThat(upload.getMeetingId()).isNull();
        assertThat(upload.getErrorCode()).isNull();
    }

    @Test void processingFileCannotBeClaimedAgain() {
        // 처리 중 같은 file ID가 다시 와도 두 번째 실행 권한을 주지 않는다.
        store.claim("FPROCESSING");
        assertThat(store.claim("FPROCESSING")).isFalse();
        assertThat(uploads.count()).isEqualTo(1);
    }

    @Test void completedFileKeepsMeetingAndCannotBeClaimedAgain() {
        // 완료 파일은 기존 회의 ID를 반환할 수 있으며 재전사를 허용하지 않는다.
        store.claim("FDONE1"); store.finish("FDONE1", 17L, null);
        var upload = store.find("FDONE1").orElseThrow();
        assertThat(upload.getStatus()).isEqualTo(SlackAudioUpload.Status.DONE);
        assertThat(upload.getMeetingId()).isEqualTo(17L);
        assertThat(upload.getErrorCode()).isNull();
        assertThat(store.claim("FDONE1")).isFalse();
    }

    @Test void failedFileKeepsSafeErrorAndDoesNotAutomaticallyRetry() {
        // 실패한 파일도 동일 ID로 자동 재시도하지 않고 실패 이력을 유지한다.
        store.claim("FFAILED1"); store.finish("FFAILED1", 23L, "MEETING_PROCESSING_FAILED");
        var upload = store.find("FFAILED1").orElseThrow();
        assertThat(upload.getStatus()).isEqualTo(SlackAudioUpload.Status.FAILED);
        assertThat(upload.getMeetingId()).isEqualTo(23L);
        assertThat(upload.getErrorCode()).isEqualTo("MEETING_PROCESSING_FAILED");
        assertThat(store.claim("FFAILED1")).isFalse();
    }

    @Test void claimSurvivesOuterTransactionRollback() {
        // 전체 흐름이 실패해도 별도 트랜잭션으로 커밋한 선점은 사라지지 않는다.
        new TransactionTemplate(transactions).executeWithoutResult(tx -> {
            assertThat(store.claim("FROLLBACK1")).isTrue(); tx.setRollbackOnly();
        });
        assertThat(store.find("FROLLBACK1")).isPresent();
    }

    @Test void duplicateClaimDoesNotPoisonCallerTransaction() {
        // 유니크 충돌은 실패한 내부 트랜잭션 밖에서 처리해 호출자 작업을 롤백시키지 않는다.
        store.claim("FDUPLICATE");
        new TransactionTemplate(transactions).executeWithoutResult(tx -> {
            assertThat(store.claim("FDUPLICATE")).isFalse();
            assertThat(store.claim("FOTHER1")).isTrue();
        });
        assertThat(store.find("FOTHER1")).isPresent();
    }

    @Test void finishSurvivesOuterTransactionRollback() {
        // 완료 기록도 호출자 트랜잭션의 후속 실패와 분리해 보존한다.
        store.claim("FFINISH1");
        new TransactionTemplate(transactions).executeWithoutResult(tx -> {
            store.finish("FFINISH1", 31L, null); tx.setRollbackOnly();
        });
        assertThat(store.find("FFINISH1").orElseThrow().getMeetingId()).isEqualTo(31L);
    }

    @Test void concurrentClaimsHaveExactlyOneWinner() throws Exception {
        // 동시에 전달된 같은 파일은 DB 유니크 제약으로 한 실행만 선점한다.
        ExecutorService executor = Executors.newFixedThreadPool(2);
        CountDownLatch start = new CountDownLatch(1);
        try {
            Callable<Boolean> claim = () -> { start.await(); return store.claim("FCONCURRENT"); };
            var first = executor.submit(claim); var second = executor.submit(claim); start.countDown();
            assertThat(List.of(first.get(15, TimeUnit.SECONDS), second.get(15, TimeUnit.SECONDS)))
                    .containsExactlyInAnyOrder(true, false);
            assertThat(uploads.count()).isEqualTo(1);
        } finally { executor.shutdownNow(); }
    }

    @Test void exactFileIdLimitIsAccepted() {
        // Slack 파일 ID의 80자 저장 한계는 잘라내지 않고 허용한다.
        assertThat(store.claim("F" + "A".repeat(79))).isTrue();
    }

    @Test void overlongFileIdIsRejectedBeforePersistence() {
        // DB 절단 오류 대신 신뢰 경계에서 81자 파일 ID를 거절한다.
        assertThatThrownBy(() -> store.claim("F" + "A".repeat(80))).isInstanceOf(IllegalArgumentException.class);
        assertThat(uploads.count()).isZero();
    }

    @Test void nullFileIdCannotBePersistedOrQueried() {
        // 누락된 file ID는 저장과 조회 모두 거절한다.
        assertInvalidFileId(null);
    }

    @Test void blankFileIdCannotBePersistedOrQueried() {
        // 빈 file ID를 정상 파일로 취급하지 않는다.
        assertInvalidFileId("");
    }

    @Test void prefixOnlyFileIdCannotBePersistedOrQueried() {
        // F 접두사 뒤 실제 식별자 문자가 필요하다.
        assertInvalidFileId("F");
    }

    @Test void lowercaseFileIdCannotBePersistedOrQueried() {
        // Slack 식별자는 대문자와 숫자 형식만 허용한다.
        assertInvalidFileId("f123");
    }

    @Test void pathInFileIdCannotBePersistedOrQueried() {
        // 파일 ID에 경로나 추가 데이터를 붙이지 못한다.
        assertInvalidFileId("F123/secret");
    }

    @Test void privateUrlCannotBePersistedOrQueriedAsFileId() {
        // 비공개 다운로드 URL을 파일 ID로 잘못 저장하지 않는다.
        assertInvalidFileId("https://private.example");
    }

    private void assertInvalidFileId(String invalid) {
        assertThatThrownBy(() -> store.claim(invalid)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> store.find(invalid)).isInstanceOf(IllegalArgumentException.class);
        assertThat(uploads.count()).isZero();
    }

    @Test void unsafeErrorCodeDoesNotOverwriteProcessingRecord() {
        // 공급자 예외 원문을 errorCode로 넘겨도 비밀값을 저장하거나 예외에 반사하지 않는다.
        store.claim("FSAFE1");
        assertThatThrownBy(() -> store.finish("FSAFE1", null, "provider token secret"))
                .isInstanceOf(IllegalArgumentException.class).hasMessageNotContaining("provider token secret");
        assertThat(store.find("FSAFE1").orElseThrow().getStatus()).isEqualTo(SlackAudioUpload.Status.PROCESSING);
    }

    @Test void missingFileIsNotFabricatedByFinish() {
        // 선점하지 않은 파일의 완료 호출은 새 이력을 만들지 않는다.
        assertThatThrownBy(() -> store.finish("FMISSING1", null, null)).isInstanceOf(IllegalArgumentException.class);
        assertThat(uploads.count()).isZero();
    }
}
