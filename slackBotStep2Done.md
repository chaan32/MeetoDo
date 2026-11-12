# Slack 봇 단계2 — /meetodo 회의록 입력

## 구현 범위

`/meetodo` → 회의록 모달 → 제출 ACK → 회의 처리 worker → 원래 채널 `⏳ 회의록 정리 중...` → 같은 채널/ts를 결과로 수정한다. 기존 Task 완료 버튼·실제 ID 멘션·새 CREATED 업무의 담당자별 DM을 그대로 공유한다. 실제 Slack/OpenAI를 호출하는 자동 테스트는 없다.

- source=SLACK_TEXT, private_metadata=원래 채널. 서버의 viewID·사용자·채널 기록과 대조하므로 변조한 채널로 보내지 않는다.
- 모달은 Slack plain_text_input 상한에 맞춰 최대3000자/여러 줄. Swagger API는 기존20000자.
- 동일 본문 해시는 기존 결과를 반환하며 요청자에게만 `이미 정리된 회의록입니다 (회의 N)`을 보낸다. 새 진행 메시지·LLM·채널 알림·DM이 생기지 않는다.
- 처리 실패는 같은 메시지를 `❌ 회의록 정리에 실패했어요 (회의 N)`으로 수정한다. 회의록 원문·공급자 예외를 포함하지 않는다.
- 봇이 채널에 없으면 요청자에게만 `/invite @MeetoDo 후 다시 시도해 주세요.`를 안내한다. 해당 진행 게시 실패는 LLM·Meeting·해시·ToolCallLog 없이 안전한 Slack 코드만 서버 로그에 남긴다. 초대 후 같은 본문을 새로 처리할 수 있다.
- ACK/버튼 작업과 회의 처리 executor를 분리했다. 회의 처리1worker/대기10개이며 넘으면 요청자에게 재시도를 안내한다.
- 기존 메시지 참조 테이블에 진행 좌표·완료 후 Task ID를 기록하므로 새 Flyway 마이그레이션이나 data/ 초기화는 필요 없다.

## 앱 설정

이미 /meetodo를 만들었고 `commands`, `chat:write`, DM용 `im:write`, 앱 토큰 `connections:write`, Socket Mode·Interactivity On·Messages Tab이 준비됐다면 **추가 scope·공개 Request URL 설정은 없다**. 코드의 명령/모달 핸들러 등록으로 동작한다. 결과를 받을 채널에 `/invite @MeetoDo`를 실행한다. 단순 코드 변경 때문에 앱을 재설치할 필요는 없다(권한 변경 시에는 재설치).

공식 근거: [Slash Commands](https://docs.slack.dev/tools/java-slack-sdk/guides/slash-commands/), [Java Modals](https://docs.slack.dev/tools/java-slack-sdk/guides/modals/), [views.open 추가 scope 없음](https://docs.slack.dev/reference/methods/views.open/), [입력3000자 상한](https://docs.slack.dev/reference/block-kit/block-elements/plain-text-input-element/). 실제 Slack SDK1.54.0의 command/viewSubmission/viewClosed/ViewState/ViewsOpenRequest/SlashCommandContext.respond를 확인했다.

## 실행

다른 로컬 서버가 같은 H2 파일을 사용 중이면 먼저 종료한다. 실제 ID 두 곳을 바꿔 실행한다.

```zsh
export OPENAI_API_KEY="$(security find-generic-password -a "$USER" -s meetodo-openai -w)"
export SLACK_BOT_TOKEN="$(security find-generic-password -a "$USER" -s meetodo-slack-bot-token -w)"
export SLACK_APP_TOKEN="$(security find-generic-password -a "$USER" -s meetodo-slack-app-token -w)"
export OPENAI_CHAT_MODEL=gpt-4o-mini
export NOTIFIER_TYPE=slack-bot
export MEETODO_SLACK_BOT_ENABLED=true
export MEETODO_SLACK_DM_ENABLED=true
export DEV_SLACK_USER_ID='U본인실제ID'
export SLACK_CHANNEL_ID='C기본알림채널ID'
./gradlew bootRun --args='--spring.profiles.active=local,openai'
```

`SLACK_CHANNEL_ID`는 기존 Swagger 알림/다이제스트용 기본 채널이다. `/meetodo` 회의 결과는 기본 채널과 달라도 명령을 실행한 원래 채널로 보낸다. DM은 반드시 MEETODO_SLACK_DM_ENABLED=true 상태로 서버를 재시작해야 한다. Docker는 같은 환경변수를 설정하고 `docker compose up -d --build app`으로 새 코드를 적용한다. Docker는 기존 실제 Member Slack ID를 사용하며 local 개발 샘플 자동 갱신과 별개다.

## 실제 Slack 확인

1. 봇을 초대한 채널에서 `/meetodo` → 여러 줄 모달이 열리는지 확인한다.
2. 아래 회의록을 붙여 넣고 정리하기를 누른다.

```text
백엔드팀 전해찬은 슬랙입력2 배포 체크리스트를 2026-11-20까지 작성한다.
백엔드팀 전해찬은 슬랙입력2 검증표를 2026-11-21까지 공유한다.
김민수는 슬랙입력2 테스트 계획을 작성한다.
지영은 슬랙입력2 회의실 예약을 확인한다.
```

3. 모달이 닫히고 원래 채널에 처리 중 메시지가 생긴 뒤 **그 메시지가 결과로 바뀌는지** 확인한다(결과용 새 채널 메시지가 생기면 안 됨).
4. 전해찬 두 업무가 CREATED이고 실제 Slack ID면 DM1통. 민수 가짜ID와 지영 미정에는 DM 없음. 이미 같은 TODO가 있다면 EXISTING이므로 DM 제외가 정상이다.
5. DM에서 완료→채널 반영, 채널에서 완료→DM 반영·버튼 제거를 재확인한다. 원래 채널 링크가 수정된 결과 메시지를 여는지 확인한다.
6. `/meetodo`로 본문을 그대로 다시 제출→요청자에게만 기존 회의 안내. 새 Meeting/Task·진행 메시지·DM 없음.
7. 기본 SLACK_CHANNEL_ID와 다른 초대된 채널에서도 실행해 결과가 명령 채널에 오는지 확인한다.
8. 모달에 공백만 입력→요청자에게 입력 안내, 회의 저장/LLM 호출 없음. 3000자 초과는 Slack 입력 제한과 서버 검증으로 막는다.
9. 봇 미초대 채널에서는 비공개 초대 안내를 확인한다. 실제 토큰을 일부러 잘못 설정할 필요 없다. 처리 실패와 비밀값 비노출은 mock 테스트로 확인한다.
10. Swagger `GET /api/meetings/{id}`, `/logs`, `GET /api/tasks`로 DB 상태·도구 호출·완료자·알림 상태를 확인한다. Slack 알림 상태 SENT는 DM별 성공까지 의미하지 않는다.

실제 결과 칸은 사용자가 채운다. 자동 테스트 결과와 실제 모델/Slack 결과를 구분한다.

## 변경 파일

- 신규 `SlackMeetingInputService.java`, `SlackMeetingInputTests.java`, `SlackMeetingSubmissionTests.java`
- `MeetingApplicationService.java`: Slack source·게시 성공 후 저장·해시 경합 정리 콜백
- `SlackBotService.java`: 진행 게시·저장 위치 수정·기존 DM 재사용
- `SlackMessageReference.java`: 완료 후 Task ID 집합 갱신
- `SlackBotConfiguration.java`, `SlackSocketDispatch.java`: 핸들러·executor·ACK/trigger 순서
- `SlackAfterCommitPersistenceTests.java`, `SlackBotServiceTests.java`, `SlackSocketDispatchTests.java`
- `README.md`, `DEVLOG.md`, 이 문서, 단계2 계획 문서

## 면접 질문

- **왜 ACK와 회의 처리를 나눴나?** Slack은3초 안에 접수 응답이 필요하지만 LLM·도구·외부 전송은 오래 걸릴 수 있다. 실제 wire ACK부터 보내고 별도 worker에서 공통 서비스를 실행한다.
- **왜 Slack 전용 에이전트를 만들지 않았나?** 입력 채널만 달라지고 날짜·중복·도구·측정·알림 규칙은 같다. 새 회의 준비 지점과 메시지 위치만 연결해 Swagger 경로와 같은 검증을 공유했다.
- **봇이 채널에 없는데 비공개 안내는 어떻게 보내나?** 기존 slash 응답 경로는 공식적으로 채널 게시 권한을 우회한다. 짧게 유효한 콜백을 메모리에만 보관하고 URL을 DB·로그에 남기지 않는다.

## 알려진 한계

모달 응답 경로는 메모리에 최대1000개/30분 보관하므로 서버 재시작·만료 때 모달을 다시 열어야 한다. 이 단계의 Socket Mode 입력은 한 인스턴스로 확인하며, 여러 인스턴스가 같은 앱의 이벤트를 나눠 받으려면 모달 상태 공유/라우팅이 필요하다. 처리 대기열은 내구성이 없어 서버 종료 시 대기 작업을 복구하지 않는다. DB 저장과 Slack 수정·DM은 원자적이지 않으며 일부 메시지 수정이 실패할 수 있다. 실제 Slack 연결·모달 왕복 지연은 사용자가 확인한다.

## 자동 검증

`./gradlew test bootJar --no-daemon`: **BUILD SUCCESSFUL, 431개 / 실패0 / 오류0 / 건너뜀0**. 기존 전체 회귀·PostgreSQL22개·H2 업그레이드4개 포함. 실제 Slack/OpenAI 호출·사용자 DB 변경·커밋은 하지 않았다.

## 게시 순서 수정 — 1안 선택

진행 게시를 먼저 성공시킨 뒤 Meeting/해시를 저장한다. 저장 경쟁에서 진 요청은 자기 임시 메시지를 정리하고 기존 회의 안내를 보낸다. 게시 실패 전용 재시도 상태·FAILED→PROCESSING 예외를 만들지 않는다. 기존 완료/처리 실패/처리 중 회의의 해시 중복 규칙은 유지한다.

동시 제출 때 임시 메시지2개가 잠깐 생길 수 있지만 DB 유니크 제약으로 처리·완료 알림은1번이다. 정리는 chat.delete로 자기 봇 메시지만 삭제하며 기존 chat:write 권한을 사용한다. Meeting·ToolCallLog는 삭제하지 않는다. 정리 실패·프로세스 종료·전송 결과 불명확 상황에서는 임시 메시지가 남을 수 있다.

### 실제 확인 순서

1. 기존 DB에 없는 새 본문을 정한다. 예: `전해찬은 게시순서검증 배포 확인표를 2026-11-20까지 작성한다.`
2. 봇 미초대 채널에서 /meetodo로 제출한다. 요청자에게만 `/invite @MeetoDo 후 다시 시도해 주세요.`가 나오고, Swagger GET /api/meetings의 최신 목록에 새 Meeting이 없어야 한다. Task도 추가되지 않아야 한다.
3. 같은 채널에서 `/invite @MeetoDo` 후 **문장을 바꾸지 않고** 다시 제출한다. 진행→같은 메시지의 결과 수정, Meeting1건·Task CREATED·실제 ID 담당자 DM을 확인한다.
4. 완료된 문장을 다시 제출한다. 요청자에게만 기존 회의 안내, 새 진행 메시지/Meeting/Task/DM 없음.
5. 두 사용자 또는 두 모달에서 또 다른 동일 새 본문을 거의 동시에 제출한다. 처리된 Meeting과 할 일은 각각 한 집합이며 한쪽은 기존 회의 안내를 받아야 한다. 잠깐 생긴 중복 진행 메시지는 정리된다. 실제 worker1개 환경은 순차 실행될 수 있으며 자동 테스트는 barrier로 DB 저장 경쟁을 강제로 검증한다.

기존 버전에서 이미 FAILED로 저장된 본문은 자동으로 해제하지 않았다. 데이터 삭제나 과거 로그 변경 없이 새 정책만 적용하므로 위 검증은 새 본문으로 한다. 기존 서버를 종료하고 같은 실행 명령으로 재시작하면 된다. 추가 Slack 설정·Flyway 마이그레이션·data/ 초기화는 없다.

최종 자동 검증에는 미초대→Meeting 없음·안내→동일 본문 정상 처리→완료 중복 안내의 실제 JPA 경로, 두 요청 barrier 경쟁의 단일 처리, 임시 메시지 삭제 실패 시 로그 보안이 포함된다. 실제 Slack은 호출하지 않았다.

추가 면접 질문: **왜 재시도 예외 대신 순서를 바꿨나?** 게시 실패가 해시를 잠그는 원인을 제거해 별도 실패 상태·재시도 선점 규칙을 없앴다. 대신 동시 요청의 임시 게시2개와 best-effort 정리를 허용하며 DB 해시 제약으로 에이전트는1번만 실행한다.
