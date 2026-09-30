"""Check or explicitly regenerate JVM inputs and separate semantic expectations from frozen JSON."""
import argparse
import hashlib
import json
from pathlib import Path
import semantic_context_eval as baseline

ROOT=Path(__file__).resolve().parents[1]
CORPUS=ROOT/'docs/fixtures/condition-guard-v1/corpus.json'
SHA='9cb4274bd1b6d455e34182827cd7574a6575cf86fd329b123062c38347f912ab'
TESTS=ROOT/'modelprobe/src/test/java/com/kandong/modelprobe'

def sources():
    raw=CORPUS.read_bytes()
    if hashlib.sha256(raw).hexdigest()!=SHA:raise ValueError('CORPUS_CHANGED')
    fresh=json.loads(raw);old=baseline.load()
    if len(fresh['pages'])!=18 or sum(len(p['items']) for p in fresh['pages'])!=72:raise ValueError('CORPUS_SIZE')
    code=baseline.kotlin(fresh).replace('SemanticContextEvalCorpus','ConditionGuardCorpus').replace(baseline.CORPUS_SHA,SHA)
    start=code.index('    data class Item(');end=code.index('    fun pages()',start)
    code=code[:start]+code[end:]
    code=code.replace('package com.kandong.modelprobe','package com.kandong.modelprobe\n\nimport com.kandong.modelprobe.SemanticContextEvalCorpus.Item\nimport com.kandong.modelprobe.SemanticContextEvalCorpus.Page')
    lines=['package com.kandong.modelprobe','','/** Frozen semantic expectations, never passed into the guard or adapter. */',
           'internal object ConditionGuardExpectations {',
           '    data class Expected(val complete:List<Set<String>>,val protected:Set<String>,val hold:Boolean)',
           '    val pages=mapOf(']
    quote=lambda s:json.dumps(s,ensure_ascii=False)
    kotlin_set=lambda xs:'setOf('+','.join(map(quote,xs))+')'
    for p in old['pages']+fresh['pages']:
        expected=p.get('oracle',p)
        hold=p.get('expectedWholePageHold',p['id'] in ['en-trailing-exception','fr-trailing-restriction','en-leading-negated-claim','fr-leading-prerequisite'])
        lines.append(f'        {quote(p["id"])} to Expected(listOf('+','.join(kotlin_set(g) for g in expected['completeGroups'])+'),'+kotlin_set(expected['protectedItems'])+','+str(hold).lower()+'),')
    lines+=['    )','}','']
    return {'ConditionGuardCorpus.kt':code,'ConditionGuardExpectations.kt':'\n'.join(lines)}

def main():
    parser=argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--write',action='store_true',help='explicitly regenerate the two known test files')
    args=parser.parse_args()
    for name,contents in sources().items():
        path=TESTS/name
        if args.write:path.write_text(contents)
        elif path.read_text()!=contents:raise ValueError('GENERATED_FILE_CHANGED: '+name)
    print('FROZEN_CONDITION_GUARD_FIXTURES_OK')

if __name__=='__main__':main()
