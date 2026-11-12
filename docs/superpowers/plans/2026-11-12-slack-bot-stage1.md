# Slack 봇 단계1 구현 계획

사용자 정책 승인: 지금은 Swagger 입력→봇 메시지·실제 ID 멘션·완료 버튼만 구현한다. /meetodo 모달·처리 중 메시지·제출 흐름은 단계2 확인 전 구현하지 않는다.

- [x] PATCH 완료 시각 누락·Slack 토큰 마스킹·봇 메시지 기능의 RED 확인.
- [x] Task 완료 권한·완료자/시각·기존 PATCH 전이 규칙 통일 및 row lock.
- [x] Meeting 채널/ts와 게시 Task ID 목록 저장, Flyway V2와 실제 PostgreSQL 검증.
- [x] 봇 전송·DB 기반 갱신·멘션/텍스트·TODO 버튼·길이 제한·다이제스트 동일 기능.
- [x] Socket Mode opt-in과 ACK 선행/비동기 처리, 테스트 기본 비연결·토큰/원문 비노출.
- [x] DEV_SLACK_USER_ID, local/Docker 환경변수·문서·Swagger 안내.
- [x] 전체 ./gradlew test bootJar --no-daemon, 실제 외부 Slack/OpenAI 호출 없이 검증.

외부 SDK 공식 문서·실제 버전 소스/API로 확인한다. 상태는 DB에서 읽고 Slack 본문을 상태 저장소로 사용하지 않는다. 다이제스트는 원래 게시 Task 목록을 보존해 완료 후에도 완료 줄을 표시한다. 다른 메시지의 자동 동기화·Outbox·실제 멘션 도달 보장은 이번 범위 밖이다.
