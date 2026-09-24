package com.kandong.modelprobe

/** Test-only error propagation for a mean of probabilities over an unchanged score mask.
 * Equal input postprocessing retains its original 1e-7 gate. Actual inference first has to
 * pass the existing detector gate; this bound never permits a mask/geometry/decision change. */
internal object EndToEndScoreBudget {
    const val SAME_INPUT_ATOL = 1e-7
    /** Exact ROI and int32 polygon used by BoxPipelineOpenCv.score; equality is
     * stronger than approximate floating quad equality and implies equal score masks. */
    fun maskFootprint(quad: List<GeometryProbeContract.Point>, width: Int, height: Int): List<Int> {
        require(quad.size == 4 && width in 1..DetectorPacking.MAX_RESIZED_DIMENSION && height in 1..DetectorPacking.MAX_RESIZED_DIMENSION)
        require(quad.all { it.x.isFinite() && it.y.isFinite() && kotlin.math.abs(it.x) <= 4096 && kotlin.math.abs(it.y) <= 4096 })
        val xmin = kotlin.math.floor(quad.minOf { it.x }).toInt().coerceIn(0, width - 1)
        val xmax = kotlin.math.ceil(quad.maxOf { it.x }).toInt().coerceIn(0, width - 1)
        val ymin = kotlin.math.floor(quad.minOf { it.y }).toInt().coerceIn(0, height - 1)
        val ymax = kotlin.math.ceil(quad.maxOf { it.y }).toInt().coerceIn(0, height - 1)
        return listOf(xmin, xmax, ymin, ymax) + quad.flatMap {
            listOf((it.x.toFloat() - xmin.toFloat()).toInt(), (it.y.toFloat() - ymin.toFloat()).toInt())
        }
    }
    fun fromDetector(input: DetectorComparison.Metrics): Double {
        require(input.passed && input.count > 0)
        require(input.maxAbsolute.isFinite() && input.maxAbsolute >= 0.0 &&
            input.maxAbsolute <= DetectorComparison.ATOL + DetectorComparison.RTOL)
        return SAME_INPUT_ATOL + input.maxAbsolute
    }
}
