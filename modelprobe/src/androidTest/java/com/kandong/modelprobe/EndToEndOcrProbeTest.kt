package com.kandong.modelprobe

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.ColorSpace
import android.os.Build
import android.os.SystemClock
import android.util.AtomicFile
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.opencv.android.OpenCVLoader
import org.opencv.core.Core
import org.opencv.core.CvType
import org.opencv.core.Mat
import org.opencv.core.Size
import org.opencv.imgproc.Imgproc
import java.io.File
import java.util.UUID

/** Packaged synthetic originals only. Expected probabilities never enter the crop/recognition pipeline. */
@RunWith(AndroidJUnit4::class)
internal class EndToEndOcrProbeTest : DetectorProbeTestSupport() {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val assets = instrumentation.context.assets
    private val cleanup = GeometryCleanup()
    private val ort = Cleanup()

    private fun resize(source: GeometryImage, plan: DetectorPacking.Plan, afterAllocation: () -> Unit = {}): IntArray {
        require(source.width in 1..DetectorPacking.MAX_SOURCE_DIMENSION && source.height in 1..DetectorPacking.MAX_SOURCE_DIMENSION)
        val pixels = source.width.toLong() * source.height
        require(pixels in 1..DetectorPacking.MAX_PIXELS.toLong() && source.bgr.size.toLong() == pixels * 3)
        require(plan == DetectorPacking.plan(source.width, source.height))
        val count = DetectorPacking.elements(plan.inputShape, 3)
        val owned = mutableListOf<Pair<String, Mat>>()
        fun own(kind: String, mat: Mat): Mat {
            owned += kind to mat; cleanup.acquired[kind] = (cleanup.acquired[kind] ?: 0) + 1; return mat
        }
        try {
            val src = own("e2eResizeSource", Mat(source.height, source.width, CvType.CV_8UC3))
            val dst = own("e2eResizeDestination", Mat(plan.height, plan.width, CvType.CV_8UC3))
            afterAllocation()
            check(src.put(0, 0, source.bgr) == source.bgr.size)
            Imgproc.resize(src, dst, Size(plan.width.toDouble(), plan.height.toDouble()), 0.0, 0.0, Imgproc.INTER_LINEAR)
            check(dst.type() == CvType.CV_8UC3 && dst.rows() == plan.height && dst.cols() == plan.width)
            val bgr = ByteArray(count); check(dst.get(0, 0, bgr) == count)
            return IntArray(count / 3) { i -> -0x1000000 or (bgr[3 * i].toInt() and 255) or
                ((bgr[3 * i + 1].toInt() and 255) shl 8) or ((bgr[3 * i + 2].toInt() and 255) shl 16) }
        } finally {
            var failure: Throwable? = null
            owned.asReversed().forEach { (kind, mat) ->
                cleanup.attempts[kind] = (cleanup.attempts[kind] ?: 0) + 1
                try { mat.release(); check(mat.empty()); cleanup.released[kind] = (cleanup.released[kind] ?: 0) + 1 }
                catch (e: Throwable) { if (failure == null) failure = e else failure!!.addSuppressed(e) }
            }
            failure?.let { throw it }
        }
    }

    /** Decode the original detector PNG, which can be opaque RGBA or RGB.
     * File identity and decoded BGR identity are separate; neither is substituted for the other. */
    private fun original(reader: DetectorProbeInputs, c: DetectorCase, files: JSONObject): GeometryImage {
        val raw = reader.asset(c.source) // Authenticates ORIGINAL file bytes before decoding.
        require(c.sourceWidth in 1..DetectorPacking.MAX_SOURCE_DIMENSION && c.sourceHeight in 1..DetectorPacking.MAX_SOURCE_DIMENSION)
        val count = c.sourceWidth.toLong() * c.sourceHeight
        require(count in 1..DetectorPacking.MAX_PIXELS.toLong())
        val metadata = files.getJSONObject(c.source)
        require(metadata.getInt("width") == c.sourceWidth && metadata.getInt("height") == c.sourceHeight)
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true; inScaled = false }
        BitmapFactory.decodeByteArray(raw, 0, raw.size, bounds)
        require(bounds.outWidth == c.sourceWidth && bounds.outHeight == c.sourceHeight && bounds.outMimeType == "image/png")
        val options = BitmapFactory.Options().apply {
            inScaled = false; inPreferredConfig = Bitmap.Config.ARGB_8888
            inPreferredColorSpace = ColorSpace.get(ColorSpace.Named.SRGB)
        }
        val bitmap = checkNotNull(BitmapFactory.decodeByteArray(raw, 0, raw.size, options))
        cleanup.bitmapOpened++
        try {
            require(bitmap.width == c.sourceWidth && bitmap.height == c.sourceHeight && bitmap.config == Bitmap.Config.ARGB_8888 &&
                bitmap.colorSpace == ColorSpace.get(ColorSpace.Named.SRGB))
            val argb = IntArray(count.toInt()); bitmap.getPixels(argb, 0, c.sourceWidth, 0, 0, c.sourceWidth, c.sourceHeight)
            val bgr = GeometryProbeContract.bgr(c.sourceWidth, c.sourceHeight, argb) // Rejects non-opaque pixels.
            val expected = metadata.getString("rawBgrSha256"); ProbeInputs.verifyHash(bgr, expected)
            return GeometryImage(c.sourceWidth, c.sourceHeight, bgr, expected)
        } finally {
            cleanup.bitmapRecycleAttempts++; bitmap.recycle(); check(bitmap.isRecycled); cleanup.bitmapRecycled++
        }
    }

    @Test fun resizeRejectsInvalidInputsAndReleasesOnFailure() {
        check(OpenCVLoader.initLocal())
        val pixels = ByteArray(32 * 32 * 3) { index ->
            val color = if ((index / 3) % 32 < 16) intArrayOf(96, 64, 32) else intArrayOf(160, 128, 96)
            color[index % 3].toByte()
        }
        val source = GeometryImage(32, 32, pixels, "unused-test-only")
        val plan = DetectorPacking.plan(32, 32)
        listOf(source.copy(width = 0), source.copy(width = Int.MAX_VALUE), source.copy(bgr = byteArrayOf()),
            source.copy(width = 4096, height = 4096)).forEach {
            assertThrows(IllegalArgumentException::class.java) { resize(it, plan) }
        }
        assertThrows(IllegalArgumentException::class.java) { resize(source, DetectorPacking.plan(640, 91)) }
        assertEquals(0, cleanup.matOpened)
        val failure = assertThrows(IllegalStateException::class.java) {
            resize(source, plan) { throw IllegalStateException("E2E_AFTER_REAL_ALLOCATION") }
        }
        assertEquals("E2E_AFTER_REAL_ALLOCATION", failure.message)
        assertEquals(2, cleanup.matOpened); assertTrue(cleanup.balanced)
        val actual = resize(source, plan)
        assertEquals(0xff204060.toInt(), actual.first()); assertEquals(0xff6080a0.toInt(), actual.last())
        assertEquals(4, cleanup.matOpened); assertTrue(cleanup.balanced)
    }

    @Test fun originalPngThroughDetectionCropsRecognitionAndPageBindings() {
        val atomic = AtomicFile(File(instrumentation.targetContext.filesDir, "end-to-end-ocr-probe-report.json"))
        val cases = JSONArray(); val models = JSONArray(); val errors = JSONArray()
        val counts = linkedMapOf("cases" to 0, "detectorInferences" to 0, "empty" to 0, "crops" to 0,
            "cropChannels" to 0, "resizedChannels" to 0, "tensors" to 0, "floats" to 0,
            "recognitionInferences" to 0, "rawRows" to 0, "bindings" to 0)
        val expectedCounts = mapOf("cases" to 10, "detectorInferences" to 10, "empty" to 5, "crops" to 9,
            "cropChannels" to 116691, "resizedChannels" to 570960, "tensors" to 5, "floats" to 631728,
            "recognitionInferences" to 10, "rawRows" to 18, "bindings" to 18)
        fun add(k: String, n: Int = 1) { counts[k] = counts.getValue(k) + n }
        val ids = GeometryFixtureInputs.IDS.take(10)
        val report = JSONObject().put("schema", 1).put("runId", UUID.randomUUID().toString()).put("status", "started")
            .put("api", Build.VERSION.SDK_INT).put("device", Build.MODEL).put("cases", cases).put("models", models).put("errors", errors)
            .put("expectedCaseIds", JSONArray(ids)).put("expectedCounts", JSONObject(expectedCounts))
            .put("detectorFixtureSha256", DetectorProbeInputs.MANIFEST_SHA).put("detectorModelSha256", DetectorProbeInputs.MODEL_SHA)
            .put("geometryFixtureSha256", GeometryFixtureInputs.MANIFEST_SHA).put("recognitionFixtureSha256", CropRecognitionFixtures.MANIFEST_SHA)
            .put("boxFixtureSha256", BoxTraceFixtureInputs.MANIFEST_SHA).put("boxConfiguration", BoxPipelineContract.CONFIG)
            .put("recognitionConfiguration", CropRecognitionContract.CONFIG)
            .put("scope", "10 packaged synthetic originals through fresh detector inference, actual boxes/crops and both recognizers; page-coordinate binding. No real pages, translation or quality acceptance.")
            .put("bufferBudget", "One detector session then one recognizer session at a time; at most 5 prepared direct recognition tensors, 631728 floats/2526912 bytes. Transient one-case comparisons. Mat counts cover buffers, not finalizer-owned headers.")
        fun balanced() = !ort.uncertain && ort.opened == ort.closed && ort.opened == ort.closeAttempts
        fun checkpoint() {
            report.put("counts", JSONObject(counts.toMap())).put("geometryCleanup", cleanup.json())
                .put("ortCleanup", JSONObject().put("opened", ort.opened).put("closed", ort.closed)
                    .put("closeAttempts", ort.closeAttempts).put("uncertain", ort.uncertain).put("balanced", balanced()))
            save(atomic, report)
        }
        checkpoint()
        val started = SystemClock.elapsedRealtimeNanos(); var previousThreads: Int? = null; var completed = false
        try {
            check(OpenCVLoader.initLocal()); check(Core.VERSION == "5.0.0")
            previousThreads = Core.getNumThreads(); Core.setNumThreads(1); check(Core.getNumThreads() == 1)
            report.put("opencvRuntime", Core.VERSION).put("opencvThreads", Core.getNumThreads())
                .put("opencvBuildSha256", ProbeInputs.sha(Core.getBuildInformation().toByteArray()))
            val detector = DetectorProbeInputs { assets.open(it) }; val detectorCases = detector.manifest()
            val detectorManifest = assets.open("detector-v1/manifest.json").use { ProbeInputs.bounded(it, DetectorProbeInputs.MANIFEST_CAP) }
            ProbeInputs.verifyHash(detectorManifest, DetectorProbeInputs.MANIFEST_SHA)
            val sourceFiles = JSONObject(String(detectorManifest, Charsets.UTF_8)).getJSONObject("files")
            val geometry = GeometryFixtureInputs({ assets.open(it) }, { assets.list(it)?.toList().orEmpty() }, cleanup)
            val geometryCases = geometry.manifest()
            val fixture = CropRecognitionFixtures({ assets.open(it) }, { assets.list(it)?.toList().orEmpty() }, cleanup)
            val references = fixture.manifest()
            val traces = BoxTraceFixtureInputs({ assets.open(it) }, { assets.list(it)?.toList().orEmpty() }); val traceCases = traces.manifest()
            check(detectorCases.map { it.id } == ids)
            val engine = OrtProbeEngine(ort); check(engine.runtime == "1.30.0"); report.put("ortRuntime", engine.runtime)
            val pipeline = CropRecognitionPipeline(cleanup)
            val prepared = arrayListOf<CropRecognitionPipeline.Prepared>()
            engine.withModel(detector.model()) { session ->
                for (c in detectorCases) {
                    val entry = JSONObject().put("id", c.id).put("status", "started").put("passed", false); cases.put(entry); checkpoint()
                    val source = original(detector, c, sourceFiles)
                    // Re-encoding RGBA as RGB changes PNG bytes; compare authenticated decoded pixels instead.
                    check(source.sha == geometry.metadata(c.source).bgrSha) { "SOURCE_PIXEL_IDENTITY" }
                    entry.put("sourcePngSha256", detector.metadata(c.source).sha)
                        .put("geometrySourcePngSha256", geometry.metadata(c.source).sha).put("sourceBgrSha256", source.sha)
                    val plan = DetectorPacking.plan(source.width, source.height)
                    val argb = resize(source, plan); val packed = DetectorPacking.pack(plan.width, plan.height, argb)
                    val probability = infer(session, tensor(packed, plan.inputShape), plan.outputShape, ort) { output, _ ->
                        FloatArray(output.remaining()).also { output.duplicate().get(it) }
                    }
                    add("detectorInferences")
                    val resizeBefore = pipeline.resizeCalls; val packBefore = pipeline.packCalls
                    val actual = pipeline.run(c.id, plan.outputShape, probability, source.width, source.height, { source })
                    // All expected arrays enter comparisons only after fresh inference AND crop/packing have finished.
                    val reference = detector.load(c); val g = geometryCases.single { it.id == c.id }; val ref = references.single { it.getString("id") == c.id }
                    val probabilities = DetectorComparison.compare(java.nio.FloatBuffer.wrap(probability), reference.referenceOutput, reference.referenceMask)
                    val scoreBudget = EndToEndScoreBudget.fromDetector(probabilities)
                    val comparison = BoxTraceComparison(scoreBudget)
                    entry.put("scoreBudget", JSONObject().put("sameInputAbsoluteTolerance", EndToEndScoreBudget.SAME_INPUT_ATOL)
                        .put("measuredInputMaxAbsoluteError", probabilities.maxAbsolute).put("totalAbsoluteTolerance", scoreBudget)
                        .put("basis", "For equal score masks, abs(mean(actual)-mean(reference)) <= max(abs(actual-reference)); detector numeric gate and every mask/geometry/disposition check remain mandatory."))
                    val traceCase = traceCases.single { it.id == c.id }; val trace = comparison.trace(traceCase, actual.boxes, traces.trace(traceCase))
                    val inputExact = packed.contentEquals(reference.referenceInput)
                    val mask = comparison.bytes(actual.boxes.mask, geometry.mask(g, false))
                    val dilated = comparison.bytes(actual.boxes.dilated ?: byteArrayOf(), geometry.mask(g, true))
                    var pass = argb.contentEquals(reference.argb) && inputExact && probabilities.passed && trace.getBoolean("passed") &&
                        mask.getBoolean("passed") && dilated.getBoolean("passed") && actual.crops.size == g.rows.size
                    entry.put("detectorPixelsExact", argb.contentEquals(reference.argb)).put("detectorInputExact", inputExact)
                        .put("actualProbabilitySha256", ProbeInputs.sha(serialized(probability))).put("detector", metrics(probabilities))
                        .put("trace", trace).put("mask", mask).put("dilated", dilated)
                    val rows = JSONArray(); entry.put("crops", rows)
                    actual.crops.forEachIndexed { rank, crop ->
                        val expected = g.rows.single { it.id == crop.row.id }
                        val geometryResult = comparison.crop(crop, expected, geometry.image(expected.crop))
                        val resized = actual.resized[rank]; val r = ref.getJSONArray("rows").getJSONObject(rank)
                        val resizeResult = comparison.bytes(GeometryProbeContract.bgr(resized.width, resized.height, resized.argb), fixture.image(r.getString("resized")).bgr)
                        val dimensions = resized.width == r.getInt("resizedWidth") && resized.height == r.getInt("resizedHeight")
                        rows.put(JSONObject().put("id", crop.row.id).put("geometry", geometryResult).put("resize", resizeResult).put("dimensionsMatch", dimensions))
                        pass = pass && geometryResult.getBoolean("passed") && resizeResult.getBoolean("passed") && dimensions
                        add("crops"); add("cropChannels", crop.bgr.size); add("resizedChannels", resized.argb.size * 3)
                    }
                    val p = actual.prepared
                    if (p == null) {
                        val noWork = actual.boxes.status == BoxPipelineContract.Status.COMPLETE && actual.boxes.boxes.isEmpty() && actual.crops.isEmpty() && actual.resized.isEmpty() &&
                            actual.values == null && pipeline.resizeCalls == resizeBefore && pipeline.packCalls == packBefore
                        pass = pass && noWork && ref.getString("status") == "EMPTY_NO_INFERENCE"
                        entry.put("noRecognitionWork", noWork); add("empty")
                    } else {
                        require(prepared.size < 5 && prepared.sumOf { it.input.floats.remaining() }.toLong() + p.input.floats.remaining() <= 631728L)
                        val values = checkNotNull(actual.values); val exact = serialized(values).contentEquals(fixture.decoded(ref.getString("tensor")))
                        val mapping = p.plan.tensorRowToInput.map { p.rows[it].readingOrder }
                        val mappingExact = mapping == CropRecognitionFixtures.ints(ref.getJSONArray("tensorRowToReadingOrder"), 2)
                        val shapeExact = p.input.shape.toList() == CropRecognitionFixtures.ints(ref.getJSONArray("shape"), 2048).map { it.toLong() }
                        entry.put("tensorExact", exact).put("tensorShapeExact", shapeExact).put("tensorRowToReadingOrder", JSONArray(mapping)).put("mappingExact", mappingExact)
                        pass = pass && exact && shapeExact && mappingExact; prepared += p; add("tensors"); add("floats", values.size)
                    }
                    add("cases"); entry.put("status", "completed").put("passed", pass && cleanup.balanced); checkpoint()
                }
            }
            check(balanced()) // Detector closed before either recognition model is loaded.
            check(prepared.size == 5 && prepared.sumOf { it.input.floats.remaining() } == 631728)
            val inputs = ProbeInputs { instrumentation.targetContext.assets.open(it) }; val manifest = inputs.manifest()
            report.put("modelIdentities", JSONArray(manifest.models.map { JSONObject().put("id", it.id).put("sha256", it.sha).put("dictionarySha256", it.dictionarySha) }))
            for (model in manifest.models) {
                val dictionary = inputs.dictionary(model)
                engine.withModel(inputs.model(model)) { session ->
                    for (p in prepared) {
                        val entry = JSONObject().put("id", "${model.id}/${p.id}").put("status", "started").put("passed", false); models.put(entry); checkpoint()
                        val ref = references.single { it.getString("id") == p.id }.getJSONArray("models").let { a -> CropRecognitionFixtures.objects(a).single { it.getString("model") == model.id } }
                        val shape = CropRecognitionFixtures.ints(ref.getJSONArray("outputShape"), 18385).map { it.toLong() }.toLongArray()
                        p.input.floats.rewind(); val decoded = engine.infer(session, p.input, shape, dictionary)
                        val bindings = CropRecognitionContract.bind(BoxPipelineContract.Status.COMPLETE, p.rows, p.plan.tensorRowToInput, decoded.raw)
                        val expectedBindings = CropRecognitionFixtures.objects(ref.getJSONArray("bindings")).sortedBy { it.getInt("readingOrder") }
                        var identities = bindings.size == expectedBindings.size; val bindingRows = JSONArray()
                        bindings.forEachIndexed { rank, b ->
                            val e = expectedBindings.getOrNull(rank)
                            val match = e != null && b.row.id == e.getString("boxId") && b.row.originalIndex == e.getInt("originalIndex") &&
                                b.row.readingOrder == e.getInt("readingOrder") && b.tensorRow == e.getInt("tensorRow") && b.raw == e.getString("raw")
                            identities = identities && match
                            bindingRows.put(JSONObject().put("boxId", b.row.id).put("originalIndex", b.row.originalIndex).put("readingOrder", b.row.readingOrder)
                                .put("tensorRow", b.tensorRow).put("raw", b.raw).put("quad", JSONArray(b.row.quad.map { listOf(it.x, it.y) }))
                                .put("detectorScore", b.row.detectorScore).put("passed", match))
                        }
                        val textMatches = decoded.raw == CropRecognitionFixtures.strings(ref.getJSONArray("expectedHostRaw"))
                        val argmaxMatches = decoded.argmaxSha256 == ProbeInputs.sha(fixture.decoded(ref.getString("argmax")))
                        val readingMatches = bindings.map { it.raw } == CropRecognitionFixtures.strings(ref.getJSONArray("readingOrderRaw"))
                        entry.put("raw", JSONArray(decoded.raw)).put("argmaxSha256", decoded.argmaxSha256).put("textMatches", textMatches).put("argmaxMatches", argmaxMatches)
                            .put("bindings", bindingRows).put("readingOrderMatches", readingMatches).put("status", "completed").put("passed", identities && textMatches && argmaxMatches && readingMatches)
                        add("recognitionInferences"); add("rawRows", decoded.raw.size); add("bindings", bindings.size); checkpoint()
                    }
                }
            }
            completed = true
        } catch (e: Exception) { errors.put(e.stackTraceToString()) }
        finally {
            try { previousThreads?.let { Core.setNumThreads(it); check(Core.getNumThreads() == it) } }
            catch (e: Exception) { errors.put(e.stackTraceToString()) }
            fun passed(a: JSONArray) = (0 until a.length()).all { a.getJSONObject(it).optBoolean("passed", false) }
            fun actualIds(a: JSONArray) = (0 until a.length()).map { a.getJSONObject(it).getString("id") }
            val modelIds = listOf("ch", "latin").flatMap { model -> ids.take(5).map { "$model/$it" } }
            val ok = completed && errors.length() == 0 && counts == expectedCounts && actualIds(cases) == ids && actualIds(models) == modelIds &&
                passed(cases) && passed(models) && cleanup.balanced && cleanup.matOpened > 0 && balanced() && ort.opened == 46
            report.put("expectedModelIds", JSONArray(modelIds)).put("passed", ok).put("status", if (ok) "passed" else "failed")
                .put("elapsedNanos", SystemClock.elapsedRealtimeNanos() - started); checkpoint()
        }
        assertTrue("Inspect fresh end-to-end-ocr-probe-report.json", report.getBoolean("passed"))
    }
}
