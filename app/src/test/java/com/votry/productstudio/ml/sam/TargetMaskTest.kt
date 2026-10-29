package com.votry.productstudio.ml.sam

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TargetMaskTest {
    @Test fun highLogitHasHighProbability() {
        val mask = TargetMask(floatArrayOf(8f), 1, 1, 1f)
        assertTrue(mask.sampleNormalized(0.5f, 0.5f) > 0.99f)
    }

    @Test fun boundsAreNormalized() {
        val logits = FloatArray(16) { -8f }
        logits[5] = 8f
        logits[6] = 8f
        logits[9] = 8f
        logits[10] = 8f
        val mask = TargetMask(logits, 4, 4, 1f)
        val bounds = mask.bounds(0.5f)
        assertNotNull(bounds)
        assertEquals(1f / 3f, bounds!!.left, 0.001f)
        assertEquals(2f / 3f, bounds.right, 0.001f)
        assertTrue(mask.occupancy(0.5f) in 0.24f..0.26f)
    }
}
