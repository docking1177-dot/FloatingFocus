"""Do not mistake a stale UI snapshot or ActivityManager timeout for launch evidence."""
import sys
import unittest
import xml.etree.ElementTree as ET
from pathlib import Path
from unittest.mock import MagicMock, Mock

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
from device_regression import Device
from device_c4 import has_timer_service


class DeviceEvidenceTests(unittest.TestCase):
    def test_home_tab_is_explicit_and_requires_expected_content(self):
        device = Device('emulator-5554', MagicMock())
        device.tap = Mock()
        device.dump = Mock(return_value=ET.fromstring('<hierarchy><node content-desc="首页入口 预设" selected="true"/></hierarchy>'))
        device.home_tab('预设')
        device.tap.assert_called_once_with('首页入口 预设')
        with self.assertRaises(AssertionError):
            device.home_tab('计时')
        self.assertEqual(2, device.tap.call_count)

    def test_unknown_home_tab_never_taps_another_entry(self):
        device = Device('emulator-5554', MagicMock())
        device.tap = Mock()
        for label in ('missing', 'App'):
            with self.assertRaises(AssertionError):
                device.home_tab(label)
        device.tap.assert_not_called()

    def test_home_tab_reads_the_selectable_parent_not_its_descriptive_child(self):
        device = Device('emulator-5554', MagicMock())
        device.tap = Mock()
        device.dump = Mock(return_value=ET.fromstring('''<hierarchy>
          <node focusable="true" selected="true"><node content-desc="首页入口 预设" selected="false"/></node>
          <node focusable="true" selected="false"><node content-desc="首页入口 计时" selected="false"/></node>
        </hierarchy>'''))
        device.home_tab('预设')
        with self.assertRaises(AssertionError):
            device.home_tab('计时')

    def test_timer_service_check_ignores_global_historical_anr(self):
        for component in ('app.floatingfocus/.FloatingService', 'app.floatingfocus/app.floatingfocus.FloatingService'):
            with self.subTest(component=component):
                self.assertTrue(has_timer_service('User 0 active services:\n  * ServiceRecord{a u0 '+component+' c:app.floatingfocus}'))
                self.assertFalse(has_timer_service('Last ANR service:\nServiceRecord{a u0 '+component+' c:app.floatingfocus}\n(nothing)'))
        self.assertFalse(has_timer_service('Last ANR service:\nServiceRecord{a u0 com.android.systemui/.SystemUIService c:android}\n(nothing)'))
        self.assertFalse(has_timer_service('  * ServiceRecord{a u0 com.example/.FloatingService}\n'))

    def test_failed_dump_never_reads_stale_hierarchy(self):
        device = Device('emulator-5554', MagicMock())
        device.shell = Mock(side_effect=['', 'ERROR: could not get idle state'] * 3)
        device.adb = Mock(return_value=b'<hierarchy><node text="Floating Focus"/></hierarchy>')
        with self.assertRaises(AssertionError):
            device.dump()
        device.adb.assert_not_called()
        device.shell.assert_any_call('rm', '-f', '/sdcard/ff-regression.xml')

    def test_dump_retries_only_with_a_new_successful_hierarchy(self):
        device = Device('emulator-5554', MagicMock())
        device.shell = Mock(side_effect=['', '', '', 'UI hierarchy dumped to: /sdcard/ff-regression.xml'])
        device.adb = Mock(return_value=b'<hierarchy><node text="fresh"/></hierarchy>')
        self.assertEqual('fresh', device.dump().find('node').get('text'))
        self.assertEqual(1, device.hierarchy_retries)
        device.adb.assert_called_once()

    def test_launch_timeout_requires_correct_foreground_and_fresh_home(self):
        for foreground, text, allowed in [
            ('app.floatingfocus/.MainActivity', 'Floating Focus', True),
            ('com.android.gallery3d/.Gallery', 'Floating Focus', False),
            ('app.floatingfocus/.MainActivity', 'System UI is not responding', False),
        ]:
            device = Device('emulator-5554', MagicMock())
            device.shell = Mock(return_value='Status: timeout')
            device.foreground = Mock(return_value=foreground)
            device.dump = Mock(return_value=ET.fromstring('<hierarchy><node text="' + text + '"/></hierarchy>'))
            if allowed:
                device.start()
                self.assertEqual(1, device.launch_timeouts)
            else:
                with self.assertRaises(AssertionError):
                    device.start()
                self.assertEqual(0, device.launch_timeouts)
