package com.meetodo.member.registration;

import java.util.Optional;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.*;
import org.springframework.data.repository.query.Param;

public interface MemberRegistrationRepository extends JpaRepository<MemberRegistrationRequest,Long> {
    Optional<MemberRegistrationRequest> findByPendingSlackUserId(String user);
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select r from MemberRegistrationRequest r where r.id=:id")
    Optional<MemberRegistrationRequest> findLockedById(@Param("id") Long id);
}
