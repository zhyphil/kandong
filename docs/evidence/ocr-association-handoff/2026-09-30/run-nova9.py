from pathlib import Path
import subprocess, hashlib, json, time, argparse, re
p=argparse.ArgumentParser();p.add_argument('--test-sha',required=True);p.add_argument('--expected-tests',type=int,required=True);p.add_argument('--serial',required=True);p.add_argument('--output',type=Path,required=True);a=p.parse_args()
root=Path('/Users/haoyuzuo/Projects/KanDong');adb='/Users/haoyuzuo/Library/Android/sdk/platform-tools/adb'; dev=a.serial
assert dev=='2AS0221B09001601';out=a.output;out.mkdir(parents=True,exist_ok=False)
def call(*args,timeout=30):
 r=subprocess.run([adb,'-s',dev,*args],capture_output=True,timeout=timeout)
 if r.returncode:raise RuntimeError((args,r.returncode,r.stderr.decode(errors='replace')))
 return r.stdout
assert call('shell','getprop','ro.product.model').decode().strip()=='NAM-LX9'
assert call('shell','getprop','ro.build.version.sdk').decode().strip()=='31'
assert call('shell','getconf','PAGE_SIZE').decode().strip()=='4096'
assert re.fullmatch('[0-9a-f]{64}',a.test_sha)
assert a.expected_tests in range(69,90)
meta={'device':dev,'api':call('shell','getprop','ro.build.version.sdk').decode().strip(),'packages':{}}
for pkg,relative in [('com.kandong.modelprobe','modelprobe/build/outputs/apk/debug/modelprobe-debug.apk'),('com.kandong.modelprobe.test','modelprobe/build/outputs/apk/androidTest/debug/modelprobe-debug-androidTest.apk')]:
 apk=root/relative;sha=hashlib.sha256(apk.read_bytes()).hexdigest()
 remote=call('shell','pm','path',pkg).decode().strip().removeprefix('package:')
 actual=call('shell','sha256sum',remote).decode().split()[0];assert actual==sha,(pkg,'APK mismatch; install separately first')
 expected=a.test_sha if pkg.endswith('.test') else '7f9ff2550d8618a03903eaaccdb8bf98aa3e0ca631663cd1773563b4185a6aea'
 assert sha==expected,(pkg,'not the chosen accepted build')
 meta['packages'][pkg]={'local':sha,'installed':actual}
manifest=root/'docs/fixtures/full-page-ocr-v1/manifest.json';fixture=json.loads(manifest.read_text());meta['fixtureSha256']=hashlib.sha256(manifest.read_bytes()).hexdigest()
ids=[v['id'] for v in fixture['pages']];assert len(ids)==10 and all(re.fullmatch('[a-z0-9-]+',v) for v in ids)
extras=['full-page-ocr-lifecycle.json','full-page-ocr-session-failures.json','end-to-end-ocr-probe-report.json']
names=extras+['full-page-ocr-summary.json']+['full-page-ocr-'+model+'-'+id+'.json' for model in ['ch','latin'] for id in ids]
meta.update(model='NAM-LX9',pageSize=4096,expectedTests=a.expected_tests)
(out/'environment.json').write_text(json.dumps(meta,indent=2)+'\n')
call('shell','run-as','com.kandong.modelprobe','rm','-f',*['files/'+name for name in names])
classes='com.kandong.modelprobe.FullPageOcrPublicationTest,com.kandong.modelprobe.FullPageOcrAssociationTest,com.kandong.modelprobe.FullPageOcrContractTest,com.kandong.modelprobe.FullPageOcrProbeTest,com.kandong.modelprobe.FullPageOcrLifecycleProbeTest,com.kandong.modelprobe.FullPageStripPlannerTest,com.kandong.modelprobe.SingleFrameStripInputTest,com.kandong.modelprobe.AndroidImageStripProbeTest,com.kandong.modelprobe.EndToEndOcrProbeTest'
with (out/'instrumentation.txt').open('wb') as log:
 proc=subprocess.Popen([adb,'-s',dev,'shell','am','instrument','-w','-r','-e','class',classes,'com.kandong.modelprobe.test/androidx.test.runner.AndroidJUnitRunner'],stdout=log,stderr=subprocess.STDOUT)
 try:
  time.sleep(2)
  if proc.poll() is None:
   # Own idle fixture Activity only; avoids OEM freezing of the instrumented process.
   (out/'foreground.txt').write_bytes(call('shell','am','start','-n','com.kandong.modelprobe/.ModelProbeActivity'))
  proc.wait(timeout=300)
 finally:
  if proc.poll() is None:
   call('shell','am','force-stop','com.kandong.modelprobe');proc.terminate();proc.wait(timeout=10)
summary=json.loads(call('shell','run-as','com.kandong.modelprobe','cat','files/full-page-ocr-summary.json'))
(out/'full-page-ocr-summary.json').write_text(json.dumps(summary,ensure_ascii=False,indent=2)+'\n')
for name in summary.get('reports',[]):
 assert name in names and name!='full-page-ocr-summary.json'
 data=call('shell','run-as','com.kandong.modelprobe','cat','files/'+name)
 assert len(data)<=256*1024
 report=json.loads(data);assert report['runId']==summary['runId']
 assert report['fixtureSha256']==meta['fixtureSha256']
 (out/name).write_text(json.dumps(report,ensure_ascii=False,indent=2)+'\n')
for name in extras:
 data=call('shell','run-as','com.kandong.modelprobe','cat','files/'+name)
 d=json.loads(data);(out/name).write_text(json.dumps(d,ensure_ascii=False,indent=2)+'\n')
 assert d.get('passed') is True,(name,'not passed')
call('shell','am','force-stop','com.kandong.modelprobe')
print(json.dumps({k:summary.get(k) for k in ['runId','technicalPassed','technicalPassedPages','expectedPages','detectorInvocations','recognitionInvocations','elapsedMillis']},ensure_ascii=False))
assert summary.get('technicalPassed') is True,'Inspect saved full-page reports'
assert 'FAILURES!!!' not in (out/'instrumentation.txt').read_text()
assert f'OK ({a.expected_tests} tests)' in (out/'instrumentation.txt').read_text()
