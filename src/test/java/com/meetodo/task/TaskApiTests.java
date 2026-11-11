package com.meetodo.task;

import com.meetodo.member.Member;
import com.meetodo.member.MemberRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import static org.hamcrest.Matchers.hasSize;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class TaskApiTests {

    @Autowired MockMvc mvc;
    @Autowired MemberRepository memberRepository;
    @Autowired TaskMentionRepository mentionRepository;
    @Autowired JdbcTemplate jdbc;

    @BeforeEach
    void resetDatabase() {
        mentionRepository.deleteAll();
        jdbc.update("delete from tasks");
        memberRepository.deleteAll();
        memberRepository.save(new Member("민수", "U-MINSU"));
    }

    // 검증: createsAndReturnsTaskWithMemberAndUnassignedTask 시나리오의 기대 동작을 확인한다.
    @Test
    void createsAndReturnsTaskWithMemberAndUnassignedTask() throws Exception {
        mvc.perform(post("/api/tasks").contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"title":"설계서 작성","assigneeName":"민수","dueDate":"2026-11-20"}
                                """))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.id").isNumber())
                .andExpect(jsonPath("$.assignee.name").value("민수"))
                .andExpect(jsonPath("$.status").value("TODO"))
                .andExpect(jsonPath("$.mentionCount").value(0))
                .andExpect(jsonPath("$.dueDate").value("2026-11-20"))
                .andExpect(jsonPath("$.createdAt").isNotEmpty());

        mvc.perform(post("/api/tasks").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"title\":\"미정 작업\"}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.assignee").doesNotExist());

        mvc.perform(get("/api/tasks").param("assignee", "민수"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(1)))
                .andExpect(jsonPath("$[0].title").value("설계서 작성"))
                .andExpect(jsonPath("$[0].mentionCount").value(0));
    }

    // 검증: updatesStatusThenReturnsUpdatedTaskOnNextRead 시나리오의 기대 동작을 확인한다.
    @Test
    void updatesStatusThenReturnsUpdatedTaskOnNextRead() throws Exception {
        String body = mvc.perform(post("/api/tasks").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"title\":\"검토하기\"}"))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        long id = com.fasterxml.jackson.databind.json.JsonMapper.builder().build()
                .readTree(body).get("id").asLong();

        mvc.perform(patch("/api/tasks/{id}", id).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"status\":\"DONE\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("DONE"));

        mvc.perform(get("/api/tasks"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].status").value("DONE"));
    }

    // 검증: returnsStructuredErrorsForInvalidInputAndMissingResource 시나리오의 기대 동작을 확인한다.
    @Test
    void returnsStructuredErrorsForInvalidInputAndMissingResource() throws Exception {
        mvc.perform(post("/api/tasks").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"title\":\" \"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"))
                .andExpect(jsonPath("$.fieldErrors.title").exists());

        mvc.perform(post("/api/tasks").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"title\":\"작업\",\"assigneeName\":\"없는 사람\"}"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("MEMBER_NOT_FOUND"));

        mvc.perform(patch("/api/tasks/98765").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"status\":\"DONE\"}"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("TASK_NOT_FOUND"));
    }

    // 검증: DTO에 없는 요청 필드는 조용히 무시하지 않고 오류 응답에 이름을 표시한다.
    @Test
    void rejectsUnknownRequestFieldsWithTheirNames() throws Exception {
        mvc.perform(post("/api/tasks").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"title\":\"회의실 예약 확인\",\"assignee\":\"지영\",\"dueDate\":\"2026-11-21\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"))
                .andExpect(jsonPath("$.fieldErrors.assignee").exists());
    }

    // 검증: 직접 API의 TODO 중복은 기존 Task ID가 포함된 409이고 DONE 이후에는 새 등록을 허용한다.
    @Test
    void rejectsDuplicateDirectTaskUntilExistingTaskIsDone() throws Exception {
        String first = mvc.perform(post("/api/tasks").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"title\":\"회의실 예약 확인\"}"))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        long existingId = com.fasterxml.jackson.databind.json.JsonMapper.builder().build()
                .readTree(first).get("id").asLong();

        mvc.perform(post("/api/tasks").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"title\":\"회의실  예약 확인\"}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("DUPLICATE_TASK"))
                .andExpect(jsonPath("$.existingTaskId").value(existingId));

        mvc.perform(patch("/api/tasks/{id}", existingId).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"status\":\"DONE\"}"))
                .andExpect(status().isOk());
        mvc.perform(post("/api/tasks").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"title\":\"회의실 예약 확인\"}"))
                .andExpect(status().isCreated());
    }

    // 검증: 같은 이름 팀원을 저장하고 이름만 요청한 경우 모호성 오류를, ID 요청은 정상 등록을 반환한다.
    @Test
    void rejectsAmbiguousAssigneeNameAndAcceptsMemberId() throws Exception {
        Member backend = memberRepository.save(new Member("지영", "백엔드팀", "U-BACKEND-JIYOUNG"));
        memberRepository.save(new Member("지영", "디자인팀", "U-DESIGN-JIYOUNG"));

        mvc.perform(post("/api/tasks").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"title\":\"화면 검토\",\"assigneeName\":\"지영\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("AMBIGUOUS_MEMBER"))
                .andExpect(jsonPath("$.candidates", hasSize(2)))
                .andExpect(jsonPath("$.candidates[0].team").exists());

        mvc.perform(post("/api/tasks").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"title\":\"API 검토\",\"assigneeId\":" + backend.getId() + "}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.assignee.id").value(backend.getId()))
                .andExpect(jsonPath("$.assignee.team").value("백엔드팀"))
                .andExpect(jsonPath("$.assignee.team").exists());
    }

    // 검증: assigneeId 필터는 동명이인 중 지정한 한 명의 Task만 반환한다.
    @Test
    void filtersTasksByAssigneeIdWhenNamesAreAmbiguous() throws Exception {
        Member backend = memberRepository.save(new Member("지영", "백엔드팀", "U-BACKEND-JIYOUNG"));
        memberRepository.save(new Member("지영", "디자인팀", "U-DESIGN-JIYOUNG"));
        mvc.perform(post("/api/tasks").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"title\":\"API 테스트\",\"assigneeId\":" + backend.getId() + "}"))
                .andExpect(status().isCreated());

        mvc.perform(get("/api/tasks").param("assigneeId", backend.getId().toString()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(1)))
                .andExpect(jsonPath("$[0].assignee.team").value("백엔드팀"));
    }
}
