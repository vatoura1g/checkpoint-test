package com.votry.productstudio.ml

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import android.graphics.Bitmap
import com.votry.productstudio.ml.sam.TargetMask
import com.votry.productstudio.processing.PixelMath
import java.io.File
import java.nio.FloatBuffer
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext

class LocalBackgroundRemover {
    private val environment = OrtEnvironment.getEnvironment()

    suspend fun process(
        source: Bitmap,
        modelFile: File,
        target: TargetMask,
        onProgress: (Int, String) -> Unit,
    ): Bitmap = withContext(Dispatchers.Default) {
        ensureActive()
        onProgress(46, "Analyse fine du verre…")
        val options = OrtSession.SessionOptions().apply {
            setIntraOpNumThreads(4)
            setInterOpNumThreads(1)
            setOptimizationLevel(OrtSession.SessionOptions.OptLevel.ALL_OPT)
        }

        try {
            environment.createSession(modelFile.absolutePath, options).use { session ->
                onProgress(54, "Calcul de la transparence…")
                val input = ImagePreprocessor.prepareInput(source)
                val shape = longArrayOf(1, 3, ModelConfig.SIZE.toLong(), ModelConfig.SIZE.toLong())
                val inputName = session.inputNames.first()

                val rawMask: FloatArray
                var maskWidth: Int
                var maskHeight: Int
                OnnxTensor.createTensor(environment, FloatBuffer.wrap(input), shape).use { tensor ->
                    session.run(mapOf(inputName to tensor)).use { result ->
                        val output = result[0] as OnnxTensor
                        val outputShape = output.info.shape
                        maskHeight = outputShape[outputShape.size - 2].toInt()
                        maskWidth = outputShape[outputShape.size - 1].toInt()
                        rawMask = FloatArray(maskWidth * maskHeight)
                        val buffer = output.floatBuffer
                            ?: throw IllegalStateException("Le moteur de transparence n'a pas produit de masque.")
                        buffer.get(rawMask, 0, min(rawMask.size, buffer.remaining()))
                    }
                }

                ensureActive()
                onProgress(72, "Séparation du produit et du décor…")
                val matte = normalizeSigmoid(rawMask)

                ensureActive()
                onProgress(84, "Nettoyage des zones transparentes…")
                val result = composeSelectedProduct(source, matte, maskWidth, maskHeight, target)
                onProgress(94, "Finition des reflets et contours…")
                result
            }
        } finally {
            options.close()
        }
    }

    private fun normalizeSigmoid(raw: FloatArray): FloatArray {
        var low = Float.MAX_VALUE
        var high = -Float.MAX_VALUE
        val alpha = FloatArray(raw.size)
        for (i in raw.indices) {
            val value = (1f / (1f + exp(-raw[i].coerceIn(-20f, 20f)))).coerceIn(0f, 1f)
            alpha[i] = value
            low = min(low, value)
            high = max(high, value)
        }
        val range = max(high - low, 1e-6f)
        for (i in alpha.indices) alpha[i] = ((alpha[i] - low) / range).coerceIn(0f, 1f)
        return alpha
    }

    private fun composeSelectedProduct(source: Bitmap, matte: FloatArray, matteWidth: Int, matteHeight: Int, target: TargetMask): Bitmap {
        val width = source.width
        val height = source.height
        val output = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val background = estimateBackground(source)
        val backgroundLuma = luma(background.first, background.second, background.third)
        val targetBounds = target.bounds(0.45f)
        val stripeRows = 80
        val sourceStripe = IntArray(width * stripeRows)
        val outputStripe = IntArray(width * stripeRows)

        var y0 = 0
        while (y0 < height) {
            val rows = min(stripeRows, height - y0)
            source.getPixels(sourceStripe, 0, width, 0, y0, width, rows)
            for (row in 0 until rows) {
                val y = y0 + row
                val ny = if (height <= 1) 0f else y.toFloat() / (height - 1).toFloat()
                for (x in 0 until width) {
                    val i = row * width + x
                    val nx = if (width <= 1) 0f else x.toFloat() / (width - 1).toFloat()
                    val targetProbability = target.sampleNormalized(nx, ny)
                    val gate = smoothStep(0.34f, 0.62f, targetProbability)
                    if (gate <= 0.01f) {
                        outputStripe[i] = shadowOrWhite(nx, ny, targetBounds)
                        continue
                    }

                    val pixel = sourceStripe[i]
                    val red = (pixel shr 16) and 0xFF
                    val green = (pixel shr 8) and 0xFF
                    val blue = pixel and 0xFF
                    val sourceLuma = luma(red, green, blue)
                    val matteAlpha = sampleBilinear(matte, matteWidth, matteHeight, nx, ny)
                    val luminanceDifference = abs(sourceLuma - backgroundLuma) / 255f
                    val channelSpread = (max(red, max(green, blue)) - min(red, min(green, blue))) / 255f
                    val opticalFloor = (0.025f + luminanceDifference * 0.42f + channelSpread * 0.08f)
                        .coerceIn(0.025f, 0.30f)
                    val opaqueDetail = matteAlpha >= 0.94f && targetProbability >= 0.62f
                    val combinedAlpha = if (opaqueDetail) {
                        (gate * matteAlpha).coerceIn(0f, 1f)
                    } else {
                        (gate * max(matteAlpha * 0.70f, opticalFloor)).coerceIn(0f, 0.78f)
                    }
                    if (combinedAlpha <= 0.015f) {
                        outputStripe[i] = shadowOrWhite(nx, ny, targetBounds)
                        continue
                    }

                    val foreground = if (opaqueDetail) {
                        Triple(red, green, blue)
                    } else {
                        neutralGlassForeground(red, green, blue, sourceLuma, backgroundLuma)
                    }
                    val alpha255 = (combinedAlpha * 255f).toInt().coerceIn(0, 255)
                    val outR = PixelMath.compositeChannel(foreground.first, alpha255)
                    val outG = PixelMath.compositeChannel(foreground.second, alpha255)
                    val outB = PixelMath.compositeChannel(foreground.third, alpha255)
                    outputStripe[i] = (0xFF shl 24) or (outR shl 16) or (outG shl 8) or outB
                }
            }
            output.setPixels(outputStripe, 0, width, 0, y0, width, rows)
            y0 += rows
        }
        return output
    }

    private fun neutralGlassForeground(red: Int, green: Int, blue: Int, sourceLuma: Float, backgroundLuma: Float): Triple<Int, Int, Int> {
        val residual = sourceLuma - backgroundLuma
        val neutral = (232f + residual * 0.22f).coerceIn(175f, 252f)
        val chromaRetention = 0.07f
        val outR = (neutral + (red - sourceLuma) * chromaRetention).toInt().coerceIn(0, 255)
        val outG = (neutral + (green - sourceLuma) * chromaRetention).toInt().coerceIn(0, 255)
        val outB = (neutral + (blue - sourceLuma) * chromaRetention).toInt().coerceIn(0, 255)
        return Triple(outR, outG, outB)
    }

    private fun sampleBilinear(values: FloatArray, width: Int, height: Int, nx: Float, ny: Float): Float {
        val x = nx.coerceIn(0f, 1f) * (width - 1).coerceAtLeast(0)
        val y = ny.coerceIn(0f, 1f) * (height - 1).coerceAtLeast(0)
        val x0 = floor(x).toInt().coerceIn(0, width - 1)
        val y0 = floor(y).toInt().coerceIn(0, height - 1)
        val x1 = min(x0 + 1, width - 1)
        val y1 = min(y0 + 1, height - 1)
        val tx = x - x0
        val ty = y - y0
        val top = values[y0 * width + x0] * (1f - tx) + values[y0 * width + x1] * tx
        val bottom = values[y1 * width + x0] * (1f - tx) + values[y1 * width + x1] * tx
        return (top * (1f - ty) + bottom * ty).coerceIn(0f, 1f)
    }

    private fun estimateBackground(bitmap: Bitmap): Triple<Int, Int, Int> {
        val sample = max(8, min(bitmap.width, bitmap.height) / 18)
        val buffer = IntArray(sample * sample)
        var red = 0L
        var green = 0L
        var blue = 0L
        var count = 0L
        fun addCorner(x: Int, y: Int) {
            bitmap.getPixels(buffer, 0, sample, x, y, sample, sample)
            for (pixel in buffer) {
                red += (pixel shr 16) and 0xFF
                green += (pixel shr 8) and 0xFF
                blue += pixel and 0xFF
                count++
            }
        }
        addCorner(0, 0)
        addCorner(max(0, bitmap.width - sample), 0)
        addCorner(0, max(0, bitmap.height - sample))
        addCorner(max(0, bitmap.width - sample), max(0, bitmap.height - sample))
        if (count == 0L) return Triple(255, 255, 255)
        return Triple((red / count).toInt(), (green / count).toInt(), (blue / count).toInt())
    }

    private fun shadowOrWhite(nx: Float, ny: Float, bounds: TargetMask.Bounds?): Int {
        if (bounds == null) return 0xFFFFFFFF.toInt()
        val objectWidth = bounds.width.coerceAtLeast(0.02f)
        val centerX = bounds.centerX
        val centerY = (bounds.bottom + 0.012f).coerceAtMost(0.995f)
        val radiusX = objectWidth * 0.34f
        val radiusY = max(0.006f, bounds.height * 0.018f)
        val dx = (nx - centerX) / radiusX
        val dy = (ny - centerY) / radiusY
        val distance = dx * dx * 2.0f + dy * dy * 2.8f
        if (distance > 6.0f) return 0xFFFFFFFF.toInt()
        val strength = (exp(-distance) * 0.09f).coerceIn(0f, 0.09f)
        if (strength < 0.004f) return 0xFFFFFFFF.toInt()
        val value = (255f * (1f - strength) + 185f * strength).toInt().coerceIn(0, 255)
        return (0xFF shl 24) or (value shl 16) or (value shl 8) or value
    }

    private fun luma(red: Int, green: Int, blue: Int): Float = red * 0.2126f + green * 0.7152f + blue * 0.0722f

    private fun smoothStep(edge0: Float, edge1: Float, value: Float): Float {
        val t = ((value - edge0) / (edge1 - edge0)).coerceIn(0f, 1f)
        return t * t * (3f - 2f * t)
    }
}
