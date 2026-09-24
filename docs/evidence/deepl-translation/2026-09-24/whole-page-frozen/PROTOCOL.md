# Whole-page text diagnostic (before inference)

Pure-source context resolved all 12 selected ambiguous labels, but p106 single-date validity still became a deadline, and p003 currency became USD without explicit source identity. Preserve both failures. Check whether sending the entire page as one text changes this. Fixed p106 (single date), p304 (until/inclusive) and p003 (currency/cards), two rounds: 6 requests, 1070 source characters. No source wording or model setting changes, no answer hints, 2 seconds between requests, stop on error without retry. Same service and account-quota guard.

This is a small regression diagnosis selected after failures, NOT a new blind holdout. Responses are raw whole-page text. No splitting or element binding is performed, so even a correct whole-page translation would NOT satisfy the product mapping requirement. Stored fixed plan and script hash precede calls. Existing geometric/source binding tools are unchanged.

Official rationale: https://developers.deepl.com/docs/learning-how-tos/examples-and-guides/how-to-use-context-parameter (keep related text together; tags are not context boundaries). Structured XML is a possible later transport, not implemented/claimed here.
