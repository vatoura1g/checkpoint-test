package com.votry.productstudio.cloud

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.util.Base64
import com.votry.productstudio.ml.MatteData
import com.votry.productstudio.ml.sam.TargetMask
import java.io.ByteArrayOutputStream
import java.net.HttpURLConnection
import java.net.URL
import org.json.JSONArray
import org.json.JSONObject
import kotlin.math.max
import kotlin.math.roundToInt
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class CloudflareAiClient {
    companion object {
        private const val MODEL = "@cf/bytedance/stable-diffusion-xl-lightning"
        private const val MAX_EDGE = 1024
    }

    suspend fun enhance(
        source: Bitmap,
        target: TargetMask,
        matte: MatteData,
        config: CloudflareConfigStore.Config,
        onProgress: (Int, String) -> Unit,
    ): Bitmap = withContext(Dispatchers.IO) {
        onProgress(74, "Préparation AI Ultra…")
        val prepared = prepare(source, target, matte)
        try {
            onProgress(80, "Reconstruction du verre…")
            val request = JSONObject().apply {
                put("prompt", "Professional ecommerce product photo of the exact same object. Preserve exact geometry and proportions. Reconstruct realistic transparent clear glass with natural refraction, rim thickness, highlights and subtle reflections. Remove all original environment and every object visible behind the glass. Pure seamless white studio background RGB 255 255 255. Natural soft contact shadow. No extra objects. No invented text or logos.")
                put("negative_prompt", "background objects, clutter, bottle behind glass, duplicated object, distorted geometry, opaque gray glass, smoke, haze, colored background, fake text, changed label, watermark")
                put("width", prepared.width)
                put("height", prepared.height)
                put("image_b64", Base64.encodeToString(prepared.imageBytes, Base64.NO_WRAP))
                put("mask", JSONArray().apply { prepared.maskBytes.forEach { put(it.toInt() and 0xFF) } })
                put("num_steps", 8)
                put("strength", 0.76)
                put("guidance", 6.0)
                put("seed", (System.currentTimeMillis() and 0x7FFFFFFF).toInt())
            }

            val endpoint = "https://api.cloudflare.com/client/v4/accounts/${config.accountId}/ai/run/$MODEL"
            val connection = (URL(endpoint).openConnection() as HttpURLConnection).apply {
                requestMethod = "POST"
                connectTimeout = 25_000
                readTimeout = 120_000
                doOutput = true
                setRequestProperty("Authorization", "Bearer ${config.apiToken}")
                setRequestProperty("Content-Type", "application/json")
                setRequestProperty("Accept", "image/*, application/json")
            }
            connection.outputStream.buffered().use { out ->
                out.write(request.toString().toByteArray(Charsets.UTF_8))
            }
            val status = connection.responseCode
            val contentType = connection.contentType.orEmpty()
            val responseBytes = (if (status in 200..299) connection.inputStream else connection.errorStream)
                ?.use { it.readBytes() } ?: ByteArray(0)
            connection.disconnect()

            if (status !in 200..299) {
                throw IllegalStateException("AI Ultra indisponible ($status)")
            }

            val generated = decodeCloudImage(responseBytes, contentType)
                ?: throw IllegalStateException("AI Ultra n'a pas renvoyé une image exploitable.")
            val normalized = if (generated.width != prepared.width || generated.height != prepared.height) {
                Bitmap.createScaledBitmap(generated, prepared.width, prepared.height, true).also {
                    if (it !== generated) generated.recycle()
                }
            } else generated

            onProgress(90, "Protection du ticket et du texte…")
            val restored = restoreProtectedAndForceWhite(
                generated = normalized,
                sourceScaled = prepared.sourceScaled,
                target = target,
                matte = matte,
            )
            normalized.recycle()
            val fullSize = Bitmap.createScaledBitmap(restored, source.width, source.height, true)
            if (fullSize !== restored) restored.recycle()
            onProgress(95, "AI Ultra terminé ✓")
            fullSize
        } finally {
            prepared.sourceScaled.recycle()
        }
    }

    private data class Prepared(
        val sourceScaled: Bitmap,
        val imageBytes: ByteArray,
        val maskBytes: ByteArray,
        val width: Int,
        val height: Int,
    )

    private fun prepare(source: Bitmap, target: TargetMask, matte: MatteData): Prepared {
        val scale = minOf(1f, MAX_EDGE.toFloat() / max(source.width, source.height).toFloat())
        var width = (source.width * scale).roundToInt().coerceAtLeast(256)
        var height = (source.height * scale).roundToInt().coerceAtLeast(256)
        width = (((width + 31) / 32) * 32).coerceAtMost(2048)
        height = (((height + 31) / 32) * 32).coerceAtMost(2048)
        val scaled = Bitmap.createScaledBitmap(source, width, height, true)

        val imageBytes = ByteArrayOutputStream().use { out ->
            scaled.compress(Bitmap.CompressFormat.PNG, 100, out)
            out.toByteArray()
        }

        val maskPixels = IntArray(width * height)
        for (y in 0 until height) {
            val ny = if (height <= 1) 0f else y.toFloat() / (height - 1)
            for (x in 0 until width) {
                val nx = if (width <= 1) 0f else x.toFloat() / (width - 1)
                val targetP = target.sampleNormalized(nx, ny)
                val alpha = matte.sampleNormalized(nx, ny)
                val protectedOpaque = targetP >= 0.60f && alpha >= 0.95f
                val value = if (protectedOpaque) 0 else 255
                maskPixels[y * width + x] = Color.rgb(value, value, value)
            }
        }
        val maskBitmap = Bitmap.createBitmap(maskPixels, width, height, Bitmap.Config.ARGB_8888)
        val maskBytes = ByteArrayOutputStream().use { out ->
            maskBitmap.compress(Bitmap.CompressFormat.PNG, 100, out)
            out.toByteArray()
        }
        maskBitmap.recycle()
        return Prepared(scaled, imageBytes, maskBytes, width, height)
    }

    private fun decodeCloudImage(bytes: ByteArray, contentType: String): Bitmap? {
        if (bytes.isEmpty()) return null
        if (contentType.startsWith("image/")) return BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
        return runCatching {
            val json = JSONObject(bytes.toString(Charsets.UTF_8))
            val result = json.optJSONObject("result") ?: json
            val base64 = result.optString("image", result.optString("image_b64", ""))
            if (base64.isBlank()) null else {
                val decoded = Base64.decode(base64.substringAfter("base64,"), Base64.DEFAULT)
                BitmapFactory.decodeByteArray(decoded, 0, decoded.size)
            }
        }.getOrNull()
    }

    private fun restoreProtectedAndForceWhite(
        generated: Bitmap,
        sourceScaled: Bitmap,
        target: TargetMask,
        matte: MatteData,
    ): Bitmap {
        val width = generated.width
        val height = generated.height
        val genPixels = IntArray(width * height)
        val srcPixels = IntArray(width * height)
        generated.getPixels(genPixels, 0, width, 0, 0, width, height)
        sourceScaled.getPixels(srcPixels, 0, width, 0, 0, width, height)

        for (y in 0 until height) {
            val ny = if (height <= 1) 0f else y.toFloat() / (height - 1)
            for (x in 0 until width) {
                val nx = if (width <= 1) 0f else x.toFloat() / (width - 1)
                val i = y * width + x
                val targetP = target.sampleNormalized(nx, ny)
                val alpha = matte.sampleNormalized(nx, ny)
                when {
                    targetP < 0.24f -> genPixels[i] = Color.WHITE
                    targetP >= 0.60f && alpha >= 0.95f -> genPixels[i] = srcPixels[i]
                    targetP < 0.50f -> {
                        val t = ((targetP - 0.24f) / 0.26f).coerceIn(0f, 1f)
                        val p = genPixels[i]
                        val r = (Color.red(p) * t + 255f * (1f - t)).toInt().coerceIn(0, 255)
                        val g = (Color.green(p) * t + 255f * (1f - t)).toInt().coerceIn(0, 255)
                        val b = (Color.blue(p) * t + 255f * (1f - t)).toInt().coerceIn(0, 255)
                        genPixels[i] = Color.rgb(r, g, b)
                    }
                }
            }
        }
        return Bitmap.createBitmap(genPixels, width, height, Bitmap.Config.ARGB_8888)
    }
}
