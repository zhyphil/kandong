"""Limited EN/FR -> Chinese consistency checks. Never translates or certifies meaning.

No page IDs, fixture imports, network, or model calls. Unsupported critical syntax
is withheld when detected. Candidate-unverified is NOT a semantic guarantee.
"""
import copy
import re
import unicodedata
from collections import Counter
from datetime import date
from decimal import Decimal, InvalidOperation
from translation_fact_checks import number_tokens

VERSION = 'critical-facts-v1'
NUMBER = r'\d+(?:[.,]\d+)*'
MONTH_NAMES = [
    ('january','janvier'), ('february','fevrier'), ('march','mars'), ('april','avril'),
    ('may','mai'), ('june','juin'), ('july','juillet'), ('august','aout'),
    ('september','septembre'), ('october','octobre'), ('november','novembre'), ('december','decembre')]
MONTHS = {name: n for n, names in enumerate(MONTH_NAMES, 1) for name in names}
MONTH = '(?:' + '|'.join(MONTHS) + ')'
DATE_SOURCE = re.compile(rf'\b(?:(?P<d>\d{{1,2}})(?:er)?\s+(?P<m>{MONTH})\s+(?P<y>\d{{4}})|'
                         rf'(?P<m2>{MONTH})\s+(?P<d2>\d{{1,2}}),?\s+(?P<y2>\d{{4}}))\b')
DATE_ZH = re.compile(r'(?P<y>\d{4})\s*年\s*(?P<m>\d{1,2})\s*月\s*(?P<d>\d{1,2})\s*[日号]')


def normalized(text):
    # Match-only normalization; do not rewrite either displayed text.
    return ''.join(c for c in unicodedata.normalize('NFKD', text.casefold().replace('’', "'"))
                   if not unicodedata.combining(c))


def decimal_value(token, language):
    if ',' in token:
        if language != 'fr' or not re.fullmatch(r'\d+,\d{1,2}', token): return None
        token = token.replace(',', '.')
    try: return Decimal(token) if re.fullmatch(r'\d+(?:\.\d+)?', token) else None
    except InvalidOperation: return None


CURRENCY = {'€':'EUR','eur':'EUR','euro':'EUR','euros':'EUR','欧元':'EUR',
            '£':'GBP','gbp':'GBP','英镑':'GBP', '$':'UNSPECIFIED_DOLLAR',
            'usd':'USD','us$':'USD','美元':'USD','美金':'USD', 'cad':'CAD','ca$':'CAD','加元':'CAD',
            'aud':'AUD','a$':'AUD','澳元':'AUD', '¥':'UNSPECIFIED_YEN_YUAN',
            'jpy':'JPY','日元':'JPY','cny':'CNY','rmb':'CNY','人民币':'CNY'}


def currency_pattern():
    parts = []
    for token in sorted(CURRENCY, key=len, reverse=True):
        part = re.escape(token)
        if token.isascii() and token.isalpha(): part = r'(?<![a-z])' + part + r'(?![a-z])'
        parts.append(part)
    return '(?:' + '|'.join(parts) + ')'


MONEY_TOKEN = re.compile(currency_pattern())
MONEY = re.compile(rf'(?P<p>{currency_pattern()})\s*(?P<pn>{NUMBER})|(?P<sn>{NUMBER})\s*(?P<s>{currency_pattern()})')


def money(text, language):
    pairs = Counter(); covered = []
    for m in MONEY.finditer(text):
        value = decimal_value(m['pn'] or m['sn'], language)
        if value is None: return None
        pairs[(CURRENCY[m['p'] or m['s']], value)] += 1
        covered.append(m.span())
    if any(not any(a <= m.start() and m.end() <= b for a,b in covered) for m in MONEY_TOKEN.finditer(text)):
        return None  # Unbound identity or invented name, e.g. "$12美元".
    return pairs


def date_checks(source, translated):
    errors = []
    matches = list(DATE_SOURCE.finditer(source)); dest = list(DATE_ZH.finditer(translated))
    date_hint = re.search(rf'\d\s+{MONTH}\b|\b{MONTH}\s+\d|\d{{1,4}}[-/]\d{{1,2}}[-/]\d{{1,4}}', source)
    date_hint = date_hint or re.search(rf'\b(?:valid|valable)\b.*\b{MONTH}\b', source)
    if not matches:
        if date_hint or dest: errors.append('date-format-unverified')
        return errors
    if len(matches) != 1 or len(dest) != 1:
        return ['date-format-unverified']
    m = matches[0]; z = dest[0]
    try:
        original = date(int(m['y'] or m['y2']), MONTHS[m['m'] or m['m2']], int(m['d'] or m['d2']))
        output = date(int(z['y']), int(z['m']), int(z['d']))
        if original != output: errors.append('date-value-unverified')
    except ValueError: return ['date-value-unverified']

    # One date per element: inspect the date's own clause, not another sentence.
    left = max(source.rfind(c, 0, m.start()) for c in '.!?;\n') + 1
    right = min([p for c in '.!?;\n' if (p := source.find(c, m.end())) >= 0] or [len(source)])
    clause = source[left:right]
    before = translated[max(translated.rfind(c, 0, z.start()) for c in '。！？；\n') + 1:z.start()]
    tail = re.split(r'[。！？；\n]', translated[z.end():], maxsplit=1)[0]
    dest_clause = before + z[0] + tail
    on = bool(re.search(r'\bvalid (?:only )?on\b|\bvalable le\b', clause))
    until = bool(re.search(r"\buntil\b|\bvalable jusqu'(?:au|a)\b", clause))
    prior = bool(re.search(r'\bbefore\b|\bavant\b', clause))
    after = bool(re.search(r'\bafter\b|\bapres\b', clause))
    dest_until = bool(re.search(r'(?:有效期?至|有效到|截至|截止)', before) or re.search(r'前.*有效', tail))
    dest_before = bool(re.search(r'前|之前|以前', tail) or re.search(r'截至|截止', before))
    dest_after = bool(re.search(r'后|之后|以后|起', tail))
    if on:
        ok = '有效' in dest_clause and not re.search(r'无效|不.*有效', dest_clause) and not (
            dest_until or dest_before or dest_after or '从' in before or '自' in before)
    elif until: ok = (dest_until or dest_before) and not dest_after
    elif prior: ok = dest_before and not dest_after
    elif after: ok = dest_after and not dest_before
    else: ok = False
    if not ok: errors.append('date-relation-unverified')
    if re.search(r'\binclus(?:ive)?\b|\bincluding\b', clause):
        if not re.search(r'含|包括', dest_clause) or re.search(r'不含|不包括|除外', dest_clause):
            errors.append('date-inclusive-unverified')
    return errors


WEIGHT = re.compile(rf'({NUMBER})\s*(kilograms?|kg\b|公斤|千克|grams?|g\b|克)')


def weights(text, language):
    return Counter((decimal_value(m[1], language), 'kg' if m[2] in (
        'kg','公斤','千克','kilogram','kilograms') else 'g') for m in WEIGHT.finditer(text))


def conditional_checks(source, translated, language):
    errors = []
    original_weight = weights(source,language); output_weight = weights(translated,'zh')
    if original_weight or output_weight:
        if original_weight != output_weight or any(value is None for value, _ in original_weight | output_weight):
            errors.append('weight-value-unverified')
    upper = bool(re.search(r'\bmaximum\b|\bat most\b|\bno more than\b',source))
    lower = bool(re.search(r'\bminimum\b|\bat least\b',source))
    if upper or lower:
        top = bool(re.search(r'最多|至多|不超过|上限|最大|最高', translated))
        bottom = bool(re.search(r'至少|最少|不低于|最低|最小|下限|及以上', translated))
        if (upper and lower) or (top, bottom) != (upper, lower): errors.append('quantity-limit-unverified')
    if re.search(r'\beach\b|\bchacun\b',source):
        if not re.search(r'每件|每个|各',translated) or re.search(r'总共|合计|总计',translated):
            errors.append('per-item-unverified')
    if re.search(r'\bage\b.*\byears\b|\byears old\b',source) and not re.search(r'\d+\s*岁',translated):
        errors.append('age-unit-unverified')
    periods = re.findall(r'\bper (night|month|year|hour|child|person)\b', source)
    period_words = {'night':r'每[晚夜]','month':r'每月','year':r'每年','hour':r'每小时',
                    'child':r'每(?:名|个|位)?(?:儿童|孩子)','person':r'每(?:人|位)'}
    if periods and (len(periods) != 1 or not re.search(period_words[periods[0]],translated)):
        errors.append('price-period-unverified')
    tax = re.search(r'\btax(?:es)? (not )?included\b',source)
    if tax:
        negative_tax = bool(re.search(r'不含税|未含税|不包括税|未包括税',translated))
        positive_tax = bool(re.search(r'(?<!不)(?<!未)含税|已包括税|已包含税',translated))
        if (negative_tax,positive_tax) != (bool(tax[1]),not bool(tax[1])):
            errors.append('tax-condition-unverified')

    negative = re.search(r'(?:不|无法|不能|不可)[^。！？；\n]{0,20}(?:退款|退还|退费)',translated)
    no_refund = bool(re.search(r'\bno (?:changes? or )?refunds?\b|\bnon[- ]refundable\b|\b(?:non|ni) remboursable\b|\baucun remboursement\b', source))
    refundable = bool(re.search(r'\brefundable\b|\bremboursable\b',source)) and not no_refund
    if refundable and negative: errors.append('refund-negation-unverified')
    if no_refund:
        positive = re.search(r'(?<!不)(?<!无)(?:可以|可予|能够|允许)退款',translated)
        if not negative or positive: errors.append('refund-negation-unverified')
        if re.search(r'\b(?:after|before|apres|avant|if)\b',source):
            matched = False
            for src, dest in ((r'after departure|apres le depart',r'(?:出发|启程|离开)后'),
                              (r'after renewal',r'(?:续订|续费)后')):
                if re.search(src,source):
                    matched = bool(re.search(dest+r'[^。！？；\n]*不[^。！？；\n]*退款',translated))
            if not matched: errors.append('refund-condition-unverified')
    if re.search(r'\bno changes?\b',source) and not re.search(r'不[^。！？；\n]{0,12}(?:更改|变更|改签)',translated):
        errors.append('change-negation-unverified')
    if re.search(r'\b(?:non|ni) echangeable\b|\bno exchanges?\b',source):
        if not re.search(r'(?:不|无法)[^。！？；\n]{0,8}(?:换票|换货|改签|更换|退换)',translated):
            errors.append('exchange-condition-unverified')
    return errors


def evaluate(source, translation, language):
    reasons = []
    if not isinstance(source,str) or not source.strip() or len(source)>10000: raise ValueError('Invalid source text')
    if language not in ('en','fr'): reasons.append('source-language-unverified')
    if not isinstance(translation,str) or not translation.strip() or len(translation)>10000:
        reasons.append('translation-unavailable')
    if not reasons:
        src = normalized(source); dst = normalized(translation)
        if re.search(r'[-−]\s*(?:[€£$¥]\s*)?\d',src+dst):
            reasons.append('signed-number-unverified')
        if number_tokens(source,language) - number_tokens(translation,'zh-CN'): reasons.append('missing-source-number')
        source_money = money(src,language); dest_money = money(dst,'zh')
        if source_money is None or dest_money is None or source_money != dest_money:
            reasons.append('currency-amount-unverified')
        clocks = lambda s: Counter((int(h),int(m)) for h,m in re.findall(r'(?<!\d)(\d{1,2}):(\d{2})(?!\d)',s))
        if clocks(src) != clocks(dst) or any(h>23 or m>59 for h,m in clocks(src) | clocks(dst)):
            reasons.append('clock-value-unverified')
        reasons.extend(date_checks(src,dst))
        reasons.extend(conditional_checks(src,dst,language))
    return {'version': VERSION, 'status': 'withheld' if reasons else 'candidate-unverified',
            'reasons': list(dict.fromkeys(reasons)), 'semanticVerified': False}


def guarded_display(bound, language):
    source = bound.get('source')
    if not isinstance(source,dict) or bound.get('id') != source.get('id') or not isinstance(bound.get('id'),str):
        raise ValueError('Mismatched source identity')
    result = evaluate(source['text'], bound['text'], language)
    blocked = result['status'] == 'withheld'
    return {'id': bound['id'], 'pageId': bound.get('pageId'), 'source': copy.deepcopy(source),
            'state': 'original-with-warning' if blocked else 'candidate-unverified',
            'displayText': source['text'] if blocked else bound['text'],
            'translation': None if blocked else bound['text'],
            'message': '这段翻译暂时无法核对，请先看原文。' if blocked else '', 'check': result}
