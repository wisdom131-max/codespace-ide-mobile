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
        // The dictation trigger must survive the removal.
        assertTrue(header.contains("HeaderChip(\"Mic\""))
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
        assertTrue(screen.contains("if (readAloud) speech.speak(row.text)"))
    }
}
