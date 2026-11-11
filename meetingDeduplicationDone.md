# 비슷한 회의록의 Meeting 중복 저장 방지 정리

작성일: 2026-11-11

## 목표

문장 끝에 잡담을 덧붙이거나 표현을 조금 바꿔 같은 회의 내용을 다시 보내도, 동일한 할 일이 이미 처리된 회의라면 새 Task와 새 Meeting을 만들지 않는다. 기존 내용과 완전히 같은 요청은 기존 해시 기반 중복 처리를 그대로 사용한다.

## 문제와 원인

- 회의 해시는 본문이 정확히 같을 때만 일치하도록 되어 있어, 문자나 문장이 추가된 재전송은 새 Meeting 후보가 됐다.
- Task 중복 검사만으로는 기존 Task가 재사용되어도 새 Meeting 행이 남을 수 있었다.
- 이미 생성된 Meeting 10·11 조회 결과에서 같은 Task 세 건이 확인됐고, 11번 본문에는 추가 문장이 있었다.

## 처리 방식

1. 기존과 같이 회의 처리를 진행하고 `findTasks` 및 `createTask` 결과에서 재사용된 Task를 확인한다.
2. 처리된 Task가 모두 한 기존 Meeting에 속하고, 그 Task ID 집합이 해당 기존 Meeting의 전체 Task 집합과 같으면 새 회의로 저장하지 않는다.
3. 이 경우 처리 중 만든 임시 Meeting과 ToolCallLog를 삭제하고 기존 Meeting 결과를 반환한다. 반환 Task에는 `resultType: EXISTING`을 표시한다.
4. 새 Task가 만들어졌거나, 기존 Task들이 여러 회의에 걸쳐 있거나, 기존 회의 작업의 일부만 재사용된 경우에는 새 Meeting을 보존한다.
5. 본문이 정확히 같은 요청은 기존 SHA-256 해시와 DB unique 제약으로 계속 중복 접수 처리한다.

이 기준은 문장 유사도 자체를 계산하지 않는다. 에이전트가 찾은 Task의 정체와 출처 회의의 전체 Task 집합을 비교해, 동일한 작업 묶음인지 판단한다.

## 트레이드오프와 범위

- 같은 작업을 새 회의에서 다시 논의했더라도 담당자·정규화 제목·TODO 상태가 같고 기존 회의의 Task 전체와 일치하면 기존 Meeting을 반환할 수 있다.
- 일부 작업만 겹치거나 작업이 여러 원본 회의에서 재사용된 경우에는 새 Meeting을 저장한다. 서로 다른 논의 기록을 무리하게 하나로 합치지 않기 위한 기준이다.
- 이 처리는 기존 데이터 정리 작업이 아니다. 규칙 적용 전에 저장된 Meeting 11 같은 기록은 DB에 남아 있으며, 이번 검증에서도 삭제하지 않았다.
- 결과가 모델의 Task 추출에 의존하므로, 새 OpenAI 요청 없이 mock LLM 통합 테스트로 서버의 저장·삭제 경계를 검증했다.

## 검증

실행 명령:

```bash
./gradlew test --tests '*MeetingAgentApiIntegrationTests' --no-daemon
```

결과: 통합 테스트 3개, 통과 3개, 실패 0개, 오류 0개, 건너뜀 0개.

`returnsTheOriginalMeetingWhenEveryTaskIsReusedFromItsTaskSet` 테스트는 문구가 달라지고 잡담이 덧붙은 회의록에서 기존 Meeting ID를 반환하며, Meeting 수 1건·Task 수 3건을 유지하고 임시 ToolCallLog가 남지 않는지 확인한다. 테스트는 mock ChatModel을 사용했고 실제 OpenAI API는 호출하지 않았다.

## 남은 직접 확인

실제 OpenAI가 유사한 회의록에서도 기존 작업을 같은 Task로 추출하는지는 사용자가 Swagger에서 확인할 수 있다.

1. `POST /api/meetings`에 이미 처리한 회의와 표현만 바꾸거나 끝에 무관한 문장을 덧붙인 회의록을 보낸다.
2. 응답의 `meetingId`가 원본 회의 ID와 같은지, `tasks[].resultType`이 `EXISTING`인지 확인한다.
3. `GET /api/meetings`에서 새 Meeting이 추가되지 않았는지 확인한다.
4. `GET /api/tasks`에서 Task 수가 늘지 않았는지 확인한다.

