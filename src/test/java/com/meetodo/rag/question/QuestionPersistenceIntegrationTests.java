package com.meetodo.rag.question;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.meetodo.member.*;
import com.meetodo.meeting.*;
import com.meetodo.rag.index.*;
import com.meetodo.rag.search.RagRetriever;
import com.meetodo.task.*;
import java.time.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.model.*;
import org.springframework.ai.document.Document;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@SpringBootTest(properties={"spring.datasource.url=jdbc:h2:mem:rag-question-persist;DB_CLOSE_DELAY=-1", "MEETODO_RAG_ENABLED=true"})
@ActiveProfiles("test")
class QuestionPersistenceIntegrationTests {
    @Autowired QuestionService service;
    @Autowired QuestionRepository questions;
    @Autowired MemberRepository members;
    @Autowired MeetingRepository meetings;
    @Autowired TaskRepository tasks;
    @Autowired RagIndexDocuments documents;
    @Autowired ObjectMapper json;
    @Autowired PlatformTransactionManager manager;
    @MockitoBean RagIndexer indexer;
    @MockitoBean RagRetriever retriever;
    @MockitoBean RagAnswerClient client;

    // 저장된 벡터 내용이 오래돼도 모델 호출 후 바뀐 실제 DB 담당자·마감일·완료 상태를 응답한다.
    @Test void persistedQuestionUsesCurrentDatabaseFactsInsteadOfIndexedTaskSnapshot() throws Exception {
        Member first = members.saveAndFlush(new Member("김", "민수", "백엔드팀", "URAGFIRST"));
        Member next = members.saveAndFlush(new Member("박", "서준", "프론트엔드팀", "URAGNEXT"));
        Instant at = Instant.parse("2026-11-14T00:00:00Z");
        Meeting meeting = Meeting.create("민수는 결제 API 스펙을 정리한다.", MeetingSource.API_TEXT);
        meeting.startProcessing(at); meeting.complete("완료", at.plusSeconds(1), 1, 0, 0);
        meetings.saveAndFlush(meeting);
        Task task = tasks.saveAndFlush(Task.create("결제 API 스펙 정리", first, LocalDate.of(2026,11,20), meeting));
        Document indexed = documents.forMeeting(meeting.getId()).stream()
                .filter(doc -> "TASK".equals(doc.getMetadata().get("kind"))).findFirst().orElseThrow();
        assertThat(indexed.getText()).contains("김민수", "TODO");
        when(indexer.status()).thenReturn(new RagIndexStatus(1,1,0,0,documents.namespace()));
        when(retriever.retrieve(anyString(),eq(false))).thenReturn(List.of(indexed));
        when(client.answer(anyString(),anyList())).thenAnswer(call -> {
            RagSourceSnapshot evidence = ((List<RagSourceSnapshot>)call.getArgument(1)).getFirst();
            new TransactionTemplate(manager).executeWithoutResult(status -> {
                Task current = tasks.findById(task.getId()).orElseThrow();
                current.assign(next,"UADMIN",at.plusSeconds(2));
                current.edit(current.getTitle(),LocalDate.of(2026,11,21));
                current.changeStatus(TaskStatus.DONE,"URAGNEXT",at.plusSeconds(3));
                tasks.saveAndFlush(current);
            });
            String answer = json.writeValueAsString(Map.of("found",true,"evidence",List.of(Map.of(
                    "documentId",evidence.documentId(),"meetingId",meeting.getId(),
                    "taskId",task.getId(),"quote",evidence.text()))));
            return new ChatResponse(List.of(new Generation(new AssistantMessage(answer))));
        });
        QuestionResponse result = service.ask("결제 API 스펙 담당자와 현재 상태는?","URAGFIRST",false);
        assertThat(result.status()).isEqualTo(QuestionStatus.ANSWERED);
        assertThat(result.currentTasks()).singleElement().satisfies(current -> {
            assertThat(current.assigneeName()).isEqualTo("박서준");
            assertThat(current.assigneeTeam()).isEqualTo("프론트엔드팀");
            assertThat(current.status()).isEqualTo("DONE");
            assertThat(current.dueDate()).isEqualTo(LocalDate.of(2026,11,21));
        });
        assertThat(result.sources()).singleElement().satisfies(source -> assertThat(source.quote()).isNull());
        assertThat(questions.findById(result.questionId())).isPresent();
        assertThat(tasks.count()).isEqualTo(1);
    }
}
