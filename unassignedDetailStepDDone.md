# 미정 담당자 상세 정보 — 단계 D

## 완료한 것

- findMember의 모델 반환값과 ToolCallLog.result에 동일한 lookupCallOrder를 제공한다.
- createTask의 AMBIGUOUS/NOT_FOUND/TEAM_MISMATCH는 현재 회의·findMember·일치하는 사유의 조회 번호만 참조한다. NONE과 member 배정은 번호를 생략할 수 있다.
- 잘못된 참조는 INVALID_LOOKUP_REFERENCE와 `findMember 결과에 있는 lookupCallOrder 값을 그대로 넣으세요`로 복구 방향을 안내한다.
- Task에 최초 unresolvedAssignee 스냅샷(사유, 요청 이름, 요청 팀, 후보 id/name/team, 생략 후보 수)을 JSON LOB로 저장한다. 후보는 Member의 현재 값으로 다시 계산하지 않는다.
- GET /api/tasks, 회의 응답 tasks와 알림에 상세 경고를 제공한다. 기존 Task 재사용은 최초 스냅샷을 유지하고 해당 회의 응답은 그 회의의 조회 정보를 사용한다.
- 이름·팀 최대 100 유니코드 코드포인트, 후보 최대 20명. 기존 비밀 마스킹·제어문자 정리·플랫폼 멘션 차단·길이 제한을 적용한다.
- 기존 데이터 백필 없음. 담당자 변경 API와 실제 멘션, B 정책 변경은 보류한다.

## 실제 검증 결과

변경 전 API 재현 테스트: 요청 이름 경로가 없어 실패했다. 조회 번호 부재 재현 테스트도 실패했다.

구현 중 일반 도구의 nullable Integer가 int로 언박싱되는 오류를 테스트가 발견했다. 조건식 양쪽을 Integer로 맞추고 일반 도구의 null 참조 회귀 테스트를 추가했다. Discord 괄호 이스케이프는 기존 정상 출력 형식이라 JSON 테스트 기대값을 맞췄다.

`./gradlew test bootJar --no-daemon`: **218개, 실패 0 / 오류 0 / 건너뜀 0**, BUILD SUCCESSFUL. 신규 테스트 36개(도구 20, API 7, 플랫폼 JSON 6, mock ChatModel 통합 3). 실제 OpenAI·Slack·Discord 전송은 실행하지 않았다.

## 변경 파일

프로덕션:
- `src/main/java/com/meetodo/task/UnresolvedAssignee.java` (신규): 불변 조회 스냅샷·경고 조립·JSON DB 변환. 별도 후보 테이블 대신 생성 시점 자료를 한 값으로 보존한다.
- `src/main/java/com/meetodo/task/Task.java`, `TaskService.java`, `dto/TaskResponse.java`
- `src/main/java/com/meetodo/tools/ToolResult.java`, `ToolExecutionService.java`, `CreateTaskTool.java`, `FindMemberTool.java`
- `src/main/java/com/meetodo/meeting/SpringAiMeetingAgent.java`, `MeetingResults.java`, `dto/MeetingResponse.java`, `dto/MeetingTaskResponse.java`

테스트:
- `src/test/java/com/meetodo/tools/UnresolvedAssigneeToolTests.java` (신규)
- `src/test/java/com/meetodo/meeting/UnresolvedAssigneeApiTests.java` (신규)
- `src/test/java/com/meetodo/notification/UnresolvedAssigneeNotificationTests.java` (신규)
- `src/test/java/com/meetodo/tools/MeetingToolsTests.java`
- `src/test/java/com/meetodo/meeting/MeetingAgentApiIntegrationTests.java`, `SpringAiMeetingAgentTests.java`

문서: `README.md`, `DEVLOG.md`, 이 문서, `docs/superpowers/plans/2026-11-12-unresolved-assignee-stage-d.md`.

알림 조립과 Slack/Discord 변환은 기존 코드를 그대로 사용한다. 서버 DTO의 warning을 상세하게 만들어 기존 공통 경로로 전달하며 JSON 테스트로 검증한다. 새 라이브러리·플랫폼 API는 추가하지 않았다.

## 직접 실행

```zsh
export OPENAI_API_KEY="$(security find-generic-password -a "$USER" -s meetodo-openai -w)"
export DISCORD_WEBHOOK_URL="$(security find-generic-password -a "$USER" -s meetodo-discord-webhook -w)"
export NOTIFIER_TYPE=discord
export OPENAI_CHAT_MODEL=gpt-4o-mini
./gradlew bootRun --args='--spring.profiles.active=local,openai'
```

로컬 ddl-auto=update가 nullable 스냅샷 컬럼을 추가하므로 기능 적용만을 위해 data/를 지울 필요는 없다. 기존 기록은 백필하지 않는다. 과거와 정확히 같은 본문은 예전 결과가 반환된다. C와 같은 초기 데이터에서 measure.sh를 실행하려면 사용자가 실험용 개발 데이터를 초기화한다. 서버 실행 중 DB 파일을 삭제하지 않는다.

## Swagger 확인

`http://localhost:8080/swagger-ui/index.html`에서 아래 요청을 **각각 별도 POST /api/meetings**로 실행한다. 한 본문으로 합치면 팀 단어가 다른 인물 조회에도 영향을 줄 수 있다. 새 제목을 사용해 기존 TODO 재사용·duplicate 요약 알림과 구분한다.

### AMBIGUOUS

```json
{"rawText":"지영은 D검증 회의실 예약 확인을 2026-11-20까지 진행한다."}
```

기대: 담당자 미정, AMBIGUOUS, requestedAssigneeName=지영, 후보 백엔드팀/디자인팀.

```text
📝 회의 정리 완료 (할 일 1건)
새로 등록 1건 · 기존 할 일 0건
담당자 미정 1건
🆕 담당자 미정 · D검증 회의실 예약 확인 · 2026-11-20 · ⚠️ 동명이인 확인 필요: "지영" (후보: 백엔드팀, 디자인팀)
회의 <meetingId> · 처리 시간 <실측값>ms
```

제목은 모델 추출 결과에 따라 표현이 달라질 수 있다. 경고는 서버의 조회 근거에서 조립한다.

### TEAM_MISMATCH

```json
{"rawText":"프론트엔드팀 해찬은 D검증 배포 체크리스트 공유를 2026-11-20까지 진행한다."}
```

기대: 담당자 미정, TEAM_MISMATCH, requestedTeam=프론트엔드팀, 등록 후보 백엔드팀 해찬.

```text
⚠️ 팀 불일치 확인 필요: "프론트엔드팀 해찬" (등록: 백엔드팀 해찬)
```

### NOT_FOUND

```json
{"rawText":"준호는 D검증 테스트 자료 준비를 2026-11-20까지 진행한다."}
```

기대: 담당자 미정, NOT_FOUND, requestedAssigneeName=준호, 빈 후보 목록.

```text
⚠️ 등록되지 않은 담당자: "준호"
```

### NONE

```json
{"rawText":"D검증 공용 문서를 2026-11-20까지 정리하기로 했다. 담당자는 정하지 않았다."}
```

기대: 담당자 미정이며 경고 없음.

각 요청 후 다음을 확인한다.

1. POST 응답과 GET /api/meetings/{id}: tasks[].unresolvedAssignee, warning, unassignedReason, notificationStatus.
2. GET /api/tasks: 새 Task의 최초 unresolvedAssignee 스냅샷과 warning.
3. GET /api/meetings/{id}/logs: findMember.result.lookupCallOrder와 createTask.arguments.lookupCallOrder가 일치하는지. 잘못된 번호는 INVALID_LOOKUP_REFERENCE와 정확한 수정 안내가 남는지.
4. Discord 채널: 한 건의 메시지, 상세 경고, 실제 멘션 없음. 알림에 생략 안내가 있으면 GET API로 전체를 확인한다.

## 실측과 남은 한계

DEVLOG 실험 표에 **D 적용 후(미정 상세 정보)** 열을 추가했고 모든 측정 칸은 비워뒀다. B는 계속 보류한다. 사용자 실측 전 호출 수·검증 실패·처리 시간 변화는 주장하지 않는다.

- findMember에 전달된 이름 자체는 모델이 추출한 값이다. 조회 참조 검증이 원문 인물 추출의 정확성까지 보장하지 않는다.
- Task 미정 중복 기준은 유지돼 서로 다른 이름도 같은 제목의 TODO면 합쳐질 수 있다. 최초 Task 정보와 회의별 정보가 다를 수 있다.
- 조회 로그가 4,000자 제한으로 잘리면 추측하지 않고 참조 오류로 거절한다.
- 추후 담당자 배정 API에서는 확정과 동시에 현재 미정 정보·경고를 비우고 과거 회의 근거를 보존한다. 현재 PATCH는 상태 변경만 한다.

## 면접 예상 질문

1. 왜 모델이 이름을 다시 보내지 않고 조회 번호를 보내나요?
   - 이름·후보를 다시 생성할 때의 누락과 변형을 피한다. 서버는 이미 실행한 조회의 회의·도구·사유를 검사해 근거를 연결한다. 인자가 늘어나는 비용은 D 실측으로 따로 확인한다.
2. 왜 후보를 Member 관계가 아니라 스냅샷으로 저장하나요?
   - 당시 왜 미정이 됐는지 설명하려면 팀 변경 전의 후보가 필요하다. 별도 조회 테이블 없이 제한된 JSON 값으로 보존하되 관계 무결성은 현재 Member를 참조하는 방식보다 약하다.
3. 같은 Task를 재사용할 때 이름이 달라지면 어떻게 하나요?
   - 최초 Task 스냅샷은 덮어쓰지 않고 이번 회의의 응답과 도구 기록에 이번 근거를 남긴다. 미정 인물이 다른데 제목이 같은 경우 합쳐질 수 있는 기존 중복 정책의 한계는 유지한다.
