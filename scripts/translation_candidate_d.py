"""One-size-up local model comparison. Protocol C and frozen source corpus unchanged."""
from translation_candidate_c import cases,validate,request_body as compact_request
MODEL='qwen3.5:4b-q4_K_M'
MANIFEST='2a654d98e6fba55d452b7043684e9b57a947e393bbffa62485a7aac05ee4eefd'
def request_body(case,full_context,thinking=False):
    r=compact_request(case,full_context,thinking);r['model']=MODEL;return r
