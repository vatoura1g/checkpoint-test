package com.votry.productstudio.processing

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PixelMathTest {
    @Test fun transparentPixelBecomesExactWhite() {
        assertEquals(255, PixelMath.compositeChannel(17, 0))
    }

    @Test fun opaquePixelIsPreserved() {
        assertEquals(37, PixelMath.compositeChannel(37, 255))
    }

    @Test fun exactWhiteCheckWorks() {
        assertTrue(PixelMath.isExactWhite(0xFFFFFFFF.toInt()))
        assertFalse(PixelMath.isExactWhite(0xFFFEFFFF.toInt()))
    }
}
