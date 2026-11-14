#!/bin/zsh
# 단계 F/G 실측: 매 회차마다 data/ 초기화 + 서버 재시작 후 회의록 1건 처리
case_filter="${1:-}" # 예: zsh measure.sh G-내일 (생략하면 전체)
export OPENAI_API_KEY="$(security find-generic-password -a "$USER" -s meetodo-openai -w)"
export OPENAI_CHAT_MODEL=gpt-4o-mini

./gradlew bootJar --no-daemon -q || exit 1
JAR=$(ls build/libs/*.jar | grep -v plain | head -1)

run_case() {
  local label="$1" body="$2" round="$3" expected_expression="${4:-}"
  [[ -n "$case_filter" && "$label" != "$case_filter" ]] && return 0
  rm -rf data/
  java -jar "$JAR" --spring.profiles.active=local,openai > /tmp/meetodo.log 2>&1 &
  local pid=$!
  until curl -s localhost:8080/actuator/health | grep -q UP; do sleep 1; done
  local reference_date=$(TZ=Asia/Seoul date +%F)
  local res=$(curl -s -X POST localhost:8080/api/meetings -H 'Content-Type: application/json' -d "$body")
  printf '%s' "$res" > "/tmp/meetodo-${label}-${round}.json"
  local finished_date=$(TZ=Asia/Seoul date +%F)
  python3 - "$label" "$round" "$res" "$expected_expression" "$reference_date" "$finished_date" << 'PY'
import json, sys
from datetime import date, datetime, timedelta
from zoneinfo import ZoneInfo
label, rnd, raw = sys.argv[1:4]
expression = sys.argv[4] if len(sys.argv) > 4 else ""
reference = date.fromisoformat(sys.argv[5]) if len(sys.argv) > 5 else datetime.now(ZoneInfo("Asia/Seoul")).date()
finished = sys.argv[6] if len(sys.argv) > 6 else reference.isoformat()
monday = reference - timedelta(days=reference.weekday())
relative = {"다음 주 금요일": monday + timedelta(days=11), "이번 주 안에": monday + timedelta(days=6),
            "다음 주 월요일": monday + timedelta(days=7), "내일": reference + timedelta(days=1)}
expected = None if not expression or expression == "없음" else relative.get(expression)
if expected is None and expression and expression != "없음":
    expected = date.fromisoformat(expression)
r = json.loads(raw); t = (r.get("tasks") or [{}])[0]
who = (t.get("assigneeTeam") or f"미정({t.get('unassignedReason')})") if r.get("status") == "DONE" else f"오류({r.get('code', r.get('status'))})"
title = (t.get("title") or "").replace("|", "\\|").replace("\n", " ")
actual = t.get("dueDate") or None
usage = r.get("usage") or {}
input_tokens = usage.get("inputTokens")
output_tokens = usage.get("outputTokens")
input_tokens = "미제공" if input_tokens is None else input_tokens
output_tokens = "미제공" if output_tokens is None else output_tokens
expected_text = expected.isoformat() if expected else "없음" if expression == "없음" else "미지정"
match = "미확인" if not expression else "예" if r.get("status") == "DONE" and r.get("tasks") and actual == (expected.isoformat() if expected else None) else "아니오"
if reference.isoformat() != finished:
    match = "기준일 변경: 재측정"
print(f"| {label} | {rnd} | {who} | {r.get('llmCallCount')} | {r.get('toolCallCount')} | {r.get('validationFailureCount')} | {r.get('processingTimeMs')} | {title} | {reference} | {expected_text} | {actual or '없음'} | {match} | {input_tokens} | {output_tokens} |")
PY
  local mid=$(python3 -c "import json,sys; print(json.loads(sys.argv[1]).get('meetingId',1))" "$res")
  curl -s "localhost:8080/api/meetings/${mid}/logs" > "/tmp/meetodo-${label}-${round}-logs.json"
  kill $pid; wait $pid 2>/dev/null
}

printf '%s\n' "| 회의록 | 회차 | 결과 | LLM | 도구 | 검증실패 | 시간(ms) | 제목 | 기준일(Seoul) | 기대 마감일 | 실제 마감일 | 일치 | 입력 토큰 | 출력 토큰 |"
printf '%s\n' "|---|---|---|---:|---:|---:|---:|---|---|---|---|---|---:|---:|"
for i in 1 2 3; do
  run_case "백엔드팀지영" '{"rawText":"백엔드팀 지영은 로그인 API 오류 처리를 2026-11-20까지 수정한다."}' $i "2026-11-20"
done
for i in 1 2 3; do
  run_case "지영" '{"rawText":"지영은 회의실 예약을 확인한다."}' $i "없음"
done

# G: 절대 날짜 실측과 분리해서 같은 상대 날짜 문장을 각3회 처리한다.
for expression in "다음 주 금요일" "이번 주 안에" "다음 주 월요일" "내일"; do
  for i in 1 2 3; do
    run_case "G-${expression// /}" "{\"rawText\":\"백엔드팀 지영은 로그인 API 오류를 ${expression}까지 수정한다.\"}" $i "$expression"
  done
done
