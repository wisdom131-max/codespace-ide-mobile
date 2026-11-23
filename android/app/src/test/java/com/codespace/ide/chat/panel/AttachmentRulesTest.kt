package com.codespace.ide.chat.panel

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The two rules that were refusing readable files (owner report, 2026-11-23). Both are pure, so
 * they are pinned here rather than discovered on a phone.
 */
class AttachmentRulesTest {

    // ---- credential gate: real secrets refused, source files allowed ----

    @Test
    fun realCredentialFilesAreStillRefused() {
        listOf(
            ".env", ".env.local", "id_rsa", "id_ed25519", "server.pem", "app.keystore",
            "release.jks", "store.p12", "credentials.json", "secrets.yaml", "password.txt",
            "api_key.properties", "private.key",
        ).forEach { assertTrue("must refuse " + it, AttachmentCredentialGate.looksLikeCredential(it)) }
    }

    @Test
    fun ordinarySourceFilesThatMentionSecretsAreAllowed() {
        listOf(
            "CredentialsStore.kt", "password_rules.kt", "secret_notes.md", "SecureTokenStore.kt",
            "secrets_panel.py", "Credentials.kt", "main.kt", "notes.txt", "README.md",
        ).forEach { assertFalse("must allow " + it, AttachmentCredentialGate.looksLikeCredential(it)) }
    }

    @Test
    fun aPathIsJudgedByItsFileName() {
        assertTrue(AttachmentCredentialGate.looksLikeCredential("app/src/.env"))
        assertFalse(AttachmentCredentialGate.looksLikeCredential("src/credentials/CredentialsStore.kt"))
    }

    // ---- content sniffing: text is text whatever it is called ----

    @Test
    fun readableTextDecodes() {
        assertEquals("hello\nworld", AttachmentTextSniff.decodeOrNull("hello\nworld".toByteArray()))
        assertEquals("<svg/>", AttachmentTextSniff.decodeOrNull("<svg/>".toByteArray()))
        assertEquals("café", AttachmentTextSniff.decodeOrNull("café".toByteArray(Charsets.UTF_8)))
        assertEquals(
            "fun main() {}",
            AttachmentTextSniff.decodeOrNull("fun main() {}".toByteArray()),
        )
    }

    @Test
    fun binaryIsNotText() {
        assertNull(AttachmentTextSniff.decodeOrNull(ByteArray(0)))
        // PNG signature then a NUL: the rule `file` uses
        val png = byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 0x00, 0x1A)
        assertNull(AttachmentTextSniff.decodeOrNull(png))
        val zip = byteArrayOf(0x50, 0x4B, 0x03, 0x04, 0x00, 0x00, 0x08)
        assertNull(AttachmentTextSniff.decodeOrNull(zip))
    }

    @Test
    fun aLargeTextFileIsStillText() {
        val big = ByteArray(20_000) { 'a'.code.toByte() }
        assertEquals(20_000, AttachmentTextSniff.decodeOrNull(big)?.length)
    }
}
