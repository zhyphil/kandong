package com.kandong.modelprobe

import ai.onnxruntime.OnnxJavaType
import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtLoggingLevel
import ai.onnxruntime.OrtSession

/** No reference text enters this API. Native creation, run and close stay on one worker. */
class OrtProbeEngine(private val cleanup: Cleanup) {
    private val thread = Thread.currentThread()
    private val environment = OrtEnvironment.getEnvironment(OrtLoggingLevel.ORT_LOGGING_LEVEL_FATAL).also {
        it.setTelemetry(false)
    }
    val runtime: String get() = environment.version
    // ORT 1.30 Java environment.close() is a no-op. This singleton lives for the process;
    // only sessions/options/tensors/results below have per-run native ownership.
    fun <T> withModel(modelBytes: ByteArray, operation: (OrtSession) -> T): T {
        check(Thread.currentThread() === thread)
        val options = OnceResource(OrtSession.SessionOptions(), cleanup)
        try {
            options.value.setSessionLogLevel(OrtLoggingLevel.ORT_LOGGING_LEVEL_FATAL)
            options.value.setExecutionMode(OrtSession.SessionOptions.ExecutionMode.SEQUENTIAL)
            options.value.setIntraOpNumThreads(1)
            options.value.setInterOpNumThreads(1)
            options.value.setOptimizationLevel(OrtSession.SessionOptions.OptLevel.ALL_OPT)
            options.value.setCPUArenaAllocator(false)
            // CPU is the default provider; no optional EP is registered.
            val session = OnceResource(environment.createSession(modelBytes, options.value), cleanup)
            try {
                requireProbe(session.value.inputNames == setOf("x") && session.value.outputNames == setOf("fetch_name_0"), "MODEL_NAMES")
                return operation(session.value)
            } finally { session.close() }
        } finally { options.close() }
    }
    fun infer(session: OrtSession, input: TensorInput, outputShape: LongArray, dictionary: List<String>): Decoded {
        check(Thread.currentThread() === thread)
        val tensor = OnceResource(OnnxTensor.createTensor(environment, input.floats, input.shape), cleanup)
        try {
            val result = OnceResource(session.run(mapOf("x" to tensor.value), setOf("fetch_name_0")), cleanup)
            try {
                val value = result.value.get("fetch_name_0").orElseThrow { ProbeFailure("OUTPUT_MISSING") }
                requireProbe(value is OnnxTensor, "OUTPUT_TYPE")
                val output = value as OnnxTensor
                val info = output.info
                requireProbe(info.type == OnnxJavaType.FLOAT, "OUTPUT_DTYPE")
                val count = CtcDecoder.validateShape(info.shape, outputShape, dictionary.size)
                requireProbe(info.numElements == count.toLong(), "OUTPUT_LENGTH")
                return CtcDecoder.decode(output.floatBuffer, info.shape, outputShape, dictionary)
            } finally { result.close() }
        } finally {
            tensor.close()
            // Explicit reachability through tensor/result close; never recycle on the UI thread.
            java.lang.ref.Reference.reachabilityFence(input)
        }
    }
}
