package com.meetodo.rag.question;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.meetodo.rag.index.RagSourceSnapshot;
import java.io.IOException;
import java.util.*;

public class EvidenceVerifier {
    public record Check(int index,String documentId,String reason) { }
    static class InvalidEvidence extends IllegalArgumentException {
        private final List<Check> checks;
        InvalidEvidence() { this("RETRIEVED_SOURCE_INVALID",List.of()); }
        InvalidEvidence(String reason,List<Check> checks) { super(reason); this.checks=List.copyOf(checks); }
        List<Check> checks() { return checks; }
    }
    public record Evidence(String documentId, Long meetingId, String quote, Long taskId) { }
    private final ObjectMapper json;
    public EvidenceVerifier(ObjectMapper json) { this.json=json; }

    public List<Evidence> verify(String content,List<RagSourceSnapshot> sources) {
        JsonNode root=parse(content);
        JsonNode items=root.path("evidence");
        String reason=null;
        if (!root.has("found")) reason="FOUND_MISSING";
        else if (!root.path("found").isBoolean()) reason="FOUND_INVALID";
        else if (!items.isArray()) reason="EVIDENCE_MISSING";
        else if (items.size()>3) reason="TOO_MANY_EVIDENCE";
        else if (!root.path("found").booleanValue() && !items.isEmpty()) reason="EVIDENCE_WITH_FOUND_FALSE";
        else if (root.path("found").booleanValue() && items.isEmpty()) reason="EVIDENCE_EMPTY";
        List<Check> checks=new ArrayList<>();
        List<Evidence> verified=new ArrayList<>();
        if (items.isArray()) {
            for (int index=0;index<items.size();index++) {
                JsonNode item=items.get(index);
                String itemReason=check(item,sources);
                String id=item.path("documentId").isTextual() ? item.path("documentId").textValue() : null;
                checks.add(new Check(index,id,itemReason));
                if ("OK".equals(itemReason)) {
                    JsonNode task=item.get("taskId");
                    verified.add(new Evidence(id,item.path("meetingId").longValue(),item.path("quote").textValue(),
                            task==null || task.isNull() ? null : task.longValue()));
                } else if (reason==null) reason=itemReason;
            }
        }
        // Diagnose later items without changing the first-failure, reject-all verdict.
        if (reason!=null) throw new InvalidEvidence(reason,checks);
        return List.copyOf(verified);
    }
    private JsonNode parse(String content) {
        if (content==null || content.isBlank()) throw invalid("NOT_JSON");
        // Classify wrappers only; never remove them or parse their enclosed JSON.
        if (content.stripLeading().startsWith("```")) throw invalid("TRAILING_CONTENT");
        try (var parser=json.createParser(content)) {
            JsonNode root=json.readTree(parser);
            if (root==null) throw invalid("NOT_JSON");
            try { if (parser.nextToken()!=null) throw invalid("TRAILING_CONTENT"); }
            catch (IOException trailing) { throw invalid("TRAILING_CONTENT"); }
            return root;
        } catch (IOException malformed) { throw invalid("NOT_JSON"); }
    }
    private String check(JsonNode item,List<RagSourceSnapshot> sources) {
        if (!item.path("documentId").isTextual()) return "DOCUMENT_ID_INVALID";
        if (!item.path("meetingId").isIntegralNumber() || !item.path("meetingId").canConvertToLong()) return "MEETING_ID_INVALID";
        if (!item.path("quote").isTextual()) return "QUOTE_INVALID";
        String id=item.path("documentId").textValue();
        List<RagSourceSnapshot> matching=sources.stream().filter(s->s.documentId().equals(id)).toList();
        if (matching.isEmpty()) return "UNKNOWN_DOCUMENT";
        RagSourceSnapshot source=matching.stream().filter(s->s.meetingId().equals(item.path("meetingId").longValue())).findFirst().orElse(null);
        if (source==null) return "MEETING_ID_MISMATCH";
        String quote=whitespace(item.path("quote").textValue());
        if (quote.isEmpty() || !whitespace(source.text()).contains(quote)) return "QUOTE_NOT_IN_SOURCE";
        JsonNode task=item.get("taskId");
        if (task!=null && !task.isNull() && (!task.isIntegralNumber() || !task.canConvertToLong())) return "TASK_ID_INVALID";
        Long taskId=task==null || task.isNull() ? null : task.longValue();
        if ("TASK".equals(source.kind())) {
            if (taskId==null) return "TASK_ID_MISSING";
            if (!Objects.equals(source.taskId(),taskId)) return "TASK_ID_MISMATCH";
        } else {
            if (!"MEETING".equals(source.kind())) return "SOURCE_KIND_INVALID";
            if (taskId!=null) return "TASK_ID_ON_MEETING";
        }
        return "OK";
    }
    private InvalidEvidence invalid(String reason) { return new InvalidEvidence(reason,List.of()); }
    static String whitespace(String text) { return text==null ? "" : text.replaceAll("(?U)\\s+"," ").strip(); }
}
