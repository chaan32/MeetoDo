# MeetoDo 진행 상황

작성일: 2026-11-11

## 현재 위치

3단계 「도구 호출 에이전트 + ToolCallLog」의 3-1~3-5를 구현하고 자동 검증까지 완료했다. 전체 정리는 [thirdStepDone.md](thirdStepDone.md), 수동 테스트 표는 [testing-plan-3-4.md](testing-plan-3-4.md)다.

## 완료된 범위

- Meeting·ToolCallLog 도메인과 저장소, 상태 전이 및 순번 조회
- `findMember`, `findTasks`, `createTask`와 공통 ToolCallLog 실행·마스킹 경계
- Spring AI 1.1.8 수동 도구 호출 루프, 최대 20 도구 호출, Asia/Seoul 날짜 컨텍스트
- `POST /api/meetings`, `GET /api/meetings/{id}`, `GET /api/meetings/{id}/logs`
- 같은 본문·같은 회의 작업 중복 방지, 실패/빈 응답 처리와 일반화된 오류 응답
- 테스트용 ChatModel mock 흐름, API·프로필·보안·동시 요청 검증

Task와 ToolCallLog 모두 Meeting을 JPA 연관관계로 참조한다. Task API 응답은 호환성을 위해 meetingId 값으로 반환한다. 실제 OpenAI 호출은 테스트에서 수행하지 않았으며, Swagger 수동 실행 결과와 측정값은 사용자 확인 대기다.

## 검증

`./gradlew test bootJar --no-daemon`: 64개 테스트 통과, 실패·오류·건너뜀 0.

## 다음 단계

4단계 Slack Block Kit 알림을 설계·구현한다. 이번 단계에는 `notifySlack`을 추가하지 않았다.
