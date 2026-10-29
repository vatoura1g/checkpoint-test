package com.votry.productstudio.ml

import android.graphics.Bitmap
import com.votry.productstudio.cloud.CloudflareAiClient
import com.votry.productstudio.cloud.CloudflareConfigStore
import com.votry.productstudio.ml.sam.TargetMask
import java.io.File

class BackgroundRemover {
    suspend fun process(
        source: Bitmap,
        modelFile: File,
        target: TargetMask,
        onProgress: (Int, String) -> Unit,
    ): Bitmap {
        val context = GhaziRuntime.contextOrNull()
        if (context != null) {
            val config = CloudflareConfigStore(context).load()
            if (config != null) {
                try {
                    val matte = MatteAnalyzer().analyze(source, modelFile, onProgress)
                    return CloudflareAiClient().enhance(source, target, matte, config, onProgress)
                } catch (_: Throwable) {
                    onProgress(76, "AI Ultra indisponible — mode local…")
                }
            }
        }
        return LocalBackgroundRemover().process(source, modelFile, target, onProgress)
    }
}
