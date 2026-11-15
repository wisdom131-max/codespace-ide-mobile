package com.codespace.ide.agent.docs

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * PROJECT 2.8: a duration must be read from the file, and an estimate must say it is one.
 *
 * The samples here are built byte by byte rather than downloaded, so the numbers asserted are the
 * numbers the container definition actually implies.
 */
class MediaMetaTest {

    private fun wav(seconds: Int, rate: Int = 8000, channels: Int = 1, bits: Int = 8): ByteArray {
        val byteRate = rate * channels * bits / 8
        val dataSize = byteRate * seconds
        val out = ArrayList<Byte>()
        fun put(s: String) = s.toByteArray(Charsets.US_ASCII).forEach { out.add(it) }
        fun le32(v: Int) = (0..3).forEach { out.add(((v shr (8 * it)) and 0xff).toByte()) }
        fun le16(v: Int) = (0..1).forEach { out.add(((v shr (8 * it)) and 0xff).toByte()) }
        put("RIFF"); le32(36 + dataSize); put("WAVE")
        put("fmt "); le32(16); le16(1); le16(channels); le32(rate); le32(byteRate); le16(channels * bits / 8); le16(bits)
        put("data"); le32(dataSize)
        repeat(dataSize) { out.add(0) }
        return out.toByteArray()
    }

    private fun mp4(timescale: Int, duration: Int): ByteArray {
        val out = ArrayList<Byte>()
        fun put(s: String) = s.toByteArray(Charsets.US_ASCII).forEach { out.add(it) }
        fun be32(v: Int) = (3 downTo 0).forEach { out.add(((v shr (8 * it)) and 0xff).toByte()) }
        be32(24); put("ftyp"); put("M4A "); be32(0); be32(0)
        put("moov"); put("mvhd")
        out.add(0); out.add(0); out.add(0); out.add(0) // version + flags
        be32(0); be32(0)                                 // creation, modification
        be32(timescale); be32(duration)
        repeat(64) { out.add(0) }
        return out.toByteArray()
    }

    @Test
    fun aWavDurationComesFromItsOwnHeader() {
        val info = MediaMeta.of(wav(seconds = 3))!!
        assertEquals("WAV (8-bit)", info.format)
        assertEquals(3000L, info.durationMs)
        assertTrue("a stated duration is exact", info.exact)
        assertEquals(8000, info.sampleRateHz)
        assertEquals(1, info.channels)
    }

    @Test
    fun anMp4DurationUsesTimescaleAndDuration() {
        val info = MediaMeta.of(mp4(timescale = 44100, duration = 44100 * 5))!!
        assertEquals(5000L, info.durationMs)
        assertTrue(info.detail, info.detail.contains("timescale 44100"))
        assertTrue(info.format.startsWith("MP4/M4A"))
        assertTrue(info.exact)
    }

    @Test
    fun anId3TaggedMp3StillFindsItsFrame() {
        val out = ArrayList<Byte>()
        "ID3".toByteArray().forEach { out.add(it) }
        out.add(4); out.add(0); out.add(0)
        out.add(0); out.add(0); out.add(0); out.add(0) // syncsafe size 0
        // MPEG-1 Layer III, 128 kbps, 44.1 kHz, stereo
        listOf(0xff, 0xfb, 0x90, 0x00).forEach { out.add(it.toByte()) }
        repeat(64 * 1024) { out.add(0) }
        val info = MediaMeta.of(out.toByteArray())!!
        assertEquals("MP3", info.format)
        assertEquals(44100, info.sampleRateHz)
        assertEquals(2, info.channels)
        assertTrue("MP3 duration is an estimate, and must say so", !info.exact)
        assertNotNull(info.durationMs)
    }

    @Test
    fun somethingThatIsNotMediaIsRefusedRatherThanGuessed() {
        assertNull(MediaMeta.of("a plain text file, honestly not an audio file".toByteArray()))
        assertTrue(MediaMeta.render("nope".toByteArray()).contains("Not a media file"))
    }

    @Test
    fun theRenderedLineLabelsAnEstimate() {
        val out = ArrayList<Byte>()
        "ID3".toByteArray().forEach { out.add(it) }
        listOf(4, 0, 0, 0, 0, 0, 0).forEach { out.add(it.toByte()) }
        listOf(0xff, 0xfb, 0x90, 0x00).forEach { out.add(it.toByte()) }
        repeat(128 * 1024) { out.add(0) }
        val text = MediaMeta.render(out.toByteArray())
        assertTrue(text, text.contains("estimated"))
    }

    @Test
    fun clockReadsLikeATime() {
        assertEquals("0m 3s", MediaMeta.clock(3000))
        assertEquals("2m 5s", MediaMeta.clock(125000))
        assertEquals("1h 0m 0s", MediaMeta.clock(3600000))
    }
}
