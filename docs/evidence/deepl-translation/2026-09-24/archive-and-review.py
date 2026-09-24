import gzip
import hashlib
import json
import statistics
from collections import Counter
from pathlib import Path

ROOT = Path('/Users/haoyuzuo/Projects/KanDong')
TEMP = Path('/private/tmp/kandong-online-phase')
EVIDENCE = ROOT / 'docs/evidence/deepl-translation/2026-09-24'


def read(p): return json.loads(p.read_text())
def save(p, data): p.write_text(json.dumps(data, ensure_ascii=False, indent=2) + '\n')


def archive_run(temp_name, name, frozen_name):
    src = TEMP / temp_name; out = EVIDENCE / name
    frozen = EVIDENCE / frozen_name
    assert read(src/'plan.json') == read(frozen/'plan.json')
    assert read(src/'source-hashes.json') == read(frozen/'source-hashes.json')
    for path, expected in read(src/'source-hashes.json').items():
        file = (frozen/'diagnostic.py') if path == 'diagnostic-script' else ROOT / (path if '/' in path else 'scripts/'+path)
        assert hashlib.sha256(file.read_bytes()).hexdigest() == expected, path
    rows = [read(f) for f in sorted(src.glob('response-*.json'))]
    plan = read(src/'plan.json')
    assert len(rows) == plan['calls'] == read(src/'status.json')['completed']
    for row, fixed in zip(rows, plan['rows']):
        assert all(row[k] == v for k,v in fixed.items())
        assert row['responseMetadata']['detected_source_language'] == row['request']['source_lang']
        assert row['responseMetadata']['billed_characters'] == len(row['request']['text'][0])
        if 'target' in row:
            assert row['bound']['id'] == row['target'] and row['bound']['pageId'] == row['page']
            assert row['bound']['source']['text'] == row['request']['text'][0]
    out.mkdir(exist_ok=False)
    for name in ('plan.json', 'source-hashes.json', 'status.json', 'usage-before.json', 'usage-after.json'):
        (out/name).write_bytes((src/name).read_bytes())
    (out/'responses.jsonl.gz').write_bytes(gzip.compress(('\n'.join(json.dumps(r,ensure_ascii=False) for r in rows)+'\n').encode(),mtime=0))
    save(out/'audit.json', {'completed':len(rows),'frozenPlanMatched':True,'sourceHashesMatched':True,
                           'billedCharacters':sum(r['responseMetadata']['billed_characters'] for r in rows),
                           'elementBinding':'target' in rows[0], 'qualityAccepted':False})
    return rows


plain = archive_run('deepl-plain-run1','plain-context-run1','plain-context-frozen')
whole = archive_run('deepl-whole-run1','whole-page-run1','whole-page-frozen')
baseline = [json.loads(l) for l in gzip.decompress((EVIDENCE/'json-context-run1/responses.jsonl.gz').read_bytes()).decode().splitlines()]

# Engineering-agent review against frozen meanings, not a user/independent translator acceptance.
# Currency rule already in TRANSLATION_NEXT_CANDIDATE: do not silently identify bare $ as USD.
exceptions = {
 ('p001','b005'):('fail','Hotel booking action became a book.'),
 ('p003','b004'):('fail','Currency identity omitted.'),
 ('p005','b004'):('fail','Theft became a flight.'),
 ('p006','b003'):('needs-review','Ticket exchange/change became generic redemption; not accepted as equivalent.'),
 ('p102','b004'):('fail','Restaurant booking action became a book.'),
 ('p103','b003'):('needs-review','Flight service label became generic flying; precision not accepted.'),
 ('p104','b004'):('fail','Theft became flying.'),
 ('p105','b007'):('fail','Bare $ identified as USD without explicit source currency.'),
 ('p106','b004'):('fail','Valid on one date became valid until a date.'),
 ('p201','b004'):('fail','Returning goods became navigation back.'),
 ('p203','b003'):('fail','Restaurant bill became a note.'),
 ('p204','b003'):('fail','Rating became a note.'),
 ('p205','b007'):('fail','Bare $ identified as USD without explicit source currency.'),
}
rubrics = {}
for name in ['translation-rubric-v1.json','translation-holdout-rubric-v2.json','translation-holdout-rubric-v3.json','translation-holdout-rubric-v4.json']:
    for c in read(ROOT/'docs/fixtures'/name)['cases']: rubrics[c['id']] = c['criteria']
review = []
for r in baseline:
    if r['mode'] != 'full': continue
    result, reason = exceptions.get((r['page'],r['target']),('pass','Source meaning preserved; source geometry/group remains locally bound.'))
    review.append({'page':r['page'],'target':r['target'],'source':r['request']['text'][0],
                   'translation':r['bound']['text'],'frozenCriterion':rubrics[r['page']][r['target']],
                   'judgment':result,'reason':reason})
save(EVIDENCE/'json-context-run1/semantic-review.json',{'reviewer':'engineering agent, not an independent human panel',
     'scope':'37 completed full-context targets only; first round incomplete, target-only controls unscored here',
     'currencyRule':'Bare $ is not automatically USD; original v1 rubric wording predates this correction.',
     'counts':dict(Counter(r['judgment'] for r in review)),'qualityAccepted':False,'rows':review})
base_lookup = {(r['page'],r['target'],r['mode']): r for r in baseline}
comparisons = []
for r in plain:
    key = (r['page'],r['target'])
    judgment,reason = (('fail','Single-day validity remains a deadline.') if key == ('p106','b004') else
                      ('fail','Bare $ is still interpreted as USD without explicit identity.') if key == ('p003','b004') else
                      ('pass','Ambiguous label now matches whole-page meaning.'))
    comparisons.append({'page':r['page'],'target':r['target'],'source':r['request']['text'][0],
        'jsonContext':base_lookup[(*key,'full')]['bound']['text'],
        'targetOnly':base_lookup[(*key,'target-only')]['bound']['text'], 'plainContext':r['bound']['text'],
        'judgment':judgment,'reason':reason})
save(EVIDENCE/'plain-context-run1/semantic-review.json',{'reviewer':'engineering agent',
     'scope':'14 selected regression targets, one round; not a new blind holdout',
     'counts':dict(Counter(r['judgment'] for r in comparisons)),'qualityAccepted':False,'rows':comparisons})
whole_review = []
for row in whole:
    checks = ({'singleDay':'fail: still translated as until','amount':'pass: EUR37.80','baggage':'pass: 2 bags, max6kg each',
               'refund':'pass: no refund after departure'} if row['page']=='p106' else
              {'untilInclusive':'pass: until9Mar2027inclusive','baggage':'pass:3bags,max5kg each',
               'exchange':'needs-review: redemption vs ticket exchange','refund':'pass: no refund'} if row['page']=='p304' else
              {'currency':'fail: some $ values identified as USD without explicit source identity',
               'pricesAndTax':'pass: prices/periods/tax retained in raw text',
               'cancellationAndPayment':'pass: deadline/refund/immediate payment retained'})
    whole_review.append({'page':row['page'],'round':row['round'],'checks':checks,
                         'translation':row['rawWholePageTranslation'],'elementBinding':False})
save(EVIDENCE/'whole-page-run1/semantic-review.json',{'reviewer':'engineering agent','qualityAccepted':False,
     'conclusion':'Whole-page text did not resolve date/currency failures in either round. Do not split/rebind raw output.',
     'rows':whole_review})
all_rows = baseline + plain + whole
save(EVIDENCE/'live-summary.json',{'provider':'DeepL','syntheticOnly':True,'successfulTranslationCalls':len(all_rows),
     'failedTranslationCalls':1,'failedCode':'HTTP_429','successfulBilledCharacters':sum(r['responseMetadata']['billed_characters'] for r in all_rows),
     'lastObservedUsage':read(EVIDENCE/'whole-page-run1/usage-after.json'),
     'credentialPrinted':False,'realScreenTransferred':False,'phoneUpdated':False,'qualityAccepted':False,
     'jsonFull37Counts':dict(Counter(r['judgment'] for r in review)),
     'plainSelected14Counts':dict(Counter(r['judgment'] for r in comparisons)),
     'plainCallSecondsMedian':statistics.median(r['seconds'] for r in plain),
     'next':'Full plain-context regression with faithful date/currency handling; no production integration yet'})
print(json.dumps(read(EVIDENCE/'live-summary.json'),ensure_ascii=False,indent=2))
