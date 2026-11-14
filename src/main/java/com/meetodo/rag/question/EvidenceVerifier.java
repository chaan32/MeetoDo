package com.meetodo.rag.question;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.meetodo.rag.index.RagSourceSnapshot;
import java.util.*;

public class EvidenceVerifier {
    static class InvalidEvidence extends IllegalArgumentException {
        InvalidEvidence() { super("Unverified evidence"); }
    }
    public record Evidence(String documentId, Long meetingId, String quote, Long taskId) { }
    private final ObjectMapper json;
    public EvidenceVerifier(ObjectMapper json) { this.json = json; }

    public List<Evidence> verify(String content, List<RagSourceSnapshot> sources) {
        try {
            JsonNode root;
            if (content == null) throw invalid();
            try (var parser = json.createParser(content)) {
                root = json.readTree(parser);
                if (parser.nextToken() != null) throw invalid();
            }
            if (root == null || !root.path("found").isBoolean()) throw invalid();
            JsonNode evidence = root.path("evidence");
            if (!evidence.isArray() || evidence.size() > 3) throw invalid();
            if (!root.path("found").booleanValue()) {
                if (!evidence.isEmpty()) throw invalid();
                return List.of();
            }
            if (evidence.isEmpty()) throw invalid();
            List<Evidence> verified = new ArrayList<>();
            for (JsonNode item : evidence) {
                if (!item.path("documentId").isTextual() || (!item.path("meetingId").isIntegralNumber() || !item.path("meetingId").canConvertToLong())
                        || !item.path("quote").isTextual()) throw invalid();
                String id = item.path("documentId").textValue();
                long meetingId = item.path("meetingId").longValue();
                RagSourceSnapshot source = sources.stream().filter(value -> value.documentId().equals(id)
                        && value.meetingId().equals(meetingId)).findFirst().orElseThrow(EvidenceVerifier::invalid);
                String quote = item.path("quote").textValue();
                String normalized = whitespace(quote);
                if (normalized.isEmpty() || !whitespace(source.text()).contains(normalized)) throw invalid();
                JsonNode task = item.get("taskId");
                if (task != null && !task.isNull() && (!task.isIntegralNumber() || !task.canConvertToLong())) throw invalid();
                Long taskId = task == null || task.isNull() ? null : task.longValue();
                if ("TASK".equals(source.kind())) {
                    if (!Objects.equals(source.taskId(), taskId) || taskId == null) throw invalid();
                } else if (!"MEETING".equals(source.kind()) || taskId != null) throw invalid();
                verified.add(new Evidence(id, meetingId, quote, taskId));
            }
            return List.copyOf(verified);
        } catch (java.io.IOException | IllegalArgumentException ignored) { throw invalid(); }
    }
    static String whitespace(String text) { return text == null ? "" : text.replaceAll("(?U)\\s+", " ").strip(); }
    private static InvalidEvidence invalid() { return new InvalidEvidence(); }
}
