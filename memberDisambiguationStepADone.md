# 동명이인 처리 개선 — 단계 A

## 범위와 설계

팀 조회와 단일 문자열 assignee, 미정 사유만 변경했다. findTasks는 계속 필수이며 단계 B는 진행하지 않았다.

| assignee | 처리 | tasks.unassignedReason | tasks.warning |
| --- | --- | --- | --- |
| member:<id> | 현재 회의 findMember 후보 ID로 배정 | null | null |
| unassigned:AMBIGUOUS | 동명이인 확정 불가 | AMBIGUOUS | 동명이인 확인 필요 |
| unassigned:NOT_FOUND | 미등록 담당자 | NOT_FOUND | 등록되지 않은 담당자 |
| unassigned:TEAM_MISMATCH | 팀이 달라 동일인 확정 불가 | TEAM_MISMATCH | 팀 불일치 확인 필요 |
| unassigned:NONE | 담당자 언급 없음 | NONE | null |

별도 ID·미정 boolean은 모순 조합을 만들 수 있다. 단일 문자열은 배정과 미정을 함께 표현할 수 없고, 형식이 잘못되면 저장하지 않는다. 잘못된 문자열은 INVALID_ASSIGNEE로 선택 방법을 안내한다. 후보 이력은 현재 회의의 성공한 findMember ToolCallLog를 통해 검증한다. 결과가 잘렸거나 파싱되지 않으면 후보로 인정하지 않는다.

findMember는 이름과 team을 정확히 비교한다. 팀 없는 동명이인 조회에는 재조회/미정 안내를 함께 반환한다. 지정한 팀이 없으면 MEMBER_TEAM_NOT_FOUND와 같은 이름 후보를 반환한다. 동일 팀에도 같은 이름이 여러 명이면 자동 선택하지 않는다.

findTasks 호출 정책은 유지했다. 미정 기존 작업 조회에는 선택 인자 unassignedReason(AMBIGUOUS/NOT_FOUND/NONE)을 전달해 createTask 없이 EXISTING을 반환하더라도 사유와 경고를 보존한다. ID·이름이 있는 배정 조회와 미정 사유를 함께 보내면 거절한다.

## 근거

Spring AI 1.1.8 실제 소스의 ToolParam.required와 MethodToolCallback.extractToolArguments/buildMethodArguments를 확인했다. team은 @ToolParam(required=false)로 선택 입력이며 assignee는 String이다. 필수 여부는 스키마 안내이고 서버 검증을 대신하지 않는다. 모순된 과거 필드만 보내고 assignee를 누락하면 입력 오류로 거절한다. 외부 도구 인자의 여분 필드 허용 정책은 유지한다.

공식 문서: https://docs.spring.io/spring-ai/reference/api/tools.html 의 Method-based tools/ToolParam 설명. 현재 웹 문서는 2.x이므로 실제 적용 API는 캐시된 1.1.8 소스와 대조했다.

## 변경 파일

- tools/FindMemberTool.java: 선택 팀과 중립 결과 메시지
- tools/CreateTaskTool.java: 단일 assignee 검증, 미정 사유와 경고
- tools/ToolExecutionService.java: 현재 회의 후보 이력 검증
- tools/FindTasksTool.java: 미정 재사용 결과의 사유 보존, 호출 정책 유지
- meeting/SpringAiMeetingAgent.java: 팀을 첫 조회에 전달하는 지침과 새 담당자 계약
- meeting/MeetingApplicationService.java 및 dto/MeetingResponse.java, MeetingTaskResponse.java: CREATED/EXISTING 사유와 경고 응답
- MeetingAgentApiIntegrationTests, SpringAiMeetingAgentTests, MeetingToolsTests, CrossMeetingTaskReuseTests: 현재 계약과 실제 비정상 입력 회귀
- README.md, DEVLOG.md, 이 문서

## 직접 실행과 Swagger 확인

DB 스키마 변경은 없다. 이미 처리한 정확히 같은 본문은 기존 응답을 반환하므로 실제 실측 전 데이터 조건을 맞춰야 한다. 로컬 data/ 초기화는 사용자가 직접 한다. A/B 비교는 동일한 입력과 모델, 초기 데이터 조건으로 진행하고 실측 표의 개선 후 값은 직접 채운다.

```zsh
export OPENAI_API_KEY="$(security find-generic-password -a "$USER" -s meetodo-openai -w)"
export OPENAI_CHAT_MODEL=gpt-4o-mini
./gradlew bootRun --args='--spring.profiles.active=local,openai'
```

http://localhost:8080/swagger-ui/index.html 의 POST /api/meetings에서 각각 입력한다.

```json
{"rawText":"백엔드팀 지영은 로그인 API 오류 처리를 2026-11-20까지 수정한다."}
```

기대: 백엔드팀 지영 배정. GET /api/meetings/{id}/logs에서 첫 findMember에 team=백엔드팀, findTasks 조회, createTask에 assignee=member:<후보 ID>를 확인한다.

```json
{"rawText":"지영은 회의실 예약을 확인한다."}
```

기대: 담당자 미정, unassignedReason=AMBIGUOUS, warning=동명이인 확인 필요. findMember는 동명이인 후보를 반환하고 createTask는 unassigned:AMBIGUOUS를 받는다.

```json
{"rawText":"등록되지 않은 수빈은 문서 초안을 공유한다."}
```

기대: unassignedReason=NOT_FOUND, warning=등록되지 않은 담당자.

```json
{"rawText":"문서 초안을 공유하기로 했다. 담당자는 아직 정하지 않았다."}
```

기대: unassignedReason=NONE, warning=null.

GET /api/tasks로 Task 담당자와 팀을, GET /api/meetings/{id}로 저장 후에도 warning/unassignedReason을 확인한다. 처리 시간과 llmCallCount/toolCallCount/validationFailureCount는 응답을 그대로 DEVLOG에 기록한다. 종료는 서버 터미널에서 Ctrl+C.

## 면접 예상 질문

1. 왜 담당자를 문자열 하나로 표현했나? — ID·이름·미정 boolean을 독립 입력으로 받으면 모순 조합이 가능했다. 한 토큰으로 결정하고 서버가 문법과 후보 ID를 검증해 의미 없는 조합을 저장하지 않는다. 문자열 문법을 별도로 검증해야 하는 비용이 있다.
2. 왜 팀을 첫 조회에 넣나? — 이름만 조회한 뒤 모델이 후보 팀을 비교하거나 재조회하는 단계를 줄이기 위해서다. 실제 호출 수 개선은 사용자가 A 실측 후 판단하며 아직 수치를 단정하지 않는다.
3. mock 테스트가 통과했는데 왜 실제 모델은 실패했나? — 기존 mock은 정상 후보 선택만 고정했고 실제 모델의 모순 입력을 포함하지 않았다. 실제 로그의 인자를 그대로 재현하고 저장 방지와 복구 안내를 검증했다. mock 통과는 실제 모델의 선택을 보장하지 않는다.


## 자동 검증 결과

실제 모순 입력 replay와 EXISTING 미정 사유 누락의 수정 전 실패를 확인한 뒤 수정했다. `./gradlew test bootJar --no-daemon`: 전체 108개, 실패·오류·건너뜀 0. 실제 OpenAI는 호출하지 않았다. A 적용 후 실측과 B 구현은 대기 중이다.


## 단계 A 후속 수정

현재 A 측정 조건은 팀 불일치 가드·빈 team 거절·TEAM_MISMATCH·실패 결과 로그 보존을 포함한다. 위 108개는 최초 A 구현 시점의 검증 기록이며 후속 수정의 전체 검증은 118개 통과다. [teamMismatchFixDone.md](teamMismatchFixDone.md)를 참고한다. 실험 표의 구조와 기존 값은 유지한다.
