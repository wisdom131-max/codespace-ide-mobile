package com.codespace.ide.chat.panel

/**
 * OWNER REPORT (2026-11-23): "my attachment refused some files". Two rules were doing the refusing
 * and both were guessing from a NAME, which is the weakest evidence available. They live here as
 * pure functions so they can be tested without a device, and so the readers have one place to ask.
 *
 * Reference behaviour (VS Code, chatAttachmentResolveService.ts): it refuses exactly one thing, an
 * image too large to attach; every other file is read as text and passed on. These rules are
 * deliberately narrower than they were, so the app stops refusing files that are plainly readable.
 */

/**
 * True when a file's NAME alone means it must never be sent to a provider.
 *
 * The old rule refused any name CONTAINING "credentials", "secret" or "password", which also
 * refused ordinary source files - `CredentialsStore.kt`, `password_rules.kt`, `secret_notes.md`.
 * That is a false refusal, and it is exactly the kind the owner hit. A name that merely mentions
 * credentials is a source file; it is a credential STORE only when the name or the extension says
 * so. Real secret files are still refused:
 *
 *  - exact stores: .env / .env.* / .npmrc / .netrc / id_rsa / id_ed25519 / id_dsa / id_ecdsa
 *  - key + store extensions: .pem .p12 .pfx .keystore .jks .key
 *  - a mentioning name that is a DATA file: credentials.json, secrets.yaml, password.txt, ...
 */
internal object AttachmentCredentialGate {

    private val EXACT_NAMES = setOf(
        ".env", ".npmrc", ".netrc", "id_rsa", "id_ed25519", "id_dsa", "id_ecdsa",
    )

    private val SECRET_EXTENSIONS = setOf("pem", "p12", "pfx", "keystore", "jks", "key")

    /** Data shapes a secret actually lives in. A .kt or .md that mentions secrets is not one. */
    private val DATA_EXTENSIONS = setOf(
        "json", "yaml", "yml", "txt", "properties", "ini", "conf", "env", "xml", "toml", "csv", "log",
    )

    fun looksLikeCredential(name: String): Boolean {
        val base = name.trim().lowercase().substringAfterLast('/')
        if (base.isEmpty()) return false
        if (base in EXACT_NAMES || base.startsWith(".env.")) return true
        val ext = base.substringAfterLast('.', "")
        if (ext in SECRET_EXTENSIONS) return true
        val mentionsSecret = base.contains("credential") || base.contains("secret") ||
            base.contains("password") || base.contains("apikey") || base.contains("api_key")
        return mentionsSecret && ext in DATA_EXTENSIONS
    }
}

/**
 * Returns the bytes as text when they really are text, or null when they are not.
 *
 * A NUL byte in the first 8 KB means binary, the same rule `file` uses. Anything that decodes
 * without a shower of replacement characters is text the owner can meaningfully attach. This is
 * what lets a .svg, a .dart file, a Makefile or an extensionless README through: the extension
 * lists always lag behind, and the owner reported files being refused that were perfectly readable.
 */
internal object AttachmentTextSniff {

    private const val PROBE_BYTES = 8192

    fun decodeOrNull(bytes: ByteArray): String? {
        if (bytes.isEmpty()) return null
        val probe = if (bytes.size > PROBE_BYTES) bytes.copyOfRange(0, PROBE_BYTES) else bytes
        if (probe.any { it == 0.toByte() }) return null
        val text = String(bytes, Charsets.UTF_8)
        val bad = text.count { it == '\uFFFD' }
        return if (bad * 100 > maxOf(1, text.length)) null else text
    }
}
