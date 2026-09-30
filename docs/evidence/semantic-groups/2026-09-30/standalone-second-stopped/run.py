from pathlib import Path
import argparse,hashlib,json,re,subprocess,time,xml.etree.ElementTree as E
p=argparse.ArgumentParser();p.add_argument('output',type=Path);args=p.parse_args();out=args.output;out.mkdir(parents=True,exist_ok=False)
root=Path('/Users/haoyuzuo/Projects/KanDong');adb=['/Users/haoyuzuo/Library/Android/sdk/platform-tools/adb','-s','emulator-5582']
def call(*a):return subprocess.check_output(adb+list(a),timeout=90)
assert call('emu','avd','name').decode().splitlines()[0]=='KanDong_OCR_API37_16K'
assert call('shell','getprop','ro.build.version.sdk').strip()==b'37'
packages={}
for package,apk in [('com.kandong.modelprobe','modelprobe/build/outputs/apk/debug/modelprobe-debug.apk'),('com.kandong.modelprobe.test','modelprobe/build/outputs/apk/androidTest/debug/modelprobe-debug-androidTest.apk')]:
 path=call('shell','pm','path',package).decode().strip().removeprefix('package:');remote=call('shell','sha256sum',path).decode().split()[0]
 assert remote==hashlib.sha256((root/apk).read_bytes()).hexdigest();packages[package]=remote

def tree(name=None):
 focus=next(x for x in call('shell','dumpsys','window').decode().splitlines() if 'mCurrentFocus=' in x)
 assert 'com.kandong.modelprobe/com.kandong.modelprobe.RegionVisualLabActivity' in focus,focus
 call('shell','uiautomator','dump','/data/local/tmp/kandong-grouped-view.xml');raw=call('shell','cat','/data/local/tmp/kandong-grouped-view.xml');t=E.fromstring(raw)
 assert all(n.get('package') in ['com.kandong.modelprobe','com.android.systemui'] for n in t.iter('node'))
 if name:(out/(name+'.xml')).write_bytes(raw)
 return t

def texts(t):return [x.get('text','') for x in t.iter('node')]
def bounds(n):return list(map(int,re.findall(r'\d+',n.get('bounds',''))))
def tap(t,label):
 n=next(n for n in t.iter('node') if n.get('text')==label);l,top,r,b=bounds(n);assert r>l and b>top
 call('shell','input','tap',str((l+r)//2),str((top+b)//2))
def screenshot(name):
 tree();(out/(name+'.png')).write_bytes(call('exec-out','screencap','-p'))
call('shell','am','force-stop','com.kandong.modelprobe');(out/'launch.txt').write_bytes(call('shell','am','start','-W','-n','com.kandong.modelprobe/.RegionVisualLabActivity'))
t=tree('initial');assert any('请求 0 次' in x for x in texts(t))
tap(t,'启用双行分组实验');t=tree('grouped-selected');assert any('请求 0 次' in x for x in texts(t));tap(t,'换页');t=tree();assert any('fr-seam' in x for x in texts(t));tap(t,'译文回放')
start=time.monotonic()
while time.monotonic()-start<45:
 t=tree()
 if any('录制结果就绪' in x for x in texts(t)):break
 assert not any('结果不可用' in x for x in texts(t)),texts(t)
else:raise AssertionError('grouped result not ready')
tree('grouped-ready');screenshot('grouped-ready')
heading=next(n for n in t.iter('node') if n.get('text')=='选区候选优先 · 整页上下文保留')
l,top,r,b=bounds(heading)
call('shell','input','swipe',str((l+r)//2),str((top+b)//2),str((l+r)//2),str(max(300,top-850)),'400');t=tree()
for _ in range(8):
 if any('4+5' in x and 'Aucun remboursement après confirmation.' in x for x in texts(t)):break
 scrolls=[n for n in t.iter('node') if n.get('class')=='android.widget.ScrollView']
 n=min(scrolls,key=lambda n:bounds(n)[3]-bounds(n)[1]);l,top,r,b=bounds(n)
 assert b-top>200
 call('shell','input','swipe',str((l+r)//2),str(top+(b-top)*2//3),str((l+r)//2),str(top+(b-top)//5),'400');t=tree()
else:raise AssertionError('grouped source card not visible')
tree('grouped-card');screenshot('grouped-card')
counters=json.loads(call('shell','run-as','com.kandong.modelprobe','cat','files/region-visual-fr-seam-counters.json'))
assert counters['pid']==int(call('shell','pidof','com.kandong.modelprobe').strip())
assert counters['cleanupBalanced'] and counters['originalCandidatesEqual'] and counters['transport']['imagesClosed']==1
assert counters['sessionsOpened']==counters['sessionsClosed'] and counters['ortOpened']==counters['ortClosed'] and counters['ttlMillis']==60000
(out/'counters.json').write_text(json.dumps(counters,indent=2)+'\n')
rotations=0
try:
 call('emu','rotate');rotations+=1;time.sleep(.8);t=tree('rotated-cleared');screenshot('rotated-cleared')
 assert any('请求 0 次' in x for x in texts(t));assert not any('Aucun remboursement' in x or '确认后不予退款' in x for x in texts(t))
finally:
 while rotations<4:call('emu','rotate');rotations+=1;time.sleep(.3)
 call('shell','am','force-stop','com.kandong.modelprobe');call('shell','rm','-f','/data/local/tmp/kandong-grouped-view.xml')
summary={'packages':packages,'scope':'cold launch fixed synthetic page actual OCR and recorded grouped response; no network or real screen capture','freshModeSelectionNoRead':True,'groupedSourceCardVisible':True,'rotationClears':True,'nativeCleanupBalanced':True,'stoppedOwnProcess':True}
(out/'summary.json').write_text(json.dumps(summary,indent=2)+'\n');print(json.dumps(summary))
