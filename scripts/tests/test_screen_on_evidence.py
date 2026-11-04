import sys
import unittest
from pathlib import Path
sys.path.insert(0,str(Path(__file__).resolve().parents[1]))
from device_screen_on_timer import effective_millis


class ScreenOnEvidenceTest(unittest.TestCase):
    def row(self):
        return dict(source='SCREEN_ON',mode='STOPWATCH',packageName='',screenSpanMillis=20000,
                    activeIntervals=[[0,3000],[12000,20000]],elapsedMillis=11000)

    def test_accepts_only_actual_active_sum_not_entire_span(self):
        self.assertEqual(11000,effective_millis(self.row()))
        row=self.row();row['elapsedMillis']=20000
        with self.assertRaises(AssertionError):effective_millis(row)

    def test_overlap_and_target_binding_cannot_be_claimed_as_valid_screen_evidence(self):
        row=self.row();row['activeIntervals']=[[0,15000],[12000,20000]]
        with self.assertRaises(AssertionError):effective_millis(row)
        row=self.row();row['packageName']='com.target'
        with self.assertRaises(AssertionError):effective_millis(row)
