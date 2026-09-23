package com.kandong.qualitylab

import android.graphics.Bitmap
import android.opengl.EGL14 as EGL
import android.opengl.EGLConfig
import android.opengl.EGLContext
import android.opengl.EGLDisplay
import android.opengl.EGLSurface
import android.opengl.GLES30 as GL
import java.nio.ByteBuffer

/**
 * Reusable GLES 3.0 C renderer. Construct, prepare, render and close on ONE owner thread.
 * No capture, fallback, shared contexts or floating-point render targets. Errors close on that
 * same thread, including partial construction. The caller must also use finally/use for cancellation.
 */
internal class GpuC : AutoCloseable {
    class Input private constructor(val width: Int, val height: Int, val count: Int,
        internal val rgba: ByteBuffer, val packingMs: Double) {
        companion object {
            /** Snapshot ARGB integers explicitly into RGBA bytes, independent of native byte order. */
            fun pack(pixels: IntArray, width: Int, height: Int): Input {
                GpuLabChecks.shape(width, height, pixels.size, 1)
                GpuLabChecks.opaque(pixels)
                val start = System.nanoTime()
                val bytes = ByteBuffer.allocateDirect((pixels.size.toLong() * 4L).toInt())
                pixels.forEachIndexed { index, pixel ->
                    if (index % 4096 == 0) GpuLabChecks.cancellation()
                    bytes.put((pixel ushr 16).toByte()).put((pixel ushr 8).toByte())
                        .put(pixel.toByte()).put(255.toByte())
                }
                bytes.flip()
                return Input(width, height, pixels.size, bytes.asReadOnlyBuffer(), elapsed(start))
            }
        }
    }

    data class Timing(val uploadAndCompletionMs: Double, val readbackAndBitmapMs: Double,
        val totalMs: Double, val allocationMs: Double)
    data class Output(val bitmap: Bitmap, val timing: Timing)

    private val owner = Thread.currentThread()
    private var display: EGLDisplay = EGL.EGL_NO_DISPLAY
    private var context: EGLContext = EGL.EGL_NO_CONTEXT
    private var surface: EGLSurface = EGL.EGL_NO_SURFACE
    private var initialized = false
    private var current = false
    private var closed = false
    private var sharpenProgram = 0
    private var interpolateProgram = 0
    private val textures = IntArray(5)
    private val framebuffer = IntArray(1)
    private val vertexArray = IntArray(1)
    private var sourceWidth = 0
    private var sourceHeight = 0
    private var outputWidth = 0
    private var outputHeight = 0
    private var readback: ByteBuffer? = null
    val renderer: String
    val version: String
    val vendor: String
    val shadingLanguage: String
    val maxTextureSize: Int
    val maxViewportDims: IntArray
    val arithmeticMode: String
    val initializationMs: Double

    init {
        val start = System.nanoTime()
        try {
            GpuLabChecks.cancellation()
            display = EGL.eglGetDisplay(EGL.EGL_DEFAULT_DISPLAY)
            check(display != EGL.EGL_NO_DISPLAY) { eglMessage("eglGetDisplay") }
            val major = IntArray(1)
            val minor = IntArray(1)
            egl(EGL.eglInitialize(display, major, 0, minor, 0), "eglInitialize")
            initialized = true
            egl(EGL.eglBindAPI(EGL.EGL_OPENGL_ES_API), "eglBindAPI")
            val configs = arrayOfNulls<EGLConfig>(1)
            val count = IntArray(1)
            val attributes = intArrayOf(EGL.EGL_SURFACE_TYPE, EGL.EGL_PBUFFER_BIT,
                EGL.EGL_RENDERABLE_TYPE, 0x0040, // EGL_OPENGL_ES3_BIT_KHR
                EGL.EGL_RED_SIZE, 8, EGL.EGL_GREEN_SIZE, 8, EGL.EGL_BLUE_SIZE, 8,
                EGL.EGL_ALPHA_SIZE, 8, EGL.EGL_DEPTH_SIZE, 0, EGL.EGL_STENCIL_SIZE, 0, EGL.EGL_NONE)
            egl(EGL.eglChooseConfig(display, attributes, 0, configs, 0, 1, count, 0), "eglChooseConfig")
            check(count[0] > 0 && configs[0] != null) { "No GLES3 RGBA8 pbuffer EGL config" }
            context = EGL.eglCreateContext(display, configs[0], EGL.EGL_NO_CONTEXT,
                intArrayOf(EGL.EGL_CONTEXT_CLIENT_VERSION, 3, EGL.EGL_NONE), 0)
            check(context != EGL.EGL_NO_CONTEXT) { eglMessage("eglCreateContext GLES3") }
            surface = EGL.eglCreatePbufferSurface(display, configs[0],
                intArrayOf(EGL.EGL_WIDTH, 1, EGL.EGL_HEIGHT, 1, EGL.EGL_NONE), 0)
            check(surface != EGL.EGL_NO_SURFACE) { eglMessage("eglCreatePbufferSurface") }
            egl(EGL.eglMakeCurrent(display, surface, surface, context), "eglMakeCurrent")
            current = true
            renderer = checkNotNull(GL.glGetString(GL.GL_RENDERER))
            version = checkNotNull(GL.glGetString(GL.GL_VERSION))
            vendor = checkNotNull(GL.glGetString(GL.GL_VENDOR))
            shadingLanguage = checkNotNull(GL.glGetString(GL.GL_SHADING_LANGUAGE_VERSION))
            val maximum = IntArray(1)
            GL.glGetIntegerv(GL.GL_MAX_TEXTURE_SIZE, maximum, 0)
            maxTextureSize = maximum[0]
            maxViewportDims = IntArray(2)
            GL.glGetIntegerv(GL.GL_MAX_VIEWPORT_DIMS, maxViewportDims, 0)
            val glMajor = IntArray(1)
            GL.glGetIntegerv(GL.GL_MAJOR_VERSION, glMajor, 0)
            check(glMajor[0] >= 3) { "GLES 3.0 required: $version" }
            val glMinor = IntArray(1)
            GL.glGetIntegerv(GL.GL_MINOR_VERSION, glMinor, 0)
            val extensionCount = IntArray(1)
            GL.glGetIntegerv(GL.GL_NUM_EXTENSIONS, extensionCount, 0)
            val extensions = (0 until extensionCount[0]).map { GL.glGetStringi(GL.GL_EXTENSIONS, it) }
            arithmeticMode = when {
                glMajor[0] > 3 || glMinor[0] >= 2 -> "precise-core-320"
                glMinor[0] >= 1 && "GL_EXT_gpu_shader5" in extensions -> "precise-ext-310"
                else -> "standard-300"
            }
            GL.glDisable(GL.GL_DITHER)
            GL.glDisable(GL.GL_BLEND)
            GL.glDisable(GL.GL_DEPTH_TEST)
            GL.glDisable(GL.GL_SCISSOR_TEST)
            GL.glPixelStorei(GL.GL_PACK_ALIGNMENT, 1)
            GL.glPixelStorei(GL.GL_UNPACK_ALIGNMENT, 1)
            sharpenProgram = program(SHARPEN)
            interpolateProgram = program(MITCHELL)
            GL.glGenTextures(textures.size, textures, 0)
            GL.glGenFramebuffers(1, framebuffer, 0)
            GL.glGenVertexArrays(1, vertexArray, 0)
            GL.glBindVertexArray(vertexArray[0])
            check(textures.all { it != 0 } && framebuffer[0] != 0 && vertexArray[0] != 0) { "GL object allocation failed" }
            gl("initialize")
            GL.glFinish()
            gl("initialize completion")
            GpuLabChecks.cancellation()
            initializationMs = elapsed(start)
        } catch (failure: Throwable) {
            try { close() } catch (cleanup: Throwable) { failure.addSuppressed(cleanup) }
            throw failure
        }
    }

    /** Allocate outside measured samples. Subsequent renders of the same shape reuse all textures. */
    fun prepare(input: Input, scale: Int): Double = guarded {
        val shape = GpuLabChecks.shape(input.width, input.height, input.count, scale)
        GpuLabChecks.deviceLimits(input.width, input.height, shape, maxTextureSize, maxViewportDims)
        GpuLabChecks.cancellation()
        if (sourceWidth == input.width && sourceHeight == input.height &&
            outputWidth == shape.width && outputHeight == shape.height) return@guarded 0.0
        GL.glFinish()
        gl("before allocation")
        val start = System.nanoTime()
        for (index in 0..2) {
            val width = if (index == 2) shape.width else input.width
            val height = if (index == 2) shape.height else input.height
            GL.glBindTexture(GL.GL_TEXTURE_2D, textures[index])
            GL.glTexParameteri(GL.GL_TEXTURE_2D, GL.GL_TEXTURE_MIN_FILTER, GL.GL_NEAREST)
            GL.glTexParameteri(GL.GL_TEXTURE_2D, GL.GL_TEXTURE_MAG_FILTER, GL.GL_NEAREST)
            GL.glTexParameteri(GL.GL_TEXTURE_2D, GL.GL_TEXTURE_WRAP_S, GL.GL_CLAMP_TO_EDGE)
            GL.glTexParameteri(GL.GL_TEXTURE_2D, GL.GL_TEXTURE_WRAP_T, GL.GL_CLAMP_TO_EDGE)
            GL.glTexImage2D(GL.GL_TEXTURE_2D, 0, GL.GL_RGBA8, width, height, 0,
                GL.GL_RGBA, GL.GL_UNSIGNED_BYTE, null)
            gl("allocate RGBA8 texture $index")
        }
        // Float textures are sampled only, never used as framebuffer attachments.
        // Precompute coefficients once with the same Float operations as the CPU reference.
        for ((index, size) in listOf(3 to shape.width, 4 to shape.height)) {
            val weights = GpuSamplingWeights.create(size, scale)
            GL.glBindTexture(GL.GL_TEXTURE_2D, textures[index])
            GL.glTexParameteri(GL.GL_TEXTURE_2D, GL.GL_TEXTURE_MIN_FILTER, GL.GL_NEAREST)
            GL.glTexParameteri(GL.GL_TEXTURE_2D, GL.GL_TEXTURE_MAG_FILTER, GL.GL_NEAREST)
            GL.glTexParameteri(GL.GL_TEXTURE_2D, GL.GL_TEXTURE_WRAP_S, GL.GL_CLAMP_TO_EDGE)
            GL.glTexParameteri(GL.GL_TEXTURE_2D, GL.GL_TEXTURE_WRAP_T, GL.GL_CLAMP_TO_EDGE)
            val buffer = ByteBuffer.allocateDirect(weights.size * 4)
                .order(java.nio.ByteOrder.nativeOrder()).asFloatBuffer().apply { put(weights); flip() }
            GL.glTexImage2D(GL.GL_TEXTURE_2D, 0, GL.GL_RGBA32F, size, 1, 0, GL.GL_RGBA, GL.GL_FLOAT, buffer)
            gl("upload Float sampling coefficients")
        }
        val bytes = (shape.count.toLong() * 4L).toInt()
        if ((readback?.capacity() ?: 0) < bytes) readback = ByteBuffer.allocateDirect(bytes)
        attach(textures[1], input.width, input.height)
        attach(textures[2], shape.width, shape.height)
        GL.glFinish()
        gl("allocation completion")
        sourceWidth = input.width
        sourceHeight = input.height
        outputWidth = shape.width
        outputHeight = shape.height
        GpuLabChecks.cancellation()
        elapsed(start)
    }

    fun render(input: Input, scale: Int): Output = guarded {
        val allocationMs = prepare(input, scale)
        // Finish earlier work OUTSIDE this sample. Include this sample's glFinish INSIDE timing.
        GL.glFinish()
        gl("before sample")
        GpuLabChecks.cancellation()
        val bytes = input.rgba.duplicate().apply { rewind() }
        val start = System.nanoTime()
        GL.glActiveTexture(GL.GL_TEXTURE0)
        GL.glBindTexture(GL.GL_TEXTURE_2D, textures[0])
        // A fresh upload occurs even if the source bytes did not change between samples.
        GL.glTexSubImage2D(GL.GL_TEXTURE_2D, 0, 0, 0, input.width, input.height,
            GL.GL_RGBA, GL.GL_UNSIGNED_BYTE, bytes)
        gl("upload RGBA")
        if (scale == 1) {
            // A real GPU draw/readback bypasses BOTH filters; never a CPU clone.
            draw(sharpenProgram, textures[0], textures[2], input.width, input.height, scale)
        } else {
            draw(sharpenProgram, textures[0], textures[1], input.width, input.height, scale)
            draw(interpolateProgram, textures[1], textures[2], outputWidth, outputHeight, scale)
        }
        GL.glFinish()
        gl("upload + draws completion")
        val completed = System.nanoTime()
        GpuLabChecks.cancellation()
        val buffer = checkNotNull(readback).apply { clear(); limit(outputWidth * outputHeight * 4) }
        GL.glReadPixels(0, 0, outputWidth, outputHeight, GL.GL_RGBA, GL.GL_UNSIGNED_BYTE, buffer)
        gl("glReadPixels RGBA8")
        buffer.rewind()
        val pixels = IntArray(outputWidth * outputHeight)
        // Logical top row is uploaded to GL y=0, sampled as y=0, and read back first.
        // No screen surface is presented; a flip at ANY of these boundaries would be a bug.
        for (index in pixels.indices) {
            if (index % 4096 == 0) GpuLabChecks.cancellation()
            val r = buffer.get().toInt() and 255
            val g = buffer.get().toInt() and 255
            val b = buffer.get().toInt() and 255
            val a = buffer.get().toInt() and 255
            pixels[index] = (a shl 24) or (r shl 16) or (g shl 8) or b
        }
        val bitmap = Bitmap.createBitmap(pixels, outputWidth, outputHeight, Bitmap.Config.ARGB_8888)
        try {
            bitmap.density = Bitmap.DENSITY_NONE
            val end = System.nanoTime()
            GpuLabChecks.cancellation()
            Output(bitmap, Timing((completed - start) / 1e6, (end - completed) / 1e6, (end - start) / 1e6, allocationMs))
        } catch (failure: Throwable) {
            bitmap.recycle()
            throw failure
        }
    }

    private fun draw(program: Int, source: Int, target: Int, width: Int, height: Int, scale: Int) {
        attach(target, width, height)
        GL.glUseProgram(program)
        GL.glActiveTexture(GL.GL_TEXTURE0)
        GL.glBindTexture(GL.GL_TEXTURE_2D, source)
        GL.glUniform1i(uniform(program, "uSource"), 0)
        GL.glUniform2i(uniform(program, "uSize"), sourceWidth, sourceHeight)
        GL.glUniform1i(uniform(program, "uScale"), scale)
        if (program == interpolateProgram) {
            for ((unit, name) in listOf(1 to "uWeightsX", 2 to "uWeightsY")) {
                GL.glActiveTexture(GL.GL_TEXTURE0 + unit)
                GL.glBindTexture(GL.GL_TEXTURE_2D, textures[unit + 2])
                GL.glUniform1i(uniform(program, name), unit)
            }
            GL.glActiveTexture(GL.GL_TEXTURE0)
        }
        GL.glDrawArrays(GL.GL_TRIANGLES, 0, 3)
        gl("draw ${if (program == sharpenProgram) "source/pass-through" else "Mitchell"}")
    }

    private fun attach(texture: Int, width: Int, height: Int) {
        GL.glBindFramebuffer(GL.GL_FRAMEBUFFER, framebuffer[0])
        GL.glFramebufferTexture2D(GL.GL_FRAMEBUFFER, GL.GL_COLOR_ATTACHMENT0, GL.GL_TEXTURE_2D, texture, 0)
        val status = GL.glCheckFramebufferStatus(GL.GL_FRAMEBUFFER)
        check(status == GL.GL_FRAMEBUFFER_COMPLETE) { "RGBA8 FBO incomplete: 0x${status.toString(16)}" }
        GL.glViewport(0, 0, width, height)
        gl("attach FBO/viewport")
    }

    private fun uniform(program: Int, name: String): Int = GL.glGetUniformLocation(program, name).also {
        check(it >= 0) { "Missing shader uniform $name" }
    }

    private fun program(fragment: String): Int {
        val vertex = shader(GL.GL_VERTEX_SHADER, VERTEX)
        var frag = 0
        var result = 0
        try {
            frag = shader(GL.GL_FRAGMENT_SHADER, fragment)
            result = GL.glCreateProgram()
            check(result != 0) { "glCreateProgram failed" }
            GL.glAttachShader(result, vertex)
            GL.glAttachShader(result, frag)
            GL.glLinkProgram(result)
            val status = IntArray(1)
            GL.glGetProgramiv(result, GL.GL_LINK_STATUS, status, 0)
            check(status[0] == GL.GL_TRUE) { "Shader link failed: ${GL.glGetProgramInfoLog(result).take(4000)}" }
            gl("link program")
            return result
        } catch (failure: Throwable) {
            if (result != 0) GL.glDeleteProgram(result)
            throw failure
        } finally {
            GL.glDeleteShader(vertex)
            if (frag != 0) GL.glDeleteShader(frag)
        }
    }

    private fun shaderSource(code: String): String {
        if (arithmeticMode == "standard-300") return code.replace("PRECISE ", "")
        val header = if (arithmeticMode == "precise-core-320") "#version 320 es"
            else "#version 310 es\n#extension GL_EXT_gpu_shader5 : require"
        return code.replace("#version 300 es", header).replace("PRECISE ", "precise ")
    }

    private fun shader(type: Int, code: String): Int {
        val id = GL.glCreateShader(type)
        check(id != 0) { "glCreateShader failed" }
        try {
            GL.glShaderSource(id, shaderSource(code))
            GL.glCompileShader(id)
            val status = IntArray(1)
            GL.glGetShaderiv(id, GL.GL_COMPILE_STATUS, status, 0)
            check(status[0] == GL.GL_TRUE) { "Shader compile failed: ${GL.glGetShaderInfoLog(id).take(4000)}" }
            gl("compile shader")
            return id
        } catch (failure: Throwable) {
            GL.glDeleteShader(id)
            throw failure
        }
    }

    private fun <T> guarded(block: () -> T): T {
        check(Thread.currentThread() === owner) { "GpuC must stay on its owner thread" }
        check(!closed) { "GpuC is closed" }
        try { return block() } catch (failure: Throwable) {
            try { close() } catch (cleanup: Throwable) { failure.addSuppressed(cleanup) }
            throw failure
        }
    }

    /** Synchronous, idempotent owner-thread cleanup; never queued on an executor being shut down. */
    override fun close() {
        check(Thread.currentThread() === owner) { "GpuC.close must run on its owner thread" }
        if (closed) return
        closed = true
        var failure: Throwable? = null
        fun release(action: () -> Unit) {
            try { action() } catch (error: Throwable) {
                if (failure == null) failure = error else failure!!.addSuppressed(error)
            }
        }
        if (current) {
            release {
                GL.glDeleteTextures(textures.size, textures, 0)
                GL.glDeleteFramebuffers(1, framebuffer, 0)
                GL.glDeleteVertexArrays(1, vertexArray, 0)
                if (sharpenProgram != 0) GL.glDeleteProgram(sharpenProgram)
                if (interpolateProgram != 0) GL.glDeleteProgram(interpolateProgram)
                gl("delete GL resources")
            }
            release { egl(EGL.eglMakeCurrent(display, EGL.EGL_NO_SURFACE, EGL.EGL_NO_SURFACE, EGL.EGL_NO_CONTEXT), "unbind EGL") }
        }
        if (surface != EGL.EGL_NO_SURFACE) release { egl(EGL.eglDestroySurface(display, surface), "destroy EGL surface") }
        if (context != EGL.EGL_NO_CONTEXT) release { egl(EGL.eglDestroyContext(display, context), "destroy EGL context") }
        if (initialized) release { egl(EGL.eglTerminate(display), "terminate EGL") }
        release { egl(EGL.eglReleaseThread(), "release EGL thread") }
        readback = null
        current = false
        failure?.let { throw it }
    }

    companion object {
        private fun elapsed(start: Long) = (System.nanoTime() - start) / 1e6
        private fun eglMessage(operation: String) = "$operation: EGL error 0x${EGL.eglGetError().toString(16)}"
        private fun egl(ok: Boolean, operation: String) { check(ok) { eglMessage(operation) } }
        private fun gl(operation: String) {
            val errors = mutableListOf<Int>()
            repeat(16) {
                val error = GL.glGetError()
                if (error == GL.GL_NO_ERROR) {
                    check(errors.isEmpty()) { "$operation: GL errors ${errors.joinToString { "0x${it.toString(16)}" }}" }
                    return
                }
                errors += error
            }
            error("$operation: GL error queue did not clear: $errors")
        }

        private val VERTEX = """
            #version 300 es
            precision highp float;
            precision highp int;
            void main() {
                vec2 p = vec2(float((gl_VertexID << 1) & 2), float(gl_VertexID & 2));
                gl_Position = vec4(p * 2.0 - 1.0, 0.0, 1.0);
            }
        """.trimIndent()

        private val SHARPEN = """
            #version 300 es
            precision highp float;
            precision highp int;
            uniform highp sampler2D uSource;
            uniform ivec2 uSize;
            uniform int uScale;
            layout(location = 0) PRECISE out vec4 color;
            vec3 sourceAt(ivec2 p) {
                return floor(texelFetch(uSource, clamp(p, ivec2(0), uSize - 1), 0).rgb * 255.0 + 0.5);
            }
            void main() {
                ivec2 p = ivec2(gl_FragCoord.xy);
                vec3 original = sourceAt(p);
                if (uScale == 1) { color = vec4(original / 255.0, 1.0); return; }
                vec3 blur = vec3(0.0);
                for (int dy = -1; dy <= 1; ++dy) {
                    for (int dx = -1; dx <= 1; ++dx) {
                        float ky = dy == 0 ? 2.0 : 1.0;
                        float kx = dx == 0 ? 2.0 : 1.0;
                        blur += sourceAt(p + ivec2(dx, dy)) * ky * kx;
                    }
                }
                vec3 delta = clamp((original - blur / 16.0) * 0.2, vec3(-12.0), vec3(12.0));
                // Explicit source rounding BEFORE storage and interpolation, matching CPU C.
                color = vec4(floor(clamp(original + delta, 0.0, 255.0) + 0.5) / 255.0, 1.0);
            }
        """.trimIndent()

        // Same published Mitchell-Netravali B=C=1/3 formula as the immutable CPU reference.
        private val MITCHELL = """
            #version 300 es
            precision highp float;
            precision highp int;
            uniform highp sampler2D uSource;
            uniform ivec2 uSize;
            uniform int uScale;
            layout(location = 0) PRECISE out vec4 color;
            uniform highp sampler2D uWeightsX;
            uniform highp sampler2D uWeightsY;
            int firstTap(int outputIndex) {
                int numerator = 2 * outputIndex + 1 - uScale;
                // For integer lab scales the only negative quotient is -1.
                return (numerator < 0 ? -1 : numerator / (2 * uScale)) - 1;
            }
            void main() {
                ivec2 pixel = ivec2(gl_FragCoord.xy);
                ivec2 first = ivec2(firstTap(pixel.x), firstTap(pixel.y));
                vec4 wx = texelFetch(uWeightsX, ivec2(pixel.x, 0), 0);
                vec4 wy = texelFetch(uWeightsY, ivec2(pixel.y, 0), 0);
                vec3 value = vec3(0.0);
                for (int y = 0; y < 4; ++y) {
                    vec3 row = vec3(0.0);
                    for (int x = 0; x < 4; ++x) {
                        ivec2 p = clamp(first + ivec2(x, y), ivec2(0), uSize - 1);
                        vec3 sampleValue = floor(texelFetch(uSource, p, 0).rgb * 255.0 + 0.5);
                        row += sampleValue * wx[x];
                    }
                    // Preserve CPU arithmetic order: horizontal row, then vertical accumulation.
                    value += row * wy[y];
                }
                color = vec4(floor(clamp(value, 0.0, 255.0) + 0.5) / 255.0, 1.0);
            }
        """.trimIndent()
    }
}
