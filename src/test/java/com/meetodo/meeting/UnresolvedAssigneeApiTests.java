package com.meetodo.meeting;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.meetodo.member.Member;
import com.meetodo.member.MemberRepository;
import com.meetodo.task.TaskMentionRepository;
import com.meetodo.task.TaskRepository;
import com.meetodo.tools.CreateTaskTool;
import com.meetodo.tools.FindMemberTool;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class UnresolvedAssigneeApiTests {
    @Autowired MockMvc mvc;
    @Autowired ObjectMapper mapper;
    @Autowired MemberRepository members;
    @Autowired TaskRepository tasks;
    @Autowired TaskMentionRepository mentions;
    @Autowired MeetingRepository meetings;
    @Autowired ToolCallLogRepository logs;
    @Autowired FindMemberTool findMember;
    @Autowired CreateTaskTool createTask;
    @MockitoBean MeetingAgent agent;

    @BeforeEach void clean() {
        mentions.deleteAll(); logs.deleteAll(); tasks.deleteAll(); meetings.deleteAll(); members.deleteAll();
        members.save(new Member("지영", "백엔드팀", "U-BACK"));
        members.save(new Member("지영", "디자인팀", "U-DESIGN"));
    }

    // 재현: 동명이인으로 미정 등록된 Task 조회에 원래 이름과 후보가 있어야 한다.
    @Test void taskApiIncludesRequestedNameAndCandidatesForAmbiguousAssignee() throws Exception {
        when(agent.process(any(Meeting.class))).thenAnswer(invocation -> {
            Meeting meeting = invocation.getArgument(0);
            var lookup = findMember.findMember("지영", meeting);
            createTask.createTask("회의실 예약", "unassigned:AMBIGUOUS", "", lookup.lookupCallOrder(), meeting);
            return new AgentResult("완료", 1, 2, 0);
        });
        mvc.perform(post("/api/meetings").contentType("application/json")
                .content("{\"rawText\":\"지영은 회의실 예약을 확인한다.\"}"))
                .andExpect(status().isOk());
        mvc.perform(get("/api/tasks"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].unresolvedAssignee.requestedAssigneeName").value("지영"))
                .andExpect(jsonPath("$[0].unresolvedAssignee.candidates.length()").value(2));
    }
    // 팀 불일치 스냅샷은 Task와 회의 응답에 요청 팀 및 실제 후보를 함께 보존한다.
    @Test void teamMismatchPersistsAndAppearsInTaskAndMeetingApis() throws Exception {
        members.save(new Member("해찬", "백엔드팀", "U-HAECHAN"));
        configure("해찬", "프론트엔드팀", "TEAM_MISMATCH", "체크리스트 공유");
        String body = postMeeting("프론트엔드팀 해찬은 체크리스트를 공유한다.");
        long id = mapper.readTree(body).path("meetingId").asLong();
        mvc.perform(get("/api/meetings/{id}", id))
                .andExpect(jsonPath("$.tasks[0].unresolvedAssignee.requestedTeam").value("프론트엔드팀"))
                .andExpect(jsonPath("$.tasks[0].unresolvedAssignee.candidates[0].team").value("백엔드팀"))
                .andExpect(jsonPath("$.tasks[0].warning").value("팀 불일치 확인 필요: \"프론트엔드팀 해찬\" (등록: 백엔드팀 해찬)"));
        mvc.perform(get("/api/tasks"))
                .andExpect(jsonPath("$[0].unresolvedAssignee.reason").value("TEAM_MISMATCH"))
                .andExpect(jsonPath("$[0].unresolvedAssignee.requestedAssigneeName").value("해찬"));
        org.assertj.core.api.Assertions.assertThat(tasks.findAll().getFirst().getUnresolvedAssignee().requestedTeam())
                .isEqualTo("프론트엔드팀");
    }

    // 등록되지 않은 이름도 Task에 저장되고 두 API의 경고에 포함된다.
    @Test void notFoundPersistsAndAppearsInTaskAndMeetingApis() throws Exception {
        configure("준호", null, "NOT_FOUND", "테스트 추가");
        String body = postMeeting("준호는 테스트를 추가한다.");
        long id = mapper.readTree(body).path("meetingId").asLong();
        mvc.perform(get("/api/meetings/{id}", id))
                .andExpect(jsonPath("$.tasks[0].warning").value("등록되지 않은 담당자: \"준호\""))
                .andExpect(jsonPath("$.tasks[0].unresolvedAssignee.requestedAssigneeName").value("준호"));
        mvc.perform(get("/api/tasks"))
                .andExpect(jsonPath("$[0].warning").value("등록되지 않은 담당자: \"준호\""))
                .andExpect(jsonPath("$[0].unassignedReason").value("NOT_FOUND"));
        org.assertj.core.api.Assertions.assertThat(tasks.findAll().getFirst().getUnresolvedAssignee().requestedAssigneeName())
                .isEqualTo("준호");
    }

    // 후보 팀이 나중에 수정돼도 기존 Task의 생성 당시 스냅샷은 바뀌지 않는다.
    @Test void ambiguousTaskKeepsStoredCandidateSnapshot() throws Exception {
        configure("지영", null, "AMBIGUOUS", "예약 확인");
        String body = postMeeting("지영은 예약을 확인한다.");
        long id = mapper.readTree(body).path("meetingId").asLong();
        mvc.perform(get("/api/meetings/{id}", id))
                .andExpect(jsonPath("$.tasks[0].warning").value("동명이인 확인 필요: \"지영\" (후보: 백엔드팀, 디자인팀)"));
        var snapshot = tasks.findAll().getFirst().getUnresolvedAssignee();
        org.assertj.core.api.Assertions.assertThat(snapshot.candidates()).hasSize(2);
        org.assertj.core.api.Assertions.assertThat(snapshot.requestedAssigneeName()).isEqualTo("지영");
        members.save(new Member("지영", "기획팀", "U-PLAN"));
        mvc.perform(get("/api/tasks"))
                .andExpect(jsonPath("$[0].unresolvedAssignee.candidates.length()").value(2));
    }

    // 기존 미정 Task는 최초 정보를 유지하고 재사용 회의 응답에는 그 회의의 요청 이름을 제공한다.
    @Test void reusedTaskKeepsOriginalSnapshotButMeetingUsesCurrentLookup() throws Exception {
        configure("준호", null, "NOT_FOUND", "자료 준비");
        postMeeting("준호는 자료를 준비한다.");
        configure("수진", null, "NOT_FOUND", "자료 준비");
        String second = postMeeting("수진은 자료를 준비한다.");
        org.assertj.core.api.Assertions.assertThat(tasks.count()).isEqualTo(1);
        var task = tasks.findAll().getFirst();
        org.assertj.core.api.Assertions.assertThat(task.getUnresolvedAssignee().requestedAssigneeName()).isEqualTo("준호");
        long id = mapper.readTree(second).path("meetingId").asLong();
        mvc.perform(get("/api/meetings/{id}", id))
                .andExpect(jsonPath("$.tasks[0].resultType").value("EXISTING"))
                .andExpect(jsonPath("$.tasks[0].unresolvedAssignee.requestedAssigneeName").value("수진"))
                .andExpect(jsonPath("$.tasks[0].warning").value("등록되지 않은 담당자: \"수진\""));
        mvc.perform(get("/api/tasks"))
                .andExpect(jsonPath("$[0].unresolvedAssignee.requestedAssigneeName").value("준호"));
    }

    // 상세 스냅샷이 없는 기존 행은 추정 백필 없이 기존 로그의 짧은 경고만 반환한다.
    @Test void historicalTaskWithoutSnapshotKeepsLegacyWarning() throws Exception {
        Meeting meeting = meetings.saveAndFlush(Meeting.create("이전 회의", MeetingSource.API_TEXT));
        var task = tasks.saveAndFlush(com.meetodo.task.Task.create("기존 작업", null, null, meeting));
        String data = mapper.writeValueAsString(java.util.Map.of("id", task.getId(), "warning", "동명이인 확인 필요", "unassignedReason", "AMBIGUOUS"));
        logs.saveAndFlush(ToolCallLog.success(meeting, "createTask", "{}",
                mapper.writeValueAsString(com.meetodo.tools.ToolResult.success("완료", data)), 0, 1));
        mvc.perform(get("/api/meetings/{id}", meeting.getId()))
                .andExpect(jsonPath("$.tasks[0].warning").value("동명이인 확인 필요"))
                .andExpect(jsonPath("$.tasks[0].unresolvedAssignee").isEmpty());
        mvc.perform(get("/api/tasks"))
                .andExpect(jsonPath("$[0].unresolvedAssignee").isEmpty());
    }

    // 담당자 언급 없음은 조회 없이 등록되고 경고가 붙지 않는다.
    @Test void noneReasonNeedsNoLookupAndHasNoWarning() throws Exception {
        when(agent.process(any(Meeting.class))).thenAnswer(invocation -> {
            createTask.createTask("문서 정리", "unassigned:NONE", "", invocation.getArgument(0));
            return new AgentResult("완료", 1, 1, 0);
        });
        String body = postMeeting("문서를 정리한다.");
        org.assertj.core.api.Assertions.assertThat(mapper.readTree(body).at("/tasks/0/warning").asText("")).isEmpty();
    }

    private void configure(String name, String team, String reason, String title) {
        when(agent.process(any(Meeting.class))).thenAnswer(invocation -> {
            Meeting meeting = invocation.getArgument(0);
            var lookup = findMember.findMember(name, team, meeting);
            var result = createTask.createTask(title, "unassigned:" + reason, "", lookup.lookupCallOrder(), meeting);
            org.assertj.core.api.Assertions.assertThat(result.success()).isTrue();
            return new AgentResult("완료", 1, 2, 0);
        });
    }

    private String postMeeting(String text) throws Exception {
        return mvc.perform(post("/api/meetings").contentType("application/json")
                        .content(mapper.writeValueAsString(java.util.Map.of("rawText", text))))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString(java.nio.charset.StandardCharsets.UTF_8);
    }
}
