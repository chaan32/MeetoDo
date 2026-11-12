# Slack 봇 단계 1 — 봇 메시지·멘션·완료 버튼

## 구현 범위

Swagger 회의 입력 → 기존 에이전트 처리 → `NOTIFIER_TYPE=slack-bot`이면 chat.postMessage. 채널·ts와 게시 Task ID 목록을 DB에 저장한다. TODO 버튼은 DB에서 권한·상태를 확인한 뒤 완료하고 원래 메시지를 chat.update한다. 다이제스트에도 같은 멘션·버튼을 적용한다.

**/meetodo 슬래시 명령·모달·처리 중 메시지·모달 중복/실패 처리는 아직 구현하지 않았다.** 사용자가 단계1을 확인한 뒤 “단계2 진행”이라고 할 때 구현한다.

## 구조·규칙

- Slack Bolt for Java / Socket Mode1.54.0, javax.websocket-api1.1, Tyrus1.20. 공개 수신 주소 없이 아웃바운드 소켓으로 액션을 받는다.
- local/docker & !test에서 MEETODO_SLACK_BOT_ENABLED=true, xapp/xoxb 두 토큰이 있어야 연결을 시작한다. 기본·테스트에서는 연결하지 않는다. NOTIFIER_TYPE=slack-bot + SLACK_CHANNEL_ID가 있어야 새 봇 알림을 보낸다. 기존 slack(웹훅)·discord 유지.
- U/W 영숫자 실제 ID 형식만 `<@ID>`로 만든다. U-DEV 등 가짜 ID는 이름(팀) 텍스트다. 사용자 제목·경고의 <@...>/<!here> 등은 escape하고 자동 파싱·링크 프리뷰를 끈다.
- 고정 action_id=meetodo_complete_task, value=taskId. TODO일 때만 버튼을 붙인다.
- 담당자 본인만 완료, 미정은 누구나 완료한다. 다른 사람은 ephemeral “담당자만 완료할 수 있어요”. 이미 DONE은 최초 완료자·시각을 유지하고 메시지만 새로 만든다.
- Task 행을 잠근 후 공통 상태 전이를 사용한다. PATCH도 같은 전이를 사용하며 TODO 재개는 완료 정보를 비운다. PATCH 완료는 Slack 완료자 없이 완료 시각을 기록한다. 기존 DONE의 완료자·시각을 추정 백필하지 않는다.
- 실제 Socket ACK를 작업 큐에 넣기 전에 전송한다. Bolt 기본 adapter는 app.run 이후 응답하므로 custom client supplier/dispatch를 사용하고 App.blockAction은 동일하게 사용한다. 단일 worker, bounded queue100으로 DB/HTTP를 처리한다.
- 메시지 본문을 파싱하지 않는다. DB의 메시지 좌표·Task ID 목록과 현재 Task 상태로 매번 재구성한다. 완료된 다이제스트 Task도 원래 게시 목록에 남는다.
- 메시지 좌표 저장은 AFTER_COMMIT 이후 전용 REQUIRES_NEW다. Slack 전송 성공 뒤 DB 저장 실패 가능성과 정확히 한 번 전달 한계는 남는다.
- 한 메시지, 최대50블록/section3000자/fallback40000자. 전체가 들어가면 모두 보내며 넘으면 완전한 Task 접두 목록만 포함하고 기존 GET 경로로 생략 안내한다. Task 줄을 중간에 자르지 않는다.
- V2: Task 완료자·시각, Meeting 채널·ts, slack_message_references(채널/ts unique, 회의 또는 다이제스트 날짜, Task ID JSON)를 추가한다. 정상 기존 local/PG DB 초기화는 필요 없다.

## 실행 — local

토큰은 키체인에서 환경변수로 읽고 출력하지 않는다. DEV_SLACK_USER_ID와 SLACK_CHANNEL_ID 예시를 본인 실제 값으로 바꾼다.

```zsh
export OPENAI_API_KEY="$(security find-generic-password -a "$USER" -s meetodo-openai -w)"
export OPENAI_CHAT_MODEL=gpt-4o-mini
export SLACK_APP_TOKEN="$(security find-generic-password -a "$USER" -s meetodo-slack-app-token -w)"
export SLACK_BOT_TOKEN="$(security find-generic-password -a "$USER" -s meetodo-slack-bot-token -w)"
export DEV_SLACK_USER_ID='U0123456789'
export SLACK_CHANNEL_ID='C0123456789'
export MEETODO_SLACK_BOT_ENABLED=true
export NOTIFIER_TYPE=slack-bot
export MEETODO_DIGEST_ENABLED=false
./gradlew bootRun --args='--spring.profiles.active=local,openai'
```

local 개발 샘플 전해찬은 기존 행에도 실제 ID를 적용한다. 새 DB에서 DEV_SLACK_USER_ID를 생략하면 가짜 ID다. 기존 실제 ID는 환경변수를 생략해도 지우지 않는다. 같은 전해찬/백엔드팀 후보가 여러 명이면 임의로 한 사람을 고치지 않고 안전한 경고만 남긴다.

키가 없어도 Task·다이제스트만 확인할 수 있다: local만 켜고 직접 Task API로 등록한다. 회의 입력은 OpenAI 활성화가 필요하다. 실제 앱·봇 토큰은 SDK 로그·예외·ToolCallLog에 출력하지 않는다.

## Docker

위 환경변수와 기존 POSTGRES_PASSWORD를 설정한 뒤:

```zsh
export SPRING_PROFILES_ACTIVE=docker,openai
docker compose build app
docker compose up -d --wait
```

V2는 자동 적용한다. Docker에서는 개발 샘플을 자동 생성하지 않으므로 기존에 넣은 샘플을 사용하거나 scripts/docker-demo-members.sql을 최초에 실행한다. 실제 ID는 기존 DB의 전해찬에게 명시적으로 적용한다(해당 이름·팀이 한 명인 개발 DB에서만).

```zsh
docker compose exec -T postgres sh -c 'psql -U "$POSTGRES_USER" -d "$POSTGRES_DB" -v actual_slack_id="$1"' sh "$DEV_SLACK_USER_ID" <<'SQL'
UPDATE members SET slack_user_id = :'actual_slack_id'
WHERE family_name='전' AND given_name='해찬' AND team='백엔드팀'
  AND :'actual_slack_id' ~ '^[UW][A-Z0-9]{1,79}$'
  AND 1=(SELECT count(*) FROM members WHERE family_name='전' AND given_name='해찬' AND team='백엔드팀');
SQL
```

다이제스트 수동 API도 쓸 경우 MEETODO_DEV_ENDPOINTS_ENABLED=true로 앱을 재생성한다. 두 앱을 띄워도 메시지 참조·Task 완료는 공유 PostgreSQL을 사용한다. 이번 단계에서 실제 두 Socket 연결·Slack 채널 검증은 하지 않았다.

## Swagger·Slack 확인 순서

Swagger: http://localhost:8080/swagger-ui/index.html. 같은 본문은 해시 재전송으로 예전 결과가 반환되므로 새 본문/Task 제목을 사용한다. 기존 TODO 재사용 정책은 유지한다.

1. POST /api/meetings:

```json
{"rawText":"해찬은 '봇1단계 배포 점검' 작업을 2026-11-20까지 완료한다. 담당자 미정으로 '봇1단계 회의실 예약 확인' 작업도 2026-11-20까지 진행한다."}
```

실행일보다 과거라면 날짜를 미래 날짜로 바꾼다. Slack 예상 모습(모델 추출 제목은 달라질 수 있음):

```text
📝 회의 정리 완료 (할 일 2건)
새로 등록 2건 · 기존 할 일 0건
담당자 미정 1건
🆕 ⬜ 미완료 · @본인 · 봇1단계 배포 점검 · 2026-11-20  [✅ 완료]
🆕 ⬜ 미완료 · 담당자 미정 · 봇1단계 회의실 예약 확인 · 2026-11-20  [✅ 완료]
```

2. 본인 계정으로 전해찬 작업 버튼 클릭 → 원래 메시지에 `✅ 완료 (<@본인ID>)`, 버튼 제거. GET /api/tasks에서 DONE, completedBySlackUserId, completedAt 확인.
3. 다른 채널 멤버가 담당자 작업 클릭 → 그 사람에게만 “담당자만 완료할 수 있어요”, Task TODO 유지. 이를 확인하려면 아직 TODO인 새 담당자 작업을 사용한다.
4. 미정 작업은 채널 누구나 클릭 가능. 완료자 ID가 기록된다.
5. PATCH /api/tasks/{id}로 DONE/TODO를 바꾼 뒤 기존 메시지의 남아 있는 버튼을 누르면 DB 기준으로 갱신된다. TODO 재개는 완료자·시각을 지우지만 버튼을 제거한 메시지에 새 버튼을 자동 재추가하는 기능은 아니다.
6. 가짜 ID인 민수·지영 담당자는 이름(팀)으로 보이고 실제 멤버는 그 fake ID와 같을 수 없으므로 Slack 완료 권한이 없다. 멘션·완료 실측은 전해찬 또는 미정 Task로 진행한다.
7. 동명이인/미등록/팀 불일치 회의록을 별도로 넣어 상세 경고가 한 번만 나오는지 확인한다. Task 최초 경고와 이번 회의 경고를 중복하지 않는다.

## 아침 다이제스트

OpenAI 없이 직접 Task API로 대상 Task를 만든 뒤 기존 digest API를 호출해도 된다.

```json
{"title":"봇 다이제스트 확인","assigneeName":"전해찬","dueDate":"2026-11-20"}
```

POST /api/dev/digest/run?date=2026-11-20. 같은 날짜가 이미 선점됐다면 개발 확인용 force=true를 사용한다. bot 모드에서 실제 멘션·TODO 버튼이 붙고, 클릭 후 원래 다이제스트에 DONE 줄이 남는다. 다음 날짜의 새 다이제스트에서는 DONE을 제외한다. 자동 예약은 MEETODO_DIGEST_ENABLED=true를 명시할 때만 켜진다.

## 검증·변경 파일

**전체355개 / 실패·오류·건너뜀0**, `./gradlew test bootJar --no-daemon` 성공. 실제 DB 메시지 참조를 통한 클릭 완료·원래 메시지 갱신 통합 테스트도 포함한다. 테스트에서는 실제 Slack/OpenAI를 호출하지 않고 MethodsClient·SocketModeClient를 mock으로 사용했다. PostgreSQL은 Testcontainers 실제 DB로 검증했다.

신규: slack/SlackBotConfiguration, SlackBotSocketRuntime, SlackSocketDispatch, SlackBotService, SlackBotRenderer, SlackMessageReference/Repository, TaskCompletionForbiddenException, V2__slack_bot_completion.sql 및 관련 테스트.

변경: Task/TaskService/TaskRepository/TaskResponse, Meeting/MeetingTaskResponse, Member/DevMemberDataInitializer, NotificationMessage/DailyDigestMessageFactory, ToolExecutionService 마스킹, build.gradle, application.yml 로그 설정, .env.example, docker-compose.yml, README/DEVLOG/구현 계획. 기존 웹훅·Discord 변환 경로는 유지한다.

확인한 실패와 수정:
- PATCH 완료 시각 부재 → 공통 상태 전이에 완료자·Clock 시각 저장.
- 합성 xoxb/xapp 토큰 마스킹 누락 → 공통 sanitizer 확장.
- 실제 JPA AFTER_COMMIT 뒤 메시지 좌표가 남지 않음 → 전용 REQUIRES_NEW.
- 봇 경고 중복·긴 줄 잘림·50블록 경계 누락 → 현재 회의 경고 우선, 전체 수용 우선과 완전한 항목 단위 생략.
- 테스트용 봇 설정의 일반 프로필 검사 유입 → 전용 테스트 프로필로 격리. 운영 버그와 구분한다.

## 근거·남은 범위

공식 문서: [Socket Mode](https://docs.slack.dev/tools/java-slack-sdk/guides/socket-mode/), [Interactive Components와 3초 ACK](https://docs.slack.dev/tools/java-slack-sdk/guides/interactive-components/), [chat.update](https://docs.slack.dev/reference/methods/chat.update/), [멘션 형식](https://docs.slack.dev/messaging/formatting-message-text/), [버튼 필드](https://docs.slack.dev/reference/block-kit/block-elements/button-element/).

실제 SDK1.54.0 소스/클래스에서 SocketModeApp.startAsync/사용자 client supplier, SocketModeRequestParser, App.blockAction, ChatPostMessageRequest, ChatUpdateRequest, ChatPostEphemeralRequest를 확인했다. 기본 SocketModeApp adapter는 app.run 결과 뒤 ACK를 보내므로 wire ACK→bounded queue 순서를 직접 검증했다. 구현한 custom supplier에는 기본 Bolt listener를 중복 등록하지 않는다.

외부 채널·실제 멘션 알림 도달·Socket 재연결은 사용자가 확인한다. 완료된 Task가 여러 메시지에 있더라도 클릭한 원본만 갱신한다. 다른 메시지 전체 자동 동기화·Outbox·정확히 한 번 전달·모달 입력은 이번 범위 밖이다. Task 완료는 성공했지만 Slack 갱신만 실패하면 DB 상태를 유지하고 ephemeral로 조회 안내한다.

면접 예상 질문:
1. 왜 메시지를 파싱하지 않나요?
   - 화면은 생략되거나 수정될 수 있다. DB의 메시지 참조와 Task 현재 상태가 원본이며 같은 정보로 매번 재구성한다.
2. 왜 ack와 처리를 분리했나요?
   - Slack은3초 안에 응답해야 하므로 DB 잠금과 외부 HTTP를 기다리지 않는다. 실제 SDK 응답 순서를 확인해 wire ACK를 먼저 보내고 bounded worker에서 처리한다.
3. 중복 클릭은 어떻게 처리하나요?
   - Task 행 잠금 안에서 DONE 여부와 담당자 권한을 확인한다. 최초 완료자·시각은 보존하고 재클릭은 DB 상태를 다시 보여준다. DB 완료와 Slack 갱신은 하나의 원자적 작업이 아니므로 실패를 분리한다.
