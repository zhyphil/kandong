package com.kandong.modelprobe

/** Test-only identity boundary. No reference output or model selection enters this contract. */
internal object CropRecognitionContract {
    const val CONFIG = "actual-DB-crop;BGR8-INTER_LINEAR;48-NCHW;stable-ratio;raw-CTC;page-order-after-infer-v1"
    const val MAX_FLOATS = 1_200_000
    data class Binding(val row: GeometryProbeContract.Row, val tensorRow: Int, val raw: String)
    fun permutation(order: List<Int>, count: Int) {
        require(count in 0..3 && order.size == count && order.toSet() == (0 until count).toSet()) { "CROP_MAPPING" }
    }
    fun validateRows(rows: List<GeometryProbeContract.Row>) {
        require(rows.size <= 3 && rows.map { it.id }.toSet().size == rows.size)
        permutation(rows.map { it.readingOrder }, rows.size)
        permutation(rows.map { it.originalIndex }, rows.size)
        rows.forEach(::validateRow)
    }
    fun validateRow(r: GeometryProbeContract.Row) {
        require(r.originalIndex in 0..2 && r.readingOrder in 0..2)
        require(r.id.matches(Regex("[a-z0-9-]{1,64}\\.box-[0-2]")))
        require(r.id.endsWith(".box-${r.originalIndex}"))
        require(r.detectorScore.isFinite() && r.detectorScore in 0.0..1.0)
        require(r.quad.size == 4 && r.quad.all { it.x.isFinite() && it.y.isFinite() })
        require(r.referenceMatrix.size == 9 && r.referenceMatrix.all { it.isFinite() })
        GeometryProbeContract.inverse(r.referenceMatrix.toDoubleArray())
        require(r.plan.width in 1..4096 && r.plan.height in 1..2048)
    }
    fun bind(status: BoxPipelineContract.Status, rows: List<GeometryProbeContract.Row>,
        tensorRowToInput: List<Int>, raw: List<String>): List<Binding> {
        if (status != BoxPipelineContract.Status.COMPLETE || rows.isEmpty()) {
            require(rows.isEmpty() && tensorRowToInput.isEmpty() && raw.isEmpty()) { "NO_INFERENCE_OUTPUT" }
            return emptyList()
        }
        validateRows(rows); permutation(tensorRowToInput, rows.size)
        require(raw.size == rows.size && raw.all { it.length <= 4096 })
        return tensorRowToInput.mapIndexed { tensorRow, input -> Binding(rows[input], tensorRow, raw[tensorRow]) }
            .sortedBy { it.row.readingOrder }
    }
    fun validateTensor(shape: LongArray, values: FloatArray) {
        require(shape.size == 4 && shape[0] in 1..3 && shape[1] == 3L && shape[2] == 48L && shape[3] in 1..2048)
        require(ProbeInputs.product(shape, MAX_FLOATS.toLong()).toInt() == values.size)
        require(values.all { it.isFinite() && it in -1f..1f })
    }
    fun validateCrop(width: Int, height: Int, bytes: Int, targetWidth: Int) {
        require(width in 1..4096 && height in 1..2048 && width.toLong() * height * 3 == bytes.toLong())
        require(width.toLong() * height <= GeometryProbeContract.MAX_PIXELS && targetWidth in 1..2048)
    }
}
