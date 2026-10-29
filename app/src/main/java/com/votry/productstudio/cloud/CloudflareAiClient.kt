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
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.roundToInt
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class CloudflareAiClient {
    companion object {
        private const val MODEL = "@cf/runwayml/stable-diffusion-v1-5-inpainting"
        private const val MAX_EDGE = 768
    }

    suspend fun enhance(
        source: Bitmap,
        target: TargetMask,
        matte: MatteData,
        config: CloudflareConfigStore.Config,
        onProgress: (Int, String) -> Unit,
    ): Bitmap = withContext(Dispatchers.IO) {
        onProgress(72, "Préparation du masque AI Ultra…")
        val prepared = prepare(source, target, matte)
        try {
            onProgress(80, "AI ULTRA — reconstruction du verre…")
            val request = JSONObject().apply {
                put("prompt", "Professional ecommerce studio photo of the exact same selected product. Keep the exact silhouette, proportions, opening, base and geometry. Reconstruct transparent clear glass naturally with realistic refraction, rim thickness, subtle highlights and clean optical edges. Remove every original scene object visible through the glass. Pure seamless white background RGB 255 255 255. Soft realistic contact shadow. Preserve protected labels and printed details exactly. No extra objects.")
                put("negative_prompt", "background clutter, bottle behind glass, furniture, duplicated object, broken rim, missing rim, cut product, detached fragments, distorted geometry, opaque gray glass, fog, smoke, fake text, changed logo, watermark")
                put("width", prepared.width)
                put("height", prepared.height)
                put("image_b64", Base64.encodeToString(prepared.cloudInputBytes, Base64.NO_WRAP))
                put("mask", JSONArray().apply { prepared.maskBytes.forEach { put(it.toInt() and 0xFF) } })
                put("num_steps", 20)
                put("strength", 0.68)
                put("guidance", 7.5)
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
                val detail = responseBytes.toString(Charsets.UTF_8).take(500)
                throw IllegalStateException("Cloudflare HTTP $status — $detail")
            }

            val generated = decodeCloudImage(responseBytes, contentType)
                ?: throw IllegalStateException("Cloudflare n'a pas renvoyé d'image exploitable")
            val normalized = if (generated.width != prepared.width || generated.height != prepared.height) {
                Bitmap.createScaledBitmap(generated, prepared.width, prepared.height, true).also {
                    if (it !== generated) generated.recycle()
                }
            } else generated

            onProgress(90, "AI ULTRA — protection du ticket et des inscriptions…")
            val restored = restoreProtectedAndForceWhite(
                generated = normalized,
                sourceScaled = prepared.sourceScaled,
                target = target,
                matte = matte,
            )
            normalized.recycle()
            val fullSize = Bitmap.createScaledBitmap(restored, source.width, source.height, true)
            if (fullSize !== restored) restored.recycle()
            onProgress(95, "AI ULTRA terminé ✓")
            fullSize
        } finally {
            prepared.sourceScaled.recycle()
            prepared.cloudInput.recycle()
        }
    }

    private data class Prepared(
        val sourceScaled: Bitmap,
        val cloudInput: Bitmap,
        val cloudInputBytes: ByteArray,
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

        val inputPixels = IntArray(width * height)
        scaled.getPixels(inputPixels, 0, width, 0, 0, width, height)
        val maskPixels = IntArray(width * height)
        for (y in 0 until height) {
            val ny = if (height <= 1) 0f else y.toFloat() / (height - 1)
            for (x in 0 until width) {
                val nx = if (width <= 1) 0f else x.toFloat() / (width - 1)
                val i = y * width + x
                val alpha = matte.sampleNormalized(nx, ny)
                val targetP = augmentedTargetProbability(target, matte, nx, ny)
                val protectedOpaque = targetP >= 0.58f && alpha >= 0.95f
                if (targetP < 0.20f) inputPixels[i] = Color.WHITE
                val maskValue = if (protectedOpaque) 0 else 255
                maskPixels[i] = Color.rgb(maskValue, maskValue, maskValue)
            }
        }

        val cloudInput = Bitmap.createBitmap(inputPixels, width, height, Bitmap.Config.ARGB_8888)
        val inputBytes = ByteArrayOutputStream().use { out ->
            cloudInput.compress(Bitmap.CompressFormat.PNG, 100, out)
            out.toByteArray()
        }
        val maskBitmap = Bitmap.createBitmap(maskPixels, width, height, Bitmap.Config.ARGB_8888)
        val maskBytes = ByteArrayOutputStream().use { out ->
            maskBitmap.compress(Bitmap.CompressFormat.PNG, 100, out)
            out.toByteArray()
        }
        maskBitmap.recycle()
        return Prepared(scaled, cloudInput, inputBytes, maskBytes, width, height)
    }

    private fun augmentedTargetProbability(target: TargetMask, matte: MatteData, nx: Float, ny: Float): Float {
        val base = target.sampleNormalized(nx, ny)
        val bounds = target.bounds(0.35f) ?: return base
        val padX = max(0.012f, bounds.width * 0.045f)
        val padY = max(0.012f, bounds.height * 0.045f)
        val insideExpanded = nx >= bounds.left - padX && nx <= bounds.right + padX &&
            ny >= bounds.top - padY && ny <= bounds.bottom + padY
        if (!insideExpanded) return base

        val nearOuterShell = abs(nx - bounds.left) <= padX * 1.4f ||
            abs(nx - bounds.right) <= padX * 1.4f ||
            abs(ny - bounds.top) <= padY * 1.6f ||
            abs(ny - bounds.bottom) <= padY * 1.4f
        val matteAlpha = matte.sampleNormalized(nx, ny)
        return if (nearOuterShell && matteAlpha >= 0.50f) max(base, 0.72f) else base
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
                val targetP = augmentedTargetProbability(target, matte, nx, ny)
                val alpha = matte.sampleNormalized(nx, ny)
                when {
                    targetP < 0.18f -> genPixels[i] = Color.WHITE
                    targetP >= 0.58f && alpha >= 0.95f -> genPixels[i] = srcPixels[i]
                    targetP < 0.48f -> {
                        val t = ((targetP - 0.18f) / 0.30f).coerceIn(0f, 1f)
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
