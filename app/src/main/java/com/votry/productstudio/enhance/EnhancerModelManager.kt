package com.votry.productstudio.enhance

import android.content.Context
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import kotlin.coroutines.coroutineContext

object EnhancerModelManager {
    private const val FILE_NAME = "realesrgan_x4plus.onnx"
    private const val URL_STRING = "https://github.com/Parasaran-Python/pixelclarity-android/releases/download/v1.0.0-models/realesrgan_x4plus.onnx"
    private const val EXPECTED_SIZE = 68622543L
    private const val EXPECTED_SHA256 = "f8719e77074a76f7bd152715e936443fe5ef86ceb7933e022a9d08c6a81567f8"

    suspend fun ensureModel(context: Context, onProgress: (Int) -> Unit): File = withContext(Dispatchers.IO) {
        val dir = File(context.noBackupFilesDir, "ghazi-enhance-models").apply { mkdirs() }
        val target = File(dir, FILE_NAME)
        if (target.exists() && target.length() == EXPECTED_SIZE && sha256(target).equals(EXPECTED_SHA256, true)) {
            onProgress(100)
            return@withContext target
        }

        val temp = File(dir, "$FILE_NAME.part")
        if (temp.exists()) temp.delete()
        val digest = MessageDigest.getInstance("SHA-256")
        var connection: HttpURLConnection? = null
        try {
            connection = (URL(URL_STRING).openConnection() as HttpURLConnection).apply {
                connectTimeout = 20_000
                readTimeout = 60_000
                instanceFollowRedirects = true
                setRequestProperty("User-Agent", "Ghazi-Android-Enhancer")
                setRequestProperty("Accept-Encoding", "identity")
            }
            val status = connection.responseCode
            if (status !in 200..299) throw IllegalStateException("Enhance model HTTP $status")
            val total = connection.contentLengthLong.takeIf { it > 0 } ?: EXPECTED_SIZE
            var downloaded = 0L
            connection.inputStream.use { input ->
                temp.outputStream().buffered().use { output ->
                    val buffer = ByteArray(128 * 1024)
                    while (true) {
                        coroutineContext.ensureActive()
                        val read = input.read(buffer)
                        if (read < 0) break
                        output.write(buffer, 0, read)
                        digest.update(buffer, 0, read)
                        downloaded += read
                        onProgress(((downloaded * 100L) / total).toInt().coerceIn(0, 99))
                    }
                }
            }
            val actualSha = digest.digest().joinToString("") { "%02x".format(it) }
            if (!actualSha.equals(EXPECTED_SHA256, true)) {
                temp.delete()
                throw IllegalStateException("Enhance model checksum invalide")
            }
            if (target.exists()) target.delete()
            if (!temp.renameTo(target)) {
                temp.copyTo(target, overwrite = true)
                temp.delete()
            }
            onProgress(100)
            target
        } finally {
            connection?.disconnect()
        }
    }

    private fun sha256(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().buffered().use { input ->
            val buffer = ByteArray(128 * 1024)
            while (true) {
                val read = input.read(buffer)
                if (read < 0) break
                digest.update(buffer, 0, read)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }
}
