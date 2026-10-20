package com.codespace.ide.chat

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * C12 Stage 3 U3 (advisor GO 2026-10-20): archive listing + entry preview
 * tests. Item 7: NO entry name is ever used as a path (traversal/absolute
 * names are display rows only), 500-entry cap with "showing X of Y", nested
 * archives listed but never auto-opened, malformed = honest refusal.
 */
class ArchiveEntryListerTest {

    @get:Rule val tmp = TemporaryFolder()

    private fun zipOf(vararg entries: Pair<String, String>, name: String = "a.zip"): File {
        val f = tmp.newFile(name)
        ZipOutputStream(f.outputStream()).use { z ->
            for ((n, content) in entries) {
                z.putNextEntry(ZipEntry(n)); z.write(content.toByteArray()); z.closeEntry()
            }
        }
        return f
    }

    @Test fun zip_listing_namesAndSizes() {
        val f = zipOf(
            "readme.txt" to "hello world",
            "src/Main.kt" to "fun main() {}",
            "inner.zip" to "PK-fake",
        )
        val doc = ArchiveEntryLister.defaultListing(f)
        assertEquals(null, doc.refusal)
        val t = doc.text
        assertTrue("entry count header: [$t]", t.contains("3 entries"))
        assertTrue("name shown: [$t]", t.contains("readme.txt"))
        assertTrue("size shown: [$t]", t.contains("bytes)"))
        assertTrue("nested note present: ${doc.notes}", doc.notes.any { it.contains("never auto-opened") })
    }

    @Test fun traversalAndAbsoluteEntryNames_areDisplayRowsOnly() {
        // ../evil.txt and /abs/path.txt are listed AS TEXT rows; previewing
        // them reads by EXACT name in memory — nothing is ever written, no
        // path resolution ever happens. The nested ../evil row also proves
        // nothing auto-opens.
        val f = zipOf(
            "../evil.txt" to "traversal body",
            "/abs/path.txt" to "absolute body",
            "safe.txt" to "safe body",
        )
        val listing = ArchiveEntryLister.list(f)
        assertEquals(3, listing.rows.size)
        assertTrue(listing.rows.any { it.name == "../evil.txt" })
        assertTrue(listing.rows.any { it.name == "/abs/path.txt" })
        // The exact-name preview finds the traversal-named entry BY NAME and
        // returns its text — no filesystem access ever occurs.
        val preview = ArchiveEntryLister.previewEntry(f, "../evil.txt")
        assertEquals("traversal body", preview.text)
        // Unknown name: honest refusal, never a guess.
        val missing = ArchiveEntryLister.previewEntry(f, "nope.txt")
        assertNotNull(missing.refusal)
    }

    @Test fun entryCap_marksShowingXofY() {
        val f = tmp.newFile("many.zip")
        ZipOutputStream(f.outputStream()).use { z ->
            for (i in 0 until 520) {
                z.putNextEntry(ZipEntry("f$i.txt")); z.write("x$i".toByteArray()); z.closeEntry()
            }
        }
        val listing = ArchiveEntryLister.list(f)
        assertEquals(SafeZipReader.MAX_ENTRIES, listing.rows.size)
        assertTrue(listing.capped)
        val doc = ArchiveEntryLister.defaultListing(f)
        assertTrue("marker: ${doc.notes}", doc.notes.any { it.contains("showing 500 of 520 entries") })
    }

    @Test fun malformedZip_isHonestRefusal() {
        val f = tmp.newFile("bad.zip")
        f.writeBytes(byteArrayOf(0x50, 0x4b, 0x03, 0x04) + "trunc".toByteArray())
        val doc = ArchiveEntryLister.defaultListing(f)
        assertNotNull(doc.refusal)
        val msg = doc.refusal
        assertTrue(msg != null && msg.contains("not a readable archive"))
    }

    @Test fun binaryEntry_refusesTextPreview() {
        val f = tmp.newFile("b.zip")
        ZipOutputStream(f.outputStream()).use { z ->
            z.putNextEntry(ZipEntry("blob.bin")); z.write(byteArrayOf(0x00, 0x01, 0x02, 0x03)); z.closeEntry()
            z.putNextEntry(ZipEntry("text.txt")); z.write("plain text".toByteArray()); z.closeEntry()
        }
        val bin = ArchiveEntryLister.previewEntry(f, "blob.bin")
        assertTrue(bin.refusal!!.contains("binary entry"))
        val text = ArchiveEntryLister.previewEntry(f, "text.txt")
        assertEquals("plain text", text.text)
    }

    @Test fun preview_isRedacted() {
        val f = zipOf("secrets.txt" to "token sk-ABCDEFGHIJKLMNOPQRSTUVWXYZ12 here")
        val doc = ArchiveEntryLister.previewEntry(f, "secrets.txt")
        assertTrue("redacted: [${doc.text}]", doc.text.contains("[REDACTED]"))
        assertTrue(doc.notes.any { it.contains("secrets redacted") })
    }

    @Test fun tar_listing_andPreview() {
        val f = tmp.newFile("t.tar")
        // minimal tar via commons-compress's own writer keeps the fixture real
        org.apache.commons.compress.archivers.tar.TarArchiveOutputStream(f.outputStream()).use { tar ->
            for ((n, c) in listOf("one.txt" to "first entry", "two.txt" to "second entry")) {
                val data = c.toByteArray()
                val e = org.apache.commons.compress.archivers.tar.TarArchiveEntry(n)
                e.size = data.size.toLong()
                tar.putArchiveEntry(e); tar.write(data); tar.closeArchiveEntry()
            }
        }
        val doc = ArchiveEntryLister.defaultListing(f)
        assertEquals(null, doc.refusal)
        assertTrue("rows listed: [${doc.text}]", doc.text.contains("one.txt") && doc.text.contains("two.txt"))
        val preview = ArchiveEntryLister.previewEntry(f, "two.txt")
        assertEquals("second entry", preview.text)
    }
}
