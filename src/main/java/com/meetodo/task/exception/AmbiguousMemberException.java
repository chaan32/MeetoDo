package com.meetodo.task.exception;

import com.meetodo.member.Member;
import java.util.List;

public class AmbiguousMemberException extends RuntimeException {
    private final List<Candidate> candidates;
    public AmbiguousMemberException(List<Member> members) {
        super("여러 팀원이 같은 이름을 사용합니다. assigneeId로 지정해 주세요.");
        this.candidates = members.stream().map(m -> new Candidate(m.getId(), m.getName(), m.getTeam())).toList();
    }
    public List<Candidate> getCandidates() { return candidates; }
    public record Candidate(Long id, String name, String team) {}
}
