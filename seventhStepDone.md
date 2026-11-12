# 7단계 — Docker Compose + PostgreSQL

## 완료한 것

- docker 프로필은 PostgreSQL + Flyway V1 + ddl-auto=validate. local H2 파일·test H2 메모리는 기존 정책 유지, 기본 Flyway 비활성.
- V1: members/meetings/tasks/tool_call_logs/task_mentions/daily_digests의 현재 컬럼·PK·6개 FK·로그 조회 인덱스. 회의 해시, TaskMention(task_id,meeting_id), DailyDigest.digest_date 유니크 제약을 실제 DB에서 검증했다. Task 제목 유니크 제약은 추가하지 않았다(기존 동시 Task 등록 한계 유지).
- Gradle Wrapper 빌드 → JRE21 멀티 스테이지, 런타임 UID/GID10001, /actuator/health 헬스체크. 이미지 빌드는 bootJar만 수행하고 전체 테스트는 호스트에서 별도로 실행한다.
- PostgreSQL named volume, DB healthy 이후 앱 시작. app 8080, replica 프로필 app2 8081. localhost에만 포트 공개하고 DB 포트는 호스트에 공개하지 않는다.
- 비밀은 환경변수, .env는 gitignore, .dockerignore는 빌드 입력 허용 목록. .env/data/Git/에이전트 파일은 이미지 컨텍스트 제외.
- Docker는 기본 OpenAI 비활성. 명시적 docker,openai에서만 에이전트 활성화. test는 실제 AI·Notifier를 제외한다. local용 llm-ping은 Docker에서 노출하지 않는다.
- Docker 수동 digest API는 MEETODO_DEV_ENDPOINTS_ENABLED=true일 때만 활성화. 기본 false, local은 기존 동작 그대로.

## 실제 검증

전체 `./gradlew test bootJar --no-daemon` 성공: **301개, 실패·오류·건너뜀 0**. 실제 PostgreSQL 13개와 Docker 프로필 조건 9개를 포함한다. 최종 재확인 명령도 BUILD SUCCESSFUL이었다. PostgreSQL Testcontainers를 사용할 수 있었고 Docker가 없으면 조용히 건너뛰지 않도록 했다.

실패 재현·수정:
1. V1 이전 PostgreSQL 시작에서 Hibernate validate가 daily_digests 누락으로 실패했다.
2. V1을 기존 @Lob/OID 매핑대로 작성하자 스키마 검증은 통과했지만 커밋 후 findDigestTasks의 JSON 읽기에서 autocommit Large Object 오류가 났다(13개 중12통과/1실패).
3. Task의 JSON을 일반 text로 바꿔 수정했다. JSON 변환기는 유지했고 PostgreSQL 테스트13개가 모두 통과했다.
4. Docker 데모 플래그/Notifier 조건 테스트의 수정 전2실패를 확인한 뒤 조건을 연결했다.

실제 Docker image build 성공. 별도 meetodo-verification 프로젝트에서 PostgreSQL+앱2개를 실행했다. 두 앱 UID/GID10001:10001과 healthy를 확인했고, 동시 digest 요청은 SENT/ALREADY_SENT 각1개, 외부 Slack 대신 로컬 mock 수신1회였다. 앱2에서 앱1이 만든 Task도 조회했다. 사용자 비밀값·실제 메신저·OpenAI는 이 검증에 사용하지 않았다.

검증용 PostgreSQL 컨테이너를 제거한 뒤 같은 named volume으로 다시 생성해 Task1건과 기존 DailyDigest SENT 기록이 남는 것도 확인했다.

사용자의 H2 data/와 기존 DB 볼륨은 수정·삭제하지 않았다. 검증 컨테이너는 종료하고 검증용 named volume은 남겼다.

## 변경 파일

- Dockerfile, .dockerignore, docker-compose.yml
- build.gradle: Boot관리 Flyway11.7.2, Testcontainers1.21.4 의존성
- src/main/resources/application.yml, application-docker.yml, application-openai.yml
- src/main/resources/db/migration/V1__initial_schema.sql
- src/main/java/com/meetodo/config/DevEndpointsCondition.java
- src/main/java/com/meetodo/digest/DailyDigestController.java
- src/main/java/com/meetodo/notification/NotificationConfiguration.java
- src/main/java/com/meetodo/meeting/SpringAiMeetingAgent.java
- src/main/java/com/meetodo/task/Task.java (JSON @Lob→text)
- src/test/java/com/meetodo/persistence/PostgresMigrationTests.java (13개)
- src/test/java/com/meetodo/config/DockerProfileConditionsTests.java (9개)
- scripts/docker-demo-members.sql, .env.example, README.md, DEVLOG.md, 이 문서
- docs/superpowers/plans/2026-11-12-docker-postgres.md

## 실행

Docker Desktop을 켠다. DB 암호를 최초1회 키체인에 저장한다. 기존 항목·볼륨이 있다면 기존 암호를 유지한다(아래 add 명령은 기존 항목을 덮어쓰지 않음).

```zsh
security add-generic-password -a "$USER" -s meetodo-postgres -w "$(openssl rand -hex 24)"
export POSTGRES_PASSWORD="$(security find-generic-password -a "$USER" -s meetodo-postgres -w)"
export OPENAI_API_KEY="$(security find-generic-password -a "$USER" -s meetodo-openai -w)"
export OPENAI_CHAT_MODEL=gpt-4o-mini
# SLACK_WEBHOOK_URL은 이미 실제 검증한 기존 환경변수를 유지한다.
export NOTIFIER_TYPE=slack
export SPRING_PROFILES_ACTIVE=docker
export MEETODO_DIGEST_ENABLED=false
export MEETODO_DEV_ENDPOINTS_ENABLED=true

docker compose build app
docker compose --profile replica up -d --wait
docker compose ps
docker compose logs -f app app2
```

Discord로 확인하려면:

```zsh
export DISCORD_WEBHOOK_URL="$(security find-generic-password -a "$USER" -s meetodo-discord-webhook -w)"
export NOTIFIER_TYPE=discord
docker compose --profile replica up -d --wait
```

OpenAI 회의 처리도 확인할 때만:

```zsh
export SPRING_PROFILES_ACTIVE=docker,openai
docker compose --profile replica up -d --wait
```

local 프로필을 Docker에 함께 켜지 않는다. Docker 기본 모드는 OpenAI 키 없이 Task·다이제스트 확인이 가능하다. 현재 비밀값을 출력하는 docker compose config 대신 구문만 확인하려면 `docker compose config --quiet`를 사용한다.

포트가 사용 중이면 APP_PORT/APP2_PORT 환경변수를 바꾼다. Swagger: http://localhost:8080/swagger-ui/index.html, http://localhost:8081/swagger-ui/index.html.

## Swagger·데이터 확인

Docker DB는 새 저장소이며 H2 데이터를 자동 복사하지 않는다. 필요하면 명시적 개발 샘플만 넣는다.

```zsh
docker compose exec -T postgres sh -c 'psql -U "$POSTGRES_USER" -d "$POSTGRES_DB"' < scripts/docker-demo-members.sql
```

POST /api/tasks:
```json
{"title":"Docker 공유 DB 확인","assigneeName":"김민수","dueDate":"2099-01-01"}
```

다른 앱의 GET /api/tasks에서도 같은 Task를 확인한다. 팀원 없이 먼저 확인할 때는 assigneeName을 생략한다. POST /api/meetings는 docker 기본 모드에서 404 MEETING_AGENT_DISABLED이며 docker,openai에서만 실제 모델을 사용한다. 이미 사용자 확인한 회의록을 넣어 전체 이름·미정 경고·logs를 확인한다. 이 실제 모델 호출은 사용자가 직접 한다.

데모 API를 끄려면 MEETODO_DEV_ENDPOINTS_ENABLED=false로 바꾸고 앱을 재생성한다. Docker의 /api/dev/digest/run이404인지 확인한다. Task·회의 일반 API는 유지한다.

## 두 인스턴스 다이제스트 동시 확인

해당 날짜 기록이 아직 없어야 한다. force=true는 중복을 허용하므로 동시 선점 검증에 사용하지 않는다. 위 Task를 만들었고 기준일2099-01-01이 새 날짜라면:

```zsh
curl -sS -X POST 'http://localhost:8080/api/dev/digest/run?date=2099-01-01' > /tmp/meetodo-app1-digest.json &
curl -sS -X POST 'http://localhost:8081/api/dev/digest/run?date=2099-01-01' > /tmp/meetodo-app2-digest.json &
wait
cat /tmp/meetodo-app1-digest.json /tmp/meetodo-app2-digest.json
```

기대: 하나 SENT, 다른 하나 ALREADY_SENT, 채널 메시지1건. URL/타입 설정이 없으면 승자는SKIPPED, 나머지는ALREADY_SENT라 실제 발송1회를 검증한 것은 아니다. 이미 날짜 기록이 있으면 둘 다ALREADY_SENT일 수 있다. 새 날짜와 그에 맞는 Task로 다시 확인한다.

DB 기록도 확인한다.

```zsh
docker compose exec -T postgres sh -c 'psql -U "$POSTGRES_USER" -d "$POSTGRES_DB"' <<'SQL'
SELECT digest_date, status, overdue_count, today_count, upcoming_count FROM daily_digests WHERE digest_date = '2099-01-01';
SELECT count(*) FROM daily_digests WHERE digest_date = '2099-01-01';
SELECT version, success FROM flyway_schema_history ORDER BY installed_rank;
SQL
```

날짜 행1건, 정상 웹훅이면SENT, Flyway V1 success=true. 단, ALREADY_SENT는 선점됐다는 뜻으로 상대 앱의 발송 완료까지 보장하지 않는다. 두 요청 완료 후 상태·채널을 확인한다.

## 종료·초기화

```zsh
# 데이터 유지 종료
docker compose --profile replica down
# 재기동 후 Task와 DailyDigest 기록이 남는지 확인
docker compose --profile replica up -d --wait
```

사용자가 DB를 완전히 초기화할 때만 아래를 실행한다. 해당 Compose 프로젝트의 PostgreSQL 데이터가 삭제된다.

```zsh
docker compose --profile replica down -v
```

H2 data/와 PostgreSQL named volume은 별개다. POSTGRES_PASSWORD만 바꿔도 기존 볼륨 DB의 암호는 바뀌지 않는다. Flyway가 이미 적용된 V1을 수정하지 말고 다음 변경은 V2로 추가한다.

전체 자동 검증:
```zsh
./gradlew test bootJar --no-daemon
```

Docker Desktop이 필요하다. Testcontainers는 실제 임시 PostgreSQL만 호출하며 OpenAI·메신저는 mock이다.

## 판단·한계·면접

사용자가 보고한 전날 H2 파일 잠금은 파일 DB를 여러 프로세스에서 여는 제약을 실제로 겪은 배경이다. 이번에 그 과거 오류 로그를 다시 확인하거나 재현하지는 않았다. 공유 서버 DB인 PostgreSQL로 바꿔 두 앱의 동시 선점을 실제 확인했다.

- H2→PostgreSQL의 기존 데이터 복사와 기존 PG OID 데이터 변환은 구현하지 않았다.
- Task 동시 등록의 서비스 검사 경쟁은 기존대로 남는다. 이번 DB 유니크 선점은 DailyDigest 대상이다.
- 정확히 한 번 전달/Outbox/자동 재시도는 보류한다. force=true의 중복 허용은 유지한다.
- Kubernetes 배포는 이번 범위가 아니다. Docker demo flag는 기본 꺼짐이며 운영 공개 전에 인증·네트워크 정책을 별도로 정해야 한다.

면접 예상 질문:
1. 왜 H2 대신 PostgreSQL인가요?
   - 파일 잠금으로 로컬 DB를 여러 앱이 함께 쓰기 어렵다. 서버 DB를 공유하면 두 앱이 같은 데이터·유니크 제약으로 동시 요청을 조정할 수 있다.
2. 왜 ddl-auto=update 대신 Flyway+validate인가요?
   - 실행마다 자동 변경하는 대신 스키마 변경을 버전으로 검토하고, 앱 매핑과 실제 스키마가 다르면 시작 단계에서 실패하게 한다. 다만 Hibernate validate만으로 유니크/FK를 모두 검증하지 않으므로 실제 SQL 제약 테스트도 쓴다.
3. H2 테스트가 통과했는데 왜 추가 테스트가 필요한가요?
   - DB마다 타입·트랜잭션 동작이 다르다. 실제 PG에서 @Lob/OID의 autocommit 조회 오류를 발견했고 text로 고쳐 재현 테스트와 H2 회귀를 함께 통과시켰다.

공식 근거: [Compose health 기반 시작 순서](https://docs.docker.com/compose/how-tos/startup-order/), [Testcontainers PostgreSQL](https://java.testcontainers.org/modules/databases/postgres/), [Flyway PostgreSQL 모듈](https://documentation.red-gate.com/flyway/reference/database-driver-reference/postgresql-database), [PG JDBC Large Objects](https://jdbc.postgresql.org/documentation/binary-data/).
