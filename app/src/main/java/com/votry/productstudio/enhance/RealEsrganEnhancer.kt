package com.votry.productstudio.enhance

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import java.io.File
import java.nio.FloatBuffer
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext

class RealEsrganEnhancer(private val context: Context) {
    companion object {
        private const val INPUT_MAX_EDGE = 720
        private const val OUTPUT_MAX_EDGE = 2600
        private const val TILE_SIZE = 128
        private const val TILE_PAD = 8
        private const val SCALE = 4
    }

    suspend fun enhance(input: Bitmap, onProgress: (Int, String) -> Unit): Bitmap = withContext(Dispatchers.Default) {
        onProgress(0, "Préparation de l'Enhance HD…")
        val model = EnhancerModelManager.ensureModel(context) { download ->
            onProgress((download * 18 / 100).coerceIn(0, 18), "Téléchargement Enhance HD… $download %")
        }
        ensureActive()
        val working = scaleDown(input, INPUT_MAX_EDGE)
        try {
            val enhanced4x = runModel(working, model) { done, total ->
                val p = 18 + ((done * 72) / max(1, total))
                onProgress(p.coerceIn(18, 90), "Enhance HD — détails $done/$total")
            }
            try {
                onProgress(92, "Protection des tickets et inscriptions…")
                val targetW: Int
                val targetH: Int
                val longest = max(enhanced4x.width, enhanced4x.height)
                if (longest > OUTPUT_MAX_EDGE) {
                    val s = OUTPUT_MAX_EDGE.toFloat() / longest.toFloat()
                    targetW = (enhanced4x.width * s).roundToInt().coerceAtLeast(1)
                    targetH = (enhanced4x.height * s).roundToInt().coerceAtLeast(1)
                } else {
                    targetW = enhanced4x.width
                    targetH = enhanced4x.height
                }
                val enhancedFinal = if (enhanced4x.width == targetW && enhanced4x.height == targetH) {
                    enhanced4x.copy(Bitmap.Config.ARGB_8888, false)
                } else {
                    Bitmap.createScaledBitmap(enhanced4x, targetW, targetH, true)
                }
                val originalReference = Bitmap.createScaledBitmap(input, targetW, targetH, true)
                try {
                    val blended = conservativeBlend(originalReference, enhancedFinal)
                    onProgress(100, "Enhance HD terminé ✓")
                    blended
                } finally {
                    originalReference.recycle()
                    enhancedFinal.recycle()
                }
            } finally {
                enhanced4x.recycle()
            }
        } finally {
            if (working !== input && !working.isRecycled) working.recycle()
        }
    }

    private fun runModel(input: Bitmap, model: File, progress: (Int, Int) -> Unit): Bitmap {
        val env = OrtEnvironment.getEnvironment()
        val options = OrtSession.SessionOptions().apply {
            setOptimizationLevel(OrtSession.SessionOptions.OptLevel.ALL_OPT)
            setIntraOpNumThreads(max(1, min(4, Runtime.getRuntime().availableProcessors())))
            setInterOpNumThreads(1)
        }
        try {
            env.createSession(model.absolutePath, options).use { session ->
                val output = Bitmap.createBitmap(input.width * SCALE, input.height * SCALE, Bitmap.Config.ARGB_8888)
                val tilesX = ceil(input.width.toDouble() / TILE_SIZE).toInt()
                val tilesY = ceil(input.height.toDouble() / TILE_SIZE).toInt()
                val totalTiles = tilesX * tilesY
                var current = 0
                for (ty in 0 until tilesY) {
                    for (tx in 0 until tilesX) {
                        current++
                        val startX = tx * TILE_SIZE
                        val endX = min(startX + TILE_SIZE, input.width)
                        val startY = ty * TILE_SIZE
                        val endY = min(startY + TILE_SIZE, input.height)
                        val paddedStartX = max(0, startX - TILE_PAD)
                        val paddedEndX = min(input.width, endX + TILE_PAD)
                        val paddedStartY = max(0, startY - TILE_PAD)
                        val paddedEndY = min(input.height, endY + TILE_PAD)
                        val padLeft = startX - paddedStartX
                        val padTop = startY - paddedStartY
                        val tileW = paddedEndX - paddedStartX
                        val tileH = paddedEndY - paddedStartY
                        val pixels = IntArray(tileW * tileH)
                        input.getPixels(pixels, 0, tileW, paddedStartX, paddedStartY, tileW, tileH)
                        val plane = tileW * tileH
                        val buffer = FloatBuffer.allocate(plane * 3)
                        for (i in 0 until plane) buffer.put(i, ((pixels[i] shr 16) and 0xFF) / 255f)
                        for (i in 0 until plane) buffer.put(plane + i, ((pixels[i] shr 8) and 0xFF) / 255f)
                        for (i in 0 until plane) buffer.put(2 * plane + i, (pixels[i] and 0xFF) / 255f)
                        val shape = longArrayOf(1, 3, tileH.toLong(), tileW.toLong())
                        OnnxTensor.createTensor(env, buffer, shape).use { tensor ->
                            session.run(mapOf(session.inputNames.first() to tensor)).use { result ->
                                val out = result[0] as OnnxTensor
                                val fb = out.floatBuffer
                                val outTileW = tileW * SCALE
                                val outTileH = tileH * SCALE
                                val outPlane = outTileW * outTileH
                                val cropW = (endX - startX) * SCALE
                                val cropH = (endY - startY) * SCALE
                                val cropStartX = padLeft * SCALE
                                val cropStartY = padTop * SCALE
                                val crop = IntArray(cropW * cropH)
                                for (row in 0 until cropH) {
                                    val srcRow = cropStartY + row
                                    for (col in 0 until cropW) {
                                        val srcCol = cropStartX + col
                                        val offset = srcRow * outTileW + srcCol
                                        val r = (fb.get(offset).coerceIn(0f, 1f) * 255f).roundToInt()
                                        val g = (fb.get(outPlane + offset).coerceIn(0f, 1f) * 255f).roundToInt()
                                        val b = (fb.get(2 * outPlane + offset).coerceIn(0f, 1f) * 255f).roundToInt()
                                        crop[row * cropW + col] = Color.rgb(r, g, b)
                                    }
                                }
                                output.setPixels(crop, 0, cropW, startX * SCALE, startY * SCALE, cropW, cropH)
                            }
                        }
                        progress(current, totalTiles)
                    }
                }
                return output
            }
        } finally {
            options.close()
        }
    }

    private fun conservativeBlend(original: Bitmap, enhanced: Bitmap): Bitmap {
        val w = original.width
        val h = original.height
        val src = IntArray(w * h)
        val enh = IntArray(w * h)
        original.getPixels(src, 0, w, 0, 0, w, h)
        enhanced.getPixels(enh, 0, w, 0, 0, w, h)
        val out = IntArray(w * h)
        for (y in 0 until h) {
            for (x in 0 until w) {
                val i = y * w + x
                val p = src[i]
                val pr = Color.red(p)
                val pg = Color.green(p)
                val pb = Color.blue(p)
                val luma = pr * 0.2126f + pg * 0.7152f + pb * 0.0722f
                val maxC = max(pr, max(pg, pb)).toFloat()
                val minC = min(pr, min(pg, pb)).toFloat()
                val saturation = if (maxC <= 1f) 0f else (maxC - minC) / maxC
                var edge = 0f
                if (x > 0) edge = max(edge, colorDistance(p, src[i - 1]))
                if (x + 1 < w) edge = max(edge, colorDistance(p, src[i + 1]))
                if (y > 0) edge = max(edge, colorDistance(p, src[i - w]))
                if (y + 1 < h) edge = max(edge, colorDistance(p, src[i + w]))

                // Protect labels, barcodes, Arabic/Latin writing and strong printed detail.
                val protect = (luma < 218f && edge > 34f) || saturation > 0.22f || luma < 150f
                if (protect) {
                    out[i] = p
                } else {
                    val e = enh[i]
                    val mix = if (luma > 242f) 0.55f else 0.38f
                    val r = (Color.red(p) * (1f - mix) + Color.red(e) * mix).roundToInt().coerceIn(0, 255)
                    val g = (Color.green(p) * (1f - mix) + Color.green(e) * mix).roundToInt().coerceIn(0, 255)
                    val b = (Color.blue(p) * (1f - mix) + Color.blue(e) * mix).roundToInt().coerceIn(0, 255)
                    out[i] = Color.rgb(r, g, b)
                }
            }
        }
        return Bitmap.createBitmap(out, w, h, Bitmap.Config.ARGB_8888)
    }

    private fun colorDistance(a: Int, b: Int): Float {
        val dr = abs(Color.red(a) - Color.red(b))
        val dg = abs(Color.green(a) - Color.green(b))
        val db = abs(Color.blue(a) - Color.blue(b))
        return (dr + dg + db) / 3f
    }

    private fun scaleDown(bitmap: Bitmap, maxEdge: Int): Bitmap {
        val longest = max(bitmap.width, bitmap.height)
        if (longest <= maxEdge) return bitmap
        val scale = maxEdge.toFloat() / longest.toFloat()
        return Bitmap.createScaledBitmap(
            bitmap,
            (bitmap.width * scale).roundToInt().coerceAtLeast(1),
            (bitmap.height * scale).roundToInt().coerceAtLeast(1),
            true,
        )
    }
}
