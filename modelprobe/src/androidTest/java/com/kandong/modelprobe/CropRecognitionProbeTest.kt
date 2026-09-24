package com.kandong.modelprobe

import android.os.Build
import android.os.SystemClock
import android.util.AtomicFile
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.opencv.android.OpenCVLoader
import org.opencv.core.Core
import java.io.File
import java.io.InputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.UUID

@RunWith(AndroidJUnit4::class)
internal class CropRecognitionProbeTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val assets = instrumentation.context.assets
    private val cleanup = GeometryCleanup()
    private val ortCleanup = Cleanup()
    private val errors = JSONArray()
    private fun reader(open: (String) -> InputStream = { assets.open(it) }, list: (String) -> List<String> = { assets.list(it)?.toList().orEmpty() }) = CropRecognitionFixtures(open, list, cleanup)
    private fun rejected(action: () -> Unit): Boolean = try { action(); false } catch (_: Exception) { true }
    private fun error(where: String, e: Throwable) { errors.put(JSONObject().put("where", where).put("error", e.stackTraceToString())) }
    private fun bytes(actual: ByteArray, expected: ByteArray): JSONObject {
        var different = kotlin.math.abs(actual.size - expected.size); var maximum = 0
        for (i in 0 until minOf(actual.size, expected.size)) {
            val d = kotlin.math.abs((actual[i].toInt() and 255) - (expected[i].toInt() and 255))
            if (d != 0) different++; maximum = maxOf(maximum, d)
        }
        return JSONObject().put("actualBytes", actual.size).put("expectedBytes", expected.size).put("differentBytes", different)
            .put("maximumChannelError", maximum).put("actualSha256", ProbeInputs.sha(actual)).put("expectedSha256", ProbeInputs.sha(expected)).put("passed", different == 0)
    }
    private fun rowJson(r: GeometryProbeContract.Row) = JSONObject().put("boxId", r.id).put("originalIndex", r.originalIndex)
        .put("readingOrder", r.readingOrder).put("detectorScore", r.detectorScore).put("quad", JSONArray(r.quad.map { listOf(it.x, it.y) }))
    private fun ortJson() = JSONObject().put("opened", ortCleanup.opened).put("closeAttempts", ortCleanup.closeAttempts)
        .put("closed", ortCleanup.closed).put("uncertain", ortCleanup.uncertain)
    private fun ortBalanced() = !ortCleanup.uncertain && ortCleanup.opened == ortCleanup.closed && ortCleanup.opened == ortCleanup.closeAttempts

    @Test fun actualBoxesThroughBothRecognizers() {
        val file = AtomicFile(File(instrumentation.targetContext.filesDir, "crop-recognition-probe-report.json"))
        val cases = JSONArray(); val models = JSONArray(); val guards = JSONArray()
        val counts = linkedMapOf("cases" to 0, "complete" to 0, "empty" to 0, "crops" to 0, "cropChannels" to 0,
            "resizes" to 0, "resizedChannels" to 0, "tensors" to 0, "floats" to 0, "inferences" to 0, "rawRows" to 0, "bindings" to 0)
        val expectedCounts = mapOf("cases" to 16, "complete" to 16, "empty" to 8, "crops" to 13, "cropChannels" to 139875,
            "resizes" to 13, "resizedChannels" to 631008, "tensors" to 8, "floats" to 816048, "inferences" to 16, "rawRows" to 26, "bindings" to 26)
        fun add(k: String, n: Int = 1) { counts[k] = counts.getValue(k) + n }
        val report = JSONObject().put("schema", 1).put("runId", UUID.randomUUID().toString()).put("status", "started")
            .put("api", Build.VERSION.SDK_INT).put("device", Build.MODEL).put("manufacturer", Build.MANUFACTURER)
            .put("fixtureSha256", CropRecognitionFixtures.MANIFEST_SHA).put("parentFixtureSha256", GeometryFixtureInputs.MANIFEST_SHA)
            .put("probeManifestSha256", ProbeInputs.MANIFEST_SHA).put("codeConfiguration", CropRecognitionContract.CONFIG)
            .put("boxConfiguration", BoxPipelineContract.CONFIG).put("opencvArtifact", "org.opencv:opencv:5.0.0.1")
            .put("expectedCaseIds", JSONArray(GeometryFixtureInputs.IDS)).put("expectedGuardIds", JSONArray(GUARD_IDS))
            .put("expectedCounts", JSONObject(expectedCounts)).put("cases", cases).put("models", models).put("guards", guards).put("errors", errors)
            .put("scope", "Frozen synthetic probabilities/source images; actual boxes/crops/recognition. No fresh detector inference, real screens, translation or quality claim.")
            .put("bufferBudget", "At most 8 prepared direct tensors, 816048 float32/3264192 bytes cumulative; transient one-case crops/resizes/packing/expected bytes; one model/session at a time. Mat release counts buffers, not native header destruction.")
        val start = SystemClock.elapsedRealtimeNanos()
        fun checkpoint() {
            report.put("counts", JSONObject(counts.toMap())).put("geometryCleanup", cleanup.json()).put("ortCleanup", ortJson())
            val output = file.startWrite()
            try { output.write(report.toString(2).toByteArray()); file.finishWrite(output) }
            catch (e: Throwable) { file.failWrite(output); throw e }
        }
        checkpoint() // Before loading inputs or either native runtime.
        var previousThreads: Int? = null; var completed = false
        try {
            check(OpenCVLoader.initLocal()); previousThreads = Core.getNumThreads(); Core.setNumThreads(1)
            check(Core.getNumThreads() == 1 && Core.VERSION.startsWith("5."))
            report.put("opencvRuntime", Core.VERSION).put("opencvThreads", Core.getNumThreads()).put("thread", Thread.currentThread().name)
                .put("opencvBuildSha256", ProbeInputs.sha(Core.getBuildInformation().toByteArray())).put("opencvOptimized", Core.useOptimized())
            val fixture = reader(); val references = fixture.manifest()
            val parent = GeometryFixtureInputs({ assets.open(it) }, { assets.list(it)?.toList().orEmpty() }, cleanup)
            val parents = parent.manifest(); val pipeline = CropRecognitionPipeline(cleanup)
            val prepared = arrayListOf<CropRecognitionPipeline.Prepared>()
            val comparison = BoxTraceComparison()
            var guardCrop: GeometryOpenCvProbe.CropResult? = null
            parents.forEachIndexed { index, c ->
                val entry = JSONObject().put("id", c.id).put("status", "started").put("passed", false); cases.put(entry); checkpoint()
                try {
                    val beforeResize = pipeline.resizeCalls; val beforePack = pipeline.packCalls
                    val actual = pipeline.run(c.id, c.shape.toLongArray(), parent.probability(c), c.sourceWidth, c.sourceHeight, { parent.image("${c.id}-source.png") })
                    add("cases"); if (actual.boxes.status == BoxPipelineContract.Status.COMPLETE) add("complete")
                    // References enter comparisons only AFTER actual boxes/crops/resize/pack have returned.
                    val expected = references[index]
                    val mask = bytes(actual.boxes.mask, parent.mask(c, false)); val dilated = bytes(actual.boxes.dilated ?: byteArrayOf(), parent.mask(c, true))
                    entry.put("boxStatus", actual.boxes.status.name).put("mask", mask).put("dilated", dilated)
                    var pass = actual.boxes.status == BoxPipelineContract.Status.COMPLETE && mask.getBoolean("passed") && dilated.getBoolean("passed") && actual.boxes.contourCount == c.contours && actual.crops.size == c.rows.size
                    val cropRows = JSONArray(); entry.put("crops", cropRows)
                    actual.crops.forEachIndexed { rank, crop ->
                        add("crops"); add("cropChannels", crop.bgr.size)
                        if (guardCrop == null) guardCrop = crop
                        val refRow = c.rows.single { it.id == crop.row.id }
                        val geometry = comparison.crop(crop, refRow, parent.image(refRow.crop))
                        val r = expected.getJSONArray("rows").getJSONObject(rank)
                        val resized = actual.resized[rank]; add("resizes")
                        val resizedBgr = GeometryProbeContract.bgr(resized.width, resized.height, resized.argb)
                        val png = fixture.image(r.getString("resized"))
                        add("resizedChannels", resizedBgr.size)
                        val pixel = bytes(resizedBgr, png.bgr)
                        val argb = bytes(CropRecognitionFixtures.intBytes(resized.argb), fixture.decoded(r.getString("resizedArgb")))
                        val dimensions = crop.width == r.getInt("width") && crop.height == r.getInt("height") && crop.rotated == r.getBoolean("rotateCounterClockwise90") && resized.width == png.width && resized.height == png.height
                        cropRows.put(rowJson(crop.row).put("geometry", geometry).put("resizePixels", pixel).put("resizeArgb", argb).put("dimensionsMatch", dimensions))
                        pass = pass && geometry.getBoolean("passed") && pixel.getBoolean("passed") && argb.getBoolean("passed") && dimensions
                    }
                    val p = actual.prepared
                    if (p == null) {
                        add("empty")
                        val noWork = actual.values == null && actual.crops.isEmpty() && actual.resized.isEmpty() && pipeline.resizeCalls == beforeResize && pipeline.packCalls == beforePack
                        entry.put("noInference", noWork); pass = pass && noWork && expected.getString("status") == "EMPTY_NO_INFERENCE"
                    } else {
                        require(prepared.size < 8 && prepared.sumOf { it.input.floats.remaining() }.toLong() + p.input.floats.remaining() <= 816048L); prepared += p
                        add("tensors"); val values = checkNotNull(actual.values); add("floats", values.size)
                        val raw = fixture.decoded(expected.getString("tensor")); ProbeInputs.validateFloats(raw)
                        val expectedBits = ByteBuffer.wrap(raw).order(ByteOrder.LITTLE_ENDIAN).asIntBuffer()
                        var differences = kotlin.math.abs(values.size - expectedBits.remaining())
                        for (i in 0 until minOf(values.size, expectedBits.remaining())) if (values[i].toRawBits() != expectedBits.get(i)) differences++
                        val mapping = p.plan.tensorRowToInput.map { p.rows[it].readingOrder }
                        val shape = CropRecognitionFixtures.ints(expected.getJSONArray("shape"), 2048).map { it.toLong() }
                        val mappingMatches = mapping == CropRecognitionFixtures.ints(expected.getJSONArray("tensorRowToReadingOrder"), 2)
                        val actualRaw = CropRecognitionFixtures.intBytes(IntArray(values.size) { values[it].toRawBits() })
                        entry.put("tensor", bytes(actualRaw, raw).put("differentFloatBits", differences).put("actualShape", JSONArray(p.input.shape.toList()))
                            .put("mapping", JSONArray(mapping)).put("mappingMatches", mappingMatches))
                        pass = pass && differences == 0 && mappingMatches && p.input.shape.toList() == shape
                    }
                    entry.put("status", "completed").put("passed", pass && cleanup.balanced)
                } catch (e: Exception) { entry.put("status", "failed"); error(c.id, e) }
                finally { checkpoint() }
            }
            require(prepared.size == 8 && prepared.sumOf { it.input.floats.remaining() } == 816048)
            val inputs = ProbeInputs { instrumentation.targetContext.assets.open(it) }; val manifest = inputs.manifest()
            val engine = OrtProbeEngine(ortCleanup); report.put("ortRuntime", engine.runtime)
            report.put("modelIdentities", JSONArray(manifest.models.map { JSONObject().put("id", it.id).put("sha256", it.sha).put("dictionarySha256", it.dictionarySha) }))
            manifest.models.forEach { model ->
                val dictionary = inputs.dictionary(model)
                engine.withModel(inputs.model(model)) { session ->
                    prepared.forEach { p ->
                        val result = JSONObject().put("id", "${model.id}/${p.id}").put("status", "started").put("passed", false); models.put(result); checkpoint()
                        try {
                            val ref = references.single { it.getString("id") == p.id }.getJSONArray("models").let { a -> CropRecognitionFixtures.objects(a).single { it.getString("model") == model.id } }
                            val shape = CropRecognitionFixtures.ints(ref.getJSONArray("outputShape"), 18385).map { it.toLong() }.toLongArray()
                            // Only pinned output shape is allowed across the inference boundary.
                            p.input.floats.rewind()
                            val decoded = engine.infer(session, p.input, shape, dictionary); add("inferences"); add("rawRows", decoded.raw.size)
                            val bindings = CropRecognitionContract.bind(BoxPipelineContract.Status.COMPLETE, p.rows, p.plan.tensorRowToInput, decoded.raw)
                            add("bindings", bindings.size)
                            val argmax = fixture.decoded(ref.getString("argmax"))
                            val indices = ByteBuffer.wrap(argmax).order(ByteOrder.LITTLE_ENDIAN).asIntBuffer()
                            while (indices.hasRemaining()) require(indices.get() in dictionary.indices)
                            val expectedText = CropRecognitionFixtures.strings(ref.getJSONArray("expectedHostRaw"))
                            val expectedBindings = CropRecognitionFixtures.objects(ref.getJSONArray("bindings")).sortedBy { it.getInt("readingOrder") }
                            val bindingRows = JSONArray(); var identities = true
                            bindings.forEachIndexed { rank, b ->
                                val e = expectedBindings[rank]
                                val same = b.row.id == e.getString("boxId") && b.row.originalIndex == e.getInt("originalIndex") && b.row.readingOrder == e.getInt("readingOrder") && b.tensorRow == e.getInt("tensorRow") && b.raw == e.getString("raw")
                                identities = identities && same
                                bindingRows.put(rowJson(b.row).put("tensorRow", b.tensorRow).put("raw", b.raw).put("passed", same))
                            }
                            val textMatches = decoded.raw == expectedText
                            val argmaxMatches = decoded.argmaxSha256 == ProbeInputs.sha(argmax)
                            val readingMatches = bindings.map { it.raw } == CropRecognitionFixtures.strings(ref.getJSONArray("readingOrderRaw"))
                            result.put("raw", JSONArray(decoded.raw)).put("expectedRaw", JSONArray(expectedText)).put("textMatches", textMatches)
                                .put("argmaxSha256", decoded.argmaxSha256).put("expectedArgmaxSha256", ProbeInputs.sha(argmax)).put("argmaxMatches", argmaxMatches)
                                .put("bindings", bindingRows).put("readingOrderMatches", readingMatches).put("shape", JSONArray(decoded.shape))
                                .put("status", "completed").put("passed", textMatches && argmaxMatches && identities && readingMatches)
                        } catch (e: Exception) { result.put("status", "failed"); error("${model.id}/${p.id}", e) }
                        finally { checkpoint() }
                    }
                }
                checkpoint() // Session/options have now closed on this same thread.
            }
            fun guard(id: String, block: () -> Boolean) {
                check(id == GUARD_IDS[guards.length()])
                val row = JSONObject().put("id", id).put("status", "started").put("passed", false); guards.put(row); checkpoint()
                try { row.put("passed", block() && cleanup.balanced && ortBalanced()).put("status", "completed") }
                catch (e: Exception) { row.put("status", "failed"); error(id, e) }
                finally { checkpoint() }
            }
            val ns = CropRecognitionFixtures.NAMESPACE
            val manifestRaw = assets.open("$ns/manifest.json").use { ProbeInputs.bounded(it, CropRecognitionFixtures.MANIFEST_CAP) }
            val name = "en-quality-16.f32z"; val payload = fixture.asset(name); val meta = fixture.metadata(name)
            fun replace(path: String, raw: ByteArray): (String) -> InputStream = { if (it == "$ns/$path") raw.inputStream() else assets.open(it) }
            fun mutation(change: (JSONObject) -> Unit): Boolean = rejected { val m = JSONObject(String(manifestRaw)); change(m); CropRecognitionFixtures.parse(m.toString().toByteArray()) }
            guard("missing-asset") { rejected { reader(list = { assets.list(it)!!.drop(1) }).manifest() } }
            guard("extra-asset") { rejected { reader(list = { assets.list(it)!!.toList() + "extra" }).manifest() } }
            guard("corrupt-manifest") { rejected { reader(open = replace("manifest.json", manifestRaw.copyOf().also { it[0] = 0 })).manifest() } }
            guard("corrupt-payload") { rejected { reader(open = replace(name, payload.copyOf().also { it[it.lastIndex] = (it.last().toInt() xor 1).toByte() })).manifest() } }
            guard("truncated-payload") { rejected { reader(open = replace(name, payload.copyOf(payload.size - 1))).manifest() } }
            guard("decoded-size-cap") { rejected { CropRecognitionFixtures.decode(payload, meta.copy(decodedBytes = CropRecognitionFixtures.RAW_CAP + 1)) } && rejected { CropRecognitionFixtures.decode(payload, meta.copy(decodedBytes = meta.decodedBytes!! - 1)) } }
            guard("decoded-hash") { rejected { CropRecognitionFixtures.decode(payload, meta.copy(decodedSha = "0".repeat(64))) } }
            guard("reauthentication") {
                var changed = false; val f = reader(open = { if (changed && it == "$ns/$name") payload.copyOf(1).inputStream() else assets.open(it) })
                f.manifest(); changed = true; rejected { f.decoded(name) }
            }
            guard("duplicate-id-mapping") {
                mutation { it.getJSONArray("cases").getJSONObject(1).put("id", "en-quality-16") } &&
                    mutation { it.getJSONArray("cases").getJSONObject(2).getJSONArray("tensorRowToReadingOrder").let { a -> a.put(1, a.getInt(0)) } }
            }
            guard("strict-schema-path-types") {
                mutation { it.put("schema", "1") } && mutation { it.put("extra", 1) } &&
                    mutation { it.getJSONArray("cases").getJSONObject(0).put("tensor", "../x.f32z") } && rejected { fixture.asset("../manifest.json") }
            }
            fun noWork(id: String, limits: BoxPipelineContract.Limits, incomplete: Boolean): Boolean {
                val c = parents.single { it.id == id }; val r = pipeline.resizeCalls; val p = pipeline.packCalls; val o = ortCleanup.opened
                val actual = pipeline.run(id, c.shape.toLongArray(), parent.probability(c), c.sourceWidth, c.sourceHeight,
                    { error("SOURCE_MUST_NOT_LOAD") }, limits)
                return (if (incomplete) actual.boxes.status != BoxPipelineContract.Status.COMPLETE else actual.boxes.status == BoxPipelineContract.Status.COMPLETE) &&
                    actual.prepared == null && actual.crops.isEmpty() && actual.resized.isEmpty() && actual.values == null && pipeline.resizeCalls == r && pipeline.packCalls == p && ortCleanup.opened == o
            }
            guard("empty-no-inference") { noWork("blank-negative-24", BoxPipelineContract.Limits(), false) }
            guard("incomplete-no-inference") { noWork("two-blocks", BoxPipelineContract.Limits(totalVertices = 1), true) }
            guard("invalid-crop-before-native") { val before = cleanup.matOpened; val crop = checkNotNull(guardCrop); rejected { pipeline.resize(crop.copy(bgr = byteArrayOf()), 48) } && before == cleanup.matOpened }
            guard("resize-failure-cleanup") {
                val before = cleanup.matOpened; val rejected = rejected { pipeline.resize(checkNotNull(guardCrop), 48) { throw IllegalStateException("INJECTED_RESIZE_AFTER_REAL_ALLOCATION") } }
                rejected && cleanup.matOpened - before == 2 && cleanup.balanced
            }
            guard("model-callback-failure-cleanup") {
                val before = ortCleanup.opened; var entered = false
                val rejected = rejected { engine.withModel(inputs.model(manifest.models.first())) { entered = true; throw IllegalStateException("INJECTED_AFTER_SESSION_CREATION") } }
                entered && rejected && ortCleanup.opened - before == 2 && ortBalanced()
            }
            completed = true
        } catch (e: Exception) { error("run", e) }
        finally {
            try { previousThreads?.let { Core.setNumThreads(it); check(Core.getNumThreads() == it); report.put("restoredOpenCvThreads", it) } }
            catch (e: Exception) { error("restore-threads", e) }
            fun passed(a: JSONArray) = (0 until a.length()).all { a.getJSONObject(it).optBoolean("passed", false) }
            val expectedModelIds = listOf("ch", "latin").flatMap { model -> GeometryFixtureInputs.IDS.filterIndexed { i, _ -> GeometryFixtureInputs.CROP_COUNTS[i] > 0 }.map { "$model/$it" } }
            report.put("expectedModelIds", JSONArray(expectedModelIds))
            val ids = { a: JSONArray -> (0 until a.length()).map { a.getJSONObject(it).getString("id") } }
            val ok = completed && errors.length() == 0 && counts == expectedCounts && ids(cases) == GeometryFixtureInputs.IDS && ids(models) == expectedModelIds && ids(guards) == GUARD_IDS &&
                passed(cases) && passed(models) && passed(guards) && cleanup.balanced && cleanup.matOpened > 0 && ortBalanced() && ortCleanup.opened == 38
            report.put("status", if (ok) "passed" else "failed").put("passed", ok).put("elapsedNanos", SystemClock.elapsedRealtimeNanos() - start)
            checkpoint() // Counts, complete errors and all comparisons persist before the assertion.
        }
        assertTrue("Inspect fresh crop-recognition-probe-report.json", report.getBoolean("passed"))
    }
    companion object {
        val GUARD_IDS = listOf("missing-asset", "extra-asset", "corrupt-manifest", "corrupt-payload", "truncated-payload",
            "decoded-size-cap", "decoded-hash", "reauthentication", "duplicate-id-mapping", "strict-schema-path-types",
            "empty-no-inference", "incomplete-no-inference", "invalid-crop-before-native", "resize-failure-cleanup", "model-callback-failure-cleanup")
    }
}
