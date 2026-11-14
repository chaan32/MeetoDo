package com.meetodo.member.registration;

import jakarta.persistence.*;
import java.time.Instant;

@Entity
@Table(name="member_registration_events")
public class MemberRegistrationAudit {
    @Id @GeneratedValue(strategy=GenerationType.IDENTITY) private Long id;
    @ManyToOne(optional=false,fetch=FetchType.LAZY) @JoinColumn(name="request_id",nullable=false) private MemberRegistrationRequest request;
    @Column(nullable=false,length=20) private String type;
    @Column(nullable=false,length=80) private String bySlackUserId;
    @Column(columnDefinition="text") private String beforeForm;
    @Column(columnDefinition="text") private String afterForm;
    @Column(nullable=false) private Instant at;
    protected MemberRegistrationAudit() { }
    MemberRegistrationAudit(MemberRegistrationRequest request,String type,String actor,String before,String after,Instant now) {
        this.request=request; this.type=type; bySlackUserId=actor; beforeForm=before; afterForm=after; at=now;
    }
}
