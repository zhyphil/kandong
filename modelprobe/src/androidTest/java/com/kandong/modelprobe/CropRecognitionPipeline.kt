package com.kandong.modelprobe

import org.opencv.core.CvType
import org.opencv.core.Mat
import org.opencv.core.Size
import org.opencv.imgproc.Imgproc
import java.nio.ByteBuffer
import java.nio.ByteOrder

/** Actual computation only. This API has no expected pixels, ordering, tensor or text. */
internal class CropRecognitionPipeline(private val cleanup: GeometryCleanup) {
    data class Prepared(val id: String, val rows: List<GeometryProbeContract.Row>,
        val plan: RecognitionPacking.Plan, val input: TensorInput)
    data class Actual(val boxes: BoxPipelineContract.Result, val crops: List<GeometryOpenCvProbe.CropResult>,
        val resized: List<RecognitionPacking.Resized>, val values: FloatArray?, val prepared: Prepared?)
    var resizeCalls = 0; private set
    var packCalls = 0; private set
    fun run(id: String, shape: LongArray, probability: FloatArray, width: Int, height: Int,
        source: () -> GeometryImage, limits: BoxPipelineContract.Limits = BoxPipelineContract.Limits()): Actual {
        val boxes = BoxPipelineOpenCv(cleanup).run(shape, probability, width, height, limits)
        if (boxes.status != BoxPipelineContract.Status.COMPLETE || boxes.boxes.isEmpty())
            return Actual(boxes, emptyList(), emptyList(), null, null)
        require(boxes.boxes.size in 1..3)
        val image = source(); require(image.width == width && image.height == height)
        val rows = boxes.boxes.mapIndexed { rank, box -> BoxPipelineContract.cropRow(id, box, rank, width, height) }
        CropRecognitionContract.validateRows(rows)
        val crops = rows.map { GeometryOpenCvProbe(cleanup).crop(image, it) }
        val sizes = crops.map { RecognitionPacking.Size(it.width, it.height) }
        val plan = RecognitionPacking.plan(sizes)
        val resized = crops.mapIndexed { index, crop -> resize(crop, plan.resizedWidths[index]) }
        packCalls++
        val values = RecognitionPacking.pack(sizes, resized)
        val tensorShape = longArrayOf(rows.size.toLong(), 3, 48, plan.width.toLong())
        CropRecognitionContract.validateTensor(tensorShape, values)
        val storage = ByteBuffer.allocateDirect(values.size * 4).order(ByteOrder.nativeOrder())
        val floats = storage.asFloatBuffer(); floats.put(values).flip()
        return Actual(boxes, crops, resized, values, Prepared(id, rows, plan, TensorInput(storage, floats, tensorShape)))
    }
    fun resize(crop: GeometryOpenCvProbe.CropResult, width: Int, afterAllocation: () -> Unit = {}): RecognitionPacking.Resized {
        CropRecognitionContract.validateCrop(crop.width, crop.height, crop.bgr.size, width)
        CropRecognitionContract.validateRow(crop.row)
        require(crop.width == crop.row.plan.outputWidth && crop.height == crop.row.plan.outputHeight && crop.rotated == crop.row.plan.rotate)
        val owned = mutableListOf<Pair<String, Mat>>()
        fun own(kind: String, mat: Mat): Mat { owned += kind to mat; cleanup.acquired[kind] = (cleanup.acquired[kind] ?: 0) + 1; return mat }
        try {
            val src = own("recognitionResizeSource", Mat(crop.height, crop.width, CvType.CV_8UC3))
            val dst = own("recognitionResizeDestination", Mat(48, width, CvType.CV_8UC3))
            afterAllocation()
            check(src.put(0, 0, crop.bgr) == crop.bgr.size)
            resizeCalls++
            Imgproc.resize(src, dst, Size(width.toDouble(), 48.0), 0.0, 0.0, Imgproc.INTER_LINEAR)
            check(dst.type() == CvType.CV_8UC3 && dst.rows() == 48 && dst.cols() == width)
            val bgr = ByteArray(width * 48 * 3); check(dst.get(0, 0, bgr) == bgr.size)
            val argb = IntArray(width * 48) { i -> -0x1000000 or (bgr[3 * i].toInt() and 255) or
                ((bgr[3 * i + 1].toInt() and 255) shl 8) or ((bgr[3 * i + 2].toInt() and 255) shl 16) }
            require(argb.all { it ushr 24 == 255 })
            return RecognitionPacking.Resized(width, 48, argb)
        } finally {
            var failure: Throwable? = null
            owned.asReversed().forEach { (kind, mat) ->
                cleanup.attempts[kind] = (cleanup.attempts[kind] ?: 0) + 1
                try { mat.release(); check(mat.empty()); cleanup.released[kind] = (cleanup.released[kind] ?: 0) + 1 }
                catch (e: Throwable) { if (failure == null) failure = e else failure!!.addSuppressed(e) }
            }
            failure?.let { throw it }
        }
    }
}
