from pathlib import Path
import argparse,hashlib,json,re,subprocess,time
p=argparse.ArgumentParser();p.add_argument('--test-sha',required=True);p.add_argument('--expected-tests',type=int,required=True);p.add_argument('--output',type=Path,required=True);a=p.parse_args()
root=Path('/Users/haoyuzuo/Projects/KanDong');adb='/Users/haoyuzuo/Library/Android/sdk/platform-tools/adb';dev='emulator-5582'
def call(*args,timeout=30):
 r=subprocess.run([adb,'-s',dev,*args],capture_output=True,timeout=timeout)
 if r.returncode:raise RuntimeError((args,r.returncode,r.stderr.decode(errors='replace')))
 return r.stdout
assert call('emu','avd','name').decode().splitlines()[0]=='KanDong_OCR_API37_16K'
assert call('shell','getprop','ro.build.version.sdk').decode().strip()=='37'
assert call('shell','getconf','PAGE_SIZE').decode().strip()=='16384'
assert re.fullmatch('[0-9a-f]{64}',a.test_sha) and 70<a.expected_tests<150
out=a.output;out.mkdir(parents=True,exist_ok=False)
meta={'avd':'KanDong_OCR_API37_16K','api':37,'pageSize':16384,'expectedTests':a.expected_tests,'packages':{}}
for pkg,relative in [('com.kandong.modelprobe','modelprobe/build/outputs/apk/debug/modelprobe-debug.apk'),('com.kandong.modelprobe.test','modelprobe/build/outputs/apk/androidTest/debug/modelprobe-debug-androidTest.apk')]:
 sha=hashlib.sha256((root/relative).read_bytes()).hexdigest();remote=call('shell','pm','path',pkg).decode().strip().removeprefix('package:')
 actual=call('shell','sha256sum',remote).decode().split()[0]
 assert sha==actual,(pkg,'installed mismatch')
 expected=a.test_sha if pkg.endswith('.test') else '7f9ff2550d8618a03903eaaccdb8bf98aa3e0ca631663cd1773563b4185a6aea';assert sha==expected
 meta['packages'][pkg]={'local':sha,'installed':actual}
(out/'environment.json').write_text(json.dumps(meta,indent=2)+'\n')
names=['full-page-region-probe.json','full-page-ocr-lifecycle.json','full-page-ocr-session-failures.json']
call('shell','run-as','com.kandong.modelprobe','rm','-f',*['files/'+n for n in names])
classes=','.join('com.kandong.modelprobe.'+n for n in ['FullPageRegionControllerTest','FullPageRegionProbeTest','FullPageOcrPublicationTest','FullPageOcrAssociationTest','FullPageOcrContractTest','FullPageStripPlannerTest','SingleFrameStripInputTest','AndroidImageStripProbeTest','FullPageOcrLifecycleProbeTest'])
with (out/'instrumentation.txt').open('wb') as log:
 proc=subprocess.Popen([adb,'-s',dev,'shell','am','instrument','-w','-r','-e','class',classes,'com.kandong.modelprobe.test/androidx.test.runner.AndroidJUnitRunner'],stdout=log,stderr=subprocess.STDOUT)
 try:
  time.sleep(2)
  if proc.poll() is None:(out/'foreground.txt').write_bytes(call('shell','am','start','-n','com.kandong.modelprobe/.ModelProbeActivity'))
  proc.wait(timeout=240)
 finally:
  if proc.poll() is None:
   call('shell','am','force-stop','com.kandong.modelprobe');proc.terminate();proc.wait(timeout=10)
for name in names:
 data=call('shell','run-as','com.kandong.modelprobe','cat','files/'+name);assert len(data)<=256*1024
 report=json.loads(data);(out/name).write_text(json.dumps(report,ensure_ascii=False,indent=2)+'\n')
call('shell','am','force-stop','com.kandong.modelprobe')
for name in names:assert json.loads((out/name).read_text()).get('passed') is True,(name,'failed')
log=(out/'instrumentation.txt').read_text();assert 'FAILURES!!!' not in log
assert f'OK ({a.expected_tests} tests)' in log,log[-1000:]
print(json.dumps({'passedTests':a.expected_tests,'reports':names}))
