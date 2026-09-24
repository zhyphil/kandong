package com.kandong.modelprobe

import ai.onnxruntime.OnnxJavaType
import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtLoggingLevel
import ai.onnxruntime.OrtSession
import android.os.Looper
import android.util.AtomicFile
import org.json.JSONObject
import org.junit.Assert.*
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer

/** Shared test-only tensor ownership, comparison reporting and atomic evidence. */
internal abstract class DetectorProbeTestSupport {
    protected fun serialized(values: FloatArray) = ByteBuffer.allocate(values.size * 4).order(ByteOrder.LITTLE_ENDIAN)
        .also { b -> values.forEach { b.putFloat(it) } }.array()
    protected fun floatHash(values: FloatBuffer): String {
        // Hash in LE independently of device byte order, without retaining an output array.
        val digest = java.security.MessageDigest.getInstance("SHA-256")
        val view = values.duplicate(); val word = ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN)
        while (view.hasRemaining()) { word.clear(); word.putInt(view.get().toRawBits()); digest.update(word.array()) }
        return ProbeInputs.hex(digest.digest())
    }
    protected fun tensor(values: FloatArray, shape: LongArray): TensorInput {
        require(values.size == DetectorPacking.elements(shape, 3))
        require(values.all { it.isFinite() && it in -1f..1f })
        val storage = ByteBuffer.allocateDirect(values.size * 4).order(ByteOrder.nativeOrder())
        val floats = storage.asFloatBuffer(); floats.put(values).flip()
        return TensorInput(storage, floats, shape.copyOf())
    }

    protected fun <T> infer(session: OrtSession, input: TensorInput, expectedShape: LongArray, cleanup: Cleanup,
        consume: (FloatBuffer, LongArray) -> T): T {
        check(Looper.myLooper() != Looper.getMainLooper())
        val environment = OrtEnvironment.getEnvironment(OrtLoggingLevel.ORT_LOGGING_LEVEL_FATAL)
        environment.setTelemetry(false)
        // Existing ORT Java singleton environment.close() is a no-op; per-run resources use OnceResource.
        val value = OnceResource(OnnxTensor.createTensor(environment, input.floats, input.shape), cleanup)
        try {
            val result = OnceResource(session.run(mapOf("x" to value.value), setOf("fetch_name_0")), cleanup)
            try {
                val output = result.value.get("fetch_name_0").orElseThrow { ProbeFailure("DETECTOR_OUTPUT_MISSING") }
                require(output is OnnxTensor) { "DETECTOR_OUTPUT_TYPE" }
                val info = output.info
                require(info.type == OnnxJavaType.FLOAT) { "DETECTOR_OUTPUT_DTYPE" }
                val count = DetectorPacking.elements(info.shape, 1)
                require(info.shape.contentEquals(expectedShape) && info.numElements == count.toLong()) { "DETECTOR_OUTPUT_SHAPE" }
                val buffer = output.floatBuffer
                require(buffer.remaining() == count) { "DETECTOR_OUTPUT_LENGTH" }
                return consume(buffer, info.shape)
            } finally { result.close() }
        } finally {
            value.close()
            java.lang.ref.Reference.reachabilityFence(input) // Keep direct storage alive through BOTH closes.
        }
    }

    private fun JSONObject.number(key: String, value: Double): JSONObject =
        put(key, if (value.isFinite()) value else "Infinity")
    protected fun metrics(m: DetectorComparison.Metrics): JSONObject = JSONObject()
        .put("comparedProbabilities", m.count).put("changedProbabilityBits", m.bitDifferences)
        .number("maxAbsoluteError", m.maxAbsolute).number("meanAbsoluteError", m.meanAbsolute)
        .number("maxRelativeError", m.maxRelative).put("relativeErrorDefinition", "abs(actual-reference)/abs(reference); nonzero/0=Infinity")
        .put("toleranceViolations", m.toleranceViolations).put("nonFinite", m.nonFinite).put("outOfRange", m.outOfRange)
        .put("maskSha256", m.maskSha256).put("positivePixels", m.positivePixels).put("maskFlips", m.maskFlips)
        .put("nearThresholdActual", m.nearThresholdActual).put("nearThresholdReference", m.nearThresholdReference)
        .put("numericParityPassed", m.passed)

    protected fun save(atomic: AtomicFile, report: JSONObject) {
        val bytes = report.toString().toByteArray(Charsets.UTF_8)
        require(bytes.size <= 256 * 1024)
        val stream = atomic.startWrite()
        try { stream.write(bytes); atomic.finishWrite(stream) }
        catch (e: Throwable) { atomic.failWrite(stream); throw e }
        assertArrayEquals(bytes, atomic.openRead().use { ProbeInputs.bounded(it, 256 * 1024, bytes.size) })
    }

}
