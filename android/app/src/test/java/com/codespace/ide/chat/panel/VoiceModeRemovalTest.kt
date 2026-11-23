package com.codespace.ide.chat.panel

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * P2 (2026-11-19): the dedicated Voice Mode experience is gone, and the retained voice
 * capabilities are still there. These are source-level guards, not UI snapshots: they fail if the
 * header chip, the screen or the settings section ever come back, and they fail if dictation or
 * read-aloud get removed along with it.
 */
class VoiceModeRemovalTest {

    private val root = File("src/main/java/com/codespace/ide")

    private fun read(rel: String) = File(root, rel).readText()

    @Test
    fun `the header no longer offers a Voice chip`() {
        val header = read("chat/panel/ChatPanelHeader.kt")
        assertFalse(header.contains("HeaderChip(\"Voice\""))
        assertFalse(header.contains("onVoice"))
        // The dictation trigger must survive the removal. It is no longer a text chip: since
        // 2026-11-23 it is the meter (VoiceMicChip), which is the same control with a waveform in it,
        // so the guard asserts the control the owner actually taps rather than its old wording.
        assertTrue(header.contains("VoiceMicChip("))
        assertTrue(header.contains("onMic"))
    }

    @Test
    fun `the voice mode screen and layer files are gone`() {
        for (f in listOf("VoiceModeScreen.kt", "VoiceModeLayer.kt", "VoiceController.kt", "VoiceTurnMachine.kt")) {
            assertFalse("still present: " + f, File(root, "agent/voice/" + f).exists())
        }
    }

    @Test
    fun `settings no longer expose a voice mode section`() {
        val settings = read("ui/screens/SettingsScreen.kt")
        assertFalse(settings.contains("VoiceSettingsSection"))
    }

    @Test
    fun `dictation and speech components are preserved`() {
        for (f in listOf("VoiceCapture.kt", "VoiceRecognizer.kt", "VoiceProviders.kt", "AndroidTtsProvider.kt")) {
            assertTrue("missing: " + f, File(root, "agent/voice/" + f).exists())
        }
        assertTrue(File(root, "chat/panel/ChatSpeech.kt").exists())
    }

    @Test
    fun `read-aloud no longer waits for a voice mode that cannot open`() {
        val screen = read("chat/panel/ChatPanelScreen.kt")
        assertFalse(screen.contains("showVoice"))
        // The intent is unchanged - read-aloud speaks the reply directly, with no voice-mode gate in
        // front of it. The speaker is reached through the loop's own handle now, so the guard names
        // that call instead of the pre-loop one.
        assertTrue(screen.contains("if (readAloud) voice.speak(row.text)"))
    }
}
