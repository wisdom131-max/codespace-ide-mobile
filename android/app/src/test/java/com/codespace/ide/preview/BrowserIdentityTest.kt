package com.codespace.ide.preview

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The browser's identity is the thing that decides whether a site serves a page or a
 * "this browser is not secure" wall, and it is the one property that cannot be checked
 * by looking at the UI. These tests hold the rule: one coherent identity, engine-derived.
 */
class BrowserIdentityTest {

    private val realEngineUa =
        "Mozilla/5.0 (Linux; Android 14; TECNO KL4 Build/UP1A; wv) AppleWebKit/537.36 " +
            "(KHTML, like Gecko) Version/4.0 Chrome/120.0.6099.144 Mobile Safari/537.36"

    @Test
    fun mobileKeepsTheEngineVersionAndDropsOnlyTheWebViewBranding() {
        val ua = BrowserSession.mobileUserAgentFrom(realEngineUa)
        assertTrue("engine version must survive", ua.contains("Chrome/120.0.6099.144"))
        assertTrue("still a mobile Android browser", ua.contains("Android 14"))
        assertFalse("the wv token is the giveaway", ua.contains("; wv"))
    }

    @Test
    fun desktopClaimsTheEngineVersionNotAnInventedOne() {
        val ua = BrowserSession.desktopUserAgentFrom(realEngineUa)
        assertTrue("same engine major version as the real UA", ua.contains("Chrome/120.0.0.0"))
        assertTrue("a desktop UA has to look like a desktop", ua.contains("Windows NT 10.0"))
        assertFalse("a Windows UA that advertises Android is incoherent", ua.contains("Android"))
        assertFalse(ua.contains("; wv"))
    }

    @Test
    fun anUnreadableEngineVersionFallsBackInsteadOfEmittingANull() {
        val ua = BrowserSession.desktopUserAgentFrom("Mozilla/5.0 (Linux; Android 14) Mobile")
        assertTrue("must still be a usable UA", ua.startsWith("Mozilla/5.0 (Windows"))
        assertTrue(ua.contains("Chrome/"))
        assertFalse("no template leftovers", ua.contains("null"))
    }

    @Test
    fun theTwoModesNeverDisagreeAboutVersion() {
        val mobile = BrowserSession.mobileUserAgentFrom(realEngineUa)
        val desktop = BrowserSession.desktopUserAgentFrom(realEngineUa)
        val mobileMajor = Regex("Chrome/(\\d+)").find(mobile)!!.groupValues[1]
        val desktopMajor = Regex("Chrome/(\\d+)").find(desktop)!!.groupValues[1]
        assertEquals("same major version in both modes", mobileMajor, desktopMajor)
    }
}
