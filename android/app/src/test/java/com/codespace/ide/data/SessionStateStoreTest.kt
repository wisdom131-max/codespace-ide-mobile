package com.codespace.ide.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SessionStateStoreTest {
    @Test
    fun encodeAndDecodeShellState_roundTrips() {
        val original = SessionStateStore.ShellState(
            projectId = "proj-1",
            activePanel = "EXPLORER",
            bottomTab = "TERMINAL",
            showBottomPanel = true,
            activeFilePath = "/storage/emulated/0/Work/app.kt",
            openFilePaths = listOf("/storage/emulated/0/Work/app.kt", "/storage/emulated/0/Work/Main.kt"),
            editorFontSize = 15,
        )

        val encoded = SessionStateStore.encodeShellState(original)
        val restored = SessionStateStore.decodeShellState(encoded)

        assertEquals(original.projectId, restored.projectId)
        assertEquals(original.activePanel, restored.activePanel)
        assertEquals(original.bottomTab, restored.bottomTab)
        assertTrue(restored.showBottomPanel)
        assertEquals(original.activeFilePath, restored.activeFilePath)
        assertEquals(original.openFilePaths, restored.openFilePaths)
        assertEquals(original.editorFontSize, restored.editorFontSize)
    }
}
