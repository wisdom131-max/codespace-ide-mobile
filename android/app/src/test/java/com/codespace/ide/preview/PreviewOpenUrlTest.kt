package com.codespace.ide.preview

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * This decides whether a tapped link stays inside the app or leaves for Chrome. Getting it wrong
 * either sends a user to Chrome for a page it cannot reach, or traps an external site in a panel.
 */
class PreviewOpenUrlTest {

    @Test
    fun everyLoopbackSpellingMeansThisDevice() {
        listOf(
            "http://localhost:5173",
            "http://localhost",
            "https://LOCALHOST:8443/",
            "http://127.0.0.1:3000/x",
            "http://0.0.0.0:8080",
            "http://[::1]:5500",
            "http://app.localhost",
        ).forEach { assertTrue(it, PreviewOpenUrl.isLocalhost(it)) }
    }

    @Test
    fun aBareHostPortIsStillRecognised() {
        assertTrue(
            "a dev server prints localhost:5173 without a scheme",
            PreviewOpenUrl.isLocalhost("localhost:5173"),
        )
        assertTrue(PreviewOpenUrl.isLocalhost("127.0.0.1:8080"))
    }

    @Test
    fun theInternetIsNotLocal() {
        listOf(
            "https://example.com",
            "https://github.com/wisdom131-max",
            "https://localhost.evil.com",
            "https://notlocalhost.com",
            "http://192.168.1.5:3000",
        ).forEach { assertFalse(it, PreviewOpenUrl.isLocalhost(it)) }
    }

    @Test
    fun garbageIsNotAReasonToOpenThePanel() {
        assertFalse(PreviewOpenUrl.isLocalhost(""))
        assertFalse(PreviewOpenUrl.isLocalhost("not a url"))
        assertFalse(PreviewOpenUrl.isLocalhost("://"))
    }
}
