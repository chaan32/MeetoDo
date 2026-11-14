package com.meetodo.member.registration;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.meetodo.member.*;
import jakarta.validation.Validation;
import java.time.Clock;
import org.junit.jupiter.api.Test;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.dao.DataIntegrityViolationException;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class MemberRegistrationRaceTests {
    @Test void uniquePendingRaceThrowsSafeDomainConflictWithoutRecoveryRead() {
        // 실패한 저장 트랜잭션에서 재조회하지 않고 안전한 오류를 밖으로 전달한다.
        var requests=mock(MemberRegistrationRepository.class);
        when(requests.saveAndFlush(any())).thenThrow(new DataIntegrityViolationException("private database details"));
        try (var factory=Validation.buildDefaultValidatorFactory()) {
            var service=new MemberRegistrationService(requests,mock(MemberRegistrationAuditRepository.class),
                    mock(MemberRepository.class),mock(MemberService.class),new AdminAuthorization("UADMIN"),
                    factory.getValidator(),new ObjectMapper(),mock(ApplicationEventPublisher.class),Clock.systemUTC());
            assertThatThrownBy(() -> service.submit("URACE","김","민수","개발팀"))
                    .isInstanceOfSatisfying(MemberManagementException.class,error -> {
                        assertThat(error.getCode()).isEqualTo("REGISTRATION_PENDING_CONFLICT");
                        assertThat(error.getMessage()).doesNotContain("private", "database");
                        assertThat(error.getCause()).isNull();
                    });
            verify(requests,times(1)).findByPendingSlackUserId("URACE");
        }
    }
}
