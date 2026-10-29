package com.votry.productstudio.ml

import kotlin.math.floor
import kotlin.math.min

data class MatteData(
    val alpha: FloatArray,
    val width: Int,
    val height: Int,
) {
    init {
        require(width > 0 && height > 0)
        require(alpha.size >= width * height)
    }

    fun sampleNormalized(nx: Float, ny: Float): Float {
        val x = nx.coerceIn(0f, 1f) * (width - 1).coerceAtLeast(0)
        val y = ny.coerceIn(0f, 1f) * (height - 1).coerceAtLeast(0)
        val x0 = floor(x).toInt().coerceIn(0, width - 1)
        val y0 = floor(y).toInt().coerceIn(0, height - 1)
        val x1 = min(x0 + 1, width - 1)
        val y1 = min(y0 + 1, height - 1)
        val tx = x - x0
        val ty = y - y0
        val top = alpha[y0 * width + x0] * (1f - tx) + alpha[y0 * width + x1] * tx
        val bottom = alpha[y1 * width + x0] * (1f - tx) + alpha[y1 * width + x1] * tx
        return (top * (1f - ty) + bottom * ty).coerceIn(0f, 1f)
    }
}
