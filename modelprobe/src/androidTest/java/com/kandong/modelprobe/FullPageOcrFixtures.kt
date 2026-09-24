package com.kandong.modelprobe

import android.content.res.AssetManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.ColorSpace
import android.graphics.PixelFormat
import android.media.Image
import android.media.ImageReader
import android.media.ImageWriter
import android.os.SystemClock
import org.json.JSONObject
import java.security.MessageDigest

/** Authenticated old PNG pixels, integer blits into an opaque RGBA ImageWriter page.
 * At most one small bitmap and one row array; never allocate a full-page Bitmap.
 * Placement/text are kept here and in the post-inference grader, never passed to the pipeline.
 */
internal class FullPageOcrFixtures(private val assets: AssetManager, private val cleanup: GeometryCleanup) {
    companion object {
        const val NAMESPACE = "full-page-ocr-v1"
        const val MANIFEST_SHA = "e95fa1bac48e0a32272a65cf4b85679705597b2bbe3ca69997f038fdf26345f1"
        val PNGS = listOf("en-checkin-24.png", "en-tickets-32.png", "fr-departure-24.png", "fr-price-32.png",
            "hans-order-24.png", "hans-luggage-32.png", "hant-order-24.png", "hant-luggage-32.png")
        val PAGE_IDS = listOf("en-normal", "en-seam", "fr-normal", "fr-seam", "hans-normal", "hans-seam",
            "hant-normal", "hant-seam", "en-seam-1080", "blank")
    }
    val transport = linkedMapOf("readersOpened" to 0, "readersClosed" to 0, "writersOpened" to 0, "writersClosed" to 0,
        "writerInputs" to 0, "writerInputsTransferred" to 0, "writerInputsClosed" to 0,
        "imagesAcquired" to 0, "imageCloseAttempts" to 0, "imagesClosed" to 0)
    private fun add(key: String) { transport[key] = transport.getValue(key) + 1 }
    val balanced get() = transport.getValue("readersOpened") == transport.getValue("readersClosed") &&
        transport.getValue("writersOpened") == transport.getValue("writersClosed") &&
        transport.getValue("writerInputs") == transport.getValue("writerInputsTransferred") + transport.getValue("writerInputsClosed") &&
        transport.getValue("imagesAcquired") == transport.getValue("imagesClosed") &&
        transport.getValue("imageCloseAttempts") == transport.getValue("imagesClosed")
    private val manifest: JSONObject
    val pages: List<JSONObject>
    init {
        require(assets.list(NAMESPACE)?.toSet() == (PNGS + "manifest.json").toSet())
        val bytes = assets.open("$NAMESPACE/manifest.json").use { ProbeInputs.bounded(it, 32768) }
        ProbeInputs.verifyHash(bytes, MANIFEST_SHA)
        manifest = JSONObject(String(bytes, Charsets.UTF_8))
        require(manifest.getInt("schema") == 1 && manifest.getString("fixtureVersion") == NAMESPACE)
        require(manifest.getJSONObject("files").keys().asSequence().toSet() == PNGS.toSet())
        val array = manifest.getJSONArray("pages")
        pages = List(array.length()) { array.getJSONObject(it) }
        require(pages.map { it.getString("id") } == PAGE_IDS)
        // Authenticate all whitelisted originals BEFORE any inference, without keeping their bytes.
        PNGS.forEach { raw(it).fill(0) }
        pages.forEach { page ->
            val w = page.getInt("width"); val h = page.getInt("height")
            val strips = FullPageStripPlanner.plan(w, h).strips
            require(w == if (page.getString("id") == "en-seam-1080") 1080 else 1176)
            require(h == 2400 && strips.size == if (w == 1080) 3 else 4)
            val placements = page.getJSONArray("placements")
            val firstStripLines = (0 until placements.length()).sumOf { i ->
                val p = placements.getJSONObject(i); val f = file(p.getString("asset"))
                require(f.getString("language") == page.getString("language"))
                require(p.getInt("left") >= 0 && p.getInt("top") >= 0 && p.getInt("left") + f.getInt("width") <= w &&
                    p.getInt("top") + f.getInt("height") <= h)
                val lines = f.getJSONArray("lines")
                (0 until lines.length()).count { j ->
                    val g = lines.getJSONObject(j).getJSONArray("glyph")
                    p.getInt("top") + g.getInt(1) >= strips[0].read.top && p.getInt("top") + g.getInt(3) <= strips[0].read.bottom
                }
            }
            require(if (page.getString("id") == "blank") placements.length() == 0 else firstStripLines > 3)
            for (i in 0 until placements.length()) {
                val p = placements.getJSONObject(i); val label = p.getString("label")
                if (!label.endsWith("boundary")) continue
                val g = file(p.getString("asset")).getJSONArray("lines").getJSONObject(0).getJSONArray("glyph")
                val top = p.getInt("top") + g.getInt(1); val bottom = p.getInt("top") + g.getInt(3)
                val boundaries = when (label) {
                    "core-boundary" -> strips.drop(1).map { it.core.top }
                    "read-top-boundary" -> strips.drop(1).map { it.read.top }
                    else -> strips.dropLast(1).map { it.read.bottom }
                }
                require(boundaries.any { it > top && it < bottom }) { "SEAM_MUST_CROSS_GLYPHS" }
            }
        }
    }
    fun file(name: String): JSONObject { require(name in PNGS); return manifest.getJSONObject("files").getJSONObject(name) }
    private fun raw(name: String): ByteArray {
        val f = file(name)
        return assets.open("$NAMESPACE/$name").use { ProbeInputs.bounded(it, 65536, f.getInt("bytes")) }
            .also { ProbeInputs.verifyHash(it, f.getString("sha256")) }
    }

    fun <T> withFrame(page: JSONObject, meta: RgbaFrameMetadata, consume: (RgbaFrameLease) -> T): T {
        require(page in pages && meta.width == page.getInt("width") && meta.height == page.getInt("height"))
        val reader = ImageReader.newInstance(meta.width, meta.height, PixelFormat.RGBA_8888, 2); add("readersOpened")
        try {
            val writer = ImageWriter.newInstance(reader.surface, 2, PixelFormat.RGBA_8888); add("writersOpened")
            try {
                val input = writer.dequeueInputImage(); add("writerInputs")
                var queued = false
                try {
                    fill(input, page)
                    input.timestamp = meta.version.snapshot // Synthetic identity, never a freshness claim.
                    writer.queueInputImage(input); queued = true; add("writerInputsTransferred")
                } finally { if (!queued) { input.close(); add("writerInputsClosed") } }
                val until = SystemClock.elapsedRealtime() + 3000
                var image: Image? = null
                while (image == null && SystemClock.elapsedRealtime() < until) {
                    image = reader.acquireNextImage()
                    if (image == null) SystemClock.sleep(5)
                }
                val output = checkNotNull(image) { "FIXTURE_IMAGE_TIMEOUT" }; add("imagesAcquired")
                val lease = AndroidRgbaFrameLease(output, meta)
                var closed = false
                val counted = object : RgbaFrameLease {
                    override val metadata get() = lease.metadata
                    override fun plane() = lease.plane()
                    override fun close() {
                        if (closed) return
                        closed = true; add("imageCloseAttempts"); lease.close(); add("imagesClosed")
                    }
                }
                try {
                    check(output.timestamp == meta.version.snapshot)
                    return consume(counted)
                } finally { counted.close() }
            } finally { writer.close(); add("writersClosed") }
        } finally { reader.close(); add("readersClosed") }
    }

    private fun fill(image: Image, page: JSONObject) {
        val width = page.getInt("width"); val height = page.getInt("height")
        require(image.width == width && image.height == height && image.format == PixelFormat.RGBA_8888)
        val plane = image.planes.single(); val bytes = plane.buffer; val base = bytes.position()
        val stride = plane.pixelStride; val rowStride = plane.rowStride
        val rowBytes = (width - 1L) * stride + 4; val span = (height - 1L) * rowStride + rowBytes
        require(stride in 4..16 && rowStride >= rowBytes && span <= bytes.remaining() &&
            bytes.remaining() <= SingleFrameStripInput.MAX_PLANE_BYTES)
        for (y in 0 until height) for (x in 0 until width) {
            val offset = base + y * rowStride + x * stride
            for (channel in 0..3) bytes.put(offset + channel, 255.toByte())
        }
        val placements = page.getJSONArray("placements")
        for (i in 0 until placements.length()) {
            val p = placements.getJSONObject(i); val name = p.getString("asset"); val f = file(name)
            val raw = raw(name)
            val bitmap = try {
                val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true; inScaled = false }
                BitmapFactory.decodeByteArray(raw, 0, raw.size, bounds)
                require(bounds.outWidth == f.getInt("width") && bounds.outHeight == f.getInt("height") && bounds.outMimeType == "image/png")
                checkNotNull(BitmapFactory.decodeByteArray(raw, 0, raw.size, BitmapFactory.Options().apply {
                    inScaled = false; inPreferredConfig = Bitmap.Config.ARGB_8888
                    inPreferredColorSpace = ColorSpace.get(ColorSpace.Named.SRGB)
                }))
            } finally { raw.fill(0) }
            cleanup.bitmapOpened++
            try {
                require(bitmap.width == f.getInt("width") && bitmap.height == f.getInt("height") &&
                    bitmap.config == Bitmap.Config.ARGB_8888 && bitmap.colorSpace == ColorSpace.get(ColorSpace.Named.SRGB))
                val row = IntArray(bitmap.width); val rgba = ByteArray(bitmap.width * 4)
                try {
                    val digest = MessageDigest.getInstance("SHA-256")
                    for (y in 0 until bitmap.height) {
                        bitmap.getPixels(row, 0, bitmap.width, 0, y, bitmap.width, 1)
                        for (x in row.indices) {
                            val color = row[x]; require(color ushr 24 == 255)
                            rgba[x * 4] = (color ushr 16).toByte(); rgba[x * 4 + 1] = (color ushr 8).toByte()
                            rgba[x * 4 + 2] = color.toByte(); rgba[x * 4 + 3] = 255.toByte()
                            val offset = base + (p.getInt("top") + y) * rowStride + (p.getInt("left") + x) * stride
                            for (c in 0..3) bytes.put(offset + c, rgba[x * 4 + c])
                        }
                        digest.update(rgba)
                    }
                    check(ProbeInputs.hex(digest.digest()) == f.getString("rgbaSha256")) { "FIXTURE_PIXEL_HASH" }
                } finally { row.fill(0); rgba.fill(0) }
            } finally { cleanup.bitmapRecycleAttempts++; bitmap.recycle(); check(bitmap.isRecycled); cleanup.bitmapRecycled++ }
        }
    }
}
