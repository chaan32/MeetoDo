"""Offline evaluator contracts; no server or model calls."""
import importlib.util
import json
import contextlib
import io
from pathlib import Path
import tempfile
import unittest

SPEC = importlib.util.spec_from_file_location('evaluate_rag', Path(__file__).parents[1] / 'evaluate_rag.py')
rag = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(rag)


def fixtures():
    return {'state': 'READY', 'fixtures': [dict(meetingId=i + 1, taskId=i + 11,
        title=['결제 API 스펙 정리', '로그인 오류 재현 테스트', '배포 로그 점검', '고객 문의 분류 기준 문서 작성'][i],
        assigneeName=['김민수', '이지영', '전해찬', '김민수'][i], assigneeTeam=['개발', '디자인', '운영', '개발'][i],
        dueDate='2026-11-21', status='TODO') for i in range(4)]}


def answered():
    task = fixtures()['fixtures'][0]
    return dict(status='ANSWERED', currentTasks=[task], sources=[dict(documentId='task-11', meetingId=1)],
                retrievedDocuments=[dict(documentId='task-11', meetingId=1, score=-0.2)])


class FakeApi:
    def __init__(self, meetings=None, tasks=None, fail_at=None):
        self.calls = []
        self.meetings = meetings or []
        self.tasks = tasks or []
        self.fail_at = fail_at
    def request(self, method, path, body=None, admin=False):
        self.calls.append((method, path, body, admin))
        if path == '/api/members':
            return [dict(id=i, name=n, team=t, active=True, slackUserId='U'+str(i)) for i,n,t in
                    [(1,'김민수','개발'),(2,'이지영','디자인'),(4,'전해찬','운영')]]
        if path == '/api/meetings' and method == 'GET': return self.meetings
        if path == '/api/tasks': return self.tasks
        if path == '/api/dev/rag/index-status': return {'ready': True}
        if path == '/api/dev/rag/reindex': return {'jobId': 'job', 'status': 'QUEUED'}
        if path == '/api/dev/rag/reindex/job': return {'jobId': 'job', 'status': 'COMPLETED'}
        if path == '/api/meetings':
            count = sum(m == 'POST' and p == path for m,p,_,_ in self.calls)
            if count == self.fail_at: raise rag.EvaluationError('HTTP_500')
            f = fixtures()['fixtures'][count-1].copy()
            f['dueDate'] = body['rawText'].split('마감일: ')[1].split('.')[0]
            f['resultType'] = 'CREATED'
            return dict(status='DONE', meetingId=count, notificationStatus='SKIPPED', tasks=[f])
        if path == '/api/questions': return dict(answered(), status='DIAGNOSTIC' if body['diagnostic'] else 'ANSWERED', llmCallCount=0)
        raise AssertionError(path)


class EvaluatorTests(unittest.TestCase):
    def setUp(self):
        output = contextlib.redirect_stdout(io.StringIO())
        output.__enter__()
        self.addCleanup(output.__exit__, None, None, None)

    def test_dataset_has_ten_questions_seven_answers_three_none(self):
        # Fixed dataset composition is part of the experiment.
        qs = rag.build_questions(fixtures())
        self.assertEqual(10, len(qs))
        self.assertEqual(7, sum(q['label']=='ANSWER' for q in qs))
        self.assertEqual(3, sum(q['label']=='NONE' for q in qs))
    def test_three_questions_use_actual_full_names(self):
        # Name retrieval must be measured rather than silently fixed.
        qs = rag.build_questions(fixtures())
        for name in ['김민수','이지영','전해찬']:
            self.assertTrue(any(name in q['question'] for q in qs))
        self.assertEqual(2, len(qs[0]['gold']))
    def test_all_gold_facts_are_required(self):
        # Missing the second task is an incorrect answer.
        self.assertFalse(rag.grade(rag.build_questions(fixtures())[0], answered())['correct'])
    def test_matching_current_facts_and_sources_pass(self):
        # Structured current facts plus retrieved citations are machine checked.
        self.assertTrue(rag.grade({'label':'ANSWER','gold':[fixtures()['fixtures'][0]]}, answered())['correct'])
    def test_source_outside_retrieval_is_rejected(self):
        # A plausible source ID alone is insufficient evidence.
        response = answered(); response['retrievedDocuments'] = []
        self.assertFalse(rag.grade({'label':'ANSWER','gold':[fixtures()['fixtures'][0]]}, response)['citationCorrect'])
    def test_source_meeting_must_match(self):
        # Matching IDs cannot override a different source meeting.
        response = answered(); response['sources'][0]['meetingId'] = 999
        self.assertFalse(rag.grade({'label':'ANSWER','gold':[fixtures()['fixtures'][0]]}, response)['citationCorrect'])
    def test_not_found_is_only_correct_for_not_found(self):
        # UNVERIFIED is never counted as a correct absence.
        for status in ['UNVERIFIED','FAILED','ANSWERED']:
            self.assertFalse(rag.grade({'label':'NONE','gold':[]}, {'status':status})['correct'])
        self.assertTrue(rag.grade({'label':'NONE','gold':[]}, {'status':'NOT_FOUND'})['correct'])
    def test_meeting_quote_without_current_facts_requires_manual_check(self):
        # Semantic prose grading is explicitly outside the automatic checker.
        response = answered(); response['currentTasks'] = []
        result = rag.grade({'label':'ANSWER','gold':[fixtures()['fixtures'][0]]}, response)
        self.assertFalse(result['correct']); self.assertTrue(result['manualCheck'])
    def test_missing_metrics_stay_missing(self):
        # Unknown server measurements must not become invented zeroes.
        self.assertIsNone(rag.metrics({})['llmCallCount'])
        self.assertIsNone(rag.metrics({})['usage'])
    def test_prepare_refuses_nonempty_meetings_before_any_post(self):
        # Preparation never writes to an existing meeting dataset.
        with tempfile.TemporaryDirectory() as d:
            api=FakeApi(meetings=[{'meetingId':1}])
            with self.assertRaises(rag.EvaluationError): rag.prepare(api, Path(d)/'f.json', 'U1')
            self.assertFalse(any(c[0]=='POST' for c in api.calls))
    def test_prepare_refuses_nonempty_tasks_before_any_post(self):
        # Standalone tasks also make the evaluation database unsafe to seed.
        with tempfile.TemporaryDirectory() as d:
            api=FakeApi(tasks=[{'taskId':1}])
            with self.assertRaises(rag.EvaluationError): rag.prepare(api, Path(d)/'f.json', 'U1')
            self.assertFalse(any(c[0]=='POST' for c in api.calls))
    def test_partial_failure_is_saved_without_delete_or_retry(self):
        # Preserve partial IDs so the user can inspect and reset manually.
        with tempfile.TemporaryDirectory() as d:
            path=Path(d)/'f.json'; api=FakeApi(fail_at=2)
            with self.assertRaises(rag.EvaluationError): rag.prepare(api,path,'U1')
            data=json.loads(path.read_text())
            self.assertEqual('SETUP_FAILED', data['state'])
            self.assertEqual(1,len(data['fixtures']))
            self.assertFalse(any(c[0]=='DELETE' for c in api.calls))
            self.assertEqual(2,sum(c[0]=='POST' and c[1]=='/api/meetings' for c in api.calls))
    def test_prepare_creates_four_fixtures_then_reindexes(self):
        # A ready manifest is published only after an explicit reindex completes.
        with tempfile.TemporaryDirectory() as d:
            api=FakeApi(); data=rag.prepare(api,Path(d)/'f.json','U1')
            self.assertEqual('READY',data['state']); self.assertEqual(4,len(data['fixtures']))
            self.assertIn(('POST','/api/dev/rag/reindex',None,True),api.calls)
    def test_scores_preserve_negative_values_and_send_diagnostic(self):
        # Calibration captures raw top scores without threshold classification.
        with tempfile.TemporaryDirectory() as d:
            api=FakeApi(); rows=rag.evaluate(api,fixtures(),'scores',Path(d))
            self.assertTrue(all(c[2]['diagnostic'] for c in api.calls if c[1]=='/api/questions'))
            self.assertEqual(-0.2,rows[0]['retrievedDocuments'][0]['score'])
            self.assertNotIn('correct',rows[0])
    def test_evaluate_uses_server_threshold(self):
        # Normal evaluation sends no threshold override.
        with tempfile.TemporaryDirectory() as d:
            api=FakeApi(); rag.evaluate(api,fixtures(),'evaluate',Path(d))
            bodies=[c[2] for c in api.calls if c[1]=='/api/questions']
            self.assertEqual({'question','diagnostic'},set(bodies[0])); self.assertFalse(bodies[0]['diagnostic'])
    def test_json_escapes_quotes_newlines_and_shell_literals(self):
        # JSON serialization never interprets shell metacharacters.
        with tempfile.TemporaryDirectory() as d:
            path=Path(d)/'a.json'; data={'text':'"\n$(token) `secret` 한글'}
            rag.save_json(path,data); self.assertEqual(data,json.loads(path.read_text()))
    def test_url_rejects_embedded_credentials_and_other_schemes(self):
        # The CLI cannot accidentally leak URL userinfo into requests.
        for url in ['https://user:secret@localhost','file:///tmp/foo','http://localhost/#token']:
            with self.assertRaises(rag.EvaluationError): rag.validate_base_url(url)

    def test_malformed_fixture_response_is_saved_as_setup_failed(self):
        # Unexpected server response structure must leave a recoverable manifest.
        api = FakeApi()
        original = api.request
        def respond(method, path, body=None, admin=False):
            if method == 'POST' and path == '/api/meetings': return []
            return original(method,path,body,admin)
        api.request = respond
        with tempfile.TemporaryDirectory() as d:
            path=Path(d)/'f.json'
            with self.assertRaises(rag.EvaluationError): rag.prepare(api,path,'U1')
            self.assertEqual('SETUP_FAILED',json.loads(path.read_text())['state'])
    def test_notifications_must_be_skipped(self):
        # An enabled notifier is a failed setup, not an accepted experiment.
        api=FakeApi(); original=api.request
        def respond(method,path,body=None,admin=False):
            response=original(method,path,body,admin)
            if method=='POST' and path=='/api/meetings': response['notificationStatus']='SENT'
            return response
        api.request=respond
        with tempfile.TemporaryDirectory() as d:
            path=Path(d)/'f.json'
            with self.assertRaises(rag.EvaluationError): rag.prepare(api,path,'U1')
            data=json.loads(path.read_text())
            self.assertEqual('SETUP_FAILED',data['state'])
            self.assertEqual(1,data['attempts'][0]['response']['meetingId'])
    def test_wrong_assignee_is_setup_failure(self):
        # Do not repair wrong model assignment or redefine gold around it.
        api=FakeApi(); original=api.request
        def respond(method,path,body=None,admin=False):
            response=original(method,path,body,admin)
            if method=='POST' and path=='/api/meetings': response['tasks'][0]['assigneeName']='다른사람'
            return response
        api.request=respond
        with tempfile.TemporaryDirectory() as d:
            with self.assertRaises(rag.EvaluationError): rag.prepare(api,Path(d)/'f.json','U1')
    def test_actual_extracted_title_becomes_gold(self):
        # Legitimate title wording differences are stored verbatim.
        api=FakeApi(); original=api.request
        def respond(method,path,body=None,admin=False):
            response=original(method,path,body,admin)
            if method=='POST' and path=='/api/meetings': response['tasks'][0]['title'] += ' 완료하기'
            return response
        api.request=respond
        with tempfile.TemporaryDirectory() as d:
            data=rag.prepare(api,Path(d)/'f.json','U1')
            self.assertTrue(data['fixtures'][0]['title'].endswith('완료하기'))
    def test_active_actor_is_required_before_fixture_creation(self):
        # Setup must not create meetings for an unregistered actor.
        api=FakeApi()
        with tempfile.TemporaryDirectory() as d:
            with self.assertRaises(rag.EvaluationError): rag.prepare(api,Path(d)/'f.json','UNKNOWN')
        self.assertFalse(any(c[0]=='POST' for c in api.calls))
    def test_same_name_team_duplicates_are_not_selected(self):
        # A team cannot disambiguate two active members with the same full name.
        members=FakeApi().request('GET','/api/members')
        members.append(dict(members[0],id=99))
        with self.assertRaises(rag.EvaluationError): rag.select_members(members,'U1',[1,2,4])
    def test_existing_manifest_is_never_reprepared(self):
        # Partial or ready manifests require explicit manual recovery.
        with tempfile.TemporaryDirectory() as d:
            path=Path(d)/'f.json'; rag.save_json(path,fixtures()); api=FakeApi()
            with self.assertRaises(rag.EvaluationError): rag.prepare(api,path,'U1')
            self.assertEqual([],api.calls)
    def test_nonready_manifest_cannot_be_evaluated(self):
        # A partial setup cannot silently trigger preparation or questions.
        data=fixtures(); data['state']='SETUP_FAILED'; api=FakeApi()
        with tempfile.TemporaryDirectory() as d:
            with self.assertRaises(rag.EvaluationError): rag.evaluate(api,data,'evaluate',Path(d))
        self.assertEqual([],api.calls)
    def test_http_question_failures_are_not_retried(self):
        # Failed requests remain one attempt each and preserve missing metrics.
        api=FakeApi(); original=api.request
        def respond(method,path,body=None,admin=False):
            if path=='/api/questions':
                api.calls.append((method,path,body,admin)); raise rag.EvaluationError('HTTP_503')
            return original(method,path,body,admin)
        api.request=respond
        with tempfile.TemporaryDirectory() as d:
            rows=rag.evaluate(api,fixtures(),'evaluate',Path(d))
            self.assertEqual(10,sum(c[1]=='/api/questions' for c in api.calls))
            self.assertIsNone(rows[0]['llmCallCount'])
            self.assertEqual('HTTP_503',rows[0]['error'])
    def test_zero_scores_and_top_five_are_preserved(self):
        # Neither score sign nor configured threshold filters diagnostic output.
        api=FakeApi(); original=api.request
        def respond(method,path,body=None,admin=False):
            response=original(method,path,body,admin)
            if path=='/api/questions':
                response['retrievedDocuments']=[dict(documentId=str(i),meetingId=1,score=-i) for i in range(6)]
            return response
        api.request=respond
        with tempfile.TemporaryDirectory() as d:
            rows=rag.evaluate(api,fixtures(),'scores',Path(d))
            self.assertEqual([0,-1,-2,-3,-4],[x['score'] for x in rows[0]['retrievedDocuments']])
    def test_invalid_server_json_shape_becomes_safe_question_error(self):
        # Unexpected success bodies cannot cause a traceback or provider dump.
        api=FakeApi(); original=api.request
        def respond(method,path,body=None,admin=False):
            if path=='/api/questions': return ['provider secret']
            return original(method,path,body,admin)
        api.request=respond
        with tempfile.TemporaryDirectory() as d:
            rows=rag.evaluate(api,fixtures(),'evaluate',Path(d))
            self.assertEqual('INVALID_QUESTION_RESPONSE',rows[0]['error'])
    def test_diagnostic_contract_failure_is_visible_in_markdown(self):
        # Score-mode answer-model calls must be obvious in the printable report.
        text=rag.markdown([dict(id='Q01',label='ANSWER',status='ANSWERED',error='DIAGNOSTIC_CONTRACT_FAILED')],'scores')
        self.assertIn('DIAGNOSTIC_CONTRACT_FAILED',text)
    def test_repository_output_is_rejected(self):
        # Generated fixture text and results stay outside version control.
        with self.assertRaises(rag.EvaluationError): rag.external_path(Path(__file__).parents[1]/'results.json')
    def test_url_without_port_is_allowed_but_invalid_port_is_rejected(self):
        # URL validation accepts normal defaults while catching malformed ports.
        self.assertEqual('http://localhost',rag.validate_base_url('http://localhost/'))
        with self.assertRaises(rag.EvaluationError): rag.validate_base_url('http://localhost:invalid')




class DiagnosticEvaluationTests(unittest.TestCase):
    def setUp(self):
        # Keep offline evaluator output bounded inside Gradle.
        import contextlib, io
        self.output=contextlib.redirect_stdout(io.StringIO()); self.output.__enter__()
        self.addCleanup(self.output.__exit__,None,None,None)
    def test_include_diagnostics_uses_admin_get_without_second_question(self):
        # Diagnostics read persisted results and never repeat the answer request.
        class DiagnosticApi(FakeApi):
            def request(self, method, path, body=None, admin=False):
                if '/questions/' in path and path.endswith('/diagnostics'):
                    self.calls.append((method,path,body,admin))
                    return {'questionId':7,'diagnostics':{'reason':'QUOTE_NOT_IN_SOURCE','checks':[{'index':0,'reason':'OK'}]}}
                result=super().request(method,path,body,admin)
                if path=='/api/questions': result['questionId']=7
                return result
        with tempfile.TemporaryDirectory() as d:
            api=DiagnosticApi(); rows=rag.evaluate(api,fixtures(),'evaluate',Path(d),include_diagnostics=True)
            reads=[c for c in api.calls if c[1].endswith('/diagnostics')]
            self.assertEqual(10,len(reads)); self.assertTrue(all(c[0]=='GET' and c[3] for c in reads))
            self.assertEqual(10,len([c for c in api.calls if c[1]=='/api/questions']))
            self.assertEqual('QUOTE_NOT_IN_SOURCE',rows[0]['verificationDiagnostics']['reason'])
    def test_diagnostic_read_failure_keeps_answer_and_marks_missing(self):
        # Admin diagnostic errors must not rewrite the measured answer status.
        class FailingDiagnostics(FakeApi):
            def request(self, method, path, body=None, admin=False):
                if path.endswith('/diagnostics'): raise rag.EvaluationError('HTTP_403')
                result=super().request(method,path,body,admin)
                if path=='/api/questions': result['questionId']=7
                return result
        with tempfile.TemporaryDirectory() as d:
            rows=rag.evaluate(FailingDiagnostics(),fixtures(),'evaluate',Path(d),include_diagnostics=True)
            self.assertEqual('ANSWERED',rows[0]['status'])
            self.assertEqual('HTTP_403',rows[0]['diagnosticError'])
            self.assertIsNone(rows[0]['verificationDiagnostics'])
    def test_aggregate_counts_first_reasons_and_any_ok_questions(self):
        # A question counts once even when multiple evidence items pass.
        rows=[{'status':'UNVERIFIED','verificationDiagnostics':{'reason':'QUOTE_NOT_IN_SOURCE','checks':[{'reason':'OK'},{'reason':'OK'}]}},
              {'status':'UNVERIFIED','verificationDiagnostics':{'reason':'NOT_JSON','checks':[]}},
              {'status':'ANSWERED','verificationDiagnostics':{'reason':None,'checks':[{'reason':'OK'}]}}]
        summary=rag.summarize_diagnostics(rows)
        self.assertEqual({'QUOTE_NOT_IN_SOURCE':1,'NOT_JSON':1},summary['topLevelReasons'])
        self.assertEqual(2,summary['questionsWithAnyOk'])
        self.assertEqual(1,summary['rejectedQuestionsWithAnyOk'])
    def test_missing_old_diagnostics_are_not_treated_as_zero_ok(self):
        # Previous runs lack saved output; absence cannot prove every item failed.
        summary=rag.summarize_diagnostics([{'status':'UNVERIFIED','verificationDiagnostics':None}])
        self.assertEqual(1,summary['missingDiagnostics'])
        self.assertEqual(0,summary['collectedQuestions'])

class DiagnosticApplicabilityTests(unittest.TestCase):
    def test_no_answer_call_is_not_missing_verification(self):
        # No-hit questions intentionally have no model-output verification record.
        result=rag.summarize_diagnostics([{'status':'NOT_FOUND','llmCallCount':0,'verificationDiagnostics':None}])
        self.assertEqual(0,result['missingDiagnostics'])
        self.assertEqual(1,result['notApplicableQuestions'])

if __name__ == '__main__': unittest.main()
