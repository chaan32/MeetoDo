# MeetoDo

회의록에서 할 일을 추출해 담당자·마감일과 함께 등록하고 Slack으로 알리는 백엔드 프로젝트입니다. 별도 프론트엔드 없이 Swagger로 입력하고 Slack으로 결과를 받는 서비스를 목표로 합니다.

현재는 **1단계: 프로젝트 설정**입니다. Spring Boot 실행, JPA 연결, Swagger, health, Spring AI 설정 및 mock 테스트를 준비했습니다. 실제 OpenAI 연결 확인과 Task API는 아직 남아 있습니다.

## 실행

Java 21이 필요합니다. Gradle은 Wrapper가 내려받으므로 별도 설치가 필요 없습니다.

```bash
./gradlew bootRun
```

- Swagger UI: http://localhost:8080/swagger-ui/index.html
- OpenAPI 문서: http://localhost:8080/v3/api-docs
- Health: http://localhost:8080/actuator/health

아직 업무 API가 없어 Swagger에 작업 목록이 비어 있는 것이 정상입니다. 기본 `local` 프로필은 `127.0.0.1`에서만 수신합니다.

```bash
./gradlew test
./gradlew bootJar
java -jar build/libs/meetodo-0.0.1-SNAPSHOT.jar
```

테스트 결과는 `build/reports/tests/test/index.html`에서 확인합니다.

## 설정 읽기

| 파일 | 역할 |
| --- | --- |
| `build.gradle` | Java 21, Web·JPA·Validation·Actuator·Spring AI·Swagger 의존성 및 JUnit 설정 |
| `settings.gradle` | 프로젝트 이름 `meetodo` 지정 |
| `gradlew`, `gradle/wrapper/` | Gradle 8.14.3으로 빌드 버전 통일 |
| `application.yml` | 앱 이름, 기본 프로필, AI 모델 기본 비활성화, health 노출 |
| `application-local.yml` | 로컬 H2 파일 DB와 개발용 스키마 갱신 |
| `application-openai.yml` | 환경변수에서 키·모델을 읽어 OpenAI Chat 활성화 |
| `application-test.yml` | 실제 로컬 데이터와 분리된 H2 메모리 DB |
| `.env.example` | 필요한 환경변수 이름 안내. 자동으로 로드되는 설정 파일은 아님 |
| `.gitignore` | 비밀값, 로컬 DB, 빌드 결과 제외 |

`ddl-auto: update`는 로컬 개발용입니다. 데이터는 `data/` 아래에 저장되므로 이 폴더를 지우면 로컬 데이터도 사라집니다. 테스트의 `create-drop`은 메모리 DB에만 적용됩니다. `open-in-view: false`는 HTTP 응답을 만드는 동안 지연 로딩으로 DB 쿼리가 추가 실행되는 것을 막고 트랜잭션 범위를 서비스에서 다루도록 합니다.

## OpenAI 설정과 연결 확인

키는 IDE 실행 설정의 환경변수나 로컬 셸에 넣습니다. 키를 채팅·소스·커밋에 남기지 마세요. `.env.example`은 안내용이며 Spring Boot가 `.env`를 자동으로 읽지는 않습니다.

```bash
# OPENAI_API_KEY를 IDE/셸 환경변수로 설정한 뒤 실행
export OPENAI_CHAT_MODEL=gpt-4o-mini
./gradlew bootRun --args='--spring.profiles.active=local,openai'
```

활성 프로필을 명시하면 기본 프로필은 적용되지 않으므로 `local,openai`를 함께 지정합니다. 모델은 계정에서 사용 가능한 것으로 바꿀 수 있습니다.

이 명령으로 서버가 뜨는 것은 **설정·빈 생성 확인**입니다. 실제 키 인증, 모델 접근 권한, 과금 상태는 요청을 보내야 확인할 수 있습니다. 현재 실제 요청을 보내는 API는 없으며 실제 연결 성공은 아직 검증하지 않았습니다.

연결 확인 학습 순서:

1. `ChatClientMockTests`에서 `ChatModel`과 `ChatClient`가 어떻게 연결되는지 읽습니다.
2. `OpenAiConfigurationTests`에서 `openai` 프로필과 `ChatClient.Builder` 주입을 확인합니다. 이 테스트는 실제 API를 호출하지 않습니다.
3. 로컬 전용 실행 코드에서 `ChatClient.Builder`로 클라이언트를 만들고 짧은 문장 하나를 요청해 봅니다. 실제 호출은 비용이 발생할 수 있습니다.
4. 성공 여부와 오류가 있다면 상태 코드만 기록합니다. 키와 요청 헤더는 기록하지 않습니다.

자동 테스트는 실제 OpenAI 요청을 보내지 않습니다. 모델 호출 예시는 mock으로 검증합니다.

## 다음 직접 구현할 부분

먼저 `Task` 등록·조회·상태 변경을 작은 단위로 만듭니다. 핵심 로직의 완성 코드는 대신 작성하지 않습니다.

1. AGENTS.md의 Task 필드를 보고 Java 타입을 정합니다. 특히 `dueDate`와 `createdAt`에 같은 타입이 적합한지 생각해 보세요.
2. 가장 작은 `Task` 엔티티와 `TaskStatus`부터 작성합니다. 담당자 미정은 어떤 값으로 표현할지 함께 정합니다.
3. 등록 한 건을 저장하고 다시 조회하는 흐름부터 확인합니다. 그다음 HTTP API와 상태 변경을 붙입니다.

에이전트·도구·중복 방지·Slack은 이후 순서대로 진행합니다. 첫 회의 처리부터 전체 시간, LLM 호출 횟수, 도구별 시간, 검증 실패 비율을 실제로 수집하고, 측정 전 수치는 기록하지 않습니다.

## 버전 근거

- Java 21 / Spring Boot 3.5.16 / Spring AI 1.1.8 / springdoc 2.8.17 / Gradle 8.14.3
- [Spring AI 공식 저장소의 Boot 호환성](https://github.com/spring-projects/spring-ai)
- [springdoc 공식 호환성 표](https://springdoc.org/#what-is-the-compatibility-matrix-of-springdoc-openapi-with-spring-boot)
- [Spring AI OpenAI 설정 문서](https://docs.spring.io/spring-ai/reference/api/chat/openai-chat.html) — 최신 문서는 버전이 다를 수 있으므로 프로젝트의 1.1.x API와 대조합니다.

진행 기준은 [AGENTS.md](AGENTS.md), 문제 해결 기록은 [DEVLOG.md](DEVLOG.md)에 둡니다.
