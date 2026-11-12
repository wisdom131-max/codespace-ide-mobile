package com.codespace.ide.chat.panel

import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * P7.D: reading a reply aloud must not read source code aloud, and a long answer is bounded rather
 * than endless. This is the pure part, so it is asserted without an audio engine.
 */
class SpeechTextTest {

    @Test fun fencedCodeIsNotSpoken() {
        val spoken = SpeechText.spoken("Here is the fix:\n```kotlin\nval x = 1\n```\nThat is all.")!!
        assertFalse(spoken.contains("val x"))
        assertTrue(spoken.contains("Here is the fix"))
    }

    @Test fun inlineCodeQuotesAndHeadingsAreStripped() {
        val spoken = SpeechText.spoken("## Result\nUse `read_file` to open it.")!!
        assertFalse(spoken.contains("`"))
        assertFalse(spoken.startsWith("#"))
        assertTrue(spoken.contains("read_file"))
    }

    @Test fun aLongAnswerIsCutAtASentenceAndSaysSo() {
        val body = (1..400).joinToString(" ") { "Sentence number $it is here." }
        val spoken = SpeechText.spoken(body)!!
        assertTrue("must be bounded", spoken.length <= SpeechText.MAX_CHARS + 60)
        assertTrue("must admit truncation", spoken.contains("the rest is in the chat"))
    }

    @Test fun aShortAnswerIsSpokenWhole() {
        val spoken = SpeechText.spoken("Done. The file is saved.")!!
        assertTrue(spoken.endsWith("saved."))
        assertFalse(spoken.contains("the rest is in the chat"))
    }

    @Test fun nothingToSayIsNothing() {
        assertNull(SpeechText.spoken("   \n\t "))
        assertNull(SpeechText.spoken("```\nonly code\n```"))
    }

    @Test fun linksAreSpokenAsTheirTextNotTheirUrl() {
        val spoken = SpeechText.spoken("See [the docs](https://example.invalid/x) for more.")!!
        assertTrue(spoken.contains("the docs"))
        assertFalse(spoken.contains("example.invalid"))
    }

    @Test fun aSpeakerWithoutAnEngineReportsUnavailableRatherThanClaimingSpeech() {
        val s = Speaker()
        s.attach(null, false, "no engine")
        assertTrue(s.state is SpeechState.Unavailable)
        s.speak("hello")
        assertTrue("a stop must never pretend it spoke", s.state is SpeechState.Unavailable)
    }
}
