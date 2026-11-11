# MeetoDo 3-4 테스트 계획

작성일: 2026-11-11

이 문서는 3-1~3-3 구현을 검증하는 자동 테스트 계약(A~G)과 직접 실행할 수동 테스트 표(H)를 정리한다.

## 현재 범위와 완료 기준

3-1~3-3 기능과 자동 테스트를 구현했다. 테스트 코드는 mock `ChatModel`을 사용하며 실제 OpenAI API를 호출하지 않는다. 전체 검증 명령은 `./gradlew test bootJar --no-daemon`이다. 최종 결과와 테스트 수는 `thirdStepDone.md`에 기록한다.

확정 정책: 과거 날짜 첫 호출은 `PAST_DUE_DATE` 오류다. 같은 작업을 재시도해도 날짜가 과거면 마감일 없이 등록하고 경고를 포함한다. 빈 최종 응답은 Meeting을 FAILED로 남긴다. 다른 회의의 같은 작업은 별도로 허용한다. 같은 회의의 중복 작업은 meeting ID·대소문자 무시 제목·담당자 ID로 판별한다. 같은 본문은 trim·줄바꿈 정규화 후 SHA-256 유일 제약으로 한 번만 처리하며, 진행 중 재요청은 202를 반환한다. 호출 상한은 도구 20회, LLM 21회다. `local & openai & !test` 외 프로필의 POST는 404 `MEETING_AGENT_DISABLED`다. arguments/result 제한은 각각 4,000자다.

## 공통 자동 테스트 규칙

- 자동 테스트에서 실제 OpenAI API를 호출하지 않는다. LLM 포트/`ChatModel`을 mock 또는 fake로 대체해 도구 호출 요청과 최종 응답을 시나리오별로 고정한다.
- 날짜 기준은 `Clock.fixed(..., ZoneId.of("Asia/Seoul"))`를 사용한다. 기준 시각은 2026-11-11 Asia/Seoul이며, 상대 날짜 예시는 금요일 2026-11-20, 이번 주 일요일 2026-11-15, 내일 2026-11-12를 검증한다.
- 테스트 이름은 행동과 기대 결과가 드러나게 한국어 또는 명확한 영어로 작성한다.
- 각 테스트 메서드에 검증 목적을 설명하는 한 줄 주석을 추가한다.
- 시간 측정 검증은 값이 0 이상인지 확인한다. 실행 속도 수치를 고정하거나 성능 회귀 기준을 임의로 만들지 않는다.

## A. 도구 단위 테스트 명세

| 도구 | 시나리오 | 기대 결과 |
|---|---|---|
| `findMember` | 정확한 이름 검색 | Member 정보 반환 |
| `findMember` | 이름 앞뒤 공백 | trim 후 같은 Member 반환 |
| `findMember` | 없는 이름 | 예외 대신 미발견 결과 반환 |
| `findMember` | 빈 문자열 또는 null | 입력 오류 결과 반환 |
| `findTasks` | 담당자·제목이 같은 작업 존재 | 해당 작업 반환 |
| `findTasks` | 담당자만 같고 제목이 다름 | 결과 없음 |
| `findTasks` | 담당자 null 조건 | 담당자 미정 작업도 조회 |
| `createTask` | 정상 입력 | 작업 저장 및 `meetingId` 연결 |
| `createTask` | 미등록 담당자 | 정책대로 담당자 미정(null) 저장 |
| `createTask` | 빈 제목 | 저장하지 않고 입력 오류 반환 |
| `createTask` | 제목 길이 초과 | 저장하지 않고 입력 오류 반환 |
| `createTask` | `2026/13/45`, `다음주` 등 형식 오류 | 저장하지 않고 날짜 입력 오류 반환 |
| `createTask` | 과거 마감일 첫 요청 | `PAST_DUE_DATE` 오류 결과 |
| `createTask` | 같은 작업 과거 날짜 재시도 | 마감일 없이 저장하고 경고 반환 |
| `createTask` | 마감일 없음 | null 마감일로 저장 |

## B. ToolCallLog 테스트 명세

| 시나리오 | 검증 |
|---|---|
| 도구 성공 | `success=true`, latencyMs ≥ 0 |
| 도구가 입력 오류 반환 | `success=false`, errorCode 기록 |
| 도구 내부 예상 못한 예외 | 실패 로그가 남고 회의 실행 정책에 따라 다음 단계가 처리됨 |
| 한 회의에서 여러 호출 | 호출 순번대로 로그 조회 |
| result가 저장 상한 초과 | 사전 합의된 최대 길이로 잘려 저장 |
| 민감/대용량 데이터 | arguments/result에 API 키 형태 문자열 또는 회의록 전체 원문이 없음 |

## C. mock LLM 에이전트 흐름 통합 테스트 명세

| 시나리오 | 검증 |
|---|---|
| 정상 흐름 | `findMember → findTasks → createTask → 최종 응답`; Meeting DONE, Task 생성 |
| 할 일 3개 | 각각의 담당자·날짜가 올바르게 저장 |
| 미등록 담당자 | 담당자 null 저장 및 최종 미정 건수 반영 |
| 도구 입력 오류 재시도 | 잘못된 날짜 결과를 모델에 전달, 올바른 재요청 후 등록, validation failure 1회 |
| 도구 반복 상한 초과 | 추가 실행 중단, Meeting FAILED |
| 모델 호출 예외 | Meeting FAILED, 응답에는 일반화된 오류만 포함 |
| 빈 최종 응답 | 3-2/3-3에서 정한 빈 응답 정책 적용 |
| 측정값 기록 | 처리시간, LLM 호출 수, 도구 호출 수, 검증 실패 수가 Meeting에 저장 |

## D. 중복 방지 테스트 명세

| 시나리오 | 검증 |
|---|---|
| 같은 회의를 두 번 처리 | 같은 회의에서 동일 작업이 중복 생성되지 않음 |
| 한 회의에서 같은 createTask 재호출 | 같은 회의의 동일 작업은 하나만 남음 |
| 다른 회의의 같은 제목 | 제목만으로 합치지 않으며 3-2에서 정한 회의 간 기준을 적용. 테스트명에 기준을 명시 |
| 같은 회의 동시 처리 2건 | 선택한 동시성 정책을 검증. DB 제약이면 제약 충돌 후 응답도 확인 |

## E. 날짜 해석 테스트 명세

고정 Clock: 2026-11-11 수요일, `Asia/Seoul`.

| 입력 | 기대 날짜/결과 |
|---|---|
| `다음 주 금요일` | 2026-11-20 |
| `이번 주 안에` | 일요일 2026-11-15 |
| `내일` | 2026-11-12 |
| 과거 날짜를 모델이 반환 | 첫 호출은 오류, 재시도 후에도 과거면 마감일 없이 저장하고 경고 |

## F. API 테스트 명세 (MockMvc)

| 요청/조건 | 기대 결과 |
|---|---|
| 정상 회의 POST | 200 및 meetingId, status, summary, tasks, unassignedCount, processingTimeMs, llmCallCount, toolCallCount |
| 빈 회의록 | 400 `VALIDATION_ERROR` |
| 글자 수 상한 초과 | 400, 필드 길이 오류 |
| 없는 Meeting GET | 404 `MEETING_NOT_FOUND` |
| 로그 GET | 호출 순번대로 반환 |
| OpenAI 비활성 프로필 | 3-3에서 정한 동작(미등록/명시적 404 등) 검증 |

## G. 보안 및 회귀 테스트 명세

| 시나리오 | 기대 결과 |
|---|---|
| 로그 캡처 | API 키, Authorization 헤더, 회의록 원문 전체가 없음 |
| 공급자 예외 | 예외 원문이 HTTP 오류에 노출되지 않음 |
| 기존 회귀 테스트 | llm-ping, Task API, 프로필 격리 테스트 전체 통과 |
| 전체 빌드 | `./gradlew test bootJar --no-daemon` 성공, 실패·오류·건너뜀 0 |

## H. 수동 Swagger 테스트

아래 결과 칸은 실제로 실행한 뒤 사용자가 채운다. 미실행 상태에서는 통과 여부를 기록하지 않는다.

| # | 입력 | 누를 곳 / 실행 | 기대 결과 | 확인할 응답 필드 | ToolCallLog에서 볼 것 | 실제 결과 |
|---:|---|---|---|---|---|---|
| 1 | 담당자·마감일이 분명한 작업 3개가 있는 회의록 | Swagger `POST /api/meetings` → Try it out → 본문 입력 → Execute | 회의 완료, 작업 3개 등록 | meetingId, status, summary, tasks, 미정 건수, 처리시간, LLM/도구 호출 수 | 팀원 조회→중복 조회→작업 등록 순서, 각각 success와 latency | |
| 2 | `다음 주 금요일`, `이번 주 안에`, `내일`을 포함한 회의록 | 같은 POST 호출 | 각 표현이 합의된 날짜 규칙대로 변환 | tasks의 dueDate | createTask arguments의 정규화 날짜와 검증 결과 | |
| 3 | 등록되지 않은 이름의 담당자가 포함된 회의록 | 같은 POST 호출 | 해당 작업은 담당자 미정으로 생성 | tasks.assignee=null, 미정 건수 | findMember 미발견 후 createTask의 assignee=null | |
| 4 | 첫 요청과 완전히 같은 회의록을 연속 두 번 전송 | POST 두 번 후 Task API `GET /api/tasks` 실행 | 중복 방지 기준에 따라 같은 회의의 작업이 중복되지 않음 | 두 meetingId/status, Task 목록과 개수 | 두 번째 실행의 중복 검사·createTask 결과 | |
| 5 | 할 일이 없는 잡담 회의록 | 같은 POST 호출 | 회의는 완료되고 Task는 0건 | status, summary, tasks=[] | 불필요한 createTask 호출이 없는지 | |
| 6 | 허용 길이를 초과한 회의록 | POST 본문에 긴 rawText 입력 | 에이전트를 실행하지 않고 길이 오류 | HTTP 400, code, fieldErrors | LLM·도구 호출 로그가 생성되지 않는지 | |
| 7 | 처리 완료한 meetingId | Swagger `GET /api/meetings/{id}/logs` → Execute | 호출 기록 반환 | 순번, toolName, success, errorCode, latencyMs | 순서·성공 여부·시간이 실제 흐름과 맞는지 | |
| 8 | 정상 회의록 한 건 처리 | 실행 중 서버 터미널 로그를 관찰 | 키와 전체 회의록 원문이 출력되지 않음 | 응답에 공급자 예외 원문이 없는지 | arguments/result가 길이 제한·민감값 제외 기준을 지키는지 | |

### 수동 테스트 전 확인할 값

- `POST /api/meetings`는 동기 실행이며, 같은 본문이 처리 중이면 202 PROCESSING을 반환한다.
- 회의록 최대 길이 20,000자, 본문 중복 판별은 trim 및 줄바꿈 정규화 후 SHA-256, 도구 호출 상한 20회, ToolCallLog arguments/result 제한은 각각 4,000자.
- 테스트용 팀원은 `local` seed 민수·지영·해찬을 사용하고 실제 Slack ID는 넣지 않는다.
- 이 문서는 절차서다. 실제 결과는 Swagger 실행 후 표의 마지막 칸에 기록한다.


## 자동 검증 현황

- 도구 단위, 에이전트 흐름, API, 중복 방지, 날짜 Clock, 프로필 격리, 로그 보안 및 전체 회귀 테스트를 구현했다.
- 수동 표의 결과 칸은 사용자가 Swagger에서 직접 확인한 뒤 작성한다. 실제 OpenAI 실행 결과를 이 문서가 주장하지 않는다.
