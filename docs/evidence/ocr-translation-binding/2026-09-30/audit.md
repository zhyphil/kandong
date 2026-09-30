> 🏛️ **Software Architect** | Step 1/1 | 137.5s

---

**Recommendation: reuse `OnDemandTranslation` with a thin, test-only coordinator and an immutable OCR evidence envelope.** Adapter-only tests can safely use it if the capture ticket exists **before inference**, with the original version, acquisition time and TTL preserved.

Clean `559dad4` verified before and after inspection. No files changed; no build, tests, ADB or network operations ran. Complexity budget remains the assigned audit within the parent’s three-specialist phase.

| Option | Trade-off |
|---|---|
| **Thin adapter/coordinator — recommended** | Reuses existing capture, request, response and rendering behavior; needs a small additive evidence discriminator. |
| Separate OCR translation state machine | Avoids shared-schema additions, but duplicates cancellation, expiry and response-binding logic. Excessive for this phase. |

1. **Existing behavior and gaps**

   [OnDemandTranslation.kt](/Users/haoyuzuo/Projects/KanDong/ocrlab/src/contextShared/java/com/kandong/ocrlab/context/OnDemandTranslation.kt:108) already requests eligible targets across the whole snapshot, then selects the ROI for presentation. Reuse this unchanged.

   [ContextEngine.kt](/Users/haoyuzuo/Projects/KanDong/ocrlab/src/contextShared/java/com/kandong/ocrlab/context/ContextEngine.kt:131) validates response identity, complete target cardinality and exact bindings before caching anything. However, swapping answer **text** between otherwise correct bindings remains structurally valid. The binding-test coordinator must detect this separately.

   [ContextContracts.kt](/Users/haoyuzuo/Projects/KanDong/ocrlab/src/contextShared/java/com/kandong/ocrlab/context/ContextContracts.kt:108) requires exactly `ch` and `latin` for `PACKAGED_SYNTHETIC_OCR`. Do not fabricate a second recognizer or label actual OCR as authored fixture text.

2. **Concrete files and API shape**

   Add under `modelprobe/src/testShared/java/com/kandong/modelprobe/`:

   - `FullPageTranslationAdapter.kt`: `adapt(metadata, association, fixtureLanguage)` returns either a rejection or immutable `AdaptedPage(snapshot, originalEvidence, sourceMap)`.
   - `FullPageTranslationProbe.kt`: owner-thread coordinator exposing `observe(version)`, `click(now)`, `captured(ticket, evidence, roi, now)`, `accept(ticket, response, now)`, `select`, `setTransform`, `render`, `clear`. Delegate translation phases to `OnDemandTranslation`; retain only capture identity, original evidence and binding checks.

   Add `FullPageTranslationProbeTest.kt` under `contextTest`, and `FullPageTranslationOcrTest.kt` under `androidTest`. Preserve the existing authored-page tests.

   Amend only `ContextContracts.kt` and `FullPageVisualOcrRunner.kt` as described below. No engine, UI, dependency or production magnifier changes are needed.

3. **Capture ordering and version guard**

   The [runner currently allocates its version inside `run`](/Users/haoyuzuo/Projects/KanDong/modelprobe/src/androidTest/java/com/kandong/modelprobe/FullPageVisualOcrRunner.kt:46). Add metadata-only, single-use `prepare(spec): PreparedCapture`, reserving its request/version, and `run(prepared, cancellation)`. Keep the existing overload delegating through preparation.

   Required sequence:

   `prepare → observe full version → explicit clickTranslate → bridge.start → runner acquisition/inference → bridge delivery → captured(original ticket)`

   Preparation must decode nothing. Acquisition time remains assigned at the existing actual frame acquisition point; never replace it with click, completion or delivery time.

   Guard **all six `CaptureVersion` fields**, including snapshot, before accepting, requesting or rendering. `TranslationPage` alone omits snapshot. Reject foreign tickets before consulting their timestamps. Preserve worker `validatedAtMillis` as a monotonic lower bound, and retain the bridge’s cleanup/cancellation boundary. Keep the original worker controller and permits worker-confined.

4. **Smallest honest evidence extension**

   Add `SourceKind.PACKAGED_FULL_PAGE_SINGLE_MODEL_OCR`. Reuse `OcrEvidence` with exactly one actual candidate, plus an additive `SPATIAL_UNCERTAINTY` review reason. Implement a separate validation branch; leave existing two-model validation unchanged.

   Preserve complete original metadata, model/detector/dictionary identity, every candidate/provenance field and spatial association in the immutable test-only envelope. Expose requests as `ProbeRequest(contract: FixtureRequest, evidence, sourceMap)` so provenance travels with the request rather than remaining an inaccessible adapter detail.

   Create one block per raw candidate, including duplicates and empty candidates. Use length-framed identity serialization and SHA-256 for snapshot/block mapping tokens: bounded below 100 characters, collision-checked, with original identities retained. Deep-freeze collections.

   Leave `SemanticGroup`, `groupId` and semantic context links empty. Spatial components are diagnostic evidence, not semantic groups.

5. **Eligibility and response rules**

   Fixture-declared language supplies EN/FR/Hans/Hant identity; neither recognizer choice nor overlap establishes language or meaning.

   Retain uncertain/conflicting/empty candidates in whole-page evidence but mark them ineligible for answer generation. Consistent duplicate candidates remain separately represented; never discard using `ownsCoreCenter`. Reject malformed evidence, unsupported geometry that cannot form valid blocks, empty pages and budget violations atomically.

   Enforce 128 blocks, 8192 total raw characters and **2000 per candidate** before requesting. OCR’s larger allowance does not authorize clipping text to fit translation contracts.

   Chinese answers use `KEEP_ORIGINAL`, `SOURCE`, `ALREADY_CHINESE`. EN/FR use opaque `BINDING_TEST_ONLY:<digest>` markers derived from request identity and exact target binding. Validate every expected marker/outcome before calling `accept`; swapped text must reject the entire response. This validates transport only.

6. **Required tests and completion boundary**

   JVM cases: click-before-inference; acquisition-before-click rejection; exact TTL boundary; clock rollback; changes to each version field; all provenance preserved; long IDs; 128/129 blocks; 2000/2001 characters; conflicts, whitespace, empty pages, malformed geometry; mutation resistance; duplicate/missing/foreign/swapped answers; ROI/zoom reuse; menu/pause/stop/provider-change clearing; late callbacks after replacement.

   Instrumentation: run the four existing fixed pages through actual OCR and the prepared capture path. Assert request context retains every original candidate, targets remain independent of ROI, markers/Chinese outcomes bind correctly, lifecycle rejection works, and existing cleanup diagnostics remain balanced. Do not assert expected OCR wording.

   **No design blocker found.** Compilation and runtime validation remain unperformed. Passing this phase would establish fixed-page OCR-to-request binding, not translation quality, live capture, privacy filtering or product readiness.