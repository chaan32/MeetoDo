package com.meetodo.rag.question;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;
class VerificationDiagnosticsSecurityTests {
    final ObjectMapper json=new ObjectMapper();
    String capture(String output) { return VerificationDiagnostics.capture("NOT_JSON",output,List.of(),List.of(),json).modelOutput(); }
    @Test void escapedWebhookUrlIsAlsoMasked() { // JSON 이스케이프된 URL도 그대로 DB에 남기지 않는다.
        String url="https:"+"\\/\\/hooks.slack.com\\/services\\/fake\\/test";
        assertThat(capture("{\"value\":\""+url+"\"}")).doesNotContain("hooks.slack.com","fake");
    }
    @Test void unicodeEscapedTokenIsAlsoMasked() { // JSON 문자열에서 복원되는 토큰도 마스킹한다.
        String token="xox"+"b"+"\\u002d"+"test-secret-value";
        assertThat(capture("{\"value\":\""+token+"\"}")).doesNotContain("secret-value","test-secret");
    }
    @Test void authorizationValueIsMasked() { // 인증 헤더의 값은 키 형태가 아니어도 숨긴다.
        assertThat(capture("{\"Authorization\":\"arbitrary-private-value\"}")).doesNotContain("arbitrary-private-value");
    }
    @Test void unrelatedFormattingStaysIntact() { // 비밀값 없는 출력 형식은 진단을 위해 그대로 보존한다.
        String output="```json\n {\"found\": true, \"evidence\": []} \n```";
        assertThat(capture(output)).isEqualTo(output);
    }
    @Test void truncationDoesNotSplitSurrogatePair() { // 출력 길이 경계의 이모지 코드 단위를 쪼개지 않는다.
        var result=VerificationDiagnostics.capture("NOT_JSON","a".repeat(3999)+"😀"+"b",List.of(),List.of(),json);
        assertThat(result.modelOutput()).hasSize(3999); assertThat(result.outputTruncated()).isTrue();
    }
    @Test void tokenJoinedToOrdinaryTextIsStillFullyMasked() { // 접두 일반 문자 때문에 토큰 전체를 놓치지 않는다.
        String token="xox"+"b-"+"test-secret-value";
        assertThat(capture("prefix"+token)).doesNotContain("test-secret-value","xox"+"b-");
    }
    @Test void tokenAcrossTruncateBoundaryLeavesNoPartialSecret() { // 길이 제한 뒤 토큰 앞부분조차 남기지 않는다.
        String token="sk"+"-"+"secret-test-value-12345";
        assertThat(capture("z".repeat(3990)+token+"suffix")).doesNotContain("sk"+"-secret-");
    }
}
