package com.codespace.ide.chat

import com.codespace.ide.chat.panel.ChatSession
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * An attachment chip is refused when the type is unknown. Android answers "unknown" for plenty
 * of ordinary files (.md and .kt among them) because the picked provider returned no type, so
 * the name has to answer instead. These cases are the ones that used to be refused outright.
 */
class AttachmentTypingTest {

    @Test
    fun sourceAndDocFilesAreText() {
        assertEquals("text/plain", ChatSession.mimeForName("Main.kt"))
        assertEquals("text/plain", ChatSession.mimeForName("build.gradle"))
        assertEquals("text/plain", ChatSession.mimeForName("notes.txt"))
        assertEquals("text/markdown", ChatSession.mimeForName("README.md"))
    }

    @Test
    fun imagesAndPdfKeepTheirRealKinds() {
        assertEquals("image/png", ChatSession.mimeForName("shot.png"))
        assertEquals("image/jpeg", ChatSession.mimeForName("photo.JPEG"))
        assertEquals("application/pdf", ChatSession.mimeForName("paper.pdf"))
    }

    @Test
    fun anUnknownExtensionIsNotText() {
        // Guessing "text" here would ship a binary to the model as mojibake.
        assertEquals("application/octet-stream", ChatSession.mimeForName("model.gguf"))
        assertEquals("application/octet-stream", ChatSession.mimeForName("no-extension"))
    }
}
