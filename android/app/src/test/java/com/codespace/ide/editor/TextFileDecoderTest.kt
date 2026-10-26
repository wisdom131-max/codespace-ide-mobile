package com.codespace.ide.editor

import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * A1-device-fix regression tests, part 2 — the ACTUAL decode seam the editor
 * read path uses (FileCache -> TextFileDecoder). The classifier decision-table
 * tests proved sniff() was right; these prove the OPEN PATH honors it.
 */
class TextFileDecoderTest {

    @get:Rule val tmp = TemporaryFolder()

    @Test fun utf16LeBom_decodesReadable() {
        val bytes = byteArrayOf(0xFF.toByte(), 0xFE.toByte()) + "package t;\npublic class A {}\n".toByteArray(Charsets.UTF_16LE)
        assertEquals("package t;\npublic class A {}\n", TextFileDecoder.decode(bytes))
    }

    @Test fun utf16BeBom_decodesReadable() {
        val bytes = byteArrayOf(0xFE.toByte(), 0xFF.toByte()) + "package t;\npublic class B {}\n".toByteArray(Charsets.UTF_16BE)
        assertEquals("package t;\npublic class B {}\n", TextFileDecoder.decode(bytes))
    }

    @Test fun utf8Bom_stripped_decoded() {
        val bytes = byteArrayOf(0xEF.toByte(), 0xBB.toByte(), 0xBF.toByte()) + "plain utf8".toByteArray()
        assertEquals("plain utf8", TextFileDecoder.decode(bytes))
    }

    @Test fun noBom_unchangedUtf8() {
        assertEquals("just text", TextFileDecoder.decode("just text".toByteArray()))
    }

    @Test fun fileRead_route_decodesUtf16File() {
        val f = tmp.newFile("Utf16.java")
        f.writeBytes(byteArrayOf(0xFF.toByte(), 0xFE.toByte()) + "class C {}".toByteArray(Charsets.UTF_16LE))
        assertEquals("class C {}", TextFileDecoder.readText(f.absolutePath))
    }
}
