package com.kandong.modelprobe

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.ColorSpace
import org.json.JSONArray
import org.json.JSONObject
import java.io.InputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder

internal data class GeometryFile(val bytes: Int, val sha: String, val decodedBytes: Int? = null,
    val decodedSha: String? = null, val width: Int? = null, val height: Int? = null, val bgrSha: String? = null)
internal data class GeometryImage(val width: Int, val height: Int, val bgr: ByteArray, val sha: String)
internal data class GeometryCase(val id: String, val shape: List<Long>, val sourceWidth: Int, val sourceHeight: Int,
    val positives: Int, val dilatedPositives: Int, val contours: Int, val rows: List<GeometryProbeContract.Row>)

/** Only the frozen namespace is accepted. Callbacks are solely for packaged assets and corruption tests. */
internal class GeometryFixtureInputs(private val open: (String) -> InputStream,
    private val list: (String) -> List<String>, private val cleanup: GeometryCleanup) {
    private var files: Map<String, GeometryFile> = emptyMap()
    private var cases: List<GeometryCase> = emptyList()
    private fun read(name: String, expected: Int? = null): ByteArray {
        require(name == "manifest.json" || name in FILE_NAMES) { "GEOMETRY_ASSET_PATH" }
        return open("$NAMESPACE/$name").use { ProbeInputs.bounded(it, if (name == "manifest.json") MANIFEST_CAP else FILE_CAP, expected) }
    }
    fun manifest(): List<GeometryCase> {
        files = emptyMap(); cases = emptyList()
        val raw = read("manifest.json"); ProbeInputs.verifyHash(raw, MANIFEST_SHA)
        val actualNames = list(NAMESPACE)
        require(actualNames.size == 78 && actualNames.toSet() == FILE_NAMES + "manifest.json") { "GEOMETRY_ASSET_SET" }
        val m = JSONObject(String(raw, Charsets.UTF_8))
        require(m.getInt("schema") == 1 && m.getString("parentManifestSha256") == DetectorProbeInputs.MANIFEST_SHA)
        val config = m.getJSONObject("configuration")
        require(config.getDouble("thresh").toFloat() == 0.3f && config.getInt("max_candidates") == 1000 && config.getBoolean("use_dilation"))
        val metadata = m.getJSONObject("files")
        require(metadata.keys().asSequence().toSet() == FILE_NAMES && FILE_NAMES.size == 77)
        val verifiedFiles = FILE_NAMES.associateWith { name ->
            val f = metadata.getJSONObject(name)
            val bytes = f.getLong("bytes"); require(bytes in 1..FILE_CAP.toLong())
            val sha = hash(f.getString("sha256"))
            if (name.endsWith(".png")) {
                val w = dimension(f, "width"); val h = dimension(f, "height")
                GeometryProbeContract.pixels(w, h)
                GeometryFile(bytes.toInt(), sha, width = w, height = h, bgrSha = hash(f.getString("rawBgrSha256")))
            } else {
                val decoded = f.getLong("decodedBytes"); require(decoded in 1..4L * GeometryProbeContract.MAX_PIXELS)
                GeometryFile(bytes.toInt(), sha, decoded.toInt(), hash(f.getString("decodedSha256")))
            }
        }
        val entries = m.getJSONArray("cases"); require(entries.length() == IDS.size)
        val verifiedCases = IDS.mapIndexed { index, id ->
            val c = entries.getJSONObject(index); require(c.getString("id") == id)
            require(c.getString("source") == "$id-source.png" && c.getString("probability") == "$id-probability.f32z" &&
                c.getString("mask") == "$id-mask.u8z" && c.getString("dilatedMask") == "$id-dilated.u8z")
            val s = c.getJSONArray("probabilityShape"); require(s.length() == 4)
            val shape = List(4) { s.getLong(it) }; val pixels = GeometryProbeContract.probabilityPixels(shape.toLongArray())
            require(verifiedFiles.getValue("$id-probability.f32z").decodedBytes?.toLong() == pixels.toLong() * 4)
            for (suffix in listOf("mask", "dilated")) require(verifiedFiles.getValue("$id-$suffix.u8z").decodedBytes == pixels)
            val positives = c.getLong("positivePixels"); val dilatedPositives = c.getLong("dilatedPositivePixels")
            val contours = c.getLong("contourCount")
            require(positives in 0..pixels.toLong() && dilatedPositives in positives..pixels.toLong() && contours in 0..1000)
            require(c.getInt("candidateLimit") == 1000 && !c.getBoolean("truncated"))
            val source = verifiedFiles.getValue("$id-source.png"); val sw = checkNotNull(source.width); val sh = checkNotNull(source.height)
            val rows = c.getJSONArray("boxes"); val before = c.getJSONArray("boxesBeforeSort"); val scores = c.getJSONArray("scoresBeforeSort")
            require(rows.length() == CROP_COUNTS[index] && before.length() == rows.length() && scores.length() == rows.length())
            val parsed = List(rows.length()) { rank ->
                val r = rows.getJSONObject(rank); val original = r.getLong("originalIndex")
                require(original in 0 until rows.length().toLong() && r.getInt("readingOrder") == rank)
                require(r.getString("id") == "$id.box-$original" && r.getString("crop") == cropName(id, rank))
                val q = quad(r.getJSONArray("quad")); require(q == quad(before.getJSONArray(original.toInt())))
                val score = r.getDouble("detectorScore")
                require(score.isFinite() && score in 0.0..1.0 && score == scores.getDouble(original.toInt()))
                val plan = GeometryProbeContract.cropPlan(sw, sh, q)
                require(r.getInt("preRotationWidth") == plan.width && r.getInt("preRotationHeight") == plan.height &&
                    r.getBoolean("rotateCounterClockwise90") == plan.rotate)
                val crop = verifiedFiles.getValue(cropName(id, rank))
                require(crop.width == plan.outputWidth && crop.height == plan.outputHeight)
                val matrixRows = r.getJSONArray("sourceToPreRotationCrop"); require(matrixRows.length() == 3)
                val matrix = List(3) { row -> matrixRows.getJSONArray(row).also { require(it.length() == 3) } }
                    .flatMap { row -> List(3) { row.getDouble(it) } }
                GeometryProbeContract.inverse(matrix.toDoubleArray())
                GeometryProbeContract.homography(q, plan)
                GeometryProbeContract.Row(r.getString("id"), original.toInt(), rank, score, q, plan, matrix, cropName(id, rank))
            }
            require(parsed.map { it.originalIndex }.toSet().size == parsed.size)
            GeometryCase(id, shape, sw, sh, positives.toInt(), dilatedPositives.toInt(), contours.toInt(), parsed)
        }
        require(verifiedCases.sumOf { it.rows.size } == 13)
        // Verify all 77 compressed files now; consumption always re-reads and authenticates its bytes.
        verifiedFiles.forEach { (name, f) -> ProbeInputs.verifyHash(read(name, f.bytes), f.sha) }
        files = verifiedFiles; cases = verifiedCases
        return cases.toList()
    }
    fun metadata(name: String): GeometryFile = files.getValue(name)
    fun asset(name: String): ByteArray {
        require(name in FILE_NAMES && files.isNotEmpty())
        val f = metadata(name)
        return read(name, f.bytes).also { ProbeInputs.verifyHash(it, f.sha) }
    }
    fun decoded(name: String): ByteArray {
        val f = metadata(name)
        val size = checkNotNull(f.decodedBytes)
        // Stricter than DetectorProbeInputs.inflate's generic 12-channel-byte budget.
        val cap = GeometryProbeContract.MAX_PIXELS.toLong() * (if (name.endsWith(".f32z")) 4 else 1)
        require(size.toLong() in 1..cap)
        return DetectorProbeInputs.inflate(asset(name), f.bytes, f.sha, size, checkNotNull(f.decodedSha))
    }
    fun probability(case: GeometryCase): FloatArray {
        require(case in cases)
        val raw = decoded("${case.id}-probability.f32z")
        require(raw.size.toLong() == GeometryProbeContract.probabilityPixels(case.shape.toLongArray()).toLong() * 4)
        return DetectorProbeInputs.floats(raw, 0f, 1f).also { GeometryProbeContract.validateProbability(case.shape.toLongArray(), it) }
    }
    fun mask(case: GeometryCase, dilated: Boolean): ByteArray {
        require(case in cases)
        val raw = decoded("${case.id}-${if (dilated) "dilated" else "mask"}.u8z")
        require(raw.size == GeometryProbeContract.probabilityPixels(case.shape.toLongArray()) && raw.all { it == 0.toByte() || it == 1.toByte() })
        require(raw.count { it == 1.toByte() } == if (dilated) case.dilatedPositives else case.positives)
        return raw
    }
    fun image(name: String): GeometryImage = decodePng(asset(name), metadata(name), cleanup)

    companion object {
        const val NAMESPACE = "detector-geometry-v1"
        const val MANIFEST_SHA = "1aa14f2efb50e62c5a2d95b45b7d5b5044d4ec90afa47a976e8dea50bbe466a6"
        const val MANIFEST_CAP = 128 * 1024
        const val FILE_CAP = 2 * 1024 * 1024
        val IDS = listOf("en-quality-16", "fr-nonrefundable-16", "zh-hans-quality-16", "zh-hant-quality-16", "mixed-quality-16",
            "blank-negative-24", "color-control", "wide-959", "wide-1499", "wide-2001", "threshold-equal", "below-box-score",
            "tiny-negative", "two-blocks", "edge-touching", "vertical")
        val CROP_COUNTS = listOf(1, 1, 2, 2, 3, 0, 0, 0, 0, 0, 0, 0, 0, 2, 1, 1)
        fun cropName(id: String, rank: Int) = "$id-crop-${rank.toString().padStart(2, '0')}.png"
        val FILE_NAMES = IDS.flatMapIndexed { i, id -> listOf("$id-source.png", "$id-probability.f32z", "$id-mask.u8z", "$id-dilated.u8z") +
            List(CROP_COUNTS[i]) { cropName(id, it) } }.toSet()
        private fun hash(value: String): String = value.also { require(it.matches(Regex("[0-9a-f]{64}"))) }
        private fun dimension(json: JSONObject, key: String): Int = json.getLong(key).also {
            require(it in 1..GeometryProbeContract.MAX_DIMENSION.toLong())
        }.toInt()
        private fun quad(array: JSONArray): List<GeometryProbeContract.Point> {
            require(array.length() == 4)
            return List(4) { i -> val p = array.getJSONArray(i); require(p.length() == 2)
                GeometryProbeContract.Point(p.getDouble(0), p.getDouble(1)) }
        }
        /** Official decoder, NOT a PNG decoder implementation. Header/bounds are format/budget prechecks.
         * Bitmap pixel storage is allocated after dimension validation, BEFORE raw BGR authentication.
         * OpenCV Mat allocation may only follow the raw BGR hash check below. */
        fun decodePng(raw: ByteArray, f: GeometryFile, cleanup: GeometryCleanup): GeometryImage {
            require(f.bytes in 1..FILE_CAP && raw.size == f.bytes)
            ProbeInputs.verifyHash(raw, f.sha)
            val w = checkNotNull(f.width); val h = checkNotNull(f.height); val expectedSha = hash(checkNotNull(f.bgrSha))
            val count = GeometryProbeContract.pixels(w, h)
            require(raw.size >= 33 && raw.copyOfRange(0, 8).contentEquals(byteArrayOf(-119, 80, 78, 71, 13, 10, 26, 10)))
            val header = ByteBuffer.wrap(raw).order(ByteOrder.BIG_ENDIAN)
            require(header.getInt(8) == 13 && header.getInt(12) == 0x49484452 && header.getInt(16) == w && header.getInt(20) == h)
            require(raw[24] == 8.toByte() && raw[25] == 2.toByte() && raw[26] == 0.toByte() && raw[27] == 0.toByte() && raw[28] == 0.toByte()) { "GEOMETRY_PNG_RGB8" }
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true; inScaled = false }
            BitmapFactory.decodeByteArray(raw, 0, raw.size, bounds)
            require(bounds.outWidth == w && bounds.outHeight == h && bounds.outMimeType == "image/png")
            val options = BitmapFactory.Options().apply {
                inScaled = false; inPreferredConfig = Bitmap.Config.ARGB_8888
                inPreferredColorSpace = ColorSpace.get(ColorSpace.Named.SRGB)
            }
            val bitmap = checkNotNull(BitmapFactory.decodeByteArray(raw, 0, raw.size, options)) { "GEOMETRY_PNG_DECODE" }
            cleanup.bitmapOpened++
            try {
                require(bitmap.width == w && bitmap.height == h && bitmap.config == Bitmap.Config.ARGB_8888 &&
                    bitmap.colorSpace == ColorSpace.get(ColorSpace.Named.SRGB))
                val argb = IntArray(count); bitmap.getPixels(argb, 0, w, 0, 0, w, h)
                val bgr = GeometryProbeContract.bgr(w, h, argb)
                ProbeInputs.verifyHash(bgr, expectedSha)
                return GeometryImage(w, h, bgr, expectedSha)
            } finally {
                cleanup.bitmapRecycleAttempts++; bitmap.recycle()
                check(bitmap.isRecycled); cleanup.bitmapRecycled++
            }
        }
    }
}
