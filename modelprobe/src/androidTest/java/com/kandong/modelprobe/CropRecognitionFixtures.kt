package com.kandong.modelprobe

import com.kandong.modelprobe.BoxTraceFixtureInputs.Companion.arr
import com.kandong.modelprobe.BoxTraceFixtureInputs.Companion.bool
import com.kandong.modelprobe.BoxTraceFixtureInputs.Companion.int
import com.kandong.modelprobe.BoxTraceFixtureInputs.Companion.integer
import com.kandong.modelprobe.BoxTraceFixtureInputs.Companion.keys
import com.kandong.modelprobe.BoxTraceFixtureInputs.Companion.num
import com.kandong.modelprobe.BoxTraceFixtureInputs.Companion.obj
import com.kandong.modelprobe.BoxTraceFixtureInputs.Companion.str
import org.json.JSONArray
import org.json.JSONObject
import java.io.InputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.zip.GZIPInputStream

/** Only authenticated synthetic references. Expected data is never an algorithm input. */
internal class CropRecognitionFixtures(private val open: (String) -> InputStream,
    private val list: (String) -> List<String>, private val cleanup: GeometryCleanup) {
    private var verified: JSONObject? = null
    private var files = emptyMap<String, GeometryFile>()
    private fun read(name: String, size: Int? = null): ByteArray {
        require(name in NAMES)
        return open("$NAMESPACE/$name").use { ProbeInputs.bounded(it, if (name == "manifest.json") MANIFEST_CAP else FILE_CAP, size) }
    }
    fun manifest(): List<JSONObject> {
        verified = null; files = emptyMap()
        val names = list(NAMESPACE); require(names.size == 51 && names.toSet() == NAMES)
        val raw = read("manifest.json", 50281); ProbeInputs.verifyHash(raw, MANIFEST_SHA)
        val m = parse(raw)
        val metadata = obj(m, "files")
        val parsed = (NAMES - "manifest.json").associateWith { name -> file(obj(metadata, name), name) }
        require(parsed.values.sumOf { it.bytes } + 50281 == 682245)
        // Authenticate every payload, including the complete argmax stream, before returning cases.
        parsed.forEach { (name, f) ->
            val bytes = read(name, f.bytes); ProbeInputs.verifyHash(bytes, f.sha)
            if (name.endsWith(".png")) GeometryFixtureInputs.decodePng(bytes, f, cleanup) else decode(bytes, f)
        }
        files = parsed; verified = m
        return objects(arr(m, "cases"))
    }
    fun metadata(name: String): GeometryFile { require(name in NAMES && name != "manifest.json"); checkNotNull(verified); return files.getValue(name) }
    fun asset(name: String): ByteArray { val f = metadata(name); return read(name, f.bytes).also { ProbeInputs.verifyHash(it, f.sha) } }
    fun decoded(name: String): ByteArray = decode(asset(name), metadata(name))
    fun image(name: String) = GeometryFixtureInputs.decodePng(asset(name), metadata(name), cleanup)
    companion object {
        const val NAMESPACE = "crop-recognition-v1"
        const val MANIFEST_SHA = "bbbfd058777e51e4fb7769764d2b49dac7618c784b8e0ef652af84d973f64f6a"
        const val MANIFEST_CAP = 128 * 1024
        const val FILE_CAP = 1024 * 1024
        const val RAW_CAP = 4_718_592
        val NAMES = (listOf("manifest.json") + GeometryFixtureInputs.IDS.flatMapIndexed { i, id ->
            val n = GeometryFixtureInputs.CROP_COUNTS[i]
            if (n == 0) emptyList() else List(n) { r -> "$id-row-$r-resized.png" } +
                List(n) { r -> "$id-row-$r-resized.argbz" } + "$id.f32z" + listOf("ch", "latin").map { "$id-$it-argmax.i32z" }
        }).toSet()
        fun objects(a: JSONArray): List<JSONObject> = List(a.length()) { a.get(it) as? JSONObject ?: error("CROP_OBJECT") }
        fun strings(a: JSONArray): List<String> = List(a.length()) { a.get(it) as? String ?: error("CROP_STRING") }
        fun ints(a: JSONArray, cap: Long): List<Int> = List(a.length()) { integer(a.get(it), 0, cap).toInt() }
        private fun hash(s: String) = s.also { require(it.matches(Regex("[0-9a-f]{64}"))) }
        private fun file(f: JSONObject, name: String): GeometryFile {
            val png = name.endsWith(".png")
            keys(f, if (png) setOf("bytes", "sha256", "width", "height", "rawBgrSha256") else setOf("bytes", "sha256", "decodedBytes", "decodedSha256"))
            val size = int(f, "bytes", 1, FILE_CAP.toLong()).toInt(); val sha = hash(str(f, "sha256"))
            return if (png) GeometryFile(size, sha, width = int(f, "width", 1, 2048).toInt(),
                height = int(f, "height", 48, 48).toInt(), bgrSha = hash(str(f, "rawBgrSha256")))
            else GeometryFile(size, sha, int(f, "decodedBytes", 1, RAW_CAP.toLong()).toInt(), hash(str(f, "decodedSha256")))
        }
        fun decode(raw: ByteArray, f: GeometryFile): ByteArray {
            require(f.bytes in 1..FILE_CAP && raw.size == f.bytes && f.decodedBytes != null && f.decodedBytes in 1..RAW_CAP)
            ProbeInputs.verifyHash(raw, f.sha)
            val decoded = GZIPInputStream(raw.inputStream()).use { ProbeInputs.bounded(it, RAW_CAP, f.decodedBytes) }
            ProbeInputs.verifyHash(decoded, checkNotNull(f.decodedSha)); return decoded
        }
        /** Same strict parser used by authenticated loading and focused schema fault injection. */
        fun parse(raw: ByteArray): JSONObject {
            val m = BoxTraceFixtureInputs.strictJson(raw, MANIFEST_CAP)
            keys(m, setOf("schema", "scope", "parentManifestSha256", "probeManifestSha256", "versions", "sourceHashes", "scriptSha256", "models", "cases", "files", "contract"))
            require(int(m, "schema", 1, 1) == 1L && str(m, "parentManifestSha256") == GeometryFixtureInputs.MANIFEST_SHA && str(m, "probeManifestSha256") == ProbeInputs.MANIFEST_SHA)
            require(str(m, "scope") == "fixed_synthetic_geometry_crops_to_two_recognizers_not_quality_or_end_to_end_detection")
            hash(str(m, "scriptSha256"))
            val versions = obj(m, "versions"); keys(versions, setOf("rapidocr", "numpy", "opencv-python", "pillow", "onnxruntime"))
            mapOf("rapidocr" to "3.9.2", "numpy" to "2.5.3", "opencv-python" to "5.0.0.93", "pillow" to "12.3.0", "onnxruntime" to "1.30.0").forEach { (k, v) -> require(str(versions, k) == v) }
            val hashes = obj(m, "sourceHashes"); keys(hashes, setOf("rapidocr/ch_ppocr_rec/main.py", "rapidocr/ch_ppocr_rec/utils.py")); hashes.keys().forEach { hash(str(hashes, it)) }
            val contract = obj(m, "contract")
            keys(contract, setOf("height", "baseWidth", "maxWidth", "maxBatch", "channels", "layout", "resize", "normalization", "rowOrder", "empty", "runtime", "noModelRouting", "noTextNormalization"))
            mapOf("height" to 48L, "baseWidth" to 320L, "maxWidth" to 2048L, "maxBatch" to 3L).forEach { (k, v) -> require(int(contract, k) == v) }
            for (k in listOf("channels", "layout", "resize", "normalization", "rowOrder", "empty", "runtime")) str(contract, k)
            require(bool(contract, "noModelRouting") && bool(contract, "noTextNormalization"))
            val modelSpecs = objects(arr(m, "models")); require(modelSpecs.map { str(it, "id") } == listOf("ch", "latin"))
            modelSpecs.forEach { model ->
                keys(model, setOf("id", "asset", "sha256", "bytes", "dictionaryAsset", "dictionarySha256", "vocabularySize", "inputName", "outputName"))
                val id = str(model, "id"); require(str(model, "asset") == "models/${id}_PP-OCRv5_rec_mobile.onnx" && str(model, "dictionaryAsset") == "probes/$id-dictionary.json")
                hash(str(model, "sha256")); hash(str(model, "dictionarySha256"))
                require(int(model, "bytes", 1, 20_000_000) == if (id == "ch") 16631306L else 7904513L)
                require(int(model, "vocabularySize") == if (id == "ch") 18385L else 504L)
                require(str(model, "inputName") == "x" && str(model, "outputName") == "fetch_name_0")
            }
            val metadata = obj(m, "files"); keys(metadata, NAMES - "manifest.json")
            val fs = (NAMES - "manifest.json").associateWith { file(obj(metadata, it), it) }
            val cases = objects(arr(m, "cases")); require(cases.map { str(it, "id") } == GeometryFixtureInputs.IDS)
            var floatCount = 0L
            cases.forEachIndexed { ci, c ->
                val id = str(c, "id"); val n = GeometryFixtureInputs.CROP_COUNTS[ci]
                keys(c, setOf("id", "status", "rows", "tensorRowToReadingOrder", "models") + if (n > 0) setOf("shape", "tensor") else emptySet())
                require(str(c, "status") == if (n == 0) "EMPTY_NO_INFERENCE" else "READY")
                val rows = objects(arr(c, "rows")); require(rows.size == n)
                val mapping = ints(arr(c, "tensorRowToReadingOrder"), 2); CropRecognitionContract.permutation(mapping, n)
                val original = mutableListOf<Int>()
                rows.forEachIndexed { rank, r ->
                    keys(r, setOf("boxId", "originalIndex", "readingOrder", "quad", "detectorScore", "crop", "cropSha256", "cropBgrSha256", "width", "height", "rotateCounterClockwise90", "sourceToPreRotationCrop", "tensorRow", "resizedWidth", "resizedHeight", "resized", "resizedArgb"))
                    val index = int(r, "originalIndex", 0, (n - 1).toLong()).toInt(); original += index
                    require(str(r, "boxId") == "$id.box-$index" && int(r, "readingOrder") == rank.toLong())
                    require(int(r, "tensorRow", 0, (n - 1).toLong()).toInt() == mapping.indexOf(rank))
                    val q = arr(r, "quad"); require(q.length() == 4); for (i in 0..3) { val p = q.get(i) as? JSONArray ?: error("QUAD"); require(p.length() == 2); for (j in 0..1) require(num(p.get(j)) in 0.0..4096.0) }
                    require(num(r.get("detectorScore")) in 0.0..1.0)
                    require(str(r, "crop") == GeometryFixtureInputs.cropName(id, rank)); hash(str(r, "cropSha256")); hash(str(r, "cropBgrSha256"))
                    int(r, "width", 1, 4096); int(r, "height", 1, 2048); bool(r, "rotateCounterClockwise90")
                    val matrix = arr(r, "sourceToPreRotationCrop"); require(matrix.length() == 3)
                    val values = (0..2).flatMap { i -> val a = matrix.get(i) as? JSONArray ?: error("MATRIX"); require(a.length() == 3); (0..2).map { num(a.get(it)) } }
                    GeometryProbeContract.inverse(values.toDoubleArray())
                    val w = int(r, "resizedWidth", 1, 2048); int(r, "resizedHeight", 48, 48)
                    require(str(r, "resized") == "$id-row-$rank-resized.png" && str(r, "resizedArgb") == "$id-row-$rank-resized.argbz")
                    require(fs.getValue(str(r, "resized")).width?.toLong() == w && fs.getValue(str(r, "resizedArgb")).decodedBytes?.toLong() == w * 48 * 4)
                }
                CropRecognitionContract.permutation(original, n)
                val models = objects(arr(c, "models")); require(models.map { str(it, "model") } == if (n == 0) emptyList<String>() else listOf("ch", "latin"))
                if (n > 0) {
                    val shape = ints(arr(c, "shape"), 2048).map { it.toLong() }.toLongArray()
                    require(shape.size == 4 && shape[0] == n.toLong() && shape[1] == 3L && shape[2] == 48L && shape[3] in 320..2048)
                    val count = ProbeInputs.product(shape, 1_200_000); floatCount += count
                    require(str(c, "tensor") == "$id.f32z" && fs.getValue("$id.f32z").decodedBytes?.toLong() == count * 4)
                }
                models.forEach { model ->
                    keys(model, setOf("model", "outputShape", "argmax", "expectedHostRaw", "readingOrderRaw", "bindings"))
                    val modelId = str(model, "model"); val shape = ints(arr(model, "outputShape"), 18385).map { it.toLong() }.toLongArray()
                    CtcDecoder.validateShape(shape, shape, if (modelId == "ch") 18385 else 504); require(shape[0] == n.toLong())
                    require(str(model, "argmax") == "$id-$modelId-argmax.i32z" && fs.getValue(str(model, "argmax")).decodedBytes?.toLong() == n * shape[1] * 4)
                    val rawText = strings(arr(model, "expectedHostRaw")); val reading = strings(arr(model, "readingOrderRaw")); require(rawText.size == n && reading.size == n)
                    val bindings = objects(arr(model, "bindings")); require(bindings.size == n)
                    // Frozen bindings are in tensor order; rows/readingOrderRaw are in page order.
                    bindings.forEachIndexed { tensorRow, b ->
                        keys(b, setOf("boxId", "originalIndex", "readingOrder", "tensorRow", "raw"))
                        val rank = mapping[tensorRow]
                        require(str(b, "boxId") == str(rows[rank], "boxId") && int(b, "originalIndex") == int(rows[rank], "originalIndex") && int(b, "readingOrder") == rank.toLong())
                        require(int(b, "tensorRow", 0, (n - 1).toLong()) == tensorRow.toLong())
                        require(str(b, "raw") == rawText[tensorRow] && reading[rank] == rawText[tensorRow])
                    }
                }
            }
            require(floatCount == 816048L); return m
        }
        fun intBytes(values: IntArray): ByteArray = ByteBuffer.allocate(values.size * 4).order(ByteOrder.LITTLE_ENDIAN).also { b -> values.forEach { b.putInt(it) } }.array()
    }
}
