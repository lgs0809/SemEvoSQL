import importlib.util
from pathlib import Path
import unittest
from concurrent.futures import ThreadPoolExecutor
import tempfile
import threading
import time

spec = importlib.util.spec_from_file_location('frozen', Path(__file__).with_name('run-frozen-benchmark.py'))
frozen = importlib.util.module_from_spec(spec)
spec.loader.exec_module(frozen)


class FrozenResultTest(unittest.TestCase):
    def test_admission_pause_finishes_active_job_without_starting_or_canceling_next(self):
        with tempfile.TemporaryDirectory() as directory, ThreadPoolExecutor(max_workers=1) as pool:
            pause = Path(directory)/'pause'
            started, transitions = [], []
            def task(job):
                started.append(job)
                if job == 1: pause.touch()
                return job
            iterator = frozen.admitted_jobs(pool,[1,2],lambda executor, job:executor.submit(task,job),1,pause,transitions.append)
            future, job = next(iterator)
            self.assertEqual((job,future.result()),(1,1))
            output = []
            def collect(): output.extend(iterator)
            reader = threading.Thread(target=collect)
            reader.start()
            deadline = time.monotonic()+2
            while not transitions and time.monotonic()<deadline: time.sleep(0.01)
            self.assertEqual(started,[1])
            self.assertEqual(transitions,[True])
            self.assertTrue(reader.is_alive())
            pause.unlink()
            reader.join(2)
            self.assertFalse(reader.is_alive())
            self.assertEqual(started,[1,2])
            self.assertEqual(transitions,[True,False])
            self.assertEqual([(job,future.result()) for future,job in output],[(2,2)])

    def test_expired_run_is_terminal_and_human_wait_is_not(self):
        for status in ('SUCCEEDED', 'FAILED', 'CANCELLED', 'EXPIRED'):
            self.assertTrue(frozen.run_is_terminal(status))
        for status in ('CANCEL_REQUESTED', 'WAITING_HUMAN', 'RUNNING', 'QUEUED', 'CANCELED'):
            self.assertFalse(frozen.run_is_terminal(status))

    def test_ready_version_uses_semantic_reference_and_fails_closed(self):
        deployed = {'queryReady':True,'activeVersion':{'semanticVersionId':13,'version':{'major':1,'minor':0,'patch':0}}}
        self.assertTrue(frozen.version_is_query_ready(deployed, 13))
        self.assertFalse(frozen.version_is_query_ready(deployed, 12))
        self.assertFalse(frozen.version_is_query_ready({'queryReady':False,'activeVersion':{'semanticVersionId':13}}, 13))
        self.assertFalse(frozen.version_is_query_ready({'queryReady':True,'activeVersion':{'id':13}}, 13))

    def test_multiset_keeps_duplicates_and_distinguishes_null_from_text(self):
        self.assertTrue(frozen.table_matches([{'region':None,'n':2},{'region':'华东','n':1}],
            [{'r':'华东','count':1},{'r':None,'count':2}], {'region':'r','n':'count'}))
        self.assertFalse(frozen.table_matches([{'region':'','n':2}], [{'r':None,'count':2}], {'region':'r','n':'count'}))
        self.assertFalse(frozen.table_matches([{'n':1},{'n':1}], [{'v':1}], {'n':'v'}))

    def test_numeric_truth_does_not_round_large_decimal(self):
        self.assertFalse(frozen.table_matches([{'n':'10000000000000000.01'}], [{'v':'10000000000000000.02'}], {'n':'v'}))
        self.assertTrue(frozen.table_matches([{'n':'2.00'}], [{'v':2}], {'n':'v'}))

    def test_order_and_columns_are_part_of_contract(self):
        self.assertFalse(frozen.table_matches([{'n':2},{'n':1}], [{'v':1},{'v':2}], {'n':'v'}, ordered=True))
        self.assertFalse(frozen.table_matches([{'n':1,'unexpected':2}], [{'v':1}], {'n':'v'}))

    def test_dates_normalize_only_explicit_date_outputs(self):
        self.assertTrue(frozen.table_matches([{'month':'2026-01-01T00:00:00'}], [{'day':'2026-01-01'}], {'month':'day'}, ['day']))
        self.assertFalse(frozen.table_matches([{'month':'2026-01-02'}], [{'day':'2026-01-01'}], {'month':'day'}, ['day']))


if __name__ == '__main__': unittest.main()
