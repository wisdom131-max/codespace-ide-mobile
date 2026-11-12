package com.codespace.ide.agent

import com.github.luben.zstd.ZstdOutputStream
import java.io.File
import java.io.FileOutputStream
import org.apache.commons.compress.archivers.tar.TarArchiveEntry
import org.apache.commons.compress.archivers.tar.TarArchiveOutputStream
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * P2.C: Zstandard is a compressor, not an archive. These tests hold the engine to that distinction -
 * a .zst is one file, a .tar.zst is a real tar - and to the bounds that keep a hostile stream from
 * filling a phone.
 *
 * Fixtures are generated here with `ZstdOutputStream`: nothing binary needs committing. The desktop
 * native that makes this runnable on the JVM comes from the `testImplementation` classifier in
 * `app/build.gradle.kts`; the APK itself carries the Android natives from the aar.
 */
class ZstdEngineTest {

    @get:Rule val tmp = TemporaryFolder()

    private fun zstd(name: String, body: ByteArray): File {
        val f = File(tmp.root, name)
        FileOutputStream(f).use { raw -> ZstdOutputStream(raw).use { it.write(body) } }
        return f
    }

    private fun tarBytes(entries: Map<String, String>): ByteArray {
        val buffer = java.io.ByteArrayOutputStream()
        TarArchiveOutputStream(buffer).use { tar ->
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
        return buffer.toByteArray()
    }

    @Test fun aPlainZstIsOneEntryNamedAfterTheFile() {
        val f = zstd("notes.txt.zst", "hello zstd".toByteArray())
        assertEquals(ArchiveEngine.Kind.ZST, ArchiveEngine.kindOf(f))
        val listing = ArchiveEngine.list(f)
        assertEquals(1, listing.total)
        assertEquals("notes.txt", listing.items.first().name)
        assertNull(listing.note)
    }

    @Test fun aPlainZstDecompressesToItsContent() {
        val body = "the quick brown fox".repeat(200)
        val f = zstd("notes.txt.zst", body.toByteArray())
        val dest = tmp.newFolder("out")
        val out = ArchiveEngine.extract(f, dest)
        assertNull(out.failure)
        assertEquals(listOf("notes.txt"), out.written)
        assertEquals(body, File(dest, "notes.txt").readText())
    }

    @Test fun aTarZstIsWalkedAsARealTar() {
        val f = zstd("bundle.tar.zst", tarBytes(mapOf("a.txt" to "alpha", "dir/b.txt" to "beta")))
        assertEquals(ArchiveEngine.Kind.TAR_ZST, ArchiveEngine.resolvedKind(f))
        val listing = ArchiveEngine.list(f)
        assertEquals(2, listing.total)
        assertEquals(listOf("a.txt", "dir/b.txt"), listing.items.map { it.name })
    }

    @Test fun aTarZstExtractsItsEntriesAndSkipsTraversal() {
        val f = zstd(
            "evil.tar.zst",
            tarBytes(mapOf("ok.txt" to "safe", "../escaped.txt" to "nope")),
        )
        val dest = tmp.newFolder("out")
        val out = ArchiveEngine.extract(f, dest)
        assertEquals(listOf("ok.txt"), out.written)
        assertTrue(out.skipped.any { it.contains("escaped.txt") })
        assertEquals("safe", File(dest, "ok.txt").readText())
        assertTrue(!File(dest.parentFile, "escaped.txt").exists())
    }

    @Test fun concatenatedFramesDecodeToTheEnd() {
        // `zstd -c a b > out.zst` produces this shape. Without continuous mode the second frame is lost.
        val first = zstd("first.zst", "first".toByteArray()).readBytes()
        val second = zstd("second.zst", "second".toByteArray()).readBytes()
        val both = File(tmp.root, "both.zst")
        both.writeBytes(first + second)
        val dest = File(tmp.root, "both.txt")
        val result = ZstdEngine.decompressTo(both, dest, 1024L * 1024L)
        assertNull(result.failure)
        assertEquals("firstsecond", dest.readText())
    }

    @Test fun aTruncatedStreamFailsAndLeavesNoPartialFile() {
        val whole = zstd("whole.zst", ByteArray(200_000) { 'x'.code.toByte() }).readBytes()
        val cut = File(tmp.root, "cut.zst")
        cut.writeBytes(whole.copyOf(whole.size / 2))
        val dest = File(tmp.root, "cut.out")
        val result = ZstdEngine.decompressTo(cut, dest, 10L * 1024L * 1024L)
        assertNotNull(result.failure)
        assertTrue("a failed decode must not leave a partial file behind", !dest.exists())
    }

    @Test fun theOutputCeilingStopsTheDecode() {
        val f = zstd("big.txt.zst", ByteArray(500_000) { 'y'.code.toByte() })
        val dest = File(tmp.root, "big.txt")
        val result = ZstdEngine.decompressTo(f, dest, 1000L)
        assertNotNull(result.failure)
        assertTrue(!dest.exists())
    }

    @Test fun theWindowCeilingIsTheDocumentedOne() {
        // 2^26 = 64 MiB. Asserted so a silent weakening of the cap fails here rather than on a phone.
        assertEquals(26, ZstdEngine.MAX_WINDOW_LOG)
    }

    @Test fun garbageIsNotMistakenForZstd() {
        val f = File(tmp.root, "nonsense.zst")
        f.writeText("this is not zstandard at all")
        assertEquals(ArchiveEngine.Kind.UNKNOWN, ArchiveEngine.kindOf(f))
        assertNotNull(ArchiveEngine.list(f).note)
    }
}
