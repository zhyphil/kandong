from pathlib import Path
import hashlib,json,subprocess
root=Path('/Users/haoyuzuo/Projects/KanDong')
out=Path('/private/tmp/kandong-single-language-scope')
adb=['/Users/haoyuzuo/Library/Android/sdk/platform-tools/adb','-s','emulator-5582']
def run(*args,timeout=30):return subprocess.check_output(adb+list(args),text=True,timeout=timeout)
def guard():
 assert run('emu','avd','name').splitlines()[0]=='KanDong_OCR_API37_16K'
 assert run('shell','getprop','ro.build.version.sdk').strip()=='37'
 assert run('shell','getconf','PAGE_SIZE').strip()=='16384'
def installed(package):
 path=run('shell','pm','path',package).strip()
 assert path.startswith('package:/data/app/') and '\n' not in path
 return run('shell','sha256sum',path[len('package:'):]).split()[0]
guard()
old=json.loads((root/'docs/evidence/language-context-v2/2026-09-24/apk-identity.json').read_text())
assert installed('com.kandong.modelprobe')==old['main']['sha256']
apk=root/'modelprobe/build/outputs/apk/androidTest/debug/modelprobe-debug-androidTest.apk'
sha=hashlib.sha256(apk.read_bytes()).hexdigest()
guard(); (out/'install.txt').write_text(run('install','-r','-t',str(apk),timeout=60))
assert installed('com.kandong.modelprobe.test')==sha
guard()
try:
 classes='com.kandong.modelprobe.FullPageTranslationContextTest,com.kandong.modelprobe.OnDemandTranslationTest'
 result=run('shell','am','instrument','-w','-r','-e','class',classes,
            'com.kandong.modelprobe.test/androidx.test.runner.AndroidJUnitRunner',timeout=60)
 (out/'android.txt').write_text(result)
 assert 'OK (12 tests)' in result and 'FAILURES!!!' not in result
 (out/'emulator-result.json').write_text(json.dumps(dict(avd='KanDong_OCR_API37_16K',serial='emulator-5582',
  api=37,pageSize=16384,tests=12,mainApkSha256=old['main']['sha256'],testApkSha256=sha,
  scope='single-language page context and existing on-demand state contracts; no model inference or OCR'),indent=2)+'\n')
 print('Dedicated emulator: 12 tests passed')
finally:
 guard();run('shell','am','force-stop','com.kandong.modelprobe')
