package com.meetodo.member;

import com.meetodo.member.dto.MemberRequest;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.test.util.ReflectionTestUtils;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class MemberServiceTests {
    @Test void databaseIdentityConflictReturnsSafeDomainError() {
        // 선행 조회 이후 DB 유니크 경쟁이 발생해도 공급자 예외를 노출하지 않는다.
        var members=mock(MemberRepository.class);
        when(members.saveAndFlush(any())).thenThrow(new DataIntegrityViolationException("private database payload"));
        var service=new MemberService(members,new AdminAuthorization("UADMIN"));
        assertThatThrownBy(() -> service.create(new MemberRequest("김","민수","백엔드팀","UMEMBER"),"UADMIN"))
                .isInstanceOfSatisfying(MemberManagementException.class,error -> {
                    assertThat(error.getCode()).isEqualTo("MEMBER_SLACK_USER_CONFLICT");
                    assertThat(error.getMessage()).doesNotContain("private", "database");
                    assertThat(error.getCause()).isNull();
                });
    }
    @Test void deactivationDoesNotDeleteIdentity() {
        // 퇴사 처리는 행을 삭제하지 않고 active만 비활성화한다.
        var members=mock(MemberRepository.class); var member=new Member("김","민수","백엔드팀","UMEMBER");
        ReflectionTestUtils.setField(member,"id",1L);
        when(members.findById(1L)).thenReturn(Optional.of(member));
        var response=new MemberService(members,new AdminAuthorization("UADMIN")).deactivate(1L,"UADMIN");
        assertThat(response.active()).isFalse(); assertThat(member.isActive()).isFalse();
        verify(members,never()).delete(any());
    }
    @Test void memberUpdateDoesNotReactivateDeactivatedIdentity() {
        // 일반 정보 변경이 비활성 팀원의 상태를 몰래 되돌리지 않는다.
        var members=mock(MemberRepository.class); var member=new Member("김","민수","백엔드팀","UMEMBER");
        ReflectionTestUtils.setField(member,"id",1L); member.deactivate();
        when(members.findById(1L)).thenReturn(Optional.of(member)); when(members.saveAndFlush(any())).thenAnswer(call -> call.getArgument(0));
        var response=new MemberService(members,new AdminAuthorization("UADMIN"))
                .update(1L,new MemberRequest("남궁","민수","디자인팀","UUPDATED"),"UADMIN");
        assertThat(response.name()).isEqualTo("남궁민수"); assertThat(response.active()).isFalse();
    }
}
