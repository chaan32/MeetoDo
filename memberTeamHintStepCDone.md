# 단계 C — 후보 팀 언급 여부 안내

## 설계와 변경

A는 정확도를 지켰지만 일부 회차에 검증 실패·추가 호출이 남았다. 메시지 변경과 구조화 필드 추가를 비교해, 실험 변수를 줄이고 후보 JSON을 보존하는 메시지 전용 변경안을 선택했다.

팀 없는 findMember(name)가 다중 후보이면 기존 TeamNames.normalize/mentionedIn을 사용한다. 후보 인원 수가 아닌 정규화된 서로 다른 팀 수를 계산하고 원래 등록 팀 이름으로 안내한다.

- 언급 0개: 회의록에서 후보 팀(백엔드팀, 디자인팀)이 언급되지 않았습니다. unassigned:AMBIGUOUS로 등록하세요.
- 언급 1개: 회의록에 백엔드팀이 언급되어 있습니다. team=백엔드팀으로 재조회하세요.
- 언급 여러 개: 회의록에 여러 후보 팀이 언급되어 있습니다. 문맥으로 확실히 구분할 수 없으면 unassigned:AMBIGUOUS로 등록하세요.

메시지는 기존 AMBIGUOUS 접두사를 유지한다. 다중 후보 데이터는 그대로 반환한다. 언급 팀이 하나라도 자동 배정 권한을 주지 않으며 재조회에서 단일 후보로 확인해야 한다. 이미 team을 지정했는데 동명이인이 남는 경우에는 같은 팀이라도 확정하지 말라는 안내를 유지한다.

## 변경 파일

- src/main/java/com/meetodo/tools/FindMemberTool.java: 후보 팀 언급 여부 계산과 결과 메시지.
- src/test/java/com/meetodo/tools/MeetingToolsTests.java: 세 메시지, 정규화된 팀 수, 같은 팀 동명이인 경계 검증.
- src/test/java/com/meetodo/meeting/MeetingAgentApiIntegrationTests.java: 잘못된 재조회 mock의 새 안내·기존 가드 회귀, 정상 재조회 안내 검증 갱신.
- DEVLOG.md, README.md, 이 문서.

## 자동 검증

새 메시지 검증 5개가 수정 전 실패한 뒤 구현했다. `./gradlew test bootJar --no-daemon`: 전체 131개, 실패·오류·건너뜀 0. 실제 OpenAI는 호출하지 않았다. 고정 mock은 계약·메시지·가드·저장 동작을 확인할 뿐 실제 LLM 호출 감소를 보장하지 않는다.

## 실행과 실측

```zsh
export OPENAI_API_KEY="$(security find-generic-password -a "$USER" -s meetodo-openai -w)"
export OPENAI_CHAT_MODEL=gpt-4o-mini
./gradlew bootRun --args='--spring.profiles.active=local,openai'
```

동일 measure 스크립트로 기존 두 종류를 3회씩 측정해 DEVLOG C 적용 후 칸을 채운다. DB 스키마 변경은 없어 초기화가 기술적으로 필수는 아니다. 다만 A와 동일한 초기 데이터 조건을 맞추고 SHA-256 기존 결과 반환을 새 실행으로 세지 않아야 한다. DB 초기화는 사용자가 직접 한다.

Swagger http://localhost:8080/swagger-ui/index.html 의 POST /api/meetings:

```json
{"rawText":"지영은 회의실 예약을 확인한다."}
```

기대: 미정(AMBIGUOUS). GET /api/meetings/{id}/logs의 최초 팀 없는 findMember 결과에 후보 팀 미언급과 미정 등록 안내가 있는지 확인한다. 모델이 이를 따랐는지와 실제 호출 횟수는 실측한다.

```json
{"rawText":"백엔드팀 지영은 로그인 API 오류 처리를 2026-11-20까지 수정한다."}
```

기대: 백엔드팀 지영 배정. 모델이 첫 조회부터 team을 넣으면 단일 후보 기존 경로를 타므로 새 AMBIGUOUS 메시지가 없어도 정상이다. 이름만 먼저 조회했다면 백엔드팀 재조회 안내가 나온다.

```json
{"rawText":"백엔드팀과 디자인팀 합동 회의에서 지영은 회의실 예약을 확인한다."}
```

기대: 팀 없는 다중 후보 조회 시 여러 후보 팀 언급과 중립적 안내. 특정 사람과 팀의 관계를 문자열 대조만으로 증명하지는 못한다는 기존 한계는 유지된다.

각 회차의 응답과 로그를 다음 DB 초기화 전에 함께 저장한다. 같은 meetingId가 다른 회차에서 재사용될 수 있다. A의 지영 2·3회차 실패 코드는 여전히 미확인이다. C 표는 아직 비어 있고 B는 보류한다.

## 면접 예상 질문

1. 왜 서버가 미리 계산하나? — 모델이 다시 판단하던 후보 팀 언급 여부를 서버가 같은 규칙으로 계산해 다음 행동을 명확히 안내하려는 실험이다.
2. 왜 언급된 팀이 하나여도 자동 배정하지 않나? — 문자열 언급과 인물 확정을 구분하며, 후보 정보와 권한을 분리한 기존 안전 정책을 유지하기 위해서다.
3. 호출이 줄었다고 볼 수 있나? — 아직은 아니다. mock 통과는 메시지·안전 동작의 근거이며 감소 여부는 동일 조건의 C 실측으로 확인한다.
