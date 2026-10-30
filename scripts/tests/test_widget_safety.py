import sys
import unittest
from pathlib import Path
from unittest.mock import patch
import xml.etree.ElementTree as ET
sys.path.insert(0,str(Path(__file__).resolve().parents[1]))
from device_widgets import require_empty_widgets,widget_ids,find_desktop_widget


class WidgetSafetyTest(unittest.TestCase):
    def test_provider_registration_is_not_a_bound_widget(self):
        require_empty_widgets('Providers:\n app.floatingfocus/TimerWidgetProvider\nWidgets:\nHosts:\n app.floatingfocus')

    def test_ff_bound_instance_blocks_data_changes(self):
        raw='Widgets:\n [0] id=2\n provider=ProviderId{cmp:ComponentInfo{com.example/Search}}\n [1] id=42\n provider=ProviderId{cmp:ComponentInfo{app.floatingfocus/app.floatingfocus.TimerWidgetProvider}}\nHosts:\n'
        self.assertEqual(widget_ids(raw),{42})
        with self.assertRaises(AssertionError):require_empty_widgets(raw)

    def test_other_providers_preserved(self):
        require_empty_widgets('Widgets:\n [0] id=2\n provider=ProviderId{cmp:ComponentInfo{com.example/Search}}\nHosts:\n')

    def test_cleanup_searches_other_pages_before_failing(self):
        class Desktop:
            width,height=720,1560
            def __init__(self):self.moves=[]
            def shell(self,*args):self.moves.append(args)
            def dump(self):
                return ET.fromstring('<hierarchy><node resource-id="app.floatingfocus:id/widget_root" /></hierarchy>' if self.moves else '<hierarchy/>')
        d=Desktop()
        with patch('device_widgets.time.sleep'):
            self.assertIsNotNone(find_desktop_widget(d))
        self.assertEqual(len(d.moves),1)
