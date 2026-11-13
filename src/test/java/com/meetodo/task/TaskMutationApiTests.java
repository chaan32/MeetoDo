package com.meetodo.task;

import com.meetodo.member.Member;
import com.meetodo.member.MemberRepository;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.transaction.annotation.Transactional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.not;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest(properties = "MEETODO_ADMIN_SLACK_IDS=UADMIN1")
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Transactional
class TaskMutationApiTests {
    private static final Instant NOW = Instant.parse("2026-11-12T16:00:00Z");
    @Autowired MockMvc mvc;
    @Autowired TaskRepository tasks;
    @Autowired MemberRepository members;
    @Autowired TaskEventRepository events;
    @MockitoBean Clock clock;

    @BeforeEach
    void fixClock() {
        ZoneId seoul = ZoneId.of("Asia/Seoul");
        when(clock.instant()).thenReturn(NOW);
        when(clock.getZone()).thenReturn(seoul);
        when(clock.withZone(seoul)).thenReturn(Clock.fixed(NOW, seoul));
    }

    // 기존 PATCH에서 제목만 수정할 수 있고 미정 업무는 누구나 수정한다.
    @Test void editsTaskTitleThroughExistingPatch() throws Exception {
        Task task = unassigned();
        edit(task, "UACTOR1", "{\"title\":\"새 API 검토\"}")
                .andExpect(status().isOk()).andExpect(jsonPath("$.title").value("새 API 검토"));
        assertThat(tasks.findById(task.getId()).orElseThrow().getTitle()).isEqualTo("새 API 검토");
        assertThat(events.findAllByTask_IdOrderByAtAscIdAsc(task.getId())).singleElement().satisfies(event -> {
            assertThat(event.getType()).isEqualTo("TITLE_CHANGED");
            assertThat(event.getBeforeValue()).isEqualTo("기존 API 검토");
            assertThat(event.getAfterValue()).isEqualTo("새 API 검토");
            assertThat(event.getBySlackUserId()).isEqualTo("UACTOR1");
            assertThat(event.getAt()).isEqualTo(NOW);
        });
    }

    // 담당자는 자신의 할 일을 수정할 수 있다.
    @Test void ownerMayEditAssignedTask() throws Exception {
        edit(assigned(), "UOWNER1", "{\"title\":\"담당자 API 검토\"}").andExpect(status().isOk());
    }

    // 관리자는 다른 담당자의 할 일을 수정할 수 있다.
    @Test void adminMayEditAssignedTask() throws Exception {
        edit(assigned(), "UADMIN1", "{\"title\":\"관리자 API 검토\"}").andExpect(status().isOk());
    }

    // 다른 사용자의 수정은 거절하고 원본 제목과 이력을 유지한다.
    @Test void anotherUserCannotEditAssignedTask() throws Exception {
        Task task = assigned();
        edit(task, "UOTHER1", "{\"title\":\"변경 API 검토\"}")
                .andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value("TASK_MUTATION_FORBIDDEN"));
        assertThat(task.getTitle()).isEqualTo("기존 API 검토");
        assertThat(events.findAllByTask_IdOrderByAtAscIdAsc(task.getId())).isEmpty();
    }

    // 변경자 헤더가 없으면 미정 업무도 수정하지 않는다.
    @Test void rejectsEditWithoutActor() throws Exception {
        mvc.perform(patch("/api/tasks/{id}", unassigned().getId()).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"title\":\"변경 API 검토\"}"))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("INVALID_SLACK_USER_ID"));
    }

    // Slack 사용자 ID 형식이 잘못되면 수정하지 않는다.
    @Test void rejectsMalformedActor() throws Exception {
        edit(unassigned(), "invalid-user", "{\"title\":\"변경 API 검토\"}")
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("INVALID_SLACK_USER_ID"));
    }

    // 서울 기준 오늘 마감일을 허용하고 제목은 생략한 값을 보존한다.
    @Test void setsDueDateToSeoulToday() throws Exception {
        edit(unassigned(), "UACTOR1", "{\"dueDate\":\"2026-11-13\"}")
                .andExpect(status().isOk()).andExpect(jsonPath("$.dueDate").value("2026-11-13"))
                .andExpect(jsonPath("$.title").value("기존 API 검토"));
    }

    // 날짜를 명시적으로 null로 보내면 기존 마감일을 지운다.
    @Test void nullDueDateClearsExistingDate() throws Exception {
        Task task = tasks.save(Task.create("기존 API 검토", null, LocalDate.of(2026, 11, 20)));
        edit(task, "UACTOR1", "{\"dueDate\":null}")
                .andExpect(status().isOk()).andExpect(jsonPath("$.dueDate").doesNotExist());
        assertThat(task.getDueDate()).isNull();
    }

    // 날짜 필드를 생략한 제목 수정은 기존 마감일을 보존한다.
    @Test void omittedDueDatePreservesExistingDate() throws Exception {
        Task task = tasks.save(Task.create("기존 API 검토", null, LocalDate.of(2026, 11, 20)));
        edit(task, "UACTOR1", "{\"title\":\"새 API 검토\"}")
                .andExpect(status().isOk()).andExpect(jsonPath("$.dueDate").value("2026-11-20"));
    }

    // 월·일이 두 자리 아닌 날짜 문자열은 엄격한 형식 검증에서 거절한다.
    @Test void rejectsNonPaddedDueDate() throws Exception {
        edit(unassigned(), "UACTOR1", "{\"dueDate\":\"2026-1-3\"}")
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("INVALID_REQUEST"));
    }

    // 달력에 없는 날짜는 자동 보정하지 않고 거절한다.
    @Test void rejectsImpossibleDueDate() throws Exception {
        edit(unassigned(), "UACTOR1", "{\"dueDate\":\"2026-02-30\"}")
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("INVALID_REQUEST"));
    }

    // UTC로는 오늘이어도 서울 기준 과거인 마감일은 거절한다.
    @Test void rejectsPastDueDateInSeoul() throws Exception {
        edit(unassigned(), "UACTOR1", "{\"dueDate\":\"2026-11-12\"}")
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("INVALID_DUE_DATE"));
    }

    // 기존 status 전용 PATCH는 완료자와 완료 시각까지 기록한다.
    @Test void preservesLegacyStatusPatch() throws Exception {
        edit(unassigned(), "UACTOR1", "{\"status\":\"DONE\"}")
                .andExpect(status().isOk()).andExpect(jsonPath("$.status").value("DONE"))
                .andExpect(jsonPath("$.completedBySlackUserId").value("UACTOR1"))
                .andExpect(jsonPath("$.completedAt").value(NOW.toString()));
    }

    // 제목·날짜·상태를 함께 보내면 각 필드를 한 요청에서 수정한다.
    @Test void editsAllSupportedFieldsTogether() throws Exception {
        edit(unassigned(), "UACTOR1", "{\"title\":\"수정 API 검토\",\"dueDate\":\"2026-11-20\",\"status\":\"DONE\"}")
                .andExpect(status().isOk()).andExpect(jsonPath("$.title").value("수정 API 검토"))
                .andExpect(jsonPath("$.dueDate").value("2026-11-20")).andExpect(jsonPath("$.status").value("DONE"));
    }

    // 문장형 제목은 명사형 안내가 포함된 필드 오류로 거절한다.
    @Test void rejectsSentenceTitleOnEdit() throws Exception {
        edit(unassigned(), "UACTOR1", "{\"title\":\"배포 로그를 정리한다.\"}")
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("VALIDATION_ERROR"))
                .andExpect(jsonPath("$.fieldErrors.title").exists());
    }

    // 빈 제목은 기존 제목을 지우지 않고 검증 오류로 거절한다.
    @Test void rejectsBlankTitleOnEdit() throws Exception {
        edit(unassigned(), "UACTOR1", "{\"title\":\" \"}")
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("VALIDATION_ERROR"));
    }

    // null 제목은 수정 요청으로 허용하지 않는다.
    @Test void rejectsNullTitleOnEdit() throws Exception {
        edit(unassigned(), "UACTOR1", "{\"title\":null}")
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("VALIDATION_ERROR"));
    }

    // 수정할 필드 없는 본문은 성공으로 처리하지 않는다.
    @Test void rejectsEmptyPatch() throws Exception {
        edit(unassigned(), "UACTOR1", "{}")
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("VALIDATION_ERROR"));
    }

    // null 상태는 상태 생략과 구분해 거절한다.
    @Test void rejectsNullStatus() throws Exception {
        edit(unassigned(), "UACTOR1", "{\"status\":null}")
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("VALIDATION_ERROR"));
    }

    // 정의하지 않은 상태 문자열은 파싱 오류로 거절한다.
    @Test void rejectsUnknownStatus() throws Exception {
        edit(unassigned(), "UACTOR1", "{\"status\":\"PENDING\"}")
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("INVALID_REQUEST"));
    }

    // PATCH도 알려지지 않은 필드를 오류 응답에 표시한다.
    @Test void rejectsUnknownPatchField() throws Exception {
        edit(unassigned(), "UACTOR1", "{\"title\":\"새 API 검토\",\"assignee\":\"민수\"}")
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("VALIDATION_ERROR"))
                .andExpect(jsonPath("$.fieldErrors.assignee").exists());
    }

    // 내부 제공 여부 플래그는 외부 수정 필드로 받지 않는다.
    @Test void rejectsInternalProvidedFlag() throws Exception {
        edit(unassigned(), "UACTOR1", "{\"title\":\"새 API 검토\",\"dueDateProvided\":true}")
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("VALIDATION_ERROR"))
                .andExpect(jsonPath("$.fieldErrors.dueDateProvided").exists());
    }

    // 같은 담당자의 미완료 업무로 제목을 바꾸면 기존 ID가 포함된 409를 반환한다.
    @Test void rejectsDuplicateTitleOnEdit() throws Exception {
        Task existing = tasks.save(Task.create("새 API 검토", null, null));
        edit(unassigned(), "UACTOR1", "{\"title\":\" 새  API 검토. \"}")
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("DUPLICATE_TASK"))
                .andExpect(jsonPath("$.existingTaskId").value(existing.getId()));
    }

    // 완료 취소는 TODO로 바꾸고 완료자·완료 시각을 모두 지운다.
    @Test void reopensDoneTaskAndClearsCompletionMetadata() throws Exception {
        Task task = doneTask();
        mvc.perform(post("/api/tasks/{id}/reopen", task.getId()).header("X-Slack-User-Id", "UACTOR1"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.status").value("TODO"))
                .andExpect(jsonPath("$.completedBySlackUserId").doesNotExist())
                .andExpect(jsonPath("$.completedAt").doesNotExist());
        assertThat(task.getCompletedBySlackUserId()).isNull();
        assertThat(task.getCompletedAt()).isNull();
    }

    // 동일한 TODO가 있으면 완료 취소 대신 기존 업무 ID를 반환한다.
    @Test void rejectsReopenWhenDuplicateTodoExists() throws Exception {
        Task done = doneTask();
        Task existing = tasks.save(Task.create(done.getTitle(), null, null));
        mvc.perform(post("/api/tasks/{id}/reopen", done.getId()).header("X-Slack-User-Id", "UACTOR1"))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("DUPLICATE_TASK"))
                .andExpect(jsonPath("$.existingTaskId").value(existing.getId()));
        assertThat(done.getStatus()).isEqualTo(TaskStatus.DONE);
    }

    // 완료 취소 경로도 변경자 헤더가 필수다.
    @Test void rejectsReopenWithoutActor() throws Exception {
        mvc.perform(post("/api/tasks/{id}/reopen", doneTask().getId()))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("INVALID_SLACK_USER_ID"));
    }

    // 다른 담당자의 완료 취소는 거절한다.
    @Test void rejectsReopenByAnotherUser() throws Exception {
        Task task = assigned();
        task.changeStatus(TaskStatus.DONE, "UOWNER1", NOW.minusSeconds(60));
        mvc.perform(post("/api/tasks/{id}/reopen", task.getId()).header("X-Slack-User-Id", "UOTHER1"))
                .andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value("TASK_MUTATION_FORBIDDEN"));
    }

    // 담당자는 자신의 완료 업무를 다시 열 수 있다.
    @Test void ownerMayReopenAssignedTask() throws Exception {
        Task task = assigned();
        task.changeStatus(TaskStatus.DONE, "UOWNER1", NOW.minusSeconds(60));
        mvc.perform(post("/api/tasks/{id}/reopen", task.getId()).header("X-Slack-User-Id", "UOWNER1"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.status").value("TODO"));
    }

    // 관리자는 다른 담당자의 완료 업무도 다시 열 수 있다.
    @Test void adminMayReopenAssignedTask() throws Exception {
        Task task = assigned();
        task.changeStatus(TaskStatus.DONE, "UOWNER1", NOW.minusSeconds(60));
        mvc.perform(post("/api/tasks/{id}/reopen", task.getId()).header("X-Slack-User-Id", "UADMIN1"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.status").value("TODO"));
    }

    // 기존 TODO PATCH도 완료 취소와 같은 중복 판정을 적용한다.
    @Test void legacyTodoPatchRejectsDuplicate() throws Exception {
        Task done = doneTask();
        Task existing = tasks.save(Task.create(done.getTitle(), null, null));
        edit(done, "UACTOR1", "{\"status\":\"TODO\"}")
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("DUPLICATE_TASK"))
                .andExpect(jsonPath("$.existingTaskId").value(existing.getId()));
    }

    // 이미 TODO인 업무를 다시 열어도 변경 이력을 중복 기록하지 않는다.
    @Test void reopeningTodoIsIdempotent() throws Exception {
        Task task = unassigned();
        mvc.perform(post("/api/tasks/{id}/reopen", task.getId()).header("X-Slack-User-Id", "UACTOR1"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.status").value("TODO"));
        assertThat(events.findAllByTask_IdOrderByAtAscIdAsc(task.getId())).isEmpty();
    }

    // 삭제는 목록에서 숨기되 실제 행과 삭제 감사 이력을 보존한다.
    @Test void softDeleteHidesTaskAndPreservesAuditAndDatabaseRow() throws Exception {
        Task task = unassigned();
        mvc.perform(delete("/api/tasks/{id}", task.getId()).header("X-Slack-User-Id", "UACTOR1"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.deleted").value(true));
        mvc.perform(get("/api/tasks")).andExpect(status().isOk())
                .andExpect(jsonPath("$[*].id", not(hasItem(task.getId().intValue()))));
        tasks.flush();
        assertThat(tasks.findById(task.getId())).hasValueSatisfying(saved -> assertThat(saved.isDeleted()).isTrue());
        assertThat(events.findAllByTask_IdOrderByAtAscIdAsc(task.getId())).singleElement().satisfies(event -> {
            assertThat(event.getType()).isEqualTo("DELETED");
            assertThat(event.getBeforeValue()).isEqualTo("false");
            assertThat(event.getAfterValue()).isEqualTo("true");
            assertThat(event.getBySlackUserId()).isEqualTo("UACTOR1");
        });
    }

    // 삭제된 업무를 다시 수정하려 하면 존재하지 않는 업무로 응답한다.
    @Test void rejectsEditingDeletedTask() throws Exception {
        Task task = unassigned();
        task.softDelete();
        tasks.flush();
        edit(task, "UACTOR1", "{\"title\":\"새 API 검토\"}")
                .andExpect(status().isNotFound()).andExpect(jsonPath("$.code").value("TASK_NOT_FOUND"));
    }

    // 다른 담당자의 삭제는 거절한다.
    @Test void rejectsDeleteByAnotherUser() throws Exception {
        mvc.perform(delete("/api/tasks/{id}", assigned().getId()).header("X-Slack-User-Id", "UOTHER1"))
                .andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value("TASK_MUTATION_FORBIDDEN"));
    }

    // 담당자는 자신의 할 일을 삭제할 수 있다.
    @Test void ownerMayDeleteAssignedTask() throws Exception {
        mvc.perform(delete("/api/tasks/{id}", assigned().getId()).header("X-Slack-User-Id", "UOWNER1"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.deleted").value(true));
    }

    // 관리자는 다른 담당자의 할 일을 삭제할 수 있다.
    @Test void adminMayDeleteAssignedTask() throws Exception {
        mvc.perform(delete("/api/tasks/{id}", assigned().getId()).header("X-Slack-User-Id", "UADMIN1"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.deleted").value(true));
    }

    // 삭제 경로도 변경자 헤더가 필수다.
    @Test void rejectsDeleteWithoutActor() throws Exception {
        mvc.perform(delete("/api/tasks/{id}", unassigned().getId()))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("INVALID_SLACK_USER_ID"));
    }

    private Task unassigned() { return tasks.save(Task.create("기존 API 검토", null, null)); }

    private Task assigned() {
        Member owner = members.save(new Member("김", "민수", "백엔드팀", "UOWNER1"));
        return tasks.save(Task.create("기존 API 검토", owner, null));
    }

    private Task doneTask() {
        Task task = unassigned();
        task.changeStatus(TaskStatus.DONE, "UACTOR1", NOW.minusSeconds(60));
        return task;
    }

    private ResultActions edit(Task task, String actor, String json) throws Exception {
        return mvc.perform(patch("/api/tasks/{id}", task.getId()).header("X-Slack-User-Id", actor)
                .contentType(MediaType.APPLICATION_JSON).content(json));
    }
}
