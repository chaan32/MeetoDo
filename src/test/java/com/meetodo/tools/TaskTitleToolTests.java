package com.meetodo.tools;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.meetodo.meeting.*;
import com.meetodo.member.MemberRepository;
import com.meetodo.task.*;
import java.time.*;
import java.util.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class TaskTitleToolTests {
    final TaskRepository tasks=mock(TaskRepository.class);
    final MemberRepository members=mock(MemberRepository.class);
    final ToolCallLogRepository logs=mock(ToolCallLogRepository.class);
    final TaskMentionService mentions=mock(TaskMentionService.class);
    final Clock clock=Clock.fixed(Instant.parse("2026-11-11T00:00:00Z"),ZoneId.of("Asia/Seoul"));
    final ToolExecutionService execution=new ToolExecutionService(logs,new ObjectMapper());
    final CreateTaskTool tool=new CreateTaskTool(members,new TaskService(tasks,members,mentions,clock),mentions,execution,clock);
    final Meeting meeting=Meeting.create("단위 검증 원문",MeetingSource.API_TEXT);
    final List<ToolCallLog> storedLogs=new ArrayList<>();
    @BeforeEach void setup() {
        ReflectionTestUtils.setField(meeting,"id",501L);
        when(logs.save(any(ToolCallLog.class))).thenAnswer(call -> { ToolCallLog log=call.getArgument(0); storedLogs.add(log); return log; });
        when(logs.findAllByMeetingIdOrderByCallOrderAsc(501L)).thenAnswer(call -> List.copyOf(storedLogs));
        when(logs.findTopByMeeting_IdOrderByCallOrderDesc(501L)).thenAnswer(call -> storedLogs.stream().reduce((a,b)->b));
        when(tasks.save(any(Task.class))).thenAnswer(call -> call.getArgument(0));
    }
    @Test void rejectsSentenceCopiedFromSlackTranscriptWithoutSaving() {
        // 실제 사용자 사례의 회의 문장 전체 제목은 저장 대신 수정 안내를 반환한다.
        ToolResult result=tool.createTask("슬랙입력 API 명세를 정리한다.","unassigned:NONE","",meeting);
        assertThat(result.success()).isFalse(); assertThat(result.errorCode()).isEqualTo("INVALID_TITLE_FORMAT");
        assertThat(result.message()).contains("명사형 업무 이름","'배포 로그를 정리한다.' → '배포 로그 정리'");
        verify(tasks,never()).save(any());
    }
    @Test void rejectsDeploymentLogSentenceAndCountsValidationFailure() {
        // 두 번째 실제 사례도 실패 로그와 검증 실패 수에 함께 반영한다.
        ToolResult result=tool.createTask("배포 로그를 정리한다.","unassigned:NONE","",meeting);
        assertThat(result.errorCode()).isEqualTo("INVALID_TITLE_FORMAT");
        assertThat(execution.validationFailureCount(501L)).isEqualTo(1);
        assertThat(storedLogs).singleElement().satisfies(log -> {
            assertThat(log.getSuccess()).isFalse(); assertThat(log.getResult()).contains("INVALID_TITLE_FORMAT");
        });
        verify(tasks,never()).save(any());
    }
    @Test void acceptsNounPhraseAndCleansOnlyWhitespaceAndFinalPeriods() {
        // 명사형 제목의 의미와 대소문자는 유지하고 표시용 공백·마침표만 정리한다.
        ToolResult result=tool.createTask("  API  명세 정리.  ","unassigned:NONE","",meeting);
        assertThat(result.success()).isTrue();
        var saved=org.mockito.ArgumentCaptor.forClass(Task.class); verify(tasks).save(saved.capture());
        assertThat(saved.getValue().getTitle()).isEqualTo("API 명세 정리");
    }
    @Test void canonicalTitleReusesExistingTodoDespiteFinalPeriodDifference() {
        // 과거 명사형 끝 마침표 차이는 백필 없이 현재 비교에서만 기존 TODO로 재사용한다.
        Task existing=Task.create("배포 로그 정리.",null,null);
        ReflectionTestUtils.setField(existing,"id",77L);
        when(tasks.findAllByAssigneeIsNullAndStatusOrderByCreatedAtDesc(TaskStatus.TODO)).thenReturn(List.of(existing));
        ToolResult result=tool.createTask("배포 로그 정리","unassigned:NONE","",meeting);
        assertThat(result.success()).isTrue(); assertThat(result.data()).contains("EXISTING","77");
        verify(tasks,never()).save(any());
    }
    @Test void findTasksUsesSameWhitespaceAndFinalPeriodComparison() {
        // 조회 도구도 createTask 서버 검사와 같은 제목 정규화로 중복을 찾는다.
        Task existing=Task.create("배포 로그 정리.",null,null);
        ReflectionTestUtils.setField(existing,"id",77L);
        when(tasks.findAllByAssigneeIsNullAndStatusOrderByCreatedAtDesc(TaskStatus.TODO)).thenReturn(List.of(existing));
        var find=new FindTasksTool(members,tasks,execution,mentions);
        ToolResult result=find.findTasks(null," 배포  로그 정리 ",meeting);
        assertThat(result.success()).isTrue(); assertThat(result.data()).contains("EXISTING","77");
    }
    @Test void createTaskToolSchemaExplainsNounTitlesWithExamples() {
        // Spring AI가 모델에 보내는 실제 title JSON 스키마 설명에도 규칙·예시가 포함된다.
        var callback=java.util.Arrays.stream(org.springframework.ai.support.ToolCallbacks.from(tool))
                .filter(item -> item.getToolDefinition().name().equals("createTask")).findFirst().orElseThrow();
        assertThat(callback.getToolDefinition().inputSchema()).contains("명사형 업무 이름","좋은 예:","나쁜 예:","API 명세 정리","배포 로그를 정리한다.");
    }

    @Test void pastDateRetryRecognizesSameTitleAfterFinalPeriodCleanup() {
        // 제목 끝 마침표 정리가 있어도 과거 날짜 재시도 이력을 찾아 기존 마감일 없음 폴백을 유지한다.
        var first=tool.createTask("회의실 예약 확인.","unassigned:NONE","2026-11-10",meeting);
        var retried=tool.createTask("회의실 예약 확인.","unassigned:NONE","2026-11-10",meeting);
        assertThat(first.errorCode()).isEqualTo("PAST_DUE_DATE"); assertThat(retried.success()).isTrue();
        var saved=org.mockito.ArgumentCaptor.forClass(Task.class); verify(tasks).save(saved.capture());
        assertThat(saved.getValue().getTitle()).isEqualTo("회의실 예약 확인"); assertThat(saved.getValue().getDueDate()).isNull();
    }

}
