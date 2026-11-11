# 팀 불일치 배정 차단과 실패 도구 결과 기록

## 변경과 검증

- ToolExecutionService.java: 현재 회의의 명시 팀 불일치 이력을 조회하는 공통 가드. 실패 결과도 마스킹 후 저장.
- FindMemberTool.java: team 필드 생략과 빈 값 구분, 정확한 팀 불일치 안내와 후보 반환.
- CreateTaskTool.java / FindTasksTool.java: 배정·기존 작업 재사용 가드, TEAM_MISMATCH 사유와 경고.
- ToolCallLog.java: 실패 결과를 받는 factory 추가. DB 컬럼 변경 없음.
- SpringAiMeetingAgent.java: 팀 불일치 재조회 우회 금지, TEAM_MISMATCH와 team 생략 지침.
- MeetingToolsTests.java / MeetingAgentApiIntegrationTests.java: 실제 호출 replay·실패 결과 보존·정상 배정 회귀.
- README.md / DEVLOG.md / memberDisambiguationStepADone.md / 이 문서: 설계·실측 조건·한계 정리.

실제 로그와 같은 호출 순서, 빈 team, 공백 team, 실패 결과 저장 테스트 4개가 수정 전 실패했고 수정 후 통과했다. 전체 `./gradlew test bootJar --no-daemon` 결과: 118개 통과, 실패·오류·건너뜀 0. 실제 OpenAI 호출은 하지 않았다.

## Swagger 확인

DB 스키마 변경이 없어 data/ 초기화는 필수가 아니다. 다만 이미 저장된 잘못된 Task와 과거 null 로그는 자동 수정하지 않는다. 정확히 같은 본문은 SHA-256 캐시로 기존 결과가 반환되므로 새로운 실행의 검증·측정에는 과거 데이터 조건을 맞춰야 한다. 동일 입력으로 A 실험을 비교하려면 사용자가 직접 개발 데이터를 초기화한다.

```zsh
export OPENAI_API_KEY="$(security find-generic-password -a "$USER" -s meetodo-openai -w)"
export OPENAI_CHAT_MODEL=gpt-4o-mini
./gradlew bootRun --args='--spring.profiles.active=local,openai'
```

http://localhost:8080/swagger-ui/index.html 의 POST /api/meetings에 각각 보낸다.

```json
{"rawText":"프론트엔드팀 해찬은 11-12까지 회의실을 예약한다."}
```

기대: 백엔드팀 해찬에게 배정하지 않는다. Task 담당자 null, unassignedReason=TEAM_MISMATCH, warning=팀 불일치 확인 필요. GET /api/meetings/{id}/logs에서 MEMBER_TEAM_NOT_FOUND의 result 안 data에 등록 후보(id/name/team)가 남는지 확인한다. 빈 team 재조회가 나오면 INVALID_MEMBER_TEAM, 다른 팀 member ID 등록 시도가 나오면 INVALID_ASSIGNEE_TEAM이어야 한다. 정상 모델이 곧바로 미정 토큰을 사용하면 이 재시도 오류들은 발생하지 않아도 된다.

```json
{"rawText":"해찬은 배포 체크리스트를 공유한다."}
```

기대: 등록된 유일한 백엔드팀 해찬 배정. findMember는 team을 생략한다.

```json
{"rawText":"백엔드팀 해찬은 배포 설정을 점검한다."}
```

기대: 백엔드팀 해찬 배정.

```json
{"rawText":"백엔드팀 지영은 로그인 API 오류를 수정한다."}
```

기대: 백엔드팀 지영 배정.

```json
{"rawText":"지영은 회의실 예약을 확인한다."}
```

기대: 미정, AMBIGUOUS, 동명이인 확인 필요.

GET /api/meetings/{id}로 경고가 재조회되는지, GET /api/tasks로 담당자를 확인한다. DEVLOG A 적용 후에는 이번 수정까지 적용한 실제 실행값을 기록한다. B는 대기 중이다.

## 사실과 추정 / 알려진 한계

로그가 증명하는 사실은 팀 조회 실패·빈 팀 재조회·member ID 등록의 순서다. 팀 불일치 실패 ToolResult에는 후보가 있었지만 기존 로그가 result를 버려 모델의 반환 결과를 제대로 확인할 수 없었다. 모델이 후보를 못 받았다거나 왜 팀을 제거했는지는 로그만으로 단정할 수 없다.

현재 회의의 같은 이름에 팀 불일치 이력이 있으면 다른 팀원을 보수적으로 막는다. 같은 회의에서 동명이인의 서로 다른 작업을 다루면 올바른 배정도 제한될 수 있다. 이 한계를 README와 DEVLOG에 유지한다.

## 면접 예상 질문

1. 왜 지침만 강화하지 않고 서버 가드를 뒀나? — 실제로 '추측하지 말라'는 안내 뒤에도 조건을 제거한 재조회와 배정이 일어났다. 모델 선택과 별개로 저장·재사용 경계에서 명시 팀 불일치를 확인해야 한다.
2. 왜 실패 결과도 저장하나? — 모델에게 반환된 후보와 기록된 정보가 달라 로그만으로 원인을 잘못 추정했다. 실패 결과도 마스킹·길이 제한을 거쳐 남겨 조사 근거를 보존한다.
3. 왜 TEAM_MISMATCH를 별도 사유로 뒀나? — 이름 자체가 없는 NOT_FOUND와, 이름은 있지만 팀이 달라 동일인인지 모르는 상황은 확인할 내용이 다르다. 별도 사유와 경고로 표현하며 보수적 가드의 오탐 가능성도 공개한다.
