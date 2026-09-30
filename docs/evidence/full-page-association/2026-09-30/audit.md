> 👁️ **Code Reviewer** | 步骤 1/1 | 386.7s

---

Proceed with a **test-only evidence association contract**. The existing preservation and whole-page rejection rules are sound. The main risks are treating geometric components as semantic equivalence, losing model provenance, and accidentally exposing mutable candidate geometry.

Read-only audit completed at `d56a87c`; worktree remains clean. No builds, tests, devices, network calls, or edits were performed. This consumes **1 of the phase’s 3 specialist steps**.

**Verified evidence.** In `ch-hant-seam p2/1`, these candidates must survive together:

| Candidate suffix | Raw text | Core owner | Page bounding box |
|---|---|---:|---|
| `s0/c0-r0-f0` | 含早餐，不含城市稅。 | false | `[70,623,303,653]` |
| `s1/c2-r2-f2` | 含早餐，不含城市税。 | true | `[67,621,303,656]` |

The [raw report](/Users/haoyuzuo/Projects/KanDong/docs/evidence/full-page-ocr/2026-09-25/emulator-final/full-page-ocr-ch-hant-seam.json:414) and [recorded baseline](/Users/haoyuzuo/Projects/KanDong/docs/FULL_PAGE_OCR_PROBE.md:57) confirm why core filtering is unacceptable. Across the 20 final reports, I counted **236 candidates, 20 empty strings, 46 noncore candidates, and 3,940 UTF-16 units**.

1. **Proposed API and preservation contract**

   Add `FullPageOcrAssociation`, accepting:

   ```kotlin
   associate(
       identity: PageIdentity,
       plan: FullPageStripPlanner.Plan,
       upstreamState: UpstreamState,
       stripReceipts: List<StripReceipt>,
       candidates: List<FullPageOcrContract.Candidate>
   ): Result
   ```

   - `PageIdentity`: one source batch/report identity, `CaptureVersion`, page fixture ID, recognizer `(id, sha, vocabulary)`, detector SHA and dictionary SHA.
   - `UpstreamState`: `COMPLETE`, `INCOMPLETE`, `CANCELLED`, `STALE`, `FAILED`.
   - `StripReceipt`: strip index, read/core rectangles, completion status, candidate count. Require receipts for **every planned strip, including empty strips**.
   - `Result`: either `Published(identity, rawCandidates, edges, groups)` or `NotPublished(reasonCodes)`. Rejection publishes no candidates or groups; it never returns a truncated prefix. The caller’s originals remain untouched.

   Every published candidate appears **exactly once** in `rawCandidates` and exactly once in group membership. Preserve every existing field, including original IDs, both quads, text, score, ownership flag, indices and recognition dimensions. Group membership references IDs; there is no winning candidate or replacement text.

   🔴 **Model provenance limitation:** [Candidate stores only `modelId`](/Users/haoyuzuo/Projects/KanDong/modelprobe/src/testShared/java/com/kandong/modelprobe/FullPageOcrContract.kt:33). Require the recognizer tuple to match the frozen model table and every candidate to match the envelope’s model ID/version/page. The adapter must preserve the single source envelope and reject concatenated sources. A bare candidate cannot retrospectively authenticate its model SHA; do not claim otherwise.

   🔴 **Deep immutability:** copy both quad lists and reconstruct provenance into privately owned snapshots. Wrap all returned collections, including nested membership/reason lists, with unmodifiable copies. Existing `Provenance` has reference equality, so determinism tests must compare full field values rather than relying on `Candidate.equals()`.

2. **Validation and failure-safe behavior**

   Before association:

   - Require `COMPLETE` and exactly one successful receipt per planned strip; receipt counts must equal the candidate histogram.
   - Validate the supplied plan against the frozen planner’s dimensions, strip indices and read/core rectangles.
   - Require unique original IDs; never use `distinctBy`.
   - Require all six `CaptureVersion` fields and page/model identity to match.
   - Enforce **64 candidates/strip, 128/page and 8,192 UTF-16 units/page**, inclusively; use `Long` accumulation.
   - Require four finite points per quad, local points inside source pixel coordinates, and page points equal to local points plus read origin within `1e-6`.
   - Preserve valid nonrectangular quads. Finite zero-width/height geometry remains an isolated member with `UNSUPPORTED_GEOMETRY`; malformed/nonfinite/out-of-bounds metadata rejects the entire input.
   - Preserve score/core values, but never use them to select members, edges or text.

   Return deterministic reason codes for invalid input, identity mismatch, duplicate IDs, budget overflow and incomplete/cancelled/stale input. Validate categories in a fixed order, independent of candidate permutation.

   This synchronous diagnostic does not establish live freshness or detect cancellation occurring after its input snapshot; existing publication guards remain necessary when integration is eventually authorized.

3. **Small geometric rule set to freeze before implementation**

   Use page-coordinate AABBs solely as association evidence. Do not expand boxes, concatenate fragments or perform text matching to create edges.

   For two candidates from **adjacent strips whose read rectangles overlap**:

   ```text
   ix = positive intersection width
   iy = positive intersection height

   strong:
       ix / max(widths)  >= 0.80
       iy / max(heights) >= 0.80

   possible clipped overlap, when strong fails:
       ix / max(widths)  >= 0.80
       iy / min(heights) >= 0.50
       at least one box contacts an internal top/bottom read edge
   ```

   Require strictly positive intersection within the shared read region. “Contacts” means within **2 source pixels** of the first/last readable pixel; the bottom pixel is `read.bottom - 1`. Threshold comparisons are inclusive. No edge for mere touching, gaps, nonadjacent strips or matching text alone.

   - A quad is unambiguous for this diagnostic only when its supplied vertices form a cyclic axis-aligned rectangle within `1e-6`. Other positive-area shapes retain AABB evidence with `NON_AXIS_ALIGNED` uncertainty. No polygon engine is needed.
   - Read-edge or page-edge contact adds `POSSIBLE_CLIP`; it never proves clipping.
   - Same-strip strongly overlapping candidates remain separate unless cross-strip edges connect them; flag both with `SAME_STRIP_OVERLAP`.
   - Build connected components of cross-strip edges. Preserve all direct edges so callers can distinguish direct evidence from transitive membership.
   - Any component with competing matches, repeated strip indices or a missing direct pairwise edge receives `MULTIPLE_MATCHES`, `SAME_STRIP_MULTIPLE` and/or `TRANSITIVE_CHAIN`. Never greedily choose one matching.
   - Only an isolated **two-member strong pair**, without geometry/clipping/competition uncertainty, qualifies as geometrically unambiguous.

   Keep text and geometry assessments separate:

   ```text
   text:     SINGLE | IDENTICAL_NONEMPTY | ALL_EMPTY | DIFFERENT_RAW
   geometry: ISOLATED | UNAMBIGUOUS_PAIR | UNCERTAIN
   reasons:  ordered, cumulative codes
   ```

   `agreedRaw` is optional and may be populated only for `IDENTICAL_NONEMPTY + UNAMBIGUOUS_PAIR`. Use exact Kotlin string equality—no trimming, Unicode normalization, Chinese conversion or correction. Empty/nonempty disagreement remains `DIFFERENT_RAW` with `HAS_EMPTY`.

   **An edge, component, or agreed string is not evidence of semantic correctness.** These cutoffs are uncalibrated diagnostic choices, not production acceptance thresholds. At 128 candidates, exhaustive pair inspection is bounded by 8,128 pairs.

4. **Twenty behavioral fixtures to freeze**

   Use `plan(1176,2400)` and page-coordinate rectangles. Its first read intervals are `[0,664)`, `[536,1264)`, `[1136,1864)`. Unless specified otherwise, `A` and `B` belong to strips 0 and 1.

   | # | Fixture | Expected groups / reason |
   |---:|---|---|
   | 1 | Identical nonempty text, identical rectangle `[100,580,300,620]` | `{A,B}`; unambiguous pair; exact `agreedRaw`. |
   | 2 | Same geometry, differing text; parameterize `稅/税`, trailing space, composed/decomposed accent | `{A,B}`; `DIFFERENT_RAW`; no normalization or winner. |
   | 3 | Adjacent columns, including slight horizontal overlap below 80%; identical labels | `{A}`, `{B}`; no qualifying spatial edge. |
   | 4 | Identical labels at different vertical positions; repeat with nonadjacent strips | Separate singletons; text cannot create edges. |
   | 5 | Full `[100,1110,300,1160]` in s1; partial `[100,1136,300,1160]` in s2 | One uncertain group; `POSSIBLE_CLIP`; differing strings remain conflicting even if one is a substring. |
   | 6 | Tall line: s0 `[100,500,300,663]`, s1 `[100,536,300,750]` | One uncertain clipped-overlap group; no agreed text even when strings match. |
   | 7 | Same text with disjoint boxes or exactly touching edges | Separate groups; preserve any individual clipping flags. |
   | 8 | Centers drift from 599 to 601 across core seam; vary valid scores/core flags | Same association result; all original scores/flags preserved. |
   | 9 | Coincident pair: empty/empty and empty/nonempty variants | One group; respectively `ALL_EMPTY` or `DIFFERENT_RAW + HAS_EMPTY`; no agreed text. |
   | 10 | Complete blank page, all strip receipts present with zero counts | Published empty result; distinguish from incomplete input. |
   | 11 | Two strongly overlapping candidates from the same strip only | Two singleton groups, both `SAME_STRIP_OVERLAP`; retain both IDs. |
   | 12 | A from s0 overlaps B and C from s1 | `{A,B,C}`; competing matches/same-strip ambiguity; no pair selection. |
   | 13 | Chain: s0 `[100,600,300,663]`, s1 `[100,536,300,1263]`, s2 `[100,1136,300,1200]` | `{A,B,C}`; A–B/B–C evidence, no A–C; `TRANSITIVE_CHAIN`, clipping uncertainty. |
   | 14 | Rotated rectangle, trapezoid, malformed-order/degenerate variants | Positive AABB overlaps retain uncertain evidence; never agreed text. Zero-span geometry stays an unsupported singleton. |
   | 15 | Exactly/below 0.80 and 0.50; exactly/above 2-pixel edge distance; page boundaries | Inclusive thresholds behave as specified; page-edge contact suppresses agreed text. |
   | 16 | Cancelled/stale/failed/incomplete; missing or unsuccessful receipt, including zero-candidate input | `NotPublished`; zero groups. |
   | 17 | Change each capture-version field, page ID, candidate model ID or envelope model SHA; mixed source envelopes | Whole-input rejection; never partition mixed identities into publishable groups. |
   | 18 | Duplicate ID, wrong strip/read/core, bad translation, wrong quad length, NaN/infinity/out-of-bounds | Whole-input rejection; no silent repair or filtering. |
   | 19 | Exactly 64/strip, 128/page, 8,192 UTF-16 units; exceed each separately; include supplementary characters | Limits accepted; excess rejected before grouping; count UTF-16 units, not code points. |
   | 20 | Reverse/shuffle candidates and receipts; mutate caller lists/quads after return; attempt output mutation | Identical canonical values/order; snapshots unchanged; returned collections immutable. |

   For ordering, sort members by original ID, edges by ordered endpoint IDs, and groups by their sorted membership IDs. A group identifier may use its minimum member ID strictly as a stable key, never as a representative.

5. **Allowed implementation files and archived replay**

   The implementation worker should own only these two new files:

   ```text
   modelprobe/src/testShared/java/com/kandong/modelprobe/FullPageOcrAssociation.kt
   modelprobe/src/contextTest/java/com/kandong/modelprobe/FullPageOcrAssociationTest.kt
   ```

   Existing [source-set wiring](/Users/haoyuzuo/Projects/KanDong/modelprobe/build.gradle.kts:21) already supplies Java/Kotlin 17 and JUnit4. No Gradle edits, Android imports, model loading, JSON dependency or changes to frozen contracts/pipeline are needed. Root owns fixture freezing, replay artifacts and documentation.

   Replay procedure:

   1. Freeze the above rules, fixture expectations and source hashes before running association over archived outputs.
   2. Enumerate exactly the 20 filenames from `emulator-final/full-page-ocr-summary.json`; hash each original report.
   3. Extract only actual candidates, source/model identity, dimensions, completion and strip receipts. **Exclude `quality`, expected text, glyph bounds, placements and reference-match IDs from association input.**
   4. Reconstruct existing `Candidate` objects without rerunning provenance generation: preserve original IDs, quads and flags. Map recognition width/time from the saved tensor shapes.
   5. Blank reports lack candidate-carried `CaptureVersion`. The [frozen runner’s version formula](/Users/haoyuzuo/Projects/KanDong/modelprobe/src/androidTest/java/com/kandong/modelprobe/FullPageOcrProbeTest.kt:150) permits an explicitly labelled reconstruction from the same run’s session and fixed page/model ordering. Record that identity as **derived**, not directly stored; otherwise report missing identity.
   6. Assert lossless field preservation and membership coverage: **236 total candidates, 20 empty, 46 noncore**, including both `p2/1` conflict members. Record groups, direct edges, uncertainty reasons and permutations without expecting a particular “correct” transcript.
   7. Only after outputs are fixed, consult archived quality annotations for descriptive analysis. Do not retune thresholds to improve those scores or select the expected Chinese variant.

   Do not run the existing `audit-reports.py` against frozen evidence: it writes summary files into its input directory. Preserve the archives and place new replay output separately.

The proposed scope is ready for root to freeze and hand to implementation. Acceptance should establish lossless, deterministic association and explicit uncertainty; OCR quality remains a separate unresolved result.