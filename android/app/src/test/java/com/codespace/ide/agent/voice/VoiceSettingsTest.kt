package com.codespace.ide.agent.voice

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import com.codespace.ide.agent.voice.VoiceSettings.Candidate

/**
 * PROJECT 2.14: the voice choice, and what happens when the preferred one is not there.
 *
 * Android guarantees nothing about installed voices, so the fallback chain is the feature. A device
 * with no male voice must still speak, and must say that the male preference could not be honoured
 * rather than pretend it was.
 */
class VoiceSettingsTest {

    private fun voice(name: String, locale: String = "en-US", network: Boolean = false) =
        Candidate(name, locale, network)

    @Test
    fun aMaleEnglishVoiceWins() {
        val choice = VoiceSettings.choose(
            listOf(voice("en-us-x-sfg#female_1-local"), voice("en-us-x-iom#male_1-local"), voice("fr-FR-xa#male_2-local")),
        )
        assertTrue(choice is VoiceSettings.Choice.Picked)
        assertEquals("en-us-x-iom#male_1-local", (choice as VoiceSettings.Choice.Picked).candidate.name)
    }

    @Test
    fun theUsersChosenVoiceBeatsTheHeuristic() {
        val choice = VoiceSettings.choose(
            listOf(voice("en-us-x-iom#male_1-local"), voice("en-gb-x-gba#female_1-local")),
            preferredName = "en-gb-x-gba#female_1-local",
        )
        assertEquals("en-gb-x-gba#female_1-local", (choice as VoiceSettings.Choice.Picked).candidate.name)
    }

    @Test
    fun withoutAMaleVoiceItStillSpeaksAndSaysSo() {
        val choice = VoiceSettings.choose(listOf(voice("en-us-x-sfg#female_1-local")))
        val picked = choice as VoiceSettings.Choice.Picked
        assertEquals("en-us-x-sfg#female_1-local", picked.candidate.name)
        assertTrue(picked.why, picked.why.contains("cannot be honoured"))
    }

    @Test
    fun noEnglishAtAllFallsBackToAnythingInstalled() {
        val choice = VoiceSettings.choose(listOf(voice("fr-FR-xa#male_2-local", locale = "fr-FR")))
        assertEquals("fr-FR-xa#male_2-local", (choice as VoiceSettings.Choice.Picked).candidate.name)
    }

    @Test
    fun localOnlyRemovesNetworkVoices() {
        val choice = VoiceSettings.choose(
            listOf(voice("en-us-x-net#male_1-network", network = true), voice("en-us-x-loc#female_1-local")),
            localOnly = true,
        )
        assertEquals("en-us-x-loc#female_1-local", (choice as VoiceSettings.Choice.Picked).candidate.name)
    }

    @Test
    fun localOnlyWithNothingLocalIsUnavailableNotSilent() {
        val choice = VoiceSettings.choose(listOf(voice("en-us-x-net#male_1-network", network = true)), localOnly = true)
        assertTrue(choice is VoiceSettings.Choice.Unavailable)
    }

    @Test
    fun aDeviceWithNoVoicesIsUnavailableWithAReason() {
        val choice = VoiceSettings.choose(emptyList())
        assertTrue(choice is VoiceSettings.Choice.Unavailable)
        assertTrue((choice as VoiceSettings.Choice.Unavailable).reason.contains("no installed voices"))
    }

    @Test
    fun femaleIsNeverMistakenForMale() {
        assertTrue(!VoiceSettings.looksMale("en-us-x-sfg#female_1-local"))
        assertTrue(VoiceSettings.looksMale("en-us-x-iom#male_1-local"))
        assertTrue(VoiceSettings.looksMale("en-GB-male"))
        assertTrue(!VoiceSettings.looksMale("en-us-x-iom-local"))
    }

    @Test
    fun pitchAndRateAreClampedToWhatTheEngineTolerates() {
        assertEquals(1.5f, VoiceSettings.clampPitch(9f), 0.001f)
        assertEquals(0.5f, VoiceSettings.clampPitch(0.01f), 0.001f)
        assertEquals(2.0f, VoiceSettings.clampRate(9f), 0.001f)
        assertEquals(0.5f, VoiceSettings.clampRate(0f), 0.001f)
    }

    @Test
    fun theDefaultTuningIsTheObservedRecipe() {
        assertTrue(VoiceSettings.DEFAULT_PITCH < 1.0f)
        assertTrue(VoiceSettings.DEFAULT_RATE > 1.0f)
        val prefs = VoiceSettings.Prefs()
        assertTrue("spoken replies must be off until asked for", !prefs.spokenReplies)
        assertTrue("welcome greetings are independent and on by default", prefs.welcomeEnabled)
        assertTrue("local only unless the owner chooses otherwise", prefs.localOnly)
    }
}
