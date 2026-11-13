package com.meetodo.slack;

import com.meetodo.task.*;
import com.meetodo.task.dto.TaskResponse;
import com.slack.api.bolt.App;
import com.slack.api.bolt.context.builtin.ViewSubmissionContext;
import com.slack.api.bolt.handler.builtin.ViewSubmissionHandler;
import com.slack.api.bolt.request.builtin.ViewSubmissionRequest;
import com.slack.api.app_backend.views.payload.ViewSubmissionPayload;
import com.slack.api.methods.MethodsClient;
import com.slack.api.methods.request.views.ViewsOpenRequest;
import com.slack.api.methods.response.views.ViewsOpenResponse;
import com.slack.api.model.view.View;
import com.slack.api.model.view.ViewState;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.test.util.ReflectionTestUtils;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@org.junit.jupiter.api.extension.ExtendWith(org.springframework.boot.test.system.OutputCaptureExtension.class)
class SlackTaskEditTests {
    final MethodsClient client = mock(MethodsClient.class);
    final SlackBotService bot = mock(SlackBotService.class);
    final TaskService tasks = mock(TaskService.class);
    final Clock clock = mock(Clock.class);
    SlackTaskEditService edits;
    @BeforeEach void setup() throws Exception {
        when(clock.instant()).thenReturn(Instant.parse("2026-11-13T00:00:00Z"));
        edits = new SlackTaskEditService(client, bot, tasks, clock);
        Task task = Task.create("기존 제목", null, LocalDate.of(2026, 11, 20)); ReflectionTestUtils.setField(task, "id", 7L);
        when(tasks.authorizeMutation(7L, "U1")).thenReturn(TaskResponse.from(task));
        var response = new ViewsOpenResponse(); response.setOk(true); response.setView(View.builder().id("V1").build());
        when(client.viewsOpen(any(ViewsOpenRequest.class))).thenReturn(response);
    }
    private void open(String operation) { edits.select("trigger-1", "C1", "1.2", "U1", operation + ":7"); }

    @Test void titleModalLoadsDatabaseValueAndTwoHundredCharacterLimit() throws Exception {
        // 제목 모달은 DB의 현재 제목과 200자 제한을 표시하며 task ID를 메타데이터로 신뢰하지 않는다.
        open("title");
        var request = ArgumentCaptor.forClass(ViewsOpenRequest.class); verify(client).viewsOpen(request.capture());
        assertThat(request.getValue().getTriggerId()).isEqualTo("trigger-1");
        assertThat(request.getValue().getViewAsString()).contains("기존 제목", "plain_text_input", "\"max_length\":200").doesNotContain("private_metadata");
        verify(bot).authorizeTaskMessage("C1", "1.2", "U1", 7L);
    }
    @Test void forgedTaskCannotOpenModal() throws Exception {
        // 저장 메시지에 없는 업무는 권한 조회나 모달 API 호출 전에 차단한다.
        doThrow(new IllegalArgumentException()).when(bot).authorizeTaskMessage("C1", "1.2", "U1", 999L);
        edits.select("trigger-1", "C1", "1.2", "U1", "title:999");
        verifyNoInteractions(tasks); verify(client, never()).viewsOpen(any(ViewsOpenRequest.class));
    }
    @Test void forbiddenOwnerCannotOpenModal() throws Exception {
        // 담당자나 관리자가 아닌 사용자는 모달을 열 수 없다.
        when(tasks.authorizeMutation(7L, "U1")).thenThrow(new TaskMutationException(org.springframework.http.HttpStatus.FORBIDDEN, "TASK_MUTATION_FORBIDDEN", "권한이 없어요"));
        open("title"); verify(client, never()).viewsOpen(any(ViewsOpenRequest.class));
    }
    @Test void submittedTitleRechecksMembershipAndServerPermission() throws Exception {
        // 제출 시에도 저장된 원본 좌표와 현재 권한을 다시 검증한 뒤 제목만 수정한다.
        open("title"); edits.submit("V1", "U1", true, "수정 제목");
        verify(bot, times(2)).authorizeTaskMessage("C1", "1.2", "U1", 7L);
        verify(tasks, times(2)).authorizeMutation(7L, "U1"); verify(tasks).edit(7L, "수정 제목", false, null, "U1");
    }
    @Test void revokedPermissionPreventsSubmission() {
        // 모달을 연 뒤 담당자가 바뀌어 권한을 잃으면 제출을 거절한다.
        open("title"); when(tasks.authorizeMutation(7L, "U1")).thenThrow(new IllegalArgumentException());
        edits.submit("V1", "U1", true, "수정 제목"); verify(tasks, never()).edit(anyLong(), any(), anyBoolean(), any(), any());
    }
    @Test void anotherUserCannotSubmitPendingModal() {
        // 다른 사용자는 view ID를 알아도 서버가 저장한 모달의 작업을 제출하지 못한다.
        open("title"); edits.submit("V1", "UOTHER", true, "공격 제목");
        verify(tasks, never()).edit(anyLong(), any(), anyBoolean(), any(), any());
        edits.submit("V1", "U1", true, "정상 제목"); verify(tasks).edit(7L, "정상 제목", false, null, "U1");
    }
    @Test void duplicateSubmissionMutatesOnlyOnce() {
        // 소비한 view ID 재전송은 같은 수정을 다시 실행하지 않는다.
        open("title"); edits.submit("V1", "U1", true, "수정 제목"); edits.submit("V1", "U1", true, "수정 제목");
        verify(tasks, times(1)).edit(7L, "수정 제목", false, null, "U1");
    }
    @Test void expiredModalCannotMutateTask() {
        // 30분 수명이 지난 모달은 서버 메모리에서 만료되어 수정하지 못한다.
        open("title"); when(clock.instant()).thenReturn(Instant.parse("2026-11-13T00:30:00Z"));
        edits.submit("V1", "U1", true, "수정 제목"); verify(tasks, never()).edit(anyLong(), any(), anyBoolean(), any(), any());
    }
    @Test void closedModalCannotMutateTask() {
        // 취소한 모달은 저장된 작업 정보도 제거한다.
        open("title"); edits.close("V1", "U1"); edits.submit("V1", "U1", true, "수정 제목");
        verify(tasks, never()).edit(anyLong(), any(), anyBoolean(), any(), any());
    }
    @Test void blankTitleDoesNotReachMutation() {
        // Slack 필수 입력을 우회한 공백 제목도 서버에서 거절한다.
        open("title"); edits.submit("V1", "U1", true, " \n "); verify(tasks, never()).edit(anyLong(), any(), anyBoolean(), any(), any());
    }
    @Test void overlongTitleDoesNotReachMutation() {
        // 201자 제목은 잘라 저장하지 않고 거절한다.
        open("title"); edits.submit("V1", "U1", true, "가".repeat(201)); verify(tasks, never()).edit(anyLong(), any(), anyBoolean(), any(), any());
    }
    @Test void exactTitleLimitIsAccepted() {
        // 200자 제목은 경계값 그대로 공통 서비스에 전달한다.
        open("title"); edits.submit("V1", "U1", true, "가".repeat(200)); verify(tasks).edit(7L, "가".repeat(200), false, null, "U1");
    }
    @Test void dateModalAllowsExplicitDateClearing() throws Exception {
        // 선택을 해제할 수 있는 datepicker는 현재 날짜를 표시하고 null 제출 시 기한을 지운다.
        open("due"); var request = ArgumentCaptor.forClass(ViewsOpenRequest.class); verify(client).viewsOpen(request.capture());
        assertThat(request.getValue().getViewAsString()).contains("datepicker", "2026-11-20", "\"optional\":true");
        edits.submit("V1", "U1", true, null); verify(tasks).edit(7L, null, true, null, "U1");
    }
    @Test void validDateReachesSharedEditService() {
        // 엄격한 날짜 형식은 LocalDate로 변환해 공통 서비스에 전달한다.
        open("due"); edits.submit("V1", "U1", true, "2026-11-21"); verify(tasks).edit(7L, null, true, LocalDate.of(2026, 11, 21), "U1");
    }
    @Test void invalidCalendarDateIsRejected() {
        // 존재하지 않는 달력 날짜를 임의 보정하지 않는다.
        open("due"); edits.submit("V1", "U1", true, "2026-02-30"); verify(tasks, never()).edit(anyLong(), any(), anyBoolean(), any(), any());
    }
    @Test void missingDateFieldDoesNotClearStoredDate() {
        // 날짜 필드가 누락된 비정상 제출을 명시적 선택 해제로 해석하지 않는다.
        open("due"); edits.submit("V1", "U1", false, null); verify(tasks, never()).edit(anyLong(), any(), anyBoolean(), any(), any());
    }
    @Test void deleteSelectionOnlyOpensConfirmationModal() throws Exception {
        // 메뉴의 삭제 선택만으로는 삭제하지 않고 사용자가 확인할 모달을 연다.
        open("delete"); var request = ArgumentCaptor.forClass(ViewsOpenRequest.class); verify(client).viewsOpen(request.capture());
        assertThat(request.getValue().getViewAsString()).contains("할 일 삭제 확인", "이 할 일을 삭제할까요?", "기존 제목");
        verify(tasks, never()).delete(anyLong(), any());
    }
    @Test void confirmedDeleteRechecksPermissionAndDeletesTask() {
        // 서버가 저장한 삭제 모달의 확인 제출만 공통 삭제 함수를 호출한다.
        open("delete"); edits.submit("V1", "U1", false, null); verify(tasks).delete(7L, "U1");
    }
    @Test void reopenMenuUsesSharedPermissionAndReopenService() throws Exception {
        // 재개 메뉴는 별도 입력 없이 현재 권한을 검증하고 공통 서비스로 다시 연다.
        open("reopen"); verify(tasks).reopen(7L, "U1"); verify(client, never()).viewsOpen(any(ViewsOpenRequest.class));
    }

    @Test void blankTitleValidationOnlySendsPrivateSafeMessage() {
        // 잘못된 제목은 공개 메시지나 공급자 응답 대신 요청자만 보는 안내로 반환한다.
        open("title"); edits.submit("V1", "U1", true, " ");
        verify(bot).ephemeral("C1", "U1", "할 일을 변경하지 못했어요. 메시지에서 다시 시도해 주세요.");
        verify(bot, never()).refreshTask(anyLong());
        verify(tasks, never()).edit(anyLong(), any(), anyBoolean(), any(), any());
    }

    @Test void invalidDateValidationOnlySendsPrivateSafeMessage() {
        // 잘못된 날짜는 파싱 예외를 노출하지 않고 요청자에게만 알려 준다.
        open("due"); edits.submit("V1", "U1", true, "2026-02-30");
        verify(bot).ephemeral("C1", "U1", "할 일을 변경하지 못했어요. 메시지에서 다시 시도해 주세요.");
        verify(tasks, never()).edit(anyLong(), any(), anyBoolean(), any(), any());
    }

    @Test void modalProviderFailureDoesNotLeakSecretToReplyOrLog(org.springframework.boot.test.system.CapturedOutput output) throws Exception {
        // views.open의 원문 예외와 비밀값은 비공개 응답과 애플리케이션 로그 모두에서 제외한다.
        when(client.viewsOpen(any(ViewsOpenRequest.class))).thenThrow(new java.io.IOException("provider secret body"));
        open("title");
        verify(bot).ephemeral("C1", "U1", "할 일을 변경하지 못했어요. 메시지에서 다시 시도해 주세요.");
        assertThat(output.getAll()).contains("Slack task mutation failed: INTERNAL_ERROR").doesNotContain("provider secret body");
        edits.submit("V1", "U1", true, "수정 제목");
        verify(tasks, never()).edit(anyLong(), any(), anyBoolean(), any(), any());
    }

    @Test void cancelledDeleteConfirmationDoesNotDelete() {
        // 삭제 확인을 취소하면 이후 지연된 제출도 업무를 삭제하지 못한다.
        open("delete"); edits.close("V1", "U1"); edits.submit("V1", "U1", false, null);
        verify(tasks, never()).delete(anyLong(), any());
    }
    @Test void homeTitleModalUsesHomeScopeAndSharedMutation() throws Exception {
        // Home 수정은 채널 메시지 좌표 대신 열린 Home의 업무 소속을 열기·제출 때 재검증한다.
        var home=mock(SlackHomeService.class); edits.setHomes(home);
        edits.selectHome("trigger-1","U1","title:7");
        edits.submit("V1","U1",true,"새 업무 제목");
        verify(home,times(2)).authorizeTask("U1",7L);
        verify(tasks).edit(7L,"새 업무 제목",false,null,"U1");
        verify(bot,never()).authorizeTaskMessage(any(),any(),any(),anyLong());
    }

    @Test void homeMutationErrorIsPublishedPrivatelyOnOwnHome() {
        // Home 권한 오류는 채널이 아니라 요청자 Home에만 안전한 안내를 표시한다.
        var home=mock(SlackHomeService.class); edits.setHomes(home);
        doThrow(new IllegalArgumentException("provider secret")).when(home).authorizeTask("U1",7L);
        edits.selectHome("trigger-1","U1","title:7");
        verify(home).publish(eq("U1"),argThat(text -> !text.contains("provider secret")));
        verifyNoInteractions(tasks);
        verify(bot,never()).ephemeral(any(),any(),any());
    }

}
