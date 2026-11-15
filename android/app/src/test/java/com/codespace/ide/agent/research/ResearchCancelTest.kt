package com.codespace.ide.agent.research

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * PROJECT 1.9: a cancelled turn must not spend another request.
 *
 * The counter is what makes this safe across turns: a stale "cancelled" must never carry into the
 * next round, because that would silently stop a search the owner asked for.
 */
class ResearchCancelTest {

    @Test
    fun aRoundIsWantedUntilTheTurnIsCancelled() {
        val round = ResearchCancel.generation()
        assertTrue(ResearchCancel.stillWanted(round))
        ResearchCancel.bump()
        assertFalse(ResearchCancel.stillWanted(round))
    }

    @Test
    fun theNextRoundIsNotInheritedAsCancelled() {
        ResearchCancel.bump()
        val fresh = ResearchCancel.generation()
        assertTrue("a new round must not start cancelled", ResearchCancel.stillWanted(fresh))
    }

    @Test
    fun anOlderRoundCannotSpendAfterANewerOneStarts() {
        val older = ResearchCancel.generation()
        ResearchCancel.bump()
        val newer = ResearchCancel.generation()
        assertTrue(ResearchCancel.stillWanted(newer))
        assertFalse(ResearchCancel.stillWanted(older))
    }
}
