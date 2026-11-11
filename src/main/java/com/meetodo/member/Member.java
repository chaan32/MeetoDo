package com.meetodo.member;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

@Entity
@Table(name = "members")
public class Member {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, length = 80)
    private String name;

    @Column(nullable = false, length = 80)
    private String team;

    @Column(name = "slack_user_id", length = 80)
    private String slackUserId;

    protected Member() {
    }

    public Member(String name, String slackUserId) {
        this(name, "미지정", slackUserId);
    }

    public Member(String name, String team, String slackUserId) {
        this.name = name;
        this.team = team;
        this.slackUserId = slackUserId;
    }

    public Long getId() { return id; }
    public String getName() { return name; }
    public String getTeam() { return team; }
    public String getSlackUserId() { return slackUserId; }
}
