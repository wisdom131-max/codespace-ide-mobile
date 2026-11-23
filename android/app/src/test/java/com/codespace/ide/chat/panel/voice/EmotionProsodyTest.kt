package com.codespace.ide.chat.panel.voice

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Read-aloud emotions (owner request, 2026-11-23). The classifier is pure, so it is pinned here
 * rather than discovered by listening to a phone.
 */
class EmotionProsodyTest {

    @Test
    fun eachEmotionIsRecognised() {
        assertEquals(SpeechEmotion.EXCITED, EmotionProsody.classify("Done! It works now."))
        assertEquals(SpeechEmotion.APOLOGETIC, EmotionProsody.classify("Sorry, I was wrong about that."))
        assertEquals(SpeechEmotion.SERIOUS, EmotionProsody.classify("Warning: this will delete data."))
        assertEquals(SpeechEmotion.CURIOUS, EmotionProsody.classify("Shall I continue with the next file?"))
        assertEquals(SpeechEmotion.WARM, EmotionProsody.classify("Thanks, let me know how it goes."))
        assertEquals(SpeechEmotion.NEUTRAL, EmotionProsody.classify("The file is at the project root."))
    }

    @Test
    fun cautionBeatsEnthusiasmAndApologyBeatsEverything() {
        // "great" alone would read as excited, but a warning inside the same sentence must win
        assertEquals(SpeechEmotion.SERIOUS, EmotionProsody.classify("Great, but careful: this breaks the build."))
        assertEquals(SpeechEmotion.APOLOGETIC, EmotionProsody.classify("Sorry, but this is a serious error!"))
    }

    @Test
    fun aQuestionIsCuriousEvenWithoutAnOpener() {
        assertEquals(SpeechEmotion.CURIOUS, EmotionProsody.classify("Which project should I write to?"))
    }

    @Test
    fun prosodyMovesInTheRightDirection() {
        val neutral = EmotionProsody.prosodyFor(SpeechEmotion.NEUTRAL)
        val excited = EmotionProsody.prosodyFor(SpeechEmotion.EXCITED)
        val serious = EmotionProsody.prosodyFor(SpeechEmotion.SERIOUS)
        assertTrue("pleased should lift pitch", excited.pitch > neutral.pitch)
        assertTrue("grave should lower pitch", serious.pitch < neutral.pitch)
        assertTrue("grave should slow down", serious.rate < neutral.rate)
        assertTrue("pleased should quicken", excited.rate > neutral.rate)
    }

    @Test
    fun prosodyIsAlwaysInsideTheEnginesRange() {
        SpeechEmotion.values().forEach { e ->
            val p = EmotionProsody.prosodyFor(e)
            assertTrue("pitch in range for " + e, p.pitch in 0.5f..2.0f)
            assertTrue("rate in range for " + e, p.rate in 0.5f..2.0f)
        }
    }

    @Test
    fun sentencesSplitOnRealBreaks() {
        val parts = EmotionProsody.sentences("First line.\nSecond line! Is this third? Yes.")
        assertEquals(listOf("First line.", "Second line!", "Is this third?", "Yes."), parts)
    }

    @Test
    fun aVeryLongSentenceIsCutAtACommaSoOneToneCannotHold() {
        val long = "This clause is long enough to matter, " + "and this one keeps going with more words ".repeat(6)
        val parts = EmotionProsody.sentences(long, maxChars = 80)
        assertTrue("must break into several", parts.size > 2)
        assertTrue("no piece may exceed the cap", parts.all { it.length <= 80 })
    }

    @Test
    fun aPlanCarriesOneProsodyPerSentence() {
        val plan = EmotionProsody.plan("Warning: careful. Done! Shall I continue?")
        assertEquals(3, plan.size)
        assertEquals(SpeechEmotion.SERIOUS, plan[0].emotion)
        assertEquals(SpeechEmotion.EXCITED, plan[1].emotion)
        assertEquals(SpeechEmotion.CURIOUS, plan[2].emotion)
        assertEquals(EmotionProsody.prosodyFor(SpeechEmotion.CURIOUS), plan[2].prosody)
    }

    @Test
    fun emptyTextProducesNothingToSay() {
        assertTrue(EmotionProsody.plan("").isEmpty())
        assertTrue(EmotionProsody.plan("   \n  ").isEmpty())
    }
}
