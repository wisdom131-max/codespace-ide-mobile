package com.codespace.ide.agent.voice

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * PROJECT 2.18: never send a voice to a server the user did not choose.
 *
 * `EXTRA_PREFER_OFFLINE` is a hint the recogniser may ignore, so these tests pin the decision to the
 * capability the platform reported rather than to the extra we asked for.
 */
class RecognitionPlanTest {

    private fun cap(
        installed: Boolean = true,
        onDevice: Boolean = true,
        pack: Boolean = true,
        cloud: Boolean = false,
        language: String = "en-US",
    ) = RecognitionPlan.Capability(installed, onDevice, pack, cloud, language)

    @Test
    fun onDeviceIsPreferredWhenItIsReallyThere() {
        val decision = RecognitionPlan.decide(cap())
        assertTrue(decision is RecognitionPlan.Decision.OnDevice)
        val extras = RecognitionPlan.engineExtras(decision)
        assertTrue(extras.requireOnDevice)
        assertTrue(extras.preferOffline)
    }

    @Test
    fun aMissingLanguagePackIsNotOnDeviceRecognition() {
        val decision = RecognitionPlan.decide(cap(pack = false))
        assertTrue(decision is RecognitionPlan.Decision.Unavailable)
        assertTrue((decision as RecognitionPlan.Decision.Unavailable).reason.contains("language pack"))
    }

    @Test
    fun withoutConsentItRefusesInsteadOfGoingToTheCloud() {
        val decision = RecognitionPlan.decide(cap(onDevice = false, cloud = false))
        assertTrue(decision is RecognitionPlan.Decision.Unavailable)
        val reason = (decision as RecognitionPlan.Decision.Unavailable).reason
        assertTrue(reason, reason.contains("cloud recognition is switched off"))
    }

    @Test
    fun withConsentItSaysPlainlyWhereTheAudioGoes() {
        val decision = RecognitionPlan.decide(cap(onDevice = false, cloud = true))
        assertTrue(decision is RecognitionPlan.Decision.Cloud)
        val cloud = decision as RecognitionPlan.Decision.Cloud
        assertTrue(cloud.disclosure, cloud.disclosure.contains("Google"))
        assertTrue(cloud.disclosure, cloud.disclosure.contains("audio"))
    }

    @Test
    fun aDeviceWithNoRecognizerAtAllSaysSo() {
        val decision = RecognitionPlan.decide(cap(installed = false))
        assertTrue(decision is RecognitionPlan.Decision.Unavailable)
        assertTrue((decision as RecognitionPlan.Decision.Unavailable).reason.contains("no speech recognition service"))
    }

    @Test
    fun cloudDecisionsNeverAskForOfflineOnly() {
        val extras = RecognitionPlan.engineExtras(RecognitionPlan.decide(cap(onDevice = false, cloud = true)))
        assertTrue(!extras.requireOnDevice)
        assertTrue(!extras.preferOffline)
    }
}
