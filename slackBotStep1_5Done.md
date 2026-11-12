# Slack 봇 단계1.5 — 새 담당자 DM

## 현재 동작

- Swagger 회의 처리 → 기존 채널 알림 → 새로 CREATED된 Task의 실제 담당자별 DM 1통. EXISTING·미정·U-DEV-는 제외.
- DM은 `📝 새 할 일이 배정됐어요`, 제목·마감일·상세 경고·✅ 완료 버튼·원래 채널 메시지 링크를 표시한다.
- 채널/DM 어디서 완료해도 같은 TaskService 권한 규칙을 거치고 관련 메시지 모두 DB 현재 상태로 갱신한다. 이미 DONE이면 최초 완료자를 유지한다.
- 채널 성공과 DM별 성공/실패는 별개다. DM 실패여도 Meeting DONE·채널 SENT 유지, DM 기록 FAILED+안전한 오류 코드.
- 메시지 유형·대상·위치·업무 집합·전송 상태·링크를 slack_message_references에 저장한다. V3로 기존 CHANNEL/SENT 행을 유지한다.
- local은 시작 시 기존 H2 메시지 기본값·NULL 허용 보정 SQL 후 ddl-auto=update, PostgreSQL은 Flyway V3 자동 적용. 정상 기존 DB의 data/ 초기화는 필요 없다.

## Slack 앱 추가 설정

1. OAuth & Permissions → Bot Token Scopes에서 기존 `chat:write`에 **`im:write` 추가** → Reinstall to Workspace.
2. App Home → Show Tabs → **Messages Tab 켜기**. Home Tab은 이번 기능에 필수 아님. DM 텍스트 수신 기능은 없어 im:history/message.im 구독은 불필요.
3. Socket Mode connections:write 앱 토큰과 Interactivity On 유지. 봇을 결과 채널에 초대한다. 재설치 후 채널 멤버십을 확인한다.
4. 재발급한 현재 토큰이 키체인 meetodo-slack-bot-token / meetodo-slack-app-token에 있는지 확인한다. 토큰 자체를 출력·캡처하지 않는다.

공식 근거: [DM 열기와 im:write](https://docs.slack.dev/reference/methods/conversations.open/), [게시와 chat:write](https://docs.slack.dev/reference/methods/chat.postMessage/), [원래 메시지 링크](https://docs.slack.dev/reference/methods/chat.getPermalink/), [Messages Tab](https://docs.slack.dev/surfaces/app-home/).

## 실행

```zsh
export OPENAI_API_KEY="$(security find-generic-password -a "$USER" -s meetodo-openai -w)"
export SLACK_BOT_TOKEN="$(security find-generic-password -a "$USER" -s meetodo-slack-bot-token -w)"
export SLACK_APP_TOKEN="$(security find-generic-password -a "$USER" -s meetodo-slack-app-token -w)"
export OPENAI_CHAT_MODEL=gpt-4o-mini
export NOTIFIER_TYPE=slack-bot
export MEETODO_SLACK_BOT_ENABLED=true
export MEETODO_SLACK_DM_ENABLED=true
export DEV_SLACK_USER_ID='U여기에본인실제ID'
export SLACK_CHANNEL_ID='C여기에채널ID'
./gradlew bootRun --args='--spring.profiles.active=local,openai'
```

다른 서버가 같은 H2 파일을 사용 중이면 먼저 종료한다. DEV_SLACK_USER_ID는 본인의 실제 영숫자 Slack ID로 바꾼다. Docker 기존 실행은 같은 환경변수와 DB 비밀값을 설정한 뒤 `docker compose up -d --build app`으로 새 이미지·V3를 적용한다. Docker Member의 실제 ID 설정은 [단계1 문서](slackBotStep1Done.md)의 SQL 안내를 따른다(local 샘플 갱신과 별개).

## Swagger·Slack 확인 순서

`http://localhost:8080/swagger-ui/index.html` → POST /api/meetings:

```json
{"rawText":"백엔드팀 전해찬은 DM1.5 배포 체크리스트를 2026-11-20까지 작성한다. 백엔드팀 전해찬은 DM1.5 배포 검증표를 2026-11-21까지 공유한다. 김민수는 DM1.5 테스트 계획을 작성한다. 지영은 DM1.5 예약을 확인한다."}
```

1. 응답에서 전해찬의 두 Task가 CREATED인지 확인한다. 기존 TODO와 제목이 같아 EXISTING이면 DM을 보내지 않는 게 정상이다. 새 업무 제목으로 확인하거나 기존 업무를 완료하고 다시 실행한다.
2. 채널에는 네 업무가 보이고, 본인 DM에는 전해찬의 두 업무만 **한 통**으로 온다. 민수 가짜 ID·지영 미정에는 DM을 보내지 않는다.
3. DM의 원래 채널 링크가 해당 회의 알림을 연다.
4. DM에서 하나 완료 → DM과 채널 모두 `✅ 완료 (@본인)`·해당 버튼 제거. 채널에서 다른 하나 완료 → DM도 갱신.
5. 다른 사람의 할 일 클릭 → 본인에게만 권한 안내. 기존 미정 완료 정책은 그대로.
6. 같은 본문 재전송 → 기존 결과 반환·채널/DM 추가 없음. 약간 다른 본문으로 기존 Task 재언급 → 채널은 새 알림, EXISTING에는 DM 없음.
7. `MEETODO_SLACK_DM_ENABLED=false`로 재시작 → 새 Task가 생겨도 채널만 게시.
8. 테스트에서는 DM 권한 실패·갱신 실패를 mock으로 확인한다. 실제 토큰/권한을 일부러 망가뜨릴 필요 없다. 채널 오류가 있으면 GET /api/meetings/{id}의 notificationErrorCode를 확인한다.

예상 DM:

```text
📝 새 할 일이 배정됐어요
🆕 ⬜ 미완료 · @전해찬 · DM1.5 배포 체크리스트 · 2026-11-20  [✅ 완료]
🆕 ⬜ 미완료 · @전해찬 · DM1.5 배포 검증표 · 2026-11-21  [✅ 완료]
원래 채널 메시지 보기
회의 ID · 처리 시간
```

플랫폼 길이를 넘으면 Task 줄 단위로 앞에서부터 표시하고 `외 N건은 GET /api/meetings/{id}에서 확인`을 붙인다. DB에는 전체 Task 목록을 보존한다.

PostgreSQL에서 DM별 기록 확인(비밀값 컬럼 없음):

```zsh
docker compose exec postgres psql -U meetodo -d meetodo -c \
  'SELECT meeting_id,message_type,target_slack_user_id,channel_id,message_ts,delivery_status,error_code FROM slack_message_references ORDER BY id DESC LIMIT 20;'
```

## 면접 질문

- **왜 DM 실패가 채널 성공을 바꾸지 않나요?** 회의 처리가 원래 업무이고 채널 게시와 개인별 DM은 독립적인 외부 전달이다. 개인 실패를 별도 기록해야 성공한 결과를 실패로 오해하거나 전체 알림을 재전송하지 않는다.
- **왜 메시지 내용을 읽어서 완료 표시를 바꾸지 않나요?** DB의 Task 상태·완료자와 게시 당시 Task ID 집합으로 다시 만든다. 메시지 표현과 데이터 규칙이 갈라지지 않으며 채널과 DM이 같은 권한 로직을 사용한다.
- **Slack 오류 코드는 남기면서 토큰은 어떻게 숨기나요?** 안전한 오류 식별자만 허용하고 공급자 예외 원문·인증 헤더·SDK 진단 로그를 남기지 않는다. SDK mock 응답과 로그 캡처로 검증한다.

## 한계

실제 Slack DM은 사용자 확인 대상이다. 전송과 DB 저장은 원자적이지 않고 재시도·Outbox·정확히 한 번 전달은 없다. 관련 메시지 조회는 작은 개발 규모에서 전체 SENT 참조를 검사하며, 커지면 Task-메시지 인덱스가 필요하다. 일부 메시지 갱신 실패 시 DB 완료는 유지되고 Slack 표시만 오래될 수 있다. 단계2 /meetodo 모달은 아직 구현하지 않는다.

## 변경 파일

- 운영 코드: `SlackBotService.java`, `SlackBotRenderer.java`, `SlackBotConfiguration.java`, `SlackMessageReference.java`, `NotificationStateService.java`
- DB: `src/main/resources/db/migration/V3__slack_assignee_direct_messages.sql`, `src/main/resources/db/local/slack-message-dm-upgrade.sql`
- 설정: `src/main/resources/application-local.yml`, `.env.example`, `docker-compose.yml`, `build.gradle`(테스트 외부 전송 차단)
- 테스트: `SlackDirectMessageTests.java`, `SlackErrorCodeTests.java`, `SlackBotServiceTests.java`, `SlackBotRendererTests.java`, `SlackBotConfigurationTests.java`, `MeetingNotificationTests.java`, `SlackAfterCommitPersistenceTests.java`, `PostgresMigrationTests.java`, `LocalSlackMessageUpgradeTests.java`
- 문서: `README.md`, `DEVLOG.md`, 이 문서, `docs/superpowers/plans/2026-11-12-slack-bot-dm-stage1-5.md`

## 자동 검증 결과

`./gradlew test bootJar --no-daemon`: **BUILD SUCCESSFUL, 394개 / 실패0 / 오류0 / 건너뜀0**.

PostgreSQL Testcontainers22개, H2 기존 행 업그레이드/반복 실행/빈 DB4개 포함. missing_scope 소실, DM 미발송, 누락 오류 코드, 기존 H2 새 NOT NULL 컬럼 추가 실패는 수정 전 실패를 확인했다. 새 업무만 DM·제외 조건·사람별 묶음·양방향 완료 갱신·부분 갱신 실패·DM 실패 격리·설정 꺼짐·안전한 링크·길이 제한·로그 보안·기존 전체 회귀를 검증했다. 실제 Slack/OpenAI 호출은 하지 않았다. 사용자 DB 삭제·git 커밋도 수행하지 않았다.
