# MeetoDo 첫 작업 정리

작성일: 2026-11-10

## 1. 현재 상태

**Spring Boot 프로젝트를 실행하고 개발을 시작할 기반을 준비했다.**

AGENTS.md의 진행 순서 중 **1단계 프로젝트 설정을 진행한 상태**다. 서버·DB·Swagger·테스트 환경은 확인했고, 실제 OpenAI 요청과 응답 확인은 남아 있다. 회의록을 처리하거나 할 일을 저장하는 업무 기능은 아직 구현하지 않았다.

## 2. 지금까지 완료한 것

### 프로젝트와 의존성

- Java 21 기반 Spring Boot 실행 진입점 작성.
- Gradle Wrapper 구성: 별도 Gradle 설치 없이 동일 버전으로 빌드 가능.
- Web, JPA, Validation, Actuator, Spring AI, Swagger 의존성 추가.
- H2와 PostgreSQL 드라이버 추가. 실제 연결을 확인한 DB는 H2다.

| 구성 | 버전 |
| --- | --- |
| Java | 21 |
| Gradle Wrapper | 8.14.3 |
| Spring Boot | 3.5.16 |
| Spring AI | 1.1.8 |
| springdoc | 2.8.17 |

### 실행 환경 분리

| 파일 | 설정한 내용과 이유 |
| --- | --- |
| `application.yml` | 기본 프로필을 `local`로 지정하고 AI 모델을 비활성화해 키 없이 개발 가능하게 함. Actuator는 health만 노출 |
| `application-local.yml` | 로컬 H2 파일 DB 사용. 재시작 후에도 데이터를 보관하며 서버는 `127.0.0.1`에서 수신 |
| `application-openai.yml` | 환경변수의 키·모델을 사용해 OpenAI Chat 활성화 |
| `application-test.yml` | H2 메모리 DB 사용. 테스트가 로컬 파일 DB를 변경하지 않도록 분리 |

- 로컬 DB의 `ddl-auto: update`는 개발 중 엔티티 변경을 스키마에 반영하기 위한 설정이다.
- 테스트의 `ddl-auto: create-drop`은 테스트용 스키마를 생성하고 종료 시 제거한다.
- `open-in-view: false`로 HTTP 응답 처리까지 JPA 영속성 컨텍스트를 열어 두는 기본 동작을 끈다.

### 비밀값과 문서

- `.gitignore`: 환경변수 파일, 로컬 DB, 빌드 결과물 제외 규칙 작성.
- `.env.example`: 필요한 환경변수 이름 안내. 실제 키는 작성하지 않음.
- `README.md`: 실행 방법, 설정 역할, 다음 학습 순서 기록.
- `DEVLOG.md`: 문제 해결 기록 양식과 개발 환경 문제 기록.
- 프로젝트 초기 설정 과정에서 Git 초기화·커밋·푸시는 수행하지 않음.

## 3. 확인한 결과와 한계

초기 설정 작업에서 다음을 검증했다. 이 문서 작성 과정에서는 테스트를 다시 실행하지 않았다.

| 검증 | 결과 |
| --- | --- |
| `./gradlew test bootJar --no-daemon` | 빌드 성공, 실행 가능한 jar 생성 |
| `ApplicationSmokeTests` | 5개 통과: AI 모델 없이 시작, health, OpenAPI, Swagger, 테스트 DB 연결 |
| `OpenAiConfigurationTests` | 1개 통과: OpenAI 모델과 ChatClient 설정 확인. 실제 요청은 보내지 않음 |
| `ChatClientMockTests` | 1개 통과: mock 모델의 응답을 ChatClient로 받는 흐름 확인 |
| 전체 자동 테스트 | 총 7개 통과, 실패·오류·건너뜀 0개 |
| 실제 jar 실행 | Java 21, 기본 local 프로필, H2 파일 DB 연결 확인 |
| 실제 HTTP 요청 | health `UP`, OpenAPI JSON 응답, Swagger HTTP 200 확인 |

실제 HTTP 검증은 임시로 18080 포트를 사용했으며, 검증용 서버는 종료했다. 평소 실행 포트는 8080이다.

**아직 확인하지 않은 것:**

- 실제 OpenAI 키 인증, 모델 접근 권한, 요청·응답 성공 여부.
- PostgreSQL 연결과 데이터 저장.
- Task·Member·Meeting·ToolCallLog 엔티티 및 업무 API 동작.
- 회의 처리 시간, LLM 호출 횟수, 도구별 시간, 검증 실패 비율 등 성능·품질 지표.

Swagger 화면은 열리지만 업무 API가 없어 작업 목록은 비어 있다. 테스트 통과는 현재 설정이 동작한다는 근거이며, 에이전트 기능 완성을 의미하지 않는다.

## 4. 작업 중 겪은 문제

1. **증상:** Codex 샌드박스에서 Maven 주소 조회, Gradle 초기화, 서버 포트 바인딩이 실패했다.
2. **가설:** 애플리케이션 코드보다 실행 환경의 네트워크·파일·소켓 제한이 원인일 수 있다.
3. **확인:** 동일 명령과 jar를 승인된 환경에서 다시 실행했다.
4. **원인:** 제한된 환경에서만 실패했으며, 포트 바인딩에서는 `Operation not permitted`를 확인했다. DNS·native library 오류의 세부 내부 원인은 조사하지 않았다.
5. **해결 → 결과:** 승인된 환경에서 버전 조회, 빌드·테스트, 실제 HTTP 응답 검증을 완료했다.

이 내용은 개발 환경 문제다. 면접에서 서비스 로직의 버그를 해결한 경험으로 확대해 설명하지 않는다.

## 5. 다음에 바로 할 것

### 먼저: 기존 설정을 직접 실행하고 이해하기

- [ ] `./gradlew bootRun`으로 서버를 실행한다.
- [ ] Swagger와 health 주소에 접속한다.
- [ ] `./gradlew test`를 실행하고 테스트 세 클래스의 역할을 읽는다.
- [ ] local은 파일 DB, test는 메모리 DB를 사용하는 이유를 설명해 본다.

```bash
./gradlew bootRun
```

- Swagger: http://localhost:8080/swagger-ui/index.html
- Health: http://localhost:8080/actuator/health

### 1단계에서 남은 것: 실제 OpenAI 연결 확인

- [ ] `OPENAI_API_KEY`를 로컬 셸 또는 IDE 환경변수로 설정한다.
- [ ] `local,openai` 프로필로 실행해 설정이 로드되는지 확인한다.
- [ ] 로컬 전용 연결 확인 코드에서 짧은 요청 하나를 보내고 응답을 확인한다.
- [ ] 성공 여부 또는 실패 상태 코드를 DEVLOG에 기록한다. 자동 테스트는 계속 mock을 사용한다.

```bash
./gradlew bootRun --args='--spring.profiles.active=local,openai'
```

현재는 실제 요청을 보내는 API가 없으므로, 이 명령으로 서버가 켜지는 것만으로 연결 확인이 끝나지는 않는다. 키를 채팅·소스·로그에 남기지 않고 실제 호출 비용을 확인한다. `.env` 파일은 자동으로 읽히지 않는다.

### 다음 구현 단계: 할 일 API

완성 코드를 받기보다 다음 순서로 직접 작성하고 리뷰받는다.

1. **Task 모델과 상태 정의:** AGENTS.md의 필드를 Java 타입으로 옮긴다. `dueDate`와 `createdAt`의 차이, 담당자 미정 표현을 먼저 생각한다.
2. **저장·조회 흐름:** Task 한 건을 저장하고 다시 읽는 가장 작은 흐름을 확인한다. 담당자 관계에 필요한 Member 모델도 이 단계에서 정한다.
3. **등록 API:** `POST /api/tasks`로 등록한 데이터가 저장되는지 확인한다.
4. **조회 API:** `GET /api/tasks?assignee=`로 담당자 조건에 맞는 결과가 나오는지 확인한다.
5. **상태 변경 API:** `PATCH /api/tasks/{id}`로 상태를 바꾸고 재조회해 반영 여부를 확인한다.
6. **검증과 기록:** 정상 흐름과 실제로 발견한 오류를 테스트로 확인하고, 문제·원인·판단 근거를 DEVLOG에 남긴다.

완료 기준은 **Swagger에서 등록 → 조회 → 상태 변경 → 재조회까지 직접 확인할 수 있는 것**이다.

## 6. 이후 진행 순서

아래는 AGENTS.md에 정한 순서이며 아직 구현하지 않았다.

| 순서 | 작업 | 확인할 결과 |
| --- | --- | --- |
| 3 | 도구 호출 에이전트 + ToolCallLog | 도구 선택·서버 실행·결과 반환 흐름과 호출 기록 확인 |
| 4 | Slack 웹훅 알림 | Block Kit으로 할 일·담당자·마감일 표시 |
| 5 | 매일 아침 워크플로 | 마감 임박·지연 할 일을 담당자별로 알림 |
| 6 | 테스트 보강 | 업무 로직을 검증하고 LLM 호출은 mock 처리 |
| 7 | Docker Compose | 애플리케이션과 PostgreSQL 실행 |
| 8 | 로컬 Kubernetes | 배포 및 다중 인스턴스 문제 재현·원인 확인·해결 |
| 9 | Slack 텍스트 입력 | 즉시 접수 응답 후 같은 스레드에 완료 결과 전달 |
| 10 | Slack 녹음 입력 | 음성을 텍스트로 변환해 기존 처리 흐름에 연결 |
| 11 | 선택 확장 | 명령어·버튼·사람 승인 단계 |
| 12 | 개선 실험·포트폴리오 | 실제 측정값과 문제 해결 경험으로 README·데모 정리 |

에이전트를 구현할 때부터 회의 전체 처리 시간, LLM 호출 횟수, 도구별 시간, 검증 실패 비율을 수집한다. 개선 실험은 **개선 전 측정값을 확보한 뒤** 진행한다.

## 7. 이어서 작업할 때의 원칙

- 핵심 로직은 개발자가 직접 작성한다. AI는 힌트와 코드 리뷰를 제공하며, “답 줘”라고 요청했을 때 정답 코드를 제공한다.
- 설정·DTO·테스트 뼈대 등 보일러플레이트는 AI가 작성할 수 있고 역할을 함께 설명한다.
- 문제가 생기면 바로 수정하기 전에 **증상 → 가설 → 확인 방법**을 정리한다.
- API 사용법이나 버전이 불확실하면 공식 문서로 확인한다.
- 기능을 미리 모두 구현하지 않고, 작은 단위로 실행·검증·기록한다.

관련 문서: [AGENTS.md](AGENTS.md) · [README.md](README.md) · [DEVLOG.md](DEVLOG.md)
