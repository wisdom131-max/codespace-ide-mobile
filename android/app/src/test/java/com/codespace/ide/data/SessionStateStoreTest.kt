package com.codespace.ide.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SessionStateStoreTest {
    /**
     * TB02 (2026-09-27) deleted the single-blob SHELL ENCODER — the split writers
     * serialize their own groups inline — so there is nothing to encode from
     * anymore. What survives is decodeShellState: the legacy-blob MIGRATION
     * READER for state written by pre-TB02 builds. This test covers the reader
     * with a hand-built legacy blob (the exact format the old encoder wrote)
     * plus the null path on garbage.
     */
    @Test
    fun decodeShellState_readsLegacyBlobAndReturnsNullOnGarbage() {
        val legacyBlob = org.json.JSONObject().apply {
            put("projectId", "proj-1")
            put("activePanel", "EXPLORER")
            put("bottomTab", "TERMINAL")
            put("showBottomPanel", true)
            put("activeFilePath", "/storage/emulated/0/Work/app.kt")
            put("openFilePaths", org.json.JSONArray(listOf("/storage/emulated/0/Work/app.kt", "/storage/emulated/0/Work/Main.kt")))
            put("editorFontSize", 15)
        }.toString()

        val restored = SessionStateStore.decodeShellState(legacyBlob)

        assertEquals("proj-1", restored?.projectId)
        assertEquals("EXPLORER", restored?.activePanel)
        assertEquals("TERMINAL", restored?.bottomTab)
        assertTrue(restored?.showBottomPanel == true)
        assertEquals("/storage/emulated/0/Work/app.kt", restored?.activeFilePath)
        assertEquals(2, restored?.openFilePaths?.size)
        assertEquals(15, restored?.editorFontSize)

        // Garbage must decode to null, never throw (the migration reader is
        // called on raw persisted strings).
        assertEquals(null, SessionStateStore.decodeShellState("not json at all"))
        assertEquals(null, SessionStateStore.decodeShellState(""))
    }
}
