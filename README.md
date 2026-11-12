# MeetoDo

회의록에서 할 일을 추출해 담당자·마감일과 함께 등록하고 Slack으로 알리는 백엔드 프로젝트입니다. 별도 프론트엔드 없이 Swagger로 입력하고 Slack으로 결과를 받는 서비스를 목표로 합니다.

Task API·회의 처리 에이전트·Slack/Discord 완료 알림·평일 마감 다이제스트를 구현했습니다. OpenAI 연결과 완료 알림의 실제 채널 표시는 사용자가 확인했습니다. 5단계 SENT·ALREADY_SENT·force 재전송도 사용자가 실제 Slack에서 확인했습니다.

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
| `application-openai.yml` | `(local | docker) & openai & !test`일 때만 OpenAI Chat 활성화. 단일 시도 및 민감한 원문을 출력하는 로거 비활성화 |
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

`local` 프로필에서 `DevMemberDataInitializer`가 김민수(백엔드팀), 이지영(백엔드팀), 이지영(디자인팀), 전해찬(백엔드팀) 샘플 팀원을 Slack 사용자 ID 기준으로 추가합니다. Slack ID는 가짜 값이며 운영 데이터로 쓰지 않습니다. 같은 이름은 허용하고 팀으로 구분합니다. Slack 연동 단계에서는 슬랙 멘션의 Slack 사용자 ID로 팀원을 확정합니다.

Member의 이름 유니크 제약이 제거되고 `team` 필드가 추가됐습니다. 이전 스키마의 로컬 H2 파일 DB에는 기존 유니크 제약이 남을 수 있으므로 새 샘플 데이터를 넣기 전에 `data/`를 초기화해야 합니다. 개발 DB 초기화는 사용자가 직접 수행합니다.

직접 Task 등록의 중복 확인은 서비스 검사이므로 동시 POST 두 건이 동시에 검사 구간을 통과할 가능성이 남아 있습니다. 이 경쟁 조건은 현재 해결하지 않았으며, 필요하면 DB 유니크 제약과 충돌 응답 처리를 추가해야 합니다.

## 회의 중복 표시와 Task 언급 횟수

같은 Task가 다른 회의에서 재사용되면 `(task, meeting)`별 `TaskMention`을 기록합니다. `mentionCount`는 중복으로 표시된 회의를 제외하고, Task가 언급된 서로 다른 회의 수를 조회 시 묶음 집계해 반환합니다. 회의 없는 `POST /api/tasks`로 생성한 Task의 초기 `mentionCount`는 0입니다.

같은 Task 묶음만 재사용한 회의도 Meeting과 ToolCallLog를 보존하며, 원본은 `duplicateOfMeetingId`로 표시합니다. 정확히 같은 회의록 본문 재전송은 기존 SHA-256 기준으로 기존 Meeting을 반환합니다.

TaskMention은 기존 개발 데이터에 대해 백필하지 않습니다. 로컬 H2 개발 데이터는 `data/`를 삭제해 초기화한 뒤 새로 시작합니다. 운영 DB로 전환할 때는 백필·스키마 변경을 마이그레이션 도구로 처리할 예정입니다.

## 다음 단계

Slack/Discord 완료 알림의 사용자 실제 확인과 평일 마감 워크플로 구현을 마쳤습니다. 마감 다이제스트의 실제 Slack 확인과 Docker Compose·PostgreSQL 전환을 마쳤으며, 다음은 Kubernetes 다중 인스턴스 검증입니다. 회의 처리 시간·LLM 호출 횟수·도구별 시간·검증 실패 비율은 실제로 수집하고 개선 전 기준값을 먼저 확보합니다.

## 버전 근거

- Java 21 / Spring Boot 3.5.16 / Spring AI 1.1.8 / springdoc 2.8.17 / Gradle 8.14.3
- [Spring AI 공식 저장소의 Boot 호환성](https://github.com/spring-projects/spring-ai)
- [springdoc 공식 호환성 표](https://springdoc.org/#what-is-the-compatibility-matrix-of-springdoc-openapi-with-spring-boot)
- [Spring AI OpenAI 설정 문서](https://docs.spring.io/spring-ai/reference/api/chat/openai-chat.html) — 최신 문서는 버전이 다를 수 있으므로 프로젝트의 1.1.x API와 대조합니다.

진행 기준은 [AGENTS.md](AGENTS.md), 문제 해결 기록은 [DEVLOG.md](DEVLOG.md)에 둡니다.


## 동명이인 개선 실험 — 단계 A

`findMember(name, team?)`는 회의록에 팀이 있으면 첫 호출에서 함께 조회한다. 이름은 아래 E의 공통 성명·이름·제한된 호칭 규칙으로 비교하고, 팀은 대소문자·공백·끝의 팀 접미사를 정규화해 비교한다. 같은 이름 후보가 여러 명이면 팀 재조회/미정 선택을 중립적으로 안내한다. 존재하지 않는 팀은 `MEMBER_TEAM_NOT_FOUND`와 같은 이름의 후보를 모델에 반환하며 다른 팀원을 자동 배정하지 않는다.

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

Notifier 인터페이스로 설계해서 두레이 같은 사내 메신저도 구현체 추가로 연결할 수 있다. 두레이는 이번에 구현하지 않았다. Slack 생성 장애로 Discord를 먼저 검증했고, 이후 사용자가 설정만 바꿔 Slack에서도 실제 완료 알림과 상세 경고를 확인했다. 최종 데모는 Slack을 사용한다.

- 실제 전송: (local | docker) & !test + NOTIFIER_TYPE(slack/discord) + 해당 URL 환경변수가 있어야 한다. 5단계부터 OpenAI 없는 다이제스트를 위해 메신저 빈에서 openai 조건을 분리했다. URL/타입 미설정과 test 프로필에서는 실제 전송하지 않는다. OpenAI 빈은 (local | docker) & openai & !test에서만 생성하며 기존 local/test 동작은 유지한다.
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


### 미정 담당자 상세 정보 — 단계 D

4단계 Discord 실제 채널 알림은 사용자가 확인했다. 미정 담당자의 이름·요청 팀·후보(id, 이름, 팀)를 `unresolvedAssignee` 스냅샷으로 Task에 저장하며 GET /api/tasks, 회의 응답 tasks와 알림 경고에 제공한다. 데이터 근거는 현재 회의의 findMember 도구 기록이다. createTask는 모델이 다시 적은 이름 대신 findMember 결과의 `lookupCallOrder`를 전달받아 현재 회의·도구·사유를 검증한다. AMBIGUOUS/NOT_FOUND/TEAM_MISMATCH에 필요하고 NONE에는 필요 없다.

Task를 재사용할 때 최초 스냅샷을 덮어쓰지 않는다. 재사용 회의의 응답·알림은 그 회의의 조회 근거를 보여준다. 기존 데이터는 백필하지 않으며 상세 정보가 없으면 기존 경고만 표시한다. 현재 PATCH /api/tasks/{id}는 상태만 변경한다. 향후 담당자 배정 API를 추가할 때 배정과 동시에 현재 미정 정보와 경고를 비우고 과거 도구 기록은 보존할 예정이다.

이름·팀은 최대 100자, 후보는 최대 20명과 생략 건수로 제한한다. 비밀값 마스킹과 제어문자 정리를 적용하며 Discord allowed_mentions 비활성화, Slack 일반 텍스트와 최종 길이 제한을 유지한다. 실제 멘션 기능은 보류한다.

알려진 한계: **findMember에 전달된 이름 자체는 모델이 추출한 값**이다. 서버 조회 기록은 모델이 원문에서 이름을 정확히 추출했음을 보장하지 않는다. 서로 다른 미정 인물도 같은 제목의 TODO이면 기존 중복 정책으로 합쳐질 수 있다. D는 사용자 실측을 완료했고 원값과 C 비교는 DEVLOG에 보존한다. E는 별도 실험으로 아직 측정하지 않았다. findTasks 정책 변경 B는 보류한다.

조회 로그가 기존 4,000자 제한으로 잘려 근거 JSON을 읽을 수 없으면 등록을 추측하지 않고 참조 오류로 거절한다. 상세 경고가 길어 알림 길이 제한을 넘으면 기존 단일 메시지 정책대로 접두 목록과 생략 안내만 보낸다. D 구현 당시 전체 218개 자동 테스트와 bootJar가 통과했다. 이후 사용자가 Slack 실제 경고 3종과 D 성능 실측을 완료했고 DEVLOG에 기록했다. [unassignedDetailStepDDone.md](unassignedDetailStepDDone.md)에 확인 절차를 정리했다.


### 성명 분리 — 단계 E

Member는 `familyName`, `givenName`을 명시적으로 저장하고 `fullName`은 두 값을 합쳐 계산한다. 이름 문자열을 잘라 성을 추측하지 않는다. 예를 들어 남궁/민, 제갈/현처럼 두 글자 성도 명시적으로 넣는다. 응답의 기존 `name`·`assigneeName` 필드에는 전체 이름을 표시하며 알림은 `이지영(백엔드팀)` 형식이다. 같은 전체 이름의 동명이인도 허용한다.

공통 `MemberNames`는 앞뒤 공백 정리 후 전체 이름 정확 일치를 우선하고 없으면 이름(givenName) 정확 일치를 찾는다. 둘 다 없을 때만 끝의 님/씨를 한 번 제거하고 같은 순서로 다시 조회한다. `지영님`, `지영 님`, `민수 씨`를 허용하지만 직급·조사·오타·부분 문자열은 서버가 추측해서 제거하지 않는다. 실제 이름 끝의 씨가 정확히 일치하면 먼저 그 사람을 찾는다. 전체 이름이 다른 사람의 이름과 충돌하면 전체 이름 후보가 우선이다. 팀으로 필터링한 결과가 없다고 다른 인물 후보로 바꾸거나 팀 조건을 버리지 않는다.

**이름 추출은 모델, 매칭 규칙은 서버**다. findMember 도구 설명과 @ToolParam, 시스템 프롬프트에 `name에는 호칭·직급·조사를 뺀 이름만 넣으세요. 예: '지영 대리는' → '지영', '민수 팀장님이' → '민수'`를 넣었다. 서버는 님/씨만 보조 처리한다. 모델이 `지영 대리는`을 그대로 보내면 NOT_FOUND가 될 수 있으며, 문자열을 임의로 자르거나 성을 만들어 보정하지 않는다. 조회 인자가 모델 추출 결과라는 한계와 팀 부분 문자열 대조·동시 Task 등록 등의 기존 한계는 유지한다.

직접 POST /api/tasks의 assigneeName, GET /api/tasks의 assignee 필터, findTasks의 이름 조회 및 ID-이름 검증도 같은 규칙을 사용한다. 직접 등록은 assigneeId 우선이며 이름만으로 여러 명이면 AMBIGUOUS_MEMBER다. 팀 불일치 가드도 같은 사람의 전체 이름·이름·호칭을 인식해 표현을 바꾼 재조회로 우회하지 못하게 한다. 가드는 기존처럼 보수적이며 서로 다른 인물을 한 회의에서 다룰 때 배정을 제한할 수 있다.

D의 `requestedAssigneeName`은 원래 findMember 조회 인자를 보존한다. 예를 들어 `지영님`으로 조회했다면 그대로 저장하고 후보 이름은 `이지영`으로 보여준다. 비밀값 마스킹·길이 제한은 유지한다.

**개발 DB는 서버 종료 후 사용자가 data/를 초기화해야 한다.** 기존 단일 name 컬럼의 값을 성·이름으로 추측하는 백필은 하지 않는다. 새 NOT NULL 컬럼과 이전 name 컬럼 제약이 있는 기존 H2 파일을 재사용하지 않는다. 운영 DB 전환 시 명시적 데이터와 마이그레이션 도구로 처리한다.

measure.sh 원문은 바꾸지 않았다. 기존 “지영은 …”, “백엔드팀 지영은 …”는 미정/배정 판정을 유지하도록 자동 테스트하며, 전체 이름 표시로 모델에 전달되는 후보·결과 내용은 달라진다. 후속 실측은 DEVLOG의 **E 적용 후(성명 분리)** 빈 칸에 따로 기록하고 기존 D 수치와 섞지 않는다. B와 실제 멘션 기능은 계속 보류한다. 확인 절차는 [memberNameStepEDone.md](memberNameStepEDone.md)에 정리한다.

E 전체 검증: `./gradlew test bootJar --no-daemon` 성공, **250개 테스트 / 실패·오류·건너뜀 0**. 자동 테스트는 로직·설정 검증이며 E의 실제 모델 입력 추출·Slack 표시·처리 시간은 사용자가 별도로 확인한다.


## 평일 마감 알림 — 5단계

LLM을 사용하지 않는다. TODO 상태와 날짜 구간을 정해진 규칙으로 분류하는 반복 작업이라 모델 판단이 필요 없으며 **이 워크플로의 LLM 호출·토큰 비용은 0**이다(서비스·인프라 비용 전체가 0이라는 뜻은 아니다). 기존 Notifier와 Slack Block Kit/Discord embed 변환·전송을 재사용한다.

- 기본 예약 비활성. `MEETODO_DIGEST_ENABLED=true`일 때 평일 09:00 Asia/Seoul에 실행한다. `MEETODO_DIGEST_CRON` 기본값은 `0 0 9 * * MON-FRI`다. 평일은 월~금이며 공휴일 달력을 적용하지 않는다.
- 날짜 계산은 주입 Clock을 서울 시간대로 사용한다. TODO 중 마감 지남(dueDate<오늘), 오늘 마감, 3일 이내(내일~오늘+3일, 달력 날짜)만 보낸다. 마감일 없는 TODO는 본문에서 빼고 하단 개수에 표시한다. DONE과 오늘+4일 이후는 제외한다.
- 담당자 ID별로 묶고 전체 이름(팀)을 표시한다. 같은 이름 다른 팀원도 별개다. 미정 묶음은 마지막이며 저장된 상세 경고를 유지한다. 담당자마다 날짜·ID 순서로 정렬하고 각 Task 한 줄에 구간을 표시한다.
- 대상이나 Notifier 설정이 없으면 SKIPPED로 남기고 전송하지 않는다. 항상 단일 메시지이며 제한을 넘으면 최대 접두 목록과 `외 N건은 GET /api/tasks에서 확인`을 붙인다.
- DailyDigest(digestDate unique, status, sentAt, 구간별 건수·마감 없음 건수)를 전송 전에 별도 트랜잭션으로 선점·커밋한다. 같은 날짜 유니크 충돌은 실패한 트랜잭션 바깥에서 처리한다. 정상 동시 실행은 한 번만 전송한다.
- 전송 실패는 FAILED/일반화된 오류 코드로 저장하고 자동 재시도하지 않는다. 이미 보냈을 가능성이 있는 타임아웃에서 중복보다 누락을 택한다. PENDING/SKIPPED/FAILED도 그날 행이 있으면 후속 일반 실행은 ALREADY_SENT다. 이 응답은 이미 선점됐다는 뜻이며 실제 전송 완료를 보장하지 않는다.
- 개발 API: `POST /api/dev/digest/run?date=2026-11-20&force=false`. local 프로필에서만 존재하며 OpenAI/예약 활성화는 필요 없다. date 생략 시 Clock의 서울 오늘이다. date 형식 오류는 400 VALIDATION_ERROR. 응답은 digestDate, status, overdueCount, todayCount, upcomingCount, noDueDateCount다.
- `force=true`는 그 날짜의 같은 행을 마지막 실행 결과로 갱신하며 의도적으로 재전송한다. 개발 확인용 예외이고 동시 강제 실행의 중복 발송을 막지 않는다. 운영 프로필에는 수동 API가 없다.

데모 실행은 기존 SLACK_WEBHOOK_URL 설정을 유지하고 `NOTIFIER_TYPE=slack ./gradlew bootRun --args='--spring.profiles.active=local'`로 시작한다. OpenAI 키가 필요 없다. 예약을 켤 때만 `export MEETODO_DIGEST_ENABLED=true`를 추가한다. cron 변경 예: `export MEETODO_DIGEST_CRON='0 30 9 * * MON-FRI'`.

기존 E 스키마 DB에는 새 daily_digests 테이블을 ddl-auto=update로 추가하므로 이번 단계만을 위해 data/를 초기화할 필요는 없다. 선점 후 프로세스 종료는 PENDING을 남길 수 있고, 전송 성공 후 상태 저장 실패는 전달 여부가 불명확할 수 있다. 정확히 한 번 전달은 보장하지 않으며 향후 Outbox/재처리 정책으로 개선할 수 있다. 여러 인스턴스 보호는 **같은 DB를 공유할 때** 유효하다. 현재 H2 파일을 여러 서버가 공유하는 배포는 지원하지 않으며 PostgreSQL 단계에서 검증한다. 현재 실제 Notifier는 local & !test이고 배포 프로필에서의 활성화는 배포 단계에 맞춰야 한다.

[5단계 확인 문서](fifthStepDone.md)에 데모 데이터·실행 요청·Slack 예상 모습·변경 파일·면접 질문을 정리했다.

5단계 검증: `./gradlew test bootJar --no-daemon` 성공, 전체 **279개 / 실패·오류·건너뜀 0**. 사용자가 이후 실제 Slack에서 첫 SENT·재실행 ALREADY_SENT·force SENT를 확인했다. 평일9시 자동 실행은 별도 확인이다.


## Docker Compose + PostgreSQL — 7단계

사용자가 보고한 전날 H2 파일 잠금 문제는 로컬 파일 DB를 여러 프로세스가 함께 열려 할 때의 제약과 연결된다. 이번 작업에서 그 과거 오류를 다시 재현하거나 정확한 예외·발생 횟수를 확인한 것은 아니다. 다중 앱이 같은 데이터를 쓰려면 서버형 DB가 필요하므로 docker 프로필에서는 PostgreSQL 16을 사용한다. local H2 파일·test H2 메모리 및 기존 ddl-auto 정책은 유지한다. H2 데이터를 PostgreSQL로 자동 복사하지 않는다.

- docker: Flyway V1 초기 스키마 적용 후 Hibernate ddl-auto=validate. local/test: Flyway 비활성.
- V1은 현재 6개 테이블·6개 외래 키와 회의 해시/TaskMention(task_id,meeting_id)/DailyDigest.digest_date 유니크 제약·로그 조회 인덱스를 포함한다. 샘플 데이터는 마이그레이션에 넣지 않는다.
- PostgreSQL의 JSON 스냅샷은 일반 text다. 실제 PostgreSQL 테스트에서 기존 @Lob/OID가 autocommit 조회 오류를 내는 것을 확인한 뒤 수정했다.
- 멀티 스테이지 Dockerfile은 Gradle Wrapper로 bootJar를 만들고 JRE21에 JAR만 복사한다. 테스트는 호스트에서 별도로 돌린다. 런타임 UID/GID 10001, /actuator/health 헬스체크.
- Compose는 PostgreSQL이 healthy일 때 앱을 시작하며 DB는 named volume에 보존한다. app은 localhost:8080, replica 프로필의 app2는 localhost:8081이다. 포트는 APP_PORT/APP2_PORT로 변경 가능하다. DB 포트는 호스트에 공개하지 않는다.
- .dockerignore는 빌드 입력 허용 목록이라 .env·data·에이전트 설정·Git 등이 이미지 컨텍스트에 들어가지 않는다. DB 암호·OpenAI 키·웹훅 URL은 환경변수로만 전달하며 저장소에는 빈 .env.example만 둔다. .env는 gitignore 대상이다.
- Docker 기본은 키 없는 Task·다이제스트다. OpenAI 회의 처리는 SPRING_PROFILES_ACTIVE=docker,openai를 명시한다. docker에 local을 함께 켜지 않는다. local용 llm-ping은 Docker에 노출하지 않는다.
- Docker의 POST /api/dev/digest/run은 MEETODO_DEV_ENDPOINTS_ENABLED=true일 때만 노출한다(기본 false). Notifier는 docker에서도 활성화할 수 있지만 test가 함께 있으면 실제 전송 빈을 만들지 않는다. 스케줄 기본 비활성은 그대로다.

### 실행 명령

Docker Desktop 실행 후 DB 암호를 준비한다. 기존 볼륨이 있다면 최초 사용한 암호를 유지한다. 키체인에 처음 저장할 때만 아래 add 명령을 사용한다(기존 항목을 덮어쓰지 않는다).

```zsh
security add-generic-password -a "$USER" -s meetodo-postgres -w "$(openssl rand -hex 24)"
export POSTGRES_PASSWORD="$(security find-generic-password -a "$USER" -s meetodo-postgres -w)"
export OPENAI_API_KEY="$(security find-generic-password -a "$USER" -s meetodo-openai -w)"
# SLACK_WEBHOOK_URL은 이미 실제 확인한 환경변수 값을 유지한다.
export NOTIFIER_TYPE=slack
export MEETODO_DEV_ENDPOINTS_ENABLED=true
export MEETODO_DIGEST_ENABLED=false
export SPRING_PROFILES_ACTIVE=docker

docker compose build app
docker compose up -d --wait
docker compose ps
docker compose logs -f app
```

둘째 앱: `docker compose --profile replica up -d --wait`. Swagger는 http://localhost:8080/swagger-ui/index.html 및 http://localhost:8081/swagger-ui/index.html 이다. port 충돌이면 APP_PORT/APP2_PORT를 바꾼다.

Docker에는 개발 팀원이 자동 생성되지 않는다. 필요할 때 명시적으로:

```zsh
docker compose exec -T postgres sh -c 'psql -U "$POSTGRES_USER" -d "$POSTGRES_DB"' < scripts/docker-demo-members.sql
```

회의 처리도 확인하려면:

```zsh
export SPRING_PROFILES_ACTIVE=docker,openai
docker compose --profile replica up -d --wait
```

종료는 `docker compose --profile replica down`(데이터 유지). **DB 초기화는 사용자가 필요할 때만** `docker compose --profile replica down -v`로 한다(해당 프로젝트 DB 데이터 삭제). H2 data/와 PostgreSQL 볼륨은 별개다. POSTGRES_PASSWORD 환경변수를 바꾸는 것만으로 기존 DB 암호가 변경되지는 않는다.

전체 테스트는 Docker Desktop이 켜진 상태에서 `./gradlew test bootJar --no-daemon`. PostgreSQL Testcontainers 테스트는 Docker가 없을 때 조용히 건너뛰지 않고 실패한다. 실제 외부 OpenAI·메신저는 테스트에서 호출하지 않는다.

[fifthStepDone.md](fifthStepDone.md)의 실제 Slack 날짜 선점 검증에 이어 [seventhStepDone.md](seventhStepDone.md)에 이미지·마이그레이션·동시 앱 검증과 두 인스턴스 요청 명령을 정리했다.

7단계 실제 검증: 전체 **301개 / 실패·오류·건너뜀0**, 실제 PostgreSQL Testcontainers13개와 Docker프로필9개 포함. 이미지 build·두앱 healthy/non-root·로컬mock 동시발송1회·컨테이너 재생성 뒤 데이터 보존 확인. 외부 메신저/OpenAI 호출 및 사용자 DB 삭제는 하지 않았다.


## Slack 봇 — 단계 1 (Swagger 입력)

기존 `NOTIFIER_TYPE=slack` 웹훅·discord에 `slack-bot`을 추가한다. `MEETODO_SLACK_BOT_ENABLED=true`와 SLACK_APP_TOKEN(xapp), SLACK_BOT_TOKEN(xoxb)이 있어야 local/docker에서 Socket Mode를 시작한다. test·기본 실행은 연결하지 않는다. SLACK_CHANNEL_ID는 결과를 보낼 채널이다. 토큰은 키체인→환경변수만 사용하고 SDK 진단 로그와 공통 ToolCallLog 마스킹에 xoxb/xapp을 포함한다.

현재 단계는 **Swagger POST /api/meetings → chat.postMessage → 완료 버튼**이다. /meetodo 모달·제출·처리 중 메시지·중복 ephemeral 흐름은 단계2 확인 전 구현하지 않는다.

- 실제 U/W 영숫자 Slack ID만 `<@ID>` 멘션으로 생성하고 U-DEV 가짜 ID는 전체 이름(팀)으로 표시한다. 제목·미정 경고 등의 사용자 문자열은 이스케이프해 임의 멘션을 만들지 않는다.
- TODO만 ✅ 완료 버튼을 붙인다. Task에 완료자 Slack ID·완료 시각을 저장한다. 담당자가 있으면 본인만 완료하며 다른 사람에게는 ephemeral로 “담당자만 완료할 수 있어요”를 보낸다. 미정은 누구나 완료 가능하다.
- Socket Mode 실제 wire ACK를 먼저 보낸 후 bounded worker에서 DB/메신저 처리를 수행한다. SDK 기본 adapter의 ctx.ack 반환만으로 ACK 전송 순서를 가정하지 않았다.
- 상태 전이는 기존 PATCH와 같은 Task 로직을 사용한다. 이미 DONE이면 최초 완료자/시각을 유지한다. PATCH로 TODO를 다시 열면 완료 정보를 비운다. PATCH로 완료한 경우 Slack 완료자는 없으므로 임의 사용자 멘션을 만들지 않는다.
- DB가 원본이다. 게시 메시지의 채널·ts·Task ID 목록을 보존하고 클릭 때 DB의 현재 상태로 다시 만들어 chat.update한다. 다이제스트의 완료 Task도 원래 메시지에 남는다. Slack 메시지 본문을 상태 저장소로 사용하지 않는다.
- V2가 Task 완료 정보, Meeting Slack 위치, Slack 메시지 참조를 추가한다. 기존 DONE의 완료자·시각은 추정 백필하지 않는다. local ddl-auto=update, docker는 Flyway V2로 적용하므로 정상 기존 DB를 초기화할 필요가 없다.
- local 개발 샘플 전해찬은 DEV_SLACK_USER_ID 실제 ID를 지정하면 기존 행에도 적용한다. 새 DB에서 생략하면 U-DEV-HAECHAN이다. 기존 실제 ID는 환경변수를 생략해도 지우지 않는다. 같은 전체 이름·팀 후보가 여러 명이면 임의로 바꾸지 않는다.

[Slack 봇 단계1 확인 문서](slackBotStep1Done.md)에 키체인 실행·Docker 샘플 ID 설정·Swagger 입력·권한/완료 확인 절차와 실제 자동 테스트 결과를 정리한다. 단계1.5에서는 관련 채널·DM을 함께 갱신한다. DB는 저장됐지만 일부 Slack 갱신만 실패할 수 있다.

Slack 봇 단계1 검증: 전체 **355개 / 실패·오류·건너뜀0**, test·bootJar 성공. 이 자동 검증에서는 실제 Slack/Socket 연결을 하지 않았다. 이후 사용자가 실제 채널의 본인 완료·다른 사람 권한 거절·미정 완료를 확인했다. 단계1.5 확인 전 단계2로 진행하지 않는다.


## Slack 봇 — 단계1.5 담당자 DM

`MEETODO_SLACK_DM_ENABLED=true`일 때만 새 CREATED Task를 실제 Slack ID 담당자별 한 통으로 묶어 보낸다. EXISTING·미정·가짜 ID·다이제스트는 DM 대상에서 제외한다. 기존 채널 알림은 그대로이며 DM에 완료 버튼과 원래 채널 메시지 링크를 넣는다. 채널 또는 DM에서 완료하면 기존 TaskService 권한/상태 전이를 거쳐, 같은 Task가 포함된 SENT 메시지를 DB 현재 상태로 각각 갱신한다. 일부 갱신 실패는 안전한 로그만 남기고 완료를 유지한다.

V3는 기존 slack_message_references에 CHANNEL/DM·대상 사용자·SENT/FAILED·오류 코드·원래 링크를 추가한다. DM 실패 전에 대화 위치를 모를 수 있어 channel/ts는 nullable이다. 기존 행은 CHANNEL/SENT로 유지하며 V1/V2는 바꾸지 않는다. local은 기존 H2 메시지 컬럼을 보정하는 멱등 SQL을 Hibernate update 전에 실행하고, docker는 Flyway V3를 적용한다. 정상 기존 데이터 초기화는 필요 없다. DM 실패는 Meeting DONE·채널 SENT를 변경하지 않는다. 채널 전송의 missing_scope/not_in_channel/channel_not_found/invalid_auth 등 알려진 안전한 Slack 코드는 notificationErrorCode에 보존하고 임의 문자열·비밀값·예외 원문은 남기지 않는다.

추가 Slack 설정은 **Bot scope im:write → 재설치, App Home → Messages Tab 켜기**다. 기존 chat:write·Socket Mode·Interactivity와 채널 초대는 유지한다. DM 텍스트 수신이 없어 im:history/message.im 구독은 필요 없다. [실행 명령·Swagger JSON·DM 확인 절차·면접 질문](slackBotStep1_5Done.md)을 따른다. 기본값은 꺼짐이며 기존 웹훅·Discord 동작을 유지한다.

알려진 한계: DM 전송과 DB 위치 저장·완료 후 메시지 갱신은 원자적이지 않고 자동 재시도/Outbox/정확히 한 번 전달을 보장하지 않는다. 관련 메시지 조회는 개발 규모에서 전체 SENT 참조의 Task ID를 확인하며, 커지면 Task-메시지 관계 인덱스가 필요하다. 동시 완료의 Slack 수정 순서까지 직렬화하지 않아 일부 메시지가 오래된 표시로 남을 수 있다. 단계2 /meetodo 모달은 사용자 확인 후 별도 구현한다.

단계1.5 자동 검증: `./gradlew test bootJar --no-daemon` 성공, **394개 / 실패·오류·건너뜀0**. 실제 PostgreSQL22개·기존 H2 업그레이드4개 포함. MethodsClient/LLM은 mock이며 실제 DM 전달은 사용자 확인 단계다.

## Slack 봇 — 단계2 /meetodo 입력

`/meetodo` → 여러 줄 모달(최대3000자) → 즉시 Socket ACK → 별도 worker의 기존 회의 처리 → 원래 채널의 처리 중 메시지를 결과로 수정한다. 기존 Task 권한·완료 버튼·CREATED 실제 담당자별 DM을 재사용한다. source=SLACK_TEXT이며 Swagger 입력의20000자 상한은 유지한다. 동일 본문은 기존 결과와 요청자 비공개 안내만 반환한다. 실패는 원문 없는 실패 문구로 같은 메시지를 수정하고, 봇 미초대는 slash 비공개 응답으로 초대 방법을 안내한다.

기존 /meetodo·commands·chat:write·im:write·Socket Mode·Interactivity·Messages Tab이 준비됐으면 추가 권한이나 공개 Request URL은 필요 없다. [실행·Slack 확인 순서·변경 파일·면접 질문](slackBotStep2Done.md)을 따른다. 기존 참조 테이블을 사용하므로 새 Flyway 마이그레이션·data/ 초기화는 없다.

한계: 모달 응답 콜백은 메모리 최대1000개/30분이며 URL을 DB·로그에 저장하지 않는다. 서버 재시작·만료 시 모달을 다시 열어야 한다. Socket Mode 입력은 단일 인스턴스 기준이고 다중 인스턴스에는 모달 상태 공유/라우팅이 필요하다. 회의 worker1개/대기10개이며 메모리 큐의 종료 후 복구·외부 전송과 DB의 원자성·Outbox는 이번 범위가 아니다.

단계2 최초 검증은425개 통과였고, 이후 게시 순서를 아래1안으로 바꿨다. 미초대 실패를 FAILED Meeting으로 남기고 재시도 예외를 두는 방식은 사용하지 않는다.

### 진행 게시 후 Meeting 저장

기존 해시 조회 → 새 본문이면 진행 메시지 게시 → 성공 시 Meeting/해시 저장 → LLM 실행 순서다. not_in_channel 등 게시 실패는 Meeting·해시·ToolCallLog를 남기지 않고 요청자에게만 초대 안내를 보낸다. 안전한 Slack 코드만 서버 로그에 남기므로 초대 후 같은 본문을 자연스럽게 처리할 수 있다. 처리에 들어간 회의의 기존 중복 규칙은 유지한다.

동시에 같은 본문이 들어오면 두 진행 메시지가 잠깐 보일 수 있다. DB 해시 유니크 제약에서 이긴 요청만 처리하고 진 요청은 자신이 게시한 임시 메시지를 chat.delete로 정리한 뒤 기존 회의 안내를 보낸다. 이 정리는 Meeting·ToolCallLog 삭제가 아니다. 정리 콜백은 Slack 제출 서비스의 필수 인자로 받아 누락을 막는다. 임시 메시지 삭제 실패는 안전한 로그만 남기며 처리 횟수를 늘리거나 승자의 결과를 실패시키지 않는다. 게시 성공 직후 프로세스 종료/DB 장애·삭제 실패·전송 타임아웃 때 임시 메시지가 남을 수 있고 정확히 한 번 UI 전달은 보장하지 않는다.

이전 버전에 이미 저장된 FAILED 회의는 자동 삭제하거나 재시도 상태로 바꾸지 않는다. 새 정책 확인에는 기존 DB에 없는 본문을 사용한다. 처리 시간은 진행 게시 성공 뒤/DB 저장 전 시작하므로 모달 입력·worker 대기·진행 게시 시간은 포함하지 않는다. 새 측정값은 기존 Swagger 측정값과 조건을 구분한다.

게시 순서 수정 후 최종 검증: `./gradlew test bootJar --no-daemon` 성공, **431개 / 실패·오류·건너뜀0**. 실제 Slack·OpenAI 호출은 없었으며 새 실제 확인 순서는 [단계2 문서](slackBotStep2Done.md)를 따른다.

## 단계 F — 명사형 제목과 서버 검증

createTask의 title 설명과 시스템 프롬프트에 `API 명세 정리`, `배포 로그 정리`, `로그인 API 오류 수정` 같은 짧은 명사형 제목을 안내한다. 이름·마감일·문장 어미·끝 마침표는 제목에서 제외한다. 공통 TaskTitles는 문장형을 자동 변환하지 않고 도구 INVALID_TITLE_FORMAT(검증 실패 수/ToolCallLog 포함), 직접 API400 VALIDATION_ERROR+fieldErrors.title로 거절한다. 저장 전 공백·끝 마침표만 정리하고 중복 비교도 같은 정리+소문자화를 사용한다. 날짜 재시도의 제목 비교도 통일했다. [Swagger·Slack 확인과 F 실측 안내](taskTitleStepFDone.md)를 따른다.

거절 범위: 끝 공백·.!? 제외 후 다, 함., 할 것/할것. bare 보관함은 허용한다. 알려진 한계: 단순 접미사 검사이므로 람다 같은 다 끝 명사·보관함.은 오탐할 수 있다. 전체 문법이나 제목의 이름/날짜 의미까지 서버가 판정하지 않는다. 기존 데이터는 백필하지 않아 과거 문장형과 새 명사형의 의미상 중복은 남을 수 있다. 단어가 다른 명사형끼리의 의미 중복도 해결하지 않는다. DB 스키마 변경·data/ 초기화는 없다.

DEVLOG 실험 표의 F 적용 후(제목 형식)에 사용자 실측6회를 E와 분리해 기록했다. measure.sh 문장·E 원값을 유지한다. 실제 OpenAI 결과·성능 수치를 자동 테스트 결과로 대체하지 않는다.

단계 F 자동 검증: `./gradlew test bootJar --no-daemon` 성공, **468개 / 실패·오류·건너뜀0**. Slack·Discord·다이제스트·DM·기존 API 전체 회귀 포함. 이 자동 테스트에서는 실제 모델·메신저를 호출하지 않았다. 이후 사용자가 아래 F 실측과 Slack 동작을 확인했다.


F 실제 확인(사용자, 알림 SKIPPED·입력별3회): 백엔드팀 지영은 LLM4/도구3/검증실패0, 지영은3/2/0으로 E와 같았다. 제목은 각각 로그인 API 오류 처리·회의실 예약 확인으로3회씩 동일했고 INVALID_TITLE_FORMAT은0회였다. 서버의 제목 거절 없이 허용 형식이 생성된 표본이며 향후 결과를 보장하지 않는다.

| 입력 | E 평균(ms) | F 평균(ms) | 차이(ms) |
| --- | --- | --- | --- |
| 백엔드팀 지영 | 6399.7 | 5967.7 | -432 |
| 지영 | 4393.0 | 4252.0 | -141 |

각 평균은 회차별 원값으로 계산했다. 표본3회·같은 호출 경로의 시간 변동일 수 있어 제목 규칙에 의한 성능 향상으로 단정하지 않는다. 사용자 실제 Slack에서는 문장형 회의록→명사형 제목·새 배정 DM, 다른 표현의 같은 업무→EXISTING·추가 DM 없음, 원본/재언급 회의 채널과 DM의 완료 동기화, 직접 API 문장형 제목400 안내를 확인했다.

measure.sh는 응답을 printf로 변환 없이 저장하고 결과 표에 제목을 출력한다. 스크립트는 회차별 개발 DB 초기화를 포함하므로 사용자 직접 실측용이며 자동 테스트는 저장·출력 부분만 격리한다. 테스트의 가짜 토큰·키·웹훅은 런타임 String.join으로 구성하고 마스킹 대상 값·assertion을 유지한다. README 외 Markdown은 .gitignore 규칙에 따라 로컬에서 관리한다.

측정 스크립트·가짜 비밀값 정리 후 전체 검증: `./gradlew test bootJar --no-daemon` 성공, **470개 / 실패·오류·건너뜀0**. JSON 저장/제목 출력 격리 회귀2개 포함. 실제 실측 스크립트 전체·외부 API·push는 실행하지 않았다.
