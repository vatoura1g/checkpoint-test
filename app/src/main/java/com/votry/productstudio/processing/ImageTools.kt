package com.votry.productstudio.processing

import android.content.ContentValues
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.net.Uri
import android.provider.MediaStore
import androidx.exifinterface.media.ExifInterface
import java.io.File
import kotlin.math.max
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

object ImageTools {
    private const val PROCESSING_MAX_EDGE = 3200
    private const val PREVIEW_MAX_EDGE = 1600

    fun decodeForProcessing(file: File): Bitmap = decodeOrientedBounded(file, PROCESSING_MAX_EDGE)

    fun decodePreview(file: File): Bitmap {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(file.absolutePath, bounds)
        val maxEdge = max(bounds.outWidth, bounds.outHeight).coerceAtLeast(1)
        var sample = 1
        while (maxEdge / (sample * 2) >= PREVIEW_MAX_EDGE) sample *= 2
        val decoded = BitmapFactory.decodeFile(
            file.absolutePath,
            BitmapFactory.Options().apply {
                inSampleSize = sample
                inPreferredConfig = Bitmap.Config.ARGB_8888
            },
        ) ?: throw IllegalStateException("Résultat illisible")
        return scaleDownIfNeeded(decoded, PREVIEW_MAX_EDGE)
    }

    private fun decodeOrientedBounded(file: File, maxEdge: Int): Bitmap {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(file.absolutePath, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) throw IllegalStateException("Photo illisible")

        val longest = max(bounds.outWidth, bounds.outHeight)
        var sample = 1
        while (longest / (sample * 2) >= maxEdge) sample *= 2

        val decoded = BitmapFactory.decodeFile(
            file.absolutePath,
            BitmapFactory.Options().apply {
                inSampleSize = sample
                inPreferredConfig = Bitmap.Config.ARGB_8888
            },
        ) ?: throw IllegalStateException("Photo illisible")

        val exif = ExifInterface(file.absolutePath)
        val orientation = exif.getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)
        val degrees = when (orientation) {
            ExifInterface.ORIENTATION_ROTATE_90 -> 90f
            ExifInterface.ORIENTATION_ROTATE_180 -> 180f
            ExifInterface.ORIENTATION_ROTATE_270 -> 270f
            else -> 0f
        }

        val oriented = if (degrees == 0f) {
            decoded
        } else {
            val matrix = Matrix().apply { postRotate(degrees) }
            Bitmap.createBitmap(decoded, 0, 0, decoded.width, decoded.height, matrix, true).also {
                if (it !== decoded) decoded.recycle()
            }
        }
        return scaleDownIfNeeded(oriented, maxEdge)
    }

    private fun scaleDownIfNeeded(bitmap: Bitmap, maxEdge: Int): Bitmap {
        val longest = max(bitmap.width, bitmap.height)
        if (longest <= maxEdge) return bitmap
        val scale = maxEdge.toFloat() / longest.toFloat()
        val width = (bitmap.width * scale).toInt().coerceAtLeast(1)
        val height = (bitmap.height * scale).toInt().coerceAtLeast(1)
        val scaled = Bitmap.createScaledBitmap(bitmap, width, height, true)
        if (scaled !== bitmap) bitmap.recycle()
        return scaled
    }

    suspend fun writeResultCache(context: Context, bitmap: Bitmap): File = withContext(Dispatchers.IO) {
        val dir = File(context.cacheDir, "ghazi-results").apply { mkdirs() }
        val file = File(dir, "Ghazi_${System.currentTimeMillis()}.png")
        file.outputStream().buffered().use { out ->
            if (!bitmap.compress(Bitmap.CompressFormat.PNG, 100, out)) {
                throw IllegalStateException("Impossible de préparer le résultat")
            }
        }
        file
    }

    suspend fun saveCachedResultToGallery(context: Context, file: File): Uri = withContext(Dispatchers.IO) {
        if (!file.exists() || file.length() == 0L) throw IllegalStateException("Résultat introuvable")
        val values = ContentValues().apply {
            put(MediaStore.Images.Media.DISPLAY_NAME, "Ghazi_${System.currentTimeMillis()}.png")
            put(MediaStore.Images.Media.MIME_TYPE, "image/png")
            put(MediaStore.Images.Media.RELATIVE_PATH, "Pictures/Ghazi")
            put(MediaStore.Images.Media.IS_PENDING, 1)
        }
        val resolver = context.contentResolver
        val uri = resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)
            ?: throw IllegalStateException("Impossible d'enregistrer la photo")
        try {
            resolver.openOutputStream(uri)?.use { output ->
                file.inputStream().buffered().use { input -> input.copyTo(output, 256 * 1024) }
            } ?: throw IllegalStateException("Stockage indisponible")
            values.clear()
            values.put(MediaStore.Images.Media.IS_PENDING, 0)
            resolver.update(uri, values, null, null)
            uri
        } catch (t: Throwable) {
            resolver.delete(uri, null, null)
            throw t
        }
    }
}
