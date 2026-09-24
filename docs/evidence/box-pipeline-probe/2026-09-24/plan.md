> 🏛️ **Box Pipeline Planner** | Step 1/1 | 133.0s

---

Feasible within the **COMPLEX / three-specialist-step budget**: this read-only plan → one test-only implementation → independent review. Parent retains documentation, final checks and Huawei execution. Confirmed clean `main@d267a10`; no files changed or builds/tests run.

**Recommended approach:** one new pipeline retains actual contours through scoring, expansion and sorting, then calls the existing crop helper. Alternative: call existing `masks()` and extract contours again; this preserves source files but duplicates native work and complicates trace identity. Prefer the single extraction.

| File | Responsibility |
|---|---|
| `testShared/.../BoxPipelineContract.kt` | Input guards, immutable stage/result records, float32 mapping/norm, half-even rounding, paired reading order, explicit incomplete outcomes. |
| `test/.../BoxPipelineGuardTest.kt` | Existing JUnit infrastructure: boundaries, rounding, score/index association, sorting and failure outcomes. |
| `androidTest/.../BoxTraceFixtureInputs.kt` | Strict authenticated manifest/trace loading; reuse unchanged `GeometryFixtureInputs` for parent inputs/crops. |
| `androidTest/.../BoxPipelineOpenCv.kt` | Actual native DB stages, unchanged offset kernel, deterministic resource ownership and failure injection. |
| `androidTest/.../BoxPipelineProbeTest.kt` | All 24 cases, guards, actual-result cropping, AtomicFile report/checkpoints. |
| Optional `androidTest/.../BoxTraceComparison.kt` | Focused stage comparison and completeness metrics. |
| `modelprobe/build.gradle.kts` | Only existing-file edit: instrumentation-only staging, authentication and package verification. |

All paths are under `modelprobe/src` except the build file. Preserve the 907-file protection manifest, existing helpers/vendor, dependencies and golden files.

Implementation sequence:

1. Validate source/probability dimensions `1..4096`, ≤1,048,576 pixels, exact length and finite probabilities `[0,1]` before Mat allocation. Compute strict `>0.3f` mask; check source row runs. Apply default-anchor 2×2 dilation and check dilated runs before `findContours`.
2. Extract `RETR_LIST/CHAIN_APPROX_SIMPLE` contours once. Reject candidate count >1000 before processing any candidate. Cached OpenCV5 JAR confirms **`Geometry.minAreaRect(MatOfPoint2f)` and `Geometry.boxPoints(RotatedRect, Mat)`**; use native boxPoints, avoiding Java `RotatedRect.points`.
3. Preserve upstream point ordering; reject minimum side `<3`. Calculate fast score using clipped floor/ceil ROI, float32-relative coordinates truncated to integer `fillPoly` vertices, and masked native mean. Accept score `>=0.5`.
4. Call unchanged `PolygonOffsetKernel.computedDistance/offset`; retain actual integer input and expanded paths. Map unsupported geometry, budgets, empty output and library failures to explicit unusable outcomes.
5. Compute expanded minrect; reject side `<5`. Perform float32 division then multiplication, half-even rounding, initial inclusive clipping, final point ordering and clipping to `width−1/height−1`; reject truncated float32 norms `<=3`.
6. Preserve distinct `contourIndex`, `rawBoxIndex` and `finalBoxIndex`; crop `originalIndex` means the **final pre-sort index**. Stable-sort paired records by y, form lines using adjacent y differences `>=10`, then stable x order.
7. Construct crop rows exclusively from computed quad/score/order, including `cropPlan` and `homography(actualQuad, plan)`. Expected matrices/boxes are comparison data only. Reuse [existing crop](/Users/haoyuzuo/Projects/KanDong/modelprobe/src/androidTest/java/com/kandong/modelprobe/GeometryOpenCvProbe.kt:98) unchanged.

Pre-implementation risks and resolutions:

- **Gzip packaging:** [existing build notes](/Users/haoyuzuo/Projects/KanDong/modelprobe/build.gradle.kts:93) explicitly warn that AAPT rewrites `.gz`. Freeze an exact staging mapping from the 24 logical `.json.gz` names to physical `.jsonz` names; keep original manifest/content unchanged. Authenticate exact 33 mapped assets and 112,533 bytes, including packaged compressed-byte hashes.
- **Budget accounting:** authenticated input inspection gives source/dilated runs `2096/4064`, `2098/4068`, `8320/16448` for 1000/1001/4096. Therefore 4096 stops on **source** preflight; dilated/native contour stages are unexecuted. The 1001 case reports contour count 1001, processed candidates 0, no scoring/offset/crops. Keep upstream processed-count metadata separate.
- **Degenerate contours:** the frozen collinear case contains two contour vertices and repeated minrect corners. Record explicit `minimum-side` rejection; do not apply positive-quad validation before that filter. Contour canonicalization must support short paths and preserve every vertex; the offset canonicalizer requires ≥3.
- **Independent gates:** score tolerance `1e-7` exceeds the below-0.5 fixture’s gap. Require exact disposition in addition to numeric tolerance. Likewise, minrect tolerance does not guarantee integer expansion/mapping equality. No snapping or tolerance widening.
- **Cleanup:** reuse `GeometryCleanup`; the existing Mat owner is private, so add a small local owner following its pattern. Register partially returned contours in `finally`. Inject failures after real allocation/native work, including crop stages. Preserve interruption/cancellation rather than converting it to success.

Acceptance is frozen as follows:

- Exact 24 IDs: unchanged `GeometryFixtureInputs.IDS` plus `candidate-count-1000`, `candidate-count-1001`, `candidate-count-4096`, `score-equal`, `score-below`, `final-size-three`, `minimum-side-two`, `collinear-height-one`.
- 22 complete cases require **1,020 full rows, 4,229 contour vertices, 17 scores, 15 distance/expansion/postquad/raw-box records, 232 expanded vertices and 14 final paired boxes**. Dispositions: 1,003 minimum-side, two score rejects, one final-size reject, 14 accepted.
- Exact old masks/dilation/counts; contours exact modulo cycle/winding; pre/post quads and sides absolute `1e-4`; scores `1e-7`; distance `1e-9 + 1e-12×|reference|`; integer input/expanded paths/raw/final/order/indices exact.
- All 13 actual-generated crops: dimensions/rotation and every BGR byte exact; existing matrix/coordinate tolerances unchanged.
- Freeze guard IDs before execution covering malformed authentication/schema/path/IDs/caps, nonfinite/range/shape inputs, threshold/rounding/sort boundaries, both budgets, degenerate geometry, unsupported offset output, native failure and cleanup. Missing/unexecuted guards cannot pass.
- Write fresh UUID report before work, checkpoint every case/guard, and persist final stage counts/errors before asserting.
- Parent: 43 old JVM tests plus new behavior checks; two Huawei runs of unchanged nine tests plus new probe; old reports unchanged apart from allowed timing/identity fields. Verify unchanged main APK, 199 old assets, exact 33 new assets, permissions/components/natives and task-graph isolation, including test build without an old main APK.

This proves only the bounded synthetic pipeline; arbitrary polygon support, native UNION hard bounds, header destruction and API36 SIGILL remain outside acceptance.