package com.codespace.ide.lsp

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * TB06-R (advisor round 2026-10-19): the open-tab rename protocol plan. The
 * order is the pinned decision — close the OLD uri, then open the NEW one
 * with the current content, both after the shell's workspace/didRenameFiles.
 */
class LspRenameReopenPlanTest {

    @Test fun openTab_closeBeforeOpen() {
        val steps = LspRenameReopenPlan.stepsFor(tabWasOpen = true, "/p/old.py", "/p/new.py")
        assertEquals(
            listOf(LspRenameReopenPlan.Step.CloseOld, LspRenameReopenPlan.Step.OpenNew),
            steps,
        )
    }

    @Test fun openTab_closeIsFirst() {
        // The order is load-bearing: an open of the new uri BEFORE the close of
        // the old one leaves a window where a server sees two documents for one
        // file, and pyright-style servers refuse the duplicate.
        val steps = LspRenameReopenPlan.stepsFor(true, "/p/old.py", "/p/new.py")
        assertTrue(steps.first() == LspRenameReopenPlan.Step.CloseOld)
        assertTrue(steps.last() == LspRenameReopenPlan.Step.OpenNew)
    }

    @Test fun noOpenTab_noTraffic() {
        // A rename of a file that was NOT open sends NO protocol traffic —
        // servers discover it via the workspace notification.
        assertTrue(LspRenameReopenPlan.stepsFor(false, "/p/old.py", "/p/new.py").isEmpty())
    }

    @Test fun samePath_noTraffic() {
        assertTrue(LspRenameReopenPlan.stepsFor(true, "/p/same.py", "/p/same.py").isEmpty())
    }
}
