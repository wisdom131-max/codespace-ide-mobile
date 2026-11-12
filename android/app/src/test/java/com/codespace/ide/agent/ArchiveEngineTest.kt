package com.codespace.ide.agent

import java.io.File
import java.io.FileOutputStream
import org.apache.commons.compress.archivers.tar.TarArchiveEntry
import org.apache.commons.compress.archivers.tar.TarArchiveOutputStream
import org.apache.commons.compress.compressors.gzip.GzipCompressorOutputStream
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * P2.B: one engine for the formats this build can read, and an honest refusal for the ones it cannot.
 * Signature sniffing matters because a renamed archive is still an archive.
 */
class ArchiveEngineTest {

    @get:Rule val tmp = TemporaryFolder()

    private fun tarGz(name: String, entries: Map<String, String>): File {
        val f = File(tmp.root, name)
        FileOutputStream(f).use { raw ->
            GzipCompressorOutputStream(raw).use { gz ->
                TarArchiveOutputStream(gz).use { tar ->
                    for ((path, body) in entries) {
                        val bytes = body.toByteArray()
                        val e = TarArchiveEntry(path)
                        e.size = bytes.size.toLong()
                        tar.putArchiveEntry(e)
                        tar.write(bytes)
                        tar.closeArchiveEntry()
                    }
                    tar.finish()
                }
            }
        }
        return f
    }

    private fun plainTar(name: String, entries: Map<String, String>): File {
        val f = File(tmp.root, name)
        FileOutputStream(f).use { raw ->
            TarArchiveOutputStream(raw).use { tar ->
                for ((path, body) in entries) {
                    val bytes = body.toByteArray()
                    val e = TarArchiveEntry(path)
                    e.size = bytes.size.toLong()
                    tar.putArchiveEntry(e)
                    tar.write(bytes)
                    tar.closeArchiveEntry()
                }
            }
        }
        return f
    }

    private fun gzip(name: String, body: String): File {
        val f = File(tmp.root, name)
        FileOutputStream(f).use { raw ->
            GzipCompressorOutputStream(raw).use { it.write(body.toByteArray()) }
        }
        return f
    }

    @Test fun aTarIsRecognisedByItsSignature() {
        assertEquals(ArchiveEngine.Kind.TAR, ArchiveEngine.kindOf(plainTar("a.tar", mapOf("x.txt" to "x"))))
    }

    @Test fun aTarGzIsRecognisedAsGzipAndItsPayloadAsTar() {
        val f = tarGz("a.tar.gz", mapOf("one.txt" to "1"))
        assertEquals(ArchiveEngine.Kind.GZ, ArchiveEngine.kindOf(f))
        val listing = ArchiveEngine.list(f)
        assertNotNull("a gz holding a tar must not be offered as a single anonymous file", listing.note)
    }

    @Test fun aPlainGzipIsOneFile() {
        val listing = ArchiveEngine.list(gzip("notes.txt.gz", "hello"))
        assertEquals(ArchiveEngine.Kind.GZ, listing.kind)
        assertEquals(1, listing.total)
        assertEquals("notes.txt", listing.items.first().name)
    }

    /**
     * P2.C reversed this test: RAR and Zstandard are supported now, so the old assertions would have
     * gone red on the first run after the change. What still matters is that a format with no reader
     * keeps answering with a reason instead of pretending.
     */
    @Test fun rarZstAndTarZstAreSupportedAndNothingElsePretends() {
        assertTrue(ArchiveEngine.supported(ArchiveEngine.Kind.RAR))
        assertTrue(ArchiveEngine.supported(ArchiveEngine.Kind.ZST))
        assertTrue(ArchiveEngine.supported(ArchiveEngine.Kind.TAR_ZST))
        assertNull(ArchiveEngine.unsupportedReason(ArchiveEngine.Kind.RAR))
        assertNull(ArchiveEngine.unsupportedReason(ArchiveEngine.Kind.ZST))
        assertNull(ArchiveEngine.unsupportedReason(ArchiveEngine.Kind.TAR_ZST))
        assertNull(ArchiveEngine.unsupportedReason(ArchiveEngine.Kind.TAR))
        // UNKNOWN is the one kind that must always explain itself.
        assertNotNull(ArchiveEngine.unsupportedReason(ArchiveEngine.Kind.UNKNOWN))
    }

    @Test fun anUnknownFileSaysSoRatherThanGuessing() {
        val f = File(tmp.root, "nonsense.bin")
        f.writeText("this is not an archive at all")
        assertEquals(ArchiveEngine.Kind.UNKNOWN, ArchiveEngine.kindOf(f))
        assertNotNull(ArchiveEngine.list(f).note)
    }

    @Test fun aTarListsItsEntries() {
        val listing = ArchiveEngine.list(plainTar("b.tar", mapOf("dir/one.txt" to "1", "two.txt" to "22")))
        assertEquals(2, listing.total)
        assertEquals(listOf("dir/one.txt", "two.txt"), listing.items.map { it.name })
        assertEquals(2L, listing.items.last().size)
    }

    @Test fun aTarIsExtractedUnderTheDestinationOnly() {
        val f = plainTar("c.tar", mapOf("sub/keep.txt" to "keep me"))
        val dest = File(tmp.root, "out-c")
        val out = ArchiveEngine.extract(f, dest)
        assertTrue(out.ok)
        assertEquals("keep me", File(dest, "sub/keep.txt").readText())
    }

    @Test fun aTraversalEntryIsSkippedNotWritten() {
        val f = plainTar("evil.tar", mapOf("../escape.txt" to "nope", "ok.txt" to "fine"))
        val dest = File(tmp.root, "out-evil")
        val out = ArchiveEngine.extract(f, dest)
        assertTrue(out.skipped.any { it.contains("unsafe") || it.contains("escape") })
        assertFalse("nothing may be written outside the destination", File(tmp.root, "escape.txt").exists())
        assertTrue(File(dest, "ok.txt").exists())
    }

    @Test fun anExistingFileIsNotOverwrittenWithoutForce() {
        val f = plainTar("d.tar", mapOf("same.txt" to "new"))
        val dest = File(tmp.root, "out-d").also { it.mkdirs() }
        File(dest, "same.txt").writeText("old")
        val refused = ArchiveEngine.extract(f, dest, overwrite = false)
        assertEquals("old", File(dest, "same.txt").readText())
        assertTrue(refused.skipped.any { it.contains("exists") })
        val allowed = ArchiveEngine.extract(f, dest, overwrite = true)
        assertTrue(allowed.ok)
        assertEquals("new", File(dest, "same.txt").readText())
    }

    @Test fun selectiveExtractionTakesOnlyTheNamedEntry() {
        val f = plainTar("e.tar", mapOf("a.txt" to "a", "b.txt" to "b"))
        val dest = File(tmp.root, "out-e")
        ArchiveEngine.extract(f, dest, only = listOf("a.txt"))
        assertTrue(File(dest, "a.txt").exists())
        assertFalse(File(dest, "b.txt").exists())
    }

    @Test fun aGzipPayloadIsDecompressedToItsOwnName() {
        val f = gzip("report.log.gz", "line")
        val dest = File(tmp.root, "out-gz")
        val out = ArchiveEngine.extract(f, dest)
        assertTrue(out.ok)
        assertEquals("line", File(dest, "report.log").readText())
    }
}
