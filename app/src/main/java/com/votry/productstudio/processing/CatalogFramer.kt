package com.votry.productstudio.processing

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Paint
import com.votry.productstudio.ml.sam.TargetMask
import kotlin.math.min

object CatalogFramer {
    const val OUTPUT_WIDTH = 2000
    const val OUTPUT_HEIGHT = 2500

    /**
     * Places the already-composited image on a fixed 4:5 catalogue canvas.
     * Only uniform scale + translation are applied: product geometry is never distorted.
     */
    fun frame(source: Bitmap, target: TargetMask): Bitmap {
        val bounds = target.bounds(0.45f) ?: return source.copy(Bitmap.Config.ARGB_8888, false)
        val objectLeft = bounds.left * source.width
        val objectTop = bounds.top * source.height
        val objectRight = bounds.right * source.width
        val objectBottom = bounds.bottom * source.height
        val objectWidth = (objectRight - objectLeft).coerceAtLeast(1f)
        val objectHeight = (objectBottom - objectTop).coerceAtLeast(1f)

        val desiredWidth = OUTPUT_WIDTH * 0.70f
        val desiredHeight = OUTPUT_HEIGHT * 0.72f
        val scale = min(desiredWidth / objectWidth, desiredHeight / objectHeight)
            .coerceIn(0.25f, 4.0f)

        val objectCenterX = (objectLeft + objectRight) * 0.5f
        val objectCenterY = (objectTop + objectBottom) * 0.5f
        val targetCenterX = OUTPUT_WIDTH * 0.5f
        val targetCenterY = OUTPUT_HEIGHT * 0.47f

        val matrix = Matrix().apply {
            postScale(scale, scale)
            postTranslate(
                targetCenterX - objectCenterX * scale,
                targetCenterY - objectCenterY * scale,
            )
        }

        val result = Bitmap.createBitmap(OUTPUT_WIDTH, OUTPUT_HEIGHT, Bitmap.Config.ARGB_8888)
        Canvas(result).apply {
            drawColor(Color.WHITE)
            drawBitmap(
                source,
                matrix,
                Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG or Paint.DITHER_FLAG),
            )
        }
        return result
    }
}
