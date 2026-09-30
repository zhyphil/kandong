package com.kandong.liveocr

import org.opencv.core.Mat
import java.nio.ByteBuffer
import java.nio.FloatBuffer

internal class TensorInput(val storage: ByteBuffer, val floats: FloatBuffer, val shape: LongArray) {
    fun wipe() { for (i in 0 until storage.capacity()) storage.put(i, 0.toByte()) }
}

/** Curated ProbeGate ownership: a failed close is recorded and never retried. */
internal class OnceResource<T : AutoCloseable>(val value: T, private val cleanup: Cleanup) : AutoCloseable {
    private val owner = Thread.currentThread()
    private var closed = false
    init { cleanup.opened++ }
    override fun close() {
        check(Thread.currentThread() === owner)
        if (closed) return
        closed = true
        cleanup.closeAttempts++
        try { value.close(); cleanup.closed++ } catch (_: Throwable) { cleanup.uncertain = true }
    }
}

internal class Cleanup {
    var opened = 0
    var closeAttempts = 0
    var closed = 0
    var uncertain = false
    val balanced get() = !uncertain && opened == closeAttempts && opened == closed
}

internal class GeometryCleanup {
    val acquired = linkedMapOf<String, Int>()
    val attempts = linkedMapOf<String, Int>()
    val released = linkedMapOf<String, Int>()
    var findContoursCalls = 0
    val balanced get() = acquired == attempts && acquired == released
}

internal class OwnedMats(private val cleanup: GeometryCleanup) : AutoCloseable {
    private val owned = ArrayList<Pair<String, Mat>>()
    fun <T : Mat> own(kind: String, mat: T): T {
        owned += kind to mat
        cleanup.acquired[kind] = (cleanup.acquired[kind] ?: 0) + 1
        return mat
    }
    override fun close() {
        var failed = false
        for ((kind, mat) in owned.asReversed()) {
            cleanup.attempts[kind] = (cleanup.attempts[kind] ?: 0) + 1
            try {
                mat.release()
                check(mat.empty())
                cleanup.released[kind] = (cleanup.released[kind] ?: 0) + 1
            } catch (_: Throwable) { failed = true }
        }
        owned.clear()
        requireOcr(!failed, OcrFailure.CLEANUP_UNCERTAIN)
    }
}
