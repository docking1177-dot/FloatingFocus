"""Result organization must never manufacture functional passes or build identity."""
import json
import sys
import tempfile
import unittest
from pathlib import Path
sys.path.insert(0,str(Path(__file__).resolve().parents[1]))
from test_catalog import classify,check_executions,device_executions,append_unique,junit_execution,flatten,case_view,add_manual_batch

TARGET={'Debug':{'sha256':'a'*64,'fingerprint':'f'*64}}


class TestCatalogTests(unittest.TestCase):
    def case(self):
        return dict(id='C',module='M',scenario='S',preconditions='P',steps='do',expected='assert',priority='P0',
            builds=['Debug','Release'],environments=['模拟器'],recommended_path='ADB',entry_points=['source'],scope='单例',manual_tier='自动化优先')

    def batch(self,id,status,day,prior=(),source=None):
        return dict(id=id,time=f'2026-10-{day:02d}T00:00:00Z',time_note='UTC',source_type='device',
            source=source if source is not None else {'fingerprint':'f'*64},variant='Debug',apk_sha256='a'*64,
            environment='模拟器',method='自动化',device='emulator-5556',android='16',launcher='Launcher3',evidence='original.json',
            executions=[dict(id=id+'-row',case_id='C',status=status,scope='单例',actual='actual',retest_of=list(prior))])

    def test_missing_identity_never_becomes_current(self):
        r=classify({'variant':'Debug','source':{},'apk_sha256':None,'environment':'真机'},TARGET)
        self.assertEqual(r[0],'身份未核验')

    def test_matching_apk_without_source_identity_is_not_enough(self):
        self.assertEqual(classify({'variant':'Debug','source':{},'apk_sha256':'a'*64},TARGET)[0],'身份未核验')

    def test_exact_identity_and_history_are_separate_from_result(self):
        b={'variant':'Debug','source':{'fingerprint':'f'*64},'apk_sha256':'a'*64}
        self.assertEqual(classify(b,TARGET)[0],'当前构建')
        b['apk_sha256']='b'*64
        self.assertEqual(classify(b,TARGET)[0],'历史构建')

    def test_summary_only_generates_separate_build_lint_and_unit_gates(self):
        raw={'status':'passed','mode':'Full','tasks':['assembleDebug','testDebugUnitTest','lintDebug'],
             'tests':167,'failures':0,'errors':0,'skipped':0,'lintErrors':0,'lintWarnings':0}
        rows=check_executions(raw)
        self.assertEqual({r['case_id'] for r in rows},{'FF-GATE-BUILD','FF-GATE-UNIT','FF-GATE-LINT'})
        self.assertTrue(all(r['status']=='通过' for r in rows))
        self.assertTrue(all(r['scope']=='汇总' for r in rows))

    def test_failed_compilation_does_not_turn_old_test_counts_into_a_pass(self):
        rows=check_executions({'status':'failed','mode':'Full','tasks':['assembleDebug','testDebugUnitTest'],
                              'tests':167,'failures':0,'errors':0,'error':'Compilation failed'})
        self.assertFalse(any(r['case_id']=='FF-GATE-UNIT' and r['status']=='通过' for r in rows))
        self.assertEqual(rows[0]['case_id'],'FF-GATE-CHECK')

    def test_missing_junit_counters_are_not_assumed_zero(self):
        rows=check_executions({'status':'passed','tasks':['testDebugUnitTest'],'tests':1})
        self.assertEqual(rows[0]['status'],'阻塞')

    def test_device_partial_pass_does_not_guess_failed_suite_or_expand_to_cases(self):
        rows=device_executions({'status':'failed','passedSuites':['smoke'],'error':'Source changed','details':{}})
        self.assertEqual({r['case_id'] for r in rows},{'FF-SUITE-SMOKE','FF-GATE-DEVICE'})
        self.assertEqual(next(r for r in rows if r['case_id']=='FF-GATE-DEVICE')['status'],'失败')

    def test_screenshot_flag_does_not_certify_readability_or_real_phone(self):
        rows=device_executions({'status':'passed','passedSuites':['widgets'],
                               'details':{'widgets':{'themeAndFontScreenshots':True}}})
        self.assertFalse(any(r['case_id'].startswith('FF-PHONE') or r['case_id']=='FF-VISUAL-WIDGET' for r in rows))

    def test_junit_skipped_is_blocked_not_automatically_not_applicable(self):
        r=junit_execution('ExampleTest',{'tests':'2','failures':'0','errors':'0','skipped':'1'})
        self.assertEqual(r['status'],'阻塞')

    def test_append_is_idempotent_and_keeps_original_bytes(self):
        with tempfile.TemporaryDirectory() as temp:
            path=Path(temp)/'runs.jsonl'
            append_unique(path,[{'id':'one','status':'失败'}])
            original=path.read_bytes()
            self.assertEqual(append_unique(path,[{'id':'one','status':'通过'}]),0)
            self.assertEqual(path.read_bytes(),original)
            append_unique(path,[{'id':'two','status':'通过'}])
            self.assertTrue(path.read_bytes().startswith(original))
            self.assertEqual(len(path.read_text().splitlines()),2)

    def test_blocked_retest_and_unrelated_pass_cannot_hide_failure(self):
        case=self.case();batches=[self.batch('fail','失败',1),self.batch('blocked','阻塞',2,['fail-row']),self.batch('pass','通过',3)]
        rows=flatten([case],batches,TARGET,[])
        view=case_view(case,rows,{'builds':{'Release':{'audio_local':False}}})
        self.assertEqual(view['auto_current'],'失败')
        self.assertEqual(rows[0]['resolved_by'],[])

    def test_explicit_current_pass_resolves_but_preserves_failed_record(self):
        rows=flatten([self.case()],[self.batch('fail','失败',1),self.batch('pass','通过',2,['fail-row'])],TARGET,[])
        self.assertEqual(rows[0]['status'],'失败')
        self.assertEqual(rows[0]['resolved_by'],['pass-row'])

    def test_identity_unknown_retest_cannot_resolve_failure(self):
        rows=flatten([self.case()],[self.batch('fail','失败',1),self.batch('pass','通过',2,['fail-row'],{})],TARGET,[])
        self.assertEqual(rows[0]['resolved_by'],[])

    def test_retest_in_other_environment_cannot_resolve_failure(self):
        a=self.batch('fail','失败',1);b=self.batch('pass','通过',2,['fail-row']);b['environment']='真机'
        self.assertEqual(flatten([self.case()],[a,b],TARGET,[])[0]['resolved_by'],[])

    def test_invalid_candidate_does_not_poison_manual_ledger(self):
        with tempfile.TemporaryDirectory() as temp:
            p=Path(temp)/'manual.jsonl';base=self.batch('base','通过',1)
            append_unique(p,[base]);original=p.read_bytes();catalog={'cases':[self.case()]}
            for field in ('scope','bad_reference','duplicate_global_id'):
                bad=self.batch('bad','通过',2)
                other=[]
                if field=='scope':bad['executions'][0].pop('scope')
                if field=='bad_reference':bad['executions'][0]['retest_of']=['missing']
                if field=='duplicate_global_id':other=[bad]
                with self.assertRaises(ValueError):add_manual_batch(p,bad,catalog,other)
                self.assertEqual(p.read_bytes(),original)
