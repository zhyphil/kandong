from pathlib import Path
import subprocess,json,re,xml.etree.ElementTree as E,hashlib,struct,time,argparse
p=argparse.ArgumentParser();p.add_argument('output',type=Path);p.add_argument('--build',type=Path,required=True);a=p.parse_args()
r=Path('/Users/haoyuzuo/Projects/KanDong');out=a.output;out.mkdir()
adb=['/Users/haoyuzuo/Library/Android/sdk/platform-tools/adb','-s','emulator-5582']
def call(*args):return subprocess.check_output(adb+list(args),timeout=90)
assert call('emu','avd','name').decode().splitlines()[0]=='KanDong_OCR_API37_16K'
expected=json.loads((a.build/'summary.json').read_text());checked={}
for pkg,f in [('com.kandong.modelprobe','modelprobe/build/outputs/apk/debug/modelprobe-debug.apk'),('com.kandong.modelprobe.test','modelprobe/build/outputs/apk/androidTest/debug/modelprobe-debug-androidTest.apk')]:
 installed=call('shell','pm','path',pkg).decode().strip().removeprefix('package:');actual=call('shell','sha256sum',installed).decode().split()[0]
 assert actual==expected['apks'][pkg]==hashlib.sha256((r/f).read_bytes()).hexdigest();checked[pkg]=actual
report={'installed':checked,'scope':'Cold-launch fixed PNG OCR and local binding-only demo; no real screen or translation provider','pages':[]}
def tree(name=None):
 focus=next((l for l in call('shell','dumpsys','window').decode().splitlines() if 'mCurrentFocus=' in l),'')
 assert 'com.kandong.modelprobe/com.kandong.modelprobe.RegionVisualLabActivity' in focus,focus
 call('shell','uiautomator','dump','/data/local/tmp/kandong-real-visual-window.xml')
 raw=call('shell','cat','/data/local/tmp/kandong-real-visual-window.xml');root=E.fromstring(raw)
 assert all(n.get('package') in ['com.kandong.modelprobe','com.android.systemui'] for n in root.iter('node'))
 if name:(out/(name+'.xml')).write_bytes(raw)
 return root
def texts(root):return [n.get('text','') for n in root.iter('node')]
def tap(root,label):
 n=next(n for n in root.iter('node') if n.get('text')==label);b=list(map(int,re.findall(r'\d+',n.get('bounds'))))
 # Coordinates are taken from the immediately observed own foreground only.
 call('shell','input','tap',str((b[0]+b[2])//2),str((b[1]+b[3])//2))
def shot(name):
 tree();data=call('exec-out','screencap','-p');(out/(name+'.png')).write_bytes(data);return struct.unpack('>II',data[16:24])
def launch(name):
 call('shell','am','force-stop','com.kandong.modelprobe')
 (out/(name+'-launch.txt')).write_bytes(call('shell','am','start','-W','-n','com.kandong.modelprobe/.RegionVisualLabActivity'))
 return tree(name)
def awaitShown(name):
 deadline=time.monotonic()+35
 while time.monotonic()<deadline:
  t=tree();ts=texts(t)
  assert not any('失败' in x or '无法打开' in x or '不确定，请重启' in x for x in ts),ts
  if any('就绪' in x or '原文已' in x or '中文原文' in x for x in ts):tree(name);return t
 raise AssertionError('no displayed OCR result')
def diag(page):
 d=json.loads(call('shell','run-as','com.kandong.modelprobe','cat','files/region-visual-'+page+'-counters.json'))
 pid=int(call('shell','pidof','com.kandong.modelprobe').strip());assert d['pid']==pid
 assert d['cleanupBalanced'] and d['originalCandidatesEqual'] and d['detectors']==4 and d['sessionsOpened']==d['sessionsClosed']==2
 assert d['ortOpened']==d['ortCloseAttempts']==d['ortClosed'] and d['matsOpened']==d['matsAttempted']==d['matsReleased']
 assert d['threadsBefore']==d['threadsAfter'] and d['transport']['imagesClosed']==1 and d['ttlMillis']==60000
 return d
root=launch('initial');assert any('请求 0 次' in t for t in texts(root));assert '翻译演示' in texts(root)
for i,page in enumerate(['en-normal','fr-seam','hans-normal','hant-seam']):
 assert any(page in x for x in texts(root));tap(root,'翻译演示');root=awaitShown(page)
 assert any('非真实翻译' in x for x in texts(root))
 d=diag(page);report['pages'].append(d);(out/(page+'-counters.json')).write_text(json.dumps(d,indent=2)+'\n');shot(page)
 tap(root,'换页');root=tree(page+'-cleared');assert not any('整页 ' in x and '项' in x for x in texts(root))
report['fourPagesDisplayed']=len(report['pages'])==4
rotations=0
try:
 call('emu','rotate');rotations+=1;time.sleep(1)
 root=tree('landscape');w,h=shot('landscape');assert w>h
 assert any('请求 0 次' in t for t in texts(root));report['rotationClears']=True
 tap(root,'翻译演示');root=awaitShown('landscape-shown');shot('landscape-shown');d=diag('en-normal')
 assert d['pid']==report['pages'][0]['pid'] and d['request']>report['pages'][-1]['request']
 report['sameProcessNativeReload']=d
 # Scroll only the observed own-page outer container to inspect the mirror/cards in landscape.
 node=next(n for n in root.iter('node') if n.get('class')=='android.widget.ScrollView' and n.get('scrollable')=='true')
 b=list(map(int,re.findall(r'\d+',node.get('bounds'))));x=b[2]-6
 call('shell','input','swipe',str(x),str(b[3]-40),str(x),str(b[1]+40),'500')
 tree('landscape-scrolled');shot('landscape-scrolled')
 report['landscapeScrollable']=True
finally:
 while rotations<4:call('emu','rotate');rotations+=1;time.sleep(.4)
call('shell','am','force-stop','com.kandong.modelprobe')
call('shell','rm','-f','/data/local/tmp/kandong-real-visual-window.xml')
(out/'summary.json').write_text(json.dumps(report,indent=2)+'\n');print(json.dumps({'fourPagesDisplayed':True,'rotationClears':True,'sameProcessNativeReload':True,'landscapeScrollable':True}))
