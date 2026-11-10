"""Live hierarchy for non-idle notification Chronometers, test emulator only.
Uses the installed SDK and device UIAutomator; no app dependency or permission.
"""
import hashlib
import os
import re
from pathlib import Path
import subprocess
import xml.etree.ElementTree as ET
import zipfile
from source_state import ROOT

def prepare_live_dump(d):
    source=ROOT/'scripts/uiautomator/LiveDump.java';cache=ROOT/'.tools/live-ui';cache.mkdir(exist_ok=True)
    sdk=ROOT/'.tools/android-sdk';jdk=Path(os.environ.get('JAVA_HOME','C:/Program Files/JetBrains/PyCharm 2025.3.3/jbr'))
    android=sdk/'platforms/android-36/android.jar';ui=sdk/'platforms/android-36/uiautomator.jar';d8=sdk/'build-tools/35.0.0/lib/d8.jar'
    signature=hashlib.sha256(b''.join(p.read_bytes() for p in (source,android,ui,d8))).hexdigest()
    marker=cache/'source.sha256';jar=cache/'live-ui.jar'
    if not jar.exists() or not marker.exists() or marker.read_text()!=signature:
        junit=next((ROOT/'.gradle-home/caches/modules-2/files-2.1/junit/junit/4.13.2').rglob('junit-4.13.2.jar'))
        classes=cache/'classes';classes.mkdir(exist_ok=True)
        def run(args):
            result=subprocess.run(list(map(str,args)),capture_output=True,check=True)
            (cache/'compile.log').write_bytes(result.stdout+result.stderr)
        run([jdk/'bin/javac.exe','-encoding','UTF-8','-source','8','-target','8','-cp',os.pathsep.join(map(str,(android,ui,junit))),'-d',classes,source])
        with zipfile.ZipFile(cache/'classes.jar','w') as z:
            for path in classes.rglob('*.class'):z.write(path,path.relative_to(classes))
        run([jdk/'bin/java.exe','-cp',d8,'com.android.tools.r8.D8','--min-api','29','--output',cache,cache/'classes.jar'])
        with zipfile.ZipFile(jar,'w') as z:z.write(cache/'classes.dex','classes.dex')
        marker.write_text(signature)
    d.adb('push',jar,'/data/local/tmp/ff-live-ui.jar')
    # The device legacy shell sets ANDROID_DATA=/data/local/tmp.
    d.shell('mkdir','-p','/data/local/tmp/local/tmp')

def live_dump(d):
    candidates=('/data/local/tmp/local/tmp/ff-live.xml','/data/local/tmp/ff-live.xml')
    for path in candidates:d.shell('rm','-f',path)
    report=d.shell('uiautomator','runtest','/system/framework/android.test.base.jar','ff-live-ui.jar','-c','LiveDump')
    assert 'OK (1 test)' in report and 'Test run aborted' not in report,report
    match=re.search(r'FF_LIVE_PATH=([^\s]+)',report)
    assert match and match[1] in candidates,report
    raw=d.adb('shell','cat',match[1])
    tree=ET.fromstring(raw)
    assert list(tree.iter('node')),'No nodes in newly generated live hierarchy'
    (d.output/'current-live.xml').write_bytes(raw)
    return tree
