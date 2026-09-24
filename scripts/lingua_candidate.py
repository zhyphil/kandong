"""Synthetic host-only policy C, fixed in LINGUA_SCREENING_PLAN before inference."""
import copy
import hashlib
import json
import math
import re

SUPPORTED = {'en', 'fr', 'zh'}


class EvidenceError(ValueError):
    pass


def require(condition, message):
    if not condition:
        raise EvidenceError(message)


def page_id(blocks):
    return hashlib.sha256(json.dumps(blocks, ensure_ascii=False, sort_keys=True,
                                     separators=(',', ':')).encode()).hexdigest()


def queries(page):
    blocks = page['blocks']
    require(isinstance(blocks, list) and 0 < len(blocks) <= 32, 'block budget')
    require(all(isinstance(b['text'], str) and len(b['text']) <= 512 for b in blocks), 'text budget')
    require(sum(len(b['text']) for b in blocks) <= 8192, 'page budget')
    require(all(isinstance(b['id'], str) and b['id'] and isinstance(b['groupId'], str)
                and b['groupId'] and type(b['ocrConflict']) is bool for b in blocks), 'block metadata')
    require(len({b['id'] for b in blocks}) == len(blocks), 'duplicate block')
    require(page['targetId'] in {b['id'] for b in blocks}, 'missing target')
    require(page['pageId'] == page_id(blocks), 'page identity')
    groups = dict.fromkeys(b['groupId'] for b in blocks)
    result = {'page': '\n'.join(b['text'] for b in blocks)}
    result.update({'group:' + g: '\n'.join(b['text'] for b in blocks if b['groupId'] == g)
                   for g in groups})
    result.update({'block:' + b['id']: b['text'] for b in blocks})
    return result


def validate_observation(observation, raw):
    require(observation['raw'] == raw, 'raw identity')
    candidates = observation['candidates']
    require(isinstance(candidates, list), 'candidate list')
    seen = set()
    last = 1.0
    for candidate in candidates:
        lang, score = candidate['language'], candidate['score']
        require(isinstance(lang, str) and re.fullmatch('[a-z]{2}', lang) is not None
                and lang not in seen, 'candidate language')
        require(type(score) in (int, float) and math.isfinite(score) and 0 <= score <= last,
                'candidate score/order')
        last = score
        seen.add(lang)
    total = sum(c['score'] for c in candidates)
    require(total == 0 or abs(total - 1) <= 1e-8, 'candidate sum')
    previous_end = 0
    for segment in observation['segments']:
        start, end = segment['start'], segment['end']
        require(type(start) is int and type(end) is int and previous_end <= start < end <= len(raw),
                'segment offsets')
        require(segment['raw'] == raw[start:end], 'segment raw identity')
        require(re.fullmatch('[a-z]{2}', segment['language']) is not None, 'segment language')
        require(type(segment['wordCount']) is int and segment['wordCount'] >= 0, 'segment words')
        previous_end = end


def validate_evidence(page, evidence):
    expected = queries(page)
    require(set(evidence) == set(expected), 'missing/extra evidence')
    for key, raw in expected.items():
        observation = evidence[key]
        require(observation['pageId'] == page['pageId'] and observation['key'] == key, 'evidence binding')
        validate_observation(observation, raw)


def letters(raw):
    return sum(c.isalpha() for c in raw)


def segment_language(observation):
    raw = observation['raw']
    covered = set()
    languages = set()
    for s in observation['segments']:
        covered.update(range(s['start'], s['end']))
        languages.add(s['language'])
    if any(c.isalpha() and i not in covered for i, c in enumerate(raw)):
        return None, 'uncovered-letters'
    if len(languages) != 1:
        return None, 'multiple-or-no-segment-language'
    return next(iter(languages)), None


def strong_top(observation):
    candidates = observation['candidates']
    if not candidates:
        return None
    top = candidates[0]
    second = candidates[1]['score'] if len(candidates) > 1 else 0
    return top['language'] if top['score'] >= .90 and top['score'] - second >= .20 else None


def complete_language(observation):
    lang, issue = segment_language(observation)
    return lang if not issue and letters(observation['raw']) >= 8 and lang in SUPPORTED \
        and strong_top(observation) == lang else None


def route(page, evidence):
    # No expected answer, corpus name or fixture label enters this function.
    raw = next((b['text'] for b in page['blocks'] if b['id'] == page['targetId']), '')

    def answer(reason, language=None, literal=False):
        action = 'keep-literal' if literal else 'keep' if language == 'zh' else \
            'translate' if language else 'review'
        return dict(action=action, language=language, raw=raw, reason=reason)

    try:
        validate_evidence(page, evidence)
    except (EvidenceError, KeyError, TypeError):
        return answer('invalid-evidence')
    target = next(b for b in page['blocks'] if b['id'] == page['targetId'])
    group = [b for b in page['blocks'] if b['groupId'] == target['groupId']]
    if any(b['ocrConflict'] for b in group):
        return answer('ocr-conflict')
    if not letters(raw):
        return answer('literal', literal=True)
    target_obs = evidence['block:' + target['id']]
    segment_lang, issue = segment_language(target_obs)
    if issue:
        return answer(issue)
    direct = complete_language(target_obs)
    if direct:
        return answer('direct', direct)
    # Context is local to this card and never rewrites source text.
    anchors = {complete_language(evidence['block:' + b['id']]) for b in group if b != target}
    anchors.discard(None)
    if len(anchors) != 1:
        return answer('missing-or-conflicting-anchors')
    anchor = next(iter(anchors))
    if complete_language(evidence['group:' + target['groupId']]) != anchor:
        return answer('group-evidence-disagrees')
    if segment_lang != anchor:
        return answer('target-segment-disagrees')
    if strong_top(target_obs) not in (None, anchor):
        return answer('strong-target-disagrees')
    support = next((c['score'] for c in target_obs['candidates'] if c['language'] == anchor), 0)
    if support < .10:
        return answer('insufficient-target-support')
    return answer('same-card-context', anchor)


class ObservationSession:
    """One detector lifetime; raw cache cleared and models unloaded even on failed inference."""
    def __init__(self, detector, convert):
        self.detector, self.convert = detector, convert
        self.cache = {}
        self.closed = False
        self.opened = 1
        self.unloaded = 0

    def observe(self, page):
        require(not self.closed, 'closed session')
        observations = {}
        for key, raw in queries(page).items():
            if raw not in self.cache:
                observation = self.convert(self.detector, raw)
                validate_observation(observation, raw)
                self.cache[raw] = copy.deepcopy(observation)
            observations[key] = dict(copy.deepcopy(self.cache[raw]), pageId=page['pageId'], key=key)
        return observations

    def close(self):
        if not self.closed:
            self.closed = True
            self.cache.clear()
            try:
                self.detector.unload_language_models()
                self.unloaded += 1
            finally:
                self.detector = None

    def __enter__(self):
        return self

    def __exit__(self, *unused):
        self.close()
