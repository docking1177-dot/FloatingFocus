"""Do not mistake a stale UI snapshot or ActivityManager timeout for launch evidence."""
import sys
import unittest
import xml.etree.ElementTree as ET
from pathlib import Path
from unittest.mock import MagicMock, Mock

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
from device_regression import Device


class DeviceEvidenceTests(unittest.TestCase):
    def test_failed_dump_never_reads_stale_hierarchy(self):
        device = Device('emulator-5554', MagicMock())
        device.shell = Mock(side_effect=['', 'ERROR: could not get idle state'])
        device.adb = Mock(return_value=b'<hierarchy><node text="Floating Focus"/></hierarchy>')
        with self.assertRaises(AssertionError):
            device.dump()
        device.adb.assert_not_called()
        device.shell.assert_any_call('rm', '-f', '/sdcard/ff-regression.xml')

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
