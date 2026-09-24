"""Synthetic-only host candidate screening. All inference settings apply to all inputs."""
import argparse,gzip,hashlib,json,os,subprocess,time,unicodedata
from pathlib import Path
import numpy as np
from PIL import Image
parser=argparse.ArgumentParser();parser.add_argument('--repo',type=Path,required=True);parser.add_argument('--models',type=Path,required=True);parser.add_argument('--output',type=Path,required=True);parser.add_argument('--android-report',type=Path,required=True);parser.add_argument('--engine',default='/opt/homebrew/bin/tesseract');args=parser.parse_args()
out=args.output;out.mkdir(parents=True,exist_ok=True);assets=args.repo/'ocrlab/src/main/assets/trilingual-v1'
manifest_bytes=(assets/'manifest.json').read_bytes();manifest=json.loads(manifest_bytes)
assert hashlib.sha256(manifest_bytes).hexdigest()=='2341dff63bd10d41867351ddff0e4145daa2d9d3493bc8cdc88ea289c1a076a8'
android_bytes=args.android_report.read_bytes()
android=json.loads(gzip.decompress(android_bytes) if args.android_report.suffix=='.gz' else android_bytes);paired={r['inputId']:r['processed']['pixelSha256'] for r in android['results'] if r['sourceKind']=='fixed' and r['processed']['scaleX']==2}
assert android['processingProfile']=='SMOOTH' and len(paired)==18
orders=['eng+fra+chi_sim+chi_tra','chi_sim+chi_tra+eng+fra'];env=os.environ.copy();env['OMP_THREAD_LIMIT']='1'
def sha(b):return hashlib.sha256(b).hexdigest()
def normalize(text):
 whites=set(range(9,14))|{32,133,160,5760,8232,8233,8239,8287,12288}|set(range(8192,8203));result=[];space=False
 for c in unicodedata.normalize('NFC',text):
  if ord(c) in whites:space=bool(result)
  else:
   if space:result.append(' ')
   result.append(c);space=False
 return ''.join(result)
def smooth2(image):
 a=np.asarray(image,dtype=np.int32);h,w=a.shape[:2];d=4
 nx=np.clip(np.arange(w*2)*2-1,0,(w-1)*d);ny=np.clip(np.arange(h*2)*2-1,0,(h-1)*d)
 x0=nx//d;x1=np.minimum(x0+1,w-1);y0=ny//d;y1=np.minimum(y0+1,h-1);wx=(nx%d)[None,:,None];wy=(ny%d)[:,None,None]
 top=a[y0[:,None],x0[None,:]]*(d-wx)+a[y0[:,None],x1[None,:]]*wx
 bottom=a[y1[:,None],x0[None,:]]*(d-wx)+a[y1[:,None],x1[None,:]]*wx
 return Image.fromarray(((top*(d-wy)+bottom*wy+8)//16).astype(np.uint8))
inputs=[]
for row in manifest['images']:
 p=assets/row['asset'];assert sha(p.read_bytes())==row['pngSha256'];im=Image.open(p).convert('RGBA');assert sha(im.tobytes())==row['pixelSha256'];assert im.getextrema()[3]==(255,255)
 for scale in [1,2]:
  image=im if scale==1 else smooth2(im);pixelsha=sha(image.tobytes());inputid=row['asset'].removesuffix('.png')
  if scale==2:assert pixelsha==paired[inputid],inputid+' differs from Android processed pixels'
  path=out/(inputid+'-'+str(scale)+'x.png');image.save(path)
  inputs.append((row,path,scale,pixelsha))
models={lang:{'bytes':(args.models/(lang+'.traineddata')).stat().st_size,'sha256':sha((args.models/(lang+'.traineddata')).read_bytes())} for lang in orders[0].split('+')}
results=[]
for order in orders:
 for row,path,scale,pixelsha in inputs:
  started=time.monotonic();r=subprocess.run([args.engine,str(path),'stdout','--tessdata-dir',str(args.models),'-l',order,'--oem','1','--psm','6'],capture_output=True,text=True,env=env,timeout=45)
  result={'inputId':row['asset'].removesuffix('.png'),'language':row['language'],'source':row['source'],'pngSha256':row['pngSha256'],'inputPixelSha256':pixelsha,'scale':scale,'algorithm':'identity-v1' if scale==1 else 'bilinear-center-integer-v1','engineLanguages':order,'oem':1,'psm':6,'recognizedRaw':r.stdout,'stderr':r.stderr,'exitCode':r.returncode,'elapsedMs':round((time.monotonic()-started)*1000,2),'exactNormalizedMatch':normalize(row['source'])==normalize(r.stdout)}
  results.append(result);print(len(results),result['inputId'],scale,order,result['exactNormalizedMatch'],flush=True)
report={'schema':1,'scope':'synthetic_only_host_screening_not_Android_acceptance_or_phone_performance','engine':subprocess.check_output([args.engine,'--version'],text=True).splitlines()[0],'engineSha256':sha(Path(args.engine).read_bytes()),'manifestSha256':sha(manifest_bytes),'hostPlatform':os.uname().machine,'models':models,'orders':orders,'policy':'all inputs receive both global language orders and scales; expected source and language used only after inference for scoring; no correction or best-per-input selection','normalization':'NFC + Unicode White_Space folding and trim only','androidSmooth2PixelMatchCount':len(paired),'results':results}
(out/'report.json').write_text(json.dumps(report,ensure_ascii=False,indent=2)+'\n')
assert len(results)==72 and all(r['exitCode']==0 for r in results)
for order in orders:
 for scale in [1,2]:
  rows=[r for r in results if r['engineLanguages']==order and r['scale']==scale]
  print('GROUP',order,scale,sum(r['exactNormalizedMatch'] for r in rows),'/',len(rows),flush=True)
