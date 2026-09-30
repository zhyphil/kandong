from pathlib import Path
import argparse,hashlib,json,re,subprocess
p=argparse.ArgumentParser();p.add_argument('--main-sha',required=True);p.add_argument('--test-sha',required=True);p.add_argument('--output',type=Path,required=True);a=p.parse_args()
r=Path('/Users/haoyuzuo/Projects/KanDong');adb='/Users/haoyuzuo/Library/Android/sdk/platform-tools/adb';dev='emulator-5582'
def call(*parts,timeout=30):
 c=subprocess.run([adb,'-s',dev,*parts],capture_output=True,timeout=timeout)
 if c.returncode:raise RuntimeError((parts,c.returncode,c.stderr.decode(errors='replace')))
 return c.stdout
assert call('emu','avd','name').decode().splitlines()[0]=='KanDong_OCR_API37_16K'
assert call('shell','getprop','ro.build.version.sdk').strip()==b'37'
assert call('shell','getconf','PAGE_SIZE').strip()==b'16384'
assert all(re.fullmatch('[0-9a-f]{64}',x) for x in [a.main_sha,a.test_sha])
out=a.output;out.mkdir(parents=True,exist_ok=False)
meta={'avd':'KanDong_OCR_API37_16K','api':37,'pageSize':16384,'packages':{}}
for pkg,relative,expected in [('com.kandong.modelprobe','modelprobe/build/outputs/apk/debug/modelprobe-debug.apk',a.main_sha),('com.kandong.modelprobe.test','modelprobe/build/outputs/apk/androidTest/debug/modelprobe-debug-androidTest.apk',a.test_sha)]:
 sha=hashlib.sha256((r/relative).read_bytes()).hexdigest();remote=call('shell','pm','path',pkg).decode().strip().removeprefix('package:')
 actual=call('shell','sha256sum',remote).decode().split()[0];assert sha==actual==expected
 meta['packages'][pkg]={'local':sha,'installed':actual}
(out/'environment.json').write_text(json.dumps(meta,indent=2)+'\n')
classes=','.join('com.kandong.modelprobe.'+n for n in ['RegionVisualSessionTest','RegionVisualLabUiTest','FullPageRegionControllerTest','FullPageOcrPublicationTest'])
with (out/'instrumentation.txt').open('wb') as log:
 proc=subprocess.Popen([adb,'-s',dev,'shell','am','instrument','-w','-r','-e','class',classes,'com.kandong.modelprobe.test/androidx.test.runner.AndroidJUnitRunner'],stdout=log,stderr=subprocess.STDOUT)
 try:proc.wait(timeout=240)
 finally:
  if proc.poll() is None:
   call('shell','am','force-stop','com.kandong.modelprobe');proc.terminate();proc.wait(timeout=10)
call('shell','am','force-stop','com.kandong.modelprobe')
log=(out/'instrumentation.txt').read_text();assert 'FAILURES!!!' not in log and proc.returncode==0
m=re.search(r'OK \((\d+) tests\)',log);assert m and int(m[1])==59,log[-2000:]
print(json.dumps({'tests':int(m[1]),'scope':'visual UI plus session, region controller and publication regressions','output':str(out)}))
