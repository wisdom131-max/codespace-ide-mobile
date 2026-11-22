package com.codespace.ide.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.assertFalse
import org.junit.Test

class TokenEstimatorTest {
    @Test fun roundsUp() { assertEquals(2, TokenEstimator.estimate(4)) }
    @Test fun zeroIsZero() { assertEquals(0, TokenEstimator.estimate(0)) }
    @Test fun fitsRespectsTheReserve() {
        assertTrue(TokenEstimator.fits(700, 1000, 512))
        assertFalse(TokenEstimator.fits(7000, 1000, 512))
    }
}
