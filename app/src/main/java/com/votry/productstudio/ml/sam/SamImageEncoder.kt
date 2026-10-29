package com.votry.productstudio.ml.sam

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import android.graphics.Bitmap
import android.graphics.Color
import java.nio.FloatBuffer
import kotlin.math.max

/**
 * Adapted from ooooooomg/mobilesam-android (Apache-2.0).
 * Modified for Ghazi: file-backed model session and one-shot still-image use.
 */
class SamImageEncoder(
    private val environment: OrtEnvironment,
    modelPath: String,
) : AutoCloseable {
    companion object {
        private const val IMAGE_SIZE = 384
        private val MEAN = floatArrayOf(123.675f, 116.28f, 103.53f)
        private val STD = floatArrayOf(58.395f, 57.12f, 57.375f)
    }

    data class Preprocessed(
        val input: FloatArray,
        val scale: Float,
        val newHeight: Int,
        val newWidth: Int,
    )

    private val options = OrtSession.SessionOptions().apply {
        setIntraOpNumThreads(4)
        setInterOpNumThreads(1)
        setOptimizationLevel(OrtSession.SessionOptions.OptLevel.ALL_OPT)
    }
    private val session = environment.createSession(modelPath, options)

    fun encode(bitmap: Bitmap): Pair<FloatArray, Preprocessed> {
        val prep = preprocess(bitmap)
        val tensor = OnnxTensor.createTensor(
            environment,
            FloatBuffer.wrap(prep.input),
            longArrayOf(1, 3, IMAGE_SIZE.toLong(), IMAGE_SIZE.toLong()),
        )
        tensor.use {
            session.run(mapOf("image" to it)).use { results ->
                val output = results[0] as OnnxTensor
                val buffer = output.floatBuffer
                    ?: throw IllegalStateException("Le moteur de sélection n'a pas produit d'image encodée.")
                val embedding = FloatArray(buffer.remaining())
                buffer.get(embedding)
                return embedding to prep
            }
        }
    }

    private fun preprocess(bitmap: Bitmap): Preprocessed {
        val scale = IMAGE_SIZE.toFloat() / max(bitmap.width, bitmap.height).toFloat()
        val newWidth = (bitmap.width * scale).toInt().coerceAtLeast(1)
        val newHeight = (bitmap.height * scale).toInt().coerceAtLeast(1)
        val resized = Bitmap.createScaledBitmap(bitmap, newWidth, newHeight, true)
        val resizedPixels = IntArray(newWidth * newHeight)
        resized.getPixels(resizedPixels, 0, newWidth, 0, 0, newWidth, newHeight)
        if (resized !== bitmap) resized.recycle()

        val padded = IntArray(IMAGE_SIZE * IMAGE_SIZE)
        for (y in 0 until newHeight) {
            System.arraycopy(resizedPixels, y * newWidth, padded, y * IMAGE_SIZE, newWidth)
        }

        val plane = IMAGE_SIZE * IMAGE_SIZE
        val input = FloatArray(plane * 3)
        for (y in 0 until IMAGE_SIZE) {
            for (x in 0 until IMAGE_SIZE) {
                val pixel = padded[y * IMAGE_SIZE + x]
                val i = y * IMAGE_SIZE + x
                input[i] = (Color.red(pixel) - MEAN[0]) / STD[0]
                input[plane + i] = (Color.green(pixel) - MEAN[1]) / STD[1]
                input[2 * plane + i] = (Color.blue(pixel) - MEAN[2]) / STD[2]
            }
        }
        return Preprocessed(input, scale, newHeight, newWidth)
    }

    override fun close() {
        session.close()
        options.close()
    }
}
