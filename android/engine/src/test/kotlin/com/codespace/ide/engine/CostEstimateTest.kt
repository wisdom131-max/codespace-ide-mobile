package com.codespace.ide.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CostEstimateTest {

    private val price = CostEstimate.Price(3.0, 15.0)

    @Test
    fun theArithmeticIsPerMillion() {
        val c = CostEstimate.cost(price, 1_000_000, 1_000_000)
        assertEquals(18.0, c, 0.0001)
    }

    @Test
    fun aSmallTurnCostsLittle() {
        val c = CostEstimate.cost(price, 1000, 500)
        assertEquals(0.0105, c, 0.0001)
    }

    @Test
    fun negativeTokensCostNothing() {
        assertEquals(0.0, CostEstimate.cost(price, -5, 10), 0.0001)
    }

    @Test
    fun aTinyCostKeepsEnoughDecimals() {
        assertTrue(CostEstimate.format(0.00042).startsWith("$0.0004"))
    }

    @Test
    fun zeroIsZero() {
        assertEquals("$0.00", CostEstimate.format(0.0))
    }
}
