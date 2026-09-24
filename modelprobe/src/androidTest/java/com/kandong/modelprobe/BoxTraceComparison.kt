package com.kandong.modelprobe

import org.json.JSONArray
import org.json.JSONObject
import kotlin.math.abs

/** Comparisons run only AFTER the probability-only pipeline returns. Never repairs results. */
internal class BoxTraceComparison(private val scoreAtol: Double = EndToEndScoreBudget.SAME_INPUT_ATOL) {
    init {
        require(scoreAtol.isFinite() && scoreAtol >= EndToEndScoreBudget.SAME_INPUT_ATOL &&
            scoreAtol <= EndToEndScoreBudget.SAME_INPUT_ATOL + DetectorComparison.ATOL + DetectorComparison.RTOL)
    }
    val counts = linkedMapOf("completeCases" to 0, "budgetCases" to 0, "rows" to 0, "contourVertices" to 0,
        "scores" to 0, "distances" to 0, "expandedPaths" to 0, "expandedVertices" to 0,
        "postQuads" to 0, "rawBoxes" to 0, "finalBoxes" to 0, "maskCases" to 0, "crops" to 0, "cropChannels" to 0)
    val dispositions = linkedMapOf<String, Int>()
    fun increment(key: String, n: Int = 1) { counts[key] = counts.getValue(key) + n }
    private fun points(a: JSONArray, integers: Boolean = false) = BoxTraceFixtureInputs.points(a, 8192, integers)
    private fun ints(a: JSONArray) = points(a, true).map { PolygonOffsetKernel.IntPoint(it.x.toLong(), it.y.toLong()) }
    private fun pjson(q: List<GeometryProbeContract.Point>) = JSONArray(q.map { listOf(it.x, it.y) })
    private fun ijson(q: List<PolygonOffsetKernel.IntPoint>) = JSONArray(q.map { listOf(it.x, it.y) })
    private fun rowJson(r: BoxPipelineContract.Row) = JSONObject().put("contourIndex", r.contourIndex)
        .put("contour", ijson(r.contour)).put("preUnclipQuad", pjson(r.preQuad)).put("minimumSide", r.minimumSide)
        .put("disposition", r.disposition).apply {
            r.score?.let { put("score", it) }; r.distance?.let { put("distance", it) }
            r.integerInput?.let { put("integerInput", ijson(it)) }
            r.expanded?.let { put("expandedPaths", JSONArray(it.map { q -> ijson(q) })) }
            r.postQuad?.let { put("expandedQuad", pjson(it)) }; r.expandedMinimumSide?.let { put("expandedMinimumSide", it) }
            r.rawBox?.let { put("rawBox", pjson(it)) }; r.rawBoxIndex?.let { put("rawBoxIndex", it) }
            r.finalBox?.let { put("finalBox", pjson(it)) }; r.finalBoxIndex?.let { put("finalBoxIndex", it) }
        }
    class Checks {
        val mismatches = JSONArray()
        val compared = linkedMapOf<String, Int>()
        private val maximum = linkedMapOf<String, Double>()
        fun exact(key: String, actual: Any?, expected: Any?) {
            compared[key] = (compared[key] ?: 0) + 1
            if (actual != expected) mismatches.put(JSONObject().put("field", key).put("actual", actual ?: JSONObject.NULL).put("expected", expected ?: JSONObject.NULL))
        }
        fun number(key: String, actual: Double?, expected: Double?, atol: Double, rtol: Double = 0.0) {
            compared[key] = (compared[key] ?: 0) + 1
            val error = if (actual != null && expected != null) abs(actual - expected) else null
            if (error != null && error.isFinite()) maximum[key] = maxOf(maximum[key] ?: 0.0, error)
            if ((actual == null) != (expected == null) || (actual != null && expected != null &&
                    (!actual.isFinite() || !expected.isFinite() || error!! > atol + rtol * abs(expected))))
                mismatches.put(JSONObject().put("field", key).put("actual", actual ?: JSONObject.NULL).put("expected", expected ?: JSONObject.NULL)
                    .put("atol", atol).put("rtol", rtol))
        }
        fun quad(key: String, actual: List<GeometryProbeContract.Point>?, expected: List<GeometryProbeContract.Point>?, atol: Double) {
            exact("$key.length", actual?.size, expected?.size)
            if (actual != null && expected != null) for (i in 0 until minOf(actual.size, expected.size)) {
                number("$key.x", actual[i].x, expected[i].x, atol); number("$key.y", actual[i].y, expected[i].y, atol)
            }
        }
        val passed get() = mismatches.length() == 0
        fun json() = JSONObject().put("passed", passed).put("comparisons", JSONObject(compared.toMap()))
            .put("maxFiniteAbs", JSONObject(maximum.toMap())).put("mismatches", mismatches)
    }
    fun trace(c: BoxTraceCase, a: BoxPipelineContract.Result, e: JSONObject): JSONObject {
        val checks = Checks(); val rowChecks = JSONArray()
        checks.exact("status", a.status.name, c.status.name)
        if (c.status == BoxPipelineContract.Status.COMPLETE) {
            increment("completeCases")
            checks.exact("processedCandidates", a.processedCandidates, c.upstreamProcessed)
            checks.exact("contourCount", a.contourCount, c.contourCount)
            checks.exact("dilatedRuns", a.dilatedRuns, c.referenceRuns)
            val erows = e.getJSONArray("rows")
            checks.exact("rows", a.rows.size, erows.length())
            a.rows.forEachIndexed { index, row ->
                increment("rows"); increment("contourVertices", row.contour.size)
                dispositions[row.disposition] = (dispositions[row.disposition] ?: 0) + 1
                if (row.score != null) increment("scores")
                if (row.distance != null) increment("distances")
                if (row.expanded != null) { increment("expandedPaths", row.expanded.size); increment("expandedVertices", row.expanded.sumOf { it.size }) }
                if (row.postQuad != null) increment("postQuads")
                if (row.rawBox != null) increment("rawBoxes")
                if (row.finalBox != null) increment("finalBoxes")
                val r = erows.optJSONObject(index)
                if (r == null) { checks.exact("extraRow", index, null); return@forEachIndexed }
                val q = Checks()
                q.exact("contourIndex", row.contourIndex, r.getInt("contourIndex"))
                q.exact("contourVertices", row.contour.size, r.getJSONArray("contour").length())
                q.exact("contourCycleAndWinding", BoxPipelineContract.sameContour(row.contour, ints(r.getJSONArray("contour"))), true)
                q.quad("preQuad", row.preQuad, points(r.getJSONArray("preUnclipQuad")), 1e-4)
                q.number("minimumSide", row.minimumSide, r.getDouble("minimumSide"), 1e-4)
                q.exact("disposition", row.disposition, r.getString("disposition")) // independent of score tolerance
                fun number(key: String) = if (r.has(key)) r.getDouble(key) else null
                if (scoreAtol > EndToEndScoreBudget.SAME_INPUT_ATOL && row.score != null) {
                    q.exact("scoreMaskFootprint", EndToEndScoreBudget.maskFootprint(row.preQuad, c.shape[3].toInt(), c.shape[2].toInt()),
                        EndToEndScoreBudget.maskFootprint(points(r.getJSONArray("preUnclipQuad")), c.shape[3].toInt(), c.shape[2].toInt()))
                }
                q.number("score", row.score, number("score"), scoreAtol)
                q.number("distance", row.distance, number("distance"), 1e-9, 1e-12)
                q.exact("integerInput", row.integerInput, if (r.has("integerInput")) ints(r.getJSONArray("integerInput")) else null)
                if (r.has("expandedPaths") && row.expanded != null) {
                    val paths = r.getJSONArray("expandedPaths")
                    val reference = List(paths.length()) { ints(paths.getJSONArray(it)) }
                    // Reuse the frozen kernel's cycle/winding/path canonicalization, preserving every vertex.
                    val comparison = PolygonOffsetKernel.compare(row.expanded, reference)
                    q.exact("expandedPathsExactCanonical", comparison.passed, true)
                    q.exact("expandedVertices", comparison.actualVertices, comparison.expectedVertices)
                } else q.exact("expandedPresence", row.expanded != null, r.has("expandedPaths"))
                q.quad("postQuad", row.postQuad, if (r.has("expandedQuad")) points(r.getJSONArray("expandedQuad")) else null, 1e-4)
                q.number("expandedMinimumSide", row.expandedMinimumSide, number("expandedMinimumSide"), 1e-4)
                q.exact("rawBoxIndex", row.rawBoxIndex, if (r.has("rawBoxIndex")) r.getInt("rawBoxIndex") else null)
                q.quad("rawBox", row.rawBox, if (r.has("rawBox")) points(r.getJSONArray("rawBox"), true) else null, 0.0)
                q.exact("finalBoxIndex", row.finalBoxIndex, if (r.has("finalBoxIndex")) r.getInt("finalBoxIndex") else null)
                val final = if (r.has("finalBox") && r.getJSONArray("finalBox").length() == 1) points(r.getJSONArray("finalBox").getJSONArray(0)) else null
                q.quad("finalBox", row.finalBox, final, 0.0)
                if (final != null) q.number("finalScore", row.score, r.getJSONArray("finalScore").getDouble(0), scoreAtol)
                val detail = q.json().put("actual", rowJson(row))
                if (!q.passed) detail.put("reference", r)
                rowChecks.put(detail); checks.exact("row.$index", q.passed, true)
            }
            val rawRows = a.rows.filter { it.rawBox != null }
            checks.exact("rawBoxes", rawRows.size, e.getJSONArray("rawBoxes").length())
            rawRows.forEachIndexed { i, r ->
                if (i < e.getJSONArray("rawBoxes").length()) {
                    checks.quad("topRawBox", r.rawBox, points(e.getJSONArray("rawBoxes").getJSONArray(i)), 0.0)
                    checks.number("topRawScore", r.score, e.getJSONArray("rawScores").getDouble(i), scoreAtol)
                }
            }
            checks.exact("usableBoxes", a.boxes.size, c.accepted)
            val order = e.getJSONArray("pairedReadingOrder")
            checks.exact("readingOrder", a.boxes.map { it.finalBoxIndex }, List(order.length()) { order.getInt(it) })
            a.boxes.forEach { box ->
                if (box.finalBoxIndex < e.getJSONArray("boxes").length()) {
                    checks.quad("pairedBox", box.quad, points(e.getJSONArray("boxes").getJSONArray(box.finalBoxIndex)), 0.0)
                    checks.number("pairedScore", box.score, e.getJSONArray("scores").getDouble(box.finalBoxIndex), scoreAtol)
                }
            }
            if (c.id == "candidate-count-1000") checks.exact("sourceRuns", a.sourceRuns, 2096L)
        } else {
            increment("budgetCases")
            checks.exact("processedCandidates", a.processedCandidates, 0)
            checks.exact("usableBoxes", a.boxes.size, 0)
            if (c.id == "candidate-count-1001") {
                checks.exact("sourceRuns", a.sourceRuns, 2098L); checks.exact("dilatedRuns", a.dilatedRuns, 4068L)
                checks.exact("contourCount", a.contourCount, 1001)
            } else {
                checks.exact("sourceRuns", a.sourceRuns, 8320L)
                checks.exact("dilatedUnexecuted", a.dilated, null); checks.exact("dilatedRunsUnexecuted", a.dilatedRuns, null)
                checks.exact("contoursUnexecuted", a.contourCount, null)
            }
        }
        return checks.json().put("id", c.id).put("status", a.status.name).put("reason", a.reason).put("rowChecks", rowChecks)
            .put("sourceRuns", a.sourceRuns).put("dilatedRuns", a.dilatedRuns ?: JSONObject.NULL)
            .put("contourCount", a.contourCount ?: JSONObject.NULL).put("processedCandidates", a.processedCandidates)
            .put("usableBoxes", a.boxes.size).put("upstreamProcessedReference", c.upstreamProcessed)
            .put("upstreamDilatedRunsReference", c.referenceRuns)
            .put("stages", JSONObject().put("dilation", if (a.dilated == null) "UNEXECUTED" else "EXECUTED")
                .put("findContours", if (a.contourCount == null) "UNEXECUTED" else "EXECUTED")
                .put("scores", a.rows.count { it.score != null }).put("unclip", a.rows.count { it.expanded != null }))
    }
    fun bytes(actual: ByteArray, expected: ByteArray): JSONObject {
        var changed = 0; var max = 0
        for (i in 0 until minOf(actual.size, expected.size)) {
            val d = abs((actual[i].toInt() and 255) - (expected[i].toInt() and 255)); if (d != 0) changed++; max = maxOf(max, d)
        }
        return JSONObject().put("comparedChannels", minOf(actual.size, expected.size)).put("actualChannels", actual.size)
            .put("expectedChannels", expected.size).put("changedChannels", changed).put("maxAbs", max)
            .put("passed", changed == 0 && actual.size == expected.size)
    }
    fun crop(actual: GeometryOpenCvProbe.CropResult, expected: GeometryProbeContract.Row, image: GeometryImage): JSONObject {
        increment("crops"); increment("cropChannels", actual.bgr.size)
        val checks = Checks()
        checks.exact("id", actual.row.id, expected.id); checks.exact("originalIndex", actual.row.originalIndex, expected.originalIndex)
        checks.exact("readingOrder", actual.row.readingOrder, expected.readingOrder)
        checks.number("score", actual.row.detectorScore, expected.detectorScore, scoreAtol)
        checks.quad("quad", actual.row.quad, expected.quad, 0.0)
        checks.exact("plan", actual.row.plan, expected.plan)
        checks.exact("width", actual.width, image.width); checks.exact("height", actual.height, image.height)
        checks.exact("rotated", actual.rotated, expected.plan.rotate)
        val m = GeometryProbeContract.normalize(actual.sourceToPreRotation)
        val inv = GeometryProbeContract.normalize(actual.preRotationToSource)
        val ref = GeometryProbeContract.normalize(expected.referenceMatrix.toDoubleArray())
        val refInv = GeometryProbeContract.inverse(ref)
        for (i in 0..8) {
            checks.number("matrix", m[i], ref[i], GeometryProbeContract.MATRIX_ATOL, GeometryProbeContract.MATRIX_RTOL)
            checks.number("inverseMatrix", inv[i], refInv[i], GeometryProbeContract.MATRIX_ATOL, GeometryProbeContract.MATRIX_RTOL)
        }
        fun point(key: String, a: GeometryProbeContract.Point, e: GeometryProbeContract.Point) {
            checks.number("$key.x", a.x, e.x, GeometryProbeContract.POINT_ATOL, GeometryProbeContract.POINT_RTOL)
            checks.number("$key.y", a.y, e.y, GeometryProbeContract.POINT_ATOL, GeometryProbeContract.POINT_RTOL)
        }
        val plan = actual.row.plan
        fun rotation(p: GeometryProbeContract.Point) = if (plan.rotate) GeometryProbeContract.Point(p.y, plan.width - 1.0 - p.x) else p
        actual.row.quad.zip(GeometryProbeContract.target(plan)).forEach { (source, target) ->
            val projected = GeometryProbeContract.project(m, source)
            point("forward", projected, target); point("inverse", GeometryProbeContract.project(inv, target), source)
            point("sourceRoundtrip", GeometryProbeContract.project(inv, projected), source)
            val rotated = GeometryProbeContract.rotatePixel(projected, plan)
            point("rotatedForward", rotated, rotation(target))
            point("rotatedInverse", GeometryProbeContract.project(inv, GeometryProbeContract.unrotatePixel(rotated, plan)), source)
        }
        for (x in listOf(0.0, plan.width - 1.0)) for (y in listOf(0.0, plan.height - 1.0)) {
            val pixel = GeometryProbeContract.Point(x, y); val source = GeometryProbeContract.project(inv, pixel)
            val restored = GeometryProbeContract.project(m, source)
            point("pixelRoundtrip", restored, pixel)
            val rotated = GeometryProbeContract.rotatePixel(restored, plan)
            point("pixelRotation", rotated, rotation(pixel))
            point("pixelRotationInverse", GeometryProbeContract.project(inv, GeometryProbeContract.unrotatePixel(rotated, plan)), source)
        }
        val pixels = bytes(actual.bgr, image.bgr)
        checks.exact("pixels", pixels.getBoolean("passed"), true)
        return checks.json().put("pixels", pixels).put("id", actual.row.id)
    }
    fun totals(): JSONObject {
        val expected = mapOf("completeCases" to 22, "budgetCases" to 2, "rows" to 1020, "contourVertices" to 4229,
            "scores" to 17, "distances" to 15, "expandedPaths" to 15, "expandedVertices" to 232, "postQuads" to 15,
            "rawBoxes" to 15, "finalBoxes" to 14, "maskCases" to 16, "crops" to 13, "cropChannels" to 139875)
        val expectedDispositions = mapOf("minimum-side" to 1003, "box-score" to 2, "final-size" to 1, "accepted" to 14)
        return JSONObject().put("actual", JSONObject(counts.toMap())).put("expected", JSONObject(expected))
            .put("dispositions", JSONObject(dispositions.toMap())).put("expectedDispositions", JSONObject(expectedDispositions))
            .put("passed", counts == expected && dispositions == expectedDispositions)
    }
}
