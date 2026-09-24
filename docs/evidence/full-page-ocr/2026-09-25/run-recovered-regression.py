from pathlib import Path
import subprocess, hashlib, json, time, argparse, re
p=argparse.ArgumentParser();p.add_argument('--serial',required=True);p.add_argument('--output',type=Path,required=True);a=p.parse_args()
root=Path('/Users/haoyuzuo/Projects/KanDong');adb='/Users/haoyuzuo/Library/Android/sdk/platform-tools/adb'; dev=a.serial
assert dev in ['emulator-5582','2KE0220109017133'];out=a.output;out.mkdir(parents=True,exist_ok=False)
def call(*args,timeout=30):
 r=subprocess.run([adb,'-s',dev,*args],capture_output=True,timeout=timeout)
 if r.returncode:raise RuntimeError((args,r.returncode,r.stderr.decode(errors='replace')))
 return r.stdout
if dev=='emulator-5582':
 assert call('emu','avd','name').decode().splitlines()[0]=='KanDong_OCR_API37_16K'
 assert call('shell','getprop','ro.build.version.sdk').decode().strip()=='37'
 assert call('shell','getconf','PAGE_SIZE').decode().strip()=='16384'
else:
 assert call('shell','getprop','ro.product.model').decode().strip()=='LIO-AN00'
 assert call('shell','getprop','ro.build.version.sdk').decode().strip()=='31'
meta={'device':dev,'api':call('shell','getprop','ro.build.version.sdk').decode().strip(),'packages':{}}
for pkg,relative in [('com.kandong.modelprobe','modelprobe/build/outputs/apk/debug/modelprobe-debug.apk'),('com.kandong.modelprobe.test','modelprobe/build/outputs/apk/androidTest/debug/modelprobe-debug-androidTest.apk')]:
 apk=root/relative;sha=hashlib.sha256(apk.read_bytes()).hexdigest()
 remote=call('shell','pm','path',pkg).decode().strip().removeprefix('package:')
 actual=call('shell','sha256sum',remote).decode().split()[0];assert actual==sha,(pkg,'APK mismatch; install separately first')
 meta['packages'][pkg]={'local':sha,'installed':actual}

(out/'environment.json').write_text(json.dumps(meta,indent=2)+'\n')
classes='com.kandong.modelprobe.FullPageOcrContractTest,com.kandong.modelprobe.FullPageStripPlannerTest,com.kandong.modelprobe.SingleFrameStripInputTest,com.kandong.modelprobe.AndroidImageStripProbeTest,com.kandong.modelprobe.EndToEndOcrProbeTest'
with (out/'instrumentation.txt').open('wb') as log:
 proc=subprocess.Popen([adb,'-s',dev,'shell','am','instrument','-w','-r','-e','class',classes,'com.kandong.modelprobe.test/androidx.test.runner.AndroidJUnitRunner'],stdout=log,stderr=subprocess.STDOUT)
 try:
  time.sleep(2)
  if proc.poll() is None:
   (out/'foreground.txt').write_bytes(call('shell','am','start','-n','com.kandong.modelprobe/.ModelProbeActivity'))
  proc.wait(timeout=240)
 finally:
  if proc.poll() is None:
   call('shell','am','force-stop','com.kandong.modelprobe');proc.terminate();proc.wait(timeout=10)
call('shell','am','force-stop','com.kandong.modelprobe')
result=(out/'instrumentation.txt').read_text()
print(result[-500:]);assert 'OK (36 tests)' in result and 'FAILURES!!!' not in result
