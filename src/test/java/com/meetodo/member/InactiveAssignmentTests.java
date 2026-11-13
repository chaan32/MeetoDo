package com.meetodo.member;
import com.meetodo.task.*;
import com.meetodo.task.dto.CreateTaskRequest;
import com.meetodo.task.exception.ResourceNotFoundException;
import com.meetodo.meeting.*;
import com.meetodo.tools.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;
import java.time.*;
import java.util.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;
class InactiveAssignmentTests {
 @Test void directApiCannotAssignInactiveMemberById() {
  // 이름 조회를 거치지 않는 ID 지정도 비활성 담당자로 새 배정을 할 수 없다.
  var members=mock(MemberRepository.class);var tasks=mock(TaskRepository.class);
  var member=new Member("김","민수","백엔드팀","UINACTIVE"); member.deactivate();
  when(members.findById(1L)).thenReturn(Optional.of(member));when(tasks.save(any())).thenAnswer(c->c.getArgument(0));
  var service=new TaskService(tasks,members,mock(TaskMentionService.class),Clock.systemUTC());
  assertThatThrownBy(()->service.create(new CreateTaskRequest("API 정리",1L,null,null))).isInstanceOf(ResourceNotFoundException.class);
  verify(tasks,never()).save(any());
 }
 @Test void toolRejectsPreviouslyAuthorizedMemberAfterDeactivation() {
  // 이전 단일 조회가 성공했어도 현재 비활성 상태면 도구 배정을 차단한다.
  var members=mock(MemberRepository.class);var tasks=mock(TaskRepository.class);var logs=mock(ToolCallLogRepository.class);
  var member=new Member("김","민수","백엔드팀","UINACTIVE");ReflectionTestUtils.setField(member,"id",1L); member.deactivate();
  var meeting=Meeting.create("민수는 API를 정리한다",MeetingSource.API_TEXT);ReflectionTestUtils.setField(meeting,"id",1L);
  when(members.findById(1L)).thenReturn(Optional.of(member));when(tasks.save(any())).thenAnswer(c->c.getArgument(0));
  when(logs.findAllByMeetingIdOrderByCallOrderAsc(1L)).thenReturn(List.of(ToolCallLog.success(meeting,"findMember","{}","{\"data\":\"[{\\\"id\\\":1}]\"}",0,1)));
  var mentions=mock(TaskMentionService.class);var execution=new ToolExecutionService(logs,new ObjectMapper());
  var tool=new CreateTaskTool(members,new TaskService(tasks,members,mentions,Clock.systemUTC()),mentions,execution,Clock.systemUTC());
  assertThat(tool.createTask("API 정리","member:1","",meeting).errorCode()).isEqualTo("INVALID_ASSIGNEE");
  verify(tasks,never()).save(any());
 }
}
