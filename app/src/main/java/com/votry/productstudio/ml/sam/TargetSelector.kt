package com.votry.productstudio.ml.sam

import ai.onnxruntime.OrtEnvironment
import android.content.Context
import android.graphics.Bitmap
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext

class TargetSelector(private val context: Context) {
    companion object {
        private const val ENCODER_ASSET = "mobile_sam_encoder.onnx"
        private const val DECODER_ASSET = "mobile_sam_decoder.onnx"
        private const val ENCODER_MIN_BYTES = 27_000_000L
        private const val DECODER_MIN_BYTES = 16_000_000L
    }

    suspend fun select(
        source: Bitmap,
        normalizedX: Float,
        normalizedY: Float,
        onProgress: (Int, String) -> Unit,
    ): TargetMask = withContext(Dispatchers.Default) {
        ensureActive()
        onProgress(8, "Identification du produit choisi…")
        val encoderFile = ensureAssetFile(ENCODER_ASSET, ENCODER_MIN_BYTES)
        val decoderFile = ensureAssetFile(DECODER_ASSET, DECODER_MIN_BYTES)
        val environment = OrtEnvironment.getEnvironment()

        val embedding: FloatArray
        val prep: SamImageEncoder.Preprocessed
        SamImageEncoder(environment, encoderFile.absolutePath).use { encoder ->
            onProgress(15, "Compréhension de la photo…")
            val encoded = encoder.encode(source)
            embedding = encoded.first
            prep = encoded.second
        }

        ensureActive()
        val target = SamMaskDecoder(environment, decoderFile.absolutePath).use { decoder ->
            onProgress(24, "Sélection précise du produit…")
            decoder.decodePoint(
                embedding = embedding,
                scale = prep.scale,
                sourceWidth = source.width,
                sourceHeight = source.height,
                normalizedX = normalizedX,
                normalizedY = normalizedY,
            )
        }

        val occupancy = target.occupancy(0.5f)
        val tapProbability = target.sampleNormalized(normalizedX, normalizedY)
        val bounds = target.bounds(0.45f)
        if (bounds == null || occupancy < 0.0015f || occupancy > 0.78f || tapProbability < 0.20f) {
            throw IllegalStateException("Ghazi n'est pas sûr du produit sélectionné. Touchez bien le centre du produit puis réessayez.")
        }
        onProgress(30, "Produit verrouillé ✓")
        target
    }

    private fun ensureAssetFile(assetName: String, minimumBytes: Long): File {
        val modelDir = File(context.filesDir, "models").apply { mkdirs() }
        val target = File(modelDir, assetName)
        if (target.exists() && target.length() >= minimumBytes) return target
        val temporary = File(modelDir, "$assetName.part")
        if (temporary.exists()) temporary.delete()
        context.assets.open(assetName).use { input ->
            temporary.outputStream().buffered().use { output -> input.copyTo(output, 256 * 1024) }
        }
        if (temporary.length() < minimumBytes) {
            temporary.delete()
            throw IllegalStateException("Le moteur de sélection Ghazi est incomplet.")
        }
        if (target.exists()) target.delete()
        if (!temporary.renameTo(target)) {
            temporary.copyTo(target, overwrite = true)
            temporary.delete()
        }
        return target
    }
}
