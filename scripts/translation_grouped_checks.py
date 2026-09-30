"""Frozen, deliberately narrow complete refund-condition comparison; never translates.
No fixture IDs, expected answers, supplier calls or corrections. Raw text survives.
"""
import re
import unicodedata
from translation_critical_checks import evaluate as old_evaluate

VERSION = 'complete-confirmation-condition-v1'
HEADS = {'en': r'(no refunds|refunds are available) (before|after)',
         'fr': r'(aucun remboursement|remboursement possible) (avant|apres)'}


def normalized(text):
    # Case/accent matching only, not punctuation/whitespace rewriting.
    return ''.join(c for c in unicodedata.normalize('NFD', text.lower()) if not unicodedata.combining(c))


def head(text, language):
    return language in HEADS and re.fullmatch(HEADS[language], normalized(text)) is not None


def tail(text):
    return re.fullmatch(r'confirmation\.?', normalized(text)) is not None


def source_condition(text, language):
    if language not in HEADS or not isinstance(text, str):
        return None
    match = re.fullmatch(HEADS[language] + r'[ \n]confirmation\.?', normalized(text))
    if not match:
        return None
    return {'event': 'confirmation', 'relation': 'BEFORE' if match[2] in ('before', 'avant') else 'AFTER',
            'positive': match[1] in ('refunds are available', 'remboursement possible')}


def chinese_condition(text):
    if not isinstance(text, str):
        return None
    # Whole response, one clause only. No optional adverbs, exceptions, extra events or negations.
    match = re.fullmatch(r'(?:在)?确认(?P<relation>前|之前|以前|后|之后|以后)(?P<refund>可退款|可以退款|允许退款|可退还|可以退还|不可退款|不能退款|不予退款|不退款|无法退款|不允许退款|不可退还|不能退还)[。.]?', text)
    if not match:
        return None
    return {'event': 'confirmation', 'relation': 'BEFORE' if match['relation'] in ('前', '之前', '以前') else 'AFTER',
            'positive': match['refund'] in ('可退款', '可以退款', '允许退款', '可退还', '可以退还')}


def evaluate(source, translation, language):
    result = old_evaluate(source, translation, language)
    original = source_condition(source, language)
    output = chinese_condition(translation)
    matched = original is not None and original == output
    reasons = [r for r in result['reasons'] if not (matched and r == 'refund-condition-unverified')]
    if not matched:
        reasons.append('complete-confirmation-condition-unverified')
    return {**result, 'version': VERSION, 'status': 'withheld' if reasons else 'candidate-unverified',
            'reasons': list(dict.fromkeys(reasons)), 'conditionMatched': matched}
