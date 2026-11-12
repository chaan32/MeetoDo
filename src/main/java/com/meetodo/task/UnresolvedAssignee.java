package com.meetodo.task;

import java.util.List;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.persistence.AttributeConverter;
import jakarta.persistence.Converter;

/** Server-owned snapshot of the lookup that left an assignee unresolved. */
public record UnresolvedAssignee(String reason, String requestedAssigneeName, String requestedTeam,
                                 List<Candidate> candidates, int omittedCandidateCount) {
    public UnresolvedAssignee {
        requestedAssigneeName = bounded(requestedAssigneeName);
        requestedTeam = bounded(requestedTeam);
        candidates = candidates == null ? List.of() : List.copyOf(candidates);
        omittedCandidateCount = Math.max(0, omittedCandidateCount) + Math.max(0, candidates.size() - 20);
        candidates = candidates.stream().limit(20).map(c -> new Candidate(c.id(), bounded(c.name()), bounded(c.team()))).toList();
    }
    public record Candidate(Long id, String name, String team) {}

    private static String bounded(String value) {
        if (value == null) return null;
        String safe = value.replaceAll("[\\p{Cntrl}\\p{Cf}]", " ");
        return safe.codePointCount(0, safe.length()) <= 100 ? safe : safe.substring(0, safe.offsetByCodePoints(0, 100));
    }

    public String warning() {
        String person = requestedAssigneeName == null ? "" : requestedAssigneeName;
        return switch (reason) {
            case "AMBIGUOUS" -> "동명이인 확인 필요: \"" + person + "\" (후보: "
                    + candidates.stream().map(Candidate::team).filter(java.util.Objects::nonNull).distinct()
                    .collect(java.util.stream.Collectors.joining(", "))
                    + (omittedCandidateCount > 0 ? " 외 " + omittedCandidateCount + "명" : "") + ")";
            case "NOT_FOUND" -> "등록되지 않은 담당자: \"" + person + "\"";
            case "TEAM_MISMATCH" -> "팀 불일치 확인 필요: \"" + (requestedTeam == null ? "" : requestedTeam + " ") + person
                    + "\" (등록: " + candidates.stream().map(c -> c.team() + " " + c.name()).distinct()
                    .collect(java.util.stream.Collectors.joining(", "))
                    + (omittedCandidateCount > 0 ? " 외 " + omittedCandidateCount + "명" : "") + ")";
            default -> "";
        };
    }

    @Converter
    public static class JsonConverter implements AttributeConverter<UnresolvedAssignee, String> {
        private static final ObjectMapper MAPPER = new ObjectMapper();
        @Override public String convertToDatabaseColumn(UnresolvedAssignee value) {
            if (value == null) return null;
            try { return MAPPER.writeValueAsString(value); }
            catch (Exception e) { throw new IllegalStateException("Cannot serialize unresolved assignee", e); }
        }
        @Override public UnresolvedAssignee convertToEntityAttribute(String value) {
            if (value == null) return null;
            try { return MAPPER.readValue(value, UnresolvedAssignee.class); }
            catch (Exception e) { throw new IllegalStateException("Cannot read unresolved assignee", e); }
        }
    }
}
