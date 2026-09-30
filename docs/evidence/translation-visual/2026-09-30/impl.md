> 📲 **Mobile App Builder** | Step 1/1 | 323.7s

---

Implemented within the 7 assigned files.

- Added `RegionVisualTranslationController`: identity-bound runs/replies, phases, display choice, revision, TTL enforcement and projected rendering.
- Added `StartMode`, default-compatible backend overload, `mirrorTransform()` and session cancellation.
- Backend preserves the original nested receipt and worker validation timestamp. Chinese bypasses reply scheduling.
- Added injectable `RegionVisualDemoReplies`, via `installReplySchedulerForProbe()`, plus `originalReceiptForProbe()`.
- Added tags: `lab_translate`, `lab_cancel`, `lab_original`, `lab_demo`, `lab_translation_status`, `lab_binding_notice`, `lab_translation_candidate_N`.
- Added 9 pure coordinator tests. Synthetic controls remain hidden; existing trailing-lambda constructor remains supported.

No builds, tests, ADB, network, commits or root-owned file edits performed. Compilation, regression tests and portrait/landscape/font-scale UI verification remain for root.
