# Slack 담당자 DM 단계 1.5 Implementation Plan

**Goal:** 새로 생성된 담당자 확정 업무를 사람별 DM으로 보내고 채널·DM 완료 상태를 DB 기준으로 동기화한다.

**Architecture:** 기존 SlackBotService·renderer·메시지 참조 테이블을 확장한다. 채널 게시와 참조 커밋 후 DM별 전송·상태 저장을 별도로 시도한다. 실패가 회의/채널 성공을 되돌리지 않는다.

**Tech Stack:** Java 21, Spring Boot, Slack Java SDK 1.54.0, JPA, Flyway V3, H2/PostgreSQL.

**Spec:** 사용자 단계 1.5 정책. 단계2 모달은 범위 밖. 실제 Slack/OpenAI 호출·사용자 DB 삭제·커밋 금지.

## 선택과 범위

- 별도 메시지 테이블 대신 기존 slack_message_references에 CHANNEL/DM, 대상 사용자, SENT/FAILED·오류 코드를 추가한다. 실패 전 위치를 모를 수 있어 channel/ts null 허용. V1/V2는 수정하지 않는다.
- 메시지 본문 파싱 대신 DB가 보유한 Task ID 목록으로 관련 메시지를 찾고 현재 Task 상태로 다시 만든다. 전체 SENT 참조 조회는 작은 개발 규모의 선택이며 커지면 Task-메시지 정규화 인덱스로 바꾼다.
- DM은 CREATED + 실제 Slack 사용자 ID만, 한 회의/사람별 묶음. 기존 재언급·미정·가짜 ID·다이제스트 제외. 기본 꺼짐.
- 원래 채널 링크는 chat.getPermalink, 대화는 conversations.open(users=사용자1명), 전송은 chat.postMessage, 갱신은 chat.update.
- Slack 오류는 비밀값 없는 알려진 코드만 보존하고 공급자 원문/예외 cause는 기록하지 않는다.

## 실행·검증

- [x] Slack missing_scope 소실 RED (1개 실패), 상태 코드 변환 RED (4개 중2개 실패), 실제 PostgreSQL 누락 컬럼 RED (2개 실패).
- [x] DM 제외/묶음/설정·권한·실패 격리·양방향 갱신 mock 테스트 RED → 구현 → GREEN.
- [x] V3 적용·기존 CHANNEL 기본값·FAILED DM null 위치·Hibernate validate 실제 PostgreSQL 검증.
- [x] env 예시/Compose/테스트 안전 기본값/README/DEVLOG/실행 안내 반영.
- [x] ./gradlew test bootJar --no-daemon 전체 검증 후 실제 결과 기록, 사용자 확인 대기.

## 공식 근거

- https://docs.slack.dev/reference/methods/conversations.open/ : im:write, users 1명으로 1:1 DM.
- https://docs.slack.dev/reference/methods/chat.postMessage/ : chat:write, 채널/DM 게시.
- https://docs.slack.dev/reference/methods/chat.getPermalink/ : 추가 scope 없음, channel/message_ts.
- https://docs.slack.dev/surfaces/app-home/ : Messages Tab 켜기, chat:write. im:history/message.im은 DM 텍스트 수신 때 사용(이번 범위 아님).

추가 검증: 기존 행이 있는 H2 update 재현2건 RED를 확인해 local 시작 SQL로 기본값·NULL 허용을 보정했다. 신규 DB·재실행 포함4건 GREEN. 공식 문서에 있는 Slack 추가 오류 코드3건 RED 후 허용 목록에 반영했다. 최종 전체 회귀를 다시 실행한다.

최종 결과: ./gradlew test bootJar --no-daemon 성공. 394개, 실패/오류/건너뜀0. 실제 Slack/OpenAI 호출·사용자 DB 변경·커밋 없음.
