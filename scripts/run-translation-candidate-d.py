#!/usr/bin/env python3
"""Bounded local Qwen3.5 comparison; only committed synthetic inputs."""
import argparse
import hashlib
import json
import platform
import time
from pathlib import Path
from translation_probe import ROOT,local_api,preserve_chinese
from translation_candidate_d import MODEL,MANIFEST,cases,request_body,validate

def save(path,data):path.write_text(json.dumps(data,ensure_ascii=False,indent=2)+'\n')

def main():
    p=argparse.ArgumentParser();p.add_argument('--out',type=Path,required=True);p.add_argument('--thinking',action='store_true');a=p.parse_args()
    if a.thinking:raise ValueError('C is non-thinking only')
    a.out.mkdir(parents=True,exist_ok=False)
    metadata=dict(model=local_api('/api/show',dict(model=MODEL)),version=local_api('/api/version'),thinking=a.thinking,
        host=platform.platform(),sourceHashes={n:hashlib.sha256((ROOT/'scripts'/n).read_bytes()).hexdigest() for n in
        ['translation_candidate_d.py','translation_candidate_c.py','translation_candidate_v3.py','run-translation-candidate-d.py','translation_probe.py','translation_probe_v2.py']})
    save(a.out/'metadata.json',metadata);rows=[]
    try:
        for round_id in (1,2):
            local_api('/api/generate',dict(model=MODEL,keep_alive=0))
            for c in cases():
                modes=['original'] if c['sourceLanguage'].startswith('zh') else ['full'] if a.thinking else ['full','target-only']
                for mode in modes:
                    row=dict(round=round_id,page=c['id'],mode=mode,thinking=a.thinking)
                    path=a.out/f'r{round_id}-{c["id"]}-{mode}.json'
                    if mode=='original':row.update(modelCalled=False,bound=preserve_chinese(c),seconds=0)
                    else:
                        row.update(modelCalled=True,request=request_body(c,mode=='full',a.thinking));save(path,row);start=time.monotonic()
                        try:
                            res=local_api('/api/chat',row['request']);row['response']=res
                            if not res.get('done') or res.get('done_reason')!='stop':raise ValueError('Incomplete generation')
                            if res.get('message',{}).get('tool_calls'):raise ValueError('Unexpected tools')
                            if not a.thinking and res.get('message',{}).get('thinking'):raise ValueError('Unexpected thinking mode')
                            row['bound']=validate(c,res['message']['content'])
                        except Exception as e:row['error']=f'{type(e).__name__}: {e}'
                        row['seconds']=time.monotonic()-start;row['loadedModel']=local_api('/api/ps')
                        if any(m['digest']!=MANIFEST for m in row['loadedModel'].get('models',[])):raise ValueError('Model identity changed')
                    save(path,row);rows.append(row)
                    print(json.dumps(dict(round=round_id,page=c['id'],mode=mode,seconds=round(row['seconds'],2),error=row.get('error'),output=[(b['id'],b['text'],b.get('literalIssues')) for b in row.get('bound',[])]),ensure_ascii=False),flush=True)
        save(a.out/'index.json',dict(rows=[{k:r.get(k) for k in ['round','page','mode','seconds','error']} for r in rows]))
    finally:
        local_api('/api/generate',dict(model=MODEL,keep_alive=0));save(a.out/'after-unload.json',local_api('/api/ps'))
    return int(any(r.get('error') for r in rows))

if __name__=='__main__':raise SystemExit(main())
