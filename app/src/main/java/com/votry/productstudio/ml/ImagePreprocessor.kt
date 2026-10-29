package com.votry.productstudio.ml

import android.graphics.Bitmap
import kotlin.math.max

object ImagePreprocessor {
    fun prepareInput(source: Bitmap): FloatArray {
        val scaled = Bitmap.createScaledBitmap(source, ModelConfig.SIZE, ModelConfig.SIZE, true)
        val pixels = IntArray(ModelConfig.SIZE * ModelConfig.SIZE)
        scaled.getPixels(pixels, 0, ModelConfig.SIZE, 0, 0, ModelConfig.SIZE, ModelConfig.SIZE)
        if (scaled !== source) scaled.recycle()

        var maxVal = 1f
        for (p in pixels) {
            maxVal = max(maxVal, max(max((p shr 16) and 0xFF, (p shr 8) and 0xFF), p and 0xFF).toFloat())
        }
        val size = ModelConfig.SIZE * ModelConfig.SIZE
        val out = FloatArray(size * 3)
        for (i in pixels.indices) {
            val p = pixels[i]
            val r = ((p shr 16) and 0xFF) / maxVal
            val g = ((p shr 8) and 0xFF) / maxVal
            val b = (p and 0xFF) / maxVal
            out[i] = (r - ModelConfig.mean[0]) / ModelConfig.std[0]
            out[size + i] = (g - ModelConfig.mean[1]) / ModelConfig.std[1]
            out[2 * size + i] = (b - ModelConfig.mean[2]) / ModelConfig.std[2]
        }
        return out
    }
}
