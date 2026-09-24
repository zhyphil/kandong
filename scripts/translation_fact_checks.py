"""Conservative literal omission checks; not a semantic translator or safety guarantee."""
from collections import Counter
from decimal import Decimal
import re

DIGITS={'零':0,'〇':0,'一':1,'二':2,'两':2,'三':3,'四':4,'五':5,'六':6,'七':7,'八':8,'九':9}
CHARS='零〇一二两三四五六七八九十百千万亿'
COUNT=re.compile(rf'(?<![{CHARS}])([零〇一二两三四五六七八九十]{{1,3}})(?![{CHARS}])(?=\s*(?:晚|夜|人|名|件|个|岁|小时|分钟|天|日|月|年|公斤|千克|kg))')

def small_count(token):
    if '十' not in token:return DIGITS.get(token)
    if token.count('十')!=1:return None
    left,right=token.split('十')
    if left and left not in DIGITS:return None
    if right and right not in DIGITS:return None
    tens=DIGITS[left] if left else 1
    if not 1<=tens<=9:return None
    return tens*10+(DIGITS[right] if right else 0)

def number_tokens(text,language):
    result=[]
    for value in re.findall(r'\d+(?:[.,]\d+)*',text):
        # No thousands-group guessing. A French comma with 1-2 fractional digits is unambiguous here.
        if language=='fr' and re.fullmatch(r'\d+,\d{1,2}',value):value=value.replace(',','.')
        if re.fullmatch(r'\d+(?:\.\d+)?',value):result.append(Decimal(value))
        else:result.append('literal:'+value)
    if language.startswith('zh'):
        for match in COUNT.finditer(text):
            number=small_count(match[1])
            if number is not None:result.append(Decimal(number))
    return Counter(result)

def issues(source,translated,source_language):
    if translated is None:return []
    errors=[]
    if number_tokens(source,source_language)-number_tokens(translated,'zh-CN'):errors.append('missing-source-number')
    aliases={'€':('€','欧元','EUR'),'£':('£','英镑','GBP'),'$':('$',),'¥':('¥',)}
    if any(symbol in source and not any(token in translated for token in equivalents) for symbol,equivalents in aliases.items()):
        errors.append('missing-currency-identity')
    return errors
