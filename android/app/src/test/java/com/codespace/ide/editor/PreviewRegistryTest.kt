package com.codespace.ide.editor

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The registry that decides "is this path open as a partial slice right now".
 *
 * The point of these tests is not the set arithmetic, it is the lifecycle: a path must stop being
 * a preview the moment the tab is closed, promoted or replaced, because every guard downstream
 * (breakpoints, didChange, didSave) trusts this answer.
 */
class PreviewRegistryTest {

    @Test
    fun aPathIsAPreviewOnlyWhileItsTabExists() {
        PreviewRegistry.replaceWith(mapOf("/tmp/big.js" to "slice"))
        assertTrue(PreviewRegistry.isPreview("/tmp/big.js"))
        assertFalse(PreviewRegistry.isPreview("/tmp/other.js"))

        PreviewRegistry.replaceWith(emptyMap())
        assertFalse("closing the tab must clear the guard", PreviewRegistry.isPreview("/tmp/big.js"))
        assertTrue(PreviewRegistry.isEmpty())
    }

    @Test
    fun replacingTheSetDropsStalePaths() {
        PreviewRegistry.replaceWith(mapOf("/tmp/a.js" to "a", "/tmp/b.js" to "b"))
        PreviewRegistry.replaceWith(mapOf("/tmp/b.js" to "b"))
        assertFalse(PreviewRegistry.isPreview("/tmp/a.js"))
        assertTrue(PreviewRegistry.isPreview("/tmp/b.js"))
    }

    @Test
    fun twoPreviewsCoexist() {
        PreviewRegistry.replaceWith(mapOf("/tmp/a.js" to "a", "/tmp/b.js" to "b"))
        assertEquals(2, listOf("/tmp/a.js", "/tmp/b.js").count { PreviewRegistry.isPreview(it) })
    }

    @Test
    fun thePolicyRefusesEveryCapabilityForAReadOnlyTab() {
        // Every capability the editor offers assumes "the content is the whole file".
        assertFalse(PreviewPolicy.shouldSyncToLsp(readOnly = true))
        assertFalse(PreviewPolicy.shouldPublishLint(readOnly = true))
        assertFalse(PreviewPolicy.shouldServeLanguageFeatures(readOnly = true))
        assertFalse(PreviewPolicy.shouldWriteToDisk(readOnly = true))
        assertFalse(PreviewPolicy.canGoDirty(readOnly = true))
        assertFalse(PreviewPolicy.shouldApplyLineKeyedEdits(readOnly = true))
        assertFalse(PreviewPolicy.shouldApplyDocumentWideEdit(readOnly = true))

        assertTrue(PreviewPolicy.shouldSyncToLsp(readOnly = false))
        assertTrue(PreviewPolicy.shouldPublishLint(readOnly = false))
        assertTrue(PreviewPolicy.shouldServeLanguageFeatures(readOnly = false))
        assertTrue(PreviewPolicy.shouldWriteToDisk(readOnly = false))
        assertTrue(PreviewPolicy.canGoDirty(readOnly = false))
        assertTrue(PreviewPolicy.shouldApplyLineKeyedEdits(readOnly = false))
        assertTrue(PreviewPolicy.shouldApplyDocumentWideEdit(readOnly = false))
    }
}
