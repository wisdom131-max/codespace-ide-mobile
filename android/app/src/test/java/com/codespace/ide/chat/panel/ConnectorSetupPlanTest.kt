package com.codespace.ide.chat.panel

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** PHASE 4c: the card picks its shape from the backend's authType, never from the service name. */
class ConnectorSetupPlanTest {

    @Test
    fun aPatServiceCollectsATokenInTheCard() {
        assertEquals(ConnectorSetupPlan.Flow.PAT, ConnectorSetupPlan.flowFor("pat"))
    }

    @Test
    fun anOauthServiceOpensItsConsentPage() {
        assertEquals(ConnectorSetupPlan.Flow.OAUTH, ConnectorSetupPlan.flowFor("oauth"))
    }

    @Test
    fun authTypeIsTrimmedAndCaseInsensitive() {
        assertEquals(ConnectorSetupPlan.Flow.PAT, ConnectorSetupPlan.flowFor(" PAT "))
    }

    @Test
    fun anUnreadStatusFallsBackToConsentNeverToATokenField() {
        assertEquals(ConnectorSetupPlan.Flow.OAUTH, ConnectorSetupPlan.flowFor(null))
        assertEquals(ConnectorSetupPlan.Flow.OAUTH, ConnectorSetupPlan.flowFor(""))
    }

    @Test
    fun anUnknownAuthTypeIsNotMistakenForPat() {
        assertEquals(ConnectorSetupPlan.Flow.OAUTH, ConnectorSetupPlan.flowFor("saml"))
    }

    @Test
    fun aConnectedServiceSettlesTheCard() {
        assertTrue(ConnectorSetupPlan.isSettled(connected = true))
        assertFalse(ConnectorSetupPlan.isSettled(connected = false))
    }
}
