package com.votry.productstudio.ml.sam

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import java.nio.FloatBuffer
import kotlin.math.max

/**
 * Adapted from ooooooomg/mobilesam-android (Apache-2.0).
 * Modified for Ghazi to use a positive user point plus one negative point,
 * without YOLO or any AGPL component.
 */
class SamMaskDecoder(
    private val environment: OrtEnvironment,
    modelPath: String,
) : AutoCloseable {
    companion object {
        private const val EMBED_CHANNELS = 256
        private const val EMBED_SIZE = 24
        private const val MASK_INPUT_SIZE = 96
        private const val MAX_MASK_EDGE = 1024
    }

    private val options = OrtSession.SessionOptions().apply {
        setIntraOpNumThreads(4)
        setInterOpNumThreads(1)
        setOptimizationLevel(OrtSession.SessionOptions.OptLevel.ALL_OPT)
    }
    private val session = environment.createSession(modelPath, options)

    fun decodePoint(
        embedding: FloatArray,
        scale: Float,
        sourceWidth: Int,
        sourceHeight: Int,
        normalizedX: Float,
        normalizedY: Float,
    ): TargetMask {
        val nx = normalizedX.coerceIn(0f, 1f)
        val ny = normalizedY.coerceIn(0f, 1f)
        val positiveX = nx * sourceWidth * scale
        val positiveY = ny * sourceHeight * scale

        // A negative point on the opposite outer area helps SAM avoid selecting
        // a large background object touching or overlapping the product.
        val negativeNx = if (nx < 0.5f) 0.96f else 0.04f
        val negativeNy = if (ny < 0.5f) 0.96f else 0.04f
        val coords = floatArrayOf(
            positiveX, positiveY,
            negativeNx * sourceWidth * scale, negativeNy * sourceHeight * scale,
        )
        val labels = floatArrayOf(1f, 0f)

        val longest = max(sourceWidth, sourceHeight).coerceAtLeast(1)
        val outputScale = if (longest <= MAX_MASK_EDGE) 1f else MAX_MASK_EDGE.toFloat() / longest.toFloat()
        val maskWidth = (sourceWidth * outputScale).toInt().coerceAtLeast(1)
        val maskHeight = (sourceHeight * outputScale).toInt().coerceAtLeast(1)
        val originalSize = floatArrayOf(maskHeight.toFloat(), maskWidth.toFloat())
        val maskInput = FloatArray(MASK_INPUT_SIZE * MASK_INPUT_SIZE)
        val hasMask = floatArrayOf(0f)

        val tensors = mutableMapOf<String, OnnxTensor>()
        try {
            tensors["image_embeddings"] = OnnxTensor.createTensor(
                environment,
                FloatBuffer.wrap(embedding),
                longArrayOf(1, EMBED_CHANNELS.toLong(), EMBED_SIZE.toLong(), EMBED_SIZE.toLong()),
            )
            tensors["point_coords"] = OnnxTensor.createTensor(
                environment,
                FloatBuffer.wrap(coords),
                longArrayOf(1, 2, 2),
            )
            tensors["point_labels"] = OnnxTensor.createTensor(
                environment,
                FloatBuffer.wrap(labels),
                longArrayOf(1, 2),
            )
            tensors["mask_input"] = OnnxTensor.createTensor(
                environment,
                FloatBuffer.wrap(maskInput),
                longArrayOf(1, 1, MASK_INPUT_SIZE.toLong(), MASK_INPUT_SIZE.toLong()),
            )
            tensors["has_mask_input"] = OnnxTensor.createTensor(
                environment,
                FloatBuffer.wrap(hasMask),
                longArrayOf(1),
            )
            tensors["orig_im_size"] = OnnxTensor.createTensor(
                environment,
                FloatBuffer.wrap(originalSize),
                longArrayOf(2),
            )

            session.run(tensors).use { results ->
                val maskTensor = results[0] as OnnxTensor
                val scoreTensor = results[1] as OnnxTensor
                val shape = maskTensor.info.shape
                val outputHeight = shape[shape.size - 2].toInt()
                val outputWidth = shape[shape.size - 1].toInt()
                val buffer = maskTensor.floatBuffer
                    ?: throw IllegalStateException("Le moteur de sélection n'a pas produit de masque.")
                val mask = FloatArray(outputWidth * outputHeight)
                buffer.get(mask, 0, minOf(mask.size, buffer.remaining()))
                val scoreBuffer = scoreTensor.floatBuffer
                val score = if (scoreBuffer != null && scoreBuffer.remaining() > 0) scoreBuffer.get(0) else 0f
                return TargetMask(mask, outputWidth, outputHeight, score)
            }
        } finally {
            tensors.values.forEach { runCatching { it.close() } }
        }
    }

    override fun close() {
        session.close()
        options.close()
    }
}
