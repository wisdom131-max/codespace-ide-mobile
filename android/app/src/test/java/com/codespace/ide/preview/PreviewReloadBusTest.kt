package com.codespace.ide.preview

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** A save has to be observable, and the last path has to be the one that was just saved. */
class PreviewReloadBusTest {

    @Test
    fun `each save bumps the revision`() {
        PreviewReloadBus.reset()
        val before = PreviewReloadBus.revision.value
        PreviewReloadBus.notifySaved("/p/a.kt")
        assertEquals(before + 1, PreviewReloadBus.revision.value)
        assertEquals("/p/a.kt", PreviewReloadBus.lastSavedPath)
        PreviewReloadBus.notifySaved("/p/b.kt")
        assertEquals(before + 2, PreviewReloadBus.revision.value)
        assertEquals("/p/b.kt", PreviewReloadBus.lastSavedPath)
    }

    @Test
    fun `reset clears both`() {
        PreviewReloadBus.notifySaved("/p/a.kt")
        PreviewReloadBus.reset()
        assertNull(PreviewReloadBus.lastSavedPath)
        assertEquals(0L, PreviewReloadBus.revision.value)
    }
}
