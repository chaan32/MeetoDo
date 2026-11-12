# 5단계 — 평일 마감 알림

## 완료·검증

LLM 없이 TODO와 날짜 규칙으로 다이제스트를 만든다. 모델 판단이 필요 없는 반복 작업이며 이 워크플로의 LLM 호출·토큰 비용은 0이다. 기존 Notifier를 재사용하므로 Slack/Discord 선택은 환경변수로 한다.

- 평일 09:00 Asia/Seoul, MEETODO_DIGEST_ENABLED 기본 false. true일 때만 예약 빈을 만든다. cron은 MEETODO_DIGEST_CRON으로 변경하며 기본 `0 0 9 * * MON-FRI`.
- TODO 중 ⏰ 마감 지남 / 📌 오늘 마감 / 📅 내일~오늘+3일 마감. DONE·오늘+4일 이후 제외. 마감 없는 TODO는 하단 개수에만 표시.
- 담당자 ID별 묶음, 전체 이름(팀), 날짜·Task ID 순서. 미정 묶음은 마지막에 상세 경고와 함께 표시. Task마다 한 줄로 묶음 이름과 구간을 반복 표시해 생략 건수를 Task 수로 계산한다.
- 대상 없음 또는 Notifier 없음 → SKIPPED. 전송 실패 → FAILED, 자동 재시도 없음.
- 날짜 unique 행을 별도 트랜잭션에서 먼저 커밋해 선점. 같은 날 후속 일반 실행은 ALREADY_SENT. 이 응답은 선점됐다는 뜻으로 전송 성공을 보장하지 않는다. 실패·SKIPPED·PENDING도 일반 재실행은 막힌다.
- local 전용 수동 실행은 OpenAI·예약 활성화 없이 가능. force=true만 같은 날짜 재전송을 허용하고 그 행의 마지막 실행 결과를 갱신한다.
- 공통 NotificationMessage에 DAILY_DIGEST 유형을 추가했다. 제한을 넘으면 단일 메시지의 접두 목록만 보내고 `외 N건은 GET /api/tasks에서 확인`을 표시한다.

변경 전 수동 API 404 및 회의 전용 생략 경로 테스트의 실패를 확인했다. 구현 후 **전체 279개 테스트, 실패·오류·건너뜀 0**, `./gradlew test bootJar --no-daemon` 성공. 신규 29개(다이제스트 API/서비스/설정 24, 플랫폼 JSON/생략 5)와 기존 회귀를 포함한다. 동시 실행 테스트에서 실제 H2 유니크 제약 충돌과 전송 1번을 확인했다. 실제 외부 전송·9시 예약 실행·다중 서버 배포는 실행하지 않았다.

## 변경 파일

신규 프로덕션 `src/main/java/com/meetodo/digest/`:
- DailyDigest.java: 날짜·상태·sentAt·집계 저장, 날짜 유니크 제약
- DailyDigestRepository.java, DailyDigestResponse.java
- DailyDigestService.java: 조회·선점·전송·결과 기록
- DailyDigestMessageFactory.java: 구간·담당자별 공통 메시지
- DailyDigestController.java: local 수동 실행과 안전한 query 검증 응답
- DailyDigestScheduler.java: 조건부 스케줄과 서울 시간대

변경:
- task/TaskRepository.java: 대상 fetch join 조회와 마감 없는 TODO count
- notification/NotificationMessage.java, SlackPayloadRenderer.java, DiscordPayloadRenderer.java: 메시지 유형·생략 경로
- notification/NotificationConfiguration.java: local & !test로 OpenAI 의존 분리
- src/main/resources/application.yml, .env.example: 예약 기본 비활성·cron 설정
- build.gradle: 테스트 프로세스의 예약 기본 비활성
- src/test/java/com/meetodo/notification/NotificationConfigurationTests.java

신규 테스트:
- src/test/java/com/meetodo/digest/DailyDigestApiTests.java
- src/test/java/com/meetodo/digest/DailyDigestServiceTests.java
- src/test/java/com/meetodo/digest/DailyDigestConfigurationTests.java
- src/test/java/com/meetodo/notification/DigestPayloadRendererTests.java

문서: README.md, DEVLOG.md(E 원값·D 비교 포함), 이 문서, docs/superpowers/plans/2026-11-12-daily-digest.md.

## 실행

E 확인을 마친 DB라면 초기화가 필요 없다. 새 daily_digests 테이블은 local ddl-auto=update로 추가된다. 데이터 삭제·커밋은 하지 않았다.

이미 실제 확인한 SLACK_WEBHOOK_URL 환경변수를 유지한다. OpenAI 키는 필요 없다.

```zsh
export NOTIFIER_TYPE=slack
export MEETODO_DIGEST_ENABLED=false
./gradlew bootRun --args='--spring.profiles.active=local'
```

예약을 켤 때는 서버를 종료한 뒤:

```zsh
export MEETODO_DIGEST_ENABLED=true
export MEETODO_DIGEST_CRON='0 0 9 * * MON-FRI'
./gradlew bootRun --args='--spring.profiles.active=local'
```

Discord로 바꿀 때:

```zsh
export DISCORD_WEBHOOK_URL="$(security find-generic-password -a "$USER" -s meetodo-discord-webhook -w)"
export NOTIFIER_TYPE=discord
```

기존 OpenAI 환경변수가 남아 있어도 local만으로는 OpenAI가 활성화되지 않는다. 수동/예약 모두 동일한 날짜 선점 규칙을 사용한다. cron을 여러 번 실행하도록 바꿔도 같은 날짜 일반 전송은 한 번이다.

## Swagger 데모

`http://localhost:8080/swagger-ui/index.html`. 기준일을 **2026-11-20**으로 지정해 실행하므로 실제 실행 날짜와 관계없이 재현할 수 있다. 아래 JSON 각각을 POST /api/tasks로 등록한다. 직접 Task API는 과거 날짜를 허용한다(에이전트의 과거 마감일 정책과 경로가 다름).

마감 지남:
```json
{"title":"5단계 지연 보고서","assigneeName":"김민수","dueDate":"2026-11-19"}
```

오늘 마감:
```json
{"title":"5단계 배포 점검","assigneeName":"전해찬","dueDate":"2026-11-20"}
```

3일 이내 경계:
```json
{"title":"5단계 운영 문서","assigneeName":"김민수","dueDate":"2026-11-23"}
```

마감일 없음:
```json
{"title":"5단계 장기 검토","assigneeName":"김민수"}
```

담당자 미정:
```json
{"title":"5단계 예약 확인","dueDate":"2026-11-20"}
```

선택: 범위 밖 작업이 빠지는지 확인한다.
```json
{"title":"5단계 범위 밖 작업","assigneeName":"전해찬","dueDate":"2026-11-24"}
```

같은 TODO가 이미 있으면 409 DUPLICATE_TASK이므로 기존 Task를 사용하거나 제목을 바꾼다. 이름만으로 이지영에게 직접 등록하면 동명이인 400이므로 assigneeId로 후보를 확정해야 한다. 위 예시는 유일한 이름만 사용한다.

실행: POST /api/dev/digest/run에서 date=2026-11-20, force=false. 요청 본문은 없다.

```sh
curl -X POST 'http://localhost:8080/api/dev/digest/run?date=2026-11-20'
```

다른 기존 데이터가 없고 웹훅이 정상이라면:
```json
{"digestDate":"2026-11-20","status":"SENT","overdueCount":1,"todayCount":2,"upcomingCount":1,"noDueDateCount":1}
```

같은 요청 재실행 → ALREADY_SENT, 추가 메시지 없음. 재전송 확인:
```sh
curl -X POST 'http://localhost:8080/api/dev/digest/run?date=2026-11-20&force=true'
```

date 생략 시 주입 Clock의 서울 오늘, 잘못된 날짜는 400 VALIDATION_ERROR. 웹훅 설정이 없으면 SKIPPED. 이미 SKIPPED 기록이 생겼다면 설정 후 force=true로 재확인한다. 기존 업무 데이터가 있으면 집계와 내용도 달라진다.

## Slack 예상 모습

```text
📋 마감 할 일 알림 (2026-11-20)
⏰ 마감 지남 1건
📌 오늘 마감 2건
📅 3일 이내 마감 1건

김민수(백엔드팀) · ⏰ 마감 지남 · 5단계 지연 보고서 · 2026-11-19
김민수(백엔드팀) · 📅 3일 이내 마감 · 5단계 운영 문서 · 2026-11-23
전해찬(백엔드팀) · 📌 오늘 마감 · 5단계 배포 점검 · 2026-11-20
담당자 미정 · 📌 오늘 마감 · 5단계 예약 확인 · 2026-11-20

기한 없는 미완료 할 일 1건 제외 · 기준일 2026-11-20
```

직접 만든 미정 Task에는 원래 조회 정보가 없어 경고가 없다. 기존 에이전트에서 생성한 AMBIGUOUS/NOT_FOUND/TEAM_MISMATCH Task가 대상 범위에 있으면 저장된 상세 경고를 그대로 덧붙인다. 이 상세 경고 3종은 기존 D/E 경로를 사용하고 다이제스트에서는 LLM을 호출하지 않는다. 한 메시지 제한을 넘으면 생략 안내로 전체 GET /api/tasks를 가리킨다. Slack 실제 멘션은 만들지 않는다.

## 설계 근거와 한계

- LLM 대신 서버 규칙: 결정된 날짜/상태 분류라 추론 불필요, 모델 호출과 토큰 비용 없음.
- DB 선점: JVM 락과 달리 같은 DB를 쓰는 여러 인스턴스에 날짜 유니크 제약이 적용된다. HTTP는 DB 트랜잭션 밖에서 호출한다.
- 실패 재시도 없음: 타임아웃 때 이미 전송됐을 가능성 때문에 중복보다 누락을 택하고 FAILED로 남긴다. 정확히 한 번 전달을 보장하지 않는다. 선점 후 종료는 PENDING으로 남을 수 있다. 향후 Outbox/재처리 정책을 검토할 수 있다.
- force는 개발용 명시적 예외. 같은 날짜의 이전 실행 이력을 별도 행으로 보존하지 않고 마지막 결과를 갱신하며 동시 강제 발송도 제한하지 않는다.
- 현재 H2 파일 DB를 여러 서버가 공유하는 배포는 지원하지 않는다. 이번 검증은 H2 두 스레드이며 실제 다중 서버·PostgreSQL 동작은 배포 단계에서 확인한다. 실제 Notifier 프로필도 현재 local & !test다.
- 평일은 월~금이며 공휴일 달력은 없다. 3일은 달력 날짜다. 서비스가 중단된 동안의 예약 실행을 자동 보충하지 않는다.

근거: [Spring Framework 6.2 Scheduling](https://docs.spring.io/spring-framework/reference/6.2/integration/scheduling.html), [REQUIRES_NEW](https://docs.spring.io/spring-framework/reference/6.2/data-access/transaction/declarative/tx-propagation.html). 실제 spring-context 6.2.19 Scheduled.cron/zone, CronExpression 소스도 확인했다. 전송 JSON은 기존 Slack/Discord API 경로를 재사용했다.

## 면접 예상 질문

1. 에이전트 서비스인데 왜 이 알림은 LLM을 쓰지 않나요?
   - 날짜와 TODO 상태로 결정되는 규칙 작업이라 모델 판단이 필요 없다. 회의록 해석은 에이전트가 맡고 반복 집계·발송은 서버가 맡아 호출·토큰 비용과 결과 변동을 줄인다.
2. 왜 synchronized 대신 DB 유니크 제약인가요?
   - synchronized는 한 JVM만 보호한다. 공유 DB에 날짜 행을 먼저 커밋하면 여러 인스턴스가 같은 제약으로 경쟁하며 정상 실행 하나만 전송한다. 실패한 트랜잭션은 바깥에서 처리해야 롤백 전파도 피한다.
3. 하루 한 번이면 정확히 한 번 전달인가요?
   - 아니다. 선점 뒤 종료하면 누락될 수 있고 HTTP 타임아웃 때 전달 여부도 불명확하다. 자동 재시도 대신 상태를 기록하며, 정확한 전달·복구가 필요해지면 Outbox와 공급자 멱등성 등 추가 설계가 필요하다.
