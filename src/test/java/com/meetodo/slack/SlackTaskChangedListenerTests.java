package com.meetodo.slack;

import com.meetodo.task.Task;
import com.meetodo.task.TaskEventRepository;
import com.meetodo.task.TaskRepository;
import com.meetodo.task.TaskService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

@SpringBootTest
@ActiveProfiles("test")
@ExtendWith(OutputCaptureExtension.class)
class SlackTaskChangedListenerTests {
    @Autowired TaskRepository tasks;
    @Autowired TaskEventRepository history;
    @Autowired TaskService service;
    @Autowired PlatformTransactionManager manager;
    @MockitoBean SlackBotService bot;

    @Test void failedRefreshAfterCommitPreservesTaskAndAudit(CapturedOutput output) {
        // 커밋 뒤 Slack 갱신 예외가 나도 수정 내용과 감사 이력은 새 DB 조회에서 그대로 남는다.
        Task task = tasks.saveAndFlush(Task.create("갱신 실패 전 제목", null, null));
        doThrow(new IllegalStateException("provider secret body")).when(bot).refreshTask(task.getId());
        assertThatCode(() -> service.edit(task.getId(), "갱신 실패 후 제목", false, null, "UACTOR"))
                .doesNotThrowAnyException();
        assertThat(tasks.findById(task.getId()).orElseThrow().getTitle()).isEqualTo("갱신 실패 후 제목");
        assertThat(history.findAllByTask_IdOrderByAtAscIdAsc(task.getId())).singleElement().satisfies(event -> {
            assertThat(event.getType()).isEqualTo("TITLE_CHANGED");
            assertThat(event.getAfterValue()).isEqualTo("갱신 실패 후 제목");
        });
        verify(bot).refreshTask(task.getId());
        assertThat(output.getAll()).contains("Slack task refresh failed: INTERNAL_ERROR").doesNotContain("provider secret body");
    }

    @Test void rolledBackChangeDoesNotRefreshSlack() {
        // 바깥 트랜잭션이 롤백되면 변경 이벤트가 있어도 Slack을 갱신하지 않는다.
        Task task = tasks.saveAndFlush(Task.create("롤백 전 제목", null, null));
        new TransactionTemplate(manager).executeWithoutResult(status -> {
            service.edit(task.getId(), "롤백 후 제목", false, null, "UACTOR");
            verifyNoInteractions(bot);
            status.setRollbackOnly();
        });
        assertThat(tasks.findById(task.getId()).orElseThrow().getTitle()).isEqualTo("롤백 전 제목");
        assertThat(history.findAllByTask_IdOrderByAtAscIdAsc(task.getId())).isEmpty();
        verifyNoInteractions(bot);
    }
}
