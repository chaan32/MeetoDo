# MeetoDo 두 번째 작업 정리

작성일: 2026-11-10

## 범위

이 문서는 [firstDone.md](firstDone.md) 작성 이후 진행한 작업을 정리한다. 초기 프로젝트 설정은 firstDone.md를 참고한다.

## 1. 이후 완료한 작업

### 개발 전용 OpenAI 연결 확인 API

`GET /api/dev/llm-ping`을 구현했다. 요청 파라미터 없이 고정 질문 `Reply with OK only.`를 OpenAI에 보내므로 연결을 직접 확인할 수 있다.

성공하면 모델의 응답과 서버에서 측정한 모델 호출 경과 시간(ms)을 반환한다.

```json
{
  "text": "OK",
  "elapsedMs": 842
}
```

실제 응답과 시간은 모델 및 네트워크에 따라 달라진다. 응답을 캐시하지 않으며, 요청마다 실제 API 비용이 발생할 수 있다. 실패하거나 빈 응답이면 공급자 예외 원문을 숨긴 일반 HTTP 502 오류를 반환한다.

### 프로필 제한

컨트롤러와 OpenAI 설정 모두 `local & openai & !test` 조건을 적용했다. `local`과 `openai`가 모두 활성화되고 `test`는 비활성화된 경우에만 엔드포인트와 OpenAI Chat 모델이 생성된다.

기본 실행, `local` 단독, `openai` 단독, 또는 `test`가 포함된 실행에서는 API가 Swagger에 나타나지 않고 직접 요청하면 404다. 프로필 배열 표기는 OR 조건이므로, AND 조건을 분명히 표현하는 프로필 표현식을 사용했다.

### 민감한 내용 로그 방지

- 컨트롤러는 사용자 요청이나 키를 로그에 남기지 않는다. 질문은 코드에 고정되어 있다.
- Spring AI 1.1.8 소스를 확인해 retry 처리기가 공급자 예외 원문을 기록하고, Chat 모델이 빈 응답 시 프롬프트를 경고에 포함하는 동작을 확인했다.
- 개발용 프로필에서 해당 로거를 끄고 자동 재시도를 1회로 설정했다.
- 실패 시 일반 502 오류만 반환한다.
- mock 기반 로그 캡처 테스트로 예외 원문이 응답·로그에 나오지 않는 것을 검증했다. 실제 키가 유출된 사고는 없었다.

## 2. 선택한 설계와 이유

컨트롤러의 `@Profile`과 YAML의 `spring.config.activate.on-profile`에 같은 `local & openai & !test` 조건을 적용했다. 컨트롤러 조건은 라우트를 제한하고, YAML 조건은 OpenAI 모델 설정이 다른 프로필에서 활성화되는 것을 제한한다. 두 조건을 함께 두어 요구된 개발 환경에서만 연결 확인 기능을 사용할 수 있게 했다.

## 3. 작업 후 검증

| 검증 | 결과 |
| --- | --- |
| 전체 자동 테스트 | 16개 통과, 실패·오류·건너뜀 0개 |
| 빌드 | `./gradlew test bootJar --no-daemon` 성공 |
| 프로필 | 비활성 프로필 7개 조합에서 모델·라우트 없음, 요청 404, Swagger 경로 없음 |
| 성공 응답 | mock 응답 텍스트, 경과 시간, 고정 질문, `Cache-Control: no-store` 확인 |
| 실패 응답 | 일반 502 및 공급자 예외 원문 비노출 확인 |
| 실제 OpenAI API 요청 | 미실행 — 사용자가 직접 확인할 단계 |

독립 파일 리뷰에서도 구체적인 수정 필요 사항은 발견되지 않았다. 테스트는 OpenAI 모델을 mock으로 대체하므로 실제 키 인증·모델 접근 성공을 입증하지 않는다.

## 4. 지금 할 일: 실제 OpenAI 연결 확인

프로젝트 폴더에서 macOS zsh를 사용해 실행한다. 키는 입력 중 화면에 표시되지 않는다.

```zsh
read -rs 'OPENAI_API_KEY?OpenAI API key: '
export OPENAI_API_KEY
echo
export OPENAI_CHAT_MODEL=gpt-4o-mini
./gradlew bootRun --args='--spring.profiles.active=local,openai'
```

서버가 켜진 뒤 Swagger UI에서 `GET /api/dev/llm-ping`을 골라 **Try it out → Execute**를 누른다.

- Swagger: http://localhost:8080/swagger-ui/index.html
- 기대 결과: HTTP 200, `text`, 숫자형 `elapsedMs`.
- curl 확인: `curl -i http://localhost:8080/api/dev/llm-ping`
- 실패 시: 응답은 일반 502 문구만 보여 준다. 환경변수 설정, 모델명·접근 권한, 네트워크를 확인한다. API 키나 인증 헤더를 로그에 남기지 않는다.
- 호출에는 비용이 발생할 수 있다. 확인 후 서버를 Ctrl+C로 종료하고 필요하면 `unset OPENAI_API_KEY`를 실행한다.

실제 성공 여부와 관찰한 시간만 DEVLOG.md에 기록한다. 아직 얻지 않은 결과나 성능 수치는 기록하지 않는다.

## 5. 그다음: Task API

OpenAI 확인 후 AGENTS.md의 다음 단계로 진행한다.

1. Task와 상태(TODO/DONE)를 정의한다.
2. Member 및 담당자 미정 표현을 정하고 JPA 저장·조회 흐름을 만든다.
3. `POST /api/tasks` 등록 API를 만든다.
4. `GET /api/tasks?assignee=` 조회 API를 만든다.
5. `PATCH /api/tasks/{id}` 상태 변경 API를 만들고 결과를 재조회한다.
6. Swagger에서 등록 → 조회 → 변경 → 재조회 흐름을 확인한다.

## 6. 남은 프로젝트 순서

| 순서 | 작업 | 현재 상태 |
| --- | --- | --- |
| 1 | Spring Boot·JPA·Swagger·OpenAI 설정 | 설정 완료, 실제 OpenAI 연결은 사용자 확인 대기 |
| 2 | Task 등록·조회·상태 변경 | 다음 단계 |
| 3 | 도구 호출 에이전트와 ToolCallLog | 예정 |
| 4 | Slack Block Kit 알림 | 예정 |
| 5 | 매일 아침 마감 알림 | 예정 |
| 6 | 테스트 보강 | 예정 |
| 7 | Docker Compose | 예정 |
| 8 | 로컬 Kubernetes 및 다중 인스턴스 문제 재현·해결 | 예정 |
| 9 | Slack 텍스트 입력과 스레드 답장 | 확장 예정 |
| 10 | Slack 녹음 파일과 음성 인식 | 확장 예정 |
| 11 | Slack 명령·버튼·사람 승인 | 여유가 있으면 진행 |
| 12 | 실제 측정 기반 개선 실험·README·데모·포트폴리오 | 예정 |

에이전트 단계부터 회의 처리 시간, LLM 호출 횟수, 도구별 소요 시간, 잘못된 도구 호출 비율을 수집한다. 개선 전후 실험은 개선 전 기준값을 먼저 측정한다.

## 7. 다음 대화에서 이어가기

- 실제 OpenAI 확인은 사용자가 직접 진행한다. 확인 전에는 Task API 단계로 넘어가지 않는다.
- 변경된 AGENTS.md에 따라 작업 전에 구조와 선택 이유를 설명하고 코드를 작성한다.
- 문제를 만나면 증상 → 가설 → 확인 방법을 정리하고, 실제 원인과 결과를 DEVLOG.md에 기록한다.
- 발생하지 않은 문제나 측정하지 않은 수치를 만들어 기록하지 않는다.

관련 문서: [AGENTS.md](AGENTS.md) · [firstDone.md](firstDone.md) · [README.md](README.md) · [DEVLOG.md](DEVLOG.md)
