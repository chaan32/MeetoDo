# 4단계 — Slack / Discord 알림

## 완료한 구조

LLM이 조회·등록 도구를 선택하고 서버가 실행한다. 알림은 결과 전달이 확정된 후속 작업이라 모델 도구로 노출하지 않는다. 완료 트랜잭션 커밋 → AFTER_COMMIT 동기 리스너 → 공통 메시지 → Slack Block Kit 또는 Discord embed → HTTP 요청 1회 → 별도 트랜잭션으로 알림 상태 저장 흐름이다.

MeetingResults는 기존 결과 조회 조립을 분리해 API와 알림이 같은 Task 결과를 사용하도록 한다. MeetingCompletionService는 최종 저장·이벤트 발행만 짧게 트랜잭션으로 묶는다. LLM 실행 전체를 DB 트랜잭션에 넣지 않는다. after-commit 리스너의 상태 갱신은 REQUIRES_NEW를 사용한다. 알림 오류는 별도로 처리하여 완료된 회의를 롤백하지 않는다.

notificationStatus는 PENDING / SENT / FAILED / SKIPPED만 사용한다. notificationType은 실제 활성 공급자(slack/discord), notificationErrorCode는 안전한 코드, notifiedAt은 성공 응답 시각이다. 실패의 HTTP 본문·URL·원문 예외는 저장하지 않는다. 실제 비활성 공급자는 타입 null이며 SKIPPED/NOT_CONFIGURED, 기존 null 상태는 SKIPPED/NOT_ATTEMPTED로 해석한다.

## 변경 파일

- notification/: Notifier, SlackNotifier, DiscordNotifier, NotificationMessage, NotificationMessageFactory, SlackPayloadRenderer, DiscordPayloadRenderer, WebhookHttpClient, NotificationDeliveryException, NotificationStatus, NotificationConfiguration, MeetingFinishedEvent, MeetingNotificationListener, NotificationStateService.
- meeting/: Meeting 알림 필드·상태, MeetingCompletionService, MeetingResults, MeetingApplicationService 연결, dto/MeetingResponse·MeetingHistoryResponse 알림 메타데이터.
- tools/ToolExecutionService.java: 웹훅 URL 형태 마스킹.
- notification 테스트 6개 파일, MeetingApiTests·MeetingToolsTests 보강, local/openai를 모사하는 테스트의 실제 알림 비활성 설정.
- build.gradle: 테스트 프로세스의 실제 웹훅 환경변수 차단. .env.example: 변수 이름 안내.
- README.md, DEVLOG.md, 이 문서.

## 전송 정책과 제한

| 상황 | 동작 |
| --- | --- |
| 새 정상 회의 | tasks 기반 요약·목록 메시지 |
| 정확히 같은 본문 재전송 | 기존 결과·알림 상태 반환, 추가 전송 없음 |
| duplicateOfMeetingId가 있는 새 회의 | 원본 ID·신규/기존/미정 건수만 짧게 전송 |
| FAILED 회의 | 원문·공급자 오류 없는 짧은 실패 알림 |
| 설정/조건 없음 | SKIPPED |
| HTTP 실패/타임아웃 | 회의 상태 유지, 알림 FAILED |

한 회의 알림은 항상 메시지 1건이다. 전체 목록이 들어가는지 먼저 검사한다. 제한을 넘을 때만 footer와 생략 안내를 예약하고 최대 접두 목록을 넣는다. 처음 들어가지 않는 항목에서 멈추므로 뒤의 짧은 항목을 대신 골라 넣지 않는다. 항목 자체는 자르지 않는다. 0개만 들어가도 생략 건수를 안내한다.

Slack 계약: header 150자, section 3000자, 전체 blocks 50개, text fallback 자체 상한 40000자. plain_text 블록과 이스케이프한 fallback으로 텍스트를 전달한다. Discord 계약: 단일 embed title256, description4096, footer2048, 총6000자 이내. 마크다운 이스케이프 후 길이를 계산하고 allowed_mentions.parse=[]로 불필요한 멘션을 막는다. wait=true로 공급자의 전송 확인 응답을 기다린다.

연결 1초 / 요청 3초, 자동 재시도 없음. 타임아웃 시 이미 전송됐을 수 있어 중복 알림 위험이 있으므로 중복보다 누락을 택하고 FAILED로 남긴다. 정확히 한 번 전달은 보장하지 않는다. 커밋 후 프로세스 종료 등에 대비한 Outbox는 후속 개선이며 이번에 구현하지 않았다.

## 공식 문서·소스 근거

- Slack Incoming Webhooks: https://docs.slack.dev/messaging/sending-messages-using-incoming-webhooks/
- Slack blocks/section: https://docs.slack.dev/reference/block-kit/blocks/ , https://docs.slack.dev/reference/block-kit/blocks/section-block/
- Discord execute webhook(wait/embeds/allowed_mentions): https://docs.discord.com/developers/resources/webhook#execute-webhook
- Discord embed limits: https://docs.discord.com/developers/resources/message#embed-limits
- Spring 6.2 afterCommit: https://docs.spring.io/spring-framework/docs/6.2.x/javadoc-api/org/springframework/transaction/support/TransactionSynchronization.html#afterCommit()
- 프로젝트 실제 Spring tx6.2.19 소스의 TransactionalEventListener·TransactionSynchronization을 확인했다. after-commit에서도 자원이 남을 수 있으므로 DB 갱신에 REQUIRES_NEW를 쓰라는 설명과 대조했다.

## 최종 자동 테스트 결과

`./gradlew test bootJar --no-daemon` 성공. 전체 182개, 실패·오류·건너뜀 0. 기존 131개에 단계4 관련 51개 검증을 보강했다. 마지막 명령 재확인은 소스 변경이 없어 up-to-date로 성공했고 테스트 XML과 bootJar 산출물을 확인했다.

공통 조립(신규/기존/사유별 미정/N번째 언급/마감일 없음/실패/중복), Slack·Discord JSON, loopback HTTP 본문·5xx·타임아웃·재시도 없음, 커밋 이후·롤백 시 미전송, 동일 본문 미전송, URL 없음·프로필 격리, 로그 캡처, 대량 목록·잘림 경계를 검증했다. 발견한 ToolCallLog 웹훅 URL 마스킹 누락과 full-fit 잘림 경계 문제는 DEVLOG에 각각 5줄 요약으로 기록했다.

## 실행과 Swagger

Slack 생성 장애로 Discord로 먼저 검증한다. Discord 웹훅 자체 연결은 사용자가 확인했으며 서비스 통합의 실제 채널 결과는 아래 순서로 확인한다. 최종 기업용 도구 데모는 Slack이다.

```zsh
export OPENAI_API_KEY="$(security find-generic-password -a "$USER" -s meetodo-openai -w)"
export DISCORD_WEBHOOK_URL="$(security find-generic-password -a "$USER" -s meetodo-discord-webhook -w)"
export NOTIFIER_TYPE=discord
export OPENAI_CHAT_MODEL=gpt-4o-mini
./gradlew bootRun --args='--spring.profiles.active=local,openai'
```

http://localhost:8080/swagger-ui/index.html 의 POST /api/meetings:

```json
{"rawText":"민수는 로그인 API 오류 처리를 2026-11-20까지 수정한다. 해찬은 배포 체크리스트를 공유한다. 지영은 회의실 예약을 확인한다."}
```

새 초기 데이터에서 기대하는 표시 예시(순서·표현은 실제 tasks에 따른다):

```text
📝 회의 정리 완료 (할 일 3건)
새로 등록 3건 · 기존 할 일 0건
담당자 미정 1건

🆕 민수(백엔드팀) · 로그인 API 오류 처리 · 2026-11-20
🆕 해찬(백엔드팀) · 배포 체크리스트 · 마감일 없음
🆕 담당자 미정 · 회의실 예약 확인 · 마감일 없음 · ⚠️ 동명이인 확인 필요

회의 <meetingId> · 처리 시간 <실제 processingTimeMs>ms
```

1. 응답 status=DONE, notificationStatus=SENT, notificationType=discord, notifiedAt을 확인한다. GET /api/meetings/{id} 및 최근 회의 목록에도 같은 알림 메타데이터가 있다.
2. 같은 JSON을 그대로 다시 보내 새 메시지가 없는지 확인한다. 원래 SENT 상태는 유지한다.
3. 모든 Task가 재사용되는 새 본문이면 새 Meeting·ToolCallLog는 보존하고 원본 ID·건수만 짧게 알린다.
4. URL 환경변수를 해제하고 서버를 다시 실행해 새 회의를 처리하면 DONE+SKIPPED/NOT_CONFIGURED다. 동일 본문은 이전 결과라 이 검증에 새 본문을 사용한다.
5. 기존 Task 재언급 중 duplicate가 아닌 회의는 mentionCount>=2이면 N번째 언급이 표시된다. 긴 목록은 외 N건은 GET /api/meetings/{id}에서 확인 문구로 마무리된다.
6. 종료는 서버 터미널 Ctrl+C. notifier는 LLM 도구가 아니므로 toolCallCount와 ToolCallLog에 notifySlack 항목이 생기지 않는다.

이번 스키마 변경은 nullable 알림 필드 추가이므로 기존 로컬 데이터 삭제가 필수는 아니다. 기존 회의를 자동 전송하거나 알림을 재시도하지 않는다. 같은 본문 캐시·기존 TODO 재사용이 실측에 영향을 주므로 새 데이터 조건이 필요하면 사용자가 data/를 초기화한다. 삭제·커밋·푸시는 사용자가 한다.

Slack 준비 후에는 NOTIFIER_TYPE=slack, SLACK_WEBHOOK_URL을 설정하고 동일 흐름으로 검증한다. 웹훅 URL을 터미널 출력·채팅·저장소에 붙이지 않는다.

## 면접 예상 질문

1. 왜 알림을 모델 도구로 만들지 않았나? — 모델이 선택할 업무 판단이 아니라 확정된 결과 전달이므로 서버가 맡는다. LLM은 팀원·중복 조회·등록 도구를 선택하고, 서버는 검증·실행과 후속 알림을 제어한다.
2. 왜 커밋 이후이며 상태 저장은 별도 트랜잭션인가? — 롤백될 결과를 채널에 먼저 보내지 않기 위해서다. 회의가 커밋된 뒤 전송하고 알림 성공·실패를 새 트랜잭션에 남겨 회의 결과와 격리한다.
3. 왜 타임아웃을 재시도하지 않나? — 응답을 못 받았어도 메시지가 이미 전송됐을 수 있다. 중복보다 누락을 택하고 FAILED로 기록한다. 정확히 한 번 전달은 보장하지 않으며 Outbox·외부 멱등성 검토는 후속 개선이다.
