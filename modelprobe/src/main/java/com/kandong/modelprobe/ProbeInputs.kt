package com.kandong.modelprobe

import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import java.security.MessageDigest
import java.util.zip.GZIPInputStream

data class ProbeModel(val id: String, val asset: String, val sha: String, val bytes: Int,
    val dictionaryAsset: String, val dictionarySha: String, val vocabulary: Int)
data class ProbeTask(val id: String, val source: String, val sourceSha: String, val model: String,
    val asset: String, val compressedSha: String, val tensorSha: String, val compressedBytes: Int,
    val tensorBytes: Int, val shape: LongArray, val outputShape: LongArray,
    val expectedRaw: List<String>, val expectedArgmax: String, val readingOrder: List<String>)
data class ProbeManifest(val models: List<ProbeModel>, val tasks: List<ProbeTask>)
data class TensorInput(val storage: ByteBuffer, val floats: FloatBuffer, val shape: LongArray)

/** All data comes from fixed packaged assets; no file/Intent/URI entrypoint. */
class ProbeInputs(private val open: (String) -> InputStream) {
    private fun bytes(path: String, cap: Int, expected: Int? = null): ByteArray {
        validatePath(path)
        return open(path).use { bounded(it, cap, expected) }
    }
    fun manifest(): ProbeManifest {
        val raw = bytes("probes/manifest.json", 128 * 1024)
        verifyHash(raw, MANIFEST_SHA) // Must precede JSON parsing.
        val json = JSONObject(String(raw, Charsets.UTF_8))
        requireProbe(json.getInt("schema") == 1 && json.getString("fixtureVersion") == "ppocr-mobile-tensor-v1", "MANIFEST_SCHEMA")
        val models = json.getJSONArray("models").objects().map { m ->
            requireProbe(m.getString("inputName") == "x" && m.getString("outputName") == "fetch_name_0", "MODEL_NAMES")
            ProbeModel(m.getString("id"), m.getString("asset"), m.getString("sha256"), m.getInt("bytes"),
                m.getString("dictionaryAsset"), m.getString("dictionarySha256"), m.getInt("vocabularySize"))
        }
        requireProbe(models.map { it.id } == listOf("ch", "latin"), "MODEL_MATRIX")
        models.forEach { m ->
            val chinese = m.id == "ch"
            requireProbe(m.asset == "models/${m.id}_PP-OCRv5_rec_mobile.onnx" &&
                m.dictionaryAsset == "probes/${m.id}-dictionary.json" &&
                m.bytes == if (chinese) 16631306 else 7904513, "MODEL_SPEC")
            requireProbe(m.vocabulary == if (chinese) 18385 else 504, "MODEL_VOCABULARY")
            validatePath(m.asset); validatePath(m.dictionaryAsset)
        }
        val tasks = json.getJSONArray("tasks").objects().map { t ->
            requireProbe(t.getString("dtype") == "float32LE", "INPUT_DTYPE")
            ProbeTask(t.getString("id"), t.getString("sourceInputId"), t.getString("sourcePixelSha256"),
                t.getString("model"), t.getString("asset"), t.getString("compressedSha256"), t.getString("tensorSha256"),
                t.getInt("compressedBytes"), t.getInt("tensorBytes"), t.getJSONArray("shape").longs(),
                t.getJSONArray("outputShape").longs(), t.getJSONArray("expectedHostRaw").strings(),
                t.getString("expectedHostArgmaxSha256"), t.getJSONArray("hostPipelineLinesReadingOrder").strings())
        }
        requireProbe(tasks.map { it.id } == models.flatMap { m -> SOURCES.map { "${m.id}/$it" } }, "TASK_MATRIX")
        tasks.forEach { t ->
            requireProbe(t.id == "${t.model}/${t.source}" && t.asset == "probes/${t.model}-${t.source}.f32z", "TASK_PATH")
            validatePath(t.asset)
            requireProbe(inputBytes(t.shape) == t.tensorBytes && t.compressedBytes in 1..MAX_GZIP, "INPUT_LENGTH")
            CtcDecoder.validateShape(t.outputShape, t.outputShape, models.first { it.id == t.model }.vocabulary)
            requireProbe(t.outputShape[0] == t.shape[0] && t.expectedRaw.size.toLong() == t.shape[0], "TASK_BATCH")
        }
        // Same five source tensors, including mixed/Hans/Hant, for BOTH recognizers.
        SOURCES.forEach { source ->
            val pair = tasks.filter { it.source == source }
            requireProbe(pair.size == 2 && pair[0].tensorSha == pair[1].tensorSha &&
                pair[0].shape.contentEquals(pair[1].shape) && pair[0].sourceSha == pair[1].sourceSha, "SOURCE_MATRIX")
        }
        return ProbeManifest(models, tasks)
    }
    fun model(model: ProbeModel): ByteArray = bytes(model.asset, 20 * 1024 * 1024, model.bytes).also { verifyHash(it, model.sha) }
    fun dictionary(model: ProbeModel): List<String> {
        val raw = bytes(model.dictionaryAsset, 256 * 1024)
        verifyHash(raw, model.dictionarySha)
        val dictionary = JSONArray(String(raw, Charsets.UTF_8)).strings()
        requireProbe(dictionary.size == model.vocabulary && dictionary.first() == "blank" && dictionary.last() == " ", "DICTIONARY")
        return dictionary // Never trim, normalize or split tokens.
    }
    fun tensor(task: ProbeTask): TensorInput {
        val compressed = bytes(task.asset, MAX_GZIP, task.compressedBytes)
        val raw = inflate(compressed, task.shape, task.compressedSha, task.tensorSha)
        val storage = ByteBuffer.allocateDirect(raw.size).order(ByteOrder.nativeOrder())
        val floats = storage.asFloatBuffer()
        floats.put(ByteBuffer.wrap(raw).order(ByteOrder.LITTLE_ENDIAN).asFloatBuffer()).flip()
        return TensorInput(storage, floats, task.shape.copyOf())
    }
    companion object {
        const val MANIFEST_SHA = "126d8d3860d5a4ea098f0875838dbed51a55d8a321f50d409805d394a460061c"
        const val MAX_GZIP = 5 * 1024 * 1024
        val SOURCES = listOf("en-quality-16", "fr-nonrefundable-16", "zh-hans-quality-16", "zh-hant-quality-16", "mixed-quality-16")
        private val allowed = setOf("probes/manifest.json") + listOf("ch", "latin").flatMap { m ->
            listOf("models/${m}_PP-OCRv5_rec_mobile.onnx", "probes/$m-dictionary.json") + SOURCES.map { "probes/$m-$it.f32z" }
        }
        fun validatePath(path: String) = requireProbe(path in allowed, "ASSET_PATH")
        fun product(shape: LongArray, cap: Long): Long {
            requireProbe(shape.isNotEmpty(), "SHAPE")
            var count = 1L
            shape.forEach { d ->
                requireProbe(d > 0 && d <= cap / count, "SHAPE_CAP")
                count *= d
            }
            return count
        }
        fun inputBytes(shape: LongArray): Int {
            requireProbe(shape.size == 4 && shape[0] in 1..3 && shape[1] == 3L && shape[2] == 48L && shape[3] in 1..848, "INPUT_SHAPE")
            return (product(shape, 1_200_000) * 4).toInt()
        }
        fun bounded(input: InputStream, cap: Int, expected: Int? = null): ByteArray {
            requireProbe(cap > 0 && (expected == null || expected in 0..cap), "BYTE_CAP")
            val output = ByteArrayOutputStream(minOf(expected ?: 8192, cap))
            val buffer = ByteArray(8192)
            var count = 0L
            while (true) {
                val n = input.read(buffer, 0, minOf(buffer.size.toLong(), cap.toLong() - count + 1).toInt())
                if (n < 0) break
                requireProbe(n > 0, "ASSET_READ")
                count += n
                requireProbe(count <= cap && (expected == null || count <= expected), "BYTE_CAP")
                output.write(buffer, 0, n)
            }
            requireProbe(expected == null || count == expected.toLong(), "BYTE_LENGTH")
            return output.toByteArray()
        }
        fun inflate(compressed: ByteArray, shape: LongArray, compressedSha: String, rawSha: String): ByteArray {
            requireProbe(compressed.size in 1..MAX_GZIP, "GZIP_CAP")
            verifyHash(compressed, compressedSha)
            val expected = inputBytes(shape)
            val raw = try {
                GZIPInputStream(ByteArrayInputStream(compressed)).use { bounded(it, expected, expected) }
            } catch (e: ProbeFailure) { throw e } catch (_: Exception) { throw ProbeFailure("GZIP_INVALID") }
            verifyHash(raw, rawSha)
            validateFloats(raw)
            return raw
        }
        fun validateFloats(raw: ByteArray) {
            requireProbe(raw.size % 4 == 0, "FLOAT_BYTES")
            val floats = ByteBuffer.wrap(raw).order(ByteOrder.LITTLE_ENDIAN).asFloatBuffer()
            while (floats.hasRemaining()) {
                val f = floats.get()
                requireProbe(f.isFinite() && f in -1f..1f, "INPUT_FLOAT")
            }
        }
        fun sha(raw: ByteArray): String = hex(MessageDigest.getInstance("SHA-256").digest(raw))
        fun hex(raw: ByteArray): String = raw.joinToString("") { "%02x".format(it) }
        fun verifyHash(raw: ByteArray, expected: String) = requireProbe(sha(raw) == expected, "ASSET_HASH")
        private fun JSONArray.objects() = List(length()) { getJSONObject(it) }
        private fun JSONArray.strings() = List(length()) { getString(it) }
        private fun JSONArray.longs() = LongArray(length()) { getLong(it) }
    }
}
