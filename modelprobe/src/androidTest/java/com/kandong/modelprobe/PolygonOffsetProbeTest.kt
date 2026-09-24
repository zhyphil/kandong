package com.kandong.modelprobe

import android.os.Build
import android.os.SystemClock
import android.util.AtomicFile
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.kandong.modelprobe.PolygonOffsetKernel.IntPoint
import com.kandong.modelprobe.PolygonOffsetKernel.Point
import com.kandong.modelprobe.PolygonOffsetKernel.Status
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.ByteArrayInputStream
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.util.UUID
import kotlin.math.abs

/** One test, synthetic integer-offset kernels only. No full DB boxes or OCR.
 * The 13 final quads are NOT actual pre-unclip detection boxes. */
@RunWith(AndroidJUnit4::class)
internal class PolygonOffsetProbeTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val assets = instrumentation.context.assets
    private val caseRows = JSONArray()
    private val guardRows = JSONArray()
    private fun reader(open: (String) -> InputStream = { assets.open(it) },
        list: (String) -> List<String> = { assets.list(it)?.toList().orEmpty() }) = PolygonOffsetFixtureInputs(open, list)
    private fun write(file: AtomicFile, report: JSONObject) {
        val stream = file.startWrite()
        try { stream.write(report.toString(2).toByteArray(Charsets.UTF_8)); file.finishWrite(stream) }
        catch (e: Throwable) { file.failWrite(stream); throw e }
    }
    private fun comparison(c: PolygonOffsetKernel.Comparison) = JSONObject()
        .put("comparedVertices", c.comparedVertices).put("changedVertices", c.changedVertices)
        .put("unmatchedVertices", c.unmatchedVertices).put("pathCountDifference", c.pathCountDifference)
        .put("expectedVertices", c.expectedVertices).put("actualVertices", c.actualVertices)
        .put("expectedCanonicalSha256", c.expectedHash).put("actualCanonicalSha256", c.actualHash).put("passed", c.passed)
    private class Totals {
        var attempted = 0; var comparedCases = 0; var comparedVertices = 0
        var changedVertices = 0; var unmatchedVertices = 0; var pathDifferences = 0
        var expectedVertices = 0; var actualVertices = 0; var failures = 0
        fun add(c: PolygonOffsetKernel.Comparison) {
            comparedCases++; comparedVertices += c.comparedVertices
            changedVertices += c.changedVertices; unmatchedVertices += c.unmatchedVertices
            pathDifferences += abs(c.pathCountDifference)
            expectedVertices += c.expectedVertices; actualVertices += c.actualVertices
            if (!c.passed) failures++
        }
        fun passed(count: Int) = attempted == count && comparedCases == count && comparedVertices > 0 &&
            changedVertices == 0 && unmatchedVertices == 0 && pathDifferences == 0 && failures == 0 && expectedVertices == actualVertices
        fun json() = JSONObject().put("attempted", attempted).put("comparedCases", comparedCases)
            .put("comparedVertices", comparedVertices).put("changedVertices", changedVertices)
            .put("unmatchedVertices", unmatchedVertices).put("absolutePathCountDifferences", pathDifferences)
            .put("expectedVertices", expectedVertices).put("actualVertices", actualVertices).put("failures", failures)
    }
    private fun runKernel(c: PolygonOffsetCase, distance: Double, totals: Totals): JSONObject {
        totals.attempted++
        val start = SystemClock.elapsedRealtimeNanos()
        return try {
            val result = PolygonOffsetKernel.offset(c.paths, distance)
            val row = JSONObject().put("status", result.status.name).put("generatedVertexBound", result.generatedVertexBound)
            if (result.status == Status.COMPLETE) {
                val metrics = PolygonOffsetKernel.compare(result.paths, c.expected)
                totals.add(metrics); row.put("comparison", comparison(metrics)).put("passed", metrics.passed)
            } else {
                totals.failures++; row.put("reason", result.reason).put("passed", false)
            }
            row.put("elapsedNanosObservational", SystemClock.elapsedRealtimeNanos() - start)
        } catch (e: Exception) {
            totals.failures++
            JSONObject().put("passed", false).put("errorClass", e.javaClass.name)
        }
    }

    @Test fun frozenPolygonOffsetsAndGuards() {
        val target = AtomicFile(File(instrumentation.targetContext.filesDir, "polygon-offset-probe-report.json"))
        val report = JSONObject().put("schema", 1).put("runId", UUID.randomUUID().toString())
            .put("scope", "Synthetic positive ROUND/CLOSED_POLYGON offset kernels; no full DB boxes or OCR")
            .put("finalQuadLimitation", "13 frozen final quads are NOT actual pre-unclip detection boxes")
            .put("allocationLimitation", "Preflight bounds offset-generated vertices, not all UNION allocations; no hard interruption")
            .put("status", "started").put("sdk", Build.VERSION.SDK_INT).put("manufacturer", Build.MANUFACTURER).put("model", Build.MODEL)
            .put("fixtureManifestSha256", PolygonOffsetFixtureInputs.MANIFEST_SHA).put("fixtureCasesSha256", PolygonOffsetFixtureInputs.CASES_SHA)
            .put("vendorCommit", PolygonOffsetFixtureInputs.COMMIT).put("vendorPatchSha256", PolygonOffsetFixtureInputs.PATCH_SHA)
            .put("expectedLegalFingerprints", JSONObject(PolygonOffsetFixtureInputs.LEGAL_IDENTITIES.mapValues { it.value.second }))
            .put("expectedCaseCount", 689).put("expectedSingleQuadCount", 683).put("expectedGuardIds", JSONArray(GUARD_IDS))
            .put("expectedGuardCount", GUARD_IDS.size).put("expectedCategories", JSONObject(PolygonOffsetFixtureInputs.CATEGORIES))
            .put("cases", caseRows).put("guards", guardRows)
        // Fresh run identity exists on disk even if fixture loading fails.
        write(target, report)
        val start = SystemClock.elapsedRealtimeNanos()
        val frozen = Totals(); val computed = Totals()
        var distanceCompared = 0; var distanceFailures = 0; var maxDistanceError = 0.0
        var loadedIds: List<String> = emptyList()
        fun metrics() {
            report.put("frozenKernel", frozen.json()).put("computedKernel", computed.json())
                .put("distance", JSONObject().put("compared", distanceCompared).put("failures", distanceFailures)
                    .put("maxAbsError", maxDistanceError).put("absoluteTolerance", PolygonOffsetKernel.DISTANCE_ATOL)
                    .put("relativeTolerance", PolygonOffsetKernel.DISTANCE_RTOL))
                .put("completedCaseRows", caseRows.length()).put("completedGuardRows", guardRows.length())
        }
        var setupSucceeded = false
        try {
            val fixtures = reader()
            report.put("verifiedLegalFingerprints", JSONObject(fixtures.verifyLegal()))
            // Authenticated provenance contains all 11 upstream and patched hashes; never source/page text.
            val legal = PolygonOffsetFixtureInputs.LEGAL_IDENTITIES.getValue("provenance.json")
            val provenanceBytes = assets.open("polygon-offset-legal/provenance.json").use {
                PolygonOffsetFixtureInputs.authenticate(it, legal.first, legal.second, PolygonOffsetFixtureInputs.MANIFEST_CAP)
            }
            val provenance = JSONObject(String(provenanceBytes, Charsets.UTF_8))
            val fingerprints = JSONObject()
            val sourceRows = provenance.getJSONArray("sources")
            require(sourceRows.length() == 11)
            for (i in 0 until sourceRows.length()) {
                val source = sourceRows.getJSONObject(i)
                fingerprints.put(source.getString("path").substringAfterLast('/'), JSONObject()
                    .put("upstreamSha256", source.getString("upstreamSha256"))
                    .put("patchedSha256", source.getString("patchedSha256")))
            }
            report.put("vendorSourceFingerprints", fingerprints)
            val cases = fixtures.load(); loadedIds = cases.map { it.id }; setupSucceeded = true
            report.put("actualCategories", JSONObject(cases.groupingBy { it.category }.eachCount()))
            cases.forEachIndexed { index, c ->
                val row = JSONObject().put("id", c.id).put("category", c.category)
                caseRows.put(row)
                row.put("frozen", runKernel(c, c.distance, frozen))
                if (index < 683) {
                    try {
                        val distance = PolygonOffsetKernel.computedDistance(c.paths)
                        val error = abs(distance - c.distance)
                        val distancePassed = error <= PolygonOffsetKernel.DISTANCE_ATOL + PolygonOffsetKernel.DISTANCE_RTOL * abs(c.distance)
                        distanceCompared++; maxDistanceError = maxOf(maxDistanceError, error)
                        if (!distancePassed) distanceFailures++
                        row.put("distance", JSONObject().put("actual", distance).put("reference", c.distance)
                            .put("absError", error).put("passed", distancePassed))
                        // Always compare the computed kernel, even when distance tolerance already failed.
                        row.put("computed", runKernel(c, distance, computed))
                    } catch (e: Exception) {
                        distanceFailures++
                        row.put("distance", JSONObject().put("passed", false).put("errorClass", e.javaClass.name))
                    }
                } else row.put("computed", JSONObject().put("status", "not-applicable-general-multipath"))
                if ((index + 1) % 32 == 0) { metrics(); write(target, report) }
            }
            runGuards { metrics(); write(target, report) }
        } catch (e: Exception) {
            report.put("errorClass", e.javaClass.name)
        }
        val completedIds = (0 until caseRows.length()).map { caseRows.getJSONObject(it).getString("id") }
        val completedGuards = (0 until guardRows.length()).map { guardRows.getJSONObject(it).getString("id") }
        val guardsPassed = completedGuards == GUARD_IDS && guardRows.length() == GUARD_IDS.size &&
            (0 until guardRows.length()).count { guardRows.getJSONObject(it).optBoolean("passed", false) } == GUARD_IDS.size
        val casesComplete = setupSucceeded && loadedIds == PolygonOffsetFixtureInputs.IDS && completedIds == loadedIds && completedIds.size == 689
        val passed = casesComplete && frozen.passed(689) && computed.passed(683) &&
            distanceCompared == 683 && distanceFailures == 0 && guardsPassed
        metrics()
        report.put("casesComplete", casesComplete).put("guardsCompleteAndPassed", guardsPassed)
            .put("elapsedNanosObservational", SystemClock.elapsedRealtimeNanos() - start)
            .put("status", "completed").put("passed", passed)
        // All metrics are durable BEFORE the assertion (including failures and incomplete counts).
        write(target, report)
        assertTrue("Polygon probe failed; inspect target filesDir/polygon-offset-probe-report.json", passed)
    }

    private fun rejected(block: () -> Unit): Boolean = try { block(); false }
        catch (_: IllegalArgumentException) { true }
        catch (_: IllegalStateException) { true }
        catch (_: IOException) { true }
    private fun guard(id: String, block: () -> Boolean) {
        require(guardRows.length() < GUARD_IDS.size && id == GUARD_IDS[guardRows.length()]) { "GUARD_ID_ORDER" }
        val row = JSONObject().put("id", id).put("passed", false)
        guardRows.put(row)
        try { row.put("passed", block()) }
        catch (e: Exception) { row.put("errorClass", e.javaClass.name) }
    }
    private fun fixtureBytes(name: String): ByteArray {
        val identity = PolygonOffsetFixtureInputs.IDENTITIES.getValue(name)
        return assets.open("polygon-offset-v1/$name").use {
            PolygonOffsetFixtureInputs.authenticate(it, identity.first, identity.second,
                if (name == "manifest.json") PolygonOffsetFixtureInputs.MANIFEST_CAP else PolygonOffsetFixtureInputs.CASES_CAP)
        }
    }
    private fun rectangle(x: Double = 0.0, y: Double = 0.0, w: Double = 10.0, h: Double = 10.0) =
        listOf(Point(x, y), Point(x + w, y), Point(x + w, y + h), Point(x, y + h))
    private fun output(paths: List<List<IntPoint>>) = object : PolygonOffsetKernel.Output {
        override val pathCount get() = paths.size
        override fun vertexCount(path: Int) = paths[path].size
        override fun point(path: Int, vertex: Int) = paths[path][vertex]
    }
    private fun runGuards(checkpoint: () -> Unit) {
        val manifest = fixtureBytes("manifest.json"); val cases = fixtureBytes("cases.json")
        val manifestText = String(manifest, Charsets.UTF_8); val casesText = String(cases, Charsets.UTF_8)
        for (name in listOf("manifest.json", "cases.json")) for (kind in listOf("corrupt", "truncated")) {
            guard("asset-$kind-$name") {
                val altered = reader(open = { path ->
                    if (path == "polygon-offset-v1/$name") {
                        val raw = if (name == "manifest.json") manifest else cases
                        ByteArrayInputStream(if (kind == "truncated") raw.copyOf(raw.size - 1)
                            else raw.copyOf().also { it[0] = (it[0].toInt() xor 1).toByte() })
                    } else assets.open(path)
                })
                rejected { altered.load() }
            }
        }
        guard("asset-wrong-hash") { rejected {
            PolygonOffsetFixtureInputs.authenticate(ByteArrayInputStream(cases), cases.size, "0".repeat(64), PolygonOffsetFixtureInputs.CASES_CAP)
        } }
        guard("asset-extra") { rejected { reader(list = { listOf("manifest.json", "cases.json", "extra.json") }).load() } }
        guard("asset-missing") { rejected { reader(list = { listOf("manifest.json") }).load() } }
        guard("asset-duplicate-name") { rejected { reader(list = { listOf("manifest.json", "cases.json", "cases.json") }).load() } }
        guard("asset-consumption-recheck") {
            var changed = false
            val fixture = reader(open = { path ->
                if (changed && path == "polygon-offset-v1/cases.json") ByteArrayInputStream(cases.copyOf(cases.size - 1)) else assets.open(path)
            })
            val first = fixture.load(); changed = true
            first.size == 689 && rejected { fixture.load() }
        }
        guard("asset-byte-caps") {
            val rejectManifest = rejected { PolygonOffsetFixtureInputs.authenticate(ByteArrayInputStream(manifest),
                PolygonOffsetFixtureInputs.MANIFEST_CAP + 1, PolygonOffsetFixtureInputs.MANIFEST_SHA, PolygonOffsetFixtureInputs.MANIFEST_CAP) }
            val rejectCases = rejected { PolygonOffsetFixtureInputs.authenticate(ByteArrayInputStream(cases),
                PolygonOffsetFixtureInputs.CASES_CAP + 1, PolygonOffsetFixtureInputs.CASES_SHA, PolygonOffsetFixtureInputs.CASES_CAP) }
            val extraByte = rejected { PolygonOffsetFixtureInputs.authenticate(ByteArrayInputStream(manifest + byteArrayOf(0)),
                manifest.size, PolygonOffsetFixtureInputs.MANIFEST_SHA, PolygonOffsetFixtureInputs.MANIFEST_CAP) }
            rejectManifest && rejectCases && extraByte
        }
        fun manifestGuard(id: String, value: String) = guard(id) {
            rejected { PolygonOffsetFixtureInputs.parseManifest(value.toByteArray(Charsets.UTF_8)) }
        }
        manifestGuard("manifest-duplicate-key", manifestText.replaceFirst("\"schema\": 1", "\"schema\": 1, \"schema\": 1"))
        manifestGuard("manifest-extra-key", manifestText.replaceFirst("{", "{\"extra\":1,"))
        manifestGuard("manifest-missing-key", manifestText.replaceFirst("\"schema\": 1,", ""))
        manifestGuard("manifest-wrong-schema", manifestText.replaceFirst("\"schema\": 1", "\"schema\": 2"))
        manifestGuard("manifest-wrong-type", manifestText.replaceFirst("\"schema\": 1", "\"schema\": \"1\""))
        manifestGuard("manifest-duplicate-nested-key", manifestText.replaceFirst("\"join\": \"ROUND\"", "\"join\": \"ROUND\", \"join\": \"ROUND\""))
        manifestGuard("manifest-wrong-config", manifestText.replaceFirst("\"ROUND\"", "\"MITER\""))
        manifestGuard("manifest-wrong-category-count", manifestText.replaceFirst("\"frozen-micro\": 2", "\"frozen-micro\": 0"))
        manifestGuard("manifest-trailing-data", manifestText + " {}")
        manifestGuard("manifest-truncated-json", manifestText.dropLast(3))
        guard("manifest-parser-byte-cap") { rejected { PolygonOffsetFixtureInputs.parseManifest(ByteArray(PolygonOffsetFixtureInputs.MANIFEST_CAP + 1) { 32 }) } }
        fun casesGuard(id: String, value: String) = guard(id) {
            rejected { PolygonOffsetFixtureInputs.parseCases(value.toByteArray(Charsets.UTF_8)) }
        }
        // JSONObject/JSONArray below manufacture malformed guard inputs only, never load real fixtures.
        fun alterFirst(block: (JSONObject) -> Unit): String {
            val array = JSONArray(casesText); block(array.getJSONObject(0)); return array.toString()
        }
        casesGuard("cases-duplicate-key", casesText.replaceFirst("\"id\": \"axis-aligned\"", "\"id\": \"axis-aligned\", \"id\": \"axis-aligned\""))
        casesGuard("cases-extra-key", alterFirst { it.put("extra", 1) })
        casesGuard("cases-missing-key", alterFirst { it.remove("distance") })
        casesGuard("cases-duplicate-id", casesText.replaceFirst("\"id\": \"fractional-rotated\"", "\"id\": \"axis-aligned\""))
        casesGuard("cases-extra-id", JSONArray(casesText).also { it.put(it.getJSONObject(0)) }.toString())
        casesGuard("cases-missing-id", JSONArray(casesText).also { it.remove(688) }.toString())
        casesGuard("cases-unknown-id", alterFirst { it.put("id", "unknown") })
        casesGuard("cases-reordered-ids", JSONArray(casesText).also {
            val first = it.getJSONObject(0); val second = it.getJSONObject(1); it.put(0, second); it.put(1, first)
        }.toString())
        casesGuard("cases-wrong-category", alterFirst { it.put("category", "general-multipath-offset") })
        casesGuard("cases-number-as-string", alterFirst { it.put("distance", "6.0") })
        casesGuard("cases-null-type", alterFirst { it.put("paths", JSONObject.NULL) })
        casesGuard("cases-noninteger-expected", alterFirst { it.getJSONArray("expected").getJSONArray(0).getJSONArray(0).put(0, 43.5) })
        casesGuard("cases-empty", "[]")
        casesGuard("cases-trailing-data", casesText + " []")
        casesGuard("cases-truncated-json", casesText.dropLast(3))
        casesGuard("cases-nonfinite", casesText.replaceFirst("\"distance\": 6.0", "\"distance\": 1e999"))
        casesGuard("cases-coordinate-bound", alterFirst { it.getJSONArray("paths").getJSONArray(0).getJSONArray(0).put(0, 8193) })
        casesGuard("cases-path-count", alterFirst { val paths = it.getJSONArray("paths"); paths.put(paths.getJSONArray(0)); paths.put(paths.getJSONArray(0)) })
        casesGuard("cases-nonquad", alterFirst { val q = it.getJSONArray("paths").getJSONArray(0); q.put(q.getJSONArray(0)) })
        casesGuard("cases-empty-expected", alterFirst { it.put("expected", JSONArray()) })
        casesGuard("cases-expected-path-cap", alterFirst { val p = it.getJSONArray("expected"); repeat(8) { _ -> p.put(p.getJSONArray(0)) } })
        casesGuard("cases-expected-vertex-cap", alterFirst {
            val p = it.getJSONArray("expected").getJSONArray(0); while (p.length() <= 512) p.put(JSONArray().put(0).put(0))
        })
        guard("cases-parser-byte-cap") { rejected { PolygonOffsetFixtureInputs.parseCases(ByteArray(PolygonOffsetFixtureInputs.CASES_CAP + 1) { 32 }) } }
        checkpoint()
        val q = rectangle()
        val invalid = listOf(
            "kernel-empty-paths" to emptyList(), "kernel-path-cap" to listOf(q, q, q),
            "kernel-nonquad" to listOf(q.take(3)), "kernel-crossed" to listOf(listOf(q[0], q[2], q[1], q[3])),
            "kernel-repeated" to listOf(listOf(q[0], q[1], q[1], q[3])),
            "kernel-collinear" to listOf(listOf(Point(0.0, 0.0), Point(1.0, 0.0), Point(2.0, 0.0), Point(0.0, 2.0))),
            "kernel-concave" to listOf(listOf(Point(0.0, 0.0), Point(4.0, 0.0), Point(1.0, 1.0), Point(0.0, 4.0))),
            "kernel-nan" to listOf(rectangle(w = Double.NaN)), "kernel-infinity" to listOf(rectangle(w = Double.POSITIVE_INFINITY)),
            "kernel-coordinate-bound" to listOf(rectangle(x = 8192.0)),
            "kernel-post-float-repeat" to listOf(rectangle(x = 8191.0, w = 0.00001)),
            "kernel-post-integer-repeat" to listOf(rectangle(w = 0.9)),
            "kernel-post-integer-collinear" to listOf(listOf(Point(0.0, 0.0), Point(1.0, -0.1), Point(2.0, 0.0), Point(2.0, 2.0))))
        val triangle = listOf(IntPoint(0, 0), IntPoint(1, 0), IntPoint(0, 1))
        for ((id, input) in invalid) guard(id) {
            var called = false
            val r = PolygonOffsetKernel.offset(input, 1.0, PolygonOffsetKernel.Library { _, _ -> called = true; output(listOf(triangle)) })
            r.status == Status.INVALID_INPUT && !called && r.paths.isEmpty()
        }
        guard("kernel-distance") {
            var calls = 0
            val spy = PolygonOffsetKernel.Library { _, _ -> calls++; output(listOf(triangle)) }
            val distances = listOf(0.0, -1.0, Double.NaN, Double.POSITIVE_INFINITY, Double.NEGATIVE_INFINITY, Math.nextUp(128.0))
            val failures = distances.count { PolygonOffsetKernel.offset(listOf(q), it, spy).status == Status.INVALID_INPUT }
            failures == 6 && calls == 0
        }
        guard("kernel-preflight-budget") {
            var called = false
            val r = PolygonOffsetKernel.offset(listOf(q), 128.0, PolygonOffsetKernel.Library { _, _ -> called = true; output(listOf(triangle)) }, 7)
            r.status == Status.BUDGET_EXCEEDED && r.generatedVertexBound > 7 && !called
        }
        guard("kernel-near-zero-branch") {
            val r = PolygonOffsetKernel.offset(listOf(q), Double.MIN_VALUE)
            r.status == Status.COMPLETE && r.generatedVertexBound == 4 && r.paths.single().size == 4
        }
        guard("kernel-library-failure") {
            var called = false
            val r = PolygonOffsetKernel.offset(listOf(q), 1.0, PolygonOffsetKernel.Library { _, _ -> called = true; throw IllegalStateException("injected") })
            called && r.status == Status.LIBRARY_ERROR && r.paths.isEmpty()
        }
        guard("kernel-empty-output") {
            var called = false
            val r = PolygonOffsetKernel.offset(listOf(q), 1.0, PolygonOffsetKernel.Library { _, _ -> called = true; output(emptyList()) })
            called && r.status == Status.EMPTY_OUTPUT && r.paths.isEmpty()
        }
        guard("kernel-output-budget-before-copy") {
            var reads = 0
            fun sized(paths: Int, vertices: Int) = object : PolygonOffsetKernel.Output {
                override val pathCount = paths
                override fun vertexCount(path: Int) = vertices
                override fun point(path: Int, vertex: Int): IntPoint { reads++; return IntPoint(0, 0) }
            }
            val results = listOf(9 to 3, 1 to 513, 2 to 257).map { (paths, vertices) ->
                PolygonOffsetKernel.offset(listOf(q), 1.0, PolygonOffsetKernel.Library { _, _ -> sized(paths, vertices) })
            }
            results.count { it.status == Status.BUDGET_EXCEEDED && it.paths.isEmpty() } == 3 && reads == 0
        }
        guard("kernel-comparison-no-loss") {
            val full = listOf(IntPoint(0, 0), IntPoint(1, 0), IntPoint(2, 0), IntPoint(2, 2), IntPoint(0, 2))
            val same = PolygonOffsetKernel.compare(listOf(full.reversed(), full.drop(2) + full.take(2)), listOf(full, full))
            val removed = PolygonOffsetKernel.compare(listOf(full.filterIndexed { i, _ -> i != 1 }, full), listOf(full, full))
            val fewer = PolygonOffsetKernel.compare(listOf(full), listOf(full, full))
            same.passed && same.comparedVertices == 10 && !removed.passed && removed.unmatchedVertices > 0 && !fewer.passed
        }
        guard("kernel-deep-immutable") {
            val source = mutableListOf(triangle.toMutableList())
            val result = PolygonOffsetKernel.offset(listOf(q), 1.0, PolygonOffsetKernel.Library { _, _ -> output(source) })
            source[0][0] = IntPoint(99, 99); source.clear()
            val outer = try { (result.paths as MutableList<List<IntPoint>>).clear(); false } catch (_: UnsupportedOperationException) { true }
            val inner = try { (result.paths[0] as MutableList<IntPoint>).clear(); false } catch (_: UnsupportedOperationException) { true }
            result.status == Status.COMPLETE && result.paths == listOf(triangle) && outer && inner
        }
        checkpoint()
    }

    companion object {
        private val GUARD_IDS = listOf(
            "asset-corrupt-manifest.json", "asset-truncated-manifest.json", "asset-corrupt-cases.json", "asset-truncated-cases.json",
            "asset-wrong-hash", "asset-extra", "asset-missing", "asset-duplicate-name", "asset-consumption-recheck", "asset-byte-caps",
            "manifest-duplicate-key", "manifest-extra-key", "manifest-missing-key", "manifest-wrong-schema", "manifest-wrong-type",
            "manifest-duplicate-nested-key", "manifest-wrong-config", "manifest-wrong-category-count", "manifest-trailing-data",
            "manifest-truncated-json", "manifest-parser-byte-cap", "cases-duplicate-key", "cases-extra-key", "cases-missing-key",
            "cases-duplicate-id", "cases-extra-id", "cases-missing-id", "cases-unknown-id", "cases-reordered-ids", "cases-wrong-category",
            "cases-number-as-string", "cases-null-type", "cases-noninteger-expected", "cases-empty", "cases-trailing-data",
            "cases-truncated-json", "cases-nonfinite", "cases-coordinate-bound", "cases-path-count", "cases-nonquad",
            "cases-empty-expected", "cases-expected-path-cap", "cases-expected-vertex-cap", "cases-parser-byte-cap",
            "kernel-empty-paths", "kernel-path-cap", "kernel-nonquad", "kernel-crossed", "kernel-repeated", "kernel-collinear",
            "kernel-concave", "kernel-nan", "kernel-infinity", "kernel-coordinate-bound", "kernel-post-float-repeat",
            "kernel-post-integer-repeat", "kernel-post-integer-collinear", "kernel-distance", "kernel-preflight-budget",
            "kernel-near-zero-branch", "kernel-library-failure", "kernel-empty-output", "kernel-output-budget-before-copy",
            "kernel-comparison-no-loss", "kernel-deep-immutable")
    }
}
