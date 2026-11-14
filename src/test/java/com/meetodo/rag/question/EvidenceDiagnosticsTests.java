package com.meetodo.rag.question;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.meetodo.rag.index.RagSourceSnapshot;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;
import static org.assertj.core.api.Assertions.*;

class EvidenceDiagnosticsTests {
    final ObjectMapper json=new ObjectMapper();
    final EvidenceVerifier verifier=new EvidenceVerifier(json);
    final RagSourceSnapshot meeting=new RagSourceSnapshot("doc","MEETING",1L,null,0,"지영은 문서를 작성한다.",Instant.EPOCH,"","ns");
    final RagSourceSnapshot task=new RagSourceSnapshot("task","TASK",1L,9L,0,"문서 작성",Instant.EPOCH,"","ns");
    String item(String id,int meetingId,String quote,String extra) {
        return "{\"documentId\":\""+id+"\",\"meetingId\":"+meetingId+",\"quote\":\""+quote+"\""+extra+"}";
    }
    String body(String items) { return "{\"found\":true,\"evidence\":["+items+"]}"; }
    EvidenceVerifier.InvalidEvidence reject(String content,String reason) {
        var error=catchThrowableOfType(()->verifier.verify(content,List.of(meeting,task)),EvidenceVerifier.InvalidEvidence.class);
        assertThat(error).isNotNull().hasMessage(reason); return error;
    }
    @Test void nonJsonReportsNotJson() { // 비JSON 출력은 내용 추측 없이 구분한다.
        reject("설명입니다","NOT_JSON");
    }
    @Test void codeFenceIsStillRejected() { // 코드 블록 제거는 이번 변경에 포함하지 않는다.
        reject("```json\n"+body(item("doc",1,"지영은 문서를 작성한다.",""))+"\n```","TRAILING_CONTENT");
    }
    @Test void extraSentenceIsStillRejected() { // 유효 JSON 뒤 문장도 계속 거절한다.
        reject(body(item("doc",1,"지영은 문서를 작성한다.",""))+" 추가 설명","TRAILING_CONTENT");
    }
    @Test void missingFoundReportsMissing() { // 필수 found 누락을 별도로 진단한다.
        reject("{\"evidence\":[]}","FOUND_MISSING");
    }
    @Test void foundTypeReportsInvalid() { // found 문자열은 boolean으로 강제 변환하지 않는다.
        reject("{\"found\":\"true\",\"evidence\":[]}","FOUND_INVALID");
    }
    @Test void tooManyEvidenceKeepsGlobalReason() { // 항목이 모두 맞아도 기존 최대3개 규칙은 유지한다.
        String good=item("doc",1,"지영은 문서를 작성한다.","");
        var error=reject(body(String.join(",",good,good,good,good)),"TOO_MANY_EVIDENCE");
        assertThat(json.valueToTree(ReflectionTestUtils.getField(error,"checks")).size()).isEqualTo(4);
    }
    @Test void unknownDocumentReportsUnknown() { // 검색 밖 문서 ID를 구분한다.
        reject(body(item("other",1,"지영은 문서를 작성한다.","")),"UNKNOWN_DOCUMENT");
    }
    @Test void wrongMeetingReportsMismatch() { // 알려진 문서에 다른 회의 ID를 붙인 경우를 구분한다.
        reject(body(item("doc",2,"지영은 문서를 작성한다.","")),"MEETING_ID_MISMATCH");
    }
    @Test void wrongQuoteReportsMismatch() { // 문장 의미가 비슷해도 인용 부분 일치만 허용한다.
        reject(body(item("doc",1,"민수는 문서를 작성한다.","")),"QUOTE_NOT_IN_SOURCE");
    }
    @Test void taskWithoutIdUsesCanonicalTaskId() { // 실제 관측처럼 세 필드만 출력하면 검증된 source의 ID를 채운다.
        var result=verifier.verify(body(item("task",1,"문서 작성","")),List.of(task));
        assertThat(result).singleElement().satisfies(evidence -> assertThat(evidence.taskId()).isEqualTo(9L));
    }
    @Test void nullTaskIdUsesCanonicalTaskId() { // 명시적 null도 검증된 TASK 문서의 서버 ID로 채운다.
        var result=verifier.verify(body(item("task",1,"문서 작성",",\"taskId\":null")),List.of(task));
        assertThat(result).singleElement().satisfies(evidence -> assertThat(evidence.taskId()).isEqualTo(9L));
    }
    @Test void matchingExplicitTaskIdRemainsAccepted() { // 이전 형식의 정확한 taskId도 계속 허용한다.
        var result=verifier.verify(body(item("task",1,"문서 작성",",\"taskId\":9")),List.of(task));
        assertThat(result).singleElement().satisfies(evidence -> assertThat(evidence.taskId()).isEqualTo(9L));
    }
    @Test void omittedTaskIdDoesNotBypassWholeAnswerRejection() { // ID 보완 가능 근거가 있어도 다른 인용이 틀리면 전체를 거절한다.
        var error=reject(body(item("task",1,"문서 작성","")+","+item("doc",1,"틀린 인용","")),"QUOTE_NOT_IN_SOURCE");
        var checks=json.valueToTree(ReflectionTestUtils.getField(error,"checks"));
        assertThat(checks.get(0).path("reason").asText()).isEqualTo("OK");
        assertThat(checks.get(1).path("reason").asText()).isEqualTo("QUOTE_NOT_IN_SOURCE");
    }
    @Test void missingCanonicalTaskIdIsNotInvented() { // 서버 원본에도 ID가 없으면 추측해서 채우지 않는다.
        var invalid=new RagSourceSnapshot("task","TASK",1L,null,0,"문서 작성",Instant.EPOCH,"","ns");
        assertThatThrownBy(()->verifier.verify(body(item("task",1,"문서 작성","")),List.of(invalid)))
                .isInstanceOf(EvidenceVerifier.InvalidEvidence.class);
    }
    @Test void taskWithWrongIdReportsMismatch() { // 검색 문서의 taskId와 다른 값은 거절한다.
        reject(body(item("task",1,"문서 작성",",\"taskId\":8")),"TASK_ID_MISMATCH");
    }
    @Test void overflowTaskIdReportsInvalid() { // long overflow를 ID로 인정하지 않는다.
        reject(body(item("task",1,"문서 작성",",\"taskId\":18446744073709551617")),"TASK_ID_INVALID");
    }
    @Test void taskIdOnMeetingReportsForbidden() { // 회의 본문 근거에 taskId를 붙이지 않는다.
        reject(body(item("doc",1,"지영은 문서를 작성한다.",",\"taskId\":9")),"TASK_ID_ON_MEETING");
    }
    @Test void firstFailureRejectsAllButLaterOkIsRecorded() { // 뒤의 유효 항목도 진단하지만 부분 답변은 하지 않는다.
        var error=reject(body(item("doc",1,"틀린 인용","")+","+item("task",1,"문서 작성",",\"taskId\":9")),"QUOTE_NOT_IN_SOURCE");
        var checks=json.valueToTree(ReflectionTestUtils.getField(error,"checks"));
        assertThat(checks.size()).isEqualTo(2);
        assertThat(checks.get(0).path("reason").asText()).isEqualTo("QUOTE_NOT_IN_SOURCE");
        assertThat(checks.get(1).path("reason").asText()).isEqualTo("OK");
        assertThat(checks.get(1).path("index").asInt()).isEqualTo(1);
    }
    @Test void parsedValidItemBeforeInvalidItemRemainsInDiagnostics() { // 앞 OK 항목도 실패와 함께 남긴다.
        var error=reject(body(item("task",1,"문서 작성",",\"taskId\":9")+","+item("other",1,"없는 문서","")),"UNKNOWN_DOCUMENT");
        var checks=json.valueToTree(ReflectionTestUtils.getField(error,"checks"));
        assertThat(checks.get(0).path("reason").asText()).isEqualTo("OK");
        assertThat(checks.get(1).path("reason").asText()).isEqualTo("UNKNOWN_DOCUMENT");
    }
    @Test void invalidJsonHasNoItemResults() { // 파싱 실패에는 부분 JSON을 분석해 항목 결과를 만들지 않는다.
        var error=reject("{\"found\":true,\"evidence\":[", "NOT_JSON");
        assertThat(json.valueToTree(ReflectionTestUtils.getField(error,"checks")).size()).isZero();
    }
    @Test void foundFalseWithEvidenceKeepsGlobalFailureAndAllChecks() { // found=false도 근거가 있으면 계속 거절하되 각 항목을 진단한다.
        var error=reject(body(item("task",1,"문서 작성",",\"taskId\":9")).replace("true","false"),"EVIDENCE_WITH_FOUND_FALSE");
        assertThat(json.valueToTree(ReflectionTestUtils.getField(error,"checks")).get(0).path("reason").asText()).isEqualTo("OK");
    }
    @Test void missingEvidenceArrayReportsMissing() { // evidence 객체를 배열로 강제 변환하지 않는다.
        reject("{\"found\":true,\"evidence\":{}}","EVIDENCE_MISSING");
    }
    @Test void emptyTrueEvidenceReportsEmpty() { // found=true인데 빈 근거면 출처 없는 답을 허용하지 않는다.
        reject(body(""),"EVIDENCE_EMPTY");
    }
    @Test void invalidMeetingIdReportsInvalid() { // 문자열 회의 ID를 숫자로 변환하지 않는다.
        reject(body(item("doc",1,"지영은 문서를 작성한다.","")).replace("\"meetingId\":1","\"meetingId\":\"1\""),"MEETING_ID_INVALID");
    }
    @Test void missingDocumentIdReportsInvalid() { // 출처 문서 ID가 없으면 항목을 거절한다.
        reject(body("{\"meetingId\":1,\"quote\":\"문서\"}"),"DOCUMENT_ID_INVALID");
    }
    @Test void missingQuoteReportsInvalid() { // 인용 필드의 누락을 구분한다.
        reject(body("{\"documentId\":\"doc\",\"meetingId\":1}"),"QUOTE_INVALID");
    }
}
