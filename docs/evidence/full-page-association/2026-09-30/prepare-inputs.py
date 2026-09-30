"""Export authenticated, archived synthetic candidates; no OCR/model/expected-text selection."""
from pathlib import Path
import argparse,base64,hashlib,json
p=argparse.ArgumentParser();p.add_argument('--out',type=Path,required=True);a=p.parse_args()
root=Path(__file__).resolve().parents[4];inventory={'pages':json.loads((root/'docs/fixtures/full-page-association-v1/manifest.json').read_text())['sources']}
a.out.mkdir(parents=True,exist_ok=False)
lines=['schema=1'];names=[];sources=[]
def b64(s):return base64.b64encode(s.encode()).decode()
def vals(xs):return ','.join(str(x) for x in xs)
for item in inventory['pages']:
 path=root/item['file'];data=path.read_bytes();assert hashlib.sha256(data).hexdigest()==item['sha256'];d=json.loads(data)
 case=item['device']+'--'+item['model']+'--'+item['page'];names.append(case);prefix=case+'.'
 def put(k,v):lines.append(prefix+k+'='+str(v))
 put('sourceBatch',d['runId']);put('detectorSha',d['detectorModelSha256']);put('modelSha',d['modelSha256']);put('vocabulary',d['vocabulary']);put('dictionarySha',d['modelGroupSessions']['dictionarySha256']);put('complete',str(d['pageComplete']).lower());put('page',d['pageFixtureId']);put('model',d['modelId']);put('width',d['layout']['width']);put('height',d['layout']['height'])
 cs=d['rawCandidates'];put('count',len(cs))
 if cs:
  v=cs[0]['version'];put('versionSource','direct')
 else:
  neighbor=json.loads((path.parent/('full-page-ocr-'+d['modelId']+'-en-normal.json')).read_text())['rawCandidates'][0]['version']
  v={'session':neighbor['session'],'snapshot':10 if d['modelId']=='ch' else 20,'page':9,'revision':1,'window':0,'display':0};put('versionSource','derived-from-frozen-runner-formula')
 put('stripCount',len(d['strips']))
 for strip in d['strips']:
  put(f"strip.{strip['index']}.read",vals(strip['read']));put(f"strip.{strip['index']}.core",vals(strip['core']))
  put(f"strip.{strip['index']}.count",strip['boxes']);put(f"strip.{strip['index']}.complete",str(strip['status']=='COMPLETE').lower())
 put('version',vals(v[k] for k in ['session','snapshot','page','revision','window','display']))
 for i,c in enumerate(cs):
  assert c['version']==v and c['pageFixtureId']==d['pageFixtureId'] and c['modelId']==d['modelId']
  fields={'id':b64(c['id']),'text':b64(c['rawText']),'strip':c['stripIndex'],'indices':vals(c[k] for k in ['contourIndex','rawBoxIndex','finalBoxIndex','stripReadingOrder']),
   'score':c['detectorScore'],'owner':str(c['ownsCoreCenter']).lower(),'read':vals(c['read']),'core':vals(c['core']),
   'local':vals(n for xy in c['localQuad'] for n in xy),'quad':vals(n for xy in c['pageQuad'] for n in xy),
   'recognition':vals([c['recognitionInputShape'][3],c['recognitionOutputShape'][1]])}
  for k,value in fields.items():put(f'{i}.{k}',value)
 sources.append(item)
lines.insert(1,'cases='+','.join(names));raw=('\n'.join(lines)+'\n').encode('ascii');(a.out/'archived.properties').write_bytes(raw)
manifest={'schema':1,'fixtureVersion':'full-page-association-v1','scope':'Archived synthetic candidates from d56a87c. No fresh inference, real pixels, or expected text passed to association. Empty-page identity is explicitly derived from the frozen runner formula and same-run neighbor session, not directly recorded in its candidates.','caseCount':len(names),'candidateCount':sum(x['candidates'] for x in sources),'archivedSha256':hashlib.sha256(raw).hexdigest(),'sources':sources}
(a.out/'manifest.json').write_text(json.dumps(manifest,ensure_ascii=False,indent=2)+'\n')
print(manifest['archivedSha256'],len(raw))
