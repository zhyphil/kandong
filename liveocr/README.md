# Bounded on-device OCR runtime

This Android library contains the curated numerical pipeline previously exercised in
`modelprobe`. It does not capture screens, request permissions, call a network service,
create a Service, render UI, translate text, or persist/log image or recognition data.
The library is a technical implementation, not OCR quality or device acceptance evidence.

Public API is in `com.kandong.liveocr`:

```kotlin
data class OcrBlock(val id: String, val text: String, val left: Int, val top: Int,
    val right: Int, val bottom: Int, val score: Double)
data class OcrPage(val blocks: List<OcrBlock>, val rawCandidateCount: Int)
class LiveOcrEngine(private val context: android.content.Context) {
    fun recognize(rgba: ByteArray, width: Int, height: Int, language: String,
        isCurrent: () -> Boolean): OcrPage
}
```

- Call synchronously off the Android main thread. Only one call may run across all
  engine instances; concurrent attempts fail with `BUSY`. No queue or retry is created.
- Supply exactly `width * height * 4` tightly packed RGBA8888 bytes, with opaque alpha.
  Keep them unchanged until return/throw; the caller retains and wipes this array in
  its own `finally`. Temporary BGR/crop/resize/tensor buffers are wiped by the runtime.
- Supported pages have width 32–2048, height 32–4096, and at most 4,194,304 pixels.
  The unchanged strip planner must also accept the geometry. It uses a 64-pixel halo,
  at most 16 strips and at most 1,048,576 detector pixels per strip.
- Language is exactly `EN`, `FR`, `ZH-HANS`, or `ZH-HANT`. EN/FR use the Latin model;
  both Chinese selections use the Chinese model. There is no language detection,
  script conversion, normalization, fallback or mixed-language policy here.
- `isCurrent` must be cheap and safe to call on the worker. Return false when consent,
  page identity, content, visibility, freshness or session state becomes invalid.
  Rejection is sticky; callback exceptions also cancel. Checks surround every native
  inference and final publication. Native inference/Clipper calls are synchronous
  and cannot be forcibly interrupted midway.
- Recognition preprocessing, crop resize and output checks share a 2048-pixel line width ceiling. Full lines are resized proportionally within it; none is silently clipped. One input is at most 294,912 floats (1,179,648 bytes). Output has a 5,000,000-float ceiling (20 MB), checked before native inference; a 2048-wide Chinese line needs 4,706,560 floats. These are bounded runtime allocations, not a measured total process-memory limit.
- All final DB boxes are recognized and retained internally until the whole page
  finishes, including empty and unowned overlap candidates. At most 64 per strip,
  128 per page and 8192 total raw UTF-16 characters are allowed. Over-budget work fails;
  it never returns a truncated page. Contour/vertex/offset/recognition-width limits from
  the experiment also remain in force.
- `rawCandidateCount` counts these final DB boxes across strips before ownership
  filtering, not every threshold contour. Published blocks use core-center ownership
  and sort by top, then left. Repeated text at separate positions is not deduplicated.
  Coordinates are in the full input page, with half-open right/bottom bounds. `score`
  is the DB detector score, not a calibrated recognition confidence. IDs are local to
  one call; callers must pair them with their own page/session identity.
- Any owned box with empty/whitespace-only decoded text rejects the entire page.
  A candidate touching an internal strip read edge also rejects the entire page with
  `STRIP_BOUNDARY_AMBIGUITY`, including unowned fragments. This is deliberately
  conservative: the runtime does not merge partial lines or infer missing counterparts.
  A fully successful detection pipeline with no boxes can return an empty page.
- `LiveOcrException.code` is a fixed code without native exception causes or text.
  All sessions, options, tensors, results and Mats close before publication. Uncertain
  cleanup poisons this runtime for the process lifetime. Immutable result Strings are
  not securely erasable; the engine retains no page state after the call.

The runtime initializes OpenCV locally and requires exactly `Core.VERSION == "5.0.0"`.
OpenCV threads and ORT intra/inter-op threads are set to one; ORT uses CPU sequential
execution, disables telemetry and disables the CPU arena. OpenCV's prior thread count
is restored. Other code must not concurrently alter OpenCV's process-wide thread setting.
The ORT environment is a process singleton whose Java `close()` is a no-op. It is not a
per-call resource. The manifest removes the dependency's telemetry initializer and
inherited network permissions; a network-capable host must declare its own permissions
in its higher-priority manifest and verify its merged manifest.

## Packaging requirements

Compile SDK 36, minimum SDK 29, JVM 17. Dependencies are only ORT Android 1.30.0,
OpenCV 5.0.0.1, and JUnit4 for unit tests. `stageLiveOcrAssets` is a task-time `Sync`
with an exact file allowlist, sizes and SHA-256 pins. It verifies source and staged
bytes. Runtime independently authenticates the bytes it consumes before loading
models or parsing dictionaries. Assets live under `assets/liveocr/` to avoid collisions.

Required existing local files:

- `modelprobe/build/detector-assets/detector-model/ch_PP-OCRv5_det_mobile.onnx`
  (4,819,576 bytes; SHA-256 `4d97c44a20d30a81aad087d6a396b08f786c4635742afc391f6621f5c6ae78ae`).
- `modelprobe/src/main/assets/models/{ch,latin}_PP-OCRv5_rec_mobile.onnx`, plus
  `modelprobe/src/main/assets/probes/{ch,latin}-dictionary.json`. Their exact identities
  are pinned from `probes/manifest.json`'s models array in `OcrAssets.kt` and Gradle.
- Existing supplier notices in `modelprobe/src/main/assets/legal/`, detector cache
  `legal/`, the two enumerated `modelprobe/src/androidTest/assets/opencv-legal/` files,
  and this module's unchanged `third_party/clipper/` copies.

No download, model generation, full fixture manifest, task tensor, expected output,
or broad androidTest directory is packaged. No ONNX file is tracked here. Missing or
different model/notice files are an environment blocker; the task provides no substitute.

## Curation and file ownership

- `LiveOcrEngine.kt`, `OcrRuntimeContract.kt`, `OcrAssets.kt`, `OcrResources.kt`:
  public entry, fixed-code failure, lifecycle, authentication, and publication guards.
- `FullPageOcrPipeline.kt`, `SingleFrameStripInput.kt`, `FullPageStripPlanner.kt`:
  curated original run/detect/resize, RGBA conversion and geometry, without `runStaged`
  or synthetic capture receipts.
- `BoxPipelineOpenCv.kt`, `BoxPipelineContract.kt`, `GeometryOpenCvProbe.kt`,
  `GeometryProbeContract.kt`, `CropRecognitionPipeline.kt`, `PolygonOffsetKernel.kt`:
  DB thresholds, score, dilation, unclip, default perspective crop and linear resize.
- `DetectorPacking.kt`, `RecognitionPacking.kt`, `CtcDecoder.kt`, `OrtRuntime.kt`:
  unchanged numerical normalization/rounding/CTC rules; only tensor/infer ownership
  was taken from `DetectorProbeTestSupport`. No JUnit, AtomicFile or reporting in main.
- `src/main/java/de/lighti/clipper/`: the same 11 vendored Java files, byte-for-byte,
  from `modelprobe/src/testShared/java/de/lighti/clipper/`. They are not original KanDong
  code. Build verification pins every file against the preserved patched provenance.
- `third_party/clipper/`: unchanged Boost license, historical notice, provenance and
  exact pre-existing rounding patch. `third_party/CURATION.txt` explains the new reuse.
- `src/test/java/com/kandong/liveocr/OcrRuntimeContractTest.kt`: pure JUnit4 coverage
  of mapping, cancellation, buffer lifetime and bounds; no device or native test fixture.
- Module Gradle/manifest and the single root `include(":liveocr")` entry complete the
  changes. No existing module source, script, root build file or project worklog changed.

## Verification handoff

This worker checked local asset hashes/lengths, exact vendor bytes, source scope and
whitespace. It did **not** run Gradle, compile, unit tests, native inference or a device.
The root task owns cache-writing validation, for example:

```sh
./gradlew --offline :liveocr:testDebugUnitTest :liveocr:assembleDebug
```

Before claiming integration, inspect the built AAR/host APK for the exact `liveocr/`
asset list, merged permissions/telemetry initializer removal, and run the parent
integration's existing checks. This module does not establish real-page OCR quality,
Huawei compatibility, latency/memory acceptance, screen ownership/privacy filtering,
translation quality or release licensing clearance. The preserved notices describe
their historical experiment provenance; they do not certify a new release.

Root integration verification on 2026-10-01: 13 library JVM tests passed; debug host APK built with 26 authenticated OCR assets and three models. Release host contains none. This is build evidence, not real-screen OCR acceptance; see `../docs/LIVE_TRANSLATION_DEV.md`. Vendor whitespace and the historical patch are retained byte-for-byte so their pinned provenance remains verifiable.
