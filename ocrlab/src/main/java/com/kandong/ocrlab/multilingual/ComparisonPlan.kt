package com.kandong.ocrlab.multilingual

import java.security.MessageDigest

internal data class ComparisonTask(val inputId: String, val sourceKind: String, val scale: Int, val engine: String) {
    val taskId = "$inputId/$sourceKind/${scale}x/$engine"
}

/** No expected text or language participates in scheduling or image processing. */
internal object ComparisonPlan {
    const val MANIFEST_SHA256 = "2341dff63bd10d41867351ddff0e4145daa2d9d3493bc8cdc88ea289c1a076a8"
    const val TOTAL = 144
    const val MAX_PIXELS = 4_000_000
    fun canStart(originalBusy: Boolean, comparisonBusy: Boolean) = !originalBusy && !comparisonBusy
    fun matrix(ids: List<String>): List<ComparisonTask> {
        require(ids.size == 18 && ids.toSet().size == 18) { "input_count_or_identity_invalid" }
        return ids.flatMap { id -> listOf("native", "fixed").flatMap { source ->
            listOf(1, 2).flatMap { scale -> listOf("latin", "chinese").map { engine ->
                ComparisonTask(id, source, scale, engine)
            } }
        } }.also { require(it.size == TOTAL) }
    }
    fun dimensions(width: Int, height: Int) {
        require(width in 1..2048 && height in 1..2048 && width.toLong() * height <= MAX_PIXELS) {
            "image_dimensions_invalid"
        }
    }
    fun sha256(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256").digest(bytes)
        .joinToString("") { "%02x".format(it.toInt() and 255) }
    fun verifyHash(bytes: ByteArray, expected: String) {
        require(expected.matches(Regex("[0-9a-f]{64}")) && sha256(bytes) == expected) { "encoded_hash_mismatch" }
    }
    fun pixelHash(pixels: IntArray): String {
        require(pixels.size <= MAX_PIXELS)
        val digest = MessageDigest.getInstance("SHA-256")
        val rgba = ByteArray(4)
        for (pixel in pixels) {
            require(pixel ushr 24 == 255) { "nonopaque_pixel" }
            rgba[0] = (pixel ushr 16).toByte()
            rgba[1] = (pixel ushr 8).toByte()
            rgba[2] = pixel.toByte()
            rgba[3] = 255.toByte()
            digest.update(rgba)
        }
        return digest.digest().joinToString("") { "%02x".format(it.toInt() and 255) }
    }
    fun verifyPixels(width: Int, height: Int, expectedWidth: Int, expectedHeight: Int,
                     pixels: IntArray, expectedHash: String) {
        dimensions(width, height)
        require(width == expectedWidth && height == expectedHeight && pixels.size == width * height) {
            "decoded_dimensions_mismatch"
        }
        require(pixelHash(pixels) == expectedHash) { "decoded_hash_mismatch" }
    }
    fun doublePixels(pixels: IntArray, width: Int, height: Int): IntArray {
        dimensions(width, height)
        dimensions(width * 2, height * 2)
        require(pixels.size == width * height) { "pixel_count_mismatch" }
        val outputWidth = width * 2
        return IntArray(pixels.size * 4).also { output ->
            for (y in 0 until height) for (x in 0 until width) {
                val value = pixels[y * width + x]
                val index = y * 2 * outputWidth + x * 2
                output[index] = value; output[index + 1] = value
                output[index + outputWidth] = value; output[index + outputWidth + 1] = value
            }
        }
    }
    fun unscale(bounds: IntArray, scaleX: Float, scaleY: Float): FloatArray {
        require(bounds.size == 4 && scaleX.isFinite() && scaleY.isFinite() && scaleX > 0 && scaleY > 0)
        return floatArrayOf(bounds[0] / scaleX, bounds[1] / scaleY, bounds[2] / scaleX, bounds[3] / scaleY)
    }
}

/** Cancellation is tested at the exact scheduling boundary, before touching another source or engine. */
internal class ComparisonCursor(private val tasks: List<ComparisonTask>) {
    var completedCount = 0
        private set
    fun next(canPublish: Boolean): ComparisonTask? = if (canPublish) tasks.getOrNull(completedCount) else null
    fun completed() { check(completedCount < tasks.size); completedCount++ }
}
