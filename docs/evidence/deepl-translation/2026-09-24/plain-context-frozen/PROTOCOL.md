# Plain-context diagnosis, frozen before inference

Observed first run: 74/192 responses then HTTP 429; Book, Vol, Return, Note and one-date validity remain wrong. Preserve that partial run. Hypothesis: JSON metadata may be unsuitable context for this API. Change only the context representation to whole-page source text in reading order, with blank lines when source groups change. The full page is retained; geometry/IDs/roles stay local, no inferred intent or answers are appended.

Fixed 14 selected regression targets (91 billed-source characters) in plan.json. One request per target, same source and target languages/default model and response binding. Selected after seeing first-run failures, NOT a blind holdout or a complete acceptance set. Source and transitive fixture hashes are recorded. Request pace: wait 2 seconds before each new translation, no retries. Quota/official endpoint restrictions unchanged. Success is transport/structure only; existing semantic criteria apply unchanged. Need further repeated/unseen samples only if quality warrants proceeding.

Official context guidance: https://developers.deepl.com/docs/learning-how-tos/examples-and-guides/how-to-use-context-parameter
Official error guidance: https://developers.deepl.com/docs/best-practices/error-handling

Pure-source context is a hypothesis, not proof that the service rejects JSON. Pacing is a mitigation, not a measured rate-limit guarantee. Cloud default weights/version remain unpinned.
