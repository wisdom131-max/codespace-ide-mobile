package com.codespace.ide.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ModePolicyTest {

    @Test
    fun askMayReadButNotEditOrRun() {
        assertTrue(ModePolicy.allows("ask", ModePolicy.Group.READ))
        assertFalse(ModePolicy.allows("ask", ModePolicy.Group.EDIT))
        assertFalse(ModePolicy.allows("ask", ModePolicy.Group.EXECUTE))
    }

    @Test
    fun editMayEditButNotExecute() {
        assertTrue(ModePolicy.allows("edit", ModePolicy.Group.EDIT))
        assertFalse(ModePolicy.allows("edit", ModePolicy.Group.EXECUTE))
    }

    @Test
    fun agentMayDoEverything() {
        assertEquals(ModePolicy.Group.values().toSet(), ModePolicy.groupsFor("agent"))
    }

    @Test
    fun unknownModeFallsBackToTheRestrictiveOne() {
        assertFalse(ModePolicy.allows("nonsense", ModePolicy.Group.EDIT))
        assertFalse(ModePolicy.allows(null, ModePolicy.Group.EXECUTE))
        assertFalse(ModePolicy.allows("", ModePolicy.Group.EDIT))
    }

    @Test
    fun caseAndSpacingDoNotChangeTheMode() {
        assertTrue(ModePolicy.allows(" AGENT ", ModePolicy.Group.EXECUTE))
        assertTrue(ModePolicy.allows("Edit", ModePolicy.Group.EDIT))
    }

    @Test
    fun askModeIsToldToOfferASwitchNotToClaimItIsBlocked() {
        val line = ModePolicy.instructionFor("ask")
        assertTrue(line.contains("switch to Edit"))
        assertTrue(line.contains("do not claim you are blocked"))
    }
}
