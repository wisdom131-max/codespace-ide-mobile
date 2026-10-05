package com.codespace.ide.terminal.shellintegration

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * D15-a: JVM test matrix for the OSC 633 shell-integration parser.
 *
 * The parser is pure Kotlin (zero Android imports) precisely so this whole
 * matrix runs host-side in CI. Two layers are tested:
 *
 *   1. PARSER — every mark type, the escape contract (backslash doubling,
 *      \x3b for ';', \xNN for control chars, fast-path ≥2000 chars), malformed
 *      inputs (truncated, unknown subcommand, non-numeric exit code, missing
 *      nonce field), and the field splitter (an escaped ';' inside a command
 *      line must NOT split the field — `echo 'x;y'` arrives as ONE E payload).
 *
 *   2. EMITTER CONTRACT — escapeValueSlow/escapeValueFast are host-side
 *      mirrors of the rootfs emitter's __csi_escape_value / _fast (adapted
 *      verbatim from VS Code's shellIntegration-bash.sh). Round-trip vectors
 *      built with the mirrors must unescape back to the original input, so a
 *      drift on either side fails here instead of on the device.
 *
 * What is deliberately NOT here: ShellIntegrationState nonce VALIDATION and
 * per-session isolation. State is Android-bound (TerminalSession listener,
 * AppOutputLog) and is covered by device gate A in AGENTS.md — including the
 * wrong-nonce spoof and two-tab isolation steps.
 */
class ShellIntegrationParserTest {

    // ── emitter mirrors (adapted verbatim from __csi_escape_value / _fast) ──

    private fun escapeValueFast(input: String): String {
        var out = input.replace("\\", "\\\\")
        out = out.replace(";", "\\x3b")
        return out
    }

    private fun escapeValueSlow(input: String): String {
        val sb = StringBuilder()
        for (b in input) {
            val v = b.code
            when {
                v < 31 -> sb.append(String.format("\\x%02x", v))
                v == 92 -> sb.append("\\\\")
                v == 59 -> sb.append("\\x3b")
                else -> sb.append(b)
            }
        }
        return sb.toString()
    }

    // ── 1. mark-type parsing ─────────────────────────────────────────────

    @Test
    fun `all simple marks parse`() {
        assertTrue(ShellIntegrationParser.parse("A") is ShellIntegrationEvent.PromptStart)
        assertTrue(ShellIntegrationParser.parse("B") is ShellIntegrationEvent.PromptEnd)
        assertTrue(ShellIntegrationParser.parse("C") is ShellIntegrationEvent.CommandOutputStart)
        assertTrue(ShellIntegrationParser.parse("F") is ShellIntegrationEvent.ContinuationStart)
        assertTrue(ShellIntegrationParser.parse("G") is ShellIntegrationEvent.ContinuationEnd)
    }

    @Test
    fun `D with exit code parses`() {
        val e = ShellIntegrationParser.parse("D;0") as ShellIntegrationEvent.CommandComplete
        assertEquals(0, e.exitCode)
        val e2 = ShellIntegrationParser.parse("D;127") as ShellIntegrationEvent.CommandComplete
        assertEquals(127, e2.exitCode)
    }

    @Test
    fun `bare D yields null exit code`() {
        val e = ShellIntegrationParser.parse("D") as ShellIntegrationEvent.CommandComplete
        assertNull(e.exitCode)
    }

    @Test
    fun `E parses command and nonce`() {
        val e = ShellIntegrationParser.parse("E;echo hello;abc123") as ShellIntegrationEvent.CommandLine
        assertEquals("echo hello", e.command)
        assertEquals("abc123", e.nonce)
    }

    @Test
    fun `E with EMPTY nonce field parses as empty-string nonce`() {
        // The emitter always emits the nonce field; an empty CODESPACE_SHELL_NONCE
        // (injection skipped) arrives as a trailing empty field — distinct from absent.
        val e = ShellIntegrationParser.parse("E;ls;") as ShellIntegrationEvent.CommandLine
        assertEquals("ls", e.command)
        assertEquals("", e.nonce)
    }

    @Test
    fun `E with NO nonce field parses as null nonce`() {
        // A foreign/spoofed emitter that omits the field entirely.
        val e = ShellIntegrationParser.parse("E;ls") as ShellIntegrationEvent.CommandLine
        assertEquals("ls", e.command)
        assertNull(e.nonce)
    }

    @Test
    fun `P Cwd property parses`() {
        val e = ShellIntegrationParser.parse("P;Cwd=/root/project") as ShellIntegrationEvent.Property
        assertEquals("Cwd", e.name)
        assertEquals("/root/project", e.value)
    }

    @Test
    fun `unknown properties still parse as Property`() {
        // D15-b/c may restore PromptType/HasRichCommandDetection from the VS Code
        // source; until then they must parse (not Unknown) so they are loggable.
        val e = ShellIntegrationParser.parse("P;PromptType=starship") as ShellIntegrationEvent.Property
        assertEquals("PromptType", e.name)
        assertEquals("starship", e.value)
    }

    // ── 2. escape contract ────────────────────────────────────────────────

    @Test
    fun `E command with escaped semicolon stays one field`() {
        // `echo 'x;y'` → emitter escapes ; → \x3b. Naive splitting would
        // tear the payload into E;echo 'x / y' / <junk nonce>.
        val payload = "E;" + escapeValueSlow("echo 'x;y'") + ";abc123"
        val e = ShellIntegrationParser.parse(payload) as ShellIntegrationEvent.CommandLine
        assertEquals("echo 'x;y'", e.command)
        assertEquals("abc123", e.nonce)
    }

    @Test
    fun `E command with backslashes round-trips`() {
        val cmd = "echo C:\\path\\to > out"
        val payload = "E;" + escapeValueSlow(cmd) + ";n"
        val e = ShellIntegrationParser.parse(payload) as ShellIntegrationEvent.CommandLine
        assertEquals(cmd, e.command)
    }

    @Test
    fun `control characters round-trip via slow path`() {
        val cmd = "printf 'a\u0001b\u0002c'"
        val payload = "E;" + escapeValueSlow(cmd) + ";n"
        val e = ShellIntegrationParser.parse(payload) as ShellIntegrationEvent.CommandLine
        assertEquals(cmd, e.command)
    }

    @Test
    fun `multibyte UTF-8 command round-trips`() {
        val cmd = "echo 'héllo wörld — 日本語'"
        val payload = "E;" + escapeValueSlow(cmd) + ";n"
        val e = ShellIntegrationParser.parse(payload) as ShellIntegrationEvent.CommandLine
        assertEquals(cmd, e.command)
    }

    @Test
    fun `fast path (>=2000 chars) round-trips`() {
        // The emitter switches to the fast escape at 2000+ chars: ONLY backslash
        // and ';' are escaped there; raw control chars are legal and pass through.
        val cmd = "x".repeat(1998) + ";\\"
        assertTrue(cmd.length >= 2000)
        val payload = "E;" + escapeValueFast(cmd) + ";n"
        val e = ShellIntegrationParser.parse(payload) as ShellIntegrationEvent.CommandLine
        assertEquals(cmd, e.command)
    }

    @Test
    fun `fast path with raw control chars passes through`() {
        val cmd = "y".repeat(2000) + "\u0007"
        val payload = "E;" + escapeValueFast(cmd) + ";n"
        val e = ShellIntegrationParser.parse(payload) as ShellIntegrationEvent.CommandLine
        assertEquals(cmd, e.command)
    }

    @Test
    fun `Cwd with escaped semicolon and spaces parses`() {
        val cwd = "/mnt/sdcard/my;dir with spaces"
        val payload = "P;Cwd=" + escapeValueSlow(cwd)
        val e = ShellIntegrationParser.parse(payload) as ShellIntegrationEvent.Property
        assertEquals("Cwd", e.name)
        assertEquals(cwd, e.value)
    }

    @Test
    fun `full mark sequence round-trips a realistic cycle`() {
        // One command lifecycle as the emitter produces it for `echo hi; exit 3`.
        val a = ShellIntegrationParser.parse("A")
        assertTrue(a is ShellIntegrationEvent.PromptStart)
        val e = ShellIntegrationParser.parse("E;" + escapeValueSlow("echo hi") + ";deadbeef")
        assertEquals("echo hi", (e as ShellIntegrationEvent.CommandLine).command)
        assertEquals("deadbeef", e.nonce)
        val c = ShellIntegrationParser.parse("C")
        assertTrue(c is ShellIntegrationEvent.CommandOutputStart)
        val d = ShellIntegrationParser.parse("D;0")
        assertEquals(0, (d as ShellIntegrationEvent.CommandComplete).exitCode)
        val cwd = ShellIntegrationParser.parse("P;Cwd=/root")
        assertEquals("/root", (cwd as ShellIntegrationEvent.Property).value)
        val b = ShellIntegrationParser.parse("B")
        assertTrue(b is ShellIntegrationEvent.PromptEnd)
    }

    // ── 3. malformed inputs — never throw, always Unknown-or-lenient ────

    @Test
    fun `unknown subcommand yields Unknown`() {
        val e = ShellIntegrationParser.parse("Z;whatever")
        assertTrue(e is ShellIntegrationEvent.Unknown)
        assertEquals("Z", (e as ShellIntegrationEvent.Unknown).subcommand)
    }

    @Test
    fun `empty payload yields Unknown`() {
        val e = ShellIntegrationParser.parse("")
        assertTrue(e is ShellIntegrationEvent.Unknown)
    }

    @Test
    fun `non-numeric exit code yields null not a throw`() {
        val e = ShellIntegrationParser.parse("D;not-a-code") as ShellIntegrationEvent.CommandComplete
        assertNull(e.exitCode)
    }

    @Test
    fun `truncated escape sequence does not throw`() {
        // A payload cut mid-escape (\x at the very end of a field).
        val e = ShellIntegrationParser.parse("E;cmd\\x")
        assertTrue(e is ShellIntegrationEvent.CommandLine)
    }

    @Test
    fun `malformed hex escape passes through literally`() {
        // \xZZ is not a hex escape — copy the backslash, keep going (fail-open).
        val e = ShellIntegrationParser.parse("E;a\\xZZb") as ShellIntegrationEvent.CommandLine
        assertEquals("a\\xZZb", e.command)
    }

    @Test
    fun `P without equals sign yields Unknown`() {
        val e = ShellIntegrationParser.parse("P;NoEqualsHere")
        assertTrue(e is ShellIntegrationEvent.Unknown)
    }

    @Test
    fun `malformed escape must not swallow a field boundary`() {
        // "\x3;" — a 1-hex-digit "escape" directly before a real separator. The
        // splitter must NOT consume the ';' as the second hex digit position of a
        // 4-char escape: that would merge "command" and "nonce" fields and let a
        // spoofed payload smuggle data across the boundary. The malformed escape
        // passes through literally and the boundary survives: fields = [E, a\x3, b].
        val fields = ShellIntegrationParser.splitFields("E;a\\x3;b")
        assertEquals(listOf("E", "a\\x3", "b"), fields)
        val e = ShellIntegrationParser.parse("E;a\\x3;b") as ShellIntegrationEvent.CommandLine
        assertEquals("a\\x3", e.command)
        assertEquals("b", e.nonce)
    }

    // ── 4. field splitter unit vectors ────────────────────────────────────

    @Test
    fun `splitter keeps escaped semicolons inside fields`() {
        val fields = ShellIntegrationParser.splitFields("E;a\\x3bb;c")
        assertEquals(listOf("E", "a\\x3bb", "c"), fields)
    }

    @Test
    fun `splitter keeps escaped backslashes inside fields`() {
        val fields = ShellIntegrationParser.splitFields("E;a\\\\b;c")
        assertEquals(listOf("E", "a\\\\b", "c"), fields)
    }

    @Test
    fun `splitter handles trailing empty field`() {
        // E;cmd; with an EMPTY nonce: fields must be E / cmd / "" (3 entries).
        val fields = ShellIntegrationParser.splitFields("E;cmd;")
        assertEquals(listOf("E", "cmd", ""), fields)
    }

    @Test
    fun `splitter on mark with no fields`() {
        val fields = ShellIntegrationParser.splitFields("A")
        assertEquals(listOf("A"), fields)
    }
}
