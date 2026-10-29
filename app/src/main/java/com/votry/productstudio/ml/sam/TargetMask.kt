package com.votry.productstudio.ml.sam

import kotlin.math.exp
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min

/** A MobileSAM target mask stored as logits at a bounded analysis resolution. */
data class TargetMask(
    val logits: FloatArray,
    val width: Int,
    val height: Int,
    val score: Float,
) {
    init {
        require(width > 0 && height > 0)
        require(logits.size >= width * height)
    }

    data class Bounds(
        val left: Float,
        val top: Float,
        val right: Float,
        val bottom: Float,
    ) {
        val width: Float get() = (right - left).coerceAtLeast(0f)
        val height: Float get() = (bottom - top).coerceAtLeast(0f)
        val centerX: Float get() = (left + right) * 0.5f
        val centerY: Float get() = (top + bottom) * 0.5f
    }

    fun probabilityAtIndex(index: Int): Float {
        val z = logits[index].coerceIn(-20f, 20f)
        return (1f / (1f + exp(-z))).coerceIn(0f, 1f)
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
        val p00 = probabilityAtIndex(y0 * width + x0)
        val p10 = probabilityAtIndex(y0 * width + x1)
        val p01 = probabilityAtIndex(y1 * width + x0)
        val p11 = probabilityAtIndex(y1 * width + x1)
        val top = p00 * (1f - tx) + p10 * tx
        val bottom = p01 * (1f - tx) + p11 * tx
        return (top * (1f - ty) + bottom * ty).coerceIn(0f, 1f)
    }

    fun occupancy(threshold: Float = 0.5f): Float {
        var foreground = 0
        val total = width * height
        for (i in 0 until total) {
            if (probabilityAtIndex(i) >= threshold) foreground++
        }
        return foreground.toFloat() / max(1, total).toFloat()
    }

    fun bounds(threshold: Float = 0.45f): Bounds? {
        var left = width
        var top = height
        var right = -1
        var bottom = -1
        for (y in 0 until height) {
            for (x in 0 until width) {
                if (probabilityAtIndex(y * width + x) >= threshold) {
                    left = min(left, x)
                    top = min(top, y)
                    right = max(right, x)
                    bottom = max(bottom, y)
                }
            }
        }
        if (right < left || bottom < top) return null
        val denomX = max(1, width - 1).toFloat()
        val denomY = max(1, height - 1).toFloat()
        return Bounds(
            left = left / denomX,
            top = top / denomY,
            right = right / denomX,
            bottom = bottom / denomY,
        )
    }
}
