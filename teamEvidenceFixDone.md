# 회의록 팀 근거 검증과 단일 후보 배정 권한

## 변경 파일

- tools/TeamNames.java: 새 공통 팀 정규화·회의록 문자열 대조. 새 의존성 없음.
- tools/FindMemberTool.java: 첫 조회부터 팀 존재 검증, 정규화된 등록 팀 비교.
- tools/ToolExecutionService.java: 단일 후보 권한, TEAM_NOT_IN_TRANSCRIPT 집계, 팀 불일치 가드에 같은 정규화 적용.
- tools/CreateTaskTool.java / FindTasksTool.java: 단일 후보 권한 검사와 복구 안내.
- meeting/SpringAiMeetingAgent.java: 실제 팀 이름 근거, 역할로 팀 추측 금지, 미정 처리 지침.
- MeetingAgentApiIntegrationTests.java / MeetingToolsTests.java / CrossMeetingTaskReuseTests.java: 실제 로그 replay·정상 회귀·정규화·권한·알려진 한계 테스트와 조회 선행조건 fixture 갱신.
- README.md / DEVLOG.md / 이 문서: 실측 조건·사실/추정·한계·실행 방법.

기존에는 다중 후보 목록의 ID도 등록할 수 있었다. 이제 모델에게 보여주는 정보(후보 목록)와 배정 권한을 분리해 성공한 단일 후보만 권한을 갖는다. findTasks에서도 검사해 기존 Task 재사용 우회를 막는다. DB 스키마 변경은 없다.

## 검증 결과

수정 전 재현 테스트 6개 실패를 확인한 뒤 수정했다. 최종 `./gradlew test bootJar --no-daemon`: 전체 126개 통과, 실패·오류·건너뜀 0. 실제 OpenAI를 호출하지 않았다.

실제 호출 순서 mock에서는 AMBIGUOUS 후 원문에 없는 팀 조회가 TEAM_NOT_IN_TRANSCRIPT로 거절되고, 후속 findTasks 및 createTask도 INVALID_ASSIGNEE로 거절돼 검증 실패 3건이 저장된다. 모델이 곧바로 미정으로 복구하면 그 호출 횟수와 실패 수는 달라진다. 이 mock 수치를 사용자 실측 표에 채우지 않는다.

## Swagger 실행

```zsh
export OPENAI_API_KEY="$(security find-generic-password -a "$USER" -s meetodo-openai -w)"
export OPENAI_CHAT_MODEL=gpt-4o-mini
./gradlew bootRun --args='--spring.profiles.active=local,openai'
```

http://localhost:8080/swagger-ui/index.html 의 POST /api/meetings로 다음을 각각 전송한다.

| JSON | 기대 결과 |
| --- | --- |
| {"rawText":"지영은 회의실 예약을 확인한다."} | 담당자 미정, AMBIGUOUS, 동명이인 확인 필요 |
| {"rawText":"백엔드팀 지영은 로그인 API 오류를 수정한다."} | 백엔드팀 지영 배정 |
| {"rawText":"백엔드 지영은 로그인 API 오류를 수정한다."} | 접미사 차이를 허용하여 백엔드팀 지영 배정 |
| {"rawText":"해찬은 배포 체크리스트를 공유한다."} | 등록된 유일한 해찬 배정, team 생략 조회 |
| {"rawText":"프론트엔드팀 해찬은 11-12까지 회의실을 예약한다."} | 명시 팀은 원문에 있지만 등록 팀과 다르므로 TEAM_MISMATCH, 팀 불일치 확인 필요 |

GET /api/meetings/{id}/logs에서 모델이 원문에 없는 팀을 재조회했다면 TEAM_NOT_IN_TRANSCRIPT의 실패 result·안내가 남는지 본다. 미리 근거 없는 조회를 하지 않고 정상 미정 처리했다면 이 오류가 없어도 정상이다. 원문에 없는 팀 조회가 발생했는데 성공해서는 안 된다. 응답 validationFailureCount는 이러한 거절을 포함한다.

회의록 팀·조회 팀·등록 팀의 대소문자/공백/끝 '팀' 접미사 차이는 허용한다. '팀'만 보낸 빈 이름과 빈 문자열은 INVALID_MEMBER_TEAM이다.

DB 초기화는 스키마상 필수가 아니다. 과거 잘못된 Task·Meeting은 자동 수정하지 않으며 같은 본문 재전송은 기존 결과를 반환한다. 동일 입력으로 실측하려면 사용자가 개발 데이터를 초기화하거나 새로운 입력으로 검증해야 한다. A 실측 표의 A 적용 후는 이번 수정까지 반영한 조건이다. B는 진행하지 않았다.

## 알려진 한계

정규화한 문자열의 존재만 확인한다. '백엔드팀 회의에서 지영은…'처럼 팀이 다른 맥락으로 등장해도 허용될 수 있다. 특히 '지영은 디자인 시안을 검토한다.'에 team='디자인팀'을 보내면 '디자인' 부분 문자열 때문에 통과한다. 이 동작은 knownLimitationDesignWorkWordAllowsDesignTeamLookupAndAssignment 테스트와 주석에 알려진 한계로 명시했다. BE팀 등 약칭 확장은 지원하지 않는다. 회의 단위 권한과 보수적 팀 불일치 가드의 기존 한계도 유지한다.

## 면접 예상 질문

1. 왜 후보 목록만으로 배정할 수 없게 했나? — 모델이 비교하도록 제공한 정보가 서버의 인물 확정 결과는 아니기 때문이다. 다중 후보는 정보, 성공한 단일 후보 조회는 배정 권한으로 구분한다.
2. 왜 문자열 대조를 택했나? — 관찰한 근거 없는 팀 생성을 단순하고 재현 가능한 검사로 차단하기 위해서다. 단, 팀과 특정 사람의 관계는 증명하지 못해 부분 문자열 오탐을 테스트·문서에 공개했다.
3. 실패 횟수와 로그를 왜 함께 확인했나? — 거절이 실제로 실행됐는지, 모델이 어떤 오류 결과를 받고 재시도했는지 판단할 근거가 필요하기 때문이다. TEAM_NOT_IN_TRANSCRIPT 집계와 마스킹된 실패 result를 검증했다.
