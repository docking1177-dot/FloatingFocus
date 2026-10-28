import sys
import unittest
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
from device_preset_shortcuts import require_empty_pins, PINNED_MATCH_FLAGS


class PinnedShortcutSafetyTests(unittest.TestCase):
    def test_queries_pinned_not_dynamic_android_api_flags(self):
        self.assertEqual('4', PINNED_MATCH_FLAGS)
    def test_existing_system_pins_refuse_before_test_data_can_replace_presets(self):
        with self.assertRaisesRegex(AssertionError, 'isolated test AVD'):
            require_empty_pins('ShortcutInfo {id=ff.preset.user-original, flags=Pin}')

    def test_empty_pinned_state_is_safe_for_dedicated_avd(self):
        require_empty_pins('[]\n')


if __name__ == '__main__':
    unittest.main()
