package com.votry.productstudio.ml

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import android.graphics.Bitmap
import com.votry.productstudio.processing.PixelMath
import java.io.File
import java.nio.FloatBuffer
import kotlin.math.exp
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext

class BackgroundRemover {
    private val env = OrtEnvironment.getEnvironment()

    suspend fun process(
        source: Bitmap,
        modelFile: File,
        onProgress: (Int, String) -> Unit,
    ): Bitmap = withContext(Dispatchers.Default) {
        ensureActive()
        onProgress(8, "Analyse du produit…")

        // V0.2 intentionally uses the CPU execution provider first. It is slower than NNAPI on
        // some devices, but far more predictable across Android vendors and avoids native crashes.
        val options = OrtSession.SessionOptions().apply {
            setOptimizationLevel(OrtSession.SessionOptions.OptLevel.ALL_OPT)
        }

        env.createSession(modelFile.absolutePath, options).use { session ->
            ensureActive()
            onProgress(20, "Préparation haute qualité…")
            val input = ImagePreprocessor.prepareInput(source)
            val shape = longArrayOf(1, 3, ModelConfig.SIZE.toLong(), ModelConfig.SIZE.toLong())
            val inputName = session.inputNames.first()

            onProgress(38, "Détection du produit et du verre…")
            val rawMask: FloatArray
            var maskWidth: Int
            var maskHeight: Int
            OnnxTensor.createTensor(env, FloatBuffer.wrap(input), shape).use { tensor ->
                session.run(mapOf(inputName to tensor)).use { result ->
                    val output = result[0] as OnnxTensor
                    val outputShape = output.info.shape
                    maskHeight = outputShape[outputShape.size - 2].toInt()
                    maskWidth = outputShape[outputShape.size - 1].toInt()
                    rawMask = FloatArray(maskWidth * maskHeight)
                    val buffer = output.floatBuffer
                    buffer.rewind()
                    buffer.get(rawMask, 0, min(rawMask.size, buffer.remaining()))
                }
            }

            ensureActive()
            onProgress(68, "Affinage des transparences…")
            val alpha = normalizeSigmoid(rawMask)

            ensureActive()
            onProgress(82, "Nettoyage du fond…")
            val result = composeWhiteMemorySafe(source, alpha, maskWidth, maskHeight)
            onProgress(94, "Finition du verre et des reflets…")
            result
        }.also {
            options.close()
        }
    }

    private fun normalizeSigmoid(raw: FloatArray): FloatArray {
        var lo = Float.MAX_VALUE
        var hi = -Float.MAX_VALUE
        val alpha = FloatArray(raw.size)
        for (i in raw.indices) {
            val value = (1f / (1f + exp(-raw[i]))).coerceIn(0f, 1f)
            alpha[i] = value
            lo = min(lo, value)
            hi = max(hi, value)
        }
        val range = max(hi - lo, 1e-6f)
        for (i in alpha.indices) {
            alpha[i] = ((alpha[i] - lo) / range).coerceIn(0f, 1f)
        }
        return alpha
    }

    private fun composeWhiteMemorySafe(source: Bitmap, alpha: FloatArray, mw: Int, mh: Int): Bitmap {
        val width = source.width
        val height = source.height
        val output = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val background = estimateBackground(source)
        val bounds = alphaBounds(alpha, mw, mh)
        val stripeRows = 96
        val sourceStripe = IntArray(width * stripeRows)
        val outputStripe = IntArray(width * stripeRows)

        var y0 = 0
        while (y0 < height) {
            val rows = min(stripeRows, height - y0)
            val count = width * rows
            source.getPixels(sourceStripe, 0, width, 0, y0, width, rows)

            for (row in 0 until rows) {
                val y = y0 + row
                for (x in 0 until width) {
                    val i = row * width + x
                    val aFloat = sampleAlphaBilinear(alpha, mw, mh, x, y, width, height)
                    if (aFloat <= 0.022f) {
                        outputStripe[i] = shadowPixelOrWhite(x, y, width, height, bounds, mw, mh)
                        continue
                    }

                    val a = (aFloat * 255f).toInt().coerceIn(0, 255)
                    val pixel = sourceStripe[i]
                    val sr = (pixel shr 16) and 0xFF
                    val sg = (pixel shr 8) and 0xFF
                    val sb = pixel and 0xFF

                    // Opaque areas are deliberately untouched. This protects labels, tickets,
                    // logos, barcodes and Arabic lettering instead of redrawing them.
                    val preserveSource = aFloat >= 0.84f
                    val (foregroundR, foregroundG, foregroundB) = if (preserveSource || aFloat < 0.09f) {
                        Triple(sr, sg, sb)
                    } else {
                        // Conservative scene-colour decontamination for semi-transparent glass.
                        val denominator = max(aFloat, 0.14f)
                        Triple(
                            ((sr - (1f - aFloat) * background.first) / denominator).toInt().coerceIn(0, 255),
                            ((sg - (1f - aFloat) * background.second) / denominator).toInt().coerceIn(0, 255),
                            ((sb - (1f - aFloat) * background.third) / denominator).toInt().coerceIn(0, 255),
                        )
                    }

                    val red = PixelMath.compositeChannel(foregroundR, a)
                    val green = PixelMath.compositeChannel(foregroundG, a)
                    val blue = PixelMath.compositeChannel(foregroundB, a)
                    outputStripe[i] = (0xFF shl 24) or (red shl 16) or (green shl 8) or blue
                }
            }

            output.setPixels(outputStripe, 0, width, 0, y0, width, rows)
            // Clear only the part used so stale pixels cannot leak into the last stripe.
            for (i in 0 until count) outputStripe[i] = 0
            y0 += rows
        }
        return output
    }

    private fun sampleAlphaBilinear(
        alpha: FloatArray,
        mw: Int,
        mh: Int,
        x: Int,
        y: Int,
        width: Int,
        height: Int,
    ): Float {
        val fx = if (width <= 1) 0f else x.toFloat() * (mw - 1).toFloat() / (width - 1).toFloat()
        val fy = if (height <= 1) 0f else y.toFloat() * (mh - 1).toFloat() / (height - 1).toFloat()
        val x0 = floor(fx).toInt().coerceIn(0, mw - 1)
        val y0 = floor(fy).toInt().coerceIn(0, mh - 1)
        val x1 = min(x0 + 1, mw - 1)
        val y1 = min(y0 + 1, mh - 1)
        val tx = fx - x0
        val ty = fy - y0
        val top = alpha[y0 * mw + x0] * (1f - tx) + alpha[y0 * mw + x1] * tx
        val bottom = alpha[y1 * mw + x0] * (1f - tx) + alpha[y1 * mw + x1] * tx
        return (top * (1f - ty) + bottom * ty).coerceIn(0f, 1f)
    }

    private fun estimateBackground(bitmap: Bitmap): Triple<Int, Int, Int> {
        val width = bitmap.width
        val height = bitmap.height
        val sample = max(8, min(width, height) / 18)
        var red = 0L
        var green = 0L
        var blue = 0L
        var count = 0L
        val buffer = IntArray(sample * sample)

        fun sampleCorner(x: Int, y: Int) {
            bitmap.getPixels(buffer, 0, sample, x, y, sample, sample)
            for (pixel in buffer) {
                red += (pixel shr 16) and 0xFF
                green += (pixel shr 8) and 0xFF
                blue += pixel and 0xFF
                count++
            }
        }

        sampleCorner(0, 0)
        sampleCorner(max(0, width - sample), 0)
        sampleCorner(0, max(0, height - sample))
        sampleCorner(max(0, width - sample), max(0, height - sample))
        if (count == 0L) return Triple(255, 255, 255)
        return Triple((red / count).toInt(), (green / count).toInt(), (blue / count).toInt())
    }

    private data class AlphaBounds(val left: Int, val top: Int, val right: Int, val bottom: Int)

    private fun alphaBounds(alpha: FloatArray, width: Int, height: Int): AlphaBounds {
        var left = width
        var top = height
        var right = 0
        var bottom = 0
        for (y in 0 until height) {
            for (x in 0 until width) {
                if (alpha[y * width + x] > 0.12f) {
                    left = min(left, x)
                    top = min(top, y)
                    right = max(right, x)
                    bottom = max(bottom, y)
                }
            }
        }
        if (left > right || top > bottom) return AlphaBounds(0, 0, width - 1, height - 1)
        return AlphaBounds(left, top, right, bottom)
    }

    private fun shadowPixelOrWhite(
        x: Int,
        y: Int,
        width: Int,
        height: Int,
        bounds: AlphaBounds,
        maskWidth: Int,
        maskHeight: Int,
    ): Int {
        val left = bounds.left.toFloat() / maskWidth * width
        val right = bounds.right.toFloat() / maskWidth * width
        val bottom = bounds.bottom.toFloat() / maskHeight * height
        val objectWidth = max(1f, right - left)
        val centerX = (left + right) * 0.5f
        val radiusX = max(18f, objectWidth * 0.34f)
        val radiusY = max(6f, height * 0.012f)
        val centerY = min(height - 1f, bottom + radiusY * 0.35f)
        val dx = (x - centerX) / radiusX
        val dy = (y - centerY) / radiusY
        if (dx * dx > 1.8f || dy * dy > 2.2f) return 0xFFFFFFFF.toInt()
        val strength = (exp(-(dx * dx * 2.0f + dy * dy * 2.8f)) * 0.10f).coerceIn(0f, 0.10f)
        if (strength < 0.006f) return 0xFFFFFFFF.toInt()
        val value = (255f * (1f - strength) + 188f * strength).toInt().coerceIn(0, 255)
        return (0xFF shl 24) or (value shl 16) or (value shl 8) or value
    }
}
