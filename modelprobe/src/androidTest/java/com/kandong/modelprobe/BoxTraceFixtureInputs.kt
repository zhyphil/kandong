package com.kandong.modelprobe

import android.util.JsonReader
import android.util.JsonToken
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayInputStream
import java.io.InputStream
import java.io.StringReader
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import java.util.zip.GZIPInputStream

internal data class BoxTraceFile(val bytes: Int, val sha: String, val decodedBytes: Int, val decodedSha: String)
internal data class BoxTraceCase(val id: String, val shape: List<Long>, val width: Int, val height: Int,
    val parent: Boolean, val contourCount: Int, val upstreamProcessed: Int, val referenceRuns: Long,
    val status: BoxPipelineContract.Status, val accepted: Int, val preUnclip: Int)
internal data class BoxTraceManifest(val files: Map<String, BoxTraceFile>, val cases: List<BoxTraceCase>)

/** Authenticated, bounded streaming JSON. No org.json coercive getters are used for validation. */
internal class BoxTraceFixtureInputs(private val open: (String) -> InputStream, private val list: (String) -> List<String>) {
    private var verified: BoxTraceManifest? = null
    private fun read(name: String, expected: Int? = null): ByteArray =
        open("$NAMESPACE/${physical(name)}").use { ProbeInputs.bounded(it, if (name == "manifest.json") MANIFEST_CAP else FILE_CAP, expected) }
    fun manifest(): List<BoxTraceCase> {
        verified = null
        val names = list(NAMESPACE)
        require(names.size == 33 && names.toSet() == LOGICAL_NAMES.map(::physical).toSet()) { "BOX_ASSET_SET" }
        val raw = read("manifest.json", MANIFEST_BYTES); ProbeInputs.verifyHash(raw, MANIFEST_SHA)
        val parsed = parseManifest(raw)
        // Authenticate EVERY compressed and decoded byte before any trace JSON is parsed.
        parsed.files.forEach { (name, meta) -> decodeFile(read(name, meta.bytes), meta, name) }
        verified = parsed
        return parsed.cases
    }
    fun metadata(name: String) = checkNotNull(verified).files.getValue(name)
    fun decoded(name: String): ByteArray {
        require(name in LOGICAL_NAMES && name != "manifest.json") { "BOX_ASSET_PATH" }
        val f = metadata(name)
        return decodeFile(read(name, f.bytes), f, name)
    }
    fun trace(c: BoxTraceCase): JSONObject {
        require(c in checkNotNull(verified).cases)
        return parseTrace(decoded("${c.id}-trace.json.gz"), c)
    }
    fun probability(c: BoxTraceCase, parent: GeometryFixtureInputs, parents: List<GeometryCase>): FloatArray {
        require(c in checkNotNull(verified).cases)
        val values = if (c.parent) {
            val p = parents.single { it.id == c.id }
            require(p.shape == c.shape && p.sourceWidth == c.width && p.sourceHeight == c.height)
            parent.probability(p)
        } else DetectorProbeInputs.floats(decoded("${c.id}-probability.f32z"), 0f, 1f)
        GeometryProbeContract.validateProbability(c.shape.toLongArray(), values)
        return values
    }
    companion object {
        const val NAMESPACE = "detector-box-trace-v1"
        const val MANIFEST_SHA = "91cc2279377e8809ba3898bd31784f79df307dc0ad816a826fac139de7184118"
        const val MANIFEST_BYTES = 22930
        const val MANIFEST_CAP = 128 * 1024
        const val FILE_CAP = 2 * 1024 * 1024
        const val TRACE_CAP = 1024 * 1024
        const val FLOAT_CAP = 4 * 1024 * 1024
        val NEW_IDS = listOf("candidate-count-1000", "candidate-count-1001", "candidate-count-4096", "score-equal",
            "score-below", "final-size-three", "minimum-side-two", "collinear-height-one")
        val IDS = GeometryFixtureInputs.IDS + NEW_IDS
        val LOGICAL_NAMES = (IDS.map { "$it-trace.json.gz" } + NEW_IDS.map { "$it-probability.f32z" } + "manifest.json").toSet()
        fun physical(logical: String): String {
            require(logical in LOGICAL_NAMES) { "BOX_ASSET_PATH" }
            return if (logical.endsWith(".json.gz")) logical.removeSuffix(".json.gz") + ".jsonz" else logical
        }
        private fun hash(s: String) = s.also { require(it.matches(Regex("[0-9a-f]{64}"))) { "BOX_HASH_FORMAT" } }
        fun keys(o: JSONObject, expected: Set<String>) { require(o.keys().asSequence().toSet() == expected) { "BOX_KEYS" } }
        fun str(o: JSONObject, key: String): String = (o.get(key) as? String) ?: error("BOX_STRING")
        fun int(o: JSONObject, key: String, low: Long = 0, high: Long = 1_048_576): Long =
            integer(o.get(key), low, high)
        fun integer(value: Any, low: Long, high: Long): Long = (value as? Long)?.also {
            require(it in low..high) { "BOX_INTEGER_RANGE" }
        } ?: error("BOX_INTEGER_TYPE")
        fun num(value: Any): Double = (value as? Number)?.toDouble()?.also {
            require(it.isFinite()) { "BOX_FINITE" }
        } ?: error("BOX_NUMBER_TYPE")
        fun obj(o: JSONObject, key: String): JSONObject = o.get(key) as? JSONObject ?: error("BOX_OBJECT_TYPE")
        fun arr(o: JSONObject, key: String): JSONArray = o.get(key) as? JSONArray ?: error("BOX_ARRAY_TYPE")
        fun bool(o: JSONObject, key: String): Boolean = o.get(key) as? Boolean ?: error("BOX_BOOLEAN_TYPE")
        private fun stringMap(o: JSONObject, expected: Map<String, String>) {
            keys(o, expected.keys); expected.forEach { (k, v) -> require(str(o, k) == v) }
        }
        /** Same parser entry for authenticated fixtures and adversarial schema tests. */
        fun strictJson(raw: ByteArray, cap: Int): JSONObject {
            require(cap in 1..TRACE_CAP && raw.size in 1..cap) { "BOX_JSON_CAP" }
            val text = Charsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(raw)).toString()
            // Byte/UTF-8 checks precede object allocation. JsonReader stays non-lenient.
            JsonReader(StringReader(text)).use { reader ->
                reader.isLenient = false
                var nodes = 0
                fun value(depth: Int, field: String): Any {
                    require(depth <= 12 && ++nodes <= 100000) { "BOX_JSON_BUDGET" }
                    return when (reader.peek()) {
                        JsonToken.BEGIN_OBJECT -> {
                            reader.beginObject(); val result = JSONObject(); val seen = hashSetOf<String>()
                            while (reader.hasNext()) {
                                require(seen.size < 64) { "BOX_OBJECT_CAP" }
                                val name = reader.nextName()
                                require(name.length <= 128 && seen.add(name)) { "BOX_DUPLICATE_KEY" }
                                result.put(name, value(depth + 1, name))
                            }
                            reader.endObject(); result
                        }
                        JsonToken.BEGIN_ARRAY -> {
                            val limit = when (field) { "rows", "rawBoxes", "rawScores", "boxes", "scores", "pairedReadingOrder" -> 1000
                                "cases" -> 24; "shape", "preUnclipQuad", "expandedQuad", "integerInput", "rawBox" -> 4
                                "expandedPaths" -> 8; else -> 8192 }
                            reader.beginArray(); val result = JSONArray()
                            while (reader.hasNext()) {
                                require(result.length() < limit) { "BOX_ARRAY_CAP" }
                                result.put(value(depth + 1, ""))
                            }
                            reader.endArray(); result
                        }
                        JsonToken.STRING -> reader.nextString().also { require(it.length <= 512) { "BOX_STRING_CAP" } }
                        JsonToken.NUMBER -> {
                            val number = reader.nextString(); require(number.length <= 64)
                            if (number.matches(Regex("-?(0|[1-9][0-9]*)"))) number.toLong()
                            else number.toDouble().also { require(it.isFinite()) { "BOX_FINITE" } }
                        }
                        JsonToken.BOOLEAN -> reader.nextBoolean()
                        else -> error("BOX_JSON_TOKEN") // null is not part of either frozen schema
                    }
                }
                val root = value(0, "") as? JSONObject ?: error("BOX_ROOT_OBJECT")
                require(reader.peek() == JsonToken.END_DOCUMENT) { "BOX_END_DOCUMENT" }
                return root
            }
        }
        fun decodeFile(raw: ByteArray, meta: BoxTraceFile, name: String): ByteArray {
            require(name in LOGICAL_NAMES && name != "manifest.json") { "BOX_ASSET_PATH" }
            val cap = if (name.endsWith(".json.gz")) TRACE_CAP else FLOAT_CAP
            require(meta.bytes in 1..FILE_CAP && raw.size == meta.bytes && meta.decodedBytes in 1..cap) { "BOX_FILE_CAP" }
            hash(meta.sha); hash(meta.decodedSha); ProbeInputs.verifyHash(raw, meta.sha)
            val decoded = GZIPInputStream(ByteArrayInputStream(raw)).use { ProbeInputs.bounded(it, cap, meta.decodedBytes) }
            ProbeInputs.verifyHash(decoded, meta.decodedSha)
            return decoded
        }
        fun parseManifest(raw: ByteArray): BoxTraceManifest {
            val m = strictJson(raw, MANIFEST_CAP)
            keys(m, setOf("schema", "scope", "parentManifestSha256", "versions", "sourceHashes", "scriptSha256", "configuration", "bounds", "limitations", "cases", "files"))
            require(int(m, "schema") == 1L && str(m, "parentManifestSha256") == GeometryFixtureInputs.MANIFEST_SHA)
            require(str(m, "scope") == "Synthetic DB intermediate trace and bounds; host reference only, not Android or OCR acceptance")
            require(str(m, "scriptSha256") == "7b5ed20968bd79a8071441302422d992490bd53ee78d583a908de3b5b14101fd")
            stringMap(obj(m, "versions"), mapOf("rapidocr" to "3.9.2", "numpy" to "2.5.3", "opencv-python" to "5.0.0.93",
                "pyclipper" to "1.4.0", "shapely" to "2.1.2", "onnxruntime" to "1.30.0"))
            stringMap(obj(m, "sourceHashes"), mapOf(
                "rapidocr/ch_ppocr_det/utils.py" to "01d25a0b1bbdcdd4aba70a23ae96714c5408df93b295c43ca194952e279adb9e",
                "rapidocr/ch_ppocr_det/main.py" to "a56c0f51fd6a8c03a5abf2f0a5843b382b8f3257d1bfacf8d5ac6305d5e63024",
                "rapidocr/utils/process_img.py" to "abaf2ed615878f618a372cba6157bc6c41a494e05f6c41bf9f7c5c2ba1ee5772"))
            val config = obj(m, "configuration")
            keys(config, setOf("thresh", "box_thresh", "max_candidates", "unclip_ratio", "use_dilation", "score_mode"))
            require(num(config.get("thresh")) == .3 && num(config.get("box_thresh")) == .5 && int(config, "max_candidates") == 1000L &&
                num(config.get("unclip_ratio")) == 1.6 && bool(config, "use_dilation") && str(config, "score_mode") == "fast")
            val bounds = obj(m, "bounds"); keys(bounds, setOf("maxPixels", "maxDimension", "rowRunBound", "candidateLimit"))
            require(int(bounds, "maxPixels") == 1048576L && int(bounds, "maxDimension") == 4096L &&
                int(bounds, "rowRunBound") == 8192L && int(bounds, "candidateLimit") == 1000L)
            val limitations = arr(m, "limitations")
            val expectedLimitations = listOf("Old parent files are referenced and must be separately authenticated",
                "Reference upstream computes first 1000 even for budget stress; Android must stop earlier for row-run budget",
                "Incomplete cases must not expose truncated boxes as complete or enter cropping/recognition",
                "No Android execution, inference, OCR accuracy, screen capture or translation")
            require(limitations.length() == expectedLimitations.size && expectedLimitations.indices.all { limitations.get(it) == expectedLimitations[it] })
            val entries = obj(m, "files"); keys(entries, LOGICAL_NAMES - "manifest.json")
            val files = (LOGICAL_NAMES - "manifest.json").associateWith { name ->
                val f = obj(entries, name); keys(f, setOf("bytes", "sha256", "decodedBytes", "decodedSha256"))
                BoxTraceFile(int(f, "bytes", 1, FILE_CAP.toLong()).toInt(), hash(str(f, "sha256")),
                    int(f, "decodedBytes", 1, if (name.endsWith(".json.gz")) TRACE_CAP.toLong() else FLOAT_CAP.toLong()).toInt(), hash(str(f, "decodedSha256")))
            }
            require(files.values.sumOf { it.bytes } + MANIFEST_BYTES == 112533)
            val cases = arr(m, "cases"); require(cases.length() == 24)
            val parsed = IDS.mapIndexed { i, id ->
                val c = cases.get(i) as? JSONObject ?: error("BOX_CASE_TYPE")
                keys(c, setOf("id", "origin", "probability", "shape", "sourceHeight", "sourceWidth", "trace", "contourCount", "processedCandidates", "rowRunBound", "expectedSafeStatus", "acceptedUpstreamBoxes", "actualPreUnclipCount"))
                require(str(c, "id") == id && str(c, "trace") == "$id-trace.json.gz") { "BOX_CASE_ID" }
                val parent = i < 16
                require(str(c, "origin") == if (parent) "frozen-parent" else "hand-authored-no-neural-inference")
                stringMap(obj(c, "probability"), mapOf((if (parent) "parentFile" else "file") to "$id-probability.f32z"))
                val shapeArray = arr(c, "shape"); require(shapeArray.length() == 4)
                val shape = List(4) { integer(shapeArray.get(it), 1, 4096) }
                val pixels = GeometryProbeContract.probabilityPixels(shape.toLongArray())
                val w = int(c, "sourceWidth", 1, 4096).toInt(); val h = int(c, "sourceHeight", 1, 4096).toInt()
                GeometryProbeContract.pixels(w, h)
                if (!parent) require(files.getValue("$id-probability.f32z").decodedBytes == pixels * 4)
                val status = when (id) { "candidate-count-1001" -> BoxPipelineContract.Status.INCOMPLETE_CANDIDATE_LIMIT
                    "candidate-count-4096" -> BoxPipelineContract.Status.INCOMPLETE_CONTOUR_BUDGET
                    else -> BoxPipelineContract.Status.COMPLETE }
                require(str(c, "expectedSafeStatus") == status.name)
                val count = int(c, "contourCount", 0, 4096).toInt()
                val processed = int(c, "processedCandidates", 0, 1000).toInt(); require(processed == minOf(count, 1000))
                BoxTraceCase(id, shape, w, h, parent, count, processed, int(c, "rowRunBound", 1, 32768), status,
                    int(c, "acceptedUpstreamBoxes", 0, processed.toLong()).toInt(), int(c, "actualPreUnclipCount", 0, processed.toLong()).toInt())
            }
            return BoxTraceManifest(files, parsed)
        }
        fun points(array: JSONArray, cap: Int, integers: Boolean = false): List<GeometryProbeContract.Point> {
            require(array.length() <= cap)
            return List(array.length()) { i ->
                val p = array.get(i) as? JSONArray ?: error("BOX_POINT_TYPE"); require(p.length() == 2)
                if (integers) { integer(p.get(0), -8321, 8321); integer(p.get(1), -8321, 8321) }
                GeometryProbeContract.Point(num(p.get(0)), num(p.get(1))).also {
                    require(kotlin.math.abs(it.x) <= 8321 && kotlin.math.abs(it.y) <= 8321)
                }
            }
        }
        private fun quads(array: JSONArray): List<List<GeometryProbeContract.Point>> {
            require(array.length() <= 1000)
            return List(array.length()) { points(array.get(it) as? JSONArray ?: error("BOX_QUAD_TYPE"), 4).also { q -> require(q.size == 4) } }
        }
        private fun scores(array: JSONArray): List<Double> {
            require(array.length() <= 1000)
            return List(array.length()) { num(array.get(it)).also { n -> require(n in 0.0..1.0) } }
        }
        fun parseTrace(raw: ByteArray, c: BoxTraceCase): JSONObject {
            val t = strictJson(raw, TRACE_CAP)
            keys(t, setOf("id", "rows", "rawBoxes", "rawScores", "boxes", "scores", "pairedReadingOrder", "contourCount", "processedCandidates", "rowRunBound", "upstreamTruncated", "expectedSafeStatus", "safePolicy"))
            require(str(t, "id") == c.id && int(t, "contourCount") == c.contourCount.toLong() && int(t, "processedCandidates") == c.upstreamProcessed.toLong() &&
                int(t, "rowRunBound") == c.referenceRuns && bool(t, "upstreamTruncated") == (c.contourCount > 1000) && str(t, "expectedSafeStatus") == c.status.name)
            require(str(t, "safePolicy") == "Do not report partial upstream output as complete; no crop/recognition on incomplete status")
            val rows = arr(t, "rows"); require(rows.length() == c.upstreamProcessed)
            var totalVertices = 0; var unclip = 0; var rawIndex = 0; var finalIndex = 0
            for (i in 0 until rows.length()) {
                val r = rows.get(i) as? JSONObject ?: error("BOX_ROW_TYPE")
                val disposition = str(r, "disposition")
                require(disposition in setOf("minimum-side", "box-score", "expanded-minimum-side", "final-size", "accepted"))
                val expected = mutableSetOf("contourIndex", "contour", "preUnclipQuad", "minimumSide", "disposition")
                if (disposition != "minimum-side") expected.add("score")
                if (disposition !in setOf("minimum-side", "box-score")) expected.addAll(listOf("distance", "integerInput", "expandedPaths", "expandedQuad", "expandedMinimumSide"))
                if (disposition in setOf("final-size", "accepted")) expected.addAll(listOf("rawBoxIndex", "rawBox", "finalBox", "finalScore"))
                if (disposition == "accepted") expected.add("finalBoxIndex")
                keys(r, expected); require(int(r, "contourIndex") == i.toLong())
                val contour = points(arr(r, "contour"), 8192, true); require(contour.isNotEmpty())
                totalVertices += contour.size; require(totalVertices <= 32768)
                require(points(arr(r, "preUnclipQuad"), 4).size == 4)
                val side = num(r.get("minimumSide")); require(side >= 0)
                require((side < 3) == (disposition == "minimum-side"))
                if (r.has("score")) {
                    val score = num(r.get("score")); require(score in 0.0..1.0)
                    require((score < .5) == (disposition == "box-score"))
                }
                if (r.has("distance")) {
                    unclip++; val distance = num(r.get("distance")); require(distance > 0 && distance <= 128)
                    require(points(arr(r, "integerInput"), 4, true).size == 4)
                    val expanded = arr(r, "expandedPaths"); require(expanded.length() in 1..8)
                    var vertices = 0
                    for (p in 0 until expanded.length()) {
                        val path = points(expanded.get(p) as? JSONArray ?: error("BOX_PATH_TYPE"), 512, true)
                        require(path.size >= 3); vertices += path.size
                    }
                    require(vertices <= 512 && points(arr(r, "expandedQuad"), 4).size == 4)
                    val expandedSide = num(r.get("expandedMinimumSide")); require(expandedSide >= 0)
                    require((expandedSide < 5) == (disposition == "expanded-minimum-side"))
                }
                if (r.has("rawBoxIndex")) {
                    require(int(r, "rawBoxIndex") == rawIndex++.toLong() && points(arr(r, "rawBox"), 4, true).size == 4)
                    val finalBoxes = quads(arr(r, "finalBox")); val finalScores = scores(arr(r, "finalScore"))
                    require(finalBoxes.size == (if (disposition == "accepted") 1 else 0) && finalScores.size == finalBoxes.size)
                }
                if (r.has("finalBoxIndex")) require(int(r, "finalBoxIndex") == finalIndex++.toLong())
            }
            require(unclip == c.preUnclip && finalIndex == c.accepted)
            require(quads(arr(t, "rawBoxes")).size == rawIndex && scores(arr(t, "rawScores")).size == rawIndex)
            require(quads(arr(t, "boxes")).size == finalIndex && scores(arr(t, "scores")).size == finalIndex)
            val order = arr(t, "pairedReadingOrder"); require(order.length() == finalIndex)
            require(List(order.length()) { integer(order.get(it), 0, (finalIndex - 1).toLong()).toInt() }.toSet() == (0 until finalIndex).toSet())
            return t
        }
    }
}
