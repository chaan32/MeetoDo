package com.meetodo.member;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

@Entity
@Table(name = "members", uniqueConstraints = @jakarta.persistence.UniqueConstraint(name = "uk_members_slack_user_id", columnNames = "slack_user_id"))
public class Member {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, length = 80)
    private String familyName;

    @Column(nullable = false, length = 80)
    private String givenName;

    @Column(nullable = false, length = 80)
    private String team;

    @Column(name = "slack_user_id", length = 80)
    private String slackUserId;

    @Column(nullable = false)
    @org.hibernate.annotations.ColumnDefault("true")
    private boolean active = true;

    protected Member() {
    }

    public Member(String familyName, String givenName, String team, String slackUserId) {
        this.familyName = java.util.Objects.requireNonNull(familyName);
        this.givenName = java.util.Objects.requireNonNull(givenName);
        this.team = team;
        this.slackUserId = slackUserId;
    }

    public void changeSlackUserId(String slackUserId) { this.slackUserId = slackUserId; }

    public void update(String familyName, String givenName, String team, String slackUserId) {
        this.familyName = familyName; this.givenName = givenName;
        this.team = team; this.slackUserId = slackUserId;
    }

    public void deactivate() { this.active = false; }
    public boolean isActive() { return active; }

    public Long getId() { return id; }
    public String getFamilyName() { return familyName; }
    public String getGivenName() { return givenName; }
    public String getFullName() { return familyName + givenName; }
    public String getName() { return getFullName(); }
    public String getTeam() { return team; }
    public String getSlackUserId() { return slackUserId; }
}
