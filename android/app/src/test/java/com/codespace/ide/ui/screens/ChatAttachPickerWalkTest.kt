package com.codespace.ide.ui.screens

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * A2 (advisor ruling 2026-10-24): the picker walk loses the silent 512 KB size
 * filter (any file can be PICKED; classification/caps happen at attach/send)
 * and the 400-entry list cap becomes HONEST: the truncated flag drives the
 * "showing first 400" row, and search walks the whole project so "search to
 * find more" is true. Pure JVM; no device needed.
 */
class ChatAttachPickerWalkTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private fun junk(dir: java.io.File, name: String, bytes: Int): java.io.File {
        val f = java.io.File(dir, name)
        java.io.RandomAccessFile(f, "rw").use { it.setLength(bytes.toLong()) }
        return f
    }

    @Test fun zipOver512KB_isNowListed() {
        val root = tmp.newFolder()
        junk(root, "small.txt", 100)
        junk(root, "big-bundle.zip", 600 * 1024)   // was invisible before A2
        junk(root, "report.docx", 700 * 1024)      // was invisible before A2
        val (files, truncated) = walkProjectFiles(root.absolutePath)
        assertEquals(3, files.size)
        assertFalse(truncated)
        assertTrue(files.contains("big-bundle.zip"))
        assertTrue(files.contains("report.docx"))
    }

    @Test fun skipDirsAndEmptyAndTmp_stillHidden() {
        val root = tmp.newFolder()
        junk(root, "visible.txt", 10)
        java.io.File(root, ".git").mkdir()
        junk(java.io.File(root, ".git"), "config", 10)
        junk(root, "empty.txt", 0)
        junk(root, "draft.chatapply.tmp", 10)
        val (files, _) = walkProjectFiles(root.absolutePath)
        assertEquals(listOf("visible.txt"), files)
    }

    @Test fun walkCap400_isHonest() {
        val root = tmp.newFolder()
        for (i in 1..450) junk(root, "f%03d.txt".format(i), 5)
        val (files, truncated) = walkProjectFiles(root.absolutePath)
        assertEquals(400, files.size)
        assertTrue(truncated)
    }

    @Test fun searchFindsFilesBeyondTheListCap() {
        val root = tmp.newFolder()
        for (i in 1..450) junk(root, "f%03d.txt".format(i), 5)
        // f445 is beyond the 400-entry walk list — search must still find it.
        val (matches, scanTruncated) = searchProjectFiles(root.absolutePath, "f445")
        assertTrue(matches.contains("f445.txt"))
        assertFalse(scanTruncated)
    }

    @Test fun searchRespectsScanBudget_honestly() {
        val root = tmp.newFolder()
        for (i in 1..200) {
            val d = java.io.File(root, "d%03d".format(i))
            d.mkdir()
            for (j in 1..120) junk(d, "x%03d.txt".format(j), 1) // 24,000 files total
        }
        val (matches, scanTruncated) = searchProjectFiles(root.absolutePath, "ZZZ-NOTHING")
        assertTrue(matches.isEmpty())
        assertTrue(scanTruncated) // the honest "refine your search" condition
    }
}
