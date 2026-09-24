"""Frozen synthetic-only money protocol; dates are local, never cloud requests.

Unchanged targets use previous immutable responses in a separate offline audit.
"""
import argparse
import copy
import hashlib
import json
import time
from pathlib import Path
from deepl_credentials import load_key
from translation_deepl import DeepLClient, DeepLError, remaining_characters, MAX_BYTES
from translation_deepl_regression import cases as previous_cases
from translation_protected_facts import prepare, display, VERSION
from translation_probe import ROOT, preserve_chinese

CORPUS_SHA = '2b16400e074b8aa8ffa5b0fe96fa1e9a57e25ebeda8bf513cc97f64a34e66f19'
RUBRIC_SHA = '9dfd8630f77575b6607a4e582942f3dae135d014c09d0ee932e834386406e0af'


def cases():
    raw = (ROOT/'docs/fixtures/translation-holdout-v6.json').read_bytes()
    if hashlib.sha256(raw).hexdigest() != CORPUS_SHA: raise ValueError('Frozen corpus changed')
    parsed = json.loads(raw)
    if parsed.get('synthetic') is not True or len(parsed['cases']) != 4: raise ValueError('Invalid corpus')
    return previous_cases() + parsed['cases']


def source(page_id, target_id):
    page = next((c for c in cases() if c['id'] == page_id), None)
    if page is None or target_id not in page['targetIds'] or page['sourceLanguage'] not in ('en','fr'):
        raise ValueError('Unknown synthetic target')
    return page, next(b for b in page['blocks'] if b['id'] == target_id)


def request_body(page_id, target_id):
    page, block = source(page_id, target_id)
    decision = prepare(block['text'], page['sourceLanguage'])
    if decision['route'] != 'protected-money': raise ValueError('Not an online money target')
    blocks = sorted(page['blocks'], key=lambda b:b['readingOrder'])
    parts = []
    for i, b in enumerate(blocks):
        if i: parts.append('\n\n' if b['groupId'] != blocks[i-1]['groupId'] else '\n')
        parts.append(b['text'])
    result = {'text':[decision['value']['xml']], 'context':''.join(parts),
              'source_lang':page['sourceLanguage'].upper(), 'target_lang':'ZH-HANS',
              'show_billed_characters':True, 'tag_handling':'xml',
              'tag_handling_version':'v2', 'ignore_tags':['keep']}
    if len(json.dumps(result,ensure_ascii=False).encode()) > MAX_BYTES: raise ValueError('Request too large')
    return result


def bind_response(page_id, target_id, response):
    page, block = source(page_id, target_id)
    if not isinstance(response,dict) or set(response) != {'translations'}: raise ValueError('Invalid envelope')
    rows = response['translations']
    if not isinstance(rows,list) or len(rows)!=1 or not isinstance(rows[0],dict): raise ValueError('Invalid count')
    row = rows[0]
    if row.get('detected_source_language') != page['sourceLanguage'].upper() or not isinstance(row.get('text'),str):
        raise ValueError('Invalid language/text')
    return display(block, page_id, page['sourceLanguage'], row['text'])


def plan():
    rows = []; local = []; routes = {}
    for c in cases():
        if c['sourceLanguage'].startswith('zh'): continue
        for b in c['blocks']:
            if b['id'] not in c['targetIds']: continue
            decision = prepare(b['text'],c['sourceLanguage']); route = decision['route']
            routes[route] = routes.get(route,0) + 1
            if route in ('local-date','withheld'):
                local.append(display(b,c['id'],c['sourceLanguage']))
    for n in (1,2):
        for c in cases():
            if c['sourceLanguage'].startswith('zh'): continue
            for target in c['targetIds']:
                _, b = source(c['id'],target)
                if prepare(b['text'],c['sourceLanguage'])['route'] == 'protected-money':
                    rows.append({'round':n,'page':c['id'],'target':target,'request':request_body(c['id'],target)})
    return {'syntheticOnly':True,'qualityAccepted':False,'version':VERSION,'routesPerRound':routes,
            'calls':len(rows),'maxSourceCharacters':sum(len(r['request']['text'][0]) for r in rows),
            'paceSeconds':2,'retries':0,'rows':rows,'local':local}


def save(path,value): path.write_text(json.dumps(value,ensure_ascii=False,indent=2)+'\n')


def execute(out,client,sleep=time.sleep):
    frozen = plan(); completed = attempted = billed = 0
    try:
        usage = client.usage();save(out/'usage-before.json',usage)
        left = remaining_characters(usage)
        if usage['character_limit'] > 1000000: raise DeepLError('PLAN_LIMIT_NEEDS_REVIEW')
        if left < frozen['maxSourceCharacters']: raise DeepLError('INSUFFICIENT_QUOTA')
        for row in frozen['rows']:
            sleep(2); attempted += 1; start = time.monotonic()
            response = client._request('/v2/translate',row['request'])
            # Keep raw success responses even if binding later rejects their shape.
            with (out/'responses.jsonl').open('a') as stream:
                stream.write(json.dumps({**row,'response':response,'seconds':time.monotonic()-start},ensure_ascii=False)+'\n')
            result = bind_response(row['page'],row['target'],response)
            count = response['translations'][0].get('billed_characters')
            if type(count) is not int or not 0 <= count <= len(row['request']['text'][0]):
                raise DeepLError('BILLING_UNVERIFIED')
            billed += count
            with (out/'displays.jsonl').open('a') as stream:
                stream.write(json.dumps({'round':row['round'],'display':result},ensure_ascii=False)+'\n')
            completed += 1
            print(json.dumps({'completed':completed,'of':frozen['calls'],'state':result['state']}),flush=True)
        save(out/'usage-after.json',client.usage())
        status = {'status':'completed','completed':completed,'attempted':attempted,'billedCharacters':billed,'qualityAccepted':False}
    except (DeepLError,ValueError,OSError) as error:
        status = {'status':'stopped','completed':completed,'attempted':attempted,'billedCharacters':billed,
                  'error':str(error) if isinstance(error,DeepLError) else type(error).__name__,'retried':False,'qualityAccepted':False}
    save(out/'status.json',status)
    return status


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument('--out',type=Path,required=True)
    parser.add_argument('--execute-synthetic',action='store_true')
    args=parser.parse_args(); frozen=plan()
    if frozen['maxSourceCharacters'] > 5000: parser.error('Synthetic budget exceeded')
    paths = sorted((ROOT/'scripts').glob('translation*.py'))
    paths += [ROOT/'scripts/deepl_credentials.py']
    paths += sorted((ROOT/'docs/fixtures').glob('translation-*.json'))
    hashes={str(p.relative_to(ROOT)):hashlib.sha256(p.read_bytes()).hexdigest() for p in paths}
    if hashes.get('docs/fixtures/translation-rubric-v6.json') != RUBRIC_SHA: raise ValueError('Rubric changed')
    args.out.mkdir(parents=True,exist_ok=False)
    save(args.out/'plan.json',frozen);save(args.out/'source-hashes.json',hashes)
    save(args.out/'chinese-local.json',[preserve_chinese(c) for c in cases() if c['sourceLanguage'].startswith('zh')])
    print(json.dumps({k:v for k,v in frozen.items() if k not in ('rows','local')}),flush=True)
    if not args.execute_synthetic: return 0
    try: client=DeepLClient(load_key())
    except (ValueError,OSError):
        save(args.out/'status.json',{'status':'stopped','completed':0,'error':'CREDENTIAL_UNAVAILABLE'})
        return 1
    status=execute(args.out,client);print(json.dumps(status),flush=True)
    return 0 if status['status']=='completed' else 1


if __name__ == '__main__': raise SystemExit(main())
