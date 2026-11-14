package com.meetodo.rag.question;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.meetodo.rag.index.RagSourceSnapshot;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;
class EvidenceVerifierTests {
 EvidenceVerifier verifier = new EvidenceVerifier(new ObjectMapper());
 RagSourceSnapshot source = new RagSourceSnapshot("doc", "MEETING", 1L, null, 0, "지영은 문서를 작성한다.", Instant.EPOCH, "", "ns");
 String response(String id, String quote) { return "{\"found\":true,\"evidence\":[{\"documentId\":\""+id+"\",\"meetingId\":1,\"quote\":\""+quote+"\"}]}"; }
 @Test void acceptsExactRetrievedQuote() {
  // 실제 검색 문서의 정확한 인용만 근거로 반환한다.
  assertThat(verifier.verify(response("doc", "지영은 문서를 작성한다."), List.of(source))).hasSize(1);
 }
 @Test void acceptsUnicodeWhitespaceOnlyChanges() {
  // 공백 종류와 연속 공백만 정규화한다.
  assertThat(verifier.verify(response("doc", "지영은　문서를  작성한다."), List.of(source))).hasSize(1);
 }
 @Test void rejectsOutsideDocument() {
  // 검색하지 않은 문서 ID를 모델이 제시해도 채택하지 않는다.
  assertThatThrownBy(() -> verifier.verify(response("other", "지영은 문서를 작성한다."), List.of(source))).isInstanceOf(IllegalArgumentException.class);
 }
 @Test void rejectsChangedName() {
  // 이름 변경을 유사 인용으로 인정하지 않는다.
  assertThatThrownBy(() -> verifier.verify(response("doc", "민수는 문서를 작성한다."), List.of(source))).isInstanceOf(IllegalArgumentException.class);
 }
 @Test void rejectsChangedPunctuation() {
  // 문장 부호 변경도 공백 정규화에 포함하지 않는다.
  assertThatThrownBy(() -> verifier.verify(response("doc", "지영은 문서를 작성한다!"), List.of(source))).isInstanceOf(IllegalArgumentException.class);
 }
 @Test void rejectsMissingCitation() {
  // found=true에 인용이 없으면 확인 불가다.
  assertThatThrownBy(() -> verifier.verify("{\"found\":true,\"evidence\":[]}", List.of(source))).isInstanceOf(IllegalArgumentException.class);
 }
 @Test void rejectsMeetingTaskAssociationInventedByModel() {
  // 당시 회의록 인용에 모델이 임의 taskId를 붙일 수 없다.
  assertThatThrownBy(() -> verifier.verify(response("doc", "지영은 문서를 작성한다.").replace("\"meetingId\":1", "\"meetingId\":1,\"taskId\":77"), List.of(source))).isInstanceOf(IllegalArgumentException.class);
 }
 @Test void rejectsTaskIdDifferentFromCanonicalTask() {
  // TASK 출처와 다른 할 일 연결을 거부한다.
  var task=new RagSourceSnapshot("doc","TASK",1L,9L,0,"지영은 문서를 작성한다.",Instant.EPOCH,"","ns");
  assertThatThrownBy(() -> verifier.verify(response("doc", "지영은 문서를 작성한다.").replace("\"meetingId\":1", "\"meetingId\":1,\"taskId\":8"), List.of(task))).isInstanceOf(IllegalArgumentException.class);
 }
 @Test void rejectsMalformedModelResponse() {
  // JSON이 아닌 답변은 확인 불가다.
  assertThatThrownBy(() -> verifier.verify("잘 모르겠습니다",List.of(source))).isInstanceOf(IllegalArgumentException.class);
 }

 @Test void rejectsTaskIdOutsideLongRangeInsteadOfWrappingToCanonicalId() {
  // Long 범위를 넘는 모델 ID가 기존 ID로 오버플로되어 일치하는 것을 거부한다.
  var task=new RagSourceSnapshot("doc","TASK",1L,1L,0,"지영은 문서를 작성한다.",Instant.EPOCH,"","ns");
  String model=response("doc","지영은 문서를 작성한다.")
    .replace("\"meetingId\":1", "\"meetingId\":1,\"taskId\":18446744073709551617");
  assertThatThrownBy(() -> verifier.verify(model,List.of(task))).isInstanceOf(IllegalArgumentException.class);
 }

 @Test void rejectsNegativeTaskIdOutsideLongRangeInsteadOfWrappingToCanonicalId() {
  // 음수 BigInteger 오버플로도 기존 양수 ID와 같은 값으로 인정하지 않는다.
  var task=new RagSourceSnapshot("doc","TASK",1L,1L,0,"지영은 문서를 작성한다.",Instant.EPOCH,"","ns");
  String model=response("doc","지영은 문서를 작성한다.")
    .replace("\"meetingId\":1", "\"meetingId\":1,\"taskId\":-18446744073709551615");
  assertThatThrownBy(() -> verifier.verify(model,List.of(task))).isInstanceOf(IllegalArgumentException.class);
 }

}
