package com.meetodo.slack;

import com.meetodo.task.*;
import com.meetodo.member.*;
import com.meetodo.member.dto.MemberRequest;
import com.meetodo.task.dto.CreateTaskRequest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import static org.mockito.Mockito.*;

@SpringBootTest(properties={"spring.datasource.url=jdbc:h2:mem:home-refresh-test;DB_CLOSE_DELAY=-1","MEETODO_ADMIN_SLACK_IDS=UADMIN"})
@ActiveProfiles("test")
class SlackHomeRefreshListenerTests {
    @Autowired TaskService tasks;
    @Autowired MemberService members;
    @Autowired PlatformTransactionManager manager;
    @MockitoBean SlackHomeService homes;

    // 직접 생성도 DB 커밋 이후에만 홈을 갱신한다.
    @Test void createdTaskRefreshesHomeOnlyAfterCommit() {
        Long id = new TransactionTemplate(manager).execute(status -> {
            var task=tasks.create(new CreateTaskRequest("홈 갱신 신규 업무",null,null));
            verifyNoInteractions(homes);
            return task.id();
        });
        verify(homes).refreshTask(id,false);
    }

    // 롤백된 생성은 홈에 존재하지 않는 업무를 표시하지 않는다.
    @Test void rolledBackTaskCreationDoesNotRefreshHome() {
        new TransactionTemplate(manager).executeWithoutResult(status -> {
            tasks.create(new CreateTaskRequest("홈 갱신 롤백 업무",null,null));
            status.setRollbackOnly();
        });
        verifyNoInteractions(homes);
    }

    // 팀원 연결 변경은 이미 열려 있는 홈들을 커밋 이후 다시 그린다.
    @Test void memberRegistrationRefreshesOpenHomesAfterCommit() {
        new TransactionTemplate(manager).executeWithoutResult(status -> {
            members.create(new MemberRequest("남궁","민수","백엔드팀","UNEWMEMBER"),"UADMIN");
            verifyNoInteractions(homes);
        });
        verify(homes).refreshAll();
    }
    @org.springframework.beans.factory.annotation.Autowired org.springframework.context.ApplicationEventPublisher events;

    @Test void reassignmentRefreshesOldNewAndUnassignedHomeWatchersAfterCommit() {
        // 재배정은 이전·새 담당자뿐 아니라 공유 미정 목록을 보던 열린 Home까지 갱신한다.
        new TransactionTemplate(manager).executeWithoutResult(status -> {
            events.publishEvent(new TaskReassignedEvent(7L, 1L, "UOLD", 2L, "UADMIN"));
            verifyNoInteractions(homes);
        });
        verify(homes).refreshAll();
    }

    @Test void rolledBackReassignmentDoesNotRefreshAnyHome() {
        // 롤백된 담당 변경은 어느 사용자 Home에도 발행하지 않는다.
        new TransactionTemplate(manager).executeWithoutResult(status -> {
            events.publishEvent(new TaskReassignedEvent(7L, 1L, "UOLD", 2L, "UADMIN"));
            status.setRollbackOnly();
        });
        verifyNoInteractions(homes);
    }

}
