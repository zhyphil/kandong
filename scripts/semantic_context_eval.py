"""Offline authored-layout evaluation; Kotlin observations, separate frozen semantic oracle."""
import argparse
import base64
import hashlib
import json
import re
import xml.etree.ElementTree as ET
from pathlib import Path

ROOT=Path(__file__).resolve().parents[1]
CORPUS=ROOT/'docs/fixtures/semantic-context-eval-v1/corpus.json'
CORPUS_SHA='4ace88c9a391cd73826ff7e369f024a4c106c9aa0dcf5e3529676f4d496c29b6'
PREFIX='KD_SEMANTIC_EVAL '
GENERATED=ROOT/'modelprobe/src/test/java/com/kandong/modelprobe/SemanticContextEvalCorpus.kt'

def sha(raw):return hashlib.sha256(raw).hexdigest()
def encoded(v):return json.dumps(v,ensure_ascii=False,sort_keys=True,separators=(',',':')).encode()
def require(value,reason):
    if not value:raise ValueError(reason)

def validate(v):
    require(v['schema']==1 and v['track']=='AUTHORED_CANDIDATES_NOT_OCR','CORPUS_KIND')
    require(len(v['pages'])==24 and len({p['id'] for p in v['pages']})==24,'PAGE_COUNT')
    for p in v['pages']:
        require(re.fullmatch('[a-z0-9-]{1,64}',p['id']) and p['language'] in ['en','fr','zh-Hans','zh-Hant'],'PAGE_ID')
        require(p['width']==1200 and p['height']==2400,'PAGE_SIZE')
        items=p['items'];labels={i['label'] for i in items}
        require(1<=len(items)<=12 and len(labels)==len(items),'ITEM_IDS')
        for i in items:
            require(re.fullmatch('[a-z0-9-]+',i['label']) and isinstance(i['text'],str) and bool(i['text']) and '\n' not in i['text'],'ITEM_TEXT')
            require(type(i['strip']) is int and 0<=i['strip']<4 and 0<=i['score']<=1,'ITEM_METADATA')
            q=i['quad'];top=max(0,i['strip']*600-64);bottom=min(2400,(i['strip']+1)*600+64)
            require(len(q)==4 and all(len(x)==2 and all(type(n) in [int,float] for n in x) and 0<=x[0]<1200 and top<=x[1]<bottom for x in q),'ITEM_QUAD')
        o=p['oracle'];protected=set(o['protectedItems'])
        require(len(protected)==len(o['protectedItems']) and protected<=labels and bool(o['rationale']),'ORACLE_IDS')
        groups=o['completeGroups'];flat=[x for g in groups for x in g]
        require(len(flat)==len(set(flat)) and all(len(g)>=2 and set(g)<=protected for g in groups),'ORACLE_GROUPS')
    require(sum(len(p['items']) for p in v['pages'])==93,'ITEM_COUNT')
    return v

def load():
    raw=CORPUS.read_bytes();require(sha(raw)==CORPUS_SHA,'CORPUS_CHANGED')
    return validate(json.loads(raw))

def kotlin(v):
    q=lambda x:json.dumps(x,ensure_ascii=False).replace('$','\\$')
    number=lambda n:str(float(n))
    lines=['package com.kandong.modelprobe','',
           '/** Generated INPUTS only. Authored candidate geometry, never model output; no semantic oracle here. */',
           'internal object SemanticContextEvalCorpus {',f'    const val SHA="{CORPUS_SHA}"',
           '    data class Item(val label:String,val text:String,val strip:Int,val quad:List<GeometryProbeContract.Point>,val score:Double)',
           '    data class Page(val id:String,val language:String,val width:Int,val height:Int,val items:List<Item>)',
           '    fun pages():List<Page> = listOf(']
    for p in v['pages']:
        lines.append(f'        Page({q(p["id"])},{q(p["language"])},{p["width"]},{p["height"]},listOf(')
        for i in p['items']:
            points=','.join(f'GeometryProbeContract.Point({number(x)},{number(y)})' for x,y in i['quad'])
            lines.append(f'            Item({q(i["label"])},{q(i["text"])},{i["strip"]},listOf({points}),{number(i["score"])}),')
        lines.append('        )),')
    lines+=['    )','}','']
    return '\n'.join(lines)

def observations(xml):
    root=ET.parse(xml).getroot()
    require(all(int(root.get(k,0))==0 for k in ['failures','errors','skipped']),'JVM_CHECK_FAILED')
    records=[]
    for out in root.iter('system-out'):
        for line in (out.text or '').splitlines():
            if line.startswith(PREFIX):records.append(json.loads(base64.b64decode(line[len(PREFIX):],validate=True)))
    require(len(records)==25 and len({r['id'] for r in records})==25,'OBSERVATION_COUNT')
    return records

def grade_page(page,obs):
    require(obs['corpusSha256']==CORPUS_SHA and obs['id']==page['id'] and obs['language']==page['language'] and obs['track']=='AUTHORED_CANDIDATES_NOT_OCR','OBSERVATION_IDENTITY')
    require(obs['published'] and obs['rawFieldsPreserved'] and obs['orderUncertain'],'EVIDENCE_FLAGS')
    expected={i['label']:i for i in page['items']};raw={i['label']:i for i in obs['raw']}
    require(len(raw)==len(obs['raw']) and raw.keys()==expected.keys(),'SOURCE_LABELS')
    for label,item in expected.items():
        actual=raw[label]
        require(all(actual[k]==item[k] for k in ['text','strip','quad','score']),'SOURCE_CHANGED')
    require(obs['contextLabels']==[i['label'] for i in obs['rawInContext']] and len(obs['contextLabels'])==len(expected) and set(obs['contextLabels'])==expected.keys(),'CONTEXT_MISSING')
    require({x['label']:x['text'] for x in obs['rawInContext']}=={k:x['text'] for k,x in expected.items()},'CONTEXT_TEXT_CHANGED')
    targets=obs['requestTargets'];seen=set()
    for t in targets:
        labels=t['labels'];require(labels and len(labels)==len(set(labels)) and set(labels)<=expected.keys(),'TARGET_MEMBERS')
        key=frozenset(labels);require(key not in seen,'TARGET_REPEATED');seen.add(key)
        require(t['sourceText']==' '.join(expected[x]['text'] for x in labels),'TARGET_TEXT_CHANGED')
    if page['language'].startswith('zh'):require(not targets,'CHINESE_REQUEST')
    truth={frozenset(g) for g in page['oracle']['completeGroups']};protected=set(page['oracle']['protectedItems'])
    related=[t for t in targets if set(t['labels'])&protected]
    unsafe=[t for t in related if frozenset(t['labels']) not in truth]
    correct=[t for t in related if frozenset(t['labels']) in truth]
    missed=[g for g in page['oracle']['completeGroups'] if frozenset(g) not in seen]
    conservative=[g for g in missed if not any(set(g)&set(t['labels']) for t in unsafe)]
    return {'id':page['id'],'language':page['language'],'category':page['category'],
            'unsafeTargets':unsafe,'correctCompleteTargets':correct,'missingCompleteGroups':missed,
            'conservativelyWithheldGroups':conservative,'unrelatedTargets':len(targets)-len(related),
            'originalCandidates':len(raw),'rationale':page['oracle']['rationale']}

def report(corpus,records):
    authored=[r for r in records if r['track']=='AUTHORED_CANDIDATES_NOT_OCR']
    by_id={r['id']:r for r in authored}
    require(len(by_id)==len(authored)==24 and by_id.keys()=={p['id'] for p in corpus['pages']},'AUTHORED_COVERAGE')
    pages=[grade_page(p,by_id[p['id']]) for p in corpus['pages']]
    archived=[r for r in records if r['track']=='ARCHIVED_OCR_REPLAY_NOT_NEW_INFERENCE']
    require(len(archived)==1 and archived[0]['id']=='fr-seam' and archived[0]['rawFieldsPreserved'],'ARCHIVE_COVERAGE')
    archive=archived[0];ambiguous=[g for g in archive['groups'] if g['ambiguous'] and len(g['labels'])==4]
    require(len(ambiguous)==1,'ARCHIVE_GROUP')
    members=set(ambiguous[0]['labels'])
    clusters=[g for g in archive['associationGroups'] if set(g['labels'])<=members]
    require(set(x for g in clusters for x in g['labels'])==members,'ARCHIVE_MEMBERS')
    unsafe=sum(len(p['unsafeTargets']) for p in pages)
    return {'schema':1,'scope':'offline authored-candidate assessment; no OCR inference or translation quality claim',
            'corpusSha256':CORPUS_SHA,'sourcePreservationPassed':True,'wholeContextPassed':True,
            'pages':pages,'totals':{'pages':24,'candidates':sum(p['originalCandidates'] for p in pages),
                'unsafeTargets':unsafe,'unsafePages':sum(bool(p['unsafeTargets']) for p in pages),
                'correctCompleteTargets':sum(len(p['correctCompleteTargets']) for p in pages),
                'conservativelyWithheldGroups':sum(len(p['conservativelyWithheldGroups']) for p in pages)},
            'semanticGatePassed':unsafe==0,'qualityAccepted':False,'semanticVerified':False,
            'duplicateStudy':{'track':archive['track'],'originalCandidates':len(archive['raw']),
                'ambiguousGroup':ambiguous[0],'underlyingAssociationGroups':clusters,
                'newCandidateAccepted':False,'originalConflictUnchanged':True,
                'interpretation':'Evidence supports studying joint source groups; it does not authorize deleting a duplicate or selecting a winner.'}}

def main(argv=None):
    p=argparse.ArgumentParser(description=__doc__);sub=p.add_subparsers(dest='mode',required=True)
    sub.add_parser('generate');check=sub.add_parser('evaluate');check.add_argument('--junit',type=Path,required=True);check.add_argument('--out',type=Path,required=True)
    args=p.parse_args(argv)
    try:
        corpus=load()
        if args.mode=='generate':GENERATED.write_text(kotlin(corpus));return 0
        require(GENERATED.read_text()==kotlin(corpus),'GENERATED_INPUTS_CHANGED')
        require(not args.out.exists(),'OUTPUT_EXISTS')
        data=report(corpus,observations(args.junit));args.out.mkdir(parents=True)
        data['observationsSha256']=sha(args.junit.read_bytes());data['evaluatorSha256']=sha(Path(__file__).read_bytes())
        (args.out/'report.json').write_text(json.dumps(data,ensure_ascii=False,indent=2)+'\n')
        print(json.dumps({'totals':data['totals'],'semanticGatePassed':data['semanticGatePassed']},ensure_ascii=False))
        return 0 if data['semanticGatePassed'] else 2
    except (ValueError,OSError,KeyError,TypeError,ET.ParseError):
        print('SEMANTIC_EVALUATION_REJECTED');return 1

if __name__=='__main__':raise SystemExit(main())
