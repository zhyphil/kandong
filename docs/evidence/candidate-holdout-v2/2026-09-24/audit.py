from pathlib import Path
import json,hashlib,unicodedata,collections,importlib.util,gzip
root=Path('/Users/haoyuzuo/Projects/KanDong');p=Path('/private/tmp/kandong-candidate-holdout-run-20260924/quality-report.json')
raw=p.read_bytes();assert hashlib.sha256(raw).hexdigest()=='78862bb839cda33d9bd11f3200c246812085bd5403dfd75a98cfd8f73330d082'
j=json.loads(raw)
s=importlib.util.spec_from_file_location('audit',root/'scripts/audit-end-to-end-ocr-quality.py');a=importlib.util.module_from_spec(s);s.loader.exec_module(a)
ws=set(range(9,14))|{32,133,160,5760,8232,8233,8239,8287,12288}|set(range(8192,8203))
def plain(s):return ''.join(c for c in unicodedata.normalize('NFC',s) if ord(c) not in ws)
def distance(x,y):
 row=list(range(len(y)+1))
 for i,c in enumerate(x,1):
  nxt=[i]
  for k,z in enumerate(y,1):nxt.append(min(row[k]+1,nxt[k-1]+1,row[k-1]+(c!=z)))
  row=nxt
 return row[-1]
counts=collections.Counter();differences=[];regressions=[]
for r in j['results']:
 ch='\n'.join(b['ch'] for b in r['blocks']);before=distance(a.normalize(r['source']),a.normalize(ch));after=distance(a.normalize(r['source']),a.normalize(r['selectedRaw'] or ''))
 identity={k:r[k] for k in ['font','id','fontPx','scale']}
 if after>before:regressions.append(dict(**identity,before=before,after=after,chRaw=ch,selectedRaw=r['selectedRaw']))
 if not r['source'] or r['selectedExact']:continue
 x,y=plain(r['source']),plain(r['selectedRaw'] or '')
 if x==y:kind='spacing-only'
 elif unicodedata.normalize('NFKC',x)==unicodedata.normalize('NFKC',y):kind='spacing-and-compatibility-forms'
 elif x.replace('’',"'")==y.replace('’',"'"):kind='spacing-and-apostrophe-style'
 elif x.replace('稅','税')==y.replace('稅','税'):kind='spacing-and-tax-glyph-form'
 elif x.endswith('。') and x[:-1]==y:kind='missing-final-period'
 else:kind='other-content-difference'
 counts[kind]+=1;differences.append(dict(**identity,category=kind,source=r['source'],selectedRaw=r['selectedRaw']))
assert len(j['results'])==124 and j['detectorCalls']==124 and j['recognizerCalls']==240
assert len(differences)==42 and len(regressions)==1
for name,digest in j['helperSha256'].items():assert hashlib.sha256((root/'scripts'/name).read_bytes()).hexdigest()==digest
out=root/'docs/evidence/candidate-holdout-v2/2026-09-24';out.mkdir(parents=True,exist_ok=True)
(out/'quality-report.json.gz').write_bytes(gzip.compress(raw,mtime=0))
summary=dict(schema=1,status='quality-evaluation-complete-not-accepted-for-product',rawReportSha256=hashlib.sha256(raw).hexdigest(),
 manifestSha256=j['manifestSha256'],groups=j['groups'],strictMismatches=len(differences),diagnosticCategories=dict(counts),
 characterEditRegressions=regressions,diagnosticDifferences=differences,
 scoring='Original NFC + Unicode White_Space remains unchanged. Diagnostic equivalences do not alter scores or raw text.',
 conclusion='Page exact-match regression count is insufficient: a pre-existing spacing mismatch can hide a new letter error. v2 produced one new character error (period to o).')
(out/'quality-audit.json').write_text(json.dumps(summary,ensure_ascii=False,indent=2)+'\n')
print(dict(categories=dict(counts),regressions=regressions))
