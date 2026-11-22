package com.codespace.ide.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class EvalSplitTest {

    private val cases = (1..10).map { EvalDataset.EvalCase("c" + it, "q" + it, "a" + it) }

    @Test
    fun everyFifthCaseIsHeldOut() {
        val s = EvalSplit.split(cases, holdoutEvery = 5)
        assertEquals(2, s.holdout.size)
        assertEquals(8, s.train.size)
    }

    @Test
    fun noCaseIsLost() {
        val s = EvalSplit.split(cases, holdoutEvery = 3)
        assertEquals(cases.size, s.train.size + s.holdout.size)
    }

    @Test
    fun theSplitIsStable() {
        val a = EvalSplit.split(cases, holdoutEvery = 3)
        val b = EvalSplit.split(cases, holdoutEvery = 3)
        assertEquals(a.holdout.map { it.id }, b.holdout.map { it.id })
    }

    @Test
    fun aSillyIntervalHoldsEverythingOut() {
        val s = EvalSplit.split(cases, holdoutEvery = 1)
        assertTrue(s.train.isEmpty())
        assertEquals(10, s.holdout.size)
    }
}
