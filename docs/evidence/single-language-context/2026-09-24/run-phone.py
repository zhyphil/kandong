from pathlib import Path
import hashlib,json,os,subprocess
root=Path('/Users/haoyuzuo/Projects/KanDong');out=Path('/private/tmp/kandong-single-language-scope')
adb=['/Users/haoyuzuo/Library/Android/sdk/platform-tools/adb','-s',os.environ['KANDONG_PHONE_SERIAL']]
def run(*args,timeout=30):return subprocess.check_output(adb+list(args),text=True,timeout=timeout).strip()
def guard():
 assert run('shell','getprop','ro.product.model').replace('_','-')=='LIO-AN00'
 assert run('shell','getprop','ro.build.version.sdk')=='31'
 assert run('shell','getconf','PAGE_SIZE')=='4096'
def installed(package):
 path=run('shell','pm','path',package)
 assert path.startswith('package:/data/app/') and '\n' not in path
 return run('shell','sha256sum',path[len('package:'):]).split()[0]
guard();identity=json.loads((out/'emulator-result.json').read_text())
assert installed('com.kandong.modelprobe')==identity['mainApkSha256']
assert installed('com.kandong.modelprobe.test')==identity['testApkSha256']
try:
 guard()
 classes='com.kandong.modelprobe.FullPageTranslationContextTest,com.kandong.modelprobe.OnDemandTranslationTest'
 result=run('shell','am','instrument','-w','-r','-e','class',classes,
            'com.kandong.modelprobe.test/androidx.test.runner.AndroidJUnitRunner',timeout=60)
 (out/'phone-android.txt').write_text(result+'\n')
 assert 'OK (12 tests)' in result and 'FAILURES!!!' not in result
 report=dict(model='LIO-AN00',api=31,release='12',pageSize=4096,tests=12,
  mainApkSha256=identity['mainApkSha256'],testApkSha256=identity['testApkSha256'],
  scope='synthetic single-language context transport and on-demand lifecycle; no OCR, translation model or screen capture')
 (out/'phone-result.json').write_text(json.dumps(report,indent=2)+'\n')
 print('Target Huawei: 12 tests passed')
finally:
 guard();run('shell','am','force-stop','com.kandong.modelprobe')
