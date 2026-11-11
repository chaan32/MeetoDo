package com.meetodo.meeting;

import java.util.List;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.meetodo.member.Member;
import com.meetodo.member.MemberRepository;
import com.meetodo.task.TaskRepository;
import com.meetodo.task.TaskMention;
import com.meetodo.task.TaskMentionRepository;
import com.meetodo.task.Task;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@org.springframework.context.annotation.Import(MeetingAgentApiIntegrationTests.FixedClockConfiguration.class)
@ActiveProfiles({"local", "openai"})
@TestPropertySource(properties = {
        "spring.ai.openai.api-key=integration-test-key",
        "NOTIFIER_TYPE=none",
        "spring.datasource.url=jdbc:h2:mem:meeting-agent-api-test",
        "spring.jpa.hibernate.ddl-auto=create-drop"
})
class MeetingAgentApiIntegrationTests {

    @org.springframework.boot.test.context.TestConfiguration(proxyBeanMethods = false)
    static class FixedClockConfiguration {
        @org.springframework.context.annotation.Bean
        @org.springframework.context.annotation.Primary
        java.time.Clock stageAClock() {
            return java.time.Clock.fixed(java.time.Instant.parse("2026-11-11T00:00:00Z"), java.time.ZoneId.of("Asia/Seoul"));
        }
    }

    @Autowired MockMvc mvc;
    @Autowired MeetingRepository meetings;
    @Autowired ToolCallLogRepository logs;
    @Autowired TaskRepository tasks;
    @Autowired TaskMentionRepository mentions;
    @Autowired com.meetodo.task.TaskMentionService mentionService;
    @Autowired MemberRepository members;
    @MockitoBean ChatModel model;

    final java.util.Set<String> fixtureLookups = new java.util.HashSet<>();

    @BeforeEach
    void cleanAndSeed() {
        fixtureLookups.clear();
        logs.deleteAll();
        mentions.deleteAll();
        tasks.deleteAll();
        meetings.deleteAll();
        members.deleteAll();
        members.save(new Member("민수", "U-DEV-MINSU"));
    }

    // 검증: sendsToolResultsBackThroughSpringAiAndPersistsTheCompletedWorkflow 시나리오의 기대 동작을 확인한다.
    @Test
    void sendsToolResultsBackThroughSpringAiAndPersistsTheCompletedWorkflow() throws Exception {
        ChatResponse[] responses = new ChatResponse[]{
                toolResponse("findMember", "{\"name\":\"민수\"}"),
                toolResponse("findTasks", "{\"assignee\":\"민수\",\"title\":\"로그인 API 수정\"}"),
                toolResponse("createTask", "{\"title\":\"로그인 API 수정\",\"assignee\":\"민수\",\"dueDate\":\"2026-11-20\"}"),
                textResponse("로그인 API 수정 작업을 등록했습니다.")};
        when(model.call(any(org.springframework.ai.chat.prompt.Prompt.class))).thenReturn(responses[0], java.util.Arrays.copyOfRange(responses, 1, responses.length));

        String response = mvc.perform(post("/api/meetings")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"rawText\":\"민수는 로그인 API 수정을 다음 주 금요일까지 한다.\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("DONE"))
                .andExpect(jsonPath("$.summary").value("로그인 API 수정 작업을 등록했습니다."))
                .andExpect(jsonPath("$.tasks[0].title").value("로그인 API 수정"))
                .andExpect(jsonPath("$.tasks[0].assigneeName").value("민수"))
                .andExpect(jsonPath("$.tasks[0].assigneeTeam").value("미지정"))
                .andExpect(jsonPath("$.tasks[0].dueDate").value("2026-11-20"))
                .andExpect(jsonPath("$.tasks[0].resultType").value("CREATED"))
                .andExpect(jsonPath("$.tasks[0].mentionCount").value(1))
                .andExpect(jsonPath("$.toolCallCount").value(3))
                .andReturn().getResponse().getContentAsString();
        long meetingId = new ObjectMapper().readTree(response).get("meetingId").asLong();

        mvc.perform(get("/api/meetings/{id}/logs", meetingId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].toolName").value("findMember"))
                .andExpect(jsonPath("$[0].success").value(true))
                .andExpect(jsonPath("$[1].toolName").value("findTasks"))
                .andExpect(jsonPath("$[2].toolName").value("createTask"))
                .andExpect(jsonPath("$[2].latencyMs").isNumber());
        assertThat(tasks.findAllByMeeting_IdOrderByCreatedAtDesc(meetingId)).hasSize(1);
        assertThat(meetings.findById(meetingId).orElseThrow().getStatus()).isEqualTo(MeetingStatus.DONE);
    }

    // 검증: 회의 API 결과에도 다른 회의에서 재사용한 기존 TODO를 EXISTING으로 포함한다.
    @Test
    void returnsCrossMeetingTodoAsExistingWithoutCreatingAnotherTask() throws Exception {
        Member minsu = members.findAllByNameOrderByIdAsc("민수").getFirst();
        Meeting priorMeeting = Meeting.create("이전 회의", MeetingSource.API_TEXT);
        priorMeeting.startProcessing(java.time.Instant.parse("2026-11-11T00:00:00Z"));
        priorMeeting.complete("이전 회의 처리 완료", java.time.Instant.parse("2026-11-11T00:01:00Z"), 1, 1, 0);
        priorMeeting = meetings.saveAndFlush(priorMeeting);
        Task existing = tasks.saveAndFlush(Task.create("배포 설정 확인", minsu, null, priorMeeting));
        tasks.saveAndFlush(Task.create("별도 회의 작업", minsu, null, priorMeeting));
        ChatResponse[] responses = new ChatResponse[]{
                toolResponse("createTask", "{\"title\":\"배포 설정 확인\",\"assignee\":\"민수\",\"dueDate\":\"\"}"),
                textResponse("기존 미완료 작업을 재사용했습니다.")};
        when(model.call(any(org.springframework.ai.chat.prompt.Prompt.class))).thenReturn(responses[0], java.util.Arrays.copyOfRange(responses, 1, responses.length));

        mvc.perform(post("/api/meetings").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"rawText\":\"새 회의에서 배포 설정 확인을 논의했다\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.tasks[0].taskId").value(existing.getId()))
                .andExpect(jsonPath("$.tasks[0].title").value("배포 설정 확인"))
                .andExpect(jsonPath("$.tasks[0].resultType").value("EXISTING"))
                .andExpect(jsonPath("$.duplicateOfMeetingId").doesNotExist());
        assertThat(tasks.count()).isEqualTo(2);
    }

    // 검증: 모든 작업이 한 이전 회의의 TODO와 같아도 새 Meeting·로그를 보존하고 EXISTING Task를 반환한다.
    @Test
    void savesDuplicateMeetingAndReturnsEveryExistingTaskWhenAllTasksMatchOnePreviousMeeting() throws Exception {
        Member minsu = members.findAllByNameOrderByIdAsc("민수").getFirst();
        Member jiyoung = members.save(new Member("지영", "U-DEV-JIYOUNG"));
        Member haechan = members.save(new Member("해찬", "U-DEV-HAECHAN"));
        Meeting original = Meeting.create("원본 회의록", MeetingSource.API_TEXT);
        original.startProcessing(java.time.Instant.parse("2026-11-11T00:00:00Z"));
        original.complete("세 작업을 논의했습니다.", java.time.Instant.parse("2026-11-11T00:01:00Z"), 4, 9, 0);
        original = meetings.saveAndFlush(original);
        long originalId = original.getId();
        Task login = tasks.saveAndFlush(Task.create("로그인 API 오류 처리", minsu, null, original));
        Task payment = tasks.saveAndFlush(Task.create("결제 실패 재현 테스트", jiyoung, null, original));
        Task checklist = tasks.saveAndFlush(Task.create("배포 체크리스트", haechan, null, original));
        mentions.saveAllAndFlush(List.of(
                TaskMention.create(login, original, java.time.Instant.parse("2026-11-11T00:00:30Z")),
                TaskMention.create(payment, original, java.time.Instant.parse("2026-11-11T00:00:30Z")),
                TaskMention.create(checklist, original, java.time.Instant.parse("2026-11-11T00:00:30Z"))));
        ChatResponse[] responses = new ChatResponse[]{
                toolResponse("findMember", "{\"name\":\"민수\"}"),
                toolResponse("findTasks", "{\"assignee\":\"민수\",\"title\":\"로그인 API 오류 처리\"}"),
                toolResponse("findMember", "{\"name\":\"지영\"}"),
                toolResponse("findTasks", "{\"assignee\":\"지영\",\"title\":\"결제 실패 재현 테스트\"}"),
                toolResponse("findMember", "{\"name\":\"해찬\"}"),
                toolResponse("findTasks", "{\"assignee\":\"해찬\",\"title\":\"배포 체크리스트\"}"),
                textResponse("모든 미완료 작업은 원본 회의의 작업과 같아 재사용했습니다.")};
        when(model.call(any(org.springframework.ai.chat.prompt.Prompt.class))).thenReturn(responses[0], java.util.Arrays.copyOfRange(responses, 1, responses.length));

        String response = mvc.perform(post("/api/meetings").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"rawText\":\"민수 로그인 API 오류 처리. 지영 결제 실패 테스트. 해찬 배포 체크리스트. 끝에 잡담을 덧붙임\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.tasks", org.hamcrest.Matchers.hasSize(3)))
                .andExpect(jsonPath("$.tasks[0].resultType").value("EXISTING"))
                .andExpect(jsonPath("$.tasks[1].resultType").value("EXISTING"))
                .andExpect(jsonPath("$.tasks[2].resultType").value("EXISTING"))
                .andExpect(jsonPath("$.tasks[0].mentionCount").value(1))
                .andExpect(jsonPath("$.duplicateOfMeetingId").value(originalId))
                .andReturn().getResponse().getContentAsString();
        long duplicateMeetingId = new ObjectMapper().readTree(response).get("meetingId").asLong();
        assertThat(duplicateMeetingId).isNotEqualTo(originalId);
        assertThat(new ObjectMapper().readTree(response).get("duplicateOfMeetingId").asLong()).isEqualTo(originalId);

        assertThat(meetings.count()).isEqualTo(2);
        assertThat(tasks.count()).isEqualTo(3);
        assertThat(mentions.count()).isEqualTo(6);
        assertThat(logs.count()).isEqualTo(6);
        assertThat(logs.findAllByMeetingIdOrderByCallOrderAsc(duplicateMeetingId)).hasSize(6);
    }

    // 검증: 도구 로그를 파싱할 수 없어도 저장된 TaskMention으로 EXISTING Task를 응답한다.
    @Test
    void includesExistingTaskFromMentionRecordWhenToolResultCannotBeParsed() throws Exception {
        Member minsu = members.findAllByNameOrderByIdAsc("민수").getFirst();
        Meeting source = Meeting.create("원본 회의", MeetingSource.API_TEXT);
        source.startProcessing(java.time.Instant.parse("2026-11-11T00:00:00Z"));
        source.complete("원본 처리", java.time.Instant.parse("2026-11-11T00:01:00Z"), 1, 1, 0);
        source = meetings.saveAndFlush(source);
        Task existing = tasks.saveAndFlush(Task.create("로그인 오류 수정", minsu, null, source));
        Meeting repeated = Meeting.create("반복 회의", MeetingSource.API_TEXT);
        repeated.startProcessing(java.time.Instant.parse("2026-11-11T00:02:00Z"));
        repeated.complete("기존 작업을 다시 언급", java.time.Instant.parse("2026-11-11T00:03:00Z"), 1, 1, 0);
        repeated = meetings.saveAndFlush(repeated);
        mentions.saveAndFlush(TaskMention.create(existing, repeated, java.time.Instant.parse("2026-11-11T00:02:30Z")));
        logs.saveAndFlush(ToolCallLog.success(repeated, "findTasks", "{}", "malformed", 0, 1));

        mvc.perform(get("/api/meetings/{id}", repeated.getId()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.tasks", org.hamcrest.Matchers.hasSize(1)))
                .andExpect(jsonPath("$.tasks[0].taskId").value(existing.getId()))
                .andExpect(jsonPath("$.tasks[0].resultType").value("EXISTING"))
                .andExpect(jsonPath("$.tasks[0].mentionCount").value(1));
    }

    // 검증: 한 회의에서 같은 Task를 여러 번 처리해도 TaskMention은 한 건만 저장한다.
    @Test
    void recordsOneMentionWhenTheSameTaskIsCreatedTwiceInOneMeeting() throws Exception {
        ChatResponse[] responses = new ChatResponse[]{
                toolResponse("createTask", "{\"title\":\"로그인 오류 수정\",\"assignee\":\"민수\",\"dueDate\":\"\"}"),
                toolResponse("createTask", "{\"title\":\"로그인 오류 수정\",\"assignee\":\"민수\",\"dueDate\":\"\"}"),
                textResponse("로그인 오류 수정은 한 번의 회의에서 언급됐습니다.")};
        when(model.call(any(org.springframework.ai.chat.prompt.Prompt.class))).thenReturn(responses[0], java.util.Arrays.copyOfRange(responses, 1, responses.length));

        mvc.perform(post("/api/meetings").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"rawText\":\"민수는 로그인 오류를 수정하고 재확인한다.\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.tasks", org.hamcrest.Matchers.hasSize(1)))
                .andExpect(jsonPath("$.tasks[0].resultType").value("CREATED"))
                .andExpect(jsonPath("$.tasks[0].mentionCount").value(1));

        assertThat(tasks.count()).isEqualTo(1);
        assertThat(mentions.count()).isEqualTo(1);
    }

    // 검증: 기존 Task가 다른 회의에서 재언급되면 mentionCount가 2가 된다.
    @Test
    void incrementsMentionCountWhenExistingTaskIsMentionedInAnotherNonDuplicateMeeting() throws Exception {
        Member minsu = members.findAllByNameOrderByIdAsc("민수").getFirst();
        Meeting source = Meeting.create("원본 회의", MeetingSource.API_TEXT);
        source.startProcessing(java.time.Instant.parse("2026-11-11T00:00:00Z"));
        source.complete("두 작업", java.time.Instant.parse("2026-11-11T00:01:00Z"), 1, 1, 0);
        source = meetings.saveAndFlush(source);
        Task existing = tasks.saveAndFlush(Task.create("로그인 오류 수정", minsu, null, source));
        Task unrelated = tasks.saveAndFlush(Task.create("배포 계획 공유", minsu, null, source));
        mentions.saveAndFlush(TaskMention.create(existing, source, java.time.Instant.parse("2026-11-11T00:00:30Z")));
        mentions.saveAndFlush(TaskMention.create(unrelated, source, java.time.Instant.parse("2026-11-11T00:00:30Z")));
        ChatResponse[] responses = new ChatResponse[]{
                toolResponse("createTask", "{\"title\":\"로그인 오류 수정\",\"assignee\":\"민수\",\"dueDate\":\"\"}"),
                textResponse("로그인 오류 수정 작업을 다시 확인했습니다.")};
        when(model.call(any(org.springframework.ai.chat.prompt.Prompt.class))).thenReturn(responses[0], java.util.Arrays.copyOfRange(responses, 1, responses.length));

        mvc.perform(post("/api/meetings").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"rawText\":\"다음 회의에서 민수는 로그인 오류 수정을 다시 확인한다.\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.duplicateOfMeetingId").doesNotExist())
                .andExpect(jsonPath("$.tasks[0].resultType").value("EXISTING"))
                .andExpect(jsonPath("$.tasks[0].mentionCount").value(2));
        String taskList = mvc.perform(get("/api/tasks"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        var taskListJson = new ObjectMapper().readTree(taskList);
        var taskMentionCount = java.util.stream.StreamSupport.stream(taskListJson.spliterator(), false)
                .filter(task -> "로그인 오류 수정".equals(task.path("title").asText()))
                .map(task -> task.path("mentionCount")).findFirst().orElseThrow();
        assertThat(taskMentionCount.asLong()).isEqualTo(2L);
        assertThat(mentions.count()).isEqualTo(3);
    }

    // 검증: 서로 다른 원본 회의의 작업이 함께 재사용되면 중복 회의로 표시하지 않고 둘 다 센다.
    @Test
    void doesNotMarkMeetingDuplicateWhenReusedTasksComeFromDifferentMeetings() throws Exception {
        Member minsu = members.findAllByNameOrderByIdAsc("민수").getFirst();
        Meeting first = completedMeeting("원본 A");
        Meeting second = completedMeeting("원본 B");
        Task taskA = tasks.saveAndFlush(Task.create("로그인 오류 수정", minsu, null, first));
        Task taskB = tasks.saveAndFlush(Task.create("배포 계획 공유", minsu, null, second));
        mentions.saveAndFlush(TaskMention.create(taskA, first, java.time.Instant.parse("2026-11-11T00:00:30Z")));
        mentions.saveAndFlush(TaskMention.create(taskB, second, java.time.Instant.parse("2026-11-11T00:00:30Z")));
        ChatResponse[] responses = new ChatResponse[]{
                toolResponse("createTask", "{\"title\":\"로그인 오류 수정\",\"assignee\":\"민수\",\"dueDate\":\"\"}"),
                toolResponse("createTask", "{\"title\":\"배포 계획 공유\",\"assignee\":\"민수\",\"dueDate\":\"\"}"),
                textResponse("두 기존 작업을 확인했습니다.")};
        when(model.call(any(org.springframework.ai.chat.prompt.Prompt.class))).thenReturn(responses[0], java.util.Arrays.copyOfRange(responses, 1, responses.length));

        mvc.perform(post("/api/meetings").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"rawText\":\"로그인 오류와 배포 계획을 다시 점검한다.\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.duplicateOfMeetingId").doesNotExist())
                .andExpect(jsonPath("$.tasks", org.hamcrest.Matchers.hasSize(2)))
                .andExpect(jsonPath("$.tasks[0].mentionCount").value(2))
                .andExpect(jsonPath("$.tasks[1].mentionCount").value(2));
    }

    // 검증: 모의 모델이 백엔드팀 지영의 findMember 후보 ID를 고르면 해당 팀원에게 등록된다.
    @Test
    void assignsDuplicateNameToTheTeamSelectedByMockLlm() throws Exception {
        Member backend = members.save(new Member("지영", "백엔드팀", "U-BACKEND-JIYOUNG"));
        members.save(new Member("지영", "디자인팀", "U-DESIGN-JIYOUNG"));
        ChatResponse[] responses = new ChatResponse[]{
                toolResponse("findMember", "{\"name\":\"지영\",\"team\":\"백엔드팀\"}"),
                toolResponse("findTasks", "{\"assigneeId\":" + backend.getId() + ",\"assigneeName\":\"지영\",\"title\":\"API 오류 수정\"}"),
                toolResponse("createTask", "{\"title\":\"API 오류 수정\",\"assigneeId\":" + backend.getId()
                        + ",\"assigneeName\":\"지영\",\"dueDate\":\"\",\"unassignedBecauseAmbiguous\":false}"),
                textResponse("백엔드팀 지영에게 등록했습니다.")};
        when(model.call(any(org.springframework.ai.chat.prompt.Prompt.class))).thenReturn(responses[0], java.util.Arrays.copyOfRange(responses, 1, responses.length));

        mvc.perform(post("/api/meetings").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"rawText\":\"백엔드팀 지영은 API 오류를 수정한다.\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.tasks[0].assigneeName").value("지영"))
                .andExpect(jsonPath("$.tasks[0].assigneeTeam").value("백엔드팀"));
    }

    // 검증: 모호한 담당자를 모델이 미정으로 선택하면 경고와 미정 건수가 응답에 포함된다.
    @Test
    void leavesAmbiguousMemberUnassignedAndReturnsWarning() throws Exception {
        members.save(new Member("지영", "백엔드팀", "U-BACKEND-JIYOUNG"));
        members.save(new Member("지영", "디자인팀", "U-DESIGN-JIYOUNG"));
        ChatResponse[] responses = new ChatResponse[]{
                toolResponse("findMember", "{\"name\":\"지영\"}"),
                toolResponse("findTasks", "{\"assigneeId\":null,\"assigneeName\":\"\",\"title\":\"회의실 예약\"}"),
                toolResponse("createTask", "{\"title\":\"회의실 예약\",\"assigneeId\":null,\"assigneeName\":\"\",\"dueDate\":\"\",\"unassignedBecauseAmbiguous\":true}"),
                textResponse("담당자 미정으로 등록했습니다. 동명이인 확인 필요.")};
        when(model.call(any(org.springframework.ai.chat.prompt.Prompt.class))).thenReturn(responses[0], java.util.Arrays.copyOfRange(responses, 1, responses.length));

        mvc.perform(post("/api/meetings").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"rawText\":\"지영은 회의실을 예약한다.\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.unassignedCount").value(1))
                .andExpect(jsonPath("$.tasks[0].assigneeName").doesNotExist())
                .andExpect(jsonPath("$.tasks[0].warning").value("동명이인 확인 필요"));
    }

    // 검증: findMember 후보가 아닌 다른 팀원의 ID로 등록하려 하면 도구가 거절하고 Task를 저장하지 않는다.
    @Test
    void rejectsMemberIdOutsideTheNamedCandidateList() throws Exception {
        Member backend = members.save(new Member("지영", "백엔드팀", "U-BACKEND-JIYOUNG"));
        Member other = members.save(new Member("민수", "백엔드팀", "U-MINSU-OTHER"));
        ChatResponse[] responses = new ChatResponse[]{
                toolResponse("createTask", "{\"title\":\"API 확인\",\"assigneeId\":" + other.getId()
                        + ",\"assigneeName\":\"지영\",\"dueDate\":\"\",\"unassignedBecauseAmbiguous\":false}"),
                textResponse("담당자를 검증하지 못해 등록하지 않았습니다.")};
        when(model.call(any(org.springframework.ai.chat.prompt.Prompt.class))).thenReturn(responses[0], java.util.Arrays.copyOfRange(responses, 1, responses.length));

        String response = mvc.perform(post("/api/meetings").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"rawText\":\"지영은 API를 확인한다.\"}"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        long meetingId = new ObjectMapper().readTree(response).get("meetingId").asLong();

        assertThat(tasks.count()).isZero();
        assertThat(logs.findAllByMeetingIdOrderByCallOrderAsc(meetingId))
                .anySatisfy(log -> assertThat(log.getErrorCode()).isEqualTo("INVALID_ASSIGNEE"));
    }

    // 검증: 정확히 같은 회의록을 재전송해도 새 Meeting과 추가 Mention을 만들지 않는다.
    @Test
    void doesNotAddMeetingOrMentionWhenExactMeetingBodyIsResubmitted() throws Exception {
        ChatResponse[] responses = new ChatResponse[]{
                toolResponse("createTask", "{\"title\":\"API 오류 수정\",\"assignee\":\"민수\",\"dueDate\":\"\"}"),
                textResponse("API 오류 수정 작업을 등록했습니다.")};
        when(model.call(any(org.springframework.ai.chat.prompt.Prompt.class))).thenReturn(responses[0], java.util.Arrays.copyOfRange(responses, 1, responses.length));
        String body = "{\"rawText\":\"민수는 API 오류를 수정한다.\"}";

        String firstResponse = mvc.perform(post("/api/meetings").contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        long firstMeetingId = new ObjectMapper().readTree(firstResponse).get("meetingId").asLong();
        mvc.perform(post("/api/meetings").contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.meetingId").value(firstMeetingId))
                .andExpect(jsonPath("$.tasks[0].mentionCount").value(1));

        assertThat(meetings.count()).isEqualTo(1);
        assertThat(mentions.count()).isEqualTo(1);
        org.mockito.Mockito.verify(model, org.mockito.Mockito.times(2))
                .call(any(org.springframework.ai.chat.prompt.Prompt.class));
    }

    // 검증: 같은 Task와 Meeting의 동시 Mention 저장 경쟁도 유니크 제약으로 한 건만 남긴다.
    @Test
    void storesOneMentionWhenConcurrentCallsRaceForSameTaskAndMeeting() throws Exception {
        Member minsu = members.findAllByNameOrderByIdAsc("민수").getFirst();
        Meeting meeting = meetings.saveAndFlush(Meeting.create("동시 언급", MeetingSource.API_TEXT));
        Task task = tasks.saveAndFlush(Task.create("설계 검토", minsu, null, meeting));
        java.util.concurrent.CountDownLatch start = new java.util.concurrent.CountDownLatch(1);
        var first = java.util.concurrent.CompletableFuture.runAsync(() -> recordAfter(start, task, meeting));
        var second = java.util.concurrent.CompletableFuture.runAsync(() -> recordAfter(start, task, meeting));
        start.countDown();
        first.join();
        second.join();

        assertThat(mentions.count()).isEqualTo(1);
        assertThat(mentionService.counts(List.of(task.getId())).get(task.getId())).isEqualTo(1L);
    }

    // 검증: 실제 로그의 모순 인자와 빈 ID 재시도는 미정 Task를 조용히 저장하지 않는다.
    @Test
    void rejectsActualContradictoryArgumentsWithoutSilentlySavingUnassignedTask() throws Exception {
        Member backend = members.save(new Member("지영", "백엔드팀", "U-BACKEND"));
        members.save(new Member("지영", "디자인팀", "U-DESIGN"));
        ChatResponse[] responses = new ChatResponse[]{
                rawToolResponse("findMember", "{\"name\":\"지영\"}"),
                rawToolResponse("createTask", "{\"title\":\"로그인 API 오류 처리\",\"assigneeId\":" + backend.getId()
                        + ",\"assigneeName\":\"지영\",\"unassignedBecauseAmbiguous\":true,\"dueDate\":\"2026-11-20\"}"),
                rawToolResponse("createTask", "{\"title\":\"로그인 API 오류 처리\",\"assigneeId\":\"\",\"assigneeName\":\"\",\"unassignedBecauseAmbiguous\":true,\"dueDate\":\"2026-11-20\"}"),
                textResponse("담당자 입력 확인이 필요합니다.")};
        when(model.call(any(org.springframework.ai.chat.prompt.Prompt.class))).thenReturn(responses[0], java.util.Arrays.copyOfRange(responses, 1, responses.length));
        mvc.perform(post("/api/meetings").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"rawText\":\"백엔드팀 지영은 로그인 API 오류 처리를 2026-11-20까지 수정한다.\"}"))
                .andExpect(status().isOk());
        assertThat(tasks.count()).isZero();
        assertThat(logs.findAll()).filteredOn(log -> "INVALID_ASSIGNEE".equals(log.getErrorCode()))
                .allSatisfy(log -> assertThat(log.getErrorMessage()).contains("배정과 미정 중 하나만 선택"));
    }

    // 검증: 처음부터 팀을 명시한 조회는 단일 후보를 반환하고 재시도 없이 등록한다.
    @Test
    void registersCorrectMemberAfterFirstLookupWithTeam() throws Exception {
        Member backend = seedJiyoung();
        ChatResponse[] responses = new ChatResponse[]{
                rawToolResponse("findMember", "{\"name\":\"지영\",\"team\":\"백엔드팀\"}"),
                rawToolResponse("findTasks", "{\"assigneeId\":" + backend.getId() + ",\"assigneeName\":\"지영\",\"title\":\"API 수정\"}"),
                rawCreate("member:" + backend.getId()), textResponse("등록 완료")};
        when(model.call(any(org.springframework.ai.chat.prompt.Prompt.class))).thenReturn(responses[0], java.util.Arrays.copyOfRange(responses, 1, responses.length));
        submitStageA("백엔드팀 지영은 API를 수정한다.")
                .andExpect(jsonPath("$.tasks[0].assigneeTeam").value("백엔드팀"))
                .andExpect(jsonPath("$.validationFailureCount").value(0))
                .andExpect(jsonPath("$.toolCallCount").value(3));
        ToolCallLog lookup = logs.findAll().stream().filter(l -> l.getToolName().equals("findMember")).findFirst().orElseThrow();
        assertThat(new ObjectMapper().readTree(new ObjectMapper().readTree(lookup.getResult()).path("data").asText())).hasSize(1);
    }

    // 검증: 팀 없는 조회의 중립 안내 후 team으로 재조회하면 올바른 후보에게 배정한다.
    @Test
    void registersAfterRetryingMemberLookupWithTeam() throws Exception {
        Member backend = seedJiyoung();
        ChatResponse[] responses = new ChatResponse[]{
                rawToolResponse("findMember", "{\"name\":\"지영\"}"),
                rawToolResponse("findMember", "{\"name\":\"지영\",\"team\":\"백엔드팀\"}"),
                rawToolResponse("findTasks", "{\"assigneeId\":" + backend.getId() + ",\"assigneeName\":\"지영\",\"title\":\"API 수정\"}"),
                rawCreate("member:" + backend.getId()), textResponse("등록 완료")};
        when(model.call(any(org.springframework.ai.chat.prompt.Prompt.class))).thenReturn(responses[0], java.util.Arrays.copyOfRange(responses, 1, responses.length));
        submitStageA("백엔드팀 지영 담당 API 수정")
                .andExpect(jsonPath("$.tasks[0].assigneeTeam").value("백엔드팀"));
        assertThat(logs.findAll().stream().filter(l -> l.getToolName().equals("findMember")).findFirst().orElseThrow().getResult())
                .contains("team=백엔드팀으로 재조회하세요");
    }

    // 검증: 팀 문맥이 없는 동명이인은 AMBIGUOUS 사유와 경고로 미정 등록한다.
    @Test
    void reportsAmbiguousReasonForNameWithoutTeamContext() throws Exception {
        seedJiyoung();
        ChatResponse[] responses = new ChatResponse[]{
                rawToolResponse("findMember", "{\"name\":\"지영\"}"), unassignedLookup(),
                rawCreate("unassigned:AMBIGUOUS"), textResponse("동명이인 확인 필요")};
        when(model.call(any(org.springframework.ai.chat.prompt.Prompt.class))).thenReturn(responses[0], java.util.Arrays.copyOfRange(responses, 1, responses.length));
        submitStageA("지영은 API를 수정한다.")
                .andExpect(jsonPath("$.tasks[0].unassignedReason").value("AMBIGUOUS"))
                .andExpect(jsonPath("$.tasks[0].warning").value("동명이인 확인 필요"));
    }

    // 검증: 등록되지 않은 이름은 NOT_FOUND 사유와 지정된 경고를 응답한다.
    @Test
    void reportsNotFoundReasonForUnregisteredName() throws Exception {
        ChatResponse[] responses = new ChatResponse[]{
                rawToolResponse("findMember", "{\"name\":\"없는 사람\"}"), unassignedLookup(),
                rawCreate("unassigned:NOT_FOUND"), textResponse("등록되지 않은 담당자")};
        when(model.call(any(org.springframework.ai.chat.prompt.Prompt.class))).thenReturn(responses[0], java.util.Arrays.copyOfRange(responses, 1, responses.length));
        submitStageA("없는 사람이 API 수정 담당")
                .andExpect(jsonPath("$.tasks[0].unassignedReason").value("NOT_FOUND"))
                .andExpect(jsonPath("$.tasks[0].warning").value("등록되지 않은 담당자"));
    }

    // 검증: 담당자 언급이 없으면 NONE 사유로 등록하고 동명이인·미등록 경고를 붙이지 않는다.
    @Test
    void reportsNoneReasonWhenMeetingHasNoAssignee() throws Exception {
        ChatResponse[] responses = new ChatResponse[]{
                unassignedLookup(), rawCreate("unassigned:NONE"), textResponse("담당자 미정 작업 등록")};
        when(model.call(any(org.springframework.ai.chat.prompt.Prompt.class))).thenReturn(responses[0], java.util.Arrays.copyOfRange(responses, 1, responses.length));
        submitStageA("API 수정이 필요하다.")
                .andExpect(jsonPath("$.tasks[0].unassignedReason").value("NONE"))
                .andExpect(jsonPath("$.tasks[0].warning").doesNotExist());
    }

    // 검증: 존재하지 않는 팀은 다른 팀원에게 자동 배정하지 않고 찾지 못함으로 기록한다.
    @Test
    void reportsUnknownTeamWithoutAssigningAnotherTeamMember() throws Exception {
        seedJiyoung();
        ChatResponse[] responses = new ChatResponse[]{
                rawToolResponse("findMember", "{\"name\":\"지영\",\"team\":\"없는팀\"}"),
                textResponse("팀 확인 필요")};
        when(model.call(any(org.springframework.ai.chat.prompt.Prompt.class))).thenReturn(responses[0], java.util.Arrays.copyOfRange(responses, 1, responses.length));
        submitStageA("없는팀 지영은 API 수정")
                .andExpect(jsonPath("$.tasks", org.hamcrest.Matchers.hasSize(0)));
        assertThat(logs.findAll()).anySatisfy(l -> assertThat(l.getErrorCode()).isEqualTo("MEMBER_TEAM_NOT_FOUND"));
    }

    // 검증: findTasks에서 미정 기존 작업을 재사용해도 현재 회의의 미정 사유와 경고를 유지한다.
    @Test
    void keepsUnassignedReasonWhenFindTasksReusesExistingTaskWithoutCreate() throws Exception {
        seedJiyoung();
        tasks.saveAndFlush(Task.create("API 수정", null, null, completedMeeting("원본 미정 작업")));
        ChatResponse[] responses = {
                rawToolResponse("findMember", "{\"name\":\"지영\"}"),
                rawToolResponse("findTasks", "{\"assigneeId\":null,\"assigneeName\":\"\",\"title\":\"API 수정\",\"unassignedReason\":\"AMBIGUOUS\"}"),
                textResponse("기존 작업 재사용. 동명이인 확인 필요")};
        when(model.call(any(org.springframework.ai.chat.prompt.Prompt.class))).thenReturn(responses[0], java.util.Arrays.copyOfRange(responses, 1, responses.length));
        submitStageA("지영 API 수정 재확인")
                .andExpect(jsonPath("$.tasks[0].resultType").value("EXISTING"))
                .andExpect(jsonPath("$.tasks[0].unassignedReason").value("AMBIGUOUS"))
                .andExpect(jsonPath("$.tasks[0].warning").value("동명이인 확인 필요"));
    }

    // 검증: 실제 호출 순서인 팀 불일치→빈 팀 조회→member ID 배정으로 오배정하지 않는다.
    @Test
    void replaysTeamMismatchThenEmptyTeamThenMemberWithoutAssigning() throws Exception {
        Member haechan = members.save(new Member("해찬", "백엔드팀", "U-HAECHAN"));
        ChatResponse[] responses = {
                rawToolResponse("findMember", "{\"name\":\"해찬\",\"team\":\"프론트엔드팀\"}"),
                rawToolResponse("findMember", "{\"name\":\"해찬\",\"team\":\"\"}"),
                rawCreate("member:" + haechan.getId()), textResponse("담당자 확인 필요")};
        when(model.call(any(org.springframework.ai.chat.prompt.Prompt.class))).thenReturn(responses[0], java.util.Arrays.copyOfRange(responses, 1, responses.length));
        submitStageA("프론트엔드팀 해찬은 11-12까지 회의실을 예약한다.")
                .andExpect(jsonPath("$.tasks", org.hamcrest.Matchers.hasSize(0)));
        assertThat(tasks.count()).isZero();
    }

    // 검증: team 필드를 생략한 재조회가 성공해도 앞선 불일치를 지우지 못하며 미정 경고로 복구한다.
    @Test
    void blocksAssignmentAfterTeamMismatchAndRegistersWithWarning() throws Exception {
        Member haechan = members.save(new Member("해찬", "백엔드팀", "U-HAECHAN"));
        ChatResponse[] responses = {
                rawToolResponse("findMember", "{\"name\":\"해찬\",\"team\":\"프론트엔드팀\"}"),
                rawToolResponse("findMember", "{\"name\":\"해찬\"}"),
                rawCreate("member:" + haechan.getId()),
                rawToolResponse("findTasks", "{\"assigneeId\":null,\"assigneeName\":\"\",\"title\":\"API 수정\",\"unassignedReason\":\"TEAM_MISMATCH\"}"),
                rawCreate("unassigned:TEAM_MISMATCH"), textResponse("팀 불일치 확인 필요")};
        when(model.call(any(org.springframework.ai.chat.prompt.Prompt.class))).thenReturn(responses[0], java.util.Arrays.copyOfRange(responses, 1, responses.length));
        String body = submitStageA("프론트엔드팀 해찬은 API 수정 담당")
                .andExpect(jsonPath("$.tasks[0].assigneeName").doesNotExist())
                .andExpect(jsonPath("$.tasks[0].unassignedReason").value("TEAM_MISMATCH"))
                .andExpect(jsonPath("$.tasks[0].warning").value("팀 불일치 확인 필요"))
                .andReturn().getResponse().getContentAsString();
        long id = new ObjectMapper().readTree(body).path("meetingId").asLong();
        mvc.perform(get("/api/meetings/{id}", id))
                .andExpect(jsonPath("$.tasks[0].warning").value("팀 불일치 확인 필요"));
        var history = logs.findAllByMeetingIdOrderByCallOrderAsc(id);
        assertThat(history).anySatisfy(l -> assertThat(l.getErrorCode()).isEqualTo("INVALID_ASSIGNEE_TEAM"));
        ToolCallLog failed = history.getFirst();
        assertThat(new ObjectMapper().readTree(new ObjectMapper().readTree(failed.getResult()).path("data").asText()).get(0).path("team").asText())
                .isEqualTo("백엔드팀");
    }

    // 검증: 팀 불일치 뒤 findTasks를 통한 기존 담당 작업 재사용도 막아 오배정 우회를 차단한다.
    @Test
    void blocksExistingAssignedTaskLookupAfterTeamMismatch() throws Exception {
        Member haechan = members.save(new Member("해찬", "백엔드팀", "U-HAECHAN"));
        tasks.saveAndFlush(Task.create("API 수정", haechan, null, completedMeeting("기존 작업")));
        ChatResponse[] responses = {
                rawToolResponse("findMember", "{\"name\":\"해찬\",\"team\":\"프론트엔드팀\"}"),
                rawToolResponse("findMember", "{\"name\":\"해찬\"}"),
                rawToolResponse("findTasks", "{\"assigneeId\":" + haechan.getId() + ",\"assigneeName\":\"해찬\",\"title\":\"API 수정\"}"),
                textResponse("팀 확인 필요")};
        when(model.call(any(org.springframework.ai.chat.prompt.Prompt.class))).thenReturn(responses[0], java.util.Arrays.copyOfRange(responses, 1, responses.length));
        submitStageA("프론트엔드팀 해찬 API 수정 재확인")
                .andExpect(jsonPath("$.tasks", org.hamcrest.Matchers.hasSize(0)));
        assertThat(mentions.count()).isZero();
        assertThat(logs.findAll()).anySatisfy(l -> {
            assertThat(l.getToolName()).isEqualTo("findTasks");
            assertThat(l.getErrorCode()).isEqualTo("INVALID_ASSIGNEE_TEAM");
        });
    }

    // 검증: 다른 회의의 팀 불일치 이력은 현재 회의의 정상 배정을 제한하지 않는다.
    @Test
    void teamMismatchGuardIsScopedToCurrentMeeting() throws Exception {
        Member haechan = members.save(new Member("해찬", "백엔드팀", "U-HAECHAN"));
        ChatResponse[] first = {rawToolResponse("findMember", "{\"name\":\"해찬\",\"team\":\"프론트엔드팀\"}"), textResponse("확인 필요")};
        when(model.call(any(org.springframework.ai.chat.prompt.Prompt.class))).thenReturn(first[0], first[1]);
        submitStageA("프론트엔드팀 해찬 담당 확인");
        ChatResponse[] second = {rawToolResponse("findMember", "{\"name\":\"해찬\"}"),
                rawCreate("member:" + haechan.getId()), textResponse("배정 완료")};
        when(model.call(any(org.springframework.ai.chat.prompt.Prompt.class))).thenReturn(second[0], java.util.Arrays.copyOfRange(second, 1, second.length));
        submitStageA("해찬은 API 수정 담당")
                .andExpect(jsonPath("$.tasks[0].assigneeName").value("해찬"))
                .andExpect(jsonPath("$.tasks[0].assigneeTeam").value("백엔드팀"));
    }

    // 검증: 이름만 명시된 유일한 해찬은 팀 조건 없이 정상 배정된다.
    @Test
    void assignsUniqueHaechanWhenTeamIsOmitted() throws Exception {
        Member haechan = members.save(new Member("해찬", "백엔드팀", "U-HAECHAN"));
        ChatResponse[] responses = {rawToolResponse("findMember", "{\"name\":\"해찬\"}"),
                rawToolResponse("findTasks", "{\"assigneeId\":" + haechan.getId() + ",\"assigneeName\":\"해찬\",\"title\":\"API 수정\"}"),
                rawCreate("member:" + haechan.getId()), textResponse("배정 완료")};
        when(model.call(any(org.springframework.ai.chat.prompt.Prompt.class))).thenReturn(responses[0], java.util.Arrays.copyOfRange(responses, 1, responses.length));
        submitStageA("해찬은 API 수정 담당")
                .andExpect(jsonPath("$.tasks[0].assigneeName").value("해찬"))
                .andExpect(jsonPath("$.validationFailureCount").value(0));
        ToolCallLog lookup = logs.findAll().stream().filter(l -> l.getToolName().equals("findMember")).findFirst().orElseThrow();
        assertThat(new ObjectMapper().readTree(lookup.getArguments()).has("team")).isFalse();
    }

    // 검증: 등록된 팀과 일치하는 백엔드팀 해찬은 정상 배정된다.
    @Test
    void assignsHaechanWhenExplicitTeamMatches() throws Exception {
        Member haechan = members.save(new Member("해찬", "백엔드팀", "U-HAECHAN"));
        ChatResponse[] responses = {rawToolResponse("findMember", "{\"name\":\"해찬\",\"team\":\"백엔드팀\"}"),
                rawToolResponse("findTasks", "{\"assigneeId\":" + haechan.getId() + ",\"assigneeName\":\"해찬\",\"title\":\"API 수정\"}"),
                rawCreate("member:" + haechan.getId()), textResponse("배정 완료")};
        when(model.call(any(org.springframework.ai.chat.prompt.Prompt.class))).thenReturn(responses[0], java.util.Arrays.copyOfRange(responses, 1, responses.length));
        submitStageA("백엔드팀 해찬은 API 수정 담당")
                .andExpect(jsonPath("$.tasks[0].assigneeTeam").value("백엔드팀"))
                .andExpect(jsonPath("$.validationFailureCount").value(0));
    }

    // 검증: 실제 AMBIGUOUS→없는 팀 재조회→findTasks→member 등록 흐름에서도 배정하지 않는다.
    @Test
    void rejectsActualInventedTeamReplayAndCountsValidationFailures() throws Exception {
        Member backend = seedJiyoung();
        ChatResponse[] responses = {
                rawToolResponse("findMember", "{\"name\":\"지영\"}"),
                rawToolResponse("findMember", "{\"name\":\"지영\",\"team\":\"백엔드팀\"}"),
                rawToolResponse("findTasks", "{\"assigneeId\":" + backend.getId() + ",\"assigneeName\":\"지영\",\"title\":\"API 수정\"}"),
                rawCreate("member:" + backend.getId()), textResponse("담당자 확인 필요")};
        when(model.call(any(org.springframework.ai.chat.prompt.Prompt.class))).thenReturn(responses[0], java.util.Arrays.copyOfRange(responses, 1, responses.length));
        String body = submitStageA("지영은 회의실 예약을 확인한다.")
                .andExpect(jsonPath("$.tasks", org.hamcrest.Matchers.hasSize(0)))
                .andExpect(jsonPath("$.validationFailureCount").value(3))
                .andReturn().getResponse().getContentAsString();
        long id = new ObjectMapper().readTree(body).path("meetingId").asLong();
        var history = logs.findAllByMeetingIdOrderByCallOrderAsc(id);
        assertThat(history.getFirst().getResult()).contains("회의록에서 후보 팀(백엔드팀, 디자인팀)이 언급되지 않았습니다.", "unassigned:AMBIGUOUS로 등록하세요.");
        assertThat(history.get(1).getErrorCode()).isEqualTo("TEAM_NOT_IN_TRANSCRIPT");
        assertThat(history.get(1).getResult()).contains("TEAM_NOT_IN_TRANSCRIPT");
        assertThat(tasks.count()).isZero();
    }

    // 검증: 동명이인이 아닌 첫 조회라도 회의록에 없는 팀은 거절한다.
    @Test
    void rejectsInventedTeamOnFirstLookupForUniqueMember() throws Exception {
        Member haechan = members.save(new Member("해찬", "백엔드팀", "U-HAECHAN"));
        ChatResponse[] responses = {rawToolResponse("findMember", "{\"name\":\"해찬\",\"team\":\"백엔드팀\"}"),
                rawCreate("member:" + haechan.getId()), textResponse("확인 필요")};
        when(model.call(any(org.springframework.ai.chat.prompt.Prompt.class))).thenReturn(responses[0], java.util.Arrays.copyOfRange(responses, 1, responses.length));
        submitStageA("해찬은 API를 수정한다.")
                .andExpect(jsonPath("$.tasks", org.hamcrest.Matchers.hasSize(0)));
        assertThat(logs.findAll()).anySatisfy(l -> assertThat(l.getErrorCode()).isEqualTo("TEAM_NOT_IN_TRANSCRIPT"));
    }

    // 검증: 회의록에 팀 접미사 없이 적힌 백엔드는 백엔드팀으로 조회·배정할 수 있다.
    @Test
    void assignsBackendJiyoungWhenTranscriptOmitsTeamSuffix() throws Exception {
        Member backend = seedJiyoung();
        ChatResponse[] responses = {rawToolResponse("findMember", "{\"name\":\"지영\",\"team\":\"백엔드\"}"),
                rawToolResponse("findTasks", "{\"assigneeId\":" + backend.getId() + ",\"assigneeName\":\"지영\",\"title\":\"API 수정\"}"),
                rawCreate("member:" + backend.getId()), textResponse("등록 완료")};
        when(model.call(any(org.springframework.ai.chat.prompt.Prompt.class))).thenReturn(responses[0], java.util.Arrays.copyOfRange(responses, 1, responses.length));
        submitStageA("백엔드 지영은 API를 수정한다.")
                .andExpect(jsonPath("$.tasks[0].assigneeTeam").value("백엔드팀"))
                .andExpect(jsonPath("$.validationFailureCount").value(0));
    }

    // 알려진 한계: 업무 내용의 '디자인'도 디자인팀 언급으로 간주해 소속 근거 없이 배정할 수 있다.
    @Test
    void knownLimitationDesignWorkWordAllowsDesignTeamLookupAndAssignment() throws Exception {
        members.save(new Member("지영", "백엔드팀", "U-BACKEND"));
        Member design = members.save(new Member("지영", "디자인팀", "U-DESIGN"));
        ChatResponse[] responses = {rawToolResponse("findMember", "{\"name\":\"지영\",\"team\":\"디자인팀\"}"),
                rawToolResponse("findTasks", "{\"assigneeId\":" + design.getId() + ",\"assigneeName\":\"지영\",\"title\":\"API 수정\"}"),
                rawCreate("member:" + design.getId()), textResponse("배정 결과")};
        when(model.call(any(org.springframework.ai.chat.prompt.Prompt.class))).thenReturn(responses[0], java.util.Arrays.copyOfRange(responses, 1, responses.length));
        submitStageA("지영은 디자인 시안을 검토한다.")
                .andExpect(jsonPath("$.tasks[0].assigneeTeam").value("디자인팀"))
                .andExpect(jsonPath("$.validationFailureCount").value(0));
    }

    private Member seedJiyoung() {
        Member backend = members.save(new Member("지영", "백엔드팀", "U-BACKEND"));
        members.save(new Member("지영", "디자인팀", "U-DESIGN"));
        return backend;
    }

    private ChatResponse unassignedLookup() {
        return rawToolResponse("findTasks", "{\"assigneeId\":null,\"assigneeName\":\"\",\"title\":\"API 수정\"}");
    }

    private ChatResponse rawCreate(String assignee) throws Exception {
        return rawToolResponse("createTask", new ObjectMapper().writeValueAsString(java.util.Map.of(
                "title", "API 수정", "assignee", assignee, "dueDate", "")));
    }

    private org.springframework.test.web.servlet.ResultActions submitStageA(String text) throws Exception {
        return mvc.perform(post("/api/meetings").contentType(MediaType.APPLICATION_JSON)
                .content(new ObjectMapper().writeValueAsString(java.util.Map.of("rawText", text))))
                .andExpect(status().isOk());
    }

    private ChatResponse rawToolResponse(String name, String arguments) {
        var call = new AssistantMessage.ToolCall("raw-" + name, "function", name, arguments);
        return new ChatResponse(List.of(new Generation(AssistantMessage.builder().content("")
                .toolCalls(List.of(call)).build())));
    }

    private Meeting completedMeeting(String text) {
        Meeting meeting = Meeting.create(text, MeetingSource.API_TEXT);
        meeting.startProcessing(java.time.Instant.parse("2026-11-11T00:00:00Z"));
        meeting.complete("완료", java.time.Instant.parse("2026-11-11T00:01:00Z"), 1, 1, 0);
        return meetings.saveAndFlush(meeting);
    }

    private void recordAfter(java.util.concurrent.CountDownLatch start, Task task, Meeting meeting) {
        try {
            start.await();
            mentionService.record(task.getId(), meeting.getId());
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(exception);
        }
    }

    // 기존 시나리오의 이름 기반 fixture를 현재 도구 계약으로 구성한다. 단계 A 회귀는 rawToolResponse를 사용한다.
    private ChatResponse toolResponse(String name, String arguments) {
        try {
            ObjectMapper mapper = new ObjectMapper();
            var object = (com.fasterxml.jackson.databind.node.ObjectNode) mapper.readTree(arguments);
            var calls = new java.util.ArrayList<AssistantMessage.ToolCall>();
            if (name.equals("findMember")) fixtureLookups.add(object.path("name").asText());
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
                    var candidates = members.findAllByNameOrderByIdAsc(memberName);
                    if (candidates.isEmpty()) token = "unassigned:NOT_FOUND";
                    else {
                        Member member = candidates.getFirst();
                        token = "member:" + member.getId();
                    }
                }
                if (token.startsWith("member:") && fixtureLookups.add(memberName)) {
                    calls.add(new AssistantMessage.ToolCall("lookup", "function", "findMember",
                            mapper.writeValueAsString(java.util.Map.of("name", memberName))));
                }
                object.remove(java.util.List.of("assigneeId", "assigneeName", "unassignedBecauseAmbiguous"));
                object.put("assignee", token);
            }
            calls.add(new AssistantMessage.ToolCall("call-" + name, "function", name, mapper.writeValueAsString(object)));
            return new ChatResponse(List.of(new Generation(AssistantMessage.builder().content("").toolCalls(calls).build())));
        } catch (Exception exception) { throw new IllegalArgumentException(exception); }
    }

    private ChatResponse textResponse(String text) {
        return new ChatResponse(List.of(new Generation(new AssistantMessage(text))));
    }
}
