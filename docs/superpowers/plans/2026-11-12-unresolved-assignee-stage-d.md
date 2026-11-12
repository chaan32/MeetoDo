# 미정 담당자 상세 정보 D 구현 계획

승인 설계: 서버 findMember 기록 + lookupCallOrder로 연결한다. 모델이 보낸 이름·후보는 저장 근거가 아니다.

- [x] 재현: 미정 Task의 GET /api/tasks에서 이름·후보가 없는 것을 RED로 확인한다.
- [x] findMember가 반환하고 기록하는 lookupCallOrder를 createTask가 현재 회의·도구·사유 기준으로 검증한다.
- [x] Task에 최초 조회 스냅샷을 저장하고, EXISTING은 최초 정보를 덮어쓰지 않는다.
- [x] Task/회의 응답에 unresolvedAssignee와 상세 warning을 제공한다. 회의별 조회 정보는 현재 createTask 결과를 우선한다.
- [x] 기존 데이터는 백필하지 않고 기존 경고를 유지한다. NONE은 경고 없음.
- [x] 사유별 API·저장·Slack/Discord JSON, 잘못된 참조, 다른 이름 인자, 여러 조회, 긴 이름·멘션 차단을 검증한다.
- [x] README/DEVLOG에 한계·D 빈 실측 칸·검증 결과를 기록한다.
- [x] ./gradlew test bootJar --no-daemon 전체 검증 및 실패/오류/건너뜀 집계.

변경 범위: tools 조회·등록·공통 실행과 프롬프트, Task/TaskService/DTO, MeetingResults/DTO, 기존 알림 조립 경로 및 관련 테스트. 새 배정 API·실제 멘션·백필·B 정책 변경은 하지 않는다. 데이터 삭제와 커밋은 사용자가 한다.
