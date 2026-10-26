package com.codespace.ide.ui.screens

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.io.RandomAccessFile

/**
 * A1-device-fix regression tests, part 1 — the ACTUAL open gate every open
 * route calls (Explorer via onOpenFile, search panel, problems jump, AI-open).
 *
 * The build-565 device round proved the decision-table tests were testing the
 * wrong thing: the classifier was correct, but the Explorer TAP never reached
 * it (its own 8KB NUL sniff routed binaries to the hex viewer first). These
 * tests pin binaryOpenVerdictFor itself with REAL files on disk — the exact
 * function the routes call — including the restored-tab guard behavior.
 */
class BinaryOpenGateTest {

    @get:Rule val tmp = TemporaryFolder()

    private fun mkFile(name: String, bytes: ByteArray): File {
        val f = File(tmp.newFolder(), name)
        f.writeBytes(bytes)
        return f
    }

    @Test fun zipMagicRenamedJava_openPathRaisesGate() {
        val f = mkFile("Fake.java", byteArrayOf(
            'P'.code.toByte(), 'K'.code.toByte(), 3, 4, 0, 0, 0, 0,
            'r'.code.toByte(), 'e'.code.toByte(), 's'.code.toByte(), 't'.code.toByte()))
        val v = binaryOpenVerdictFor(f.absolutePath)
        assertNotNull("zip-magic file must NOT pass the open gate", v)
        assertEquals(com.codespace.ide.chat.AttachmentTypeKind.ZIP, v!!.kind)
    }

    @Test fun randomBinaryRenamedJava_openPathRaisesGate() {
        val bytes = ByteArray(4096) { (it * 7 + 3).toByte() }  // deterministic NUL-bearing junk
        bytes[0] = 0xCA.toByte(); bytes[1] = 0xFE.toByte(); bytes[2] = 0xBA.toByte(); bytes[3] = 0xBE.toByte()
        val f = mkFile("Binary.java", bytes)
        val v = binaryOpenVerdictFor(f.absolutePath)
        assertNotNull("NUL-bearing binary must NOT pass the open gate", v)
        assertEquals(com.codespace.ide.chat.AttachmentTypeKind.OTHER_BINARY, v!!.kind)
    }

    @Test fun utf16BomJava_openPathPassesGate() {
        val f = mkFile("Utf16.java",
            byteArrayOf(0xFF.toByte(), 0xFE.toByte()) + "package t;".toByteArray(Charsets.UTF_16LE))
        assertNull("UTF-16+BOM source must OPEN (the dialog is not for text)",
            binaryOpenVerdictFor(f.absolutePath))
    }

    @Test fun plainJava_openPathPassesGate() {
        val f = mkFile("Plain.java", "package t;".toByteArray())
        assertNull(binaryOpenVerdictFor(f.absolutePath))
    }

    @Test fun missingFile_openPathPassesGate() {
        // Unreadable/missing files must not nag: the editor shows its own read error.
        assertNull(binaryOpenVerdictFor(File(tmp.newFolder(), "gone.java").absolutePath))
    }

    @Test fun largeFile_headOnlyIsRead() {
        // The gate reads only SNIFF_WINDOW_BYTES even from a huge file (no OOM path).
        val f = mkFile("big.java", ByteArray(2_000_000) { 65 })
        RandomAccessFile(f, "rw").use { it.seek(500_000); it.write(ByteArray(16)) }
        assertNull(binaryOpenVerdictFor(f.absolutePath))
    }
}
