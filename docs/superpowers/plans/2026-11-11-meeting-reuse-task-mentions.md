# Meeting 재사용 표시와 Task 언급 기록 구현 계획

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:executing-plans to implement this plan task-by-task.

**Goal:** 비슷한 회의를 별도 Meeting과 로그로 보존하고 Task별 유효한 언급 횟수를 응답한다.

**Architecture:** Meeting은 duplicateOfMeeting self-reference로 원본을 가리킨다. TaskMention은 task/meeting 복합 유니크 기록이며 조회 응답은 저장된 기록을 묶음 집계한다. 기존 Task 데이터는 백필하지 않는다.

**Tech Stack:** Java 21, Spring Boot, Spring Data JPA, H2, JUnit 5, MockMvc, mock ChatModel.

**Spec:** `docs/superpowers/specs/2026-11-11-meeting-reuse-task-mentions-design.md`

## Global Constraints

- 실제 OpenAI API를 호출하지 않는다.
- 정확히 같은 본문 재전송은 기존 SHA-256 중복 처리를 유지한다.
- 직접 POST `/api/tasks`로 생성된 Task는 TaskMention 없이 mentionCount 0이다.
- 기존 데이터 백필은 하지 않는다. 로컬 `data/`는 사용자가 초기화한다.

## Review Focus

- EXISTING만 있는 회의의 응답 tasks가 로그의 실제 도구 결과에서 복원되는가.
- 원본 Task 집합의 일부나 여러 회의에 걸친 Task 재사용이 duplicate로 오판되지 않는가.
- 중복 Mention 동시 삽입이 Meeting 처리를 rollback시키지 않는가.
- duplicate 회의의 Mention이 저장되더라도 count 집계에서 빠지는가.
- GET tasks API의 기존 생성·상태변경 응답 계약이 유지되는가.

### Task 1: Meeting 응답 회귀 재현과 중복 표시

**Files:** `MeetingAgentApiIntegrationTests`, `MeetingApplicationService`, `Meeting`, `MeetingRepository`, `MeetingResponse`, `MeetingTaskResponse`, `MeetingHistoryResponse`.

- [ ] mock LLM 통합 테스트를 먼저 작성: 전체 EXISTING이면 tasks가 비지 않고 resultType=EXISTING, 기존 meeting ID로 duplicate 표시, 새 Meeting 저장, ToolCallLog 보존.
- [ ] 일부 Task만 일치하거나 Task가 여러 원본 회의에서 온 경우 duplicate ID가 null인 테스트 작성.
- [ ] 정확히 같은 본문 재전송 시 기존 Meeting을 반환하고 새 행·호출이 생기지 않는 테스트를 유지/보강.
- [ ] 테스트 실행해 tasks 누락 또는 삭제된 Meeting/로그 관련 실패를 확인.
- [ ] 기존 Meeting/로그 삭제 분기를 제거하고 일치 판정 결과를 새 Meeting에 저장한다. 응답에 duplicateOfMeetingId를 추가하고, 해당 Meeting의 Task 목록은 생성 Task와 EXISTING 도구 로그 ID를 합쳐 조립한다.
- [ ] 관련 통합 테스트를 통과시킨다.

### Task 2: TaskMention 저장과 집계

**Files:** 신규 `TaskMention`, `TaskMentionRepository`, `TaskMentionService`; `TaskService`, `CreateTaskTool`, 관련 도구·API 테스트.

- [ ] 서비스/API 테스트 작성: 에이전트 생성 Task는 1, 회의 없는 직접 생성 Task는 0, 같은 Meeting 중복은 1, 다른 Meeting 재언급은 2, duplicate Meeting은 count 제외.
- [ ] 동시 유니크 충돌을 별도 트랜잭션에서 처리하는 저장 테스트를 작성한다.
- [ ] `TaskMention` 복합 유니크 제약과 REQUIRES_NEW 저장 경계를 구현한다. duplicate Meeting은 언급 횟수에서 제외한다.
- [ ] Task 생성/재사용 흐름에서 TaskMention을 기록한다. 같은 `(task, meeting)` 충돌은 무시하고 회의 요청은 계속 완료되게 한다.
- [ ] Task ID 목록 단위 집계 Repository 쿼리를 추가하고 대상 Task가 없거나 언급 기록이 없을 때 0을 반환한다.

### Task 3: API 응답과 문서, 전체 회귀 검증

**Files:** `TaskResponse`, `MeetingTaskResponse`, `MeetingResponse`, `MeetingHistoryResponse`, `TaskService`, `MeetingApplicationService`, `README.md`, `DEVLOG.md`, 관련 API 테스트.

- [ ] GET `/api/tasks`에 mentionCount가 포함되고 Meeting 응답 Task 항목에도 `mentionCount`와 `resultType`이 나오는 테스트를 추가한다.
- [ ] 응답 조립을 묶음 집계와 연결하고 직접 생성 Task는 mentionCount 0으로 반환한다.
- [ ] README/DEVLOG에 개발 데이터는 초기화했고 운영 DB 전환 시 마이그레이션 도구로 처리할 예정이라고 기록한다. 실행하지 않은 데이터 초기화는 완료한 사실로 쓰지 않는다.
- [ ] `./gradlew test bootJar --no-daemon` 전체를 실행해 실패·오류·건너뜀을 확인한다.
- [ ] `git diff --check`를 실행한다.

