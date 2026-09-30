from pathlib import Path
import subprocess,json,re,xml.etree.ElementTree as E,hashlib,struct,time
r=Path('/Users/haoyuzuo/Projects/KanDong');out=Path('/private/tmp/kandong-visual-cold-accepted');out.mkdir()
a=['/Users/haoyuzuo/Library/Android/sdk/platform-tools/adb','-s','emulator-5582']
def call(*args):return subprocess.check_output(a+list(args),timeout=60)
assert call('emu','avd','name').decode().splitlines()[0]=='KanDong_OCR_API37_16K'
s=json.loads(Path('/private/tmp/kandong-visual-validation-accepted/summary.json').read_text());checked={}
for pkg,f in [('com.kandong.modelprobe','modelprobe/build/outputs/apk/debug/modelprobe-debug.apk'),('com.kandong.modelprobe.test','modelprobe/build/outputs/apk/androidTest/debug/modelprobe-debug-androidTest.apk')]:
 installed=call('shell','pm','path',pkg).decode().strip().removeprefix('package:');actual=call('shell','sha256sum',installed).decode().split()[0]
 assert actual==s['apks'][f]==hashlib.sha256((r/f).read_bytes()).hexdigest();checked[pkg]=actual
report={'installed':checked,'coldLaunch':False,'explicitShow':False,'landscape':False,'rotationClears':False,'missingPackageError':False,'restoredPair':False}
def tree(name):
 focus=next((l for l in call('shell','dumpsys','window').decode().splitlines() if 'mCurrentFocus=' in l),'')
 assert 'com.kandong.modelprobe/com.kandong.modelprobe.RegionVisualLabActivity' in focus,focus
 call('shell','uiautomator','dump','/data/local/tmp/kandong-region-window.xml')
 raw=call('shell','cat','/data/local/tmp/kandong-region-window.xml');root=E.fromstring(raw)
 assert all(n.get('package') in ['com.kandong.modelprobe','com.android.systemui'] for n in root.iter('node'))
 (out/(name+'.xml')).write_bytes(raw);return root

def launch(name):
 call('shell','am','force-stop','com.kandong.modelprobe')
 (out/(name+'-launch.txt')).write_bytes(call('shell','am','start','-W','-n','com.kandong.modelprobe/.RegionVisualLabActivity'))
 return tree(name)
def tapText(root,text):
 n=next(n for n in root.iter('node') if n.get('text')==text);b=list(map(int,re.findall(r'\d+',n.get('bounds'))))
 call('shell','input','tap',str((b[0]+b[2])//2),str((b[1]+b[3])//2))
def shot(name):
 data=call('exec-out','screencap','-p');(out/(name+'.png')).write_bytes(data);return struct.unpack('>II',data[16:24])
root=launch('initial');assert any('展示 0 次' in n.get('text','') for n in root.iter('node'));report['coldLaunch']=True
assert not any('无法打开实验' in n.get('text','') for n in root.iter('node'))
tapText(root,'展示样例');root=tree('shown');assert any('冲突，未选择答案' in n.get('text','') for n in root.iter('node'));report['explicitShow']=True
shot('shown')
rotations=0
try:
 call('emu','rotate');rotations+=1;time.sleep(1)
 root=tree('landscape');w,h=shot('landscape')
 report['landscape']=w>h
 report['rotationClears']=any('展示 0 次' in n.get('text','') for n in root.iter('node'))
 if w>h:
  tapText(root,'展示样例');root=tree('landscape-shown');shot('landscape-shown')
  tapText(root,'菜单');tree('landscape-menu');shot('landscape-menu')
finally:
 while rotations<4:call('emu','rotate');rotations+=1;time.sleep(.4)
root=launch('portrait-restored');shot('portrait-restored')
# Deliberately remove ONLY this emulator's own test package to verify the host error boundary.
call('shell','am','force-stop','com.kandong.modelprobe')
try:
 (out/'missing-package-uninstall.txt').write_bytes(call('uninstall','com.kandong.modelprobe.test'))
 root=launch('missing-package');report['missingPackageError']=any('无法打开实验' in n.get('text','') for n in root.iter('node'));assert report['missingPackageError']
 shot('missing-package')
finally:
 (out/'restore-test-package.txt').write_bytes(call('install','-r','-t',str(r/'modelprobe/build/outputs/apk/androidTest/debug/modelprobe-debug-androidTest.apk')))
root=launch('final-pair');assert any(n.get('text')=='展示样例' for n in root.iter('node'))
report['restoredPair']=True
remote=call('shell','pm','path','com.kandong.modelprobe.test').decode().strip().removeprefix('package:')
assert call('shell','sha256sum',remote).decode().split()[0]==checked['com.kandong.modelprobe.test']
(out/'summary.json').write_text(json.dumps(report,indent=2)+'\n')
call('shell','am','force-stop','com.kandong.modelprobe');call('shell','rm','-f','/data/local/tmp/kandong-region-window.xml')
print(json.dumps(report,indent=2))
