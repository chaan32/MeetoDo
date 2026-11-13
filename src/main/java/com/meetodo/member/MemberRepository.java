package com.meetodo.member;

import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface MemberRepository extends JpaRepository<Member, Long> {
    @Query("select m from Member m where concat(m.familyName, m.givenName) = :name order by m.id")
    List<Member> findAllByNameOrderByIdAsc(@Param("name") String name);
    List<Member> findAllByGivenNameOrderByIdAsc(String givenName);
    boolean existsBySlackUserId(String slackUserId);
    boolean existsBySlackUserIdAndIdNot(String slackUserId, Long id);
    java.util.Optional<Member> findBySlackUserId(String slackUserId);
    java.util.Optional<Member> findBySlackUserIdAndActiveTrue(String slackUserId);
}
