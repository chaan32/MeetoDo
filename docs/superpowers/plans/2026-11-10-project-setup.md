# MeetoDo Project Setup Implementation Plan

> **For agentic workers:** Use superpowers:executing-plans for this small setup. Respect AGENTS.md: the user implements core logic.

**Goal:** Java 21에서 실행 가능한 Spring Boot 프로젝트와 학습 시작점을 준비한다.

**Architecture:** 단일 Spring Boot 애플리케이션. 로컬은 H2 파일 DB, 테스트는 H2 메모리 DB를 사용한다. OpenAI 자동 설정은 별도 프로필로 켠다.

**Tech Stack:** Java 21, Gradle 8.14.3, Spring Boot 3.5.16, Spring AI 1.1.8, springdoc 2.8.17.

**Spec:** [AGENTS.md](../../../AGENTS.md), 특히 0절과 12절의 첫 단계.

## Global Constraints

- 핵심 로직은 사용자가 작성한다. 에이전트, 도구, 중복 방지, 날짜·담당자 처리 등은 구현하지 않는다.
- 비밀값은 환경변수로 받으며 저장소에 넣지 않는다.
- 테스트에서 실제 LLM을 호출하지 않는다.
- 현재 디렉터리는 Git 저장소가 아닌 새 프로젝트 폴더다. 이번 작업은 커밋·푸시하지 않는다.

## Choices

- H2 파일 DB를 선택한다. PostgreSQL은 Compose 단계에서 연결한다. 파일 DB는 재시작 후 데이터가 남고, 메모리 DB는 테스트 격리에 사용한다.
- 기본 AI 모델은 비활성화한다. `openai` 프로필에서만 Chat 모델을 활성화해 키 없이 초기 개발이 가능하게 한다.
- 버전은 공식 호환성 문서와 Maven Central에서 확인한 3.5/1.1/2.8 계열로 고정한다.
- AGENTS.md에서 설정 파일과 테스트 뼈대 작성을 허용했으므로 이 범위에서 바로 진행한다.

## Review Focus

- API 키 없는 기본 실행: 컨텍스트와 health가 정상인지 확인.
- 테스트가 로컬 파일 DB를 건드리지 않는지 확인.
- Swagger UI와 OpenAPI JSON이 제공되는지 확인.
- OpenAI 프로필에서 ChatClient 구성이 가능하고 호출은 mock으로 대체되는지 확인.
- 키·로컬 DB·빌드 결과물이 Git 제외 대상인지 확인.

## Task 1: 실행 가능한 설정과 검증

**Files:** `build.gradle`, `settings.gradle`, Gradle Wrapper, `.gitignore`, `.env.example`, `src/main/java/com/meetodo/MeetoDoApplication.java`, `src/main/resources/application*.yml`, `src/test/java/com/meetodo/*Tests.java`, `src/test/resources/application-test.yml`, `README.md`, `DEVLOG.md`.

**Interfaces:** 기본 HTTP 서버 8080, `/actuator/health`, `/v3/api-docs`, `/swagger-ui/index.html`. Task API는 다음 학습 단계다.

- [x] 의존성, 실행 진입점, 기본·로컬·OpenAI 프로필을 작성한다.
- [x] health, OpenAPI, Swagger, DB 연결을 확인하는 통합 테스트와 mock 기반 ChatClient 테스트를 작성한다.
- [x] `./gradlew test bootJar`를 실행하고 실패 여부를 확인한다.
- [x] 실제 서버를 띄워 health와 Swagger 응답을 확인한 후 검증용 서버를 종료한다.
- [x] README에 실행 방법, 설정 역할, 실제 OpenAI 연결 미검증 여부, 다음 학습 과제를 기록한다.

실제 OpenAI 연결 확인은 키와 모델 접근 권한이 필요한 별도 단계다. mock 테스트의 성공으로 실제 연결 성공을 주장하지 않는다.

## Verification — 2026-11-10

- `./gradlew test bootJar --no-daemon`: BUILD SUCCESSFUL. 테스트 7개, 실패·오류·건너뜀 0개.
- Java 21로 패키징된 jar 실행, 기본 local 프로필 및 H2 파일 DB 연결 확인.
- 검증용 18080 포트에서 health `UP`, OpenAPI 3.1.0 문서, Swagger HTTP 200 확인. 검증 서버는 정상 종료.
- 기본 샌드박스는 포트 바인딩을 차단하므로 승인 후 제한 밖에서 검증했다.
- 독립 리뷰: 구체적 버그나 중대한 우려 없음. 실제 OpenAI 호출은 미실행.
