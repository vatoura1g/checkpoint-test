package com.votry.productstudio.ml

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import android.graphics.Bitmap
import com.votry.productstudio.processing.PixelMath
import java.io.File
import java.nio.FloatBuffer
import kotlin.math.exp
import kotlin.math.max
import kotlin.math.min
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class BackgroundRemover {
    private val env = OrtEnvironment.getEnvironment()

    suspend fun process(source: Bitmap, modelFile: File, onProgress: (String) -> Unit): Bitmap = withContext(Dispatchers.Default) {
        onProgress("Analyse du produit…")
        val options = OrtSession.SessionOptions().apply {
            setOptimizationLevel(OrtSession.SessionOptions.OptLevel.ALL_OPT)
            try { addNnapi() } catch (_: Throwable) { }
        }
        val session = try {
            env.createSession(modelFile.absolutePath, options)
        } catch (_: Throwable) {
            options.close()
            val cpuOptions = OrtSession.SessionOptions().apply {
                setOptimizationLevel(OrtSession.SessionOptions.OptLevel.ALL_OPT)
            }
            env.createSession(modelFile.absolutePath, cpuOptions)
        }

        try {
            val input = ImagePreprocessor.prepareInput(source)
            val shape = longArrayOf(1, 3, ModelConfig.SIZE.toLong(), ModelConfig.SIZE.toLong())
            val inputName = session.inputNames.first()
            onProgress("Détourage haute qualité…")
            val rawMask: FloatArray
            var mw: Int
            var mh: Int
            OnnxTensor.createTensor(env, FloatBuffer.wrap(input), shape).use { tensor ->
                session.run(mapOf(inputName to tensor)).use { result ->
                    val out = result[0] as OnnxTensor
                    val outShape = out.info.shape
                    mh = outShape[outShape.size - 2].toInt()
                    mw = outShape[outShape.size - 1].toInt()
                    rawMask = FloatArray(mw * mh)
                    val fb = out.floatBuffer
                    fb.rewind()
                    fb.get(rawMask, 0, min(rawMask.size, fb.remaining()))
                }
            }
            onProgress("Nettoyage du verre…")
            val alpha = normalizeSigmoid(rawMask)
            composeWhite(source, alpha, mw, mh)
        } finally {
            session.close()
            options.close()
        }
    }

    private fun normalizeSigmoid(raw: FloatArray): FloatArray {
        var lo = Float.MAX_VALUE
        var hi = -Float.MAX_VALUE
        val sig = FloatArray(raw.size)
        for (i in raw.indices) {
            val v = (1f / (1f + exp(-raw[i]))).coerceIn(0f, 1f)
            sig[i] = v
            lo = min(lo, v)
            hi = max(hi, v)
        }
        val range = max(hi - lo, 1e-6f)
        for (i in sig.indices) sig[i] = ((sig[i] - lo) / range).coerceIn(0f, 1f)
        return sig
    }

    private fun composeWhite(source: Bitmap, alphaSmall: FloatArray, mw: Int, mh: Int): Bitmap {
        val w = source.width
        val h = source.height
        val src = IntArray(w * h)
        source.getPixels(src, 0, w, 0, 0, w, h)
        val bg = estimateBackground(src, w, h)
        val out = IntArray(src.size)
        for (y in 0 until h) {
            val my = y * mh / h
            for (x in 0 until w) {
                val mx = x * mw / w
                val aFloat = alphaSmall[my * mw + mx].coerceIn(0f, 1f)
                val a = (aFloat * 255f).toInt().coerceIn(0, 255)
                val p = src[y * w + x]
                if (a <= 4) {
                    out[y * w + x] = 0xFFFFFFFF.toInt()
                    continue
                }
                val sr = (p shr 16) and 0xFF
                val sg = (p shr 8) and 0xFF
                val sb = p and 0xFF
                val preserve = a >= 220
                val (fr, fg, fb) = if (preserve || aFloat < 0.08f) {
                    Triple(sr, sg, sb)
                } else {
                    // Conservative foreground decontamination: estimate the original scene color
                    // from image corners, then invert alpha compositing. Opaque labels/tickets are untouched.
                    val denom = max(aFloat, 0.12f)
                    Triple(
                        ((sr - (1f - aFloat) * bg.first) / denom).toInt().coerceIn(0, 255),
                        ((sg - (1f - aFloat) * bg.second) / denom).toInt().coerceIn(0, 255),
                        ((sb - (1f - aFloat) * bg.third) / denom).toInt().coerceIn(0, 255),
                    )
                }
                val r = PixelMath.compositeChannel(fr, a)
                val g = PixelMath.compositeChannel(fg, a)
                val b = PixelMath.compositeChannel(fb, a)
                out[y * w + x] = (0xFF shl 24) or (r shl 16) or (g shl 8) or b
            }
        }
        return Bitmap.createBitmap(out, w, h, Bitmap.Config.ARGB_8888)
    }

    private fun estimateBackground(pixels: IntArray, w: Int, h: Int): Triple<Int, Int, Int> {
        val sample = max(8, min(w, h) / 16)
        var rs = 0L; var gs = 0L; var bs = 0L; var count = 0L
        fun addRect(x0: Int, y0: Int, x1: Int, y1: Int) {
            for (y in y0 until y1) for (x in x0 until x1) {
                val p = pixels[y * w + x]
                rs += (p shr 16) and 0xFF; gs += (p shr 8) and 0xFF; bs += p and 0xFF; count++
            }
        }
        addRect(0, 0, min(sample, w), min(sample, h))
        addRect(max(0, w - sample), 0, w, min(sample, h))
        addRect(0, max(0, h - sample), min(sample, w), h)
        addRect(max(0, w - sample), max(0, h - sample), w, h)
        if (count == 0L) return Triple(255, 255, 255)
        return Triple((rs / count).toInt(), (gs / count).toInt(), (bs / count).toInt())
    }
}
