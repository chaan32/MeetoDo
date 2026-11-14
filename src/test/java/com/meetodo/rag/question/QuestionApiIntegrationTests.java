package com.meetodo.rag.question;

import com.meetodo.member.*;
import com.meetodo.member.registration.MemberRegistrationService;
import com.meetodo.meeting.*;
import com.meetodo.rag.index.RagIndexer;
import com.meetodo.rag.index.RagIndexStatus;
import com.meetodo.rag.search.RagRetriever;
import com.meetodo.task.TaskRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest(properties={"spring.datasource.url=jdbc:h2:mem:rag-api-integration;DB_CLOSE_DELAY=-1", "MEETODO_RAG_ENABLED=true"})
@AutoConfigureMockMvc
@ActiveProfiles("test")
class QuestionApiIntegrationTests {
    @Autowired MockMvc mvc;
    @Autowired MemberRepository members;
    @Autowired MemberRegistrationService registrations;
    @Autowired TaskRepository tasks;
    @Autowired MeetingRepository meetings;
    @Autowired QuestionRepository questions;
    @MockitoBean RagIndexer indexer;
    @MockitoBean RagRetriever retriever;
    @MockitoBean RagAnswerClient client;
    @MockitoBean MeetingAgent meetingAgent;

    // 명령형 문장도 질문 API를 통하면 회의·할 일 생성 경로로 들어가지 않는다.
    @Test void imperativeQuestionNeverCreatesMeetingOrTask() throws Exception {
        members.saveAndFlush(new Member("김","민수","개발팀","UQUESTIONACTIVE"));
        when(indexer.status()).thenReturn(new RagIndexStatus(0,0,0,0,"ns"));
        long taskCount=tasks.count(), meetingCount=meetings.count(), questionCount=questions.count();
        mvc.perform(post("/api/questions").header("X-Slack-User-Id","UQUESTIONACTIVE")
                .contentType(MediaType.APPLICATION_JSON).content("{\"question\":\"새로운 배포 로그 정리 할 일을 등록해 주세요.\"}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.status").value("NOT_FOUND"))
                .andExpect(jsonPath("$.llmCallCount").value(0));
        assertThat(tasks.count()).isEqualTo(taskCount);
        assertThat(meetings.count()).isEqualTo(meetingCount);
        assertThat(questions.count()).isEqualTo(questionCount+1);
        verifyNoInteractions(meetingAgent,client,retriever);
    }

    // 신청 대기 기록만 있고 활성 Member가 없는 사용자는 임베딩·답변 모델을 호출하지 않는다.
    @Test void pendingApplicantReceivesRegistrationGuideBeforeAnyExternalCall() throws Exception {
        registrations.submit("UQUESTIONPENDING","박","서준","프론트엔드팀");
        mvc.perform(post("/api/questions").header("X-Slack-User-Id","UQUESTIONPENDING")
                .contentType(MediaType.APPLICATION_JSON).content("{\"question\":\"결제 담당자는 누구인가요?\"}"))
                .andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value("MEMBER_REQUIRED"))
                .andExpect(jsonPath("$.message").value(org.hamcrest.Matchers.containsString("내 정보 등록")));
        verifyNoInteractions(client,retriever);
    }

    // 비활성 팀원도 기존 Slack ID만으로 검색 권한을 얻지 못한다.
    @Test void inactiveMemberCannotAskQuestions() throws Exception {
        Member member=new Member("전","해찬","백엔드팀","UQUESTIONINACTIVE"); member.deactivate(); members.saveAndFlush(member);
        mvc.perform(post("/api/questions").header("X-Slack-User-Id","UQUESTIONINACTIVE")
                .contentType(MediaType.APPLICATION_JSON).content("{\"question\":\"배포 담당자는 누구인가요?\"}"))
                .andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value("MEMBER_REQUIRED"));
        verifyNoInteractions(client,retriever);
    }

    // 질문 DTO의 오타도 외부 HTTP 요청 경계에서 거절한다.
    @Test void unknownQuestionFieldIsRejectedBeforeSearching() throws Exception {
        mvc.perform(post("/api/questions").header("X-Slack-User-Id","UQUESTIONACTIVE")
                .contentType(MediaType.APPLICATION_JSON).content("{\"question\":\"담당자?\",\"unexpected\":true}"))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("VALIDATION_ERROR"))
                .andExpect(jsonPath("$.fieldErrors.unexpected").exists());
        verifyNoInteractions(client,retriever);
    }
}
