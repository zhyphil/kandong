from pathlib import Path
import hashlib, json, os, subprocess, zipfile, xml.etree.ElementTree as ET
root=Path('/Users/haoyuzuo/Projects/KanDong')
sdk=Path('/Users/haoyuzuo/Library/Android/sdk')
env=os.environ.copy();env['JAVA_HOME']='/Users/haoyuzuo/Library/Java/JavaVirtualMachines/temurin-17.0.20.1/Contents/Home'
def sha(b):return hashlib.sha256(b).hexdigest()
def run(args):return subprocess.check_output(list(map(str,args)),env=env,text=True,stderr=subprocess.STDOUT)
main=root/'modelprobe/build/outputs/apk/debug/modelprobe-debug.apk'
test=root/'modelprobe/build/outputs/apk/androidTest/debug/modelprobe-debug-androidTest.apk'
assert sha(main.read_bytes())=='7f9ff2550d8618a03903eaaccdb8bf98aa3e0ca631663cd1773563b4185a6aea'
meta={'mainApkSha256':sha(main.read_bytes()),'testApkSha256':sha(test.read_bytes()),'testApkBytes':test.stat().st_size}
for name, apk in [('main',main),('test',test)]:
 run([sdk/'build-tools/35.0.0/apksigner','verify','--verbose',apk])
 run([sdk/'build-tools/35.0.0/zipalign','-c','-P','16','4',apk])
 xml=run([sdk/'cmdline-tools/latest/bin/apkanalyzer','manifest','print',apk]);m=ET.fromstring(xml)
 assert not [e for e in m if e.tag.startswith('uses-permission')]
 if name=='test':
  assert m.find('instrumentation').get('{http://schemas.android.com/apk/res/android}targetPackage')=='com.kandong.modelprobe'
  assert not [e for e in m.find('application') if e.tag in ['activity','activity-alias','service','provider','receiver']]
  meta['testManifest']=xml
sources={}
for folder in ['modelprobe/src/androidTest/assets','modelprobe/build/detector-assets']:
 for p in (root/folder).rglob('*'):
  if p.is_file():sources['assets/'+p.relative_to(root/folder).as_posix()]=p
for namespace,folder in [('detector-geometry-v1','docs/fixtures/detector-geometry-v1'),('polygon-offset-v1','docs/fixtures/polygon-offset-v1')]:
 for p in (root/folder).iterdir():
  if p.is_file():sources[f'assets/{namespace}/{p.name}']=p
for p in (root/'docs/fixtures/recognition-prep-v1').iterdir():
 if p.is_file():sources['assets/'+p.name]=p
for p in (root/'docs/fixtures/detector-box-trace-v1').iterdir():
 if p.is_file():
  name=p.name[:-3]+'z' if p.name.endswith('.json.gz') else p.name
  sources['assets/detector-box-trace-v1/'+name]=p
for p in (root/'docs/fixtures/crop-recognition-v1').iterdir():
 if p.is_file():sources['assets/crop-recognition-v1/'+p.name]=p
assert len(sources)==283
with zipfile.ZipFile(main) as z:
 assert not any(n.startswith(('assets/detector-box-trace-v1/','assets/crop-recognition-v1/')) for n in z.namelist())
 for n in z.namelist():
  if n.endswith('.dex'):
   raw=z.read(n)
   assert b'Lcom/kandong/modelprobe/BoxPipeline' not in raw and b'Lcom/kandong/modelprobe/BoxTrace' not in raw and b'Lcom/kandong/modelprobe/CropRecognition' not in raw
with zipfile.ZipFile(test) as z:
 dex=b''.join(z.read(n) for n in z.namelist() if n.endswith('.dex'))
 for cls in ['BoxPipelineContract','BoxPipelineOpenCv','BoxPipelineProbeTest','BoxTraceFixtureInputs','CropRecognitionContract','CropRecognitionPipeline','CropRecognitionProbeTest','CropRecognitionFixtures','EndToEndOcrProbeTest','EndToEndScoreBudget']:
  assert ('Lcom/kandong/modelprobe/'+cls+';').encode() in dex,cls
 assert b'Lcom/kandong/modelprobe/BoxPipelineGuardTest;' not in dex
 assert b'Lcom/kandong/modelprobe/CropRecognitionContractTest;' not in dex

 names=z.namelist();assert len(names)==len(set(names))
 actual={n for n in names if n.startswith('assets/') and not n.endswith('/')}
 assert actual==sources.keys(),(actual-sources.keys(),sources.keys()-actual)
 for n,p in sources.items():assert z.read(n)==p.read_bytes(),n
 natives=[{'path':n,'bytes':z.getinfo(n).file_size,'sha256':sha(z.read(n))} for n in names if n.endswith('.so')]
 old=json.loads((root/'docs/evidence/detector-geometry-probe/2026-09-24/opencv5-candidate/apk-verification.json').read_bytes())
 assert {x['path']:(x['bytes'],x['sha256']) for x in natives}=={x['path']:(x['bytes'],x['sha256']) for x in old['openCvNativeEntries']}
 meta['nativeLibrariesUnchanged']=natives
meta['testAssetCount']=len(sources)
protected=json.loads(Path('/private/tmp/kandong-e2e-protected-20260924.json').read_bytes())
for p,h in protected.items():
 if p not in {'TASKS.md','WORKLOG.md','modelprobe/src/androidTest/java/com/kandong/modelprobe/BoxTraceComparison.kt'}:assert sha((root/p).read_bytes())==h,p
meta['protectedFilesUnchanged']=len(protected)-3
candidate=json.loads((root/'docs/evidence/polygon-offset/2026-09-24/expanded/all-round/comparison.json').read_bytes())['sourceSha256']
folder=root/'modelprobe/src/testShared/java/de/lighti/clipper'
assert {p.name for p in folder.iterdir() if p.is_file()}==candidate.keys()
for n,h in candidate.items():assert sha((folder/n).read_bytes())==h,n
assert (root/'modelprobe/src/androidTest/assets/polygon-offset-legal/LICENSE.txt').read_bytes()==(root/'docs/evidence/polygon-offset/2026-09-24/license.txt').read_bytes()
meta['vendorSourceFilesVerified']=len(candidate)
units=[]
for p in (root/'modelprobe/build/test-results/testDebugUnitTest').glob('TEST-*.xml'):
 a=ET.parse(p).getroot().attrib;assert int(a['failures'])==int(a['errors'])==int(a['skipped'])==0
 units.append({k:a[k] for k in ['name','tests','failures','errors','skipped']})
assert sum(int(x['tests']) for x in units)>52
old_units=json.loads((root/'docs/evidence/box-pipeline-probe/2026-09-24/apk-verification.json').read_bytes())['unitTests']
for old in old_units:assert old in units
assert any(x['name']=='com.kandong.modelprobe.CropRecognitionContractTest' and int(x['tests'])>0 for x in units)
meta['unitTests']=units
lint=ET.parse(root/'modelprobe/build/reports/lint-results-debug.xml').getroot()
severities=[x.get('severity') for x in lint.findall('issue')];assert 'Error' not in severities and 'Fatal' not in severities
meta['lint']={'errors':0,'warnings':severities.count('Warning')}
Path('/private/tmp/kandong-e2e-apk-verification-20260924.json').write_text(json.dumps(meta,ensure_ascii=False,indent=2)+'\n')
print(json.dumps({k:v for k,v in meta.items() if k not in ['testManifest','nativeLibrariesUnchanged']},ensure_ascii=False))
