package com.kandong.liveocr

import org.json.JSONArray
import java.io.InputStream
import java.security.MessageDigest

internal data class OcrModel(val id: String, val asset: String, val sha: String, val bytes: Int,
    val dictionaryAsset: String, val dictionarySha: String, val dictionaryBytes: Int, val vocabulary: Int)

/** Only authenticated weights/dictionaries. The experiment task manifest is never packaged/read. */
internal class OcrAssets(private val open: (String) -> InputStream) {
    fun model(model: OcrModel): ByteArray {
        requireOcr(model in MODELS, OcrFailure.ASSET_HASH)
        return read(model.asset, model.bytes, model.sha)
    }
    fun detector() = read(DETECTOR_ASSET, DETECTOR_BYTES, DETECTOR_SHA)
    fun dictionary(model: OcrModel): List<String> {
        requireOcr(model in MODELS, OcrFailure.ASSET_HASH)
        val raw = read(model.dictionaryAsset, model.dictionaryBytes, model.dictionarySha)
        return try {
            // Hash/length validation precedes all JSON parsing; never normalize dictionary tokens.
            val json = JSONArray(String(raw, Charsets.UTF_8))
            requireOcr(json.length() == model.vocabulary, OcrFailure.DICTIONARY)
            List(json.length()) { json.getString(it) }.also {
                requireOcr(it.first() == "blank" && it.last() == " ", OcrFailure.DICTIONARY)
            }
        } finally { raw.fill(0) }
    }
    private fun read(path: String, expected: Int, sha: String): ByteArray {
        requireOcr(expected in 1..20 * 1024 * 1024, OcrFailure.ASSET_LENGTH)
        val raw = ByteArray(expected)
        try {
            val stream = try { open(path) } catch (_: Exception) { throw LiveOcrException(OcrFailure.ASSET_MISSING) }
            stream.use { input ->
                var count = 0
                while (count < expected) {
                    val n = input.read(raw, count, expected - count)
                    requireOcr(n > 0, OcrFailure.ASSET_LENGTH)
                    count += n
                }
                requireOcr(input.read() == -1, OcrFailure.ASSET_LENGTH)
            }
            requireOcr(hash(raw) == sha, OcrFailure.ASSET_HASH)
            return raw
        } catch (e: Throwable) { raw.fill(0); throw e }
    }
    companion object {
        const val DETECTOR_ASSET = "liveocr/models/ch_PP-OCRv5_det_mobile.onnx"
        const val DETECTOR_BYTES = 4819576
        const val DETECTOR_SHA = "4d97c44a20d30a81aad087d6a396b08f786c4635742afc391f6621f5c6ae78ae"
        val MODELS = listOf(
        OcrModel("ch", "liveocr/models/ch_PP-OCRv5_rec_mobile.onnx", "5825fc7ebf84ae7a412be049820b4d86d77620f204a041697b0494669b1742c5", 16631306,
            "liveocr/probes/ch-dictionary.json", "72ad5c46d5bbc22921613539feaa90e2c4c4567bfb107a7e741be72e5e54a1af", 110794, 18385),
        OcrModel("latin", "liveocr/models/latin_PP-OCRv5_rec_mobile.onnx", "b20bd37c168a570f583afbc8cd7925603890efbcdc000a59e22c269d160b5f5a", 7904513,
            "liveocr/probes/latin-dictionary.json", "d80d308e9759b3ea3aaf6e57fa51b39ef0a2eeb3145a9b23df819f0f0279af44", 2654, 504))
        fun forLanguage(language: String): OcrModel = when (language) {
            "EN", "FR" -> MODELS[1]
            "ZH-HANS", "ZH-HANT" -> MODELS[0]
            else -> throw LiveOcrException(OcrFailure.UNSUPPORTED_LANGUAGE)
        }
        fun hash(raw: ByteArray): String = MessageDigest.getInstance("SHA-256").digest(raw)
            .joinToString("") { "%02x".format(it) }
        fun product(shape: LongArray, cap: Long): Long {
            requireOcr(shape.isNotEmpty(), OcrFailure.SHAPE_CAP)
            var count = 1L
            shape.forEach { d ->
                requireOcr(d > 0 && d <= cap / count, OcrFailure.SHAPE_CAP)
                count *= d
            }
            return count
        }
    }
}
