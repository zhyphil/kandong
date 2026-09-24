import hashlib,json,subprocess
from pathlib import Path
root=Path('/Users/haoyuzuo/Projects/KanDong')
out=Path('/private/tmp/kandong-online-phase')
adb=['/Users/haoyuzuo/Library/Android/sdk/platform-tools/adb','-s','emulator-5582']
def run(*args,timeout=30):return subprocess.check_output(adb+list(args),text=True,timeout=timeout)
assert run('emu','avd','name').splitlines()[0]=='KanDong_OCR_API37_16K'
assert run('shell','getprop','ro.build.version.sdk').strip()=='37'
assert run('shell','getconf','PAGE_SIZE').strip()=='16384'
expected_main='7f9ff2550d8618a03903eaaccdb8bf98aa3e0ca631663cd1773563b4185a6aea'
def installed_sha(package):
 p=run('shell','pm','path',package).strip()
 assert p.startswith('package:/data/app/') and '\n' not in p
 return run('shell','sha256sum',p.removeprefix('package:')).split()[0]
assert installed_sha('com.kandong.modelprobe')==expected_main
apk=root/'modelprobe/build/outputs/apk/androidTest/debug/modelprobe-debug-androidTest.apk'
test_sha=hashlib.sha256(apk.read_bytes()).hexdigest()
assert 'Success' in run('install','-r','-t',str(apk),timeout=60)
assert installed_sha('com.kandong.modelprobe.test')==test_sha
(out/'apk-identity.json').write_text(json.dumps({'main':expected_main,'test':test_sha,'avd':'KanDong_OCR_API37_16K','sdk':37,'pageSize':16384},indent=2)+'\n')
classes=','.join('com.kandong.modelprobe.'+s for s in ['TranslationRoutingTest','OnDemandTranslationTest','FullPageTranslationContextTest'])
try:
 text=run('shell','am','instrument','-w','-r','-e','class',classes,'com.kandong.modelprobe.test/androidx.test.runner.AndroidJUnitRunner',timeout=120)
 (out/'emulator.txt').write_text(text)
 assert 'OK (20 tests)' in text and 'FAILURES!!!' not in text
 print('Dedicated emulator: 20 synthetic routing/context tests passed')
finally:run('shell','am','force-stop','com.kandong.modelprobe')
