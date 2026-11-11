# MeetoDo 3단계 완료 정리

작성일: 2026-11-11

## 범위와 완료 상태

3단계 「도구 호출 에이전트 + ToolCallLog」의 3-1~3-5 구현·자동 검증·실행 안내를 정리한다. `notifySlack`은 4단계로 남겼다.

- 3-1 Meeting·ToolCallLog 모델과 Repository: 완료
- 3-2 세 도구, 입력 검증, 공통 기록 실행 경계: 완료
- 3-3 Spring AI 도구 호출 루프, 회의 처리 API, 중복 및 실패 처리: 완료
- 3-4 mock LLM 자동 검증과 수동 Swagger 시나리오: 완료
- 3-5 OpenAI 실제 실행 안내와 기준값 기록 양식: 완료. 실제 OpenAI 실행은 사용자가 수행할 예정

## 구현 구조와 선택

- `MeetingAgent`는 애플리케이션 서비스가 의존하는 경계다. Spring AI 구현은 `local & openai & !test` 프로필에서만 활성화했다. 기본·테스트 프로필에는 OpenAI ChatModel과 MeetingAgent가 없다. 비활성 프로필의 `POST /api/meetings`는 명시적인 404 `MEETING_AGENT_DISABLED`를 반환한다.
- Spring AI 자동 도구 루프 대신 `ToolCallingManager.executeToolCalls`를 직접 반복 호출한다. 각 응답의 도구 요청 수를 세어 20회 실행 상한을 지키고, 최대 LLM 호출 수는 21회다. 모델은 호출 요청만 고르며 서버가 등록된 도구를 실행하고 결과를 대화 기록에 돌려준다.
- Meeting 본문은 trim 및 줄바꿈 정규화 뒤 SHA-256으로 식별한다. DB unique 제약이 동시 중복 접수를 막는다. 이미 처리 중인 동일 본문 요청은 기존 Meeting을 202 PROCESSING으로 돌려준다. 완료/실패된 본문을 다시 요청하면 기존 결과를 반환한다.
- 같은 회의에서 Task 중복 기준은 Meeting ID + 대소문자 무시 제목 + 담당자 ID(null 허용)다. 다른 회의의 같은 제목은 별도 Task로 저장한다.
- API에는 DTO를 사용하고 엔티티를 직접 노출하지 않는다. 회의 본문은 20,000자까지 허용한다. 기본/test 프로필의 회의 POST는 실행되지 않는다.
- Task와 ToolCallLog 모두 Meeting을 JPA `ManyToOne`으로 참조하도록 정리했다. Task API DTO는 기존 호환성을 위해 `meetingId` 숫자 값을 반환한다. 두 엔티티 모두 Meeting FK를 갖도록 일관성을 맞추고 참조 무결성·회의별 조회를 활용한다.
- 도구별 클래스(`findMember`, `findTasks`, `createTask`)와 공통 `ToolExecutionService`를 분리했다. 예상 가능한 입력 오류는 모델이 읽을 결과 객체로 돌려주고, 공통 경계가 성공/실패·순번·시간·로그 마스킹을 담당한다. ToolCallLog의 arguments/result는 각각 최대 4,000자다. API 키 모양, Bearer/Authorization 값, 회의 원문 전체는 저장 전에 마스킹한다.
- 날짜 기준은 주입된 `Clock`의 Asia/Seoul 오늘 날짜다. `이번 주 안에`는 해당 주 일요일로 해석하도록 프롬프트에 명시했다. 날짜 출력은 서버에서 엄격한 `yyyy-MM-dd`로 검증한다. 과거 날짜 첫 호출은 오류이며, 같은 작업 재시도도 과거면 dueDate 없이 등록하고 경고한다.

## Spring AI 근거

Spring AI 1.1.8의 공식 [Tool Calling 문서](https://docs.spring.io/spring-ai/reference/1.1/api/tools.html)와 실제 1.1.8 라이브러리 소스의 `ToolCallingManager`, `DefaultToolCallingManager`, `ToolCallingChatOptions`, `ToolContext`, `ToolCallbacks`를 확인했다. `ToolContext`는 모델로 보내지 않는 서버 측 실행 문맥이고, 수동 호출 흐름은 `ToolCallingManager.executeToolCalls(prompt, response)`로 도구 실행 결과가 포함된 대화 기록을 얻는다. 테스트에서도 실제 ToolCallingManager와 mock ChatModel을 결합해 도구 결과 왕복을 검증했다.

## API와 직접 확인 절차

서버 실행:

```bash
export OPENAI_API_KEY="$(security find-generic-password -a "$USER" -s meetodo-openai -w)"
./gradlew bootRun --args='--spring.profiles.active=local,openai'
```

키체인에서 값을 읽어 환경변수로 전달하므로 키를 화면에 출력하지 않는다. 실제 OpenAI 연결은 자동 테스트에서 호출하지 않았다. Swagger는 `http://localhost:8080/swagger-ui/index.html`에서 연다.

1. `POST /api/meetings`에 회의록을 넣는다. 응답의 `meetingId`, `status`, `summary`, `tasks`, `unassignedCount`, `processingTimeMs`, `llmCallCount`, `toolCallCount`, `validationFailureCount`를 확인한다.
2. `GET /api/meetings/{meetingId}`로 완료 상태와 요약·작업을 다시 확인한다.
3. `GET /api/meetings/{meetingId}/logs`로 `callOrder`, `toolName`, `success`, `errorCode`, `latencyMs`를 확인한다.
4. Task 연결은 `GET /api/tasks`로 확인한다. 담당자 조회 API는 `GET /api/tasks?assignee=민수`다.

### 수동 실행용 회의록 예시

1. 담당자·마감일 명확:
   `민수는 로그인 API 오류 처리를 2026-11-20까지 수정한다. 지영은 결제 실패 재현 테스트를 2026-11-14까지 추가한다. 해찬은 배포 체크리스트를 2026-11-18까지 공유한다.`
2. 상대 날짜 혼합:
   `민수는 로그인 API 문서를 다음 주 금요일까지 갱신한다. 지영은 결제 테스트 결과를 이번 주 안에 공유한다. 해찬은 회의 요약을 내일까지 올린다.`
3. 없는 이름·모호한 표현:
   `외부 컨설턴트 준호는 로그 샘플을 다음 주까지 정리한다. 지영은 가능하면 결제 오류도 확인한다. 담당자는 아직 정하지 않은 채 배포 체크리스트를 다음 주 금요일 전으로 준비한다.`

예시 순서: Swagger에서 예시 1→2→3 순으로 각각 `POST /api/meetings`; 각 `meetingId`를 사용해 GET 결과와 logs를 확인한다. 예시 3은 미등록 담당자를 `null`로 처리하는지, 모호해서 결정되지 않은 작업을 모델이 무리하게 만들지 않는지 살핀다. 상대 날짜 결과는 `tasks[].dueDate`와 createTask 로그 arguments에서 확인한다.

## 개선 전 기준값 기록 양식

실제 실행값은 사용자가 채운다. 아직 OpenAI에서 측정하지 않은 수치는 비워 둔다.

| 회의록 종류 | 처리 시간(ms) | LLM 호출 횟수 | 도구 호출 횟수 | 검증 실패 수 | 결과가 맞았는지 | 메모 |
|---|---:|---:|---:|---:|---|---|
| 담당자·마감일 명확 |  |  |  |  |  |  |
| 상대 날짜 혼합 |  |  |  |  |  |  |
| 없는 이름·애매한 표현 |  |  |  |  |  |  |

## 자동 검증 결과

`./gradlew test bootJar --no-daemon` 성공. 64개 테스트, 실패 0, 오류 0, 건너뜀 0. 테스트는 mock ChatModel을 사용했고 실제 OpenAI 호출은 없었다. `git diff --check`도 오류 없이 완료했다.

통합 테스트 중 실제 발견한 지연 로딩 오류와 수정은 [DEVLOG.md](DEVLOG.md)에 증상→가설→확인→원인→해결 순서로 기록했다. 자동 테스트 상세와 8개 수동 시나리오의 결과 칸은 [testing-plan-3-4.md](testing-plan-3-4.md)를 참고한다.

## 다음 단계

4단계 Slack Block Kit 알림으로 진행한다. `notifySlack`은 현재 도구 목록·프롬프트·API에 포함하지 않았다.
