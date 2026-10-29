package com.votry.productstudio.ml

import android.content.Context
import android.graphics.Bitmap
import com.votry.productstudio.cloud.CloudflareAiClient
import com.votry.productstudio.cloud.CloudflareConfigStore
import com.votry.productstudio.ml.sam.TargetMask
import java.io.File

class HybridBackgroundRemover(private val context: Context) {
    suspend fun process(
        source: Bitmap,
        modelFile: File,
        target: TargetMask,
        onProgress: (Int, String) -> Unit,
    ): Bitmap {
        val config = CloudflareConfigStore(context).load()
        if (config != null) {
            return try {
                val matte = MatteAnalyzer().analyze(source, modelFile, onProgress)
                CloudflareAiClient().enhance(source, target, matte, config, onProgress)
            } catch (_: Throwable) {
                onProgress(76, "AI Ultra indisponible — mode local…")
                BackgroundRemover().process(source, modelFile, target, onProgress)
            }
        }
        return BackgroundRemover().process(source, modelFile, target, onProgress)
    }
}
