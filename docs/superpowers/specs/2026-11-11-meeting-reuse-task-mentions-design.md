# Meeting 재사용 표시와 Task 언급 기록 설계

## 의도와 범위

동일한 할 일 묶음이 다시 언급된 회의도 별도 Meeting 및 ToolCallLog로 보존한다. 재사용 Task를 회의 결과에 표시하고, Task별 서로 다른 비중복 회의 언급 횟수를 반환한다. 정확히 같은 회의록 본문 재전송은 기존 SHA-256 멱등 처리를 유지한다.

## 목표

- Task가 모두 기존 Task여도 회의 결과의 `tasks` 배열을 채운다.
- 모든 Task가 한 기존 회의의 Task 집합과 일치하면 새 Meeting에 `duplicateOfMeeting`을 연결하되 Meeting과 ToolCallLog를 보존한다.
- `(task, meeting)`별 TaskMention 기록으로 mentionCount를 제공한다.
- 같은 회의의 반복 언급 및 동시 삽입에서도 TaskMention은 최대 한 건이며, 충돌이 회의 처리를 실패시키지 않는다.
- 직접 POST로 생성한 Task는 mentionCount 0으로 유지한다.

## 비목표

- 기존 H2 개발 데이터의 TaskMention 백필 및 재구성은 하지 않는다. 사용자가 로컬 `data/`를 초기화한다.
- 과거 ToolCallLog로부터 이전 언급을 복원하지 않는다.
- mentionCount 캐시나 숫자 카운터를 두지 않는다.

## 접근 방식

1. 임시 Meeting 삭제: 중복 회의 데이터가 줄지만 실제 LLM 호출과 ToolCallLog 근거가 사라진다. 선택하지 않는다.
2. 새 Meeting을 보존하고 원본을 `duplicateOfMeeting`으로 표시: 호출 기록과 정기 회의 이력을 남긴다. 선택한다.
3. 숫자 카운터: 읽기는 단순하지만 동시 갱신 및 이력 정합성을 별도로 관리해야 한다. 선택하지 않는다.
4. TaskMention 이력: DB 유니크 제약으로 회의별 멱등성을 보장하고 mentionCount를 집계한다. 선택한다.

## 저장 및 조회 설계

- Meeting은 nullable self-reference `duplicateOfMeeting`을 가진다. API 응답은 FK의 ID를 `duplicateOfMeetingId`로 반환한다.
- TaskMention은 Task 및 Meeting 연관관계와 `mentionedAt`을 저장하고 `(task_id, meeting_id)` unique 제약을 둔다.
- Task 도구로 새로 만든 Task는 최초 회의에서 한 번 기록한다. 기존 TODO Task가 다른 회의에서 재사용되면 그 회의에서 한 번 기록한다.
- `duplicateOfMeeting`이 설정된 회의의 TaskMention은 저장할 수 있지만 mentionCount 집계에서는 제외한다.
- 직접 POST `/api/tasks`는 Meeting이 없어 TaskMention을 만들지 않는다.
- 저장소의 묶음 집계 쿼리로 요청한 Task ID들의 mentionCount를 함께 읽는다. 중복 기록 시 별도 트랜잭션의 insert/flush 충돌을 포착해 전체 처리 흐름을 지속한다.
- 기존 데이터는 백필하지 않는다. README와 DEVLOG에 개발 DB 초기화 및 운영 DB 마이그레이션 도구 계획을 기록한다.

## 응답과 테스트

- Meeting 응답: `duplicateOfMeetingId`; Task 항목은 `taskId`, 제목, 담당자, 마감일, `resultType`, `mentionCount`.
- Task API 응답: `mentionCount` 추가.
- mock LLM 테스트로 EXISTING Task 결과, Meeting·로그 보존, 중복 판별 경계, TaskMention 멱등성과 집계 제외, 정확한 본문 재전송, API 응답 필드를 검증한다.
- 검증 명령: `./gradlew test bootJar --no-daemon`. 실제 OpenAI 호출은 하지 않는다.

## 주요 호출 선택

새 Meeting과 해당 ToolCallLog는 그대로 남기고 `duplicateOfMeetingId`로 관계를 나타낸다. TaskMention은 직접 생성 API를 제외한 에이전트 Task 생성·재사용 결과에서 기록한다. mentionCount는 비중복 Meeting만 대상으로 매 조회 집계하며 기존 개발 데이터는 초기화하고 백필하지 않는다.
