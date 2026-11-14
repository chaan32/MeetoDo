package com.meetodo.member.registration;

import jakarta.persistence.*;
import java.time.Instant;

@Entity
@Table(name="member_registration_requests", uniqueConstraints=@UniqueConstraint(name="uk_registration_pending_user", columnNames="pending_slack_user_id"))
public class MemberRegistrationRequest {
    @Id @GeneratedValue(strategy=GenerationType.IDENTITY) private Long id;
    @Column(nullable=false,length=80) private String slackUserId;
    @Column(length=80) private String pendingSlackUserId;
    @Column(nullable=false,length=80) private String familyName;
    @Column(nullable=false,length=80) private String givenName;
    @Column(nullable=false,length=80) private String team;
    @Enumerated(EnumType.STRING) @Column(nullable=false,length=20) private RegistrationStatus status;
    @Column(length=80) private String processedBySlackUserId;
    @Column(length=300) private String rejectionReason;
    private Long memberId;
    @Column(nullable=false) private boolean duplicateName;
    @Column(nullable=false) private Instant createdAt;
    private Instant processedAt;

    protected MemberRegistrationRequest() { }
    MemberRegistrationRequest(String user, String family, String given, String team, boolean duplicateName, Instant now) {
        slackUserId=user; pendingSlackUserId=user; familyName=family; givenName=given; this.team=team;
        status=RegistrationStatus.PENDING; createdAt=now;
        this.duplicateName=duplicateName;
    }
    void approve(String actor, String family, String given, String team, Long member, boolean duplicateName, Instant now) {
        familyName=family; givenName=given; this.team=team; memberId=member;
        this.duplicateName=duplicateName;
        finish(RegistrationStatus.APPROVED,actor,now);
    }
    void reject(String actor,String reason,Instant now) {
        rejectionReason=reason; finish(RegistrationStatus.REJECTED,actor,now);
    }
    private void finish(RegistrationStatus next,String actor,Instant now) {
        status=next; processedBySlackUserId=actor; processedAt=now; pendingSlackUserId=null;
    }
    public Long getId() { return id; }
    public String getSlackUserId() { return slackUserId; }
    public String getFamilyName() { return familyName; }
    public String getGivenName() { return givenName; }
    public String getTeam() { return team; }
    public RegistrationStatus getStatus() { return status; }
    public String getProcessedBySlackUserId() { return processedBySlackUserId; }
    public String getRejectionReason() { return rejectionReason; }
    public Long getMemberId() { return memberId; }
    public boolean isDuplicateName() { return duplicateName; }
}
