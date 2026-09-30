"""Opt-in fixed synthetic grouped experiment. Dry run by default; no arbitrary input upload.
CLI matches v1: --out NEW [--execute-synthetic], --review-template RUN --out FILE,
--export RUN --review FILE --out FILE. No retries or resume. Provider output is never authored here.
"""
import argparse
import json
import re
import time
from collections import Counter
from datetime import datetime
from pathlib import Path
import translation_full_page_recorded as v1
from translation_full_page_recorded import require, encoded, sha, parse, read, save, pinned, fingerprint, stable_key
from translation_grouped_checks import head, tail, normalized, evaluate

ROOT = v1.ROOT
FIXTURES = ROOT / 'docs/fixtures/full-page-grouped-v1'
VERSION = 'full-page-semantic-layout-v1'
AUTHORED = 'AUTHORED_GROUPED_CONDITION'
OCR = 'RECORDED_GROUPED_OCR_SOURCE'
INPUT_SHA = '938198b215b5cdaa7a48eea033ba4b17f94966e7110618fb050c96f70dfe0ca3'
RUBRIC_SHA = '45809deffd4e459c0809c6b34bf9ff9e3f1c0f3b77a40bf7410acfb567575072'
PROTOCOL_SHA = 'd7bed331ff414d03da49339d67b70ca74082b4bd5b15f7b14899b2529517a675'
AUTHORED_SHA = 'd67acfda67f52a30f6e682d0fbf868f7a95c9ec46ae9e36161692bd73e8fa6f7'
CHECK_FILES = v1.CHECK_FILES + ['translation_full_page_grouped.py', 'translation_grouped_checks.py']


def bounds(c):
    q = c['pageQuad']
    return min(p[0] for p in q), min(p[1] for p in q), max(p[0] for p in q), max(p[1] for p in q)


def near_horizontal(c):
    q = c['pageQuad']
    if len(q) != 4:
        return False
    a, b, c_, d = q
    height = bounds(c)[3] - bounds(c)[1]
    if height <= 0 or not (a[0] < b[0] and d[0] < c_[0] and a[1] < d[1] and b[1] < c_[1]):
        return False
    for i in range(4):
        p, r, s = q[i], q[(i+1)%4], q[(i+2)%4]
        if (r[0]-p[0])*(s[1]-r[1])-(r[1]-p[1])*(s[0]-r[0]) <= 0:
            return False
    return abs(b[1]-a[1]) <= .02*(b[0]-a[0]) and abs(c_[1]-d[1]) <= .02*(c_[0]-d[0]) and \
        abs(d[0]-a[0]) <= .05*height and abs(c_[0]-b[0]) <= .05*height


def adjacent(a, b):
    l, t, r, d = bounds(a); x, y, z, w = bounds(b)
    h = min(d-t, w-y)
    return h > 0 and abs(l-x) <= .5*h and 0 <= y-d <= .75*h and max(d-t, w-y) <= 1.5*h


def overlap(a, b):
    l,t,r,d = bounds(a); x,y,z,w = bounds(b)
    return max(l,x) < min(r,z) and max(t,y) < min(d,w)


def blocks_pair(other, a, b):
    if overlap(other,a) or overlap(other,b):
        return True
    l,t,r,d = bounds(a); x,y,z,w = bounds(b); ol,ot,orr,ob = bounds(other)
    return max(l,x,ol) < min(r,z,orr) and ot < y and ob > d


def derive_layout(page):
    """Independent spatial computation. Never mutates archive data or picks an overlap winner."""
    raw = page['ocr']['rawCandidates']; require(len(raw) <= 128)
    ordered = sorted(raw, key=lambda c: (bounds(c)[1],bounds(c)[0],bounds(c)[3],bounds(c)[2],c['rawText'],stable_key(c['id'])))
    by_id = {c['id']: c for c in raw}
    original = {i:g for g in page['ocr']['association']['groups'] for i in g['memberIds']}
    graph = {c['id']: set() for c in raw}
    for a in ordered:
        if not head(a['rawText'],page['language']):
            continue
        for b in ordered:
            if a is not b and tail(b['rawText']) and adjacent(a,b):
                graph[a['id']].add(b['id']); graph[b['id']].add(a['id'])
    groups = []; seen = set()
    for c in ordered:
        cid = c['id']
        if cid in seen or not graph[cid]:
            continue
        todo = [cid]; component = set()
        while todo:
            i = todo.pop()
            if i not in component:
                component.add(i); todo.extend(graph[i]-component)
        seen |= component
        members = [x for x in ordered if x['id'] in component]
        reasons = []
        if len(members) != 2:
            reasons.append('NON_UNIQUE_ADJACENCY')
        else:
            a,b = members
            if any(blocks_pair(x,a,b) for x in raw if x['id'] not in component):
                reasons.append('INTERVENING_OR_OVERLAP_COMPETITOR')
            for x in members:
                g = original[x['id']]
                if g['text'] == 'DIFFERENT_RAW' or g['reasons'] not in ([], ['NON_AXIS_ALIGNED']):
                    reasons.append('ORIGINAL_DIAGNOSTIC_UNSUPPORTED')
                if not near_horizontal(x):
                    reasons.append('GEOMETRY_UNQUALIFIED')
        keys = [stable_key(x['id']) for x in members]
        groups.append({'key':'g:'+fingerprint([VERSION,*keys]), 'memberKeys':keys,
                       'eligible':not reasons,'ambiguous':bool(reasons),'reasons':list(dict.fromkeys(reasons)),
                       'qualification':['LIMITED_GROUP_GEOMETRY_QUALIFICATION'] if not reasons else []})
    group_by_key = {k:g for g in groups for k in g['memberKeys']}
    targets = []; blocked = []
    for c in ordered:
        key = stable_key(c['id']); group = group_by_key.get(key)
        if group:
            if group['eligible'] and group['key'] not in [t['key'] for t in targets]:
                sources = [next(x for x in ordered if stable_key(x['id']) == k) for k in group['memberKeys']]
                targets.append({'key':group['key'],'memberKeys':group['memberKeys'],'sourceText':'\n'.join(x['rawText'] for x in sources)})
        elif page['language'] in ('en','fr') and (tail(c['rawText']) or re.search(r'\b(refunds?|remboursement)\b',normalized(c['rawText']))):
            blocked.append(key)
        elif v1.known(c,original[c['id']]):
            targets.append({'key':key,'memberKeys':[key],'sourceText':c['rawText']})
    return {'version':VERSION,'orderedKeys':[stable_key(x['id']) for x in ordered],
            'orderUncertain':True,'groups':groups,'blockedKeys':blocked,'targets':targets,
            'context':'\n'.join(x['rawText'] for x in ordered)}


def grouped_fields(page):
    layout = derive_layout(page)
    fields = v1._canonical_fields(page) + ['grouped',VERSION,'orderUncertain','1','order',str(len(layout['orderedKeys'])),*layout['orderedKeys']]
    for g in layout['groups']:
        fields += ['phrase',g['key'],str(len(g['memberKeys'])),*g['memberKeys'],'1' if g['eligible'] else '0',
                   '1' if g['ambiguous'] else '0',str(len(g['reasons'])),*g['reasons'],str(len(g['qualification'])),*g['qualification']]
    fields += ['blocked',str(len(layout['blockedKeys'])),*layout['blockedKeys']]
    for target in layout['targets']:
        fields += ['target',target['key'],str(len(target['memberKeys'])),*target['memberKeys'],target['sourceText']]
    return fields + ['ordered-context',layout['context']]


def derive_inputs():
    data = v1.inputs()
    return {'schema':1,'syntheticOnly':True,'qualityAccepted':False,'parentInputSha256':v1.INPUT_SHA,
            'pages':[{**p,'layout':derive_layout(p),'canonicalFields':grouped_fields(p)} for p in data['pages']]}


def inputs():
    pinned(FIXTURES / 'protocol.json', PROTOCOL_SHA)
    pinned(FIXTURES / 'rubric.json', RUBRIC_SHA)
    pinned(FIXTURES / 'authored.json', AUTHORED_SHA)
    value = pinned(FIXTURES / 'inputs.json', INPUT_SHA)
    require(value == derive_inputs(), 'DERIVATION_CHANGED')
    return value


def canonical_fields(page_id):
    return next(p['canonicalFields'] for p in inputs()['pages'] if p['id']==page_id)


def source_hashes():
    fixed = v1.source_hashes()
    for name,expected in [('protocol',PROTOCOL_SHA),('rubric',RUBRIC_SHA),('authored',AUTHORED_SHA),('inputs',INPUT_SHA)]:
        path = FIXTURES / (name+'.json'); require(sha(path.read_bytes())==expected,'PINNED_SOURCE_CHANGED')
        fixed[str(path.relative_to(ROOT))]=expected
    for name in CHECK_FILES:
        fixed['scripts/'+name]=sha((ROOT / 'scripts' / name).read_bytes())
    return fixed


class DeepLAdapter:
    @staticmethod
    def request(row):
        # Preserve original member strings and their LF separator, but do not let
        # the provider treat that visual line wrap as two independent sentences.
        return {**v1.DeepLAdapter.request(row), 'split_sentences': 'nonewlines'}

    @staticmethod
    def response(row, response):
        old = v1.DeepLAdapter.response(row,response)  # retain original envelope/billing validation
        text = response['translations'][0]['text']
        check = evaluate(row['sourceText'],text,row['language'])
        # This milestone has only plain complete condition sources. Never bypass old protection errors.
        require(v1.prepare(row['sourceText'],row['language'])['route']=='plain','UNSUPPORTED_ROUTE')
        reasons = list(dict.fromkeys(check['reasons'] + [r for r in old['check']['reasons']
            if not (r=='refund-condition-unverified' and check['conditionMatched'])]))
        passed = not reasons
        check.update(reasons=reasons,status='candidate-unverified' if passed else 'withheld')
        return {**old,'state':'candidate-unverified' if passed else 'original-with-warning',
                'displayText':text if passed else row['sourceText'],'translation':text if passed else None,'check':check}


def plan():
    data = inputs(); rows = []
    for v in pinned(FIXTURES/'authored.json',AUTHORED_SHA)['variants']:
        rows.append({'track':AUTHORED,'page':v['id'],'key':'condition','sourceId':v['id']+'/condition',
                     'memberKeys':['condition-head','condition-tail'],'language':v['language'],
                     'sourceText':v['sourceText'],'context':v['context']})
    for page in data['pages']:
        if page['language'] not in ('en','fr'):
            continue
        for target in page['layout']['targets']:
            if len(target['memberKeys']) != 2:
                continue
            rows.append({'track':OCR,'page':page['id'],'key':target['key'],'sourceId':target['key'],
                         'memberKeys':target['memberKeys'],'language':page['language'],
                         'sourceText':target['sourceText'],'context':page['layout']['context']})
    for row in rows:
        row['id']=f'{row["track"]}/{row["page"]}/{row["key"]}'
        row['sourceSha256']=sha(row['sourceText'].encode())
        row['request']=DeepLAdapter.request(row); row['requestSha256']=sha(encoded(row['request']))
    require(len(rows)==10 and len({r['id'] for r in rows})==10,'PLAN_COUNT_CHANGED')
    require(sum(r['track']==AUTHORED for r in rows)==8 and sum(r['track']==OCR for r in rows)==2,'TRACK_CHANGED')
    budget=sum(len(r['request']['text'][0]) for r in rows);require(budget<=1000,'SOURCE_BUDGET_EXCEEDED')
    return {'schema':1,'provider':'DeepL','syntheticOnly':True,'qualityAccepted':False,'semanticVerified':False,
            'inputSha256':INPUT_SHA,'rubricSha256':RUBRIC_SHA,'protocolSha256':PROTOCOL_SHA,'authoredSha256':AUTHORED_SHA,
            'calls':10,'maxUsageCalls':2,'countsByTrack':{AUTHORED:8,OCR:2},'chineseCalls':0,'ambiguousCalls':0,
            'maxSourceCharacters':budget,'paceSeconds':2,'retries':0,'rows':rows}


utc_now = v1.utc_now
check_usage = v1.check_usage
safe_error = v1.safe_error

def run_hash(out):
    out = Path(out)
    names = ["plan", "source-hashes", "preflight", "status", "usage-before", "usage-after", "quota-preflight"] + [f"{prefix}-{i:02d}" for prefix in ("response", "receipt", "check") for i in range(10)]
    return fingerprint([item for name in names for item in (name, sha((out / (name+".json")).read_bytes()))])

def preflight(frozen):
    return {k: v for k, v in frozen.items() if k != 'rows'}

def new_run(out):
    frozen = plan(); hashes = source_hashes()
    out = Path(out); out.mkdir(parents=True, exist_ok=False)
    save(out / 'plan.json', frozen); save(out / 'source-hashes.json', hashes)
    save(out / 'preflight.json', preflight(frozen))
    save(out / 'status.json', {'status': 'dryrun', 'completed': 0, 'attempted': 0, 'qualityAccepted': False})
    return frozen

def validate_plan(out):
    frozen = plan()
    require(read(out / 'plan.json') == frozen, 'FROZEN_PLAN_CHANGED')
    require(read(out / 'source-hashes.json') == source_hashes(), 'SOURCE_HASHES_CHANGED')
    require(read(out / 'preflight.json') == preflight(frozen), 'PREFLIGHT_CHANGED')
    return frozen

def execute(out, client, sleep=time.sleep):
    """Client injection is for existing unittest fakes; CLI alone loads the key."""
    from translation_deepl import DeepLError
    out = Path(out); frozen = validate_plan(out)
    require(read(out / 'status.json')['status'] == 'dryrun', 'RESUME_REFUSED')
    started = utc_now(); completed = attempted = billed = 0
    status = {'status': 'running', 'recordedAt': started, 'qualityAccepted': False, 'retried': False}
    save(out / 'status.json', {**status, 'attempted': 0, 'completed': 0})
    try:
        usage = client.usage(); save(out / 'usage-before.json', usage)
        left = check_usage(usage, frozen['maxSourceCharacters'])
        save(out / 'quota-preflight.json', {'remainingCharacters': left, 'budget': frozen['maxSourceCharacters'], 'passed': True})
        for n, row in enumerate(frozen['rows']):
            if n:
                sleep(2)
            attempted += 1
            save(out / 'status.json', {**status, 'attempted': attempted, 'completed': completed,
                                      'billedCharacters': billed, 'quotaConsumptionUncertain': True})
            response = client._request('/v2/translate', row['request'])
            # Persist the complete unmodified parsed envelope BEFORE shape or binding checks.
            raw = encoded(response)
            (out / f'response-{n:02d}.json').write_bytes(raw)
            receipt = {'id': row['id'], 'requestSha256': row['requestSha256'], 'rawResponseSha256': sha(raw)}
            save(out / f'receipt-{n:02d}.json', receipt)
            bound = DeepLAdapter.response(row, response)
            save(out / f'check-{n:02d}.json', bound)
            billed += response['translations'][0]['billed_characters']; completed += 1
        after = client.usage(); save(out / 'usage-after.json', after); check_usage(after)
        status.update(status='completed', quotaConsumptionUncertain=False)
    except (DeepLError, ValueError, OSError, TypeError, KeyError) as error:
        status.update(status='stopped', error=safe_error(error), quotaConsumptionUncertain=attempted > completed)
    status.update(completed=completed, attempted=attempted, billedCharacters=billed)
    save(out / 'status.json', status)
    return status

def verified_run(out):
    out = Path(out); frozen = validate_plan(out); status = read(out / 'status.json')
    require(status.get('status') == 'completed' and status.get('completed') == 10 and
            status.get('attempted') == 10 and status.get('retried') is False and
            status.get('qualityAccepted') is False and status.get('quotaConsumptionUncertain') is False, 'PARTIAL_RUN')
    require(isinstance(status.get('recordedAt'), str) and status['recordedAt'].endswith('Z'), 'INVALID_RECORDED_TIME')
    datetime.fromisoformat(status['recordedAt'].replace('Z', '+00:00'))
    before = read(out / 'usage-before.json'); after = read(out / 'usage-after.json')
    left = check_usage(before, frozen['maxSourceCharacters']); check_usage(after)
    require(read(out / 'quota-preflight.json') == {'remainingCharacters': left, 'budget': frozen['maxSourceCharacters'], 'passed': True})
    records = []; billed = 0
    for prefix in ('response', 'receipt', 'check'):
        require({p.name for p in out.glob(prefix + '-*.json')} == {f'{prefix}-{i:02d}.json' for i in range(10)}, 'RECORD_COUNT_CHANGED')
    for n, row in enumerate(frozen['rows']):
        raw = (out / f'response-{n:02d}.json').read_bytes(); response = parse(raw)
        receipt = {'id': row['id'], 'requestSha256': row['requestSha256'], 'rawResponseSha256': sha(raw)}
        require(read(out / f'receipt-{n:02d}.json') == receipt, 'RESPONSE_IDENTITY_CHANGED')
        bound = DeepLAdapter.response(row, response)
        require(read(out / f'check-{n:02d}.json') == bound, 'CHECK_CHANGED')
        records.append((row, receipt, bound)); billed += response['translations'][0]['billed_characters']
    require(status.get('billedCharacters') == billed, 'BILLING_CHANGED')
    return frozen, status, records

def review_identity(row, receipt):
    return {k: row[k] for k in ('id', 'track', 'page', 'key', 'sourceId', 'sourceSha256', 'requestSha256', 'memberKeys')} | \
        {'rawResponseSha256': receipt['rawResponseSha256']}

def review_template(run):
    frozen, _, records = verified_run(run)
    return {'schema': 1, 'kind': 'DEVELOPER_REVIEW', 'inputSha256': INPUT_SHA, 'rubricSha256': RUBRIC_SHA,
            'planSha256': sha(encoded(frozen)), 'runSha256': run_hash(run), 'reviewer': None,
            'entries': [{**review_identity(row, receipt), 'verdict': None, 'sourceQuality': None,
                         'contextDefects': None, 'notes': None} for row, receipt, _ in records]}

def validate_review(run, review):
    template = review_template(run)
    require(set(review) == set(template), 'REVIEW_ENVELOPE_CHANGED')
    for k in ('schema', 'kind', 'inputSha256', 'rubricSha256', 'planSha256', 'runSha256'):
        require(review[k] == template[k], 'REVIEW_IDENTITY_CHANGED')
    require(isinstance(review['reviewer'], str) and bool(review['reviewer'].strip()), 'REVIEWER_REQUIRED')
    require(isinstance(review['entries'], list) and len(review['entries']) == 10, 'REVIEW_COUNT_CHANGED')
    by_id = {r['id']: r for r in review['entries']}
    require(len(by_id) == 10 and set(by_id) == {r['id'] for r in template['entries']}, 'REVIEW_IDENTITY_CHANGED')
    for expected in template['entries']:
        actual = by_id[expected['id']]
        require(set(actual) == set(expected), 'REVIEW_FIELDS_CHANGED')
        for k in ('id', 'track', 'page', 'key', 'sourceId', 'sourceSha256', 'requestSha256', 'rawResponseSha256', 'memberKeys'):
            require(actual[k] == expected[k], 'REVIEW_IDENTITY_CHANGED')
        require(actual['verdict'] in ('pass', 'fail', 'uncertain'), 'REVIEW_VERDICT_REQUIRED')
        require(actual['sourceQuality'] in ('correct', 'incorrect', 'uncertain'), 'SOURCE_REVIEW_REQUIRED')
        require(isinstance(actual['contextDefects'], str) and isinstance(actual['notes'], str) and
                bool(actual['notes'].strip()), 'REVIEW_NOTES_REQUIRED')
    return by_id

def main(argv=None):
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--out', type=Path, required=True)
    mode = parser.add_mutually_exclusive_group()
    mode.add_argument('--execute-synthetic', action='store_true')
    mode.add_argument('--export', type=Path)
    mode.add_argument('--review-template', type=Path)
    parser.add_argument('--review', type=Path)
    args = parser.parse_args(argv)
    try:
        require(bool(args.export) == bool(args.review), 'EXPORT_REQUIRES_REVIEW')
        if args.export or args.review_template:
            require(not args.out.exists(), 'OUTPUT_EXISTS')
            result = export_packet(args.export, args.review) if args.export else review_template(args.review_template)
            save(args.out, result); return 0
        frozen = new_run(args.out)
        print(json.dumps(preflight(frozen)), flush=True)
        if not args.execute_synthetic:
            return 0
        # Deliberately imported only in the explicit execution branch.
        from deepl_credentials import load_key
        from translation_deepl import DeepLClient
        try:
            client = DeepLClient(load_key())
        except (ValueError, OSError):
            save(args.out / 'status.json', {'status': 'stopped', 'error': 'CREDENTIAL_UNAVAILABLE',
                                          'completed': 0, 'attempted': 0, 'qualityAccepted': False})
            return 1
        status = execute(args.out, client)
        print(json.dumps(status), flush=True)
        return 0 if status['status'] == 'completed' else 1
    except (ValueError, OSError, KeyError, TypeError, AttributeError, IndexError, StopIteration):
        print('RECORDED_EVIDENCE_REJECTED', flush=True)
        return 1

def export_packet(run, review_path):
    frozen, status, records = verified_run(run)
    review_raw = Path(review_path).read_bytes(); reviews = validate_review(run, parse(review_raw))
    outcomes = {}; diagnostics = []
    for row, receipt, bound in records:
        review = reviews[row['id']]; rule_pass = bound['state']=='candidate-unverified'
        allowed = review['verdict']=='pass' and review['sourceQuality']=='correct' and rule_pass
        diagnostic = {**review_identity(row,receipt),'sourceQuality':review['sourceQuality'],'verdict':review['verdict'],
                      'contextDefects':review['contextDefects'],'rulePassed':rule_pass,'ruleReasons':bound['check']['reasons'],
                      'uiCandidate':row['track']==OCR and allowed,'semanticVerified':False}
        diagnostics.append(diagnostic)
        if row['track']==OCR:
            outcomes[(row['page'],row['key'])] = {'key':row['key'],'memberKeys':row['memberKeys'],
                'sourceText':row['sourceText'],'chinese':bound['translation'] if allowed else None,
                'kind':'CANDIDATE' if allowed else 'KEEP_ORIGINAL','origin':'RECORDED_DEEPL' if allowed else 'SOURCE',
                'reason':None if allowed else 'CHECK_UNVERIFIED','semanticVerified':False,
                'sourceQuality':review['sourceQuality'],'verdict':review['verdict'],'rulePassed':rule_pass,
                'rawResponseSha256':receipt['rawResponseSha256'],'requestSha256':row['requestSha256']}
    pages=[]
    for page in inputs()['pages']:
        layout=page['layout']; results=[]
        for target in layout['targets']:
            result=outcomes.get((page['id'],target['key']))
            if result is None:
                result={**target,'chinese':None,'kind':'KEEP_ORIGINAL','origin':'SOURCE',
                        'reason':'ALREADY_CHINESE' if page['language'].startswith('zh') else 'NO_RECORDED_RESULT',
                        'semanticVerified':False,'sourceQuality':'unreviewed','verdict':'unreviewed','rulePassed':False,
                        'rawResponseSha256':None,'requestSha256':None}
            results.append(result)
        pages.append({k:page[k] for k in ('id','language','width','height')} | {
            'fingerprint':fingerprint(page['canonicalFields']),'canonicalFields':page['canonicalFields'],
            'layout':layout,'targetKeys':[t['key'] for t in layout['targets']],'outcomes':results})
    return {'schema':1,'syntheticOnly':True,'qualityAccepted':False,'semanticVerified':False,'provider':'DeepL',
            'mode':'RECORDED_GROUPED','layoutVersion':VERSION,'inputSha256':INPUT_SHA,'rubricSha256':RUBRIC_SHA,
            'protocolSha256':PROTOCOL_SHA,'authoredSha256':AUTHORED_SHA,'sourceHashes':source_hashes(),
            'sourceHashesSha256':sha(encoded(source_hashes())),'runSha256':run_hash(run),
            'reviewSha256':sha(review_raw),'recordedAt':status['recordedAt'],'pages':pages,
            'report':{'preflight':preflight(frozen),'status':status,'diagnostics':diagnostics,
                      'usageBefore':read(Path(run)/'usage-before.json'),'usageAfter':read(Path(run)/'usage-after.json'),
                      'reviewKind':'DEVELOPER_REVIEW_NOT_BLIND_QUALITY_ACCEPTANCE'}}


if __name__ == '__main__':
    raise SystemExit(main())
