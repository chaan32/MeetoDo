# Slack 입력 단계2 Implementation Plan

**Goal:** /meetodo 모달 제출을 즉시 ACK하고 기존 회의 처리·알림·DM을 공유한다.

**Architecture:** SlackMeetingInputService가 모달/입력/비동기 실행을 맡는다. MeetingApplicationService는 새 해시 선점 시에만 진행 메시지 준비 콜백을 실행한다. SlackBotService는 저장된 진행 메시지 좌표가 있으면 chat.update하고, 기존 AFTER_COMMIT·DM 경계를 유지한다.

**Tech Stack:** Java21, Bolt/Slack SDK1.54.0 SocketMode, 기존 JPA/H2/PostgreSQL.

**Spec:** 사용자 단계2 정책. 실제 Slack/OpenAI 호출·사용자 data/ 삭제·커밋 금지.

## 설계 선택

- 완료 후 새 게시 대신 저장된 진행 메시지를 수정한다. 채널/DM 참조는 기존 테이블·컬럼을 재사용한다(스키마 추가 없음).
- 중복 사전 조회만 믿지 않고 SHA-256 유니크 선점 후 새 회의에만 콜백을 실행한다. 기존 결과에는 진행 메시지·LLM·새 알림 없음.
- 회의 처리 worker는 Socket ACK/버튼/모달 열기와 분리한다. ACK를 먼저 전송하며, Slash trigger는3초 내 사용하도록 대기 작업 뒤에 두지 않는다.
- 모달은 plain_text_input의 공식 최대3000자·여러줄. API는 기존20000자 유지.
- 채널 봇 미초대 시 chat.postEphemeral도 실패할 수 있다. slash response_url 기반 비공개 응답 콜백을 viewID별 메모리에만 최대30분/1000개 보유한다. URL/컨텍스트는 DB·로그에 저장하지 않는다. 재시작·만료 때 모달 다시 열기를 안내한다.
- 진행 게시 실패는 LLM 없이 FAILED/알림FAILED 및 안전한 오류 코드로 보존한다. 처리 실패는 기존 원문 없는 실패 알림으로 같은 메시지를 수정한다. DM은 CREATED 실제 ID만 기존규칙 유지.

## 실행

- [x] 모달 views.open 미호출 RED, 진행 게시 미호출 RED, 새 Slack source/콜백 순서·게시 실패3개 중2개 RED.
- [x] 진행 좌표 저장·AFTER_COMMIT update·새 배정 DM 실제 JPA 통합 RED→GREEN.
- [x] 명령/모달 제출 ACK·private_metadata·검증·중복·실패·초대·만료·worker 거절 mock 테스트.
- [x] README/DEVLOG/실행·실측 안내 반영.
- [x] ./gradlew test bootJar --no-daemon 전체 검증 후 멈춤.

## 공식 근거

- https://docs.slack.dev/tools/java-slack-sdk/guides/slash-commands/ : command, ACK3초, SocketMode RequestURL 불필요.
- https://docs.slack.dev/tools/java-slack-sdk/guides/modals/ : viewSubmission/state/private_metadata, ACK3초.
- https://docs.slack.dev/reference/block-kit/block-elements/plain-text-input-element/ : max_length<=3000.
- https://docs.slack.dev/interactivity/handling-user-interaction/ : trigger3초, response_url은채널게시권한우회·30분5회·ephemeral.

진행 게시 실패의 안전한 Slack 코드 보존 테스트1건 RED 후 보완했고, 실제 JPA 실패 메시지 수정·DM 경로도 GREEN. 봇 미초대 후 같은 본문을 재시도할지 사용자 정책 확인 대기(기존 해시 규칙과 충돌).

현재 전체검증: 425개 / 실패0 / 오류0 / 건너뜀0, bootJar 성공. 동일 본문 진행 게시 실패 재시도 예외는 사용자 확인 대기로 아직 구현하지 않았다.

## 후속 승인 — 게시 선행1안

- [x] 기존 본문 조회 후 진행 게시 성공 시에만 Meeting/해시 저장, 게시 실패 데이터 미저장.
- [x] 유니크 해시 경합 패자는 필수 정리 콜백으로 자기 진행 메시지 삭제 후 기존 결과 반환.
- [x] 미초대→데이터 없음·안내, 초대 후 같은 본문 성공, 완료 재전송 중복, barrier 동시2요청 처리1번 검증.
- [x] 최종 전체 ./gradlew test bootJar --no-daemon 결과 기록.

이 승인이 위 '정책 확인 대기'를 대체한다. 과거에 이미 저장된 FAILED 행은 변경하지 않는다.

게시 선행1안 최종: 전체431개, 실패/오류/건너뜀0, test·bootJar 성공. 실제 Slack/OpenAI·사용자 DB 변경 없음.
