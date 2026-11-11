# MeetoDo

회의록에서 할 일을 추출해 담당자·마감일과 함께 등록하고 Slack으로 알리는 백엔드 프로젝트입니다. 별도 프론트엔드 없이 Swagger로 입력하고 Slack으로 결과를 받는 서비스를 목표로 합니다.

Task 등록·담당자별 조회·상태 변경과 도구 호출 기반 회의 처리 API를 구현했습니다. 실제 OpenAI 연결은 사용자가 확인했습니다. Slack 알림은 다음 단계입니다.

## 실행

Java 21이 필요합니다. Gradle은 Wrapper가 내려받으므로 별도 설치가 필요 없습니다.

```bash
./gradlew bootRun
```

- Swagger UI: http://localhost:8080/swagger-ui/index.html
- OpenAPI 문서: http://localhost:8080/v3/api-docs
- Health: http://localhost:8080/actuator/health

기본 `local` 프로필에서 Task API와 개발용 팀원 샘플을 사용할 수 있습니다. `local,openai`에서는 추가로 개발용 `GET /api/dev/llm-ping`이 표시됩니다. `local` 프로필은 `127.0.0.1`에서만 수신합니다.

```bash
./gradlew test
./gradlew bootJar
java -jar build/libs/meetodo-0.0.1-SNAPSHOT.jar
```

테스트 결과는 `build/reports/tests/test/index.html`에서 확인합니다.

## 설정 읽기

| 파일 | 역할 |
| --- | --- |
| `build.gradle` | Java 21, Web·JPA·Validation·Actuator·Spring AI·Swagger 의존성 및 JUnit 설정 |
| `settings.gradle` | 프로젝트 이름 `meetodo` 지정 |
| `gradlew`, `gradle/wrapper/` | Gradle 8.14.3으로 빌드 버전 통일 |
| `application.yml` | 앱 이름, 기본 프로필, AI 모델 기본 비활성화, health 노출 |
| `application-local.yml` | 로컬 H2 파일 DB와 개발용 스키마 갱신. local 프로필에서만 가짜 팀원 seed 실행 |
| `application-openai.yml` | `local & openai & !test`일 때만 OpenAI Chat 활성화. 단일 시도 및 민감한 원문을 출력하는 로거 비활성화 |
| `application-test.yml` | 실제 로컬 데이터와 분리된 H2 메모리 DB |
| `.env.example` | 필요한 환경변수 이름 안내. 자동으로 로드되는 설정 파일은 아님 |
| `.gitignore` | 비밀값, 로컬 DB, 빌드 결과 제외 |

`ddl-auto: update`는 로컬 개발용입니다. 데이터는 `data/` 아래에 저장되므로 이 폴더를 지우면 로컬 데이터도 사라집니다. 테스트의 `create-drop`은 메모리 DB에만 적용됩니다. `open-in-view: false`는 HTTP 응답을 만드는 동안 지연 로딩으로 DB 쿼리가 추가 실행되는 것을 막고 트랜잭션 범위를 서비스에서 다루도록 합니다.

## OpenAI 설정과 연결 확인

키는 IDE 실행 설정의 환경변수나 로컬 셸에 넣습니다. 키를 채팅·소스·커밋에 남기지 마세요. `.env.example`은 안내용이며 Spring Boot가 `.env`를 자동으로 읽지는 않습니다.

macOS 기본 셸인 zsh에서는 키를 화면이나 명령 기록에 직접 남기지 않고 입력할 수 있습니다.

```zsh
read -rs 'OPENAI_API_KEY?OpenAI API key: '
export OPENAI_API_KEY
echo
export OPENAI_CHAT_MODEL=gpt-4o-mini
./gradlew bootRun --args='--spring.profiles.active=local,openai'
```

활성 프로필을 명시하면 기본 프로필은 적용되지 않으므로 `local,openai`를 함께 지정합니다. 모델은 계정에서 사용 가능한 것으로 바꿀 수 있습니다.

서버가 뜨는 것은 **설정·빈 생성 확인**입니다. ping 요청이 성공해야 실제 키 인증과 모델 접근이 확인됩니다. 사용자는 실제 연결을 확인했습니다.

1. http://localhost:8080/swagger-ui/index.html 에 접속합니다.
2. `GET /api/dev/llm-ping` → **Try it out** → **Execute**를 누릅니다. 입력값은 없습니다.
3. HTTP 200과 `text`, `elapsedMs`를 확인합니다. 고정 질문은 `Reply with OK only.`이며 응답은 보통 `OK`입니다. 실제 문구와 시간은 모델과 네트워크에 따라 달라집니다.

다른 터미널에서 직접 호출할 수도 있습니다.

```bash
curl -i http://localhost:8080/api/dev/llm-ping
```

- `text`: 모델의 응답 텍스트.
- `elapsedMs`: 서버에서 ChatClient 호출 시작부터 응답 텍스트를 받기까지의 경과 시간(ms). 브라우저 왕복 시간은 포함하지 않습니다.
- 응답에 `Cache-Control: no-store`를 설정합니다. 매번 실제 요청을 보내므로 호출 비용이 발생할 수 있습니다.
- 실패하거나 응답이 비어 있으면 HTTP 502와 `{"error":"OpenAI connection check failed."}`를 반환합니다. 키·모델 접근·네트워크 상태를 확인하세요. 공급자 오류 원문은 응답과 로그에 노출하지 않습니다.
- 자동 재시도는 하지 않습니다. 실패 시 요청 횟수와 대기 시간이 늘어나는 것을 피하기 위한 개발용 설정입니다.

프로필 조건은 `local & openai & !test`입니다. 기본/local 단독/openai 단독/test 및 `local,openai,test`에서는 이 API와 OpenAI Chat 모델 빈이 생성되지 않으며, Swagger에 표시되지 않고 직접 요청하면 404입니다. 프로필 이름을 배열로 나열하는 `@Profile({"local", "openai"})`는 OR 조건이므로 사용하지 않았습니다.

자동 테스트는 실제 OpenAI 요청을 보내지 않습니다. `LlmPingProfileTests`는 비활성 프로필 조합을 검증하고, `LlmPingControllerTests`는 개발 프로필을 모사하되 OpenAI 모델 빈을 mock으로 교체해 성공·실패·원문 비노출을 확인합니다.

확인 후 서버를 Ctrl+C로 종료하고 필요하면 `unset OPENAI_API_KEY`로 현재 셸의 키를 제거합니다. 기본 실행을 다시 확인하려면 `./gradlew bootRun`을 사용합니다.

## Task API

- `POST /api/tasks`: 실제 필드는 `title`, `assigneeId`, `assigneeName`, `dueDate`입니다. 예: `{ "title": "회의실 예약 확인", "assigneeId": 1, "assigneeName": "지영", "dueDate": "2026-11-21" }`. `assigneeId`가 담당자를 결정하며, 이름만 보냈는데 동명이인이면 후보를 포함한 `400 AMBIGUOUS_MEMBER`를 반환합니다. 중복 TODO는 `409 DUPLICATE_TASK`와 `existingTaskId`를 반환합니다.
- 외부 HTTP 요청 DTO에서는 알 수 없는 필드를 `400 VALIDATION_ERROR`로 거절합니다. 이 검사는 MVC `RequestBodyAdvice`가 입력 DTO만 검사하므로 Spring AI의 OpenAI 응답·도구 인자 파서에는 적용되지 않습니다.
- `GET /api/tasks?assignee=지영`: 같은 이름을 가진 팀원의 Task를 모두 조회합니다. 한 명만 조회하려면 `GET /api/tasks?assigneeId=2`를 사용합니다. 응답의 담당자에는 `id`, `name`, `team`, `slackUserId`가 포함됩니다.
- `PATCH /api/tasks/{id}`: `{ "status": "DONE" }`로 상태 변경.

`Task`는 nullable `ManyToOne`으로 `Member`를 참조합니다. 담당자가 미정이면 `assignee`가 null입니다. DTO와 엔티티를 분리하고 Bean Validation을 적용했습니다. 오류는 `code`, `message`, `fieldErrors` 형식입니다.

`local` 프로필에서 `DevMemberDataInitializer`가 민수, 백엔드팀 지영, 디자인팀 지영, 해찬 샘플 팀원을 Slack 사용자 ID 기준으로 추가합니다. Slack ID는 가짜 값이며 운영 데이터로 쓰지 않습니다. 같은 이름은 허용하고 팀으로 구분합니다. Slack 연동 단계에서는 슬랙 멘션의 Slack 사용자 ID로 팀원을 확정합니다.

Member의 이름 유니크 제약이 제거되고 `team` 필드가 추가됐습니다. 이전 스키마의 로컬 H2 파일 DB에는 기존 유니크 제약이 남을 수 있으므로 새 샘플 데이터를 넣기 전에 `data/`를 초기화해야 합니다. 개발 DB 초기화는 사용자가 직접 수행합니다.

직접 Task 등록의 중복 확인은 서비스 검사이므로 동시 POST 두 건이 동시에 검사 구간을 통과할 가능성이 남아 있습니다. 이 경쟁 조건은 현재 해결하지 않았으며, 필요하면 DB 유니크 제약과 충돌 응답 처리를 추가해야 합니다.

## 회의 중복 표시와 Task 언급 횟수

같은 Task가 다른 회의에서 재사용되면 `(task, meeting)`별 `TaskMention`을 기록합니다. `mentionCount`는 중복으로 표시된 회의를 제외하고, Task가 언급된 서로 다른 회의 수를 조회 시 묶음 집계해 반환합니다. 회의 없는 `POST /api/tasks`로 생성한 Task의 초기 `mentionCount`는 0입니다.

같은 Task 묶음만 재사용한 회의도 Meeting과 ToolCallLog를 보존하며, 원본은 `duplicateOfMeetingId`로 표시합니다. 정확히 같은 회의록 본문 재전송은 기존 SHA-256 기준으로 기존 Meeting을 반환합니다.

TaskMention은 기존 개발 데이터에 대해 백필하지 않습니다. 로컬 H2 개발 데이터는 `data/`를 삭제해 초기화한 뒤 새로 시작합니다. 운영 DB로 전환할 때는 백필·스키마 변경을 마이그레이션 도구로 처리할 예정입니다.

## 다음 단계

Slack Block Kit·Discord embed 알림을 구현했으며 실제 채널 확인 후 매일 아침 워크플로를 진행합니다. 회의 처리 시간·LLM 호출 횟수·도구별 시간·검증 실패 비율은 실제로 수집하고 개선 전 기준값을 먼저 확보합니다.

## 버전 근거

- Java 21 / Spring Boot 3.5.16 / Spring AI 1.1.8 / springdoc 2.8.17 / Gradle 8.14.3
- [Spring AI 공식 저장소의 Boot 호환성](https://github.com/spring-projects/spring-ai)
- [springdoc 공식 호환성 표](https://springdoc.org/#what-is-the-compatibility-matrix-of-springdoc-openapi-with-spring-boot)
- [Spring AI OpenAI 설정 문서](https://docs.spring.io/spring-ai/reference/api/chat/openai-chat.html) — 최신 문서는 버전이 다를 수 있으므로 프로젝트의 1.1.x API와 대조합니다.

진행 기준은 [AGENTS.md](AGENTS.md), 문제 해결 기록은 [DEVLOG.md](DEVLOG.md)에 둡니다.


## 동명이인 개선 실험 — 단계 A

`findMember(name, team?)`는 회의록에 팀이 있으면 첫 호출에서 함께 조회한다. 이름은 앞뒤 공백을 제거해 비교하고, 팀은 대소문자·공백·끝의 팀 접미사를 정규화해 비교한다. 같은 이름 후보가 여러 명이면 팀 재조회/미정 선택을 중립적으로 안내한다. 존재하지 않는 팀은 `MEMBER_TEAM_NOT_FOUND`와 같은 이름의 후보를 모델에 반환하며 다른 팀원을 자동 배정하지 않는다.

`createTask` 도구의 담당자 입력은 `assignee` 하나다. `member:<id>`는 현재 회의의 성공한 findMember 결과에 포함된 실제 ID만 허용한다. `unassigned:AMBIGUOUS`는 경고 `동명이인 확인 필요`, `unassigned:NOT_FOUND`는 경고 `등록되지 않은 담당자`, `unassigned:NONE`은 담당자 언급 없음이며 경고를 붙이지 않는다. 회의 tasks에는 `unassignedReason`도 반환한다. 미정 기존 Task를 findTasks로만 재사용할 때도 모델이 전달한 `unassignedReason`을 응답에 보존한다.

단계 A는 findTasks의 필수 호출 정책을 유지한다. 단계 B의 선택 호출 전환은 A 실측 후 별도로 진행한다. 이번 변경은 DB 스키마를 바꾸지 않는다. 단, 같은 본문은 SHA-256 재전송 처리로 기존 결과를 반환하므로 실측할 때 과거 결과를 새 실행으로 오인하지 않아야 한다. 동일한 실험 입력을 다시 실행하려면 사용자가 로컬 개발 데이터를 초기화한 뒤 실행한다.

실행·확인 순서와 설계 근거는 [memberDisambiguationStepADone.md](memberDisambiguationStepADone.md), 실측 표는 [DEVLOG.md](DEVLOG.md)에 있다. 실제 OpenAI 실측은 사용자가 수행한다.


## 팀 불일치 안전 처리와 실패 도구 로그

팀 필드 생략은 조건 없는 조회이며, 빈 문자열·공백은 INVALID_MEMBER_TEAM으로 거절한다. 현재 회의에서 findMember(name, team)가 MEMBER_TEAM_NOT_FOUND였다면 이후 같은 이름의 다른 팀원 배정은 createTask와 findTasks가 거절한다. 모델은 unassigned:TEAM_MISMATCH로 등록하고, 응답은 unassignedReason=TEAM_MISMATCH, warning=팀 불일치 확인 필요를 포함한다. 기존 findTasks 필수 호출 정책은 유지한다.

실패 도구 결과도 ToolCallLog.result에 마스킹·최대 4,000자 제한 후 저장한다. errorCode/errorMessage는 계속 별도로 제공한다. 과거 실패 로그의 null 결과는 복원하지 않는다.

알려진 한계: 한 회의에서 같은 이름의 서로 다른 사람을 다루면, 앞선 팀 불일치 때문에 나중의 올바른 배정도 제한될 수 있다. 오배정을 막기 위한 보수적 정책이다. 기존 동시 중복 등록 등의 한계도 유지한다.

A 실험 표의 A 적용 후 값은 이번 팀 불일치·실패 결과 로그 수정을 반영한 상태에서 측정한다. B는 구현하지 않았다. 확인 순서는 [teamMismatchFixDone.md](teamMismatchFixDone.md)에 있다.


## 회의록의 팀 근거와 배정 권한

findMember에 team을 전달하면 서버가 현재 Meeting.rawText에 팀 이름이 있는지 먼저 확인한다. 대소문자와 공백을 정규화하고 팀 이름의 끝 '팀' 접미사를 제거한 뒤 부분 문자열로 대조한다. 회의록에 없는 팀은 TEAM_NOT_IN_TRANSCRIPT로 거절하고 validationFailureCount에 포함하며, 마스킹·길이 제한을 거친 실패 result도 ToolCallLog에 남긴다. 빈 값과 정규화 후 빈 이름('팀' 등)은 INVALID_MEMBER_TEAM이다.

모델에게 보여주는 정보(후보 목록)와 배정 권한을 분리했다. 성공한 단일 후보 findMember 결과만 현재 회의의 배정 권한이 된다. AMBIGUOUS의 다중 후보 목록은 정보이며 createTask 배정이나 findTasks의 담당자 조회·기존 Task 재사용을 허용하지 않는다.

알려진 한계:
- BE팀 같은 약칭을 백엔드팀으로 확장하지 않는다.
- 문자열 존재만 확인하므로 '백엔드팀 회의에서 지영은…' 같은 문장에서 특정 사람의 소속 관계까지 증명하지 못한다. 인용·부정 등 다른 문맥의 팀 언급도 구분하지 않는다.
- '팀' 접미사를 떼고 부분 문자열로 비교하므로 팀 이름과 같은 단어가 업무 내용에 나오면 통과할 수 있다. 예: '지영은 디자인 시안을 검토한다.' + team='디자인팀'은 '디자인'이 존재해 허용된다. 이 동작은 knownLimitationDesignWorkWordAllowsDesignTeamLookupAndAssignment 테스트로 고정했다.
- 단일 후보 조회 이력은 회의 단위이며 작업별로 팀과 사람을 연결하지 않는다. 기존 보수적 팀 불일치 가드와 동시 Task 등록 등의 한계도 유지한다.

A 적용 후 실측은 이번 회의록 팀 대조·표기 정규화·단일 후보 권한 수정까지 반영한 상태를 기준으로 한다. B는 아직 진행하지 않았다. 실행·확인 방법은 [teamEvidenceFixDone.md](teamEvidenceFixDone.md)에 있다.


## 단계 C — AMBIGUOUS 메시지에 후보 팀 언급 정보 제공

팀 없는 다중 후보 조회에서 서버가 기존 TeamNames 정규화·부분 문자열 비교를 재사용해 후보 팀의 회의록 언급 여부를 계산한다. 정규화한 서로 다른 팀을 기준으로, 0개는 unassigned:AMBIGUOUS 등록, 1개는 team을 넣은 재조회, 여러 개는 중립적 문맥 확인을 안내한다. 후보 데이터 구조와 단일 후보 권한·TEAM_NOT_IN_TRANSCRIPT·TEAM_MISMATCH 가드·findTasks 필수 호출은 유지한다. 같은 팀에서도 동명이인이 남으면 단일 후보로 취급하지 않는다.

목표는 정확도를 유지하며 모델의 불필요한 재조회와 오류 복구를 줄이는 것이다. 자동 테스트 131개가 통과했고, A·C 각 입력 3회 실측 원값과 평균을 DEVLOG에 기록했다. 표본이 작고 C 미정 경로에서 findTasks 생략도 함께 관측돼 메시지 단독 효과나 일반화된 성능 개선을 단정하지 않는다. 지영 A 2·3회차의 실패 코드는 미확인으로 유지한다. B는 보류하며 기존 알려진 한계도 유지한다. [memberTeamHintStepCDone.md](memberTeamHintStepCDone.md)에 변경·확인 방법을 정리했다.


## 현재 실제 동작 — findTasks 지시와 관측 차이

정책·시스템 프롬프트는 여전히 findTasks 필수 호출을 지시하지만, C의 지영 미정 실측 3회는 모두 findMember → createTask였고 findTasks를 생략했다. 호출 순서는 서버가 강제하지 않는다. createTask는 독립적으로 담당자 미정(null) + 정규화 제목 + TODO를 검사하므로 순차 요청의 중복 검사는 유지된다. 기존 서비스/H2 테스트 11개를 재실행해 통과를 확인했다. 동시 등록 경쟁 한계는 그대로다.

이 관찰을 B의 선택 호출 정책 검토 근거로 기록했으며, 현재 정책은 변경하지 않았다. A 지영 2·3회차의 실패 코드는 여전히 미확인이다. C 실측 원값·평균·검증 근거는 DEVLOG에 있다.


## 회의 완료 알림 — Slack / Discord

알림은 모델 도구가 아니라 서버의 완료 후속 처리다. Meeting DONE/FAILED 저장과 이벤트 발행을 짧은 트랜잭션으로 묶고, AFTER_COMMIT 동기 리스너가 단일 메시지를 전송한다. 알림 결과는 REQUIRES_NEW 트랜잭션으로 저장한다. 전송 실패가 Meeting DONE을 FAILED로 바꾸지 않는다. 기존 processingTimeMs는 회의 처리 시간이며, 알림 대기 시간은 포함하지 않는다. API 응답 시간에는 알림 대기가 더해질 수 있다.

Notifier 인터페이스로 설계해서 두레이 같은 사내 메신저도 구현체 추가로 연결할 수 있다. 두레이는 이번에 구현하지 않았다. Slack 생성 장애로 Discord로 먼저 검증한다. 사용자는 Discord 웹훅 자체 연결을 확인했으며, 이 서비스의 실제 메시지 표시는 사용자 확인 단계다. 최종 데모는 Slack을 사용한다.

- 실제 전송: local & openai & !test + NOTIFIER_TYPE(slack/discord) + 해당 URL 환경변수가 모두 있어야 한다. 기본 local만 실행하거나 test 프로필이면 실제 전송하지 않는다.
- notificationStatus: PENDING / SENT / FAILED / SKIPPED. notificationType, notificationErrorCode, notifiedAt도 조회 응답에 제공한다. notifiedAt은 공급자 성공 응답 시각이며 사람이 읽었다는 뜻은 아니다.
- URL이나 활성화 조건이 없으면 SKIPPED / NOT_CONFIGURED. 과거 데이터의 알림 상태가 null이면 SKIPPED / NOT_ATTEMPTED로 표시하며 과거 회의를 자동 전송하지 않는다.
- 동일 본문 재전송은 기존 결과와 기존 알림 상태를 반환하며 추가 전송하지 않는다. 같은 Task 묶음인 새 Meeting은 원본 ID와 건수를 짧게 알린다. Meeting FAILED는 일반화된 짧은 실패 알림을 보낸다.
- summary는 LLM 문장을 사용하지 않고 서버 tasks의 신규·기존·미정 건수로 조립한다. 이름·팀 텍스트, 마감일, CREATED/EXISTING, N번째 언급, 경고를 표시한다. 실제 사용자 멘션은 아직 사용하지 않는다.
- 항상 메시지 1건이다. 전체 목록이 들어가면 전부 넣는다. 넘으면 생략 안내와 footer 공간을 확보해 앞에서부터 들어가는 완전한 항목만 넣고 '외 N건은 GET /api/meetings/{id}에서 확인'을 붙인다. Slack은 Block Kit/section/fallback 제한, Discord는 단일 embed description 및 전체 문자 제한을 적용한다. 중간 항목만 건너뛰거나 할 일 문자열을 반으로 자르지 않는다.
- 연결 타임아웃 1초 / 요청 타임아웃 3초, 자동 재시도 없음. **타임아웃 시 이미 전송됐을 수 있어 중복 알림 위험이 있으므로, 중복보다 누락을 택하고 FAILED로 남김**(TIMEOUT_DELIVERY_UNKNOWN). 이는 실제 미전송을 확정했다는 뜻은 아니다.
- **정확히 한 번 전달은 보장하지 않음. 향후 Outbox 패턴으로 개선 가능.** 커밋 후 프로세스 종료나 상태 저장 장애 시 PENDING이 남을 수 있다. Outbox 역시 외부 공급자 중복 수신까지 자동 해결하는 것은 아니며 별도 멱등성 검토가 필요하다. 현재 재전송 API는 없다.
- URL·HTTP 응답 본문·예외 원문은 기록하지 않는다. 실패 로그는 Meeting ID와 안전한 코드만 기록한다. ToolCallLog의 Slack/Discord 웹훅 URL 형태도 마스킹한다.

macOS 키체인에서 환경변수로 읽어 실행한다. .env.example은 변수 이름 안내이며 자동 로드되지 않는다.

```zsh
export OPENAI_API_KEY="$(security find-generic-password -a "$USER" -s meetodo-openai -w)"
export DISCORD_WEBHOOK_URL="$(security find-generic-password -a "$USER" -s meetodo-discord-webhook -w)"
export NOTIFIER_TYPE=discord
export OPENAI_CHAT_MODEL=gpt-4o-mini
./gradlew bootRun --args='--spring.profiles.active=local,openai'
```

확인 시나리오·예상 메시지·잘림 기준·공식 문서 근거는 [fourthStepDone.md](fourthStepDone.md)에 있다. 자동 테스트는 mock LLM과 loopback HTTP 서버만 사용한다.


4단계 자동 검증: 전체 182개 테스트, 실패·오류·건너뜀 0 및 bootJar 성공. 실제 Slack·Discord 서비스 통합 전송은 사용자가 확인한다. 작업 중 발견한 ToolCallLog 웹훅 URL 마스킹 누락과 단일 메시지 잘림 경계 수정은 DEVLOG에 기록했다.
