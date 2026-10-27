import json
from pathlib import Path
import unittest
import wave

ROOT = Path(__file__).resolve().parents[2]


class C4AssetsTests(unittest.TestCase):
    def test_audio_lengths_match_led_profile_and_single_stream_spacing(self):
        profile = json.loads((ROOT / 'app/src/main/assets/c4_profile.json').read_text(encoding='utf-8'))
        for name, millis in [('c4_beep', profile['normalPulseMillis']), ('c4_final', profile['finalPulseMillis']), ('c4_explosion', 900)]:
            with wave.open(str(ROOT / 'app/src/main/res/raw' / (name + '.wav'))) as file:
                self.assertEqual(1, file.getnchannels())
                self.assertEqual(2, file.getsampwidth())
                self.assertEqual(millis, file.getnframes() * 1000 / file.getframerate())
            if name != 'c4_explosion':
                self.assertLessEqual(millis, profile['minimumIntervalMillis'])
