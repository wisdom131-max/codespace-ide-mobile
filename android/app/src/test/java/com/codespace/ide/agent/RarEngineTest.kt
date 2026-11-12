package com.codespace.ide.agent

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * P2.C: RAR fail-safe behaviour, tested without any RAR fixture.
 *
 * Why no fixture: producing a .rar requires a RAR archiver, and the UnRAR licence junrar is under
 * forbids using it to build one. So the tests here cover what can be proven without a real archive -
 * signature detection, and that a damaged or fake archive is reported in words instead of throwing,
 * hanging, or writing files. Real RAR listing/extraction coverage needs committed fixtures (see the
 * matrix row 2-14 in docs/P2-C-RAR-ZST-2026-11-12.md) and is explicitly UNVERIFIED until then.
 */
class RarEngineTest {

    @get:Rule val tmp = TemporaryFolder()

    private fun rarHeader(name: String, v5: Boolean): File {
        val f = File(tmp.root, name)
        // RAR5 marker: 52 61 72 21 1A 07 01 00. RAR4: 52 61 72 21 1A 07 00.
        val magic = if (v5) byteArrayOf(0x52, 0x61, 0x72, 0x21, 0x1A, 0x07, 0x01, 0x00)
        else byteArrayOf(0x52, 0x61, 0x72, 0x21, 0x1A, 0x07, 0x00)
        f.writeBytes(magic + ByteArray(64))
        return f
    }

    @Test fun rarIsDetectedByItsSignatureNotItsName() {
        val f = File(tmp.root, "renamed.bin")
        f.writeBytes(byteArrayOf(0x52, 0x61, 0x72, 0x21, 0x1A, 0x07, 0x01, 0x00) + ByteArray(32))
        assertEquals(ArchiveEngine.Kind.RAR, ArchiveEngine.kindOf(f))
    }

    @Test fun rar5AndRar4MarkersBothReadAsRar() {
        assertEquals(ArchiveEngine.Kind.RAR, ArchiveEngine.kindOf(rarHeader("v5.rar", v5 = true)))
        assertEquals(ArchiveEngine.Kind.RAR, ArchiveEngine.kindOf(rarHeader("v4.rar", v5 = false)))
    }

    @Test fun aTruncatedRarIsReportedNotThrown() {
        val f = rarHeader("truncated.rar", v5 = true)
        val listing = ArchiveEngine.list(f)
        // Either a note explaining the problem or an empty listing - never a crash, never a lie.
        assertTrue("a damaged RAR must say something", listing.note != null || listing.total == 0)
    }

    @Test fun extractingADamagedRarWritesNothingAndExplainsWhy() {
        val f = rarHeader("damaged.rar", v5 = true)
        val dest = tmp.newFolder("out")
        val out = ArchiveEngine.extract(f, dest)
        assertTrue("nothing may be written from an unreadable archive", out.written.isEmpty())
        assertNotNull("the failure must carry a reason", out.failure)
        assertEquals(0, dest.listFiles()?.size ?: 0)
    }

    @Test fun aTextFileNamedRarIsNotTreatedAsAnArchive() {
        val f = File(tmp.root, "fake.rar")
        f.writeText("definitely not a rar archive")
        assertEquals(ArchiveEngine.Kind.UNKNOWN, ArchiveEngine.kindOf(f))
        assertNotNull(ArchiveEngine.list(f).note)
    }

    @Test fun theDictionaryCeilingIsTheDocumentedOne() {
        // 64 MiB. A phone-sized cap: RAR5 may declare up to 4 GiB, which junrar would allocate.
        assertEquals(64L * 1024 * 1024, RarEngine.MAX_DICTIONARY_BYTES)
    }
}
