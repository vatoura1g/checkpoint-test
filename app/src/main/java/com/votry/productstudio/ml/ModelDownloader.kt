package com.votry.productstudio.ml

import android.content.Context
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class ModelDownloader(private val context: Context) {
    suspend fun ensureModel(onProgress: (Int) -> Unit): File = withContext(Dispatchers.IO) {
        val dir = File(context.filesDir, "models").apply { mkdirs() }
        val target = File(dir, ModelConfig.FILE_NAME)
        if (target.exists() && target.length() >= ModelConfig.MIN_BYTES) {
            onProgress(100)
            return@withContext target
        }

        val temp = File(dir, "${ModelConfig.FILE_NAME}.part")
        if (temp.exists()) temp.delete()
        val connection = (URL(ModelConfig.URL).openConnection() as HttpURLConnection).apply {
            connectTimeout = 20_000
            readTimeout = 60_000
            instanceFollowRedirects = true
        }
        connection.connect()
        if (connection.responseCode !in 200..299) {
            throw IllegalStateException("Téléchargement du moteur impossible (${connection.responseCode}).")
        }
        val total = connection.contentLengthLong
        connection.inputStream.use { input ->
            temp.outputStream().buffered().use { output ->
                val buffer = ByteArray(256 * 1024)
                var done = 0L
                while (true) {
                    val read = input.read(buffer)
                    if (read < 0) break
                    output.write(buffer, 0, read)
                    done += read
                    if (total > 0) onProgress(((done * 100L) / total).toInt().coerceIn(0, 99))
                }
            }
        }
        connection.disconnect()
        if (temp.length() < ModelConfig.MIN_BYTES) {
            temp.delete()
            throw IllegalStateException("Le moteur téléchargé est incomplet.")
        }
        if (target.exists()) target.delete()
        if (!temp.renameTo(target)) {
            temp.copyTo(target, overwrite = true)
            temp.delete()
        }
        onProgress(100)
        target
    }
}
