"""Source-grounded labels and exact money spans; no fixtures, network or credentials.

This does not certify general translation semantics. Unknown syntax is not repaired.
"""
import copy
import re
import xml.etree.ElementTree as ET
from datetime import date
from xml.sax.saxutils import escape
from translation_critical_checks import MONTH_NAMES, MONEY, money, normalized, guarded_display

VERSION = 'protected-facts-v1'
ORIGINAL_MONEY = re.compile(MONEY.pattern, re.IGNORECASE)


def validate_source(source, language):
    if language not in ('en', 'fr') or not isinstance(source, str) or not source.strip() or len(source) > 10000:
        raise ValueError('Unsupported source')
    if any(ord(c) < 32 and c not in '\t\r\n' for c in source):
        raise ValueError('Unsupported source characters')


def local_date(source, language):
    """Only a complete positive validity label; never drop a trailing condition."""
    if language not in ('en', 'fr') or not isinstance(source, str) or len(source) > 10000:
        return None
    text = normalized(source.strip())
    months = {names[0 if language == 'en' else 1]: n for n, names in enumerate(MONTH_NAMES, 1)}
    month = '(?:' + '|'.join(months) + ')'
    if language == 'en':
        prefix = r'(?P<mode>valid on|only valid on|valid only on|valid until)'
        value = rf'(?:(?P<d>\d{{1,2}}) (?P<m>{month}) (?P<y>\d{{4}})|(?P<m2>{month}) (?P<d2>\d{{1,2}}),? (?P<y2>\d{{4}}))'
        suffix = r'(?P<inclusive> inclusive)?'
    else:
        prefix = r"(?P<mode>valable le|valable jusqu'au)"
        value = rf'(?P<d>\d{{1,2}})(?P<ordinal>er)? (?P<m>{month}) (?P<y>\d{{4}})'
        suffix = r'(?P<inclusive> inclus)?'
    match = re.fullmatch(prefix + ' ' + value + suffix + r'\.?', text)
    if not match: return None
    parts = match.groupdict()
    day = int(parts.get('d') or parts['d2'])
    if parts.get('ordinal') and day != 1: return None
    try:
        parsed = date(int(parts.get('y') or parts['y2']), months[parts.get('m') or parts['m2']], day)
    except ValueError: return None
    until = match['mode'] in ('valid until', "valable jusqu'au")
    inclusive = bool(match['inclusive'])
    if inclusive and not until: return None
    only = 'only' in match['mode']
    rendered = f'{parsed.year}年{parsed.month}月{parsed.day}日'
    rendered = ('有效期至' + rendered + ('（含当日）' if inclusive else '')) if until else (
        ('仅在' if only else '在') + rendered + '有效')
    return {'text': rendered, 'origin': 'local-date-rule', 'version': VERSION,
            'fact': {'date': parsed.isoformat(), 'relation': 'until' if until else 'on',
                     'only': only, 'inclusive': inclusive}, 'semanticVerified': False}


def protect_money(source, language):
    """Offsets refer to ORIGINAL text; normalization is used only for validation."""
    validate_source(source, language)
    pairs = money(normalized(source), language)
    if pairs is None: raise ValueError('Unsupported currency syntax')
    if not pairs: return None
    if re.search(r'[+−-]\s*(?:[€£$¥]\s*)?\d', source): raise ValueError('Signed money unsupported')
    tokens = []; chunks = ['<segment>']; start = 0
    for match in ORIGINAL_MONEY.finditer(source):
        amount = match['pn'] or match['sn']
        if not re.fullmatch(r'\d+(?:[.,]\d{1,2})?', amount): raise ValueError('Ambiguous precision')
        # Do not match only the end/start of a spaced thousands amount.
        if re.search(r'\d[\s.,]*$', source[:match.start()]) or re.match(r'[\s.,]*\d', source[match.end():]):
            raise ValueError('Partial money amount')
        token = {'id': f'm{len(tokens)}', 'text': match[0], 'span': list(match.span())}
        tokens.append(token)
        chunks += [escape(source[start:match.start()]), f'<keep id="{token["id"]}">{escape(match[0])}</keep>']
        start = match.end()
    if len(tokens) != sum(pairs.values()) or len(tokens) > 16: raise ValueError('Unbound currency')
    chunks += [escape(source[start:]), '</segment>']
    return {'source': source, 'language': language, 'xml': ''.join(chunks), 'tokens': tokens, 'version': VERSION}


def restore_money(prepared, response):
    """Accept only our exact flat schema; output plain text, never executable markup."""
    if not isinstance(response, str) or len(response) > 10000 or '<!' in response or '<?' in response:
        raise ValueError('Unsupported XML')
    expected = protect_money(prepared['source'], prepared['language'])
    if expected is None or expected != prepared: raise ValueError('Invalid binding')
    try: root = ET.fromstring(response)
    except ET.ParseError as error: raise ValueError('Invalid XML') from error
    if root.tag != 'segment' or root.attrib or len(root) != len(expected['tokens']):
        raise ValueError('Invalid envelope')
    displayed = [root.text or '']; checked = displayed.copy()
    for node, token in zip(root, expected['tokens']):
        if node.tag != 'keep' or node.attrib != {'id': token['id']} or len(node) or node.text != token['text']:
            raise ValueError('Altered protected money')
        displayed += [token['text'], node.tail or '']
        # French comma remains visible exactly as written. Only the known protected
        # token gets canonical decimal spelling for the pre-existing numeric guard.
        check_token = token['text'].replace(',', '.') if expected['language'] == 'fr' else token['text']
        checked += [check_token, node.tail or '']
    return {'text': ''.join(displayed), 'checkText': ''.join(checked)}


def prepare(source, language):
    validate_source(source, language)
    date_label = local_date(source, language)
    if date_label: return {'route': 'local-date', 'value': date_label}
    if re.search(r'\b(?:valid|valable)\b', normalized(source)):
        return {'route': 'withheld', 'reason': 'complete-validity-label-unverified'}
    try: protected = protect_money(source, language)
    except ValueError: return {'route': 'withheld', 'reason': 'money-syntax-unverified'}
    return {'route': 'protected-money', 'value': protected} if protected else {'route': 'plain'}


def display(source, page_id, language, response_text=None):
    """Source identity/geometry are local. Provider returns only the requested text."""
    if not isinstance(source, dict) or not isinstance(source.get('id'), str):
        raise ValueError('Invalid source binding')
    decision = prepare(source['text'], language)
    route = decision['route']; text = check_text = None; reason = None
    origin = {'version': VERSION, 'route': route, 'provider': None}
    if route == 'local-date':
        text = check_text = decision['value']['text']
        origin.update(origin='local-date-rule', fact=decision['value']['fact'])
    elif route == 'withheld': reason = decision['reason']
    elif route == 'protected-money':
        origin.update(origin='deepl-with-original-money', provider='DeepL')
        try:
            restored = restore_money(decision['value'], response_text)
            text, check_text = restored['text'], restored['checkText']
        except (ValueError, TypeError): reason = 'protected-money-binding-unverified'
    else:
        origin.update(origin='deepl-plain', provider='DeepL')
        text = check_text = response_text
    result = guarded_display({'id': source['id'], 'pageId': page_id, 'source': source, 'text': check_text}, language)
    if reason:
        result['check']['reasons'] = [reason]
        result['check']['protectionVersion'] = VERSION
    if result['state'] == 'candidate-unverified':
        result['translation'] = result['displayText'] = text
        if route == 'local-date': result['state'] = 'local-date-rendered'
    result['provenance'] = copy.deepcopy(origin)
    return result
