package com.codespace.ide.agent.voice

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * PROJECT 2.16: what Copilot says out loud, and what it must never say.
 *
 * Reading a code fence aloud, pronouncing asterisks, or intoning a table row is the difference
 * between a voice assistant and a screen reader.
 */
class VoiceScriptTest {

    @Test
    fun fencesBecomeAPlaceholderAndTheirContentsAreDropped() {
        val text = "Here is the fix.\n\n```kotlin\nfun main() { println(42) }\n```\n\nThat should do it."
        val spoken = VoiceScript.strip(text)
        assertTrue(spoken, !spoken.contains("println"))
        assertTrue(spoken, !spoken.contains("```"))
        assertTrue(spoken, spoken.contains("Code block"))
        assertTrue(spoken, spoken.contains("That should do it"))
    }

    @Test
    fun inlineCodeLosesItsTicksButKeepsTheWord() {
        assertEquals("Run npm install first.", VoiceScript.strip("Run `npm install` first."))
    }

    @Test
    fun linksKeepTheirWordsAndImagesVanish() {
        val spoken = VoiceScript.strip("See [the docs](https://example.com) and ![a chart](x.png).")
        assertTrue(spoken, spoken.contains("the docs"))
        assertTrue(spoken, !spoken.contains("example.com"))
    }

    @Test
    fun headingsAndEmphasisAreNotPronounced() {
        val spoken = VoiceScript.strip("## Result\n\nThis is **very** important and *quite* urgent.")
        assertTrue(spoken, !spoken.contains("##"))
        assertTrue(spoken, !spoken.contains("**"))
        assertTrue(spoken, spoken.contains("very important"))
    }

    @Test
    fun bulletsLoseTheirMarkers() {
        val spoken = VoiceScript.strip("- first point\n- second point")
        assertEquals("first point\nsecond point", spoken)
    }

    @Test
    fun tableRowsAreNotSpoken() {
        val spoken = VoiceScript.strip("| a | b |\n|---|---|\n| 1 | 2 |\n\nThe table is done.")
        assertTrue(spoken, !spoken.contains("|"))
        assertTrue(spoken, spoken.contains("The table is done"))
    }

    @Test
    fun aReplyIsSplitAtSentenceBoundariesSoTheFirstOneCanStartEarly() {
        val segments = VoiceScript.segments(
            "The build is green. Three tests were added. Nothing else changed.",
            minChars = 1,
        )
        assertEquals(3, segments.size)
        assertEquals("The build is green.", segments[0])
    }

    @Test
    fun shortStubsAreMergedRatherThanSpokenAlone() {
        val segments = VoiceScript.segments("Yes. The fix is in place and the build is green.", minChars = 24)
        assertEquals(1, segments.size)
        assertTrue(segments[0].startsWith("Yes."))
    }

    @Test
    fun aWallOfTextIsBrokenIntoInterruptiblePieces() {
        val long = (1..12).joinToString(" ") { "This is sentence number " + it + " of a very long answer" } + "."
        val segments = VoiceScript.segments(long, minChars = 10, maxChars = 120)
        assertTrue("expected several pieces, got " + segments.size, segments.size > 2)
        segments.forEach { assertTrue("piece too long: " + it.length, it.length <= 140) }
    }

    @Test
    fun aDecimalsDecimalPointDoesNotEndASentence() {
        val segments = VoiceScript.segments("Version 1.5 shipped today and it works well.", minChars = 1)
        assertTrue(segments.toString(), segments.none { it == "Version 1." })
    }

    @Test
    fun anEmptyReplyProducesNothingToSay() {
        assertTrue(VoiceScript.segments("").isEmpty())
        assertTrue(VoiceScript.segments("```\nonly code\n```").none { it.contains("only code") })
    }

    @Test
    fun aCodeOnlyReplySaysOnlyThatItIsACodeBlock() {
        // It is still worth a word: silence would look like a failure.
        val segments = VoiceScript.segments("```\ncode only\n```")
        assertEquals(1, segments.size)
        assertTrue(segments[0], !segments[0].contains("code only"))
        assertTrue(VoiceScript.speakable("A real sentence."))
    }

    @Test
    fun longSingleSentenceIsCutAtACommaNotMidWord() {
        val text = "This sentence has a first clause that runs on, and a second clause that also runs on, and a third clause to finish it off completely."
        val pieces = VoiceScript.splitLong(text, 60)
        assertTrue(pieces.size > 1)
        pieces.forEach { assertTrue("cut mid-word: " + it, !it.endsWith(" ,")) }
        assertEquals(text.replace(Regex("\\s+"), " ").trim(), pieces.joinToString(" ").replace(Regex("\\s+"), " ").trim())
    }
}
