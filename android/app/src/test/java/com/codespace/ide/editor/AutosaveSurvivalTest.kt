package com.codespace.ide.editor

import androidx.compose.runtime.mutableStateListOf
import com.codespace.ide.domain.EditorTab
import com.codespace.ide.domain.Language
import com.codespace.ide.ui.panes.restoreAutosaveFiles
import java.io.File
import java.net.URLEncoder
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Owner order 2026-11-07, follow-up 1: a REFUSED autosave must survive. It is a judgement about
 * content, not proof the bytes are worthless, so the file is kept untouched and offered as a
 * separate read-only copy. This test drives the real restore function and then proves the autosave
 * file on disk is byte-identical.
 */
class AutosaveSurvivalTest {

    private val root: File = File(System.getProperty("java.io.tmpdir"), "vn-autosave-${System.nanoTime()}").apply { mkdirs() }

    @After
    fun tearDown() {
        root.deleteRecursively()
    }

    private fun autosaveNameFor(path: String) = URLEncoder.encode(path, "UTF-8") + ".autosave"

    @Test
    fun aRefusedAutosaveIsKeptByteIdenticalAndNeverApplied() {
        val real = File(root, "big.js")
        val whole = (1..200).joinToString("\n") { "line $it" } + "\n"
        real.writeText(whole)

        // The autosave holds only the head of the file: the shape a preview slice has.
        val slice = (1..50).joinToString("\n") { "line $it" } + "\n"
        val autosave = File(root, autosaveNameFor(real.absolutePath))
        autosave.writeText(slice)
        val before = autosave.readBytes()
        val realBefore = real.readBytes()

        // An open tab for the real file, so the only thing that can stop the restore is our guard.
        val tabs = mutableStateListOf(
            EditorTab(
                id = real.absolutePath,
                path = real.absolutePath,
                name = "big.js",
                content = whole,
                language = Language.JAVASCRIPT,
            ),
        )

        val (restored, noTab, failed) = restoreAutosaveFiles(listOf(autosave), tabs)

        assertEquals("nothing may be restored from a slice", 0, restored)
        assertEquals(0, noTab)
        assertEquals("counted as refused", 1, failed)
        assertTrue("the autosave file must still exist", autosave.exists())
        assertEquals(
            "the autosave must be byte-identical afterwards",
            before.toList(),
            autosave.readBytes().toList(),
        )
        assertEquals(
            "the tab content must NOT have been replaced by the slice",
            whole,
            tabs[0].content,
        )
        assertEquals("the real file must be untouched", realBefore.toList(), real.readBytes().toList())
    }

    @Test
    fun aGenuineUnsavedEditIsStillRestored() {
        val real = File(root, "edited.js")
        val whole = (1..200).joinToString("\n") { "line $it" } + "\n"
        real.writeText(whole)

        val edited = whole.replace("line 42", "line 42 // edited but never saved")
        val autosave = File(root, autosaveNameFor(real.absolutePath))
        autosave.writeText(edited)

        val tabs = mutableStateListOf(
            EditorTab(
                id = real.absolutePath,
                path = real.absolutePath,
                name = "edited.js",
                content = whole,
                language = Language.JAVASCRIPT,
            ),
        )

        val (restored, _, failed) = restoreAutosaveFiles(listOf(autosave), tabs)

        assertEquals("a mid-file edit is a real recovery", 1, restored)
        assertEquals(0, failed)
        assertEquals("the tab now holds the unsaved edit", edited, tabs[0].content)
        assertEquals("and the real file is still the saved version", whole, real.readText())
    }
}
