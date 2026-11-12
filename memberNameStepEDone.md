# 성명 분리 — 단계 E

## 완료한 것

- Member의 성·이름을 familyName/givenName으로 명시 저장하고 fullName을 계산한다. 성을 추정해 자르지 않는다. 기존 응답 name·assigneeName 값은 전체 이름이다.
- 새 개발 샘플: 김민수(백엔드팀), 이지영(백엔드팀), 이지영(디자인팀), 전해찬(백엔드팀).
- 공통 MemberNames 조회: 전체 이름 정확 일치 → 없으면 이름 정확 일치 → 둘 다 없으면 님/씨 한 번만 제거하고 재조회. 정확한 원래 이름이 호칭 제거보다 우선이며 팀 조건은 이후 적용한다.
- findMember, 직접 Task 등록·조회, findTasks 이름·ID 대조, 팀 불일치 가드에 공통 규칙을 연결했다. 이름을 바꿔 재조회해도 기존 보수적 팀 가드를 지우지 않는다.
- 도구 description/@ToolParam/시스템 프롬프트에 모델이 호칭·직급·조사를 뺀 이름을 추출하도록 안내했다. 서버는 직급·조사를 추측해 제거하지 않는다.
- D requestedAssigneeName은 원래 조회 인자를 보존하고 후보는 전체 이름으로 표시한다. 마스킹·길이 제한·조회 참조 검증은 유지한다.

## 검증 근거

변경 전 지영님 조회의 재현 테스트 1건이 실제 assertion 실패했다. 수정 후 전체 `./gradlew test bootJar --no-daemon`이 성공했다.

**250개 테스트, 실패 0 / 오류 0 / 건너뜀 0.** 신규 32개: H2 공통 조회 15, API·가드 12, 개발 샘플·도구 설명 3, mock ChatModel 통합 2. 기존 회의록의 배정·미정 결과, 두 글자 성, 원래 이름 우선, 호칭, 전체 이름 충돌 우선순위, 팀 불일치·원문에 없는 팀, 미정 상세 정보·Slack/Discord JSON도 확인했다. 실제 E OpenAI 호출·Slack 전송·성능 실측은 하지 않았다.

공통 매칭은 작은 정적 유틸리티로 기존 Repository와 서비스에서 재사용하며 새 빈이나 라이브러리를 추가하지 않았다. Repository는 성명 concat 정확 조회와 givenName 정확 조회를 제공한다. fullName을 별도 DB 컬럼에 중복 저장하지 않는다.

## 변경 파일

프로덕션:
- `src/main/java/com/meetodo/member/Member.java`, `MemberRepository.java`, `DevMemberDataInitializer.java`
- `src/main/java/com/meetodo/member/MemberNames.java` (신규)
- `src/main/java/com/meetodo/tools/FindMemberTool.java`, `FindTasksTool.java`, `ToolExecutionService.java`
- `src/main/java/com/meetodo/task/TaskService.java`, `dto/CreateTaskRequest.java`
- `src/main/java/com/meetodo/meeting/SpringAiMeetingAgent.java`

신규 테스트:
- `src/test/java/com/meetodo/member/MemberNamesTests.java`
- `src/test/java/com/meetodo/member/MemberNameApiTests.java`
- `src/test/java/com/meetodo/member/DevMemberDataInitializerTests.java`
- `src/test/java/com/meetodo/MemberFixtures.java` (동적 테스트 샘플에 대한 명시적 성 매핑 보조)

기존 테스트:
- `meeting/MeetingAgentApiIntegrationTests.java`, `SpringAiMeetingAgentTests.java`, `UnresolvedAssigneeApiTests.java`
- `task/TaskApiTests.java`, `TaskServiceTests.java`
- `tools/MeetingToolsTests.java`, `CrossMeetingTaskReuseTests.java`, `UnresolvedAssigneeToolTests.java`
- `notification/UnresolvedAssigneeNotificationTests.java`

문서: `README.md`, `DEVLOG.md`, 이 문서, `docs/superpowers/plans/2026-11-12-member-name-stage-e.md`.

## 데이터 초기화와 실행

기존 DB의 이름을 성·이름으로 추측해 백필하지 않는다. 기존 members의 name NOT NULL 컬럼과 새 성·이름 컬럼 구조가 다르므로 **서버를 끈 뒤 사용자가 data/를 초기화하고 시작**한다. 이번 작업에서 데이터를 삭제하지 않았다. 운영 DB 전환 시에는 명시적 데이터와 마이그레이션 도구를 사용한다.

이미 실제 검증한 SLACK_WEBHOOK_URL 설정은 유지한다. 키를 출력하지 않고 실행한다.

```zsh
export OPENAI_API_KEY="$(security find-generic-password -a "$USER" -s meetodo-openai -w)"
export OPENAI_CHAT_MODEL=gpt-4o-mini
export NOTIFIER_TYPE=slack
# SLACK_WEBHOOK_URL은 기존에 확인한 환경변수 값을 설정해 둔다.
./gradlew bootRun --args='--spring.profiles.active=local,openai'
```

## Swagger 확인

`http://localhost:8080/swagger-ui/index.html` → POST /api/meetings. 아래 회의록은 각각 별도 요청한다. 같은 본문은 기존 결과 반환, 같은 TODO는 재사용 정책이 적용되므로 새 초기 데이터에서 확인한다.

### 이름+팀, 모델의 직급 추출

```json
{"rawText":"백엔드팀 지영 대리는 E검증 로그인 API 오류 수정을 2026-11-20까지 진행한다."}
```

모델은 name=지영, team=백엔드팀으로 조회해야 한다. 응답 assigneeName=이지영, assigneeTeam=백엔드팀. Slack 예시(추출한 제목 표현은 달라질 수 있음):

```text
📝 회의 정리 완료 (할 일 1건)
새로 등록 1건 · 기존 할 일 0건
담당자 미정 0건
🆕 이지영(백엔드팀) · E검증 로그인 API 오류 수정 · 2026-11-20
회의 <id> · 처리 시간 <실측값>ms
```

### 이름만 있는 동명이인

```json
{"rawText":"지영님은 E검증 회의실 예약을 2026-11-20까지 확인한다."}
```

기대: AMBIGUOUS 미정, 후보 이지영(백엔드팀)/이지영(디자인팀), Slack 상세 경고. 모델이 호칭을 제거해 name=지영을 보내면 아래와 같다.

```text
🆕 담당자 미정 · E검증 회의실 예약 · 2026-11-20 · ⚠️ 동명이인 확인 필요: "지영" (후보: 백엔드팀, 디자인팀)
```

모델이 name=지영님을 그대로 보내도 서버는 매칭에만 님을 제거한다. 이 경우 requestedAssigneeName과 경고는 지영님을 보존한다. 둘 다 동일한 동명이인 미정 판정이다. 원문에서 어떤 값을 추출할지는 실제 모델의 동작이므로 도구 로그를 확인한다.

### 유일한 이름과 호칭

```json
{"rawText":"민수 씨는 E검증 운영 문서를 정리하고, 해찬은 E검증 배포 목록을 공유한다."}
```

기대: 김민수(백엔드팀), 전해찬(백엔드팀)으로 배정. 마감일 없음. 직급 추출까지 확인하려면 별도로 아래 요청을 사용한다.

```json
{"rawText":"민수 팀장님이 E검증 운영 점검을 진행한다."}
```

모델이 name=민수로 추출하면 배정된다. 민수 팀장님이를 그대로 보내면 서버는 직급·조사를 제거하지 않아 NOT_FOUND가 될 수 있다.

### 팀 불일치

```json
{"rawText":"프론트엔드팀 해찬은 E검증 배포 점검을 진행한다."}
```

기대: TEAM_MISMATCH 미정. 경고 `팀 불일치 확인 필요: "프론트엔드팀 해찬" (등록: 백엔드팀 전해찬)`.

### 전체 이름과 직접 API

```json
{"rawText":"디자인팀 이지영은 E검증 시안을 검토한다."}
```

기대: 이지영(디자인팀). 직접 POST /api/tasks의 예:

```json
{"title":"E직접 운영 문서 검토","assigneeName":"민수 씨","dueDate":"2026-11-20"}
```

기대: 201, assignee.name=김민수. assigneeName=이지영 또는 지영님은 동명이인이므로 400 AMBIGUOUS_MEMBER + 전체 이름·팀 후보. 실제 조회한 후보 ID를 assigneeId로 보내면 지정한 팀원에게 등록한다.

요청 후 GET /api/tasks와 GET /api/meetings/{id}에서 전체 이름·팀·미정 상세를 확인한다. GET /api/meetings/{id}/logs에서는 모델이 추출한 name, team, 조회/등록 lookupCallOrder, 오류·검증 실패를 확인한다. 두 글자 성의 별도 개발 샘플은 추가하지 않았으며 남궁/민·제갈 등의 지원은 명시적 성명 저장 구조로 처리하고 남궁/민은 자동 테스트로 확인했다.

## 실험과 한계

- 이름 추출은 모델, 매칭 규칙은 서버다. 모델이 잘못 추출한 이름을 올바른 인물로 보정하지 않는다.
- 끝 호칭 님/씨 한 번만 지원한다. 직급·조사·별명·오타·부분 문자열은 자동 제거/매칭하지 않는다.
- 전체 이름이 다른 사람의 givenName과 같으면 전체 이름 후보 우선. 팀 불일치 가드는 기존처럼 보수적으로 적용한다.
- 기존 팀 언급 문자열 검사와 미정 Task 중복·동시 등록 등의 한계는 유지한다.
- measure.sh의 기존 두 회의록 문장은 유지했다. 판정은 같아도 모델이 받는 후보·응답 이름은 달라졌으므로 E 적용 후(성명 분리) 열에 별도 실측한다. 빈 칸은 채우지 않았고 D 원값도 바꾸지 않았다. B와 실제 멘션은 보류한다.

## 면접 예상 질문

1. 왜 전체 이름에서 첫 글자를 성으로 나누지 않았나요?
   - 남궁 같은 복성과 다양한 이름 형식을 추측으로 잘못 저장하지 않기 위해 성·이름을 명시적으로 입력한다. 표시 이름은 계산해 중복 저장 불일치도 피한다.
2. 왜 서버가 직급·조사까지 제거하지 않나요?
   - 이름과 문장의 경계를 문자열 규칙만으로 추측하면 다른 사람을 매칭할 수 있다. 문맥 추출은 모델에 맡기고 서버는 제한된 호칭 처리와 정확한 후보 매칭·권한 검증을 담당한다.
3. 왜 E를 별도 실험으로 기록하나요?
   - 문장이 같아도 후보와 결과의 이름이 바뀌어 모델 입력 조건이 달라진다. 성능·호출 수 차이를 D 개선 효과와 섞어 해석하지 않기 위해 분리한다.
