from pathlib import Path
import subprocess, json, time, argparse

# Frozen OCR baseline only. Refuse phones and unrelated AVDs before any mutation.
p=argparse.ArgumentParser(description="Run the frozen OCR baseline on the dedicated KanDong API37 emulator")
p.add_argument("--output", type=Path, required=True)
a=p.parse_args()
root=Path(__file__).resolve().parents[1]
out=a.output.resolve()
assert not out.exists(), 'use a fresh output directory; never overwrite evidence'
assert root not in out.parents, 'reports must first be written outside the checkout'
out.mkdir(parents=True)
adb=['/Users/haoyuzuo/Library/Android/sdk/platform-tools/adb','-s','emulator-5582']
def run(*args):return subprocess.run(adb+list(args),capture_output=True,text=True,timeout=30)
def installed_hash(package):
 loc=run('shell','pm','path',package).stdout.strip()
 assert loc.startswith('package:/data/app/') and '\n' not in loc
 return run('shell','sha256sum',loc.removeprefix('package:')).stdout.split()[0]
assert run('emu','avd','name').stdout.splitlines()[0]=='KanDong_OCR_API37_16K'
assert run('shell','getconf','PAGESIZE').stdout.strip()=='16384'
identity=json.loads((root/'docs/evidence/candidate-selection-v2/2026-09-24/apk-identity.json').read_bytes())
assert installed_hash('com.kandong.modelprobe')==identity['mainApkSha256']
assert installed_hash('com.kandong.modelprobe.test')==identity['testApkSha256']
assert run('shell','getprop','ro.product.model').stdout.strip()=='sdk_gphone16k_arm64'
assert run('shell','getprop','ro.build.version.sdk').stdout.strip()=='37'
(out/'installed.json').write_text(json.dumps(identity,indent=2)+'\n')
groups=[
 ('regression','com.kandong.modelprobe.AndroidDetectorProbeTest,com.kandong.modelprobe.OriginalImageProbeTest',7,
  {'detector':'detector-probe-report.json','original':'original-image-probe-report.json'}),
 ('geometry','com.kandong.modelprobe.DetectorGeometryProbeTest',1,{'geometry':'detector-geometry-probe-report.json'}),
 ('polygon','com.kandong.modelprobe.PolygonOffsetProbeTest',1,{'polygon':'polygon-offset-probe-report.json'}),
 ('boxes','com.kandong.modelprobe.BoxPipelineProbeTest',1,{'boxes':'box-pipeline-probe-report.json'}),
 ('recognition','com.kandong.modelprobe.CropRecognitionProbeTest',1,{'recognition':'crop-recognition-probe-report.json'}),
 ('e2e','com.kandong.modelprobe.EndToEndOcrProbeTest',2,{'e2e':'end-to-end-ocr-probe-report.json'})]
# Run the changed integration first, so a failure does not repeat expensive unchanged regressions.
groups=[groups[-1],*groups[:-1],('selection','com.kandong.modelprobe.CandidateSelectionProbeTest',1,{'selection':'candidate-selection-probe-report.json'})]
seen=set()
try:
 for n in [1,2]:
  for label,classes,count,names in groups:
   run('shell','am','force-stop','com.kandong.modelprobe')
   assert run('shell','run-as','com.kandong.modelprobe','rm','-f',*['files/'+v for v in names.values()]).returncode==0
   path=out/f'{label}-{n}-instrumentation.txt'
   extra=[]
   if label=='selection':
    raw_e2e=run('shell','run-as','com.kandong.modelprobe','cat','files/end-to-end-ocr-probe-report.json')
    import hashlib
    parsed=json.loads(raw_e2e.stdout)
    assert parsed['runId']==json.loads((out/f'e2e-{n}-report.json').read_bytes())['runId'] and parsed['passed']
    extra=['-e','e2eRunId',parsed['runId'],'-e','e2eReportSha256',hashlib.sha256(raw_e2e.stdout.encode()).hexdigest()]
   with path.open('w') as stream:
    result=subprocess.Popen(adb+['shell','am','instrument','-w','-r',*extra,'-e','class',classes,
      'com.kandong.modelprobe.test/androidx.test.runner.AndroidJUnitRunner'],stdout=stream,stderr=subprocess.STDOUT)
    if label in ('polygon','boxes','recognition','e2e'):
     deadline=time.monotonic()+30
     while True:
      present=run('shell','run-as','com.kandong.modelprobe','test','-s','files/'+next(iter(names.values())))
      if present.returncode==0:break
      assert result.poll() is None and time.monotonic()<deadline,'new report did not start'
      time.sleep(.5)
     launch=run('shell','am','start','-n','com.kandong.modelprobe/.ModelProbeActivity')
     (out/f'foreground-{label}-{n}.txt').write_text(launch.stdout+launch.stderr)
     assert launch.returncode==0 and 'Starting:' in launch.stdout
    result.wait(timeout=180)
   for kind,name in names.items():
    raw=run('shell','run-as','com.kandong.modelprobe','cat','files/'+name);assert raw.returncode==0
    j=json.loads(raw.stdout);assert j['runId'] not in seen;seen.add(j['runId'])
    (out/f'{kind}-{n}-report.json').write_text(json.dumps(j,ensure_ascii=False,indent=2)+'\n')
    if kind=='polygon':
     assert j['fixtureManifestSha256']=='e4415247a09d954501e0c5f87a2e5a6e35cdeef9ffc5ff5349aaf0a1a0f9e8ea'
     assert j['sdk']==37 and j['model']=='sdk_gphone16k_arm64'
     print(n,label,'passed',j.get('passed'),'cases',j.get('completedCaseRows'),'guards',j.get('completedGuardRows'),
       'frozen',j.get('frozenKernel'),'computed',j.get('computedKernel'),'distance',j.get('distance'),flush=True)
    elif kind=='boxes':
     assert j['fixtureSha256']=='91cc2279377e8809ba3898bd31784f79df307dc0ad816a826fac139de7184118'
     assert j['api']==37 and j['device']=='sdk_gphone16k_arm64'
     print(n,kind,'passed',j.get('passed'),'cases',len(j.get('cases',[])),'guards',len(j.get('guards',[])),'totals',j.get('totals'),flush=True)
    elif kind=='recognition':
     print(n,kind,'status',j.get('status'),'passed',j.get('passed'),'totals',j.get('totals'),'cases',len(j.get('cases',[])),'guards',len(j.get('guards',[])),flush=True)
    else:print(n,kind,'report saved', 'passed',j.get('passed'),flush=True)
   text=path.read_text();print(label,n,text[-200:],flush=True)
   assert result.returncode==0 and f'OK ({count} test' in text,'failure saved; not accepted'
finally:
 run('shell','am','force-stop','com.kandong.modelprobe')
 if 'result' in locals() and result.poll() is None:
  result.terminate()
  try:result.wait(timeout=5)
  except subprocess.TimeoutExpired:result.kill();result.wait()
