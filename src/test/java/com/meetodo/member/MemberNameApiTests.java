package com.meetodo.member;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.meetodo.meeting.*;
import com.meetodo.task.*;
import com.meetodo.tools.*;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class MemberNameApiTests {
    @Autowired MockMvc mvc;
    @Autowired ObjectMapper mapper;
    @Autowired MemberRepository members;
    @Autowired TaskRepository tasks;
    @Autowired TaskMentionRepository mentions;
    @Autowired MeetingRepository meetings;
    @Autowired ToolCallLogRepository logs;
    @Autowired FindMemberTool findMember;
    @Autowired CreateTaskTool createTask;
    @Autowired FindTasksTool findTasks;
    @MockitoBean MeetingAgent agent;
    Member minsu;
    Member backend;

    @BeforeEach void seed() {
        mentions.deleteAll(); logs.deleteAll(); tasks.deleteAll(); meetings.deleteAll(); members.deleteAll();
        minsu = members.save(new Member("김", "민수", "백엔드팀", "U-MINSU"));
        backend = members.save(new Member("이", "지영", "백엔드팀", "U-JIYOUNG-BACK"));
        members.save(new Member("이", "지영", "디자인팀", "U-JIYOUNG-DESIGN"));
        members.save(new Member("전", "해찬", "백엔드팀", "U-HAECHAN"));
    }

    // 전체 이름을 직접 API로 보내면 전체 이름과 팀으로 응답한다.
    @Test void directApiAcceptsFullNameAndDisplaysFullName() throws Exception {
        createDirect("전체 이름 작업", "김민수")
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.assignee.name").value("김민수"))
                .andExpect(jsonPath("$.assignee.team").value("백엔드팀"));
    }

    // 이름만 보내도 같은 사람을 찾아 전체 이름으로 표시한다.
    @Test void directApiAcceptsGivenNameAndDisplaysFullName() throws Exception {
        createDirect("이름만 작업", "민수")
                .andExpect(status().isCreated()).andExpect(jsonPath("$.assignee.id").value(minsu.getId()))
                .andExpect(jsonPath("$.assignee.name").value("김민수"));
    }

    // 공백을 둔 씨 호칭도 직접 API에서 공통 조회 규칙을 따른다.
    @Test void directApiAcceptsSpacedHonorific() throws Exception {
        createDirect("호칭 작업", "민수 씨")
                .andExpect(status().isCreated()).andExpect(jsonPath("$.assignee.name").value("김민수"));
    }

    // 성까지 같은 동명이인은 전체 이름을 보내도 임의로 배정하지 않는다.
    @Test void directApiRejectsAmbiguousFullName() throws Exception {
        createDirect("동명이인 작업", "이지영")
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("AMBIGUOUS_MEMBER"))
                .andExpect(jsonPath("$.candidates.length()").value(2))
                .andExpect(jsonPath("$.candidates[0].name").value("이지영"));
    }

    // 님을 제거한 결과가 동명이인이면 직접 API에서도 모호성 오류를 반환한다.
    @Test void directApiRejectsAmbiguousHonorificName() throws Exception {
        createDirect("호칭 동명이인 작업", "지영님")
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("AMBIGUOUS_MEMBER"));
    }

    // 직접 API도 직급이나 조사를 임의로 제거하지 않는다.
    @Test void directApiDoesNotGuessAwayRolesOrParticles() throws Exception {
        createDirect("직급 작업", "민수 팀장님이")
                .andExpect(status().isNotFound()).andExpect(jsonPath("$.code").value("MEMBER_NOT_FOUND"));
    }

    // 두 글자 성은 명시한 값 그대로 저장되고 전체 이름으로 조회된다.
    @Test void directApiFindsExplicitCompoundFamilyName() throws Exception {
        members.save(new Member("남궁", "민", "기획팀", "U-NAMGUNG"));
        createDirect("두 글자 성 작업", "남궁민")
                .andExpect(status().isCreated()).andExpect(jsonPath("$.assignee.name").value("남궁민"));
    }

    // ID와 이름을 함께 필터링할 때도 이름·호칭을 같은 담당자의 별칭으로 인정한다.
    @Test void taskListAcceptsGivenNameHonorificAlongsideId() throws Exception {
        createDirect("조회 작업", "김민수").andExpect(status().isCreated());
        mvc.perform(get("/api/tasks").param("assigneeId", minsu.getId().toString()).param("assignee", "민수 씨"))
                .andExpect(status().isOk()).andExpect(jsonPath("$[0].assignee.name").value("김민수"));
    }

    // 이름 필터는 동명이인 양쪽의 Task를 반환하고 ID 필터는 한 사람만 반환한다.
    @Test void taskListGivenNameIncludesBothHomonyms() throws Exception {
        var all = members.findAllByGivenNameOrderByIdAsc("지영");
        for (Member member : all) {
            mvc.perform(post("/api/tasks").contentType("application/json")
                    .content(mapper.writeValueAsString(Map.of("title", "팀별 작업", "assigneeId", member.getId()))))
                    .andExpect(status().isCreated());
        }
        mvc.perform(get("/api/tasks").param("assignee", "지영님"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.length()").value(2));
        mvc.perform(get("/api/tasks").param("assigneeId", backend.getId().toString()))
                .andExpect(status().isOk()).andExpect(jsonPath("$.length()").value(1));
    }

    // D 스냅샷은 호칭 제거 전 조회 인자를 보존하고 후보에는 전체 이름을 담는다.
    @Test void unresolvedSnapshotPreservesQueriedHonorificAndFullCandidateNames() throws Exception {
        when(agent.process(any(Meeting.class))).thenAnswer(invocation -> {
            Meeting meeting = invocation.getArgument(0);
            var lookup = findMember.findMember("지영님", meeting);
            assertThat(createTask.createTask("예약 확인", "unassigned:AMBIGUOUS", "", lookup.lookupCallOrder(), meeting).success()).isTrue();
            return new AgentResult("완료", 1, 2, 0);
        });
        mvc.perform(post("/api/meetings").contentType("application/json").content("{\"rawText\":\"지영님은 예약을 확인한다.\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.tasks[0].unresolvedAssignee.requestedAssigneeName").value("지영님"))
                .andExpect(jsonPath("$.tasks[0].unresolvedAssignee.candidates[0].name").value("이지영"))
                .andExpect(jsonPath("$.tasks[0].warning").value("동명이인 확인 필요: \"지영님\" (후보: 백엔드팀, 디자인팀)"));
        mvc.perform(get("/api/tasks"))
                .andExpect(jsonPath("$[0].unresolvedAssignee.requestedAssigneeName").value("지영님"));
    }

    // findTasks의 ID-이름 대조도 호칭 없는 이름 조회와 동일한 사람을 인정한다.
    @Test void findTasksAcceptsGivenNameForAuthorizedFullNamedMember() {
        Meeting meeting = meetings.saveAndFlush(Meeting.create("김민수는 자료를 준비한다.", MeetingSource.API_TEXT));
        tasks.saveAndFlush(Task.create("자료 준비", minsu, null));
        findMember.findMember("김민수", meeting);
        var result = findTasks.findTasks(minsu.getId(), "민수 씨", "자료 준비", meeting);
        assertThat(result.success()).isTrue();
        assertThat(result.data()).contains("김민수", "EXISTING");
    }

    // 이름 별칭으로 팀 불일치 후 전체 이름을 재조회해도 보수적 배정 차단이 유지된다.
    @Test void teamMismatchCannotBeBypassedBySwitchingToFullName() {
        Meeting meeting = meetings.saveAndFlush(Meeting.create("프론트엔드팀 해찬은 준비한다.", MeetingSource.API_TEXT));
        var wrongTeam = findMember.findMember("해찬", "프론트엔드팀", meeting);
        assertThat(wrongTeam.errorCode()).isEqualTo("MEMBER_TEAM_NOT_FOUND");
        var retry = findMember.findMember("전해찬", meeting);
        long id;
        try { id = mapper.readTree(retry.data()).get(0).path("id").asLong(); }
        catch (Exception e) { throw new AssertionError(e); }
        var assigned = createTask.createTask("준비", "member:" + id, null, meeting);
        assertThat(assigned.errorCode()).isEqualTo("INVALID_ASSIGNEE_TEAM");
        var fallback = createTask.createTask("준비", "unassigned:TEAM_MISMATCH", null, wrongTeam.lookupCallOrder(), meeting);
        assertThat(fallback.success()).isTrue();
        assertThat(tasks.findAll().getFirst().getUnresolvedAssignee().warning())
                .isEqualTo("팀 불일치 확인 필요: \"프론트엔드팀 해찬\" (등록: 백엔드팀 전해찬)");
    }

    private org.springframework.test.web.servlet.ResultActions createDirect(String title, String name) throws Exception {
        return mvc.perform(post("/api/tasks").contentType("application/json")
                .content(mapper.writeValueAsString(Map.of("title", title, "assigneeName", name))));
    }
}
