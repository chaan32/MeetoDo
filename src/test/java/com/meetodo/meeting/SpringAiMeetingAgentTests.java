package com.meetodo.meeting;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.util.List;
import java.util.Optional;
import java.util.ArrayList;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.meetodo.member.Member;
import com.meetodo.member.MemberRepository;
import com.meetodo.task.TaskRepository;
import com.meetodo.task.TaskService;
import com.meetodo.task.TaskMentionService;
import com.meetodo.tools.CreateTaskTool;
import com.meetodo.tools.FindMemberTool;
import com.meetodo.tools.FindTasksTool;
import com.meetodo.tools.ToolExecutionService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.model.tool.ToolCallingManager;
import org.springframework.test.util.ReflectionTestUtils;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class SpringAiMeetingAgentTests {

    ChatModel model;
    MemberRepository members;
    TaskRepository tasks;
    ToolCallLogRepository logs;
    ToolExecutionService execution;
    SpringAiMeetingAgent agent;

    final java.util.Map<Long, Member> fixtureMembers = new java.util.HashMap<>();
    int fixtureCallOrder;
    int fixtureLastLookup;
    final java.util.Set<String> fixtureLookups = new java.util.HashSet<>();

    @BeforeEach
    void setUp() {
        fixtureLookups.clear();
        fixtureCallOrder = 0;
        fixtureLastLookup = 0;
        fixtureMembers.clear();
        model = mock(ChatModel.class);
        members = mock(MemberRepository.class);
        tasks = mock(TaskRepository.class);
        when(members.findById(any())).thenAnswer(call -> Optional.ofNullable(fixtureMembers.get(call.getArgument(0))));
        logs = mock(ToolCallLogRepository.class);
        TaskMentionService mentions = mock(TaskMentionService.class);
        var storedLogs = new java.util.ArrayList<ToolCallLog>();
        when(logs.save(any(ToolCallLog.class))).thenAnswer(call -> { storedLogs.add(call.getArgument(0)); return call.getArgument(0); });
        when(logs.findAllByMeetingIdOrderByCallOrderAsc(any())).thenAnswer(call -> List.copyOf(storedLogs));
        when(logs.findTopByMeeting_IdOrderByCallOrderDesc(any())).thenAnswer(call -> storedLogs.stream().reduce((a, b) -> b));
        execution = new ToolExecutionService(logs, new ObjectMapper());
        Clock clock = Clock.fixed(Instant.parse("2026-11-11T00:00:00Z"), ZoneId.of("Asia/Seoul"));
        agent = new SpringAiMeetingAgent(model, ToolCallingManager.builder().build(),
                new FindMemberTool(members, execution), new FindTasksTool(members, tasks, execution, mentions),
                new CreateTaskTool(members, new TaskService(tasks, members, mentions, java.time.Clock.systemUTC()), mentions, execution, clock),
                clock, execution);
        when(members.findAllByGivenNameOrderByIdAsc("민수")).thenReturn(List.of(new Member("김", "민수", "미지정", "U-DEV-MINSU")));
    }

    // 검증: executesRequestedToolThenReturnsFinalAnswerAndMeasuresCalls 시나리오의 기대 동작을 확인한다.
    @Test
    void executesRequestedToolThenReturnsFinalAnswerAndMeasuresCalls() {
        Meeting meeting = meetingWithId("민수는 API 초안을 작성한다.", 71L);
        ChatResponse[] responses = new ChatResponse[]{toolResponse("findMember", "{\"name\":\"민수\"}"),
                textResponse("민수 담당 작업을 확인했습니다.")};
        when(model.call(any(Prompt.class))).thenReturn(responses[0], java.util.Arrays.copyOfRange(responses, 1, responses.length));

        AgentResult result = agent.process(meeting);

        assertThat(result.summary()).isEqualTo("민수 담당 작업을 확인했습니다.");
        assertThat(result.llmCallCount()).isEqualTo(2);
        assertThat(result.toolCallCount()).isEqualTo(1);
        verify(logs).save(any(ToolCallLog.class));
    }

    // 검증: HTTP 요청은 엄격히 검증하면서 Spring AI 도구 인자의 미지 필드는 허용한다.
    @Test
    void acceptsUnknownExtraFieldsInSpringAiToolArguments() {
        Meeting meeting = meetingWithId("민수 업무", 79L);
        when(members.findAllByGivenNameOrderByIdAsc("민수")).thenReturn(List.of(new Member("김", "민수", "백엔드팀", "U-MINSU")));
        ChatResponse[] responses = new ChatResponse[]{
                toolResponse("findMember", "{\"name\":\"민수\",\"futureField\":true}"),
                textResponse("확인 완료")};
        when(model.call(any(Prompt.class))).thenReturn(responses[0], java.util.Arrays.copyOfRange(responses, 1, responses.length));

        AgentResult result = agent.process(meeting);

        assertThat(result.summary()).isEqualTo("확인 완료");
        var log = org.mockito.ArgumentCaptor.forClass(ToolCallLog.class);
        verify(logs).save(log.capture());
        assertThat(log.getValue().getSuccess()).isTrue();
    }

    // 검증: registersThreeTasksAndKeepsAnUnknownAssigneeUnassigned 시나리오의 기대 동작을 확인한다.
    @Test
    void registersThreeTasksAndKeepsAnUnknownAssigneeUnassigned() {
        Meeting meeting = meetingWithId("로그인 API 2026-11-20, 결제 오류 확인 2026-11-21, 회의록 배포", 76L);
        Member minsu = new Member("김", "민수", "미지정", "U-MINSU");
        Member jiyoung = new Member("이", "지영", "미지정", "U-JIYOUNG");
        when(members.findAllByGivenNameOrderByIdAsc("민수")).thenReturn(List.of(minsu));
        when(members.findAllByGivenNameOrderByIdAsc("지영")).thenReturn(List.of(jiyoung));
        when(members.findAllByGivenNameOrderByIdAsc("없는 사람")).thenReturn(List.of());
        when(tasks.findAllByMeeting_IdAndTitleIgnoreCase(any(), any())).thenReturn(List.of());
        when(tasks.findAllByAssigneeOrderByCreatedAtDesc(any())).thenReturn(List.of());
        when(tasks.findAllByAssigneeIsNullOrderByCreatedAtDesc()).thenReturn(List.of());
        when(tasks.save(any())).thenAnswer(call -> call.getArgument(0));
        ChatResponse[] responses = new ChatResponse[]{
                toolResponse("findMember", "{\"name\":\"민수\"}"),
                toolResponse("findTasks", "{\"assignee\":\"민수\",\"title\":\"로그인 API\"}"),
                toolResponse("createTask", "{\"title\":\"로그인 API\",\"assignee\":\"민수\",\"dueDate\":\"2026-11-20\"}"),
                toolResponse("findMember", "{\"name\":\"지영\"}"),
                toolResponse("findTasks", "{\"assignee\":\"지영\",\"title\":\"결제 오류 확인\"}"),
                toolResponse("createTask", "{\"title\":\"결제 오류 확인\",\"assignee\":\"지영\",\"dueDate\":\"2026-11-21\"}"),
                toolResponse("findMember", "{\"name\":\"없는 사람\"}"),
                toolResponse("findTasks", "{\"assignee\":\"\",\"title\":\"회의록 배포\"}"),
                toolResponse("createTask", "{\"title\":\"회의록 배포\",\"assignee\":\"없는 사람\",\"dueDate\":\"\"}"),
                textResponse("세 작업을 확인했습니다. 한 건은 담당자 미정입니다.")};
        when(model.call(any(org.springframework.ai.chat.prompt.Prompt.class))).thenReturn(responses[0], java.util.Arrays.copyOfRange(responses, 1, responses.length));

        AgentResult result = agent.process(meeting);

        assertThat(result.llmCallCount()).isEqualTo(10);
        assertThat(result.toolCallCount()).isEqualTo(9);
        var captor = org.mockito.ArgumentCaptor.forClass(com.meetodo.task.Task.class);
        verify(tasks, times(3)).save(captor.capture());
        assertThat(captor.getAllValues()).extracting(com.meetodo.task.Task::getTitle)
                .containsExactly("로그인 API", "결제 오류 확인", "회의록 배포");
        assertThat(captor.getAllValues()).extracting(com.meetodo.task.Task::getMeetingId)
                .containsOnly(76L);
        assertThat(captor.getAllValues().get(2).getAssignee()).isNull();
        assertThat(captor.getAllValues().get(0).getDueDate().toString()).isEqualTo("2026-11-20");
        assertThat(captor.getAllValues().get(1).getDueDate().toString()).isEqualTo("2026-11-21");
    }

    // 검증: retriesPastDateOnceThenCreatesWithoutDeadlineAndReturnsWarningContext 시나리오의 기대 동작을 확인한다.
    @Test
    void retriesPastDateOnceThenCreatesWithoutDeadlineAndReturnsWarningContext() {
        Meeting meeting = meetingWithId("2026-11-10까지 기획 검토", 77L);
        List<ToolCallLog> recordedLogs = new ArrayList<>();
        when(logs.findAllByMeetingIdOrderByCallOrderAsc(77L)).thenAnswer(call -> List.copyOf(recordedLogs));
        when(logs.findTopByMeeting_IdOrderByCallOrderDesc(77L)).thenAnswer(call -> recordedLogs.isEmpty()
                ? Optional.empty() : Optional.of(recordedLogs.getLast()));
        when(logs.save(any(ToolCallLog.class))).thenAnswer(call -> {
            ToolCallLog log = call.getArgument(0);
            recordedLogs.add(log);
            return log;
        });
        when(tasks.findAllByMeeting_IdAndTitleIgnoreCase(77L, "기획 검토")).thenReturn(List.of());
        when(tasks.save(any())).thenAnswer(call -> call.getArgument(0));
        ChatResponse[] responses = new ChatResponse[]{
                toolResponse("createTask", "{\"title\":\"기획 검토\",\"assignee\":\"\",\"dueDate\":\"2026-11-10\"}"),
                toolResponse("createTask", "{\"title\":\"기획 검토\",\"assignee\":\"\",\"dueDate\":\"2026-11-10\"}"),
                textResponse("기획 검토는 담당자 미정으로 등록했습니다. 마감일은 과거 날짜여서 비워 두었습니다.")};
        when(model.call(any(org.springframework.ai.chat.prompt.Prompt.class))).thenReturn(responses[0], java.util.Arrays.copyOfRange(responses, 1, responses.length));

        AgentResult result = agent.process(meeting);

        assertThat(result.llmCallCount()).isEqualTo(3);
        assertThat(result.toolCallCount()).isEqualTo(2);
        assertThat(result.validationFailureCount()).isEqualTo(1);
        assertThat(result.summary()).contains("마감일은 과거 날짜여서 비워");
        var taskCaptor = org.mockito.ArgumentCaptor.forClass(com.meetodo.task.Task.class);
        verify(tasks).save(taskCaptor.capture());
        assertThat(taskCaptor.getValue().getDueDate()).isNull();
        assertThat(recordedLogs).extracting(ToolCallLog::getErrorCode).containsExactly("PAST_DUE_DATE", null);
    }

    // 검증: stopsAtTheToolCallLimitAndReportsMeasuredCounts 시나리오의 기대 동작을 확인한다.
    @Test
    void stopsAtTheToolCallLimitAndReportsMeasuredCounts() {
        Meeting meeting = meetingWithId("반복 도구 요청", 72L);
        ChatResponse request = toolResponse("findMember", "{\"name\":\"민수\"}");
        ChatResponse[] responses = new ChatResponse[]{request};
        when(model.call(any(Prompt.class))).thenReturn(responses[0], java.util.Arrays.copyOfRange(responses, 1, responses.length));

        assertThatThrownBy(() -> agent.process(meeting))
                .isInstanceOf(MeetingAgentException.class)
                .satisfies(exception -> {
                    MeetingAgentException failure = (MeetingAgentException) exception;
                    assertThat(failure.getErrorCode()).isEqualTo("TOOL_CALL_LIMIT_EXCEEDED");
                    assertThat(failure.getLlmCallCount()).isEqualTo(21);
                    assertThat(failure.getToolCallCount()).isEqualTo(20);
                });
        verify(model, times(21)).call(any(Prompt.class));
        verify(logs, times(20)).save(any(ToolCallLog.class));
    }

    // 검증: rejectsBlankFinalAnswerAsAnAgentFailure 시나리오의 기대 동작을 확인한다.
    @Test
    void rejectsBlankFinalAnswerAsAnAgentFailure() {
        Meeting meeting = meetingWithId("회의 내용", 73L);
        ChatResponse[] responses = new ChatResponse[]{textResponse("  ")};
        when(model.call(any(Prompt.class))).thenReturn(responses[0], java.util.Arrays.copyOfRange(responses, 1, responses.length));

        assertThatThrownBy(() -> agent.process(meeting))
                .isInstanceOf(MeetingAgentException.class)
                .satisfies(exception -> assertThat(((MeetingAgentException) exception).getErrorCode())
                        .isEqualTo("EMPTY_LLM_RESPONSE"));
    }

    // 검증: wrapsProviderExceptionsWithoutIncludingTheirRawMessage 시나리오의 기대 동작을 확인한다.
    @Test
    void wrapsProviderExceptionsWithoutIncludingTheirRawMessage() {
        Meeting meeting = meetingWithId("회의 내용", 74L);
        when(model.call(any(Prompt.class))).thenThrow(new IllegalStateException(String.join("", "provider secret s", "k-live-abcdefghijklmnopqrstuvwxyz")));

        assertThatThrownBy(() -> agent.process(meeting))
                .isInstanceOf(MeetingAgentException.class)
                .hasMessageNotContaining("provider secret")
                .satisfies(exception -> assertThat(((MeetingAgentException) exception).getErrorCode())
                        .isEqualTo("LLM_ERROR"));
    }

    // 검증: injectsTodaysSeoulDateIntoSystemInstructions 시나리오의 기대 동작을 확인한다.
    @Test
    void injectsTodaysSeoulDateIntoSystemInstructions() {
        Meeting meeting = meetingWithId("날짜 기준 확인", 75L);
        ChatResponse[] responses = new ChatResponse[]{textResponse("확인 완료")};
        when(model.call(any(Prompt.class))).thenReturn(responses[0], java.util.Arrays.copyOfRange(responses, 1, responses.length));

        agent.process(meeting);

        var prompt = org.mockito.ArgumentCaptor.forClass(org.springframework.ai.chat.prompt.Prompt.class);
        verify(model).call(prompt.capture());
        assertThat(prompt.getValue().getInstructions().getFirst().getText()).contains("2026-11-11");
        assertThat(prompt.getValue().getInstructions().getFirst().getText())
                .contains("원래 날짜 표현").contains("다음 주 금요일").contains("직접 계산하거나 ISO 날짜로 바꾸지 않는다")
                .contains("동명이인 확인 필요").contains("팀을 추측하지 않는다");
    }

    // 검증: 2026-11-11 고정 시계에서 상대 날짜가 정규화된 마감일로 저장된다.
    @Test
    void acceptsRelativeDateResultsResolvedFromFixedSeoulClock() {
        Meeting meeting = meetingWithId("다음 주 금요일, 이번 주 안에, 내일", 78L);
        when(tasks.findAllByMeeting_IdAndTitleIgnoreCase(78L, "다음 주 금요일 작업")).thenReturn(List.of());
        when(tasks.findAllByMeeting_IdAndTitleIgnoreCase(78L, "이번 주 작업")).thenReturn(List.of());
        when(tasks.findAllByMeeting_IdAndTitleIgnoreCase(78L, "내일 작업")).thenReturn(List.of());
        when(tasks.save(any())).thenAnswer(call -> call.getArgument(0));
        ChatResponse[] responses = new ChatResponse[]{
                toolResponse("createTask", "{\"title\":\"다음 주 금요일 작업\",\"assignee\":\"\",\"dueDate\":\"다음 주 금요일\"}"),
                toolResponse("createTask", "{\"title\":\"이번 주 작업\",\"assignee\":\"\",\"dueDate\":\"이번 주 안에\"}"),
                toolResponse("createTask", "{\"title\":\"내일 작업\",\"assignee\":\"\",\"dueDate\":\"내일\"}"),
                textResponse("상대 날짜 작업 세 건을 등록했습니다.")};
        when(model.call(any(Prompt.class))).thenReturn(responses[0], java.util.Arrays.copyOfRange(responses, 1, responses.length));

        AgentResult result = agent.process(meeting);

        assertThat(result.toolCallCount()).isEqualTo(3);
        var taskCaptor = org.mockito.ArgumentCaptor.forClass(com.meetodo.task.Task.class);
        verify(tasks, times(3)).save(taskCaptor.capture());
        assertThat(taskCaptor.getAllValues()).extracting(com.meetodo.task.Task::getDueDate)
                .containsExactly(java.time.LocalDate.parse("2026-11-20"),
                        java.time.LocalDate.parse("2026-11-15"), java.time.LocalDate.parse("2026-11-12"));
    }

    @Test void modelPromptIncludesNounTitleRulesAndGoodAndBadExamples() {
        // 실제 모델 입력 시스템 메시지에 제목 규칙과 수정 예시가 전달된다.
        when(model.call(any(Prompt.class))).thenReturn(textResponse("완료"));
        agent.process(meetingWithId("민수는 API 명세를 정리한다.",91L));
        var prompts=org.mockito.ArgumentCaptor.forClass(Prompt.class); verify(model).call(prompts.capture());
        assertThat(prompts.getValue().getInstructions().getFirst().getText())
                .contains("명사형 업무 이름","좋은 예:","나쁜 예:","API 명세 정리","배포 로그를 정리한다.","INVALID_TITLE_FORMAT");
    }

    // 화자 정보 없는 음성의 1인칭 요청은 mock 모델의 NONE 결과로 미정 Task를 저장한다.
    @Test void firstPersonAudioTaskRemainsUnassignedForHumanConfirmation() {
        Meeting meeting=Meeting.create("제가 배포 로그를 정리할게요.",MeetingSource.AUDIO);
        ReflectionTestUtils.setField(meeting,"id",93L);
        when(tasks.save(any(com.meetodo.task.Task.class))).thenAnswer(call -> {
            var task=(com.meetodo.task.Task)call.getArgument(0); ReflectionTestUtils.setField(task,"id",33L); return task;
        });
        var call=new AssistantMessage.ToolCall("audio-task","function","createTask",
                "{\"title\":\"배포 로그 정리\",\"assignee\":\"unassigned:NONE\"}");
        var response=new ChatResponse(List.of(new Generation(AssistantMessage.builder().content("").toolCalls(List.of(call)).build())));
        when(model.call(any(Prompt.class))).thenReturn(response,textResponse("담당자를 지정해 주세요."));
        agent.process(meeting);
        var saved=org.mockito.ArgumentCaptor.forClass(com.meetodo.task.Task.class); verify(tasks).save(saved.capture());
        assertThat(saved.getValue().getAssignee()).isNull();
        var prompts=org.mockito.ArgumentCaptor.forClass(Prompt.class); verify(model,times(2)).call(prompts.capture());
        assertThat(prompts.getAllValues().getFirst().getInstructions().getFirst().getText())
                .contains("화자 구분 없이","unassigned:NONE","사람이 Slack에서 담당자를 지정");
    }

    // 텍스트 실험의 프롬프트에는 음성 전용 안내를 섞지 않는다.
    @Test void textMeetingDoesNotReceiveAudioOnlySpeakerRule() {
        when(model.call(any(Prompt.class))).thenReturn(textResponse("완료"));
        agent.process(meetingWithId("민수는 로그를 정리한다.",94L));
        var prompts=org.mockito.ArgumentCaptor.forClass(Prompt.class); verify(model).call(prompts.capture());
        assertThat(prompts.getValue().getInstructions().getFirst().getText()).doesNotContain("화자 구분 없이");
    }

    // 검증: 실제 음성 오답 ISO를 거절한 뒤 원표현 재시도는 서버 날짜로 저장한다.
    @Test void rejectsObservedVoiceIsoAndAcceptsOriginalExpressionOnRetry() {
        Meeting meeting = Meeting.create("백엔드 팀 지영 씨는 로그인 API 오류를 다음 주 금요일까지 수정해 주세요.", MeetingSource.AUDIO);
        ReflectionTestUtils.setField(meeting, "id", 95L);
        meeting.startProcessing(Instant.parse("2026-11-13T00:00:00Z"));
        when(tasks.save(any(com.meetodo.task.Task.class))).thenAnswer(call -> call.getArgument(0));
        when(model.call(any(Prompt.class))).thenReturn(dateToolResponse("2026-11-24"), dateToolResponse("다음 주 금요일"), textResponse("등록 완료"));

        AgentResult result = agent.process(meeting);

        assertThat(result.toolCallCount()).isEqualTo(2);
        assertThat(result.llmCallCount()).isEqualTo(3);
        assertThat(result.validationFailureCount()).isEqualTo(1);
        var saved = org.mockito.ArgumentCaptor.forClass(com.meetodo.task.Task.class);
        verify(tasks).save(saved.capture());
        assertThat(saved.getValue().getDueDate()).isEqualTo(java.time.LocalDate.of(2026, 11, 20));
        var recorded = org.mockito.ArgumentCaptor.forClass(ToolCallLog.class);
        verify(logs, times(2)).save(recorded.capture());
        assertThat(recorded.getAllValues()).extracting(ToolCallLog::getErrorCode).containsExactly("INVALID_DUE_DATE", null);
    }

    // 검증: 프롬프트와 도구가 같은 회의 시작일의 서울 날짜를 기준으로 삼는다.
    @Test void promptUsesMeetingStartedAtSeoulDate() {
        Meeting meeting = meetingWithId("내일 자료 정리", 96L);
        meeting.startProcessing(Instant.parse("2026-11-13T15:00:00Z"));
        when(model.call(any(Prompt.class))).thenReturn(textResponse("완료"));
        agent.process(meeting);
        var prompt = org.mockito.ArgumentCaptor.forClass(Prompt.class);
        verify(model).call(prompt.capture());
        assertThat(prompt.getValue().getInstructions().getFirst().getText()).contains("2026-11-14");
    }

    private ChatResponse dateToolResponse(String expression) {
        var call = new AssistantMessage.ToolCall("date-task", "function", "createTask",
                "{\"title\":\"로그인 API 오류 수정\",\"assignee\":\"unassigned:NONE\",\"dueDate\":\"" + expression + "\"}");
        return new ChatResponse(List.of(new Generation(AssistantMessage.builder().content("").toolCalls(List.of(call)).build())));
    }

    private Meeting meetingWithId(String text, long id) {
        Meeting meeting = Meeting.create(text, MeetingSource.API_TEXT);
        ReflectionTestUtils.setField(meeting, "id", id);
        return meeting;
    }

    // 기존 시나리오의 이름 기반 fixture를 현재 도구 계약으로 구성한다. 단계 A 회귀는 rawToolResponse를 사용한다.
    private ChatResponse toolResponse(String name, String arguments) {
        try {
            ObjectMapper mapper = new ObjectMapper();
            var object = (com.fasterxml.jackson.databind.node.ObjectNode) mapper.readTree(arguments);
            var calls = new java.util.ArrayList<AssistantMessage.ToolCall>();
            if (name.equals("findMember")) {
                fixtureLookups.add(object.path("name").asText());
                fixtureLastLookup = fixtureCallOrder + 1;
            }
            if (name.equals("findTasks")) {
                if (object.has("assignee")) {
                    object.put("assigneeName", object.path("assignee").asText());
                    object.remove("assignee");
                }
                if (!object.has("assigneeId")) object.putNull("assigneeId");
            }
            if (name.equals("createTask")) {
                String memberName = object.path("assigneeName").asText(object.path("assignee").asText(""));
                String token;
                if (object.path("unassignedBecauseAmbiguous").asBoolean(false)) token = "unassigned:AMBIGUOUS";
                else if (object.path("assigneeId").canConvertToLong()) token = "member:" + object.path("assigneeId").asLong();
                else if (memberName.isBlank()) token = "unassigned:NONE";
                else {
                    var candidates = members.findAllByGivenNameOrderByIdAsc(memberName);
                    if (candidates.isEmpty()) token = "unassigned:NOT_FOUND";
                    else {
                        Member member = candidates.getFirst();
                        if (member.getId() == null) ReflectionTestUtils.setField(member, "id", (long) memberName.hashCode() & 0x7fffffffL);
                        fixtureMembers.put(member.getId(), member);
                        token = "member:" + member.getId();
                    }
                }
                if (token.startsWith("member:") && fixtureLookups.add(memberName)) {
                    calls.add(new AssistantMessage.ToolCall("lookup", "function", "findMember",
                            mapper.writeValueAsString(java.util.Map.of("name", memberName))));
                }
                if (token.startsWith("unassigned:") && !token.equals("unassigned:NONE"))
                    object.put("lookupCallOrder", fixtureLastLookup);
                object.remove(java.util.List.of("assigneeId", "assigneeName", "unassignedBecauseAmbiguous"));
                object.put("assignee", token);
            }
            fixtureCallOrder += calls.size() + 1;
            calls.add(new AssistantMessage.ToolCall("call-" + name, "function", name, mapper.writeValueAsString(object)));
            return new ChatResponse(List.of(new Generation(AssistantMessage.builder().content("").toolCalls(calls).build())));
        } catch (Exception exception) { throw new IllegalArgumentException(exception); }
    }

    private ChatResponse textResponse(String text) {
        return new ChatResponse(List.of(new Generation(new AssistantMessage(text))));
    }
}
