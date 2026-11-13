package com.meetodo.task;

import com.meetodo.member.Member;
import com.meetodo.member.MemberRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.test.web.servlet.MockMvc;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Transactional
class TaskAssignmentApiTests {
    @Autowired MockMvc mvc;
    @Autowired MemberRepository members;
    @Autowired TaskRepository tasks;

    // 미정 TODO를 지정하면 담당자와 실제 Slack 형식의 지정자를 응답한다.
    @Test void assignsUnassignedTodoThroughApi() throws Exception {
        var member = members.save(new Member("전", "해찬", "백엔드", "UASSIGNEE"));
        var task = tasks.save(Task.create("배정 API 검토", null, null));
        mvc.perform(patch("/api/tasks/{id}/assignee", task.getId())
                        .header("X-Slack-User-Id", "UACTOR1")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"assigneeId\":" + member.getId() + "}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.assignee.id").value(member.getId()))
                .andExpect(jsonPath("$.assignedBySlackUserId").value("UACTOR1"))
                .andExpect(jsonPath("$.assignedAt").isNotEmpty());
    }

    // 담당자 ID 누락은 필드 이름이 포함된 검증 오류다.
    @Test void rejectsMissingAssigneeId() throws Exception {
        mvc.perform(patch("/api/tasks/1/assignee").header("X-Slack-User-Id", "UACTOR1")
                        .contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.fieldErrors.assigneeId").exists());
    }

    // 음수 ID는 서비스나 DB에 넘기지 않는다.
    @Test void rejectsNegativeAssigneeId() throws Exception {
        mvc.perform(patch("/api/tasks/1/assignee").header("X-Slack-User-Id", "UACTOR1")
                        .contentType(MediaType.APPLICATION_JSON).content("{\"assigneeId\":-1}"))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.fieldErrors.assigneeId").exists());
    }

    // 임의 요청 필드는 공통 엄격 HTTP 검증을 그대로 적용한다.
    @Test void rejectsUnknownField() throws Exception {
        mvc.perform(patch("/api/tasks/1/assignee").header("X-Slack-User-Id", "UACTOR1")
                        .contentType(MediaType.APPLICATION_JSON).content("{\"assigneeId\":1,\"actor\":\"fake\"}"))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.fieldErrors.actor").exists());
    }

    // actor 헤더 누락은 400으로 응답한다.
    @Test void requiresActorHeader() throws Exception {
        mvc.perform(patch("/api/tasks/1/assignee").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"assigneeId\":1}"))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("INVALID_SLACK_USER_ID"));
    }

    // 가짜 개발 ID는 일반화된 400 코드로 응답한다.
    @Test void rejectsFakeActorWithStructuredError() throws Exception {
        mvc.perform(patch("/api/tasks/1/assignee").header("X-Slack-User-Id", "U-DEV-HAECHAN")
                        .contentType(MediaType.APPLICATION_JSON).content("{\"assigneeId\":1}"))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("INVALID_SLACK_USER_ID"));
    }

    // 배정 중복도 기존 Task ID를 포함한 공통 409를 사용한다.
    @Test void duplicateAssignmentReturnsExistingTaskId() throws Exception {
        var member = members.save(new Member("전", "해찬", "백엔드", "UDUPLICATE1"));
        var existing = tasks.save(Task.create("API 배정 검토", member, null));
        var unassigned = tasks.save(Task.create("api 배정 검토", null, null));
        mvc.perform(patch("/api/tasks/{id}/assignee", unassigned.getId()).header("X-Slack-User-Id", "UACTOR1")
                        .contentType(MediaType.APPLICATION_JSON).content("{\"assigneeId\":" + member.getId() + "}"))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("DUPLICATE_TASK"))
                .andExpect(jsonPath("$.existingTaskId").value(existing.getId()));
    }
}
