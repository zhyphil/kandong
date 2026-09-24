#!/usr/bin/env python3
"""Run two frozen synthetic rounds against the dedicated loopback-only model server."""
import argparse
import hashlib
import json
import os
from pathlib import Path
import platform
import time
from translation_probe import (ROOT,MODEL,load_cases,request_body,bind,preserve_chinese,local_api)

def save(p, data):
    p.write_text(json.dumps(data,ensure_ascii=False,indent=2,allow_nan=False)+'\n')

def main():
    parser=argparse.ArgumentParser();parser.add_argument('--out',type=Path,required=True);args=parser.parse_args()
    args.out.mkdir(parents=True,exist_ok=False)
    cases=load_cases()
    metadata=dict(host=platform.platform(),runtime=local_api('/api/version'),model=local_api('/api/show',dict(model=MODEL)),
        scripts={f:hashlib.sha256((ROOT/'scripts'/f).read_bytes()).hexdigest() for f in
        ['translation_probe.py','run-translation-probe.py']},started=time.strftime('%Y-%m-%dT%H:%M:%S%z'))
    save(args.out/'metadata.json',metadata)
    allrows=[]
    try:
        for round_id in (1,2):
            local_api('/api/generate',dict(model=MODEL,keep_alive=0))
            for c in cases:
                for mode in (['full','target-only'] if not c['sourceLanguage'].startswith('zh') else ['original']):
                    row=dict(round=round_id,page=c['id'],mode=mode,sourceLanguage=c['sourceLanguage'])
                    path=args.out/f'r{round_id}-{c["id"]}-{mode}.json'
                    if mode=='original':
                        row.update(modelCalled=False,bound=preserve_chinese(c),elapsedSeconds=0)
                    else:
                        request=request_body(c,mode=='full'); row.update(modelCalled=True,request=request)
                        save(path,row) # actual request survives timeout/process failure
                        start=time.monotonic()
                        try:
                            response=local_api('/api/chat',request);row['response']=response
                            if not response.get('done') or response.get('done_reason')!='stop':
                                raise ValueError('Incomplete or truncated generation')
                            if response.get('message',{}).get('tool_calls'): raise ValueError('Unexpected tool call')
                            row['bound']=bind(c,response['message']['content'])
                        except Exception as e:row['error']=f'{type(e).__name__}: {e}'
                        row['elapsedSeconds']=time.monotonic()-start
                        row['loadedModel']=local_api('/api/ps')
                    save(path,row);allrows.append(row)
                    print(json.dumps(dict(round=round_id,page=c['id'],mode=mode,seconds=round(row['elapsedSeconds'],3),
                        error=row.get('error'),output=[(b['id'],b['text']) for b in row.get('bound',[])]),ensure_ascii=False),flush=True)
        save(args.out/'index.json',dict(rows=[dict(round=r['round'],page=r['page'],mode=r['mode'],
            error=r.get('error'),seconds=r['elapsedSeconds']) for r in allrows]))
    finally:
        local_api('/api/generate',dict(model=MODEL,keep_alive=0))
        save(args.out/'after-unload.json',local_api('/api/ps'))
    return int(any(r.get('error') for r in allrows))

if __name__=='__main__':raise SystemExit(main())
