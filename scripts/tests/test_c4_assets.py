import re
from pathlib import Path
import unittest

ROOT = Path(__file__).resolve().parents[2]


class C4AssetsTests(unittest.TestCase):
    def test_full_timeline_keeps_every_numbered_event_and_local_audio_is_debug_only(self):
        lines = (ROOT/'app/src/main/assets/c4_timeline.lrc').read_text(encoding='utf-8').splitlines()
        self.assertEqual(136, len(lines))
        self.assertTrue(all(re.fullmatch(r'\[\d{2}:\d{2}\.\d{2}\].+', line) for line in lines))
        self.assertEqual(88, sum('滴' in line for line in lines))
        self.assertEqual(11, sum('快速连响' in line for line in lines))
        build = (ROOT/'app/build.gradle.kts').read_text(encoding='utf-8')
        self.assertIn('sourceSets.getByName("debug").assets.srcDir(rootProject.file(".tools/c4-local-assets"))', build)
        self.assertFalse(list((ROOT/'app/src/main').rglob('*.m4a')))
        self.assertFalse(list((ROOT/'app/src/release').rglob('*.m4a')))
