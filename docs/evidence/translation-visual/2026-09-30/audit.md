> 🏛️ **Software Architect** | 步骤 1/1 | 102.0s

---

Recommend a **test-source-only presentation controller over the existing Probe**, reusing the current OCR backend and geometry session. Verified `AO_ORCHESTRATED=1`, clean Git state at `bb63d2f`. This audit performed no edits, builds, ADB, network calls, or tests.

Complexity budget: COMPLEX, three specialists total—this audit, implementation, review. No additional orchestration.

| Option | Trade-off |
|---|---|
| Put translation state directly in Surface | Fewer files, but couples callback ownership, cancellation, and display logic to Activity. |
| Add one pure presentation controller | Recommended: one additional abstraction, with independently testable state transitions and existing geometry preserved. |

1. **File/API split**

   Add `testShared/RegionVisualTranslationController.kt`, owning `FullPageTranslationProbe`, current run identity, presentation phase, and `ORIGINAL/DEMO` view choice. Suggested operations: `begin(version, now)`, `captured(run, metadata, association, language, roi, validatedAt, now)`, `reply(run, request, response, now)`, `failed(...)`, `render(roi, transform, now)`, and `clear(reason)`.

   Keep native execution in [RegionVisualOcrBackend.kt](/Users/haoyuzuo/Projects/KanDong/modelprobe/src/androidTest/java/com/kandong/modelprobe/RegionVisualOcrBackend.kt:20); add one Android local-reply scheduler with an injectable held/failing implementation.

   Extend [RegionVisualSession.kt](/Users/haoyuzuo/Projects/KanDong/modelprobe/src/testShared/java/com/kandong/modelprobe/RegionVisualSession.kt:46) with explicit `StartMode.OCR_ONLY/BINDING_DEMO`, preserving `showSample()` and existing Backend implementations through a default overload. Expose the normalized mirror transform. Avoid a mutable “translate next result” Boolean.

2. **Acquisition and receipt sequence**

   Reject busy clicks **before** preparing, changing mode, clearing current work, or incrementing accepted-start counts. Busy includes OCR reading/cleanup and pending replies; enqueue nothing.

   Accepted demo click:

   `runner.prepare(spec) → probe.observe(prepared.version) → probe.click(now) → bridge.start { runner.run(prepared, ticket) }`

   Preparation reserves metadata only; actual pixels follow the click. [Runner preparation](/Users/haoyuzuo/Projects/KanDong/modelprobe/src/androidTest/java/com/kandong/modelprobe/FullPageVisualOcrRunner.kt:47) already supports this.

   Preserve the original `RegionOcrBridge.Evidence<Result>` using the nested-receipt pattern already exercised by [FullPageTranslationOcrTest](/Users/haoyuzuo/Projects/KanDong/modelprobe/src/androidTest/java/com/kandong/modelprobe/FullPageTranslationOcrTest.kt:25). Backend `result()` still unwraps the same original Result. Deliver `receipt.validatedAtMillis` to Probe only while `bridge.value() === receipt`.

   Source raster, metadata, association, and Probe must derive from this single inference. Never rerun OCR, substitute UI time for worker validation, or renew acquisition time.

3. **States and replies**

   Use `IDLE → READING → WAITING → READY`, plus `ERROR`, `EXPIRED`, and `CANCELLED`. Chinese bypasses WAITING/provider scheduling and retains original text through existing Probe behavior. EN/FR schedules exactly one asynchronous response for `probe.pending(now)`.

   Default answer text should visibly say **“本地绑定演示，非真实翻译”**, using binding identifiers rather than plausible translated content. Retain incomplete/conflicting candidates as original-with-reason cards. READY means display available, not OCR or translation quality accepted.

   Probe currently hides phase and only exposes source mapping through pending requests. Add narrowly scoped, expiry-checked `phase(now)` and `sourceMap(now)` accessors in its testShared file; otherwise Chinese and completed replies lose a convenient authoritative label mapping. No shared-contract changes are needed.

4. **Cancellation and callback ownership**

   Menu, collapse, pause, stop, page switch, expiry, and disposal must revoke bridge delivery, Probe state, scheduler handle, cached receipt, and readable card data together. Resume restores geometry only.

   Scheduler cancellation must remove its Runnable and null pending request/response references. Queued work should hold a weak receiver and cancellable token, never Activity/Surface closures. Check exact run/request identity **before** reading callback time, clearing handles, or reporting failure. Cancellation must leave the native reservation occupied until worker cleanup finishes; preserve poisoned-slot behavior.

5. **Geometry and readable cards**

   Keep ROI, pan, and viewport in source-pixel units. Feed the session’s normalized transform into Probe. Existing mirror rendering already applies `source.factor` after projection; do not multiply zoom twice.

   Map `card.targetId` and `anchor.sourceId` through `sourceMap` to original candidate IDs, then original candidate ordinal. Use that stable number on source/mirror badges and cards, never current sorted-card position. Render text in wrapping, scrollable TextViews; rectangles remain anchors.

   Keep OCR-only controls/tags and gestures unchanged. Add demo, cancel, view-original, and view-demo controls; synthetic gesture mode must hide/disable demo functionality. Geometry and toggles only rerender.

   **Concrete UI risk:** current [card cache key](/Users/haoyuzuo/Projects/KanDong/modelprobe/src/androidTest/java/com/kandong/modelprobe/RegionVisualLabSurface.kt:179) contains geometry but no reply/view state. Include presentation revision, phase, and view choice or replies can leave stale cards.

6. **Focused verification**

   Add pure tests for rejected-click mode leakage, fresh preparation, late/foreign success and failure, original acquisition expiry, first retry after expiry, Chinese zero scheduling, and display-only changes.

   Add deterministic native/UI tests with held/failing replies and real four-page OCR: same receipt/frame identity, cancellation in both phases, replacement/rotation, expiry, original/demo toggles, and unchanged OCR counts. Check keyboard focus/activation, 48dp targets, wrapping at 1/1.5/2 font scale, and landscape reachability.

   Preserve unchanged suites: `RegionVisualSessionTest` for geometry/lifecycle; `RegionOcrBridgeTest` for reservations, clocks, and stale callbacks; `FullPageTranslationProbeTest` for binding/expiry; `RegionVisualLabUiTest` for seven synthetic gesture regressions; `RegionVisualOcrUiTest` for real OCR and Activity replacement; `FullPageTranslationOcrTest` for four-page binding and prepared-capture ownership.
