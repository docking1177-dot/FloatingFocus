import sys
import unittest
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
from device_overtime_reminders import reminder_events, snooze_events, layout_bounds


class OvertimeEvidenceTest(unittest.TestCase):
    def test_only_complete_actual_reminder_lines_are_imported(self):
        log = ('11-04 D FloatingFocus: Overtime reminder round=1 effects=[OUTLINE] level=LIGHT '
               'overtime=51002 elapsed=52002 planned=1000 next=160000\n'
               'D FloatingFocus: Overtime reminder round=2 effects=[] level=STANDARD '
               'overtime=181000 elapsed=182000 planned=1000 next=240000\n'
               'Overtime schedule next=160000 now=108000 exact=false count=0\n'
               'Overtime reminder round=3 effects=[SOUND]')
        rows = reminder_events(log)
        self.assertEqual(2, len(rows))
        self.assertEqual(['OUTLINE'], rows[0]['effects'])
        self.assertEqual(51002, rows[0]['overtime'])
        self.assertEqual([], rows[1]['effects'])
        self.assertEqual('STANDARD', rows[1]['level'])

    def test_confirmation_fields_do_not_invent_a_timer_extension(self):
        log = ('D FloatingFocus: Overtime snoozed seconds=180 count=1 timerStart=42 elapsed=52000 planned=1000\n'
               'D FloatingFocus: OverTime unrelated\n'
               'D FloatingFocus: OverTime snoozed seconds=0')
        self.assertEqual([dict(seconds=180, count=1, timerStart=42, elapsed=52000, planned=1000)], snooze_events(log))

    def test_short_content_shift_cannot_hide_behind_stable_window_samples(self):
        log = ('Overlay layout t=10 role=time bounds=278.0,28.0,708.0,156.0 totalMillis=1000\n'
               'Overlay layout t=11 role=controls bounds=96.0,156.0,692.0,288.0\n'
               'Overlay layout t=20 role=time bounds=283.0,28.0,713.0,169.0 totalMillis=2000\n'
               'Overlay layout t=21 role=controls bounds=101.0,169.0,697.0,301.0\n'
               'Overlay layout t=22 role=preview-time bounds=0,0,1,1')
        bounds = layout_bounds(log, 10, {'time', 'controls'})
        self.assertEqual(2, len(bounds['time']))
        self.assertEqual(2, len(bounds['controls']))
        self.assertEqual([(283.0, 28.0, 713.0, 169.0)], layout_bounds(log, 20, {'time'})['time'])


if __name__ == '__main__':
    unittest.main()
