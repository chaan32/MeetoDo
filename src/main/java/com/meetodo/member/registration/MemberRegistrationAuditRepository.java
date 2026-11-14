package com.meetodo.member.registration;

import org.springframework.data.jpa.repository.JpaRepository;

public interface MemberRegistrationAuditRepository extends JpaRepository<MemberRegistrationAudit,Long> { }
