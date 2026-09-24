package com.kandong.modelprobe

import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayInputStream
import java.io.InputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.zip.GZIPInputStream

internal data class DetectorCase(val id: String, val sourceWidth: Int, val sourceHeight: Int,
    val plan: DetectorPacking.Plan, val positives: Int, val nearThreshold: Int) {
    val source get() = "$id-source.png"
    val resized get() = "$id-resized.png"
    val argb get() = "$id-resized.argbz"
    val input get() = "$id-input.f32z"
    val output get() = "$id-output.f32z"
    val mask get() = "$id-mask.u8z"
}
internal data class DetectorFile(val bytes: Int, val sha: String, val decodedBytes: Int?, val decodedSha: String?)
internal data class DetectorCaseInput(val argb: IntArray, val referenceInput: FloatArray,
    val referenceOutput: FloatArray, val referenceMask: ByteArray)

/** Packaged assets only. The callback exists for asset corruption tests, never Intent/URI/file input. */
internal class DetectorProbeInputs(private val open: (String) -> InputStream) {
    private var files: Map<String, DetectorFile> = emptyMap()
    private var cases: List<DetectorCase> = emptyList()

    private fun read(path: String, cap: Int, expected: Int? = null): ByteArray {
        require(path in ALLOWED) { "DETECTOR_ASSET_PATH" }
        return open(path).use { ProbeInputs.bounded(it, cap, expected) }
    }

    fun manifest(): List<DetectorCase> {
        files = emptyMap(); cases = emptyList()
        val raw = read("detector-v1/manifest.json", MANIFEST_CAP)
        ProbeInputs.verifyHash(raw, MANIFEST_SHA) // Authenticate before interpreting ANY supplied limit/path.
        val json = JSONObject(String(raw, Charsets.UTF_8))
        require(json.getInt("schema") == 1 && json.getString("sourceManifestSha256") == SOURCE_MANIFEST_SHA)
        val model = json.getJSONObject("model")
        require(model.getString("asset") == MODEL_ASSET && model.getInt("bytes") == MODEL_BYTES && model.getString("sha256") == MODEL_SHA)
        require(model.getString("inputName") == "x" && model.getString("outputName") == "fetch_name_0" &&
            model.getString("dtype") == "FLOAT" && model.getString("layout") == "NCHW" && model.getString("outputLayout") == "N1HW")
        require(json.getJSONObject("versions").getString("onnxruntime") == "1.30.0")
        val limits = json.getJSONObject("limits")
        require(limits.getInt("maxSourceDimension") == DetectorPacking.MAX_SOURCE_DIMENSION &&
            limits.getInt("maxResizedDimension") == DetectorPacking.MAX_RESIZED_DIMENSION &&
            limits.getInt("maxResizedPixels") == DetectorPacking.MAX_PIXELS &&
            limits.getInt("maxCompressedFileBytes") == FILE_CAP && limits.getInt("maxModelBytes") == MODEL_CAP)
        val acceptance = json.getJSONObject("acceptance")
        require(acceptance.getDouble("outputAtol") == DetectorComparison.ATOL &&
            acceptance.getDouble("outputRtol") == DetectorComparison.RTOL &&
            acceptance.getDouble("outputMeanAbsMax") == DetectorComparison.MEAN_ABS_MAX &&
            acceptance.getDouble("threshold").toFloat() == DetectorComparison.THRESHOLD &&
            acceptance.getInt("maxThresholdFlips") == 0 && acceptance.getString("thresholdOperator") == "> float32(0.3)")
        val metadata = json.getJSONObject("files")
        require(metadata.keys().asSequence().toSet() == FILE_NAMES)
        val verifiedFiles = FILE_NAMES.associateWith { name ->
            val f = metadata.getJSONObject(name)
            val bytes = f.getLong("bytes")
            require(bytes in 1..FILE_CAP.toLong())
            val sha = f.getString("sha256"); require(SHA.matches(sha))
            val compressed = name.endsWith("z")
            val decoded = if (compressed) f.getLong("decodedBytes") else null
            if (decoded != null) require(decoded in 1..(DetectorPacking.MAX_PIXELS.toLong() * 12))
            val decodedSha = if (compressed) f.getString("decodedSha256") else null
            require(decodedSha == null || SHA.matches(decodedSha))
            DetectorFile(bytes.toInt(), sha, decoded?.toInt(), decodedSha)
        }
        val rows = json.getJSONArray("cases")
        require(rows.length() == IDS.size)
        val verifiedCases = IDS.mapIndexed { index, id ->
            val row = rows.getJSONObject(index)
            require(row.getString("id") == id)
            val sw = row.getInt("sourceWidth"); val sh = row.getInt("sourceHeight")
            require(sw == SOURCE_WIDTHS[index] && sh == SOURCE_HEIGHTS[index])
            val plan = DetectorPacking.plan(sw, sh)
            require(row.getInt("width") == plan.width && row.getInt("height") == plan.height && row.getInt("effectiveLimit") == plan.effectiveLimit)
            val inputShape = row.getJSONArray("inputShape").shape()
            val outputShape = row.getJSONArray("outputShape").shape()
            require(inputShape.contentEquals(plan.inputShape) && outputShape.contentEquals(plan.outputShape))
            val inputElements = DetectorPacking.elements(inputShape, 3)
            val pixels = DetectorPacking.elements(outputShape, 1)
            val case = DetectorCase(id, sw, sh, plan, row.getInt("positivePixels"), row.getInt("nearThresholdPixels"))
            require(case.positives in 0..pixels && case.nearThreshold in 0..pixels)
            require(id != "blank-negative-24" || case.positives == 0)
            mapOf("source" to case.source, "resized" to case.resized, "resizedArgb" to case.argb,
                "input" to case.input, "output" to case.output, "mask" to case.mask).forEach { (key, name) ->
                require(row.getString(key) == name)
            }
            require(verifiedFiles.getValue(case.source).sha == SOURCE_HASHES[index])
            for ((name, dimensions) in listOf(case.source to (sw to sh), case.resized to (plan.width to plan.height), case.argb to (plan.width to plan.height))) {
                val meta = metadata.getJSONObject(name)
                require(meta.getInt("width") == dimensions.first && meta.getInt("height") == dimensions.second)
            }
            require(verifiedFiles.getValue(case.argb).decodedBytes == pixels * 4)
            require(verifiedFiles.getValue(case.input).decodedBytes == inputElements * 4)
            require(verifiedFiles.getValue(case.output).decodedBytes == pixels * 4)
            require(verifiedFiles.getValue(case.mask).decodedBytes == pixels)
            case
        }
        files = verifiedFiles; cases = verifiedCases
        return cases.toList()
    }

    fun model(): ByteArray = read(MODEL_ASSET, MODEL_CAP, MODEL_BYTES).also { ProbeInputs.verifyHash(it, MODEL_SHA) }
    fun metadata(name: String): DetectorFile = files.getValue(name)
    fun asset(name: String): ByteArray {
        require(name in FILE_NAMES && files.isNotEmpty())
        val f = files.getValue(name)
        return read("detector-v1/$name", FILE_CAP, f.bytes).also { ProbeInputs.verifyHash(it, f.sha) }
    }
    private fun decoded(name: String): ByteArray {
        val f = metadata(name)
        return inflate(asset(name), f.bytes, f.sha, checkNotNull(f.decodedBytes), checkNotNull(f.decodedSha))
    }
    fun load(case: DetectorCase): DetectorCaseInput {
        require(case in cases)
        // Source PNGs are authenticated identity evidence only; this stage never decodes/resizes them.
        asset(case.source); asset(case.resized)
        val argbRaw = decoded(case.argb)
        val pixels = DetectorPacking.pixels(case.plan.width, case.plan.height)
        val argb = IntArray(pixels)
        ByteBuffer.wrap(argbRaw).order(ByteOrder.LITTLE_ENDIAN).asIntBuffer().get(argb)
        require(argb.all { it ushr 24 == 255 })
        val input = floats(decoded(case.input), -1f, 1f)
        val output = floats(decoded(case.output), 0f, 1f)
        val mask = decoded(case.mask)
        require(mask.indices.all { mask[it] == if (output[it] > DetectorComparison.THRESHOLD) 1.toByte() else 0.toByte() })
        require(mask.count { it == 1.toByte() } == case.positives)
        require(output.count { kotlin.math.abs(it - DetectorComparison.THRESHOLD) <= DetectorComparison.NEAR_BAND } == case.nearThreshold)
        return DetectorCaseInput(argb, input, output, mask)
    }

    companion object {
        const val MANIFEST_SHA = "74aa1e39c8d3187ee2388ad07bb228e3c876c0486ccd02c47208942d26f2c80a"
        const val SOURCE_MANIFEST_SHA = "2341dff63bd10d41867351ddff0e4145daa2d9d3493bc8cdc88ea289c1a076a8"
        const val MODEL_ASSET = "detector-model/ch_PP-OCRv5_det_mobile.onnx"
        const val MODEL_SHA = "4d97c44a20d30a81aad087d6a396b08f786c4635742afc391f6621f5c6ae78ae"
        const val MODEL_BYTES = 4_819_576
        const val MANIFEST_CAP = 128 * 1024
        const val FILE_CAP = 2 * 1024 * 1024
        const val MODEL_CAP = 6 * 1024 * 1024
        val IDS = listOf("en-quality-16", "fr-nonrefundable-16", "zh-hans-quality-16", "zh-hant-quality-16", "mixed-quality-16",
            "blank-negative-24", "color-control", "wide-959", "wide-1499", "wide-2001")
        private val SOURCE_WIDTHS = listOf(640, 640, 640, 640, 640, 640, 67, 959, 1499, 2001)
        private val SOURCE_HEIGHTS = listOf(91, 91, 118, 118, 145, 101, 53, 100, 100, 100)
        private val SOURCE_HASHES = listOf(
            "7dcc8eea74aa255202c8e1d2e126236de81202048253a501646c5cc0dcd9070f",
            "dfffb230fa0bbc886a1695aef47550aec41a13a9961ef29e43b5b29ad2f5a8d1",
            "a5cd006a679246fe50c1bb4d6cb9e2b616c3a69168cd99f8c2d230a448957184",
            "9453b4a14282aa818b9446475b001e6ec2ea81ab2affa22b551d3939484683cc",
            "1afec2c7e0a618edd371fb015697a9c477d7945bae83ee756f98c64676dd0b2b",
            "ae57633560626ae3902356aa31cf7afd1e67ee1c9aa289e12e177412f834e19f",
            "9dd718e24688d8ded5505526238bdd398985ffa117891731edb06a684bc3dc5e",
            "ddc561a0569ef22af5751cd0a8799ab235d2963d83bcdd7c798ca667d03ebb50",
            "340a8fc0ddbbd634bbe8c6db1ba54ed2823fbb5c578c91da8e43182c0aa30649",
            "2230ae21bdc0b153ce1d7dce132f386b9454eb5b13f2dcbfc2af505a9f841b02")
        private val SHA = Regex("[0-9a-f]{64}")
        private val FILE_NAMES = IDS.flatMap { id -> listOf("$id-source.png", "$id-resized.png", "$id-resized.argbz",
            "$id-input.f32z", "$id-output.f32z", "$id-mask.u8z") }.toSet() + "manifest.properties"
        private val ALLOWED = FILE_NAMES.map { "detector-v1/$it" }.toSet() + "detector-v1/manifest.json" + MODEL_ASSET
        private fun JSONArray.shape(): LongArray {
            require(length() == 4)
            return LongArray(4) { getLong(it) }
        }
        /** Internal seam for malformed gzip tests; production path supplies authenticated metadata only. */
        fun inflate(compressed: ByteArray, expectedCompressed: Int, compressedSha: String, expectedDecoded: Int, decodedSha: String): ByteArray {
            require(expectedCompressed in 1..FILE_CAP && compressed.size == expectedCompressed)
            require(expectedDecoded in 1..DetectorPacking.MAX_PIXELS * 12)
            ProbeInputs.verifyHash(compressed, compressedSha)
            val raw = GZIPInputStream(ByteArrayInputStream(compressed)).use { ProbeInputs.bounded(it, expectedDecoded, expectedDecoded) }
            ProbeInputs.verifyHash(raw, decodedSha)
            return raw
        }
        fun floats(raw: ByteArray, minimum: Float, maximum: Float): FloatArray {
            require(raw.isNotEmpty() && raw.size % 4 == 0 && raw.size <= DetectorPacking.MAX_PIXELS * 12)
            val buffer = ByteBuffer.wrap(raw).order(ByteOrder.LITTLE_ENDIAN).asFloatBuffer()
            // Validate every value before allocating the returned array.
            while (buffer.hasRemaining()) { val f = buffer.get(); require(f.isFinite() && f in minimum..maximum) }
            buffer.rewind()
            return FloatArray(buffer.remaining()).also { buffer.get(it) }
        }
    }
}
