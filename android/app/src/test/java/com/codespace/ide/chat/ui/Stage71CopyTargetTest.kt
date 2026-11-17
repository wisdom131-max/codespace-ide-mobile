package com.codespace.ide.chat.ui

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * PROJECT SEVEN — the Copy target is 30x24dp ON PURPOSE.
 *
 * Checkpoint 5 expanded it to Material's 48dp interactive size. The owner reviewed that and asked for the
 * original size back. This test exists so the decision cannot be quietly reversed again by a future
 * accessibility pass: the numbers are pinned, and the modifier that expanded it must stay gone.
 */
class Stage71CopyTargetTest {

    @Test
    fun `the copy target is the owner-approved 30 by 24 dp`() {
        assertEquals(30, CopyTarget.widthDp)
        assertEquals(24, CopyTarget.heightDp)
        assertTrue(CopyTarget.describe().contains("30 x 24"))
        assertTrue(CopyTarget.describe().contains("owner-approved"))
    }

    @Test
    fun `the target is not quietly returned to 48dp`() {
        assertTrue("width must stay under 48", CopyTarget.widthDp < 48)
        assertTrue("height must stay under 48", CopyTarget.heightDp < 48)
    }

    @Test
    fun `no Material interactive-size expansion remains in the copy row`() {
        val src = locate("app/src/main/java/com/codespace/ide/chat/ui/CopyIconRow.kt")
        assertTrue("CopyIconRow.kt must exist for this guard to mean anything", src != null)
        assertFalse(
            "the 48dp expansion was reverted at the owner's request; do not reintroduce it",
            src!!.contains("minimumInteractiveComponentSize"),
        )
    }

    @Test
    fun `the action label and button role survive the size restoration`() {
        val src = locate("app/src/main/java/com/codespace/ide/chat/ui/CopyIconRow.kt")!!
        assertTrue(src.contains("onClickLabel = \"Copy message\""))
        assertTrue(src.contains("Role.Button"))
    }

    /** Works from the module directory or the repo root, whichever the runner uses. */
    private fun locate(relative: String): String? {
        var dir: File? = File(".").absoluteFile
        repeat(4) {
            val f = File(dir, relative)
            if (f.isFile) return f.readText()
            dir = dir?.parentFile
        }
        return null
    }
}
