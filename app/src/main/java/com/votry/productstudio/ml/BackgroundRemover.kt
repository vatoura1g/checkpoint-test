package com.votry.productstudio.ml

import android.graphics.Bitmap
import com.votry.productstudio.cloud.CloudflareAiClient
import com.votry.productstudio.cloud.CloudflareConfigStore
import com.votry.productstudio.ml.sam.TargetMask
import com.votry.productstudio.ml.sam.TargetMaskCleaner
import java.io.File

class BackgroundRemover {
    suspend fun process(
        source: Bitmap,
        modelFile: File,
        target: TargetMask,
        onProgress: (Int, String) -> Unit,
    ): Bitmap {
        val cleanedTarget = TargetMaskCleaner.keepLargestComponent(target)
        val context = GhaziRuntime.contextOrNull()
        if (context != null) {
            val config = CloudflareConfigStore(context).load()
            if (config != null) {
                onProgress(44, "AI ULTRA activé ✓")
                val matte = MatteAnalyzer().analyze(source, modelFile, onProgress)
                return try {
                    CloudflareAiClient().enhance(source, cleanedTarget, matte, config, onProgress)
                } catch (t: Throwable) {
                    throw IllegalStateException("AI Ultra a échoué : ${t.message ?: t.javaClass.simpleName}", t)
                }
            }
        }
        onProgress(44, "Mode LOCAL")
        return LocalBackgroundRemover().process(source, modelFile, cleanedTarget, onProgress)
    }
}
