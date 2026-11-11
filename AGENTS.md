# AGENTS.md — MeetoDo

회의록(또는 녹음)을 주면 AI 에이전트가 할 일을 추출해 담당자·마감일과 함께 등록하고, 슬랙으로 알려주는 백엔드 서비스.

---

## 0. 너(AI)의 역할과 작업 방식 — 가장 중요

- 나는 이 프로젝트를 포트폴리오와 면접에 쓸 개발자다. 면접에서 "어떤 문제가 있었고, 원인을 어떻게 확인했고, 어떻게 고쳤는지"를 내 경험으로 설명할 수 있어야 한다.
- 코드는 네가 작성한다. 대신 작업 전에 무엇을 어떤 구조로 만들지, 주요 설계 선택지 2개 이상과 고른 방식·이유를 먼저 설명하고 내 확인을 받는다.
- 작업 후에는 바뀐 파일 목록, 내가 직접 실행·확인할 방법(명령어, Swagger 경로, 요청 예시 JSON, 기대 결과), 면접에서 물어볼 만한 "왜 이렇게 했나" 질문 2~3개와 답을 정리한다.
- 한 단계(또는 하위 단계)가 끝나면 반드시 멈추고 내 확인을 받는다. 확인 전에는 다음 단계로 넘어가지 않는다.
- 보일러플레이트(설정 파일, Dockerfile, Compose, Kubernetes 매니페스트 뼈대, 단순 DTO, 테스트 뼈대, 슬랙 Block Kit JSON 틀)는 각 설정이 무슨 역할인지 짧게 설명한다.
- 내가 코드를 수정해서 보여주면 버그와 설계 문제를 이유와 함께 짚고, 더 나은 방법이 있으면 대안과 트레이드오프를 설명한다.
- 문제가 생기면 바로 고치기 전에 "증상 → 가설 → 확인 방법"을 먼저 정리해서 보여준다. 해결 후에는 DEVLOG.md용 5줄 요약(증상/가설/확인/원인/해결→결과)을 제안한다.
- 내가 실제 실행에서 발견한 문제를 가져오면, 먼저 내 분석이 맞는지 코드로 확인한 뒤 설계를 제안한다.
- 라이브러리 버전이나 API 사용법(Spring AI, OpenAI, Slack API)이 불확실하면 추측하지 말고 공식 문서와 실제 라이브러리 소스로 확인하고, 확인한 근거(문서 위치나 클래스 이름)를 짧게 남긴다.
- 성능 개선은 반드시 개선 전 측정값을 먼저 확보한 뒤 진행한다. 수치를 지어내지 않는다. 실제로 일어나지 않은 문제도 DEVLOG와 README에 쓰지 않는다.
- 앞으로 겪을 문제를 미리 다 막아두지 않는다. 실제로 부딪히고 원인을 확인하는 과정을 남길 수 있게 한다. 단, 데이터 손실이나 비밀값 노출 같은 위험은 미리 경고하고 막는다.
- 로컬 DB 파일(`data/`) 삭제, git 커밋·푸시는 내가 직접 한다. 필요하면 안내만 한다.

## 0-1. ponytail과 함께 쓸 때의 규칙

- ponytail의 "최소한의 코드" 원칙은 프로덕션 코드에만 적용한다.
- 아래는 줄이거나 생략하지 않는다. ponytail 규칙과 충돌하면 이 항목을 우선한다.
    - 테스트: 재현 테스트(수정 전 실패 확인), 경계값·오류 입력 테스트, 회귀 테스트, 실제 모델의 비정상 입력(모순된 인자, 여분 필드, 잘못된 형식)을 흉내 낸 mock 테스트
    - 입력 검증, 오류 응답 형식, 도구 결과 메시지
    - ToolCallLog·측정값(처리 시간, LLM 호출 수, 도구 호출 수, 검증 실패 수) 기록
    - 비밀값 마스킹, 로그 보안
    - DEVLOG, 설계 문서, 면접 예상 질문 정리
- 테스트는 짧게 쓰려고 케이스를 합치지 않는다. 케이스마다 무엇을 검증하는지 테스트 이름과 1줄 주석으로 드러나게 분리한다.
- 코드를 줄일 때는 "기능·검증·테스트를 유지한 채 중복과 불필요한 추상화만 줄인다"를 기준으로 한다.
- 기능이 끝나면 `@ponytail-review`로 불필요한 프로덕션 코드를 점검할 수 있다. 단, 위 보호 항목은 삭제 대상이 아니다.

---

## 1. 배경

- 지원 직무: 기업용 AI Agent 플랫폼 백엔드 개발 (인턴)
- 주요 업무: AI Agent 플랫폼 백엔드 API 개발 / LLM과 업무 도구를 연결하고 반복 업무를 실행하는 워크플로 개발
- 자격 요건: Java 기본 지식 / 직접 만든 기능에서 문제를 찾고 원인을 확인해 고친 경험 / 작업 내용과 판단 근거를 공유하고 피드백으로 보완한 경험
- 우대 사항: Spring Boot / Docker·Kubernetes 배포 / LLM API 기능, AI Agent가 업무 도구를 호출하도록 연결한 경험 / AI 코딩 도구로 생성한 코드를 테스트와 리뷰로 검증한 경험
- 목표: 위 요건을 작고 완성된 서비스 하나로 보여준다.

## 2. UI/UX 원칙

- 별도 웹 프론트엔드는 만들지 않는다. 슬랙이 곧 사용자 화면이다.
- 필수 단계 입력은 Swagger UI(springdoc-openapi)로 API를 호출한다.
- 출력은 슬랙 메시지다. LLM summary를 그대로 쓰지 않고, 요약은 2~3줄로 짧게, 할 일 목록은 서버의 tasks(resultType, mentionCount, 경고 포함)로 Block Kit 형식으로 만든다.
- 확장 단계에서 입력도 슬랙으로 옮긴다: 봇에게 회의록 텍스트 → 봇에게 녹음 파일.
- 처리 중임을 알리는 즉시 응답과, 완료 후 같은 스레드 답장으로 상태를 알 수 있게 한다.

## 3. 핵심 시나리오

1. 사용자가 회의록 텍스트(또는 녹음 파일)를 보낸다.
2. 에이전트(LLM)가 할 일을 추출하고, 필요한 도구를 스스로 골라 호출한다. (팀원 조회 → 중복 확인 → 할 일 등록)
    - 도구를 실행하는 주체는 LLM이 아니라 서버다. LLM은 어떤 도구를 어떤 인자로 호출할지만 정하고, 서버가 검증·실행 후 결과를 다시 LLM에 돌려주는 루프를 반복한다.
    - 도구 결과 메시지도 모델에게 주는 프롬프트다. 결과 메시지는 한쪽으로 유도하지 않고 중립적으로 쓴다.
3. 결과가 슬랙 채널(또는 원래 메시지의 스레드)로 전송된다.
4. 매일 아침 9시, 마감 임박·지연 할 일을 담당자별로 정리해 슬랙으로 알린다.

---

## 4. 기술 스택

| 구성 | 버전/선택 |
| --- | --- |
| Java | 21 |
| Gradle Wrapper | 8.14.3 |
| Spring Boot | 3.5.16 |
| Spring AI | 1.1.8 (OpenAI Chat, Tool Calling / 음성 인식은 확장 단계) |
| springdoc-openapi | 2.8.17 |
| DB | 로컬 H2 파일 DB(`data/`), 테스트 H2 메모리 DB → Docker 단계에서 PostgreSQL |
| 슬랙 | Incoming Webhook(필수 알림) / 슬랙 앱·봇, Socket Mode, Block Kit(확장) |
| 배포 | Docker, Docker Compose, 로컬 Kubernetes(kind 또는 minikube): Deployment, Service, ConfigMap, Secret |
| 테스트 | JUnit 5, Spring Boot Test, MockMvc, mock ChatModel (실제 OpenAI 호출 금지) |

### 프로필

- 기본 프로필 `local`. AI 모델은 비활성화되어 키 없이 개발 가능.
- OpenAI 관련 빈과 엔드포인트는 `local & openai & !test` 조건에서만 활성화한다.
- 비활성 프로필에서 `POST /api/meetings`는 `404 MEETING_AGENT_DISABLED`.
- 테스트 프로필은 H2 메모리 DB와 `create-drop`을 사용한다.

### 비밀값

- OpenAI 키, 슬랙 웹훅 URL, 봇 토큰은 로컬에선 macOS 키체인 → 환경변수, Kubernetes에선 Secret으로 관리한다. 저장소에 올리지 않는다.
- API 키, Authorization 헤더, 회의록 원문 전체를 로그·응답·ToolCallLog에 남기지 않는다.

로컬 실행:

```zsh
export OPENAI_API_KEY="$(security find-generic-password -a "$USER" -s meetodo-openai -w)"
export OPENAI_CHAT_MODEL=gpt-4o-mini
./gradlew bootRun --args='--spring.profiles.active=local,openai'
```

---

## 5. 도메인 모델 (현재)

- **Member**: id, name, team, slackUserId
    - 이름은 유니크하지 않다(동명이인 허용). 구분은 id와 team으로 한다.
    - 개발용 샘플은 local 프로필에서만, slackUserId 기준으로 추가한다. (예: 민수, 백엔드팀 지영, 디자인팀 지영, 해찬)
- **Task**: id, title, assignee(Member, nullable = 담당자 미정), dueDate(LocalDate, nullable), status(TODO/DONE), meeting(ManyToOne, nullable), createdAt(Instant)
- **Meeting**: id, rawText, contentHash(SHA-256, unique), source, status(PENDING/PROCESSING/DONE/FAILED), summary, duplicateOfMeeting(자기참조, nullable), 처리 시작·종료 시각, processingTimeMs, llmCallCount, toolCallCount, validationFailureCount, 생성 시각
- **ToolCallLog**: id, meeting(ManyToOne), callOrder, toolName, arguments(JSON, 최대 4,000자, 마스킹), result(최대 4,000자, 마스킹), success, errorCode, errorMessage, latencyMs, createdAt
    - 개선 전후 비교의 근거 데이터다. 삭제하지 않는다.
- **TaskMention**: id, task, meeting, mentionedAt — (taskId, meetingId) 유니크
    - mentionCount = 해당 Task가 언급된 서로 다른 Meeting 수. duplicateOfMeeting이 있는 Meeting은 제외.
    - 숫자 컬럼을 ++ 하지 않는다 (동시 갱신 충돌, 멱등성, 이력 보존 때문).
    - 조회 시 묶음 집계 쿼리로 계산한다 (N+1 방지). 회의 없이 직접 만든 Task는 0.

## 6. 확정된 정책

### 날짜
- 오늘 기준은 주입된 Clock의 Asia/Seoul 날짜. 시스템 프롬프트에 오늘 날짜를 넣는다.
- "이번 주 안에"는 해당 주 일요일로 해석한다.
- 날짜는 서버에서 `yyyy-MM-dd`로 엄격 검증한다.
- 과거 마감일: 첫 시도는 `INVALID_DUE_DATE` 오류로 모델이 다시 시도하게 한다. 재시도 후에도 과거면 마감일 없이 등록하고 경고를 남긴다.

### 중복
- **같은 회의록 재전송**: 본문을 정규화(trim, 줄바꿈 정리)한 SHA-256 해시로 판단. 이미 처리된 같은 본문이면 기존 결과 반환, 처리 중이면 202 PROCESSING.
- **할 일 중복 기준**: 같은 담당자(미정 포함) + 정규화된 제목(앞뒤 공백 제거, 연속 공백 축약, 대소문자 무시) + status=TODO. 모든 회의를 대상으로 한다. DONE은 재사용하지 않는다.
    - 에이전트 경로: 조용히 기존 Task 재사용, 응답에 `resultType: EXISTING`
    - 직접 API(`POST /api/tasks`): `409 DUPLICATE_TASK` + `existingTaskId`
    - 두 경로는 같은 중복 판정 함수를 사용한다.
- **비슷한 회의**: 처리된 Task가 모두 EXISTING이고 한 기존 Meeting의 Task 집합과 같으면, 새 Meeting을 저장한 채로 `duplicateOfMeetingId`에 원본을 기록한다. Meeting과 ToolCallLog는 삭제하지 않는다.

### 담당자와 동명이인
- 원칙: **확실하지 않으면 추측해서 배정하지 않는다. 담당자 미정으로 두고 알린다.**
- 등록되지 않은 이름은 담당자 미정(null)으로 등록한다.
- 동명이인이 있으면 이름만으로 배정하지 않는다. id(와 team)로 확정한다.
- 서버는 모델이 고른 memberId가 실제 후보 목록에 있는지 검증한다. 후보 밖 id는 거절한다.
- 서버는 모순된 입력(배정과 미정을 동시에 요청 등)을 받아도 한쪽으로 추측하지 않는다. 거절하고 올바른 방향으로 안내한다.
- 직접 API는 `assigneeId`를 우선한다. 이름만 왔는데 여러 명이면 `400 AMBIGUOUS_MEMBER` + 후보 목록(id, name, team).
- `GET /api/tasks?assignee=이름`은 같은 이름 담당자 전체, `?assigneeId=`는 한 명.
- 슬랙 연동 단계에서는 멘션의 슬랙 사용자 ID로 담당자를 확정한다.

### 요청 검증
- 외부에서 들어오는 HTTP 요청 본문에만 알 수 없는 필드를 거부한다 (`400 VALIDATION_ERROR`, `fieldErrors`에 필드명).
- 전역 Jackson 설정은 바꾸지 않는다. OpenAI 응답 파싱과 도구 인자 파싱은 여분 필드를 허용한다.
- 원칙: **받는 요청은 엄격하게, 외부 API 응답은 관대하게.**
- 회의록 본문은 최대 20,000자. 빈 입력은 거절한다.

### 에이전트 실행
- Spring AI 자동 도구 루프 대신 `ToolCallingManager.executeToolCalls`를 직접 반복 호출한다.
- 회의 1건당 도구 실행 상한 20회. 넘으면 Meeting을 FAILED로 남긴다.
- 예상 가능한 입력 오류는 예외 대신 모델이 읽을 수 있는 결과(errorCode + 짧은 설명)로 돌려준다. 공통 실행 경계에서 순번·시간·성공 여부·마스킹을 처리한다.
- LLM 오류, 빈 응답, 상한 초과는 FAILED + 일반화된 오류 응답 (공급자 예외 원문 노출 금지).

## 7. 에이전트 도구

| 도구 | 역할 |
| --- | --- |
| findMember(name, team?) | 팀원 조회. 여러 명이면 AMBIGUOUS + 후보 목록(id, name, team) |
| findTasks(assignee, title) | 모든 회의를 대상으로 미완료 중복 확인 |
| createTask(title, assignee, dueDate) | 할 일 등록. 서비스 계층(TaskService) 재사용 |
| notifySlack(message) | 슬랙 알림 (4단계에서 도구로 둘지, 서버가 직접 보낼지 결정) |

도구 설명·결과 메시지 규칙:
- 회의록에 팀이나 역할이 언급되면 처음부터 team을 함께 넣어 조회하게 안내한다 (LLM 왕복을 줄이기 위해).
- 팀 없이 조회해서 여러 명이 나오면, 결과 메시지는 중립적으로: "회의록에 팀이 언급되어 있으면 team을 함께 넣어 다시 조회하세요. 언급이 없으면 담당자 미정으로 처리하세요."
- 담당자 입력은 모순이 생길 수 없는 구조로 둔다.

## 8. API (현재)

| 메서드 | 경로 | 설명 |
| --- | --- | --- |
| POST | /api/tasks | 할 일 등록 (assigneeId 우선, 중복이면 409) |
| GET | /api/tasks?assignee=&assigneeId= | 할 일 조회 (mentionCount, 담당자 팀 포함) |
| PATCH | /api/tasks/{id} | 상태 변경 (TODO/DONE) |
| POST | /api/meetings | 회의록으로 에이전트 실행 |
| GET | /api/meetings/{id} | 처리 상태·결과 조회 |
| GET | /api/meetings/{id}/logs | 도구 호출 기록 (callOrder 순) |
| GET | /api/dev/llm-ping | OpenAI 연결 확인 (local & openai & !test 전용) |
| GET | /actuator/health | 헬스 체크 |

회의 응답 주요 필드: meetingId, duplicateOfMeetingId, status, summary, tasks[taskId, title, assigneeName, assigneeTeam, dueDate, resultType, mentionCount, warning], unassignedCount, processingTimeMs, llmCallCount, toolCallCount, validationFailureCount

## 9. 설계 포인트

- 알림은 Notifier 인터페이스로 추상화하고 SlackNotifier를 구현체로 둔다. 슬랙 전송 실패는 회의 처리 결과를 실패시키지 않고 기록만 남긴다.
- 입력 채널(API, 슬랙 텍스트, 슬랙 녹음 파일)이 달라도 이후 처리 흐름(에이전트 → 도구 → 알림)은 하나로 공유한다.
- 슬랙 입력은 즉시 접수 응답 후 비동기로 처리하고 완료 시 스레드에 답장한다.
- 녹음 파일은 봇 토큰으로 다운로드 → OpenAI 음성 인식 → 텍스트로 변환 후 같은 흐름에 넣는다.
- Kubernetes에서 replicas를 2 이상으로 늘렸을 때 생기는 문제(스케줄러 중복 실행, H2 파일 DB 공유 불가 등)는 직접 재현하고 원인을 확인한 뒤 해결한다.
- 로컬 개발 데이터는 백필하지 않고 `data/` 초기화로 처리한다. 운영 DB 전환 시에는 마이그레이션 도구(Flyway 등)를 쓴다.

## 10. 알려진 한계 (해결 전까지 README·DEVLOG에 유지)

- 같은 업무를 일부러 다시 맡긴 경우에도 미완료 Task가 재사용된다 (오탐을 감수한 선택).
- 서로 다른 회의, 또는 직접 API의 동시 요청이 같은 할 일을 등록하면 둘 다 들어갈 수 있다 (DB 제약 없이 서비스 검사만 함).
- "...#" 같은 재전송과 진짜 다음 정기 회의를 할 일 묶음만으로는 구분하지 못한다. 둘 다 duplicate로 표시되어 mentionCount가 늘지 않는다. (후보 개선: 원본 회의와의 시간 차이 기준)

---

## 11. 측정 지표

- 회의 1건 처리 전체 시간, LLM 호출 횟수, 도구 호출 횟수, 도구별 소요 시간
- 잘못된 도구 호출 비율 (검증 실패 수 / 전체 호출 수)
- 음성 인식 시간, 슬랙 접수 응답 시간 (확장 단계)
- 실제 실행값은 내가 DEVLOG 표에 직접 채운다. 비어 있는 칸을 추정값으로 채우지 않는다.

## 12. 개선 실험 (직접 수행하고 실제 수치만 기록)

- 도구 설명·결과 메시지·입력 구조 개선 전후 → 잘못된 도구 호출 비율, LLM 호출 수, 결과 정확도
    - 진행 중: 동명이인("백엔드팀 지영") 처리. 개선 전: 담당자 미정(오답), llmCallCount 4, toolCallCount 3, validationFailureCount 1
- 중복 확인을 LLM에 맡김 vs 서버에서 처리 → LLM 호출 횟수, 처리 시간
- 동기 vs 비동기 처리 → 응답 시간
- 모델 비교 → 정확도, 시간, 비용

---

## 13. 테스트 원칙

- 실제 OpenAI는 테스트에서 호출하지 않는다. mock ChatModel로 모델 응답(도구 호출 요청, 최종 답변)을 시나리오별로 고정한다.
- mock 모델은 기대한 정상 응답만이 아니라, 실제 실행 로그에서 관찰한 비정상 입력(모순된 인자, 빈 문자열 id, 여분 필드, 잘못된 날짜)도 재현한다.
- 실제 실행에서 버그를 발견하면 그 로그와 똑같은 입력으로 재현 테스트를 먼저 만들고, 수정 전 실패를 확인한 뒤 고친다.
- 날짜가 걸린 테스트는 고정된 Clock(Asia/Seoul)을 쓴다.
- 커밋 전에는 일부 테스트가 아니라 전체를 돌린다: `./gradlew test bootJar --no-daemon`
- 테스트 통과는 "설정과 로직이 기대대로 동작한다"는 근거일 뿐, 실제 모델 동작을 보장하지 않는다. 실제 동작은 내가 local,openai로 직접 확인한다.

## 14. DEVLOG 형식

```
## 날짜 — 제목
- 증상:
- 가설:
- 확인:
- 원인:
- 해결 → 결과: (실제 테스트·실행 결과만)
```
- 개발 환경 문제(샌드박스, 버전 등)는 서비스 버그 해결 경험과 구분해서 기록한다.
- 판단이 바뀐 경우(예: 삭제 방식 → 표시 방식)는 처음 판단, 바꾼 이유, 최종 판단을 남긴다.

---

## 15. 진행 순서와 상태

| 순서 | 작업 | 상태 |
| --- | --- | --- |
| 1 | 프로젝트 세팅, OpenAI 연결 확인 | 완료 |
| 2 | Task·Member 모델, 할 일 API | 완료 |
| 3 | 도구 호출 에이전트, Meeting, ToolCallLog | 완료 |
| 3+ | 회의 간 중복 재사용, duplicateOfMeeting 표시, TaskMention·mentionCount, 요청 필드 엄격 검증, 직접 등록 409, 동명이인 | 완료 |
| 3++ | 동명이인 도구 입력 구조 개선 (findMember team, 중립 메시지, 모순 불가 입력) + 개선 전후 실측 | 진행 중 |
| 4 | 슬랙 웹훅 알림 (Block Kit, 요약 2~3줄, N번째 언급 표시) | 예정 |
| 5 | 매일 아침 마감 알림 워크플로 | 예정 |
| 6 | 테스트 보강 | 계속 |
| 7 | Docker Compose + PostgreSQL | 예정 |
| 8 | 로컬 Kubernetes 배포 + 다중 인스턴스 문제 재현·해결 | 예정 |
| 9 | 확장 1: 슬랙 봇에게 회의록 텍스트 → 스레드 답장 | 예정 |
| 10 | 확장 2: 슬랙 봇에게 녹음 파일 → 음성 인식 | 예정 |
| 11 | 확장 3: 슬랙 명령어·버튼, 사람 승인 단계 | 여유 시 |
| 12 | 개선 실험 정리, README·데모·포트폴리오 | 예정 |

## 16. 산출물

- GitHub 저장소 + README (아키텍처 그림, 데모, 문제→원인→개선 사례, 개선 전후 수치, 알려진 한계)
- DEVLOG.md
- 데모: Swagger에서 회의록 전송 → 슬랙 정리 메시지 → 다음 날 아침 알림 (확장 시: 슬랙 봇에게 녹음 전송 → 스레드 답장)