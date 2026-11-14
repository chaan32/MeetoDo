package com.meetodo.rag.question;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import java.util.regex.Pattern;

public record VerificationDiagnostics(String reason,String modelOutput,boolean outputTruncated,List<EvidenceVerifier.Check> checks) {
    private static final String REDACTED="[REDACTED]";
    private static final Pattern JSON_STRING=Pattern.compile("\"(?:\\\\.|[^\"\\\\])*\"");
    private static final Pattern SECRET=Pattern.compile("(?i)(?:xox[baprs]|xapp|sk)-[A-Za-z0-9_-]+");
    private static final Pattern URL=Pattern.compile("(?i)https?://[^\\s\"<>\\\\]+");
    private static final Pattern BEARER=Pattern.compile("(?i)Bearer\\s+[A-Za-z0-9._~+/-]+=*");
    private static final Pattern AUTH=Pattern.compile("(?i)([\"']?(?:authorization|api[_-]?key)[\"']?\\s*[:=]\\s*)(?:\"[^\"\\r\\n]*\"|'[^'\\r\\n]*'|[^\\s,}]+)");

    static VerificationDiagnostics capture(String reason,String output,List<EvidenceVerifier.Check> checks,
                                           List<String> transcripts,ObjectMapper json) {
        String safe=sanitize(output,transcripts,json);
        boolean truncated=safe!=null && safe.length()>4000;
        return new VerificationDiagnostics(reason,limit(safe,4000),truncated,checks.stream().map(check ->
                new EvidenceVerifier.Check(check.index(),limit(sanitize(check.documentId(),transcripts,json),200),check.reason())).toList());
    }
    private static String sanitize(String value,List<String> transcripts,ObjectMapper json) {
        if (value==null) return null;
        var literals=JSON_STRING.matcher(value);
        StringBuilder protectedText=new StringBuilder();
        while (literals.find()) {
            String replacement=literals.group();
            try {
                String decoded=json.readValue(replacement,String.class);
                String redacted=redact(decoded,transcripts,json);
                if (!decoded.equals(redacted)) replacement=json.writeValueAsString(redacted);
            } catch (JsonProcessingException ignored) { /* Malformed literals still receive plain-text redaction. */ }
            literals.appendReplacement(protectedText,java.util.regex.Matcher.quoteReplacement(replacement));
        }
        literals.appendTail(protectedText);
        return redact(protectedText.toString(),transcripts,json);
    }
    private static String redact(String value,List<String> transcripts,ObjectMapper json) {
        for (String transcript:transcripts) {
            if (transcript==null || transcript.isBlank()) continue;
            value=value.replace(transcript,REDACTED);
            try {
                String escaped=json.writeValueAsString(transcript);
                value=value.replace(escaped.substring(1,escaped.length()-1),REDACTED);
            } catch (JsonProcessingException ignored) { return REDACTED; }
        }
        value=URL.matcher(value).replaceAll(REDACTED);
        value=SECRET.matcher(value).replaceAll(REDACTED);
        value=BEARER.matcher(value).replaceAll("Bearer "+REDACTED);
        return AUTH.matcher(value).replaceAll("$1"+REDACTED);
    }
    private static String limit(String value,int maximum) {
        if (value==null || value.length()<=maximum) return value;
        int end=maximum;
        if (Character.isHighSurrogate(value.charAt(end-1))) end--;
        return value.substring(0,end);
    }
}
