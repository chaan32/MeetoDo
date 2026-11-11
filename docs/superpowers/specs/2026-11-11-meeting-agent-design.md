# Meeting Agent 3단계 설계

## 의도

회의록을 받아 LLM이 `findMember` → `findTasks` → `createTask` 도구를 요청하고, 서버가 실행 결과를 다시 모델에 전달한다. 회의 상태, 결과, 측정값과 도구 호출 기록을 저장하고 Swagger에서 검증한다.

## 목표와 범위

- 3-3: 회의 처리 API, 제한된 도구 루프, 실패 상태·오류 처리, 재처리 중복 방지
- 3-4: 실제 OpenAI 없는 도구·에이전트·API·보안·프로필 회귀 테스트와 수동 테스트 표
- 3-5: 개발 프로필 실제 실행 안내, 세 가지 회의록 예시, 기준값 입력 양식
- OpenAI 모델과 호출은 `local & openai & !test`에서만 제공한다. `test`에서는 scripted mock `ChatModel`을 사용한다.
- Slack 알림 도구는 4단계에 둔다.

## 설계 선택지

1. **Spring AI 자동 루프 vs 직접 관리하는 ToolCallingManager 루프:** 자동 루프는 짧고 간단하지만 회의별 LLM·도구 호출 상한과 측정값을 관리하기 어렵다. 사용자 제어 루프는 호출 제한을 적용할 수 있어 선택한다.
2. **동기 처리 vs 비동기 접수:** 이번 회의 API는 Swagger에서 결과를 바로 확인해야 하므로 동기로 처리한다. Slack 입력은 별도 확장 단계에서 접수 응답 후 비동기로 처리한다.
3. **중복 기준:** 회의록 정규화 텍스트의 SHA-256을 고유 키로 삼아 같은 내용을 두 번 제출하면 기존 Meeting 상태·결과를 돌려준다. 한 Meeting 안의 Task는 같은 제목(대소문자 무시)·같은 담당자 조합으로 중복을 막고, Meeting이 다르면 같은 Task 제목도 등록을 허용한다.
4. **회의 도구 문맥:** ThreadLocal 대신 Spring AI `ToolContext`로 Meeting ID를 전달한다. 공식 문서와 1.1.8 소스에서 ToolContext가 모델 입력과 분리되어 도구에 전달되는 것을 확인했다.
5. **과거 마감일:** 첫 입력은 `PAST_DUE_DATE` 오류로 모델에 반환한다. 같은 Task에 대해 한 번 재시도해도 과거 날짜면 날짜 없이 등록하고, 도구 결과에 경고를 넣어 최종 응답에서 사용자에게 알리도록 한다.

## API 동작

- `POST /api/meetings`: 비어 있지 않고 20,000자 이하인 회의록을 검증하고 동기 처리한다. 완료 시 Meeting 결과·Task 목록·미정 건수·시간·호출 횟수를 반환한다. 동일 입력 재요청은 기존 Meeting을 반환하며 처리 중이면 `PROCESSING`을 반환한다.
- `GET /api/meetings/{id}`: 저장된 상태와 결과를 조회한다.
- `GET /api/meetings/{id}/logs`: 비밀값과 원문을 제거한 도구 로그를 순번대로 조회한다.
- Meeting agent가 비활성화된 프로필의 POST는 `404 MEETING_AGENT_DISABLED`로 명시한다.
- LLM 예외, 빈 최종 응답, 호출 상한 초과는 Meeting을 `FAILED`로 저장하고 일반화된 오류와 Meeting ID만 반환한다.

## 제한 및 보안

- 한 Meeting 처리 중 최대 20회의 도구 실행, 마지막 최종 응답을 포함해 최대 21회의 LLM 호출을 허용한다.
- 입력 검증 실패 수는 도구 로그의 검증 오류 코드에서 계산한다.
- API 키, Authorization, 회의록 원문 전체와 공급자 예외 문구는 애플리케이션 로그·ToolCallLog·오류 응답에 남기지 않는다.
- 테스트는 고정된 `Clock(Asia/Seoul)`과 scripted `ChatModel`만 사용한다.

## 남은 구현 판단

- 실제 API 응답 DTO와 에러 DTO의 세부 JSON 이름은 기존 Task API의 `code`, `message`, `fieldErrors` 형식과 일치시킨다.
- Task도 Meeting을 `ManyToOne`으로 참조해 ToolCallLog와 FK 표현을 일치시킨다. Task API DTO는 기존 `meetingId` 숫자 필드를 유지해 HTTP 호환성을 지킨다. 스키마는 H2/현재 개발 DB 기준으로 JPA가 생성하며, 기존 외부 DB에 orphan meetingId가 있으면 FK 적용 전 데이터 정리가 필요할 수 있다.


## 구현 중 설계 갱신

Task의 scalar meetingId 보존안은 구현 과정에서 JPA Meeting 연관관계로 조정했다. ToolCallLog와 같은 FK 무결성·회의별 조회 모델을 유지하면서, 외부 API는 DTO의 meetingId로 호환한다.
