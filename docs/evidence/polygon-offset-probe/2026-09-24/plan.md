> 🏛️ **Polygon Probe Planner** | Step 1/1 | 132.3s

---

Recommend the **all-round candidate**, restricted to this synthetic test probe. Complexity remains COMPLEX: this plan → one implementation worker → independent review; the parent owns checks, Huawei installation, and documentation.

The alternative, offset-only rounding, changes fewer calls and also matches 689/689 cases. All-round is preferable because offset execution invokes `DefaultClipper`/`Edge`, whose rounding also differs from Pyclipper. Neither result establishes general library equivalence or production suitability.

Read-only findings: worktree clean at `1b6e412`; all 11 temporary candidate hashes match [comparison.json](/Users/haoyuzuo/Projects/KanDong/docs/evidence/polygon-offset/2026-09-24/expanded/all-round/comparison.json). **175,411 bytes describes upstream; patched sources total 175,621 bytes.** Preserve that distinction in provenance.

| Implementation ownership | Concrete responsibility |
|---|---|
| `testShared/.../PolygonOffsetKernel.kt` | Pure bounded wrapper, input/output guards, derived distance, immutable results, exact canonical comparison. No Android/OpenCV imports or general Boolean API. |
| `test/.../PolygonOffsetGuardTest.kt` | Existing JUnit4 infrastructure; numerical regression, rejection boundaries, immutable-copy and comparator tests. |
| `androidTest/.../PolygonOffsetFixtureInputs.kt` | Authenticate two packaged files, strictly parse bounded schemas, validate exact IDs/categories/configuration, expose immutable cases. |
| `androidTest/.../PolygonOffsetProbeTest.kt` | Frozen and derived comparisons, asset/parser guards, incremental AtomicFile evidence, complete-count acceptance. |
| 11 `testShared/java/de/lighti/clipper/*.java` files | Copy the authenticated all-round candidate exactly; preserve upstream contents plus the existing rounding patch only. |
| `modelprobe/build.gradle.kts`; three `androidTest/assets/polygon-offset-legal/` files | Isolated staging, source/legal fingerprints and packaged-byte checks; `LICENSE`, `NOTICE`, provenance. |

All other existing sources, tests and fixtures remain unchanged. Parent owns `TASKS.md`, `WORKLOG.md`, evidence and decision documentation.

1. **Kernel contract.** Validate collection sizes before traversal/copying: 1–2 paths, exactly four finite, unique, ordered, strictly convex vertices each; absolute coordinates ≤8192. Validate original values, float32-roundtripped values, and integer paths after truncation toward zero. Reject post-cast repetition, crossing, collinearity or loss of convexity before constructing Clipper. Preserve relative winding and allow identical polygons across paths—`multi-0` requires this.

   Accept only finite `0 < distance ≤128`, `ROUND`, `CLOSED_POLYGON`, miter 2 and arc tolerance .25. Return explicit invalid-input, budget, empty-output or library-error outcomes; never truncate results into success. Check ≤8 output paths and ≤512 total vertices before copying/canonicalization. Return deeply immutable copies without vendor objects.

2. **Bounded-work limitation and fix.** Input caps plus the vendor’s arc-step formula permit a conservative preflight bound on offset-generated vertices; handle its near-zero-distance branch explicitly. Check this bound before library construction. **This does not enforce the final 512-vertex cap before UNION allocations, nor provide hard interruption.** Final output limits remain postconditions; document this limited guarantee instead of modifying pinned vendor internals.

   `ClipperOffset.execute(Paths, …)` discards the internal Boolean result; inspection shows unsuccessful execution leaves the cleared output empty. Therefore empty output must fail explicitly, with exceptions also propagated/reported.

3. **Fixtures and numeric gates.** Stage only `manifest.json` and `cases.json` into `build/polygon-offset-assets/polygon-offset-v1/`. Locally verified identities:

   - Manifest: 1,086 bytes; SHA-256 `e4415247a09d954501e0c5f87a2e5a6e35cdeef9ffc5ff5349aaf0a1a0f9e8ea`.
   - Cases: 1,225,517 bytes; SHA-256 `972e5c809dfaea5744caa05ceb7920383a99dca1d923e2bf69c950a707c09576`.

   Authenticate bounded bytes before parsing: 128 KiB/2 MiB. Use platform strict streaming parsing with explicit duplicate-key detection, exact key sets/types, bounded arrays and complete input consumption. Default `JSONObject` parsing alone is insufficient. Pin all 689 IDs and category counts: 2/13/128/512/28/6.

   Require 689 frozen-distance exact comparisons. For 683 single quads, compute float32-coordinate area ×1.6/perimeter in Double, require `abs(error) ≤1e-9 + 1e-12×abs(reference)`, then compare computed-distance output exactly. Six multipath cases use frozen distance only. Canonicalization permits cyclic start, reversed winding and path order, preserving every vertex and duplicate path.

4. **Guards and evidence.** Cover corrupt/truncated/wrong-hash/missing/extra files; duplicate/extra IDs and keys; invalid shapes; NaN/infinity; path/coordinate/distance limits; post-cast degeneration; empty/error/output-budget outcomes. Exercise parser guards directly without weakening authenticated loading. Test output limits through the actual output-validation helper. Include a frozen negative-half rounding regression and comparator failures for removed vertices.

   Write fresh UUID/scope/fingerprints to target `filesDir/polygon-offset-probe-report.json` before loading fixtures; persist incremental case/guard metrics and final counts before assertions. Require exact case/guard ID sets and nonzero comparison counts. Timing is observational only.

5. **Parent/reviewer checks.** Run existing JVM red–green workflow, then `:modelprobe:testDebugUnitTest`, `:modelprobe:lintDebug`, `:modelprobe:assembleDebugAndroidTest`. Require all 34 old JVM tests plus new tests; audit task graphs, test-only compilation, exact staged/APK entries, signatures and source/legal hashes. Retain Boost license and upstream/patched hashes, 35-call patch attribution, archived status and local maintenance ownership.

   Verify unchanged main APK SHA `7f9ff2550d8618a03903eaaccdb8bf98aa3e0ca631663cd1773563b4185a6aea`; record the new test APK hash and verify installed hashes before each of two fresh Huawei runs. Require old eight device regressions plus the new probe each round. Preserve emulator SIGILL and existing native-header limitations as unresolved. The 13 final quads remain synthetic inputs, not pre-unclip detection evidence.