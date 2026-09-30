"""Versioned offline recheck; v1 calls and checks remain immutable.

One visual wrap at the event/action boundary and ordinary refund wording only.
No fixture IDs, answer generation, raw-response rewriting, or network access.
"""
import re
from translation_critical_checks import evaluate as original_evaluate
from translation_grouped_checks import source_condition

VERSION = 'complete-confirmation-condition-v2'
ACTION = r'可退款|可以退款|允许退款|可退还|可以退还|可办理退款|不可退款|不能退款|不予退款|不退款|无法退款|不允许退款|不可退还|不能退还|恕不退款'
WRAP = r'[ \t]*(?:\r?\n[ \t]*)?'


def chinese_condition(text):
    if not isinstance(text,str):
        return None
    match=re.fullmatch(r'(?:在)?确认(?P<relation>前|之前|以前|后|之后|以后)'+WRAP+
                      r'(?P<action>'+ACTION+r')[。.]?',text)
    if not match:
        return None
    return {'event':'confirmation','relation':'BEFORE' if match['relation'] in ('前','之前','以前') else 'AFTER',
            'positive':match['action'] in ('可退款','可以退款','允许退款','可退还','可以退还','可办理退款')}


def evaluate(source,translation,language):
    previous=original_evaluate(source,translation,language)
    src=source_condition(source,language)
    matched=src is not None and src==chinese_condition(translation)
    reasons=[r for r in previous['reasons'] if not (matched and r=='refund-condition-unverified')]
    if not matched:
        reasons.append('complete-confirmation-condition-unverified')
    return {**previous,'version':VERSION,'conditionMatched':matched,
            'status':'withheld' if reasons else 'candidate-unverified','reasons':list(dict.fromkeys(reasons))}
