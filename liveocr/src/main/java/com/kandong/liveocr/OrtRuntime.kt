package com.kandong.liveocr

import ai.onnxruntime.OnnxJavaType
import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtLoggingLevel
import ai.onnxruntime.OrtSession
import java.nio.ByteBuffer
import java.nio.ByteOrder

/** Curated OrtProbeEngine and DetectorProbeTestSupport tensor/infer methods only. */
internal class OrtRuntime(private val cleanup: Cleanup, private val current: OcrCurrent) {
    private val thread = Thread.currentThread()
    private val environment = OrtEnvironment.getEnvironment(OrtLoggingLevel.ORT_LOGGING_LEVEL_FATAL).also {
        it.setTelemetry(false)
        requireOcr(it.version == "1.30.0", OcrFailure.RUNTIME_VERSION)
    }

    private fun ready() {
        check(Thread.currentThread() === thread)
        current.check()
        requireOcr(!cleanup.uncertain, OcrFailure.CLEANUP_UNCERTAIN)
    }

    // ORT's Java environment is a process singleton; close() is a no-op. Only sessions,
    // options, tensors and results below have per-call native ownership.
    fun <T> withModel(modelBytes: ByteArray, operation: (OrtSession) -> T): T {
        ready()
        val options = OnceResource(OrtSession.SessionOptions(), cleanup)
        try {
            options.value.setSessionLogLevel(OrtLoggingLevel.ORT_LOGGING_LEVEL_FATAL)
            options.value.setExecutionMode(OrtSession.SessionOptions.ExecutionMode.SEQUENTIAL)
            options.value.setIntraOpNumThreads(1)
            options.value.setInterOpNumThreads(1)
            options.value.setOptimizationLevel(OrtSession.SessionOptions.OptLevel.ALL_OPT)
            options.value.setCPUArenaAllocator(false)
            ready()
            // CPU is the default provider. No optional execution provider is registered.
            val session = OnceResource(environment.createSession(modelBytes, options.value), cleanup)
            try {
                ready()
                requireOcr(session.value.inputNames == setOf("x") &&
                    session.value.outputNames == setOf("fetch_name_0"), OcrFailure.MODEL_NAMES)
                return operation(session.value)
            } finally { session.close() }
        } finally { options.close() }
    }

    fun recognize(session: OrtSession, input: TensorInput, shape: LongArray, dictionary: List<String>): Decoded {
        ready()
        val tensor = OnceResource(OnnxTensor.createTensor(environment, input.floats, input.shape), cleanup)
        try {
            ready()
            val result = OnceResource(session.run(mapOf("x" to tensor.value), setOf("fetch_name_0")), cleanup)
            try {
                ready() // Reject stale results before reading or decoding any native output.
                val value = result.value.get("fetch_name_0").orElseThrow { LiveOcrException(OcrFailure.OUTPUT_MISSING) }
                requireOcr(value is OnnxTensor, OcrFailure.OUTPUT_TYPE)
                val output = value as OnnxTensor
                val info = output.info
                requireOcr(info.type == OnnxJavaType.FLOAT, OcrFailure.OUTPUT_DTYPE)
                val count = CtcDecoder.validateShape(info.shape, shape, dictionary.size)
                requireOcr(info.numElements == count.toLong(), OcrFailure.OUTPUT_LENGTH)
                return CtcDecoder.decode(output.floatBuffer, info.shape, shape, dictionary).also { ready() }
            } finally { result.close() }
        } finally {
            tensor.close()
            java.lang.ref.Reference.reachabilityFence(input)
        }
    }

    fun detect(session: OrtSession, input: TensorInput, expectedShape: LongArray): FloatArray {
        ready()
        val tensor = OnceResource(OnnxTensor.createTensor(environment, input.floats, input.shape), cleanup)
        var copy: FloatArray? = null
        try {
            ready()
            val result = OnceResource(session.run(mapOf("x" to tensor.value), setOf("fetch_name_0")), cleanup)
            try {
                ready()
                val value = result.value.get("fetch_name_0").orElseThrow { LiveOcrException(OcrFailure.OUTPUT_MISSING) }
                requireOcr(value is OnnxTensor, OcrFailure.OUTPUT_TYPE)
                val output = value as OnnxTensor
                val info = output.info
                requireOcr(info.type == OnnxJavaType.FLOAT, OcrFailure.OUTPUT_DTYPE)
                val count = DetectorPacking.elements(info.shape, 1)
                requireOcr(info.shape.contentEquals(expectedShape) && info.numElements == count.toLong(), OcrFailure.OUTPUT_SHAPE)
                val buffer = output.floatBuffer
                requireOcr(buffer.remaining() == count && count <= DetectorPacking.MAX_PIXELS, OcrFailure.OUTPUT_LENGTH)
                copy = FloatArray(count).also { buffer.duplicate().get(it) }
                ready()
            } finally { result.close() }
            ready()
            return checkNotNull(copy)
        } catch (e: Throwable) { copy?.fill(0f); throw e }
        finally {
            tensor.close()
            java.lang.ref.Reference.reachabilityFence(input)
        }
    }

    companion object {
        fun tensor(values: FloatArray, shape: LongArray): TensorInput {
            requireOcr(values.size.toLong() == OcrAssets.product(shape, 3L * DetectorPacking.MAX_PIXELS) &&
                values.all { it.isFinite() && it in -1f..1f }, OcrFailure.INVALID_INPUT)
            val storage = ByteBuffer.allocateDirect(values.size * 4).order(ByteOrder.nativeOrder())
            try {
                val floats = storage.asFloatBuffer()
                floats.put(values).flip()
                return TensorInput(storage, floats, shape.copyOf())
            } catch (e: Throwable) {
                for (i in 0 until storage.capacity()) storage.put(i, 0.toByte())
                throw e
            }
        }
    }
}
