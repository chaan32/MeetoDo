#!/usr/bin/env python3
"""MeetoDo RAG experiment: explicit fixtures, raw scores, conservative fact checks.

Only --prepare creates meetings. Use a dedicated empty evaluation database, active
members, configured admin, and disabled Slack notifications. No DELETE or retries.
Python 3.9+ standard library; results and fixtures must be outside this repository.
"""
import argparse
from collections import Counter
from datetime import datetime, timedelta
import json
from pathlib import Path
import sys
import time
from urllib import error, parse, request
from zoneinfo import ZoneInfo

DEFAULT_DIR = Path('/tmp/meetodo-rag-eval')
FACT_FIELDS = ('taskId', 'title', 'assigneeName', 'assigneeTeam', 'dueDate', 'status')


class EvaluationError(Exception):
    """A safe fixed error code, never a provider error body or URL."""


def validate_base_url(url):
    try:
        parts = parse.urlsplit(url)
        parts.port  # Reject malformed ports even though an explicit port is optional.
        valid = (parts.scheme in ('http', 'https') and bool(parts.hostname)
                 and parts.username is None and parts.password is None
                 and not parts.query and not parts.fragment)
    except ValueError:
        valid = False
    if not valid:
        raise EvaluationError('INVALID_BASE_URL')
    return url.rstrip('/')


class Api:
    def __init__(self, base_url, actor, admin):
        self.base_url = validate_base_url(base_url)
        for identity in (actor, admin):
            if not identity or len(identity) > 80 or any(ord(c) < 33 or ord(c) > 126 for c in identity):
                raise EvaluationError('INVALID_SLACK_ID')
        self.actor, self.admin = actor, admin

    def request(self, method, path, body=None, admin=False):
        encoded = None if body is None else json.dumps(body, ensure_ascii=False).encode('utf-8')
        req = request.Request(self.base_url + path, data=encoded, method=method,
                              headers={'Content-Type': 'application/json',
                                       'X-Slack-User-Id': self.admin if admin else self.actor})
        try:
            # Reject redirects rather than sending the actor header to another host.
            class NoRedirect(request.HTTPRedirectHandler):
                def redirect_request(self, req, fp, code, msg, headers, newurl):
                    return None
            with request.build_opener(NoRedirect).open(req, timeout=180) as response:
                return json.load(response)
        except error.HTTPError as failure:
            raise EvaluationError('HTTP_' + str(failure.code)) from None
        except (error.URLError, TimeoutError, OSError):
            raise EvaluationError('NETWORK_ERROR') from None
        except (ValueError, UnicodeError):
            raise EvaluationError('INVALID_JSON_RESPONSE') from None


def external_path(path):
    path = Path(path).expanduser().resolve()
    repo = Path(__file__).resolve().parents[1]
    if path == repo or repo in path.parents:
        raise EvaluationError('OUTPUT_MUST_BE_OUTSIDE_REPOSITORY')
    return path


def save_json(path, data):
    path = external_path(path)
    path.parent.mkdir(parents=True, exist_ok=True)
    temporary = path.with_name(path.name + '.tmp')
    temporary.write_text(json.dumps(data, ensure_ascii=False, indent=2, allow_nan=False) + '\n', encoding='utf-8')
    temporary.replace(path)


def select_members(members, actor, member_ids=None):
    active = [m for m in members if m.get('active') is True]
    if not any(m.get('slackUserId') == actor for m in active):
        raise EvaluationError('ACTIVE_ACTOR_REQUIRED')
    counts = Counter((m.get('name'), m.get('team')) for m in active)
    available = [m for m in active if m.get('name') and m.get('team')
                 and counts[(m['name'], m['team'])] == 1]
    if member_ids:
        selected = [next((m for m in available if m['id'] == identity), None) for identity in member_ids]
    else:
        selected = []
        ordered = sorted(available, key=lambda m: (['김민수', '이지영', '전해찬'].index(m['name'])
                         if m['name'] in ['김민수', '이지영', '전해찬'] else 3, m['id']))
        for member in ordered:
            if member['name'] not in [m['name'] for m in selected]:
                selected.append(member)
            if len(selected) == 3:
                break
    if len(selected) != 3 or any(m is None for m in selected) or len({m['name'] for m in selected}) != 3:
        raise EvaluationError('THREE_DISTINCT_ACTIVE_FULL_NAMES_REQUIRED')
    return selected


def require_ready(api):
    state = api.request('GET', '/api/dev/rag/index-status', admin=True)
    if state.get('ready') is not True:
        raise EvaluationError('INDEX_NOT_READY')
    return state


def reindex(api, timeout=180):
    job = api.request('POST', '/api/dev/rag/reindex', admin=True)
    if job.get('status') != 'QUEUED' or not job.get('jobId'):
        raise EvaluationError('REINDEX_NOT_QUEUED')
    deadline = time.monotonic() + timeout
    while time.monotonic() < deadline:
        state = api.request('GET', '/api/dev/rag/reindex/' + parse.quote(str(job['jobId']), safe=''), admin=True)
        if state.get('status') == 'COMPLETED':
            return require_ready(api)
        if state.get('status') == 'FAILED':
            raise EvaluationError('REINDEX_FAILED')
        time.sleep(0.5)
    raise EvaluationError('REINDEX_TIMEOUT')


def prepare(api, manifest_path, actor, member_ids=None):
    manifest_path = external_path(manifest_path)
    if manifest_path.exists():
        raise EvaluationError('MANIFEST_ALREADY_EXISTS_USE_EXISTING_OR_RESET_MANUALLY')
    data = {'state': 'PREPARING', 'createdAt': datetime.now(ZoneInfo('Asia/Seoul')).isoformat(),
            'fixtures': [], 'attempts': []}
    save_json(manifest_path, data)
    try:
        # Both inventories must be empty before the first write. Never clear them.
        meetings = api.request('GET', '/api/meetings')
        tasks = api.request('GET', '/api/tasks')
        if meetings != [] or tasks != []:
            raise EvaluationError('DEDICATED_EMPTY_DATABASE_REQUIRED')
        members = select_members(api.request('GET', '/api/members'), actor, member_ids)
        data['members'] = members
        # This read also checks dev endpoint/admin access before creating fixtures.
        api.request('GET', '/api/dev/rag/index-status', admin=True)
        today = datetime.now(ZoneInfo('Asia/Seoul')).date()
        titles = ['결제 API 스펙 정리', '로그인 오류 재현 테스트', '배포 로그 점검', '고객 문의 분류 기준 문서 작성']
        for i, title in enumerate(titles):
            member = members[[0, 1, 2, 0][i]]
            due = (today + timedelta(days=7 + i)).isoformat()
            body = {'rawText': f"담당자: {member['team']} {member['name']}. 할 일: {title}. 마감일: {due}. "
                    '이 회의에서 확정한 할 일은 위 한 건뿐입니다.'}
            attempt = {'request': body, 'response': None}
            data['attempts'].append(attempt)
            save_json(manifest_path, data)
            response = api.request('POST', '/api/meetings', body)
            attempt['response'] = response
            save_json(manifest_path, data)
            if not isinstance(response, dict):
                raise EvaluationError('INVALID_FIXTURE_RESPONSE')
            items = response.get('tasks', [])
            if (response.get('status') != 'DONE' or response.get('notificationStatus') != 'SKIPPED'
                    or response.get('duplicateOfMeetingId') is not None or not response.get('meetingId')
                    or not isinstance(items, list) or len(items) != 1 or not isinstance(items[0], dict)):
                raise EvaluationError('FIXTURE_MEETING_CONTRACT_FAILED')
            task = items[0]
            if (task.get('resultType') != 'CREATED' or task.get('assigneeName') != member['name']
                    or task.get('assigneeTeam') != member['team'] or task.get('dueDate') != due
                    or task.get('status') != 'TODO' or not task.get('title') or not task.get('taskId')
                    or task['taskId'] in [f['taskId'] for f in data['fixtures']]):
                raise EvaluationError('FIXTURE_TASK_CONTRACT_FAILED')
            # Preserve actual returned titles; extraction wording may legitimately vary.
            data['fixtures'].append(dict({key: task[key] for key in FACT_FIELDS}, meetingId=response['meetingId']))
            save_json(manifest_path, data)
        data['indexStatus'] = reindex(api)
        data['state'] = 'READY'
        save_json(manifest_path, data)
        return data
    except (EvaluationError, ValueError, TypeError, KeyError, AttributeError) as failure:
        if not isinstance(failure, EvaluationError):
            failure = EvaluationError('INVALID_SETUP_RESPONSE')
        data['state'] = 'SETUP_FAILED'
        data['error'] = str(failure)
        save_json(manifest_path, data)
        raise failure from None


def build_questions(manifest):
    fixtures = manifest.get('fixtures', [])
    if manifest.get('state') != 'READY' or len(fixtures) != 4:
        raise EvaluationError('READY_FOUR_FIXTURE_MANIFEST_REQUIRED')
    if (len({f['taskId'] for f in fixtures}) != 4 or len({f['meetingId'] for f in fixtures}) != 4
            or any(not all(key in f for key in FACT_FIELDS) for f in fixtures)):
        raise EvaluationError('INVALID_FIXTURE_MANIFEST')
    a, b, c, d = fixtures
    if a['assigneeName'] != d['assigneeName'] or len({a['assigneeName'],b['assigneeName'],c['assigneeName']}) != 3:
        raise EvaluationError('INVALID_FIXTURE_OWNERS')
    rows = [
        (f"{a['assigneeTeam']} {a['assigneeName']}이 맡은 일은 무엇인가요?", [a,d]),
        (f"{b['assigneeTeam']} {b['assigneeName']}이 맡은 일은 무엇인가요?", [b]),
        (f"{c['assigneeTeam']} {c['assigneeName']}이 맡은 일은 무엇인가요?", [c]),
        (f"'{a['title']}'의 담당자와 팀은 무엇인가요?", [a]),
        (f"'{b['title']}'의 마감일은 언제인가요?", [b]),
        (f"'{c['title']}'의 현재 상태는 무엇인가요?", [c]),
        (f"'{d['title']}'의 담당자와 마감일은 무엇인가요?", [d]),
        ('달 기지 산소 재활용 장치 구매 담당자는 누구인가요?', []),
        ('남극 연구선 선체 도색 업무의 마감일은 언제인가요?', []),
        ('양자 중력 실험용 진공 챔버 제작 상태는 무엇인가요?', [])]
    return [dict(id=f'Q{i+1:02d}', question=text, label='ANSWER' if gold else 'NONE', gold=gold)
            for i, (text, gold) in enumerate(rows)]


def grade(question, response):
    if question['label'] == 'NONE':
        correct = response.get('status') == 'NOT_FOUND'
        return dict(correct=correct, citationCorrect=None, notFoundCorrect=correct, manualCheck=False,
                    factPrecision=None, factRecall=None)
    gold = question['gold']
    current = response.get('currentTasks') or []
    matched = sum(any(all(task.get(k) == expected.get(k) for k in FACT_FIELDS) for task in current) for expected in gold)
    true_items = sum(any(all(task.get(k) == expected.get(k) for k in FACT_FIELDS) for expected in gold) for task in current)
    meetings = {f['meetingId'] for f in gold}
    sources = response.get('sources') or []
    retrieved = {(r.get('documentId'), r.get('meetingId')) for r in response.get('retrievedDocuments') or []}
    cited = {source.get('meetingId') for source in sources}
    citation = bool(sources) and cited == meetings and all(
        source.get('documentId') and (source.get('documentId'), source.get('meetingId')) in retrieved for source in sources)
    precision = true_items / len(current) if current else 0.0
    recall = matched / len(gold)
    return dict(correct=response.get('status') == 'ANSWERED' and precision == 1 and recall == 1 and citation,
                citationCorrect=citation, notFoundCorrect=None,
                manualCheck=response.get('status') == 'ANSWERED' and not current,
                factPrecision=precision, factRecall=recall)


def metrics(response):
    return {key: response.get(key) for key in ('processingTimeMs', 'llmCallCount', 'embeddingCallCount', 'usage',
                                              'similarityThreshold', 'temporaryThreshold')}


def markdown(rows, mode):
    columns = ['id', 'label', 'status', 'error']
    if mode == 'evaluate':
        columns += ['correct', 'citationCorrect', 'notFoundCorrect', 'manualCheck', 'factPrecision', 'factRecall']
    if any('verificationDiagnostics' in row for row in rows):
        columns += ['verificationReason', 'diagnosticError']
    columns += ['processingTimeMs', 'httpElapsedMs', 'llmCallCount', 'embeddingCallCount', 'usage']
    def cell(value):
        if value is None: return 'missing'
        text = json.dumps(value, ensure_ascii=False) if isinstance(value, (dict,list)) else str(value)
        return text.replace('|', '\\|').replace('\n', '<br>')
    lines = ['| ' + ' | '.join(columns) + ' |', '| ' + ' | '.join(['---'] * len(columns)) + ' |']
    lines += ['| ' + ' | '.join(cell(row.get(key)) for key in columns) + ' |' for row in rows]
    if mode == 'scores':
        lines += ['', 'Diagnostic retrieval only: no threshold classification, correctness claim, or automatic tuning.', '',
                  '| id | label | rank | documentId | meetingId | score |', '| --- | --- | --- | --- | --- | --- |']
        for row in rows:
            for rank, hit in enumerate(row.get('retrievedDocuments', []), 1):
                lines.append('| ' + ' | '.join(cell(v) for v in [row['id'],row['label'],rank,hit.get('documentId'),hit.get('meetingId'),hit.get('score')]) + ' |')
    else:
        lines += ['', 'Machine correctness requires all current Task facts plus meeting-bound retrieved citations. '
                  'MEETING-only prose needs manual checking and fails machine grading. '
                  'NOT_FOUND is correct only for NONE gold; UNVERIFIED is never correct.']
    if any('verificationDiagnostics' in row for row in rows):
        summary = summarize_diagnostics(rows)
        lines += ['', 'Verification diagnostics (first rejection; original verdict unchanged):', '',
                  '| topLevelReason | questions |', '| --- | --- |']
        lines += [f'| {reason} | {count} |' for reason, count in summary['topLevelReasons'].items()]
        lines += ['', f"Questions with any OK evidence: {summary['questionsWithAnyOk']}",
                  f"UNVERIFIED questions with any OK evidence: {summary['rejectedQuestionsWithAnyOk']}",
                  f"Collected: {summary['collectedQuestions']}; missing: {summary['missingDiagnostics']}; not applicable (no model call): {summary['notApplicableQuestions']}"]
    return '\n'.join(lines) + '\n'


def summarize_diagnostics(rows):
    collected = [row for row in rows if isinstance(row.get('verificationDiagnostics'), dict)]
    reasons = Counter(row['verificationDiagnostics'].get('reason') for row in collected
                      if row['verificationDiagnostics'].get('reason'))
    with_ok = [row for row in collected if any(check.get('reason') == 'OK'
               for check in row['verificationDiagnostics'].get('checks', []))]
    not_applicable = sum(row.get('verificationDiagnostics') is None and row.get('llmCallCount') == 0
                         and not row.get('diagnosticError') for row in rows)
    return dict(topLevelReasons=dict(sorted(reasons.items())), questionsWithAnyOk=len(with_ok),
                rejectedQuestionsWithAnyOk=sum(row.get('status') == 'UNVERIFIED' for row in with_ok),
                collectedQuestions=len(collected), missingDiagnostics=len(rows)-len(collected)-not_applicable,
                notApplicableQuestions=not_applicable)


def evaluate(api, manifest, mode, output_dir, include_diagnostics=False):
    questions = build_questions(manifest)
    output_dir = external_path(output_dir)
    output_dir.mkdir(parents=True, exist_ok=True)
    require_ready(api)
    rows = []
    for question in questions:
        started = time.monotonic()
        response = None
        try:
            response = api.request('POST', '/api/questions', {'question':question['question'], 'diagnostic':mode == 'scores'})
            if (not isinstance(response, dict) or any(
                    not isinstance(response.get(key, []), list)
                    or any(not isinstance(item, dict) for item in response.get(key, []))
                    for key in ('sources', 'currentTasks', 'retrievedDocuments'))):
                response = None
                raise EvaluationError('INVALID_QUESTION_RESPONSE')
            elapsed = round((time.monotonic()-started)*1000, 3)
            row = dict(question, status=response.get('status'), httpElapsedMs=elapsed, **metrics(response))
            row['retrievedDocuments'] = (response.get('retrievedDocuments') or [])[:5]
            if mode == 'scores':
                if response.get('llmCallCount') != 0 or response.get('status') != 'DIAGNOSTIC':
                    row['error'] = 'DIAGNOSTIC_CONTRACT_FAILED'
            else:
                row.update(grade(question, response))
        except EvaluationError as failure:
            row = dict(question, status='HTTP_ERROR', error=str(failure),
                       httpElapsedMs=round((time.monotonic()-started)*1000,3), **metrics({}))
            if mode == 'evaluate': row.update(grade(question, {}))
        if include_diagnostics:
            row['verificationDiagnostics'] = None
            identity = response.get('questionId') if isinstance(response, dict) else None
            if not isinstance(identity, int) or isinstance(identity, bool) or identity <= 0:
                row['diagnosticError'] = 'QUESTION_ID_UNAVAILABLE'
            else:
                try:
                    diagnostic = api.request('GET', '/api/dev/rag/questions/' + str(identity) + '/diagnostics', admin=True)
                    value = diagnostic.get('diagnostics') if isinstance(diagnostic, dict) else None
                    if not isinstance(diagnostic, dict) or diagnostic.get('questionId') != identity or (
                            value is not None and (not isinstance(value, dict) or not isinstance(value.get('checks'), list)
                            or any(not isinstance(check, dict) for check in value['checks']))):
                        raise EvaluationError('INVALID_DIAGNOSTIC_RESPONSE')
                    row['verificationDiagnostics'] = value
                    row['verificationReason'] = value.get('reason') if value else None
                except EvaluationError as failure:
                    row['diagnosticError'] = str(failure)
        # One request per question, including failures: retries distort call/cost counts.
        save_json(output_dir / (question['id'] + '.json'), {'question':question, 'response':response, 'result':row})
        rows.append(row)
        save_json(output_dir / 'results.json', {'mode':mode, 'results':rows,
                  **({'diagnosticSummary':summarize_diagnostics(rows)} if include_diagnostics else {})})
        (output_dir / 'results.md').write_text(markdown(rows, mode), encoding='utf-8')
    print(markdown(rows, mode))
    return rows


def main(argv=None):
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--base-url', default='http://localhost:8080')
    parser.add_argument('--actor', required=True, help='Active member Slack user ID')
    parser.add_argument('--admin', help='Admin Slack user ID; defaults to --actor')
    parser.add_argument('--manifest', type=Path, default=DEFAULT_DIR / 'fixtures.json')
    parser.add_argument('--output-dir', type=Path, default=DEFAULT_DIR)
    parser.add_argument('--prepare', action='store_true', help='Create four fixtures ONLY in an empty dedicated database')
    parser.add_argument('--member-ids', help='Exactly three active member IDs, e.g. 1,2,4')
    parser.add_argument('--include-diagnostics', action='store_true', help='Fetch persisted verification diagnostics with admin identity; no additional model call')
    parser.add_argument('--mode', choices=('scores','evaluate'), default='scores')
    args = parser.parse_args(argv)
    try:
        api = Api(args.base_url, args.actor, args.admin or args.actor)
        manifest_path, output_dir = external_path(args.manifest), external_path(args.output_dir)
        member_ids = None
        if args.member_ids:
            try:
                member_ids = [int(value) for value in args.member_ids.split(',')]
                if len(member_ids) != 3 or len(set(member_ids)) != 3: raise ValueError()
            except ValueError:
                raise EvaluationError('THREE_MEMBER_IDS_REQUIRED') from None
        if args.prepare:
            prepare(api, manifest_path, args.actor, member_ids)
            print('READY: fixtures created and reindexed. Run again without --prepare to ask questions.')
            return 0
        try:
            manifest = json.loads(manifest_path.read_text(encoding='utf-8'))
        except (OSError, ValueError):
            raise EvaluationError('READABLE_MANIFEST_REQUIRED_USE_EXPLICIT_PREPARE') from None
        members = api.request('GET', '/api/members')
        if not any(m.get('active') is True and m.get('slackUserId') == args.actor for m in members):
            raise EvaluationError('ACTIVE_ACTOR_REQUIRED')
        run_dir = output_dir / (args.mode + '-' + datetime.now(ZoneInfo('Asia/Seoul')).strftime('%Y%m%dT%H%M%S%f'))
        rows = evaluate(api, manifest, args.mode, run_dir, include_diagnostics=args.include_diagnostics)
        print('Saved results:', run_dir)
        return 1 if any(row.get('error') or row.get('diagnosticError') for row in rows) else 0
    except EvaluationError as failure:
        print('Evaluation stopped:', str(failure), file=sys.stderr)
        return 1
    except (OSError, ValueError, TypeError, KeyError):
        print('Evaluation stopped: INVALID_DATA_OR_OUTPUT_ERROR', file=sys.stderr)
        return 1


if __name__ == '__main__':
    sys.exit(main())
