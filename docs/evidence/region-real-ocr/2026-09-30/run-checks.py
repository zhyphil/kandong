from pathlib import Path
import argparse, hashlib, json, re, shutil, subprocess, xml.etree.ElementTree as ET
ROOT=Path('/Users/haoyuzuo/Projects/KanDong')
ADB='/Users/haoyuzuo/Library/Android/sdk/platform-tools/adb'
DEV='emulator-5582'
p=argparse.ArgumentParser();p.add_argument('mode',choices=['build','install','tests']);p.add_argument('output',type=Path);p.add_argument('--classes',default='');args=p.parse_args()
out=args.output;out.mkdir(parents=True,exist_ok=False)
def sha(p): return hashlib.sha256(p.read_bytes()).hexdigest()
def adb(*x,timeout=60):
 r=subprocess.run([ADB,'-s',DEV,*x],capture_output=True,timeout=timeout)
 if r.returncode: raise RuntimeError((x,r.stderr.decode(errors='replace')))
 return r.stdout
def identity():
 assert adb('emu','avd','name').decode().splitlines()[0]=='KanDong_OCR_API37_16K'
 assert adb('shell','getprop','ro.build.version.sdk').strip()==b'37'
 assert adb('shell','getprop','ro.product.cpu.abi').strip()==b'arm64-v8a'
 assert adb('shell','getconf','PAGE_SIZE').strip()==b'16384'
apks={'com.kandong.modelprobe':ROOT/'modelprobe/build/outputs/apk/debug/modelprobe-debug.apk','com.kandong.modelprobe.test':ROOT/'modelprobe/build/outputs/apk/androidTest/debug/modelprobe-debug-androidTest.apk'}
if args.mode=='build':
 import os
 env=dict(os.environ,JAVA_HOME='/Users/haoyuzuo/Library/Java/JavaVirtualMachines/temurin-17.0.20.1/Contents/Home')
 with (out/'gradle.txt').open('wb') as f:
  r=subprocess.run(['./gradlew','--offline',':modelprobe:testDebugUnitTest',':modelprobe:assembleDebug',':modelprobe:assembleDebugAndroidTest',':modelprobe:lintDebug'],cwd=ROOT,env=env,stdout=f,stderr=subprocess.STDOUT)
 if r.returncode: raise SystemExit('Build failed: '+str(out/'gradle.txt'))
 results=ROOT/'modelprobe/build/test-results/testDebugUnitTest'
 shutil.copytree(results,out/'jvm',ignore=shutil.ignore_patterns('binary'))
 totals={k:sum(int(ET.parse(p).getroot().attrib.get(k,0)) for p in results.glob('TEST-*.xml')) for k in ['tests','failures','errors','skipped']}
 assert totals['failures']==totals['errors']==totals['skipped']==0
 lint=ROOT/'modelprobe/build/reports/lint-results-debug.xml';shutil.copy(lint,out/lint.name)
 counts={s:sum(1 for x in ET.parse(lint).getroot().findall('issue') if x.attrib['severity']==s) for s in ['Error','Warning','Fatal']}
 assert counts['Error']==counts['Fatal']==0
 files=subprocess.check_output(['git','ls-files','--modified','--others','--exclude-standard'],cwd=ROOT,text=True).splitlines()
 data={'jvm':totals,'lint':counts,'apks':{k:sha(v) for k,v in apks.items()},'sourceSha256':{f:sha(ROOT/f) for f in files if f.startswith('modelprobe/') and (ROOT/f).is_file()}}
 (out/'summary.json').write_text(json.dumps(data,indent=2)+'\n');print(json.dumps(data))
else:
 identity(); identities={}
 for package,apk in apks.items():
  local=sha(apk)
  if args.mode=='install':
   result=adb('install','-r','-t',str(apk),timeout=120).decode();assert 'Success' in result,result
  remote=adb('shell','pm','path',package).decode().strip().removeprefix('package:')
  actual=adb('shell','sha256sum',remote).decode().split()[0];assert actual==local
  identities[package]={'local':local,'installed':actual}
 (out/'environment.json').write_text(json.dumps({'avd':'KanDong_OCR_API37_16K','api':37,'pageSize':16384,'packages':identities},indent=2)+'\n')
 if args.mode=='tests':
  assert args.classes and all(re.fullmatch('[A-Za-z][A-Za-z0-9]+',x) for x in args.classes.split(','))
  classes=','.join('com.kandong.modelprobe.'+x for x in args.classes.split(','))
  with (out/'instrumentation.txt').open('wb') as f:
   r=subprocess.run([ADB,'-s',DEV,'shell','am','instrument','-w','-r','-e','class',classes,'com.kandong.modelprobe.test/androidx.test.runner.AndroidJUnitRunner'],stdout=f,stderr=subprocess.STDOUT,timeout=480)
  log=(out/'instrumentation.txt').read_text();m=re.search(r'OK \((\d+) tests\)',log)
  assert r.returncode==0 and m and 'FAILURES!!!' not in log,log[-3000:]
  result={'tests':int(m[1]),'classes':args.classes.split(','),'scope':'fixed synthetic page actual OCR and behavioral regression only'}
  (out/'summary.json').write_text(json.dumps(result,indent=2)+'\n');print(json.dumps(result))
 else: print(json.dumps(identities))
