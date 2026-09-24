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
import java.io.ByteArrayInputStream
import java.io.File
import java.io.InputStream
import java.util.UUID

/** One new instrumentation group. Synthetic local bytes only; no ORT/device capture/network. */
@RunWith(AndroidJUnit4::class)
internal class BoxPipelineProbeTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val assets = instrumentation.context.assets
    private val cleanup = GeometryCleanup()
    private val guards = JSONArray()
    private fun reader(open: (String) -> InputStream = { assets.open(it) },
        list: (String) -> List<String> = { assets.list(it)?.toList().orEmpty() }) = BoxTraceFixtureInputs(open, list)
    private fun write(file: AtomicFile, report: JSONObject) {
        val output = file.startWrite()
        try { output.write(report.toString(2).toByteArray(Charsets.UTF_8)); file.finishWrite(output) }
        catch (e: Throwable) { file.failWrite(output); throw e }
    }
    private fun rejected(block: () -> Unit): Boolean = try { block(); false } catch (_: Exception) { true }
    private fun guard(id: String, checkpoint: () -> Unit, block: () -> JSONObject) {
        check(id in GUARD_IDS && (0 until guards.length()).none { guards.getJSONObject(it).getString("id") == id })
        val row = JSONObject().put("id", id).put("status", "started").put("passed", false)
        guards.put(row); checkpoint()
        try {
            val metrics = block()
            row.put("metrics", metrics).put("passed", metrics.getBoolean("passed") && cleanup.balanced).put("status", "completed")
        } catch (e: InterruptedException) { Thread.currentThread().interrupt(); throw e }
        catch (e: java.util.concurrent.CancellationException) { throw e }
        catch (e: Exception) { row.put("status", "failed").put("errorClass", e.javaClass.name) }
        finally { row.put("cleanup", cleanup.json()); checkpoint() }
    }
    private fun verdict(pass: Boolean) = JSONObject().put("passed", pass)
    private fun runGuards(f: BoxTraceFixtureInputs, cases: List<BoxTraceCase>, parent: GeometryFixtureInputs,
        parents: List<GeometryCase>, engine: BoxPipelineOpenCv, checkpoint: () -> Unit) {
        val namespace = BoxTraceFixtureInputs.NAMESPACE
        val manifestPath = "$namespace/manifest.json"
        val manifest = assets.open(manifestPath).use { ProbeInputs.bounded(it, BoxTraceFixtureInputs.MANIFEST_CAP) }
        val name = "score-equal-trace.json.gz"
        val physical = "$namespace/${BoxTraceFixtureInputs.physical(name)}"
        val compressed = assets.open(physical).use { ProbeInputs.bounded(it, BoxTraceFixtureInputs.FILE_CAP) }
        val decoded = f.decoded(name)
        fun mutatedOpen(path: String, replacement: ByteArray): (String) -> InputStream =
            { requested -> if (requested == path) ByteArrayInputStream(replacement) else assets.open(requested) }
        fun parserMutation(change: (JSONObject) -> Unit): Boolean {
            val m = JSONObject(String(manifest, Charsets.UTF_8)); change(m)
            return rejected { BoxTraceFixtureInputs.parseManifest(m.toString().toByteArray(Charsets.UTF_8)) }
        }
        guard("asset-missing", checkpoint) { verdict(rejected { reader(list = { assets.list(it)!!.drop(1) }).manifest() }) }
        guard("asset-extra", checkpoint) { verdict(rejected { reader(list = { assets.list(it)!!.toList() + "extra.jsonz" }).manifest() }) }
        guard("asset-duplicate", checkpoint) { verdict(rejected { reader(list = { assets.list(it)!!.toList().let { n -> n + n.first() } }).manifest() }) }
        guard("path-alias", checkpoint) {
            verdict(listOf("../manifest.json", "score-equal-trace.jsonz", "/manifest.json", "score-equal-trace.json.gz/", "unknown-trace.json.gz")
                .all { rejected { f.decoded(it) } })
        }
        guard("manifest-corrupt", checkpoint) {
            val bytes = manifest.copyOf(); bytes[0] = (bytes[0].toInt() xor 1).toByte()
            verdict(rejected { reader(open = mutatedOpen(manifestPath, bytes)).manifest() })
        }
        guard("manifest-truncated", checkpoint) { verdict(rejected { reader(open = mutatedOpen(manifestPath, manifest.copyOf(manifest.size - 1))).manifest() }) }
        guard("manifest-cap", checkpoint) { verdict(rejected { reader(open = mutatedOpen(manifestPath, ByteArray(BoxTraceFixtureInputs.MANIFEST_CAP + 1))).manifest() }) }
        guard("compressed-corrupt", checkpoint) {
            val bytes = compressed.copyOf(); bytes[bytes.lastIndex] = (bytes.last().toInt() xor 1).toByte()
            verdict(rejected { reader(open = mutatedOpen(physical, bytes)).manifest() })
        }
        guard("compressed-truncated", checkpoint) {
            verdict(rejected { reader(open = mutatedOpen(physical, compressed.copyOf(compressed.size - 1))).manifest() })
        }
        guard("reauthentication-on-read", checkpoint) {
            var corrupt = false
            val changing = reader(open = { path ->
                if (corrupt && path == physical) ByteArrayInputStream(compressed.copyOf(compressed.size - 1)) else assets.open(path)
            })
            val parsed = changing.manifest(); corrupt = true
            verdict(rejected { changing.trace(parsed.single { it.id == "score-equal" }) })
        }
        guard("decoded-hash", checkpoint) {
            verdict(rejected { BoxTraceFixtureInputs.decodeFile(compressed, f.metadata(name).copy(decodedSha = "0".repeat(64)), name) })
        }
        guard("decoded-size", checkpoint) {
            verdict(rejected { BoxTraceFixtureInputs.decodeFile(compressed, f.metadata(name).copy(decodedBytes = decoded.size - 1), name) })
        }
        guard("trace-cap", checkpoint) {
            verdict(rejected { BoxTraceFixtureInputs.decodeFile(compressed, f.metadata(name).copy(decodedBytes = BoxTraceFixtureInputs.TRACE_CAP + 1), name) })
        }
        guard("float-cap", checkpoint) {
            val n = "score-equal-probability.f32z"
            val raw = assets.open("$namespace/${BoxTraceFixtureInputs.physical(n)}").use { ProbeInputs.bounded(it, BoxTraceFixtureInputs.FILE_CAP) }
            verdict(rejected { BoxTraceFixtureInputs.decodeFile(raw, f.metadata(n).copy(decodedBytes = BoxTraceFixtureInputs.FLOAT_CAP + 1), n) })
        }
        guard("json-duplicate-key", checkpoint) {
            val text = String(manifest, Charsets.UTF_8).replaceFirst("\"schema\": 1", "\"schema\": 1, \"schema\": 1")
            check(text != String(manifest, Charsets.UTF_8))
            verdict(rejected { BoxTraceFixtureInputs.parseManifest(text.toByteArray(Charsets.UTF_8)) })
        }
        guard("json-wrong-types", checkpoint) {
            val checks = listOf(parserMutation { it.put("schema", "1") }, parserMutation { it.getJSONObject("configuration").put("use_dilation", 1) },
                parserMutation { it.getJSONArray("cases").getJSONObject(0).getJSONArray("shape").put(0, 1.5) })
            verdict(checks.all { it }).put("checks", JSONArray(checks))
        }
        guard("json-key-and-case-set", checkpoint) {
            val checks = listOf(
                parserMutation { it.put("extra", 1) },
                parserMutation { it.remove("schema") },
                parserMutation { it.getJSONArray("cases").getJSONObject(0).put("extra", 1) },
                parserMutation { it.getJSONArray("cases").getJSONObject(0).remove("id") },
                parserMutation { it.getJSONArray("cases").remove(23) },
                parserMutation { val rows = it.getJSONArray("cases"); rows.put(rows.getJSONObject(0)) },
                parserMutation { it.getJSONArray("cases").getJSONObject(0).put("id", "unknown") },
                parserMutation {
                    val rows = it.getJSONArray("cases"); val first = rows.getJSONObject(0)
                    rows.put(0, rows.getJSONObject(1)); rows.put(1, first)
                })
            verdict(checks.all { it }).put("checks", JSONArray(checks))
        }
        guard("json-duplicate-id", checkpoint) {
            verdict(parserMutation { it.getJSONArray("cases").getJSONObject(1).put("id", it.getJSONArray("cases").getJSONObject(0).getString("id")) })
        }
        guard("json-trailing-document", checkpoint) {
            verdict(rejected { BoxTraceFixtureInputs.parseManifest(manifest + "{}".toByteArray()) })
        }
        guard("json-invalid-utf8", checkpoint) {
            verdict(rejected { BoxTraceFixtureInputs.strictJson(byteArrayOf(123, 34, -61, 40, 34, 58, 49, 125), 128) })
        }
        guard("json-array-cap", checkpoint) {
            val raw = ("{\"rows\":[" + List(1001) { "0" }.joinToString(",") + "]}").toByteArray()
            verdict(rejected { BoxTraceFixtureInputs.strictJson(raw, BoxTraceFixtureInputs.TRACE_CAP) })
        }
        guard("json-depth-cap", checkpoint) {
            val raw = ("{\"x\":" + "[".repeat(13) + "0" + "]".repeat(13) + "}").toByteArray()
            verdict(rejected { BoxTraceFixtureInputs.strictJson(raw, 1024) })
        }
        guard("metadata-cap-and-path", checkpoint) {
            verdict(listOf(parserMutation { it.getJSONObject("files").getJSONObject(name).put("bytes", Int.MAX_VALUE) },
                parserMutation { it.getJSONArray("cases").getJSONObject(0).put("trace", "../manifest.json") },
                parserMutation { it.getJSONObject("files").getJSONObject(name).put("decodedBytes", BoxTraceFixtureInputs.TRACE_CAP + 1) }).all { it })
        }
        guard("trace-schema-and-id", checkpoint) {
            val c = cases.single { it.id == "score-equal" }
            val wrong = JSONObject(String(decoded, Charsets.UTF_8)).put("id", "score-below").toString().toByteArray()
            val wrongType = JSONObject(String(decoded, Charsets.UTF_8)).put("contourCount", "1").toString().toByteArray()
            verdict(rejected { BoxTraceFixtureInputs.parseTrace(wrong, c) } && rejected { BoxTraceFixtureInputs.parseTrace(wrongType, c) })
        }
        guard("invalid-probability-before-native", checkpoint) {
            val opened = cleanup.matOpened; val calls = cleanup.findContoursCalls
            val checks = mutableListOf<Boolean>()
            for (bad in listOf(Float.NaN, Float.NEGATIVE_INFINITY, Float.POSITIVE_INFINITY, -.1f, 1.1f))
                checks.add(rejected { engine.run(longArrayOf(1, 1, 1, 1), floatArrayOf(bad), 1, 1) })
            checks.add(rejected { engine.run(longArrayOf(1, 1, 4096, 4096), floatArrayOf(), 1, 1) })
            checks.add(rejected { engine.run(longArrayOf(1, 1, 1, 2), floatArrayOf(0f), 1, 1) })
            checks.add(rejected { engine.run(longArrayOf(2, 1, 1, 1), floatArrayOf(0f), 1, 1) })
            checks.add(rejected { engine.run(longArrayOf(1, 1, 1, 1), floatArrayOf(0f), 4097, 1) })
            verdict(checks.all { it } && cleanup.matOpened == opened && cleanup.findContoursCalls == calls).put("checks", JSONArray(checks))
        }
        fun run(id: String, limits: BoxPipelineContract.Limits = BoxPipelineContract.Limits(), library: PolygonOffsetKernel.Library? = null,
            after: (BoxPipelineOpenCv.Stage) -> Unit = {}): BoxPipelineContract.Result {
            val c = cases.single { it.id == id }
            return engine.run(c.shape.toLongArray(), f.probability(c, parent, parents), c.width, c.height, limits, library, after)
        }
        guard("source-run-budget", checkpoint) {
            val opened = cleanup.matOpened; val calls = cleanup.findContoursCalls
            val r = run("candidate-count-4096")
            verdict(r.status == BoxPipelineContract.Status.INCOMPLETE_CONTOUR_BUDGET && r.sourceRuns == 8320L &&
                r.dilated == null && r.dilatedRuns == null && r.contourCount == null && r.processedCandidates == 0 && r.boxes.isEmpty() &&
                cleanup.matOpened == opened && cleanup.findContoursCalls == calls)
        }
        guard("dilated-run-budget", checkpoint) {
            val values = FloatArray(192 * 192)
            for (y in 0 until 32) for (x in 0 until 64) values[(y * 3) * 192 + x * 3] = 1f
            val opened = cleanup.matOpened; val calls = cleanup.findContoursCalls
            val r = engine.run(longArrayOf(1, 1, 192, 192), values, 192, 192)
            verdict(r.status == BoxPipelineContract.Status.INCOMPLETE_CONTOUR_BUDGET && r.sourceRuns <= 8192 &&
                r.dilatedRuns != null && r.dilatedRuns > 8192 && r.contourCount == null && r.rows.isEmpty() && r.boxes.isEmpty() &&
                cleanup.matOpened > opened && cleanup.findContoursCalls == calls).put("sourceRuns", r.sourceRuns).put("dilatedRuns", r.dilatedRuns)
        }
        guard("candidate-budget", checkpoint) {
            val calls = cleanup.findContoursCalls
            val r = run("candidate-count-1001")
            verdict(r.status == BoxPipelineContract.Status.INCOMPLETE_CANDIDATE_LIMIT && r.sourceRuns == 2098L && r.dilatedRuns == 4068L &&
                r.contourCount == 1001 && r.rows.isEmpty() && r.boxes.isEmpty() && cleanup.findContoursCalls == calls + 1)
        }
        guard("geometry-budget-before-copy", checkpoint) {
            val r = run("two-blocks", BoxPipelineContract.Limits(totalVertices = 7))
            val p = run("two-blocks", BoxPipelineContract.Limits(perContour = 3))
            verdict(listOf(r, p).all { it.status == BoxPipelineContract.Status.INCOMPLETE_GEOMETRY_BUDGET && it.contourCount == 2 && it.rows.isEmpty() && it.boxes.isEmpty() })
        }
        guard("degenerate-minimum-side", checkpoint) {
            val r = run("collinear-height-one")
            val one = engine.run(longArrayOf(1, 1, 1, 1), floatArrayOf(1f), 1, 1)
            verdict(r.status == BoxPipelineContract.Status.COMPLETE && r.rows.single().contour.size == 2 &&
                r.rows.single().minimumSide == 0.0 && r.rows.single().disposition == "minimum-side" && r.rows.single().score == null &&
                one.status == BoxPipelineContract.Status.COMPLETE && one.rows.single().contour.size == 1 && one.rows.single().disposition == "minimum-side")
        }
        guard("threshold-score-dispositions", checkpoint) {
            val equal = run("score-equal"); val below = run("score-below"); val size = run("final-size-three")
            val threshold = engine.run(longArrayOf(1, 1, 1, 2), floatArrayOf(.3f, Math.nextUp(.3f)), 2, 1)
            verdict(listOf(equal, below, size, threshold).all { it.status == BoxPipelineContract.Status.COMPLETE } &&
                equal.rows.single().disposition == "accepted" && below.rows.single().disposition == "box-score" &&
                size.rows.single().disposition == "final-size" && threshold.mask.contentEquals(byteArrayOf(0, 1)))
        }
        fun output(count: Int) = object : PolygonOffsetKernel.Output {
            override val pathCount = count
            override fun vertexCount(path: Int): Int = error("must stop before vertices")
            override fun point(path: Int, vertex: Int): PolygonOffsetKernel.IntPoint = error("must stop before points")
        }
        guard("offset-empty", checkpoint) {
            var called = 0
            val r = run("two-blocks", library = PolygonOffsetKernel.Library { _, _ -> called++; output(0) })
            verdict(called == 1 && r.status == BoxPipelineContract.Status.INCOMPLETE_OFFSET_EMPTY && r.boxes.isEmpty() && r.rows.single().score != null)
        }
        guard("offset-budget", checkpoint) {
            var called = 0
            val r = run("two-blocks", library = PolygonOffsetKernel.Library { _, _ -> called++; output(9) })
            verdict(called == 1 && r.status == BoxPipelineContract.Status.INCOMPLETE_OFFSET_BUDGET && r.boxes.isEmpty())
        }
        guard("offset-library-failure", checkpoint) {
            var called = 0
            val r = run("two-blocks", library = PolygonOffsetKernel.Library { _, _ -> called++; throw IllegalStateException("injected") })
            verdict(called == 1 && r.status == BoxPipelineContract.Status.INCOMPLETE_OFFSET_FAILURE && r.boxes.isEmpty())
        }
        guard("partial-boxes-unusable", checkpoint) {
            var called = 0
            val r = run("two-blocks", library = PolygonOffsetKernel.Library { paths, distance ->
                called++
                if (called == 2) output(0) else {
                    val genuine = PolygonOffsetKernel.offset(paths.map { q -> q.map { PolygonOffsetKernel.Point(it.x.toDouble(), it.y.toDouble()) } }, distance)
                    check(genuine.status == PolygonOffsetKernel.Status.COMPLETE)
                    object : PolygonOffsetKernel.Output {
                        override val pathCount = genuine.paths.size
                        override fun vertexCount(path: Int) = genuine.paths[path].size
                        override fun point(path: Int, vertex: Int) = genuine.paths[path][vertex]
                    }
                }
            })
            verdict(called == 2 && r.status == BoxPipelineContract.Status.INCOMPLETE_OFFSET_EMPTY && r.rows.first().disposition == "accepted" &&
                r.rows.size == 2 && r.boxes.isEmpty())
        }
        for (stage in BoxPipelineOpenCv.Stage.entries) guard("native-failure-${stage.name.lowercase()}", checkpoint) {
            val opened = cleanup.matOpened; var injected = false
            val r = run("two-blocks", after = { if (it == stage) { injected = true; throw IllegalStateException("injected") } })
            verdict(injected && cleanup.matOpened > opened && r.status == BoxPipelineContract.Status.INCOMPLETE_NATIVE_FAILURE && r.boxes.isEmpty())
        }
        for (stage in listOf(GeometryOpenCvProbe.Stage.MATRIX, GeometryOpenCvProbe.Stage.WARP, GeometryOpenCvProbe.Stage.ROTATE))
            guard("crop-failure-${stage.name.lowercase()}", checkpoint) {
                val actual = run("vertical"); check(actual.status == BoxPipelineContract.Status.COMPLETE)
                val c = cases.single { it.id == "vertical" }
                val row = BoxPipelineContract.cropRow(c.id, actual.boxes.single(), 0, c.width, c.height)
                val source = parent.image("vertical-source.png")
                val opened = cleanup.matOpened; var injected = false
                val failed = rejected { GeometryOpenCvProbe(cleanup).crop(source, row) {
                    if (it == stage) { injected = true; throw IllegalStateException("injected") }
                } }
                verdict(failed && injected && cleanup.matOpened > opened)
            }
        guard("bitmap-failure-after-allocation", checkpoint) {
            val namePng = "vertical-source.png"; val opened = cleanup.bitmapOpened
            val failed = rejected { GeometryFixtureInputs.decodePng(parent.asset(namePng), parent.metadata(namePng).copy(bgrSha = "0".repeat(64)), cleanup) }
            verdict(failed && cleanup.bitmapOpened == opened + 1 && cleanup.bitmapOpened == cleanup.bitmapRecycled)
        }
    }
    @Test fun completeSyntheticBoxPipeline() {
        val file = AtomicFile(File(instrumentation.targetContext.filesDir, "box-pipeline-probe-report.json"))
        val caseRows = JSONArray(); val comparison = BoxTraceComparison()
        val report = JSONObject().put("schema", 1).put("runId", UUID.randomUUID().toString()).put("status", "started")
            .put("scope", "Synthetic test-only DB boxes and crops; no inference, OCR, capture, translation or production integration")
            .put("api", Build.VERSION.SDK_INT).put("manufacturer", Build.MANUFACTURER).put("device", Build.MODEL)
            .put("fixtureSha256", BoxTraceFixtureInputs.MANIFEST_SHA).put("parentFixtureSha256", GeometryFixtureInputs.MANIFEST_SHA)
            .put("codeConfiguration", BoxPipelineContract.CONFIG).put("opencvArtifact", "org.opencv:opencv:5.0.0.1")
            .put("expectedCaseIds", JSONArray(BoxTraceFixtureInputs.IDS)).put("expectedGuardIds", JSONArray(GUARD_IDS))
            .put("cases", caseRows).put("guards", guards)
            .put("limitations", "Mat.release buffer completion only; native headers finalizer-owned. Offset budgets do not bound UNION internals or provide hard native timeout. Timings observational.")
        val started = SystemClock.elapsedRealtimeNanos()
        fun checkpoint() { report.put("cleanup", cleanup.json()); write(file, report) }
        checkpoint() // Fresh UUID and report exist BEFORE native initialization or fixture reads.
        var completed = false
        var previousThreads: Int? = null
        try {
            check(OpenCVLoader.initLocal()); report.put("opencvRuntime", Core.VERSION)
            previousThreads = Core.getNumThreads()
            Core.setNumThreads(1); check(Core.getNumThreads() == 1)
            report.put("openCvThreads", Core.getNumThreads()).put("openCvOptimized", Core.useOptimized())
                .put("openCvBuildInfoSha256", ProbeInputs.sha(Core.getBuildInformation().toByteArray()))
            val parent = GeometryFixtureInputs({ assets.open(it) }, { assets.list(it)?.toList().orEmpty() }, cleanup)
            val parents = parent.manifest()
            val fixture = reader(); val cases = fixture.manifest()
            val engine = BoxPipelineOpenCv(cleanup)
            for (c in cases) {
                val entry = JSONObject().put("id", c.id).put("status", "started").put("passed", false)
                caseRows.put(entry); checkpoint()
                val before = cleanup.findContoursCalls; val start = SystemClock.elapsedRealtimeNanos()
                try {
                    val result = engine.run(c.shape.toLongArray(), fixture.probability(c, parent, parents), c.width, c.height)
                    // Golden data first enters here, after the entire actual pipeline has returned.
                    val compared = comparison.trace(c, result, fixture.trace(c))
                    entry.put("trace", compared)
                    var passed = compared.getBoolean("passed")
                    val expectedCalls = if (c.id == "candidate-count-4096") 0 else 1
                    entry.put("findContoursCalls", cleanup.findContoursCalls - before).put("expectedFindContoursCalls", expectedCalls)
                    passed = passed && cleanup.findContoursCalls - before == expectedCalls
                    if (c.parent) {
                        val pc = parents.single { it.id == c.id }; comparison.increment("maskCases")
                        val mask = comparison.bytes(result.mask, parent.mask(pc, false))
                        val dilated = comparison.bytes(result.dilated ?: byteArrayOf(), parent.mask(pc, true))
                        entry.put("mask", mask).put("dilated", dilated)
                        passed = passed && mask.getBoolean("passed") && dilated.getBoolean("passed") && result.contourCount == pc.contours
                        val crops = JSONArray(); entry.put("crops", crops)
                        if (result.status == BoxPipelineContract.Status.COMPLETE && result.boxes.isNotEmpty()) {
                            val source = parent.image("${c.id}-source.png")
                            result.boxes.forEachIndexed { rank, box ->
                                val row = BoxPipelineContract.cropRow(c.id, box, rank, c.width, c.height)
                                // Compute and crop actual geometry BEFORE looking up the expected crop by ID.
                                val crop = GeometryOpenCvProbe(cleanup).crop(source, row)
                                val expected = pc.rows.single { it.id == row.id }
                                val checked = comparison.crop(crop, expected, parent.image(expected.crop))
                                crops.put(checked); passed = passed && checked.getBoolean("passed")
                            }
                        }
                        passed = passed && crops.length() == pc.rows.size
                    }
                    entry.put("status", "completed").put("passed", passed && cleanup.balanced)
                } catch (e: InterruptedException) { Thread.currentThread().interrupt(); throw e }
                catch (e: java.util.concurrent.CancellationException) { throw e }
                catch (e: Exception) { entry.put("status", "failed").put("errorClass", e.javaClass.name) }
                finally { entry.put("elapsedNanos", SystemClock.elapsedRealtimeNanos() - start).put("cleanup", cleanup.json()); checkpoint() }
            }
            runGuards(fixture, cases, parent, parents, engine, ::checkpoint)
            completed = true
        } catch (e: InterruptedException) { Thread.currentThread().interrupt(); report.put("errorClass", e.javaClass.name); throw e }
        catch (e: java.util.concurrent.CancellationException) { report.put("errorClass", e.javaClass.name); throw e }
        catch (e: Exception) { report.put("errorClass", e.javaClass.name) }
        finally {
            previousThreads?.let { Core.setNumThreads(it) }
            val totals = comparison.totals(); report.put("totals", totals)
            val caseIds = List(caseRows.length()) { caseRows.getJSONObject(it).getString("id") }
            val guardIds = List(guards.length()) { guards.getJSONObject(it).getString("id") }
            val casesPassed = caseIds == BoxTraceFixtureInputs.IDS && caseIds.isNotEmpty() &&
                (0 until caseRows.length()).all { caseRows.getJSONObject(it).getBoolean("passed") }
            val guardsPassed = guardIds == GUARD_IDS && guardIds.isNotEmpty() &&
                (0 until guards.length()).all { guards.getJSONObject(it).getBoolean("passed") }
            val passed = completed && casesPassed && guardsPassed && totals.getBoolean("passed") && cleanup.balanced &&
                cleanup.matOpened > 0 && cleanup.bitmapOpened > 0 && cleanup.findContoursCalls > 0
            report.put("status", if (passed) "passed" else "failed").put("passed", passed)
                .put("elapsedNanos", SystemClock.elapsedRealtimeNanos() - started)
            checkpoint() // All errors, non-vacuous counts and cleanup persisted BEFORE assertion.
        }
        assertTrue("Inspect fresh box-pipeline-probe-report.json", report.getBoolean("passed"))
    }
    companion object {
        // Frozen before execution. No dynamic pass list built from the operations that happened to run.
        val GUARD_IDS = listOf("asset-missing", "asset-extra", "asset-duplicate", "path-alias", "manifest-corrupt", "manifest-truncated",
            "manifest-cap", "compressed-corrupt", "compressed-truncated", "reauthentication-on-read", "decoded-hash", "decoded-size", "trace-cap", "float-cap",
            "json-duplicate-key", "json-wrong-types", "json-key-and-case-set", "json-duplicate-id", "json-trailing-document", "json-invalid-utf8", "json-array-cap", "json-depth-cap",
            "metadata-cap-and-path", "trace-schema-and-id", "invalid-probability-before-native", "source-run-budget", "dilated-run-budget", "candidate-budget",
            "geometry-budget-before-copy", "degenerate-minimum-side", "threshold-score-dispositions", "offset-empty", "offset-budget", "offset-library-failure",
            "partial-boxes-unusable", "native-failure-allocated", "native-failure-dilated", "native-failure-contours", "native-failure-min_rect", "native-failure-score",
            "native-failure-offset", "native-failure-expanded_rect", "crop-failure-matrix", "crop-failure-warp", "crop-failure-rotate", "bitmap-failure-after-allocation")
    }
}
