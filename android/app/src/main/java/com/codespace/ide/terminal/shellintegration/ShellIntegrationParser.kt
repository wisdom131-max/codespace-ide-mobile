package com.codespace.ide.terminal.shellintegration

/**
 * D15-a: pure-Kotlin parser for VS Code shell-integration OSC 633 sequences.
 *
 * Contract (adapted from microsoft/vscode shellIntegration-bash.sh, MIT — the
 * emitter is baked into the rootfs as /etc/profile.d/99-shell-integration.sh):
 *
 *   ESC ] 633 ; <payload> ST/BEL
 *
 *   A                     prompt started
 *   B                     prompt ended
 *   C                     command output started
 *   D [;<exitCode>]       command finished (no field = empty command / no code)
 *   E ;<cmdline>[;<nonce>] command line about to execute (pre-exec)
 *   F / G                 continuation prompt start / end (multi-line command)
 *   P ;<Name>=<Value>     property broadcast (we consume Cwd; others pass through)
 *
 * Escaping rule (mirrors the emitter's __csi_escape_value): backslash → `\\`,
 * `;` → `\x3b`, control chars (< 31) → `\xNN`. The FAST path (inputs ≥ 2000
 * chars) escapes ONLY backslashes and `;`, so raw control characters are legal
 * in the stream and pass through unchanged.
 *
 * Design rules (approved D15 architecture):
 *   - ZERO Android imports: the full test matrix runs host-side as JVM tests.
 *   - The vendored TerminalEmulator does a dumb 10-line forward of the raw
 *     payload; ALL grammar work lives here.
 *   - This parser NEVER throws: any malformed input yields
 *     [ShellIntegrationEvent.Unknown] so the state layer can log it and move
 *     on. A broken guest script must never crash the emulator reader thread.
 *   - Nonce VALIDATION is NOT done here (the parser does not know the
 *     session's expected nonce) — ShellIntegrationState compares the E event's
 *     nonce against the per-session nonce injected via
 *     [ShellIntegrationState.NONCE_ENV] and flags mismatches unvalidated.
 */
object ShellIntegrationParser {

    private fun isHexDigit(c: Char): Boolean = (c in '0'..'9') || (c in 'a'..'f') || (c in 'A'..'F')

    /** Split the payload into fields on UNESCAPED `;`, preserving `\xNN` and `\\` verbatim. */
    internal fun splitFields(payload: String): List<String> {
        val fields = ArrayList<String>()
        val current = StringBuilder()
        var i = 0
        while (i < payload.length) {
            val c = payload[i]
            if (c == ';') {
                fields.add(current.toString())
                current.setLength(0)
                i++
            } else if (c == '\\' && i + 1 < payload.length && payload[i + 1] == 'x' && i + 3 < payload.length &&
                isHexDigit(payload[i + 2]) && isHexDigit(payload[i + 3])
            ) {
                // \xNN escape — cannot contain a field separator; copy 4 chars verbatim.
                // The hex-digit check matters for FAIL-CLOSED parsing: a malformed
                // "\x3;" must NOT swallow the ';' as if it were part of an escape —
                // that would merge two fields and let a spoofed payload smuggle a
                // nonce into the wrong slot. Malformed escapes fall through to the
                // literal branch and the field boundary survives.
                current.append(payload, i, i + 4)
                i += 4
            } else if (c == '\\' && i + 1 < payload.length && payload[i + 1] == '\\') {
                // escaped backslash — copy verbatim
                current.append("\\\\")
                i += 2
            } else {
                current.append(c)
                i++
            }
        }
        fields.add(current.toString())
        return fields
    }

    /**
     * Unescape one field per the emitter's contract: `\xNN` → codepoint,
     * `\\` → `\`. Anything unexpected is copied literally (fail-open on
     * malformed escapes; the fast path's raw control chars just pass through).
     */
    internal fun unescape(field: String): String {
        val sb = StringBuilder()
        var i = 0
        while (i < field.length) {
            val c = field[i]
            if (c == '\\' && i + 3 < field.length && field[i + 1] == 'x') {
                val hex = field.substring(i + 2, i + 4)
                val v = hex.toIntOrNull(16)
                if (v != null) {
                    sb.append(v.toChar())
                    i += 4
                } else {
                    sb.append(c)
                    i++
                }
            } else if (c == '\\' && i + 1 < field.length && field[i + 1] == '\\') {
                sb.append('\\')
                i += 2
            } else {
                sb.append(c)
                i++
            }
        }
        return sb.toString()
    }

    /** Parse a raw OSC 633 payload (everything after `633;`). Never throws. */
    fun parse(payload: String): ShellIntegrationEvent {
        if (payload.isEmpty()) return ShellIntegrationEvent.Unknown("", "")
        val fields = splitFields(payload)
        if (fields.isEmpty()) return ShellIntegrationEvent.Unknown("", payload)
        return when (fields[0]) {
            "A" -> ShellIntegrationEvent.PromptStart(payload)
            "B" -> ShellIntegrationEvent.PromptEnd(payload)
            "C" -> ShellIntegrationEvent.CommandOutputStart(payload)
            "D" -> ShellIntegrationEvent.CommandComplete(
                // Exit code field is never escaped (numeric); missing field = no code.
                fields.getOrNull(1)?.let { f -> f.toIntOrNull() },
                payload,
            )
            "E" -> ShellIntegrationEvent.CommandLine(
                command = unescape(fields.getOrNull(1) ?: ""),
                // The emitter always appends the nonce field, but an empty nonce
                // ("" when CODESPACE_SHELL_NONCE was absent) is distinct from absent.
                nonce = if (fields.size > 2) fields[2] else null,
                raw = payload,
            )
            "F" -> ShellIntegrationEvent.ContinuationStart(payload)
            "G" -> ShellIntegrationEvent.ContinuationEnd(payload)
            "P" -> {
                val kv = fields.getOrNull(1) ?: ""
                val eq = kv.indexOf('=')
                if (eq <= 0) {
                    ShellIntegrationEvent.Unknown("P", payload)
                } else {
                    ShellIntegrationEvent.Property(
                        name = kv.substring(0, eq),
                        value = unescape(kv.substring(eq + 1)),
                        raw = payload,
                    )
                }
            }
            else -> ShellIntegrationEvent.Unknown(fields[0], payload)
        }
    }
}

/** Parsed OSC 633 events. Every variant carries the raw payload for diagnostics. */
sealed interface ShellIntegrationEvent {
    /** Full raw payload, for debug logging and failure forensics. */
    val raw: String

    data class PromptStart(override val raw: String) : ShellIntegrationEvent
    data class PromptEnd(override val raw: String) : ShellIntegrationEvent
    data class CommandOutputStart(override val raw: String) : ShellIntegrationEvent
    /** [exitCode] is null for a bare `D` (empty command / no code captured). */
    data class CommandComplete(val exitCode: Int?, override val raw: String) : ShellIntegrationEvent
    /** [nonce] is null when the field is absent (spoofed/foreign emitter). */
    data class CommandLine(val command: String, val nonce: String?, override val raw: String) : ShellIntegrationEvent
    data class ContinuationStart(override val raw: String) : ShellIntegrationEvent
    data class ContinuationEnd(override val raw: String) : ShellIntegrationEvent
    data class Property(val name: String, val value: String, override val raw: String) : ShellIntegrationEvent
    /** Unknown subcommand or malformed payload — logged, never fatal. */
    data class Unknown(val subcommand: String, override val raw: String) : ShellIntegrationEvent
}
