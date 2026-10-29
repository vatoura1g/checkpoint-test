package com.votry.productstudio.processing

object PixelMath {
    fun compositeChannel(source: Int, alpha: Int, background: Int = 255): Int {
        val a = alpha.coerceIn(0, 255)
        return ((source.coerceIn(0, 255) * a + background.coerceIn(0, 255) * (255 - a) + 127) / 255)
            .coerceIn(0, 255)
    }

    fun isExactWhite(argb: Int): Boolean =
        ((argb shr 16) and 0xFF) == 255 && ((argb shr 8) and 0xFF) == 255 && (argb and 0xFF) == 255
}
