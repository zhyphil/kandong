> 👁️ **Code Reviewer** | 步骤 1/1 | 167.2s

---

Reviewed clean `9aecfd8`, read-only. **A separate `Pending.claimForRegion()` path is the smallest suitable seam**, provided it transfers the original Guard and cannot mint authority from an `Outcome`. Existing immutable association snapshots are a good foundation; retain them unchanged.

1. 🔴 **Publication authority must survive handoff without renewal.**

   [FullPageOcrPublication.kt:13](/Users/haoyuzuo/Projects/KanDong/modelprobe/src/testShared/java/com/kandong/modelprobe/FullPageOcrPublication.kt:13) exposes constructible `Outcome`; `publish()` consumes the pending value and finishes its Guard. It supplies neither dimensions nor acquisition/expiry history.

   Add `claimForRegion(scopeSucceeded, resourcesClosed)` alongside `publish()`, sharing the same consumed flag and validation order:

   - Success transfers the **original association, original `RgbaFrameMetadata`, and same Guard instance** into an opaque, single-use permit.
   - Detach transferred fields from `Pending` without finishing the transferred Guard. Subsequent `publish`, claim, or `discard` cannot revoke the new owner.
   - Failure consumes and clears everything, as today.
   - Only validated staging may mint a permit. Close the shortcut through the current `internal Pending(...)` constructor; a private mint authority checked by the new claim path preserves legacy construction compatibility.
   - No controller overload accepting `Outcome`, arbitrary `Result`, replacement timestamps, or caller-supplied TTL.

   Add only a forwarding method to [FullPageOcrPipeline.StagedPage:25](/Users/haoyuzuo/Projects/KanDong/modelprobe/src/androidTest/java/com/kandong/modelprobe/FullPageOcrPipeline.kt:25). Keep `run`, `runStaged`, and legacy `publish` behavior unchanged. Claim after the existing successful-scope/resource-close boundary—not merely after inference.

   This authenticates the validated test pipeline’s handoff, **not real-screen provenance or privacy**. Archived reports remain explicitly historical projection fixtures and cannot enter the controller.

2. 🔴 **Guard revocation must win over callback reentrancy.**

   [Guard.checkpoint():20](/Users/haoyuzuo/Projects/KanDong/modelprobe/src/testShared/java/com/kandong/modelprobe/FullPageOcrPublication.kt:20) saves `source`, invokes it, then can return success even if that callback invoked `finish()`.

   After the callback returns, verify that the original source is still installed before accepting its checkpoint. Preserve sticky cancellation and `lastNow`.

   Use one serialized owner for controller operations. Projection must build results locally, checkpoint during processing and immediately before publication, then recheck controller epoch and lease identity **after** that last callback. Reentrant pause, replacement, or cancellation must return no results. Document thread confinement rather than implying cross-thread safety.

3. **Use a separate lossless page representation and controller.**

   Suggested bounded delta: two `testShared` files for evidence/projection and lifecycle, shared `contextTest` coverage, plus the additive publication/Android forwarding changes.

   `PageEvidence` retains the complete original association and metadata, including every candidate’s provenance, both quads, raw text, model identity, edges, groups and uncertainty. Preserve individual candidate IDs; groups remain **spatial associations**. Do not adapt through [CandidateContext.kt:15](/Users/haoyuzuo/Projects/KanDong/modelprobe/src/testShared/java/com/kandong/modelprobe/CandidateContext.kt:15) or weaken [validOcr():108](/Users/haoyuzuo/Projects/KanDong/ocrlab/src/contextShared/java/com/kandong/ocrlab/context/ContextContracts.kt:108).

   Ownership is **producer Pending → unclaimed permit → controller-owned lease**. Permit consumption removes its payload; duplicate callbacks cannot close an already transferred lease.

   Controller behavior:

   - Observe only `CaptureVersion`; only explicit click creates a controller-bound ticket. Duplicate in-flight clicks create no work.
   - Bind the producer checkpoint to that ticket’s validity. Acceptance requires the same outstanding ticket, matching observed version and original acquisition time at/after click, using the same monotonic clock.
   - Reject stale tickets **before** ticking, validating replacements or mutating current state; release only their unclaimed evidence.
   - A valid replacement attempt revokes the old lease before validating the replacement.
   - Clear removes page, projection and ticket, finishes any controller-owned Guard, and advances epoch. Producer-owned in-flight work loses ticket authorization and must discard on completion. Restore performs no acquisition.
   - Preserve controller clock history across clear; never reset acquisition time, Guard history or TTL.

4. **Define projection explicitly in source pixels.**

   Keep source-space ROI and pan in original image pixels, mirror dimensions in mirror pixels, and scale in `1..5`. Clip ROI to the page; clamp pan as in [ContextGeometry.kt:12](/Users/haoyuzuo/Projects/KanDong/ocrlab/src/contextShared/java/com/kandong/ocrlab/context/ContextGeometry.kt:12):

   `mirror = (source − clippedRoi.origin − clampedPan) × scale`

   Clip displayed anchors to the mirror viewport. ROI membership depends on source geometry; scale/pan only affect placement and visibility.

   For this slice, use explicitly **conservative bounding-envelope intersection** for rotated/unordered quads. Preserve the original quad and label anchors as envelopes, never exact ink. Degenerate geometry remains retained with an explicit unprojectable state. For each ROI candidate expose its spatial group’s complete member IDs, including members outside ROI; retain unrelated outside-page-selection evidence too.

   Distinguish successful zero-candidate page, nonblank page with empty selection, empty-string candidates, and rejected/expired page. None implies language or translation readiness.

Must-have tests, using existing JUnit/shared Android source sets:

| Area | Required assertion |
|---|---|
| Legacy compatibility | Existing publication tests retain their behavior; `publish` still finishes its Guard. |
| Exclusive transfer | Publish/claim/discard permutations consume once; permit cannot activate twice; old cleanup cannot revoke its new owner. |
| Continuous validity | Preserve dimensions/acquisition/TTL; reject expiry exactly at boundary, backward time across handoff, every version-field change, callback exceptions and revival attempts. |
| Click lifecycle | Observation/ROI/scale/pan acquire nothing; duplicate clicks coalesce; pre-click evidence rejects; restore needs another click. |
| Replacement isolation | Invalid current replacement clears old evidence; stale success/failure/duplicate callback cannot clear a newer ticket or page. |
| Reentrant cancellation | Clear or replace from a checkpoint during projection—including the final checkpoint—publishes nothing from the old lease. |
| Losslessness | Full-field preservation of `稅／税`, noncore, empty and conflicting candidates; unchanged associations and outside-ROI negation/amount evidence. |
| Geometry/status | Partial intersection, rotated-envelope false positive, touching boundary, degenerate quad, page-edge clipping, pan/scale mapping, complete blank versus rejection. |

No builds, tests, devices, network access or edits performed. The recorded 178-test baseline was read, not rerun; the parent should retain those regressions and add the cases above.