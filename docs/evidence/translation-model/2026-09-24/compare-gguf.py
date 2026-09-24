import struct,json,pathlib,hashlib
root=pathlib.Path('/private/tmp/kandong-translation-probe')
def parse(path):
 with path.open('rb') as f:
  def val(fmt):return struct.unpack('<'+fmt,f.read(struct.calcsize('<'+fmt)))[0]
  def string():return f.read(val('Q')).decode('utf-8')
  def value(t):
   fmts={0:'B',1:'b',2:'H',3:'h',4:'I',5:'i',6:'f',7:'?',10:'Q',11:'q',12:'d'}
   if t in fmts:return val(fmts[t])
   if t==8:return string()
   if t==9:
    ty=val('I');n=val('Q');return [value(ty) for _ in range(n)]
   raise ValueError(t)
  assert f.read(4)==b'GGUF';version=val('I');nt,nk=val('Q'),val('Q');kv={}
  for _ in range(nk):
   k=string();kv[k]=value(val('I'))
  tensors=[]
  for _ in range(nt):
   name=string();nd=val('I');dims=[val('Q') for _ in range(nd)];ty=val('I');off=val('Q');tensors.append(dict(name=name,dims=dims,type=ty,offset=off))
  alignment=kv.get('general.alignment',32);start=(f.tell()+alignment-1)//alignment*alignment
  tensors.sort(key=lambda x:x['offset'])
  for i,t in enumerate(tensors):
   end=tensors[i+1]['offset'] if i+1<len(tensors) else path.stat().st_size-start
   size=end-t['offset'];f.seek(start+t['offset']);h=hashlib.sha256();remaining=size
   while remaining:
    chunk=f.read(min(remaining,1024*1024));assert chunk;h.update(chunk);remaining-=len(chunk)
   t.update(paddedBytes=size,paddedSha256=h.hexdigest())
  return dict(version=version,metadata=kv,tensors={t['name']:t for t in tensors},dataStart=start)
a=parse(root/'models/qwen2.5-1.5b-instruct-q4_k_m.gguf');b=parse(root/'ollama-models/blobs/sha256-098cb604ff3cc846891b7e8c00abe4f52f5c6fdc936e21e7e41f2eaf22c1c7cb')
meta=[]
for k in sorted(a['metadata'].keys()|b['metadata'].keys()):
 if a['metadata'].get(k)!=b['metadata'].get(k):meta.append(dict(key=k,before=str(a['metadata'].get(k))[:100],after=str(b['metadata'].get(k))[:100]))
tdiff=[]
for k in sorted(a['tensors'].keys()|b['tensors'].keys()):
 av=a['tensors'].get(k,{});bv=b['tensors'].get(k,{})
 if any(av.get(field)!=bv.get(field) for field in ['dims','type','paddedBytes','paddedSha256']):tdiff.append(k)
r=dict(ggufVersions=[a['version'],b['version']],dataStarts=[a['dataStart'],b['dataStart']],sourceTensorCount=len(a['tensors']),importedTensorCount=len(b['tensors']),metadataDifferences=meta,tensorDifferences=tdiff,sourceTensors=a['tensors'],importedTensors=b['tensors'])
(root/'gguf-compare.json').write_text(json.dumps(r,indent=2)+'\n');print({k:v for k,v in r.items() if k not in ['sourceTensors','importedTensors']})
