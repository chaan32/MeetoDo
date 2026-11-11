# MeetoDo 세 번째 작업 정리

작성일: 2026-11-11

## 범위

이 문서는 [secondDone.md](secondDone.md) 작성 뒤 진행한 작업을 기록한다. 프로젝트 초기 설정과 개발용 OpenAI 연결 확인은 [firstDone.md](firstDone.md), [secondDone.md](secondDone.md)를 참고한다.

## 1. 이번에 완료한 작업: Task API와 Member 모델

`Task`와 `Member` 도메인, 할 일 API, 서비스 계층을 추가했다.

### 데이터 모델

- `Member`: id, name, slackUserId.
- `Task`: id, title, nullable assignee(Member), dueDate, status, meetingId, createdAt.
- 상태는 `TODO`, `DONE` enum.
- `dueDate`는 날짜만 담는 `LocalDate`, `createdAt`은 시각을 담는 `Instant`.
- 담당자가 미정이면 문자열 대신 `assignee: null`로 표현.
- 요청·응답 DTO와 JPA 엔티티를 분리.

### API

| 요청 | 기능 |
| --- | --- |
| `POST /api/tasks` | 작업 생성. 담당자와 마감일은 선택 입력 |
| `GET /api/tasks?assignee=민수` | 이름 기준 담당자 필터 조회. 생략하면 전체 조회 |
| `PATCH /api/tasks/{id}` | `TODO` 또는 `DONE` 상태 변경 |

입력 검증 오류는 `VALIDATION_ERROR`, 없는 팀원은 `MEMBER_NOT_FOUND`, 없는 작업은 `TASK_NOT_FOUND` 등 `code`, `message`, `fieldErrors` 형식으로 반환한다.

컨트롤러는 HTTP 입출력을 맡고, `TaskService`가 생성·조회·상태 변경 유스케이스를 처리한다. 이후 `createTask`, `findTasks`, `findMember` 도구가 HTTP 컨트롤러를 거치지 않고 서비스를 재사용할 수 있도록 분리했다.

### 개발용 팀원 데이터

아직 팀원 등록 API가 없어 `local` 프로필에서만 시작 시 샘플 팀원 민수·지영·해찬을 이름 기준으로 추가한다. Slack ID는 실제 값이 아닌 `U-DEV-*` 식별자다. 운영 환경에서 사용할 사용자 데이터가 아니다.

## 2. 테스트와 발견한 문제

서비스 단위 테스트 4개와 H2·MockMvc 기반 API 테스트 3개를 작성했다. 전체 검증에서 총 **23개 테스트가 통과**했고 `./gradlew test bootJar --no-daemon` 빌드도 성공했다.

전체 테스트 첫 실행 때 기존 LLM ping 테스트 3개가 실패했다. Task 엔티티를 추가한 뒤 테스트 컨텍스트가 `ddl-auto=none`으로 설정되어 필요한 테이블이 만들어지지 않은 것이 원인이었다. 해당 테스트의 재정의를 제거하고 공통 test 프로필의 `create-drop`을 사용해 해결했다. 이후 전체 테스트가 통과했다.

실제 OpenAI 연결은 사용자가 확인했다고 전달했다. 이 코드 변경에서는 새 OpenAI 요청을 보내지 않았다.

## 3. 3단계 진행 상황

3-1~3-5를 완료했다. Meeting·ToolCallLog 저장소, 세 도구와 입력 검증, Spring AI 도구 호출 루프, 회의 처리 API, 중복 방지, mock LLM 자동 테스트와 수동 실행 안내까지 구현했다. 설계 및 실행 상세는 [thirdStepDone.md](thirdStepDone.md)에 기록했다.

Spring AI 1.1.8 공식 Tool 문서와 라이브러리 코드를 확인했고, 테스트에서는 mock ChatModel만 사용했다. 실제 OpenAI 호출은 하지 않았다. 전체 검증은 64개 테스트 통과, 실패·오류·건너뜀 0이다. 실제 통합 테스트에서 발견한 지연 로딩 오류와 수정은 DEVLOG에 기록했다.

## 4. 그 이후의 진행 순서

| 순서 | 작업 | 상태 |
| --- | --- | --- |
| 1 | 프로젝트 설정 및 실제 OpenAI 연결 | 완료: 사용자가 실제 연결 확인 |
| 2 | Task 등록·조회·상태 변경 API | 완료: 테스트·빌드 통과 |
| 3-1 | Meeting·ToolCallLog 도메인과 저장소 | 완료: 상태 전이·순서 조회 테스트, 전체 빌드 통과 |
| 3-2 | 도구 구현과 입력 검증 | 완료: 도구 테스트 11개, 전체 38개 테스트 및 bootJar 통과 |
| 3-3 | 에이전트 흐름과 회의 처리 API | 완료 |
| 3-4 | 자동·수동 테스트 | 완료; 자동 63개 통과, 수동 표 제공 |
| 3-5 | 실제 실행 예시와 기준값 기록 양식 | 완료; 실제 실행·측정은 사용자 확인 대기 |
| 4 | Slack Block Kit 알림 | 예정 |
| 5 | 매일 아침 마감 알림 | 예정 |
| 6 | 테스트 보강 | 계속 보강 |
| 7 | Docker Compose와 PostgreSQL | 예정 |
| 8 | 로컬 Kubernetes와 다중 인스턴스 문제 재현·해결 | 예정 |
| 9 | Slack 텍스트 입력·스레드 답장 | 확장 예정 |
| 10 | Slack 녹음 파일·음성 인식 | 확장 예정 |
| 11 | 명령어·버튼·사람 승인 | 여유가 있으면 진행 |
| 12 | 실측 기반 개선 실험, README·데모·포트폴리오 | 예정 |

## 5. 다음 단계

다음은 4단계 Slack Block Kit 알림이다. 3단계에서는 notifySlack 도구를 추가하지 않았다. 실제 OpenAI 사용자의 Swagger 검증 결과와 측정값은 사용자가 실행한 뒤 기준값 표에 기록한다.

관련 문서: [AGENTS.md](AGENTS.md) · [firstDone.md](firstDone.md) · [secondDone.md](secondDone.md) · [README.md](README.md) · [DEVLOG.md](DEVLOG.md)


## 최신 진행 갱신 (2026-11-11)

이 문서의 앞부분은 3-2 완료 시점의 스냅샷이다. 이후 3-3~3-5를 완료했다. 최신 상세 내용, 설계 선택, 실행 안내, 실측값 입력 양식은 [thirdStepDone.md](thirdStepDone.md), 수동 시나리오는 [testing-plan-3-4.md](testing-plan-3-4.md)를 참고한다. 전체 자동 검증은 64개 테스트 통과, 실패·오류·건너뜀 0으로 끝났다.
