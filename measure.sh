#!/bin/zsh
# 단계 A 실측: 매 회차마다 data/ 초기화 + 서버 재시작 후 회의록 1건 처리
export OPENAI_API_KEY="$(security find-generic-password -a "$USER" -s meetodo-openai -w)"
export OPENAI_CHAT_MODEL=gpt-4o-mini

./gradlew bootJar --no-daemon -q || exit 1
JAR=$(ls build/libs/*.jar | grep -v plain | head -1)

run_case() {
  local label="$1" body="$2" round="$3"
  rm -rf data/
  java -jar "$JAR" --spring.profiles.active=local,openai > /tmp/meetodo.log 2>&1 &
  local pid=$!
  until curl -s localhost:8080/actuator/health | grep -q UP; do sleep 1; done
  local res=$(curl -s -X POST localhost:8080/api/meetings -H 'Content-Type: application/json' -d "$body")
  echo "$res" > "/tmp/meetodo-${label}-${round}.json"
  python3 - "$label" "$round" "$res" << 'PY'
import json, sys
label, rnd, raw = sys.argv[1], sys.argv[2], sys.argv[3]
r = json.loads(raw); t = (r.get("tasks") or [{}])[0]
who = t.get("assigneeTeam") or f"미정({t.get('unassignedReason')})"
print(f"| {label} | {rnd} | {who} | {r.get('llmCallCount')} | {r.get('toolCallCount')} | {r.get('validationFailureCount')} | {r.get('processingTimeMs')} |")
PY
  local mid=$(python3 -c "import json,sys; print(json.loads(sys.argv[1]).get('meetingId',1))" "$res")
  curl -s "localhost:8080/api/meetings/${mid}/logs" > "/tmp/meetodo-${label}-${round}-logs.json"
  kill $pid; wait $pid 2>/dev/null
}

echo "| 회의록 | 회차 | 결과 | LLM | 도구 | 검증실패 | 시간(ms) |"
echo "|---|---|---|---:|---:|---:|---:|"
for i in 1 2 3; do
  run_case "백엔드팀지영" '{"rawText":"백엔드팀 지영은 로그인 API 오류 처리를 2026-11-20까지 수정한다."}' $i
done
for i in 1 2 3; do
  run_case "지영" '{"rawText":"지영은 회의실 예약을 확인한다."}' $i
done
