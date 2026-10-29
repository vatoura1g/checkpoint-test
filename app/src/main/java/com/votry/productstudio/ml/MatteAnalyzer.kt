package com.votry.productstudio.ml

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import android.graphics.Bitmap
import java.io.File
import java.nio.FloatBuffer
import kotlin.math.exp
import kotlin.math.max
import kotlin.math.min
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class MatteAnalyzer {
    private val environment = OrtEnvironment.getEnvironment()

    suspend fun analyze(
        source: Bitmap,
        modelFile: File,
        onProgress: (Int, String) -> Unit,
    ): MatteData = withContext(Dispatchers.Default) {
        onProgress(46, "Analyse fine du verre…")
        val options = OrtSession.SessionOptions().apply {
            setIntraOpNumThreads(4)
            setInterOpNumThreads(1)
            setOptimizationLevel(OrtSession.SessionOptions.OptLevel.ALL_OPT)
        }
        try {
            environment.createSession(modelFile.absolutePath, options).use { session ->
                val input = ImagePreprocessor.prepareInput(source)
                val shape = longArrayOf(1, 3, ModelConfig.SIZE.toLong(), ModelConfig.SIZE.toLong())
                val inputName = session.inputNames.first()
                onProgress(56, "Calcul de la transparence…")
                val raw: FloatArray
                var width: Int
                var height: Int
                OnnxTensor.createTensor(environment, FloatBuffer.wrap(input), shape).use { tensor ->
                    session.run(mapOf(inputName to tensor)).use { result ->
                        val output = result[0] as OnnxTensor
                        val outputShape = output.info.shape
                        height = outputShape[outputShape.size - 2].toInt()
                        width = outputShape[outputShape.size - 1].toInt()
                        raw = FloatArray(width * height)
                        val buffer = output.floatBuffer
                            ?: throw IllegalStateException("Le moteur de transparence n'a pas produit de masque.")
                        buffer.rewind()
                        buffer.get(raw, 0, min(raw.size, buffer.remaining()))
                    }
                }
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
                onProgress(69, "Carte du verre prête ✓")
                MatteData(alpha, width, height)
            }
        } finally {
            options.close()
        }
    }
}
