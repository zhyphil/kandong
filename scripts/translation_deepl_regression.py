"""Frozen full-regression + new single-language pages, DeepL synthetic data only."""
import argparse
import copy
import hashlib
import json
import time
from pathlib import Path
from deepl_credentials import load_key
from translation_candidate_c import cases as prior_cases
from translation_critical_checks import guarded_display, VERSION
from translation_deepl import DeepLClient, DeepLError, remaining_characters, MAX_BYTES
from translation_probe import ROOT, bind, preserve_chinese

NEW_CORPUS_SHA = 'bb8188dd8a7ab9b42cae088fa21222938ad9e566c934e89d6ded040f640e6d1c'


def cases():
    data = (ROOT / 'docs/fixtures/translation-holdout-v5.json').read_bytes()
    if hashlib.sha256(data).hexdigest() != NEW_CORPUS_SHA: raise ValueError('Frozen corpus changed')
    new = json.loads(data)
    if new.get('synthetic') is not True or len(new['cases']) != 4: raise ValueError('Synthetic corpus required')
    return prior_cases() + new['cases']


def case_by_id(page_id):
    found = [c for c in cases() if c['id'] == page_id]
    if len(found) != 1: raise ValueError('Unknown synthetic page')
    return found[0]


def request_body(page_id, target_id):
    c = case_by_id(page_id)
    if c['sourceLanguage'] not in ('en','fr') or target_id not in c['targetIds']:
        raise ValueError('Unsupported synthetic target')
    blocks = sorted(c['blocks'], key=lambda b: b['readingOrder'])
    parts = []
    for index, block in enumerate(blocks):
        if index: parts.append('\n\n' if blocks[index-1]['groupId'] != block['groupId'] else '\n')
        parts.append(block['text'])
    target = next(b for b in blocks if b['id'] == target_id)
    body = {'text':[target['text']], 'source_lang':c['sourceLanguage'].upper(),
            'target_lang':'ZH-HANS','show_billed_characters':True,'context':''.join(parts)}
    if len(json.dumps(body,ensure_ascii=False).encode()) > MAX_BYTES: raise ValueError('Request too large')
    return body


def bind_response(page_id, target_id, response):
    c = copy.deepcopy(case_by_id(page_id))
    if c['sourceLanguage'] not in ('en','fr') or target_id not in c['targetIds']: raise ValueError('Invalid target')
    if not isinstance(response,dict) or set(response) != {'translations'}: raise ValueError('Invalid envelope')
    rows = response['translations']
    if not isinstance(rows,list) or len(rows)!=1 or not isinstance(rows[0],dict): raise ValueError('Invalid count')
    row=rows[0]
    if row.get('detected_source_language') != c['sourceLanguage'].upper() or not isinstance(row.get('text'),str):
        raise ValueError('Invalid language/text')
    c['targetIds']=[target_id]
    return bind(c,json.dumps({'translations':[{'id':target_id,'text':row['text']}]}))[0]


def plan():
    rows=[]
    for n in (1,2):
        for c in cases():
            if c['sourceLanguage'].startswith('zh'):continue
            for target in c['targetIds']:
                rows.append({'round':n,'page':c['id'],'target':target,
                             'request':request_body(c['id'],target)})
    return {'syntheticOnly':True,'qualityAccepted':False,'guardVersion':VERSION,
            'oldTargets':48,'newTargets':8,'calls':len(rows),
            'sourceCharacters':sum(len(r['request']['text'][0]) for r in rows),
            'paceSeconds':2,'retries':0,'rows':rows}


def save(path, value): path.write_text(json.dumps(value,ensure_ascii=False,indent=2)+'\n')


def execute(out, client, sleep=time.sleep):
    frozen=plan();completed=0;attempted=0;blocked=0
    try:
        usage=client.usage();save(out/'usage-before.json',usage)
        left=remaining_characters(usage)
        if usage['character_limit']>1000000:raise DeepLError('PLAN_LIMIT_NEEDS_REVIEW')
        if left<frozen['sourceCharacters']:raise DeepLError('INSUFFICIENT_QUOTA')
        for index,row in enumerate(frozen['rows']):
            sleep(2);start=time.monotonic();attempted+=len(row['request']['text'][0])
            response=client._request('/v2/translate',row['request'])
            bound=bind_response(row['page'],row['target'],response)
            display=guarded_display(bound,case_by_id(row['page'])['sourceLanguage'])
            blocked+=display['state']=='original-with-warning'
            value=response['translations'][0]
            save(out/f'response-{index:03}.json',{**row,'bound':bound,'display':display,
                 'seconds':time.monotonic()-start,
                 'responseMetadata':{k:value[k] for k in ('detected_source_language','billed_characters','model_type_used') if k in value}})
            completed+=1
            print(json.dumps({'completed':completed,'of':frozen['calls'],'withheld':blocked}),flush=True)
        save(out/'usage-after.json',client.usage())
        status={'status':'completed','completed':completed,'attemptedSourceCharacters':attempted,
                'withheld':blocked,'candidateUnverified':completed-blocked,'qualityAccepted':False}
    except (DeepLError,ValueError,OSError) as error:
        status={'status':'stopped','completed':completed,'attemptedSourceCharacters':attempted,
                'error':str(error) if isinstance(error,DeepLError) else type(error).__name__,
                'withheld':blocked,'qualityAccepted':False,'retried':False}
    save(out/'status.json',status)
    return status


def main():
    p=argparse.ArgumentParser()
    p.add_argument('--out',type=Path,required=True)
    p.add_argument('--execute-synthetic',action='store_true')
    args=p.parse_args();frozen=plan()
    if frozen['sourceCharacters']>5000:p.error('source character budget exceeded')
    args.out.mkdir(parents=True,exist_ok=False)
    save(args.out/'plan.json',frozen)
    save(args.out/'chinese-local.json',[preserve_chinese(c) for c in cases() if c['sourceLanguage'].startswith('zh')])
    scripts=['translation_deepl_regression.py','translation_critical_checks.py','translation_fact_checks.py',
             'deepl_credentials.py','translation_deepl.py','translation_candidate_c.py',
             'translation_candidate_v3.py','translation_probe.py','translation_probe_v2.py']
    paths=[ROOT/'scripts'/n for n in scripts]
    paths += [ROOT/'docs/fixtures'/n for n in ['translation-pages-v1.json','translation-holdout-v2.json',
              'translation-holdout-v3.json','translation-holdout-v4.json','translation-holdout-v5.json']]
    save(args.out/'source-hashes.json',{str(f.relative_to(ROOT)):hashlib.sha256(f.read_bytes()).hexdigest() for f in paths})
    print(json.dumps({k:v for k,v in frozen.items() if k!='rows'}),flush=True)
    if not args.execute_synthetic:return 0
    try:client=DeepLClient(load_key())
    except (ValueError,OSError):
        save(args.out/'status.json',{'status':'stopped','completed':0,'error':'CREDENTIAL_UNAVAILABLE'})
        return 1
    status=execute(args.out,client)
    print(json.dumps(status),flush=True)
    return 0 if status['status']=='completed' else 1


if __name__=='__main__':raise SystemExit(main())
