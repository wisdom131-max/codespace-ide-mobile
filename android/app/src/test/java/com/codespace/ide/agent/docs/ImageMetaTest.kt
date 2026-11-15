package com.codespace.ide.agent.docs

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * PROJECT 2.9: the size of an image comes from its header, not from decoding it.
 *
 * Each sample is assembled byte by byte to the format's own specification, so the numbers asserted
 * are the numbers the format implies rather than whatever one particular encoder wrote.
 */
class ImageMetaTest {

    private fun bytes(vararg v: Int): ByteArray = ByteArray(v.size) { v[it].toByte() }

    private fun png(w: Int, h: Int, depth: Int = 8, colour: Int = 6): ByteArray {
        val out = ArrayList<Byte>()
        listOf(0x89, 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a).forEach { out.add(it.toByte()) }
        listOf(0, 0, 0, 13).forEach { out.add(it.toByte()) }
        "IHDR".toByteArray().forEach { out.add(it) }
        listOf(w shr 24, w shr 16, w shr 8, w).forEach { out.add(it.toByte()) }
        listOf(h shr 24, h shr 16, h shr 8, h).forEach { out.add(it.toByte()) }
        out.add(depth.toByte()); out.add(colour.toByte())
        listOf(0, 0, 0).forEach { out.add(it.toByte()) }
        return out.toByteArray()
    }

    private fun gif(w: Int, h: Int): ByteArray {
        val out = ArrayList<Byte>()
        "GIF89a".toByteArray().forEach { out.add(it) }
        listOf(w and 0xff, (w shr 8) and 0xff, h and 0xff, (h shr 8) and 0xff).forEach { out.add(it.toByte()) }
        repeat(4) { out.add(0) }
        return out.toByteArray()
    }

    private fun bmp(w: Int, h: Int): ByteArray {
        val out = ArrayList<Byte>()
        "BM".toByteArray().forEach { out.add(it) }
        repeat(12) { out.add(0) }
        out.add(40); repeat(3) { out.add(0) }
        listOf(w and 0xff, (w shr 8) and 0xff, 0, 0).forEach { out.add(it.toByte()) }
        listOf(h and 0xff, (h shr 8) and 0xff, 0, 0).forEach { out.add(it.toByte()) }
        repeat(4) { out.add(0) }
        return out.toByteArray()
    }

    /** Baseline JPEG: SOI, an APP1 carrying one EXIF orientation tag, then SOF0 with the size. */
    private fun jpeg(w: Int, h: Int, orientation: Int?): ByteArray {
        val out = ArrayList<Byte>()
        out.add(0xff.toByte()); out.add(0xd8.toByte())
        if (orientation != null) {
            val tiff = ArrayList<Byte>()
            "MM".toByteArray().forEach { tiff.add(it) }
            listOf(0, 42, 0, 0, 0, 8).forEach { tiff.add(it.toByte()) }        // big-endian, IFD at 8
            listOf(0, 1).forEach { tiff.add(it.toByte()) }                      // one entry
            listOf(0x01, 0x12, 0, 3, 0, 0, 0, 1, 0, orientation, 0, 0).forEach { tiff.add(it.toByte()) }
            listOf(0, 0, 0, 0).forEach { tiff.add(it.toByte()) }                // no next IFD
            val body = ArrayList<Byte>()
            "Exif".toByteArray().forEach { body.add(it) }
            body.add(0); body.add(0)
            body.addAll(tiff)
            val len = body.size + 2
            out.add(0xff.toByte()); out.add(0xe1.toByte())
            out.add((len shr 8).toByte()); out.add((len and 0xff).toByte())
            out.addAll(body)
        }
        out.add(0xff.toByte()); out.add(0xc0.toByte())
        listOf(0, 11, 8).forEach { out.add(it.toByte()) }
        out.add((h shr 8).toByte()); out.add((h and 0xff).toByte())
        out.add((w shr 8).toByte()); out.add((w and 0xff).toByte())
        out.add(3)
        repeat(9) { out.add(0) }
        return out.toByteArray()
    }

    @Test
    fun pngSizeComesFromIhdr() {
        val info = ImageMeta.of(png(1920, 1080))!!
        assertEquals("PNG", info.format)
        assertEquals(1920, info.width)
        assertEquals(1080, info.height)
        assertTrue(info.detail.contains("truecolour + alpha"))
    }

    @Test
    fun gifSizeComesFromTheScreenDescriptor() {
        val info = ImageMeta.of(gif(320, 200))!!
        assertEquals(320, info.width)
        assertEquals(200, info.height)
    }

    @Test
    fun bmpSizeComesFromTheInfoHeader() {
        val info = ImageMeta.of(bmp(640, 480))!!
        assertEquals(640, info.width)
        assertEquals(480, info.height)
    }

    @Test
    fun jpegSizeComesFromTheStartOfFrame() {
        val info = ImageMeta.of(jpeg(4032, 3024, null))!!
        assertEquals("JPEG", info.format)
        assertEquals(4032, info.width)
        assertEquals(3024, info.height)
        assertEquals(1, info.orientation)
        assertTrue("no EXIF means no rotation", !info.isRotated())
    }

    @Test
    fun aRotatedPhonePhotoIsReportedAsDisplayedNotAsStored() {
        val info = ImageMeta.of(jpeg(4032, 3024, orientation = 6))!!
        assertEquals(6, info.orientation)
        assertTrue(info.isRotated())
        val text = ImageMeta.render(info)
        assertTrue(text, text.contains("3024x4032 as displayed"))
        assertTrue(text, text.contains("stored 4032x3024"))
    }

    @Test
    fun aLittleEndianExifBlockIsUnderstoodToo() {
        val out = ArrayList<Byte>()
        out.add(0xff.toByte()); out.add(0xd8.toByte())
        val body = ArrayList<Byte>()
        "Exif".toByteArray().forEach { body.add(it) }
        body.add(0); body.add(0)
        "II".toByteArray().forEach { body.add(it) }
        listOf(42, 0, 8, 0, 0, 0).forEach { body.add(it.toByte()) }
        listOf(1, 0).forEach { body.add(it.toByte()) }
        listOf(0x12, 0x01, 3, 0, 1, 0, 0, 0, 8, 0, 0, 0).forEach { body.add(it.toByte()) }
        listOf(0, 0, 0, 0).forEach { body.add(it.toByte()) }
        val len = body.size + 2
        out.add(0xff.toByte()); out.add(0xe1.toByte())
        out.add((len shr 8).toByte()); out.add((len and 0xff).toByte())
        out.addAll(body)
        out.add(0xff.toByte()); out.add(0xc0.toByte())
        listOf(0, 11, 8, 0x0b, 0xd0, 0x10, 0x00, 3).forEach { out.add(it.toByte()) }
        repeat(9) { out.add(0) }
        val info = ImageMeta.of(out.toByteArray())!!
        assertEquals(8, info.orientation)
        assertTrue(info.isRotated())
    }

    @Test
    fun somethingThatIsNotAnImageIsRefused() {
        assertNull(ImageMeta.of(bytes(0x42, 0x4d, 0x0)))
        assertNull(ImageMeta.of(ByteArray(0)))
        assertNotNull(ImageMeta.of(png(1, 1)))
    }

    @Test
    fun theRenderedLineLeadsWithTheFormatAndTheShownSize() {
        val text = ImageMeta.render(ImageMeta.of(png(800, 600))!!)
        assertTrue(text, text.startsWith("PNG, 800x600 as displayed"))
    }
}
