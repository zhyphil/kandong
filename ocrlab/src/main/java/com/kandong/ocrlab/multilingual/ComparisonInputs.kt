package com.kandong.ocrlab.multilingual

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.ColorSpace
import com.kandong.ocrlab.SyntheticCase
import com.kandong.ocrlab.SyntheticFixtures
import org.json.JSONObject

internal data class ComparisonInput(val case: SyntheticCase, val asset: String, val width: Int,
    val height: Int, val pngSha256: String, val pixelSha256: String, val renderedLines: List<String>) {
    val inputId = "${case.id}-${case.fontPx}"
}
internal data class ComparisonImage(val bitmap: Bitmap, val baseWidth: Int, val baseHeight: Int,
    val basePixelSha256: String, val processedPixelSha256: String, val lineCount: Int) {
    val scaleX: Float get() = bitmap.width.toFloat() / baseWidth
    val scaleY: Float get() = bitmap.height.toFloat() / baseHeight
}
internal object ComparisonInputs {
    private const val DIRECTORY = "trilingual-v1/"
    fun load(context: Context): List<ComparisonInput> {
        val bytes = context.assets.open("${DIRECTORY}manifest.json").use { it.readBytesLimited(128 * 1024) }
        ComparisonPlan.verifyHash(bytes, ComparisonPlan.MANIFEST_SHA256)
        val manifest = JSONObject(bytes.toString(Charsets.UTF_8))
        require(manifest.getInt("schema") == 1 && manifest.getString("fixtureVersion") == "trilingual-v1")
        val rows = manifest.getJSONArray("images")
        require(rows.length() == 18)
        val originals = SyntheticFixtures.load(context)
        return List(rows.length()) { index ->
            val row = rows.getJSONObject(index)
            val id = row.getString("id")
            val size = row.getInt("fontPx")
            val source = row.getString("source")
            require(source.length <= 4096 && size in listOf(16, 24, 32))
            val case = if (index < 7) {
                originals.single { it.id == id && it.fontPx == size }.also {
                    require(it.source == source && it.language == row.getString("language")) { "critical_original_changed" }
                }
            } else SyntheticCase(id, row.getString("language"), source, size)
            val asset = row.getString("asset")
            require(asset == "$id-$size.png" && asset.matches(Regex("[a-z0-9-]+\\.png")))
            val width = row.getInt("width"); val height = row.getInt("height")
            ComparisonPlan.dimensions(width, height)
            ComparisonPlan.dimensions(width * 2, height * 2)
            val lines = row.getJSONArray("renderedLines")
            require(lines.length() <= 64)
            ComparisonInput(case, asset, width, height, row.getString("pngSha256"), row.getString("pixelSha256"),
                List(lines.length()) { lines.getString(it) })
        }.also { ComparisonPlan.matrix(it.map { row -> row.inputId }) }
    }

    /** Only packaged, authenticated names enter this loader. One source is held at a time. */
    fun render(context: Context, input: ComparisonInput, sourceKind: String, scale: Int,
               algorithm: String = if (scale == 1) "identity-v1" else "nearest-neighbor-exact-2x2-v1"): ComparisonImage {
        require((algorithm == "identity-v1" && scale == 1) ||
            (algorithm == "nearest-neighbor-exact-2x2-v1" && scale == 2) ||
            (algorithm == "bilinear-center-integer-v1" && scale in 2..3))
        val base: Bitmap
        val lineCount: Int
        when (sourceKind) {
            "native" -> {
                val rendered = SyntheticFixtures.render(input.case)
                base = rendered.bitmap; lineCount = rendered.lineCount
            }
            "fixed" -> {
                val bytes = context.assets.open(DIRECTORY + input.asset).use { it.readBytesLimited(4 * 1024 * 1024) }
                ComparisonPlan.verifyHash(bytes, input.pngSha256)
                val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true; inScaled = false }
                BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
                require(bounds.outWidth == input.width && bounds.outHeight == input.height) { "png_dimensions_mismatch" }
                ComparisonPlan.dimensions(bounds.outWidth, bounds.outHeight)
                val options = BitmapFactory.Options().apply {
                    inScaled = false; inPreferredConfig = Bitmap.Config.ARGB_8888
                    inPreferredColorSpace = ColorSpace.get(ColorSpace.Named.SRGB)
                }
                base = requireNotNull(BitmapFactory.decodeByteArray(bytes, 0, bytes.size, options)) { "png_decode_failed" }
                base.density = Bitmap.DENSITY_NONE
                lineCount = input.renderedLines.size
            }
            else -> error("source_kind_invalid")
        }
        var processed: Bitmap? = null
        try {
            ComparisonPlan.dimensions(base.width, base.height)
            val pixels = pixels(base)
            if (sourceKind == "fixed") ComparisonPlan.verifyPixels(base.width, base.height, input.width, input.height,
                pixels, input.pixelSha256)
            val baseHash = ComparisonPlan.pixelHash(pixels)
            if (scale == 1) return ComparisonImage(base, base.width, base.height, baseHash, baseHash, lineCount)
            val resized = if (algorithm == "bilinear-center-integer-v1")
                SmoothPixels.resize(pixels, base.width, base.height, scale)
            else ComparisonPlan.doublePixels(pixels, base.width, base.height)
            val output = Bitmap.createBitmap(resized, base.width * scale, base.height * scale, Bitmap.Config.ARGB_8888)
            processed = output
            output.density = Bitmap.DENSITY_NONE
            // Hash actual Bitmap pixels sent to the SDK, not the requested buffer or source text.
            val result = ComparisonImage(output, base.width, base.height, baseHash,
                ComparisonPlan.pixelHash(pixels(output)), lineCount)
            base.recycle()
            return result
        } catch (failure: Throwable) {
            processed?.recycle(); base.recycle(); throw failure
        }
    }
    private fun pixels(bitmap: Bitmap) = IntArray(bitmap.width * bitmap.height).also {
        bitmap.getPixels(it, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height)
    }
    private fun java.io.InputStream.readBytesLimited(limit: Int): ByteArray {
        val output = java.io.ByteArrayOutputStream()
        val buffer = ByteArray(8192)
        while (true) {
            val count = read(buffer)
            if (count < 0) break
            require(output.size() + count <= limit) { "asset_size_limit" }
            output.write(buffer, 0, count)
        }
        return output.toByteArray()
    }
}
