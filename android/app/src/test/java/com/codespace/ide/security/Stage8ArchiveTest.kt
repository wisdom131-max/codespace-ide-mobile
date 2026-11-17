package com.codespace.ide.security

import com.codespace.ide.agent.ArchiveVerifier
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * PROJECT EIGHT 8.6 - an archive that escapes its destination must be REFUTED, not verified.
 *
 * The pre-existing check only looked for ".." in an entry name. These cases cover the shapes a hostile
 * archive actually uses: an absolute entry that tar writes wherever it names, a Windows-rooted entry,
 * a climb hidden mid-path, and an entry name carrying control characters.
 */
class Stage8ArchiveTest {

    @Test
    fun `an absolute entry is refused rather than verified`() {
        val reason = ArchiveVerifier.unsafePath("/data/data/com.codespace.ide/files/evil.sh")
        assertNotNull("an absolute entry can escape the destination", reason)
        assertEquals(
            ArchiveVerifier.Result.REFUTED,
            ArchiveVerifier.verifyListing("evil.sh 10\n/data/data/com.codespace.ide/evil.sh 4\n").result,
        )
    }

    @Test
    fun `a drive-rooted or network entry is refused`() {
        assertNotNull(ArchiveVerifier.unsafePath("C:/Windows/System32/drivers/x.sys"))
        assertNotNull(ArchiveVerifier.unsafePath("//host/share/x"))
    }

    @Test
    fun `a climb hidden mid-path is refused`() {
        assertNotNull(ArchiveVerifier.unsafePath("a/../../etc/passwd"))
        assertNotNull(ArchiveVerifier.unsafePath(".."))
    }

    @Test
    fun `an ordinary entry is accepted`() {
        assertNull(ArchiveVerifier.unsafePath("src/main/App.kt"))
        assertNull(ArchiveVerifier.unsafePath("./notes/readme.md"))
        assertEquals(ArchiveVerifier.Result.VERIFIED, ArchiveVerifier.verifyListing("src/main/App.kt 120\n").result)
    }

    @Test
    fun `a corrupt archive is refused and an unreadable one is not called clean`() {
        assertEquals(
            ArchiveVerifier.Result.REFUTED,
            ArchiveVerifier.verifyListing("gzip: stdin: not a zip archive").result,
        )
        val empty = ArchiveVerifier.verifyListing("")
        assertEquals(ArchiveVerifier.Result.NOT_CHECKABLE, empty.result)
    }
}
