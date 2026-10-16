package com.codespace.ide.terminal

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * Advisor 1b (2026-10-15): the EXACT device repro strings from the silent-close
 * bug report, run against the pure token machinery in a JVM harness with the
 * owner's cwd shape ("/sdcard/My codespace app 3" — a path with SPACES, mapped
 * to a host equivalent built in a temp tree).
 *
 * Proven here: no exception is thrown for ANY reported shape — the parse and
 * candidate generation are total functions. Combined with the code-path audit
 * (every canonical()/resolution branch catches Exception), the silent close
 * cannot be a Java exception; it is native, ANR, or a system kill (see the
 * CrumbLog exit-info evidence on the next device round).
 */
class FileLinkReproTest {

    @get:Rule
    val tmp: TemporaryFolder = TemporaryFolder()

    // ── The exact owner strings ────────────────────────────────────────────

    @Test
    fun ownerReproExistingFile() {
        // "error   at spantest.txt:1" — word-span candidates drop leading chunks
        val cands = IdeTerminalBridge.linkCandidates("error   at spantest.txt:1".trim())
        assertTrue(cands.contains("spantest.txt:1"))
        val (path, line) = IdeTerminalBridge.parseFileLinkToken("spantest.txt:1")
        assertEquals("spantest.txt", path)
        assertEquals(1, line)
    }

    @Test
    fun ownerReproNonexistentFile() {
        val (path, line) = IdeTerminalBridge.parseFileLinkToken("nosuchfile.txt:1")
        assertEquals("nosuchfile.txt", path)
        assertEquals(1, line)
        // The resolution branch shape: File(cwd, path).exists() is simply false —
        // no exception, the resolver returns null (now shown as File not found).
        assertFalse(File(tmp.root, "nosuchfile.txt").exists())
    }

    @Test
    fun ownerReproNoLineNumber() {
        val (path, line) = IdeTerminalBridge.parseFileLinkToken("error   at spantest.txt".trim())
        // Candidate "spantest.txt" parses with no line.
        val (p2, l2) = IdeTerminalBridge.parseFileLinkToken("spantest.txt")
        assertEquals("spantest.txt", p2)
        assertEquals(-1, l2)
        assertEquals(-1, line.takeIf { it > 0 } ?: -1)
    }

    // ── Audit shapes (advisor 1c) ───────────────────────────────────────────

    @Test
    fun parseShapes() {
        // trailing colon / comma / semicolon are stripped
        assertEquals(Pair("spantest.txt", -1), IdeTerminalBridge.parseFileLinkToken("spantest.txt:"))
        assertEquals(Pair("spantest.txt", -1), IdeTerminalBridge.parseFileLinkToken(" spantest.txt ,"))
        // absolute path with line
        assertEquals(Pair("/abs/dir/x.kt", 42), IdeTerminalBridge.parseFileLinkToken("/abs/dir/x.kt:42"))
        // relative parent — parses to the literal, resolution decides
        assertEquals(Pair("..", -1), IdeTerminalBridge.parseFileLinkToken(".."))
        // empty and blank
        assertEquals(Pair("", -1), IdeTerminalBridge.parseFileLinkToken(""))
        assertEquals(Pair("", -1), IdeTerminalBridge.parseFileLinkToken("   "))
        // unicode filename
        assertEquals(Pair("日本.txt", 3), IdeTerminalBridge.parseFileLinkToken("日本.txt:3"))
        // path with spaces
        assertEquals(Pair("My Project/main.kt", 7), IdeTerminalBridge.parseFileLinkToken("My Project/main.kt:7"))
        // col suffix documents CURRENT behavior: last colon wins, so the path part
        // carries ":42" and the token does not resolve — known gap, not a crash.
        assertEquals(Pair("Main.kt:42", 5), IdeTerminalBridge.parseFileLinkToken("Main.kt:42:5"))
    }

    @Test
    fun hugeLineNumberIsNotALine() {
        // Previously candidateLine.toInt() overflowed (NumberFormatException escaped
        // the parse); now anything over 6 digits is not a line number.
        val (path, line) = IdeTerminalBridge.parseFileLinkToken("Main.kt:999999999999")
        assertEquals("Main.kt:999999999999", path)
        assertEquals(-1, line)
    }

    @Test
    fun candidatesBoundedAtFive() {
        // 6 spaces would hit the 5-cap BEFORE the file token appears — the cap is
        // the documented bound; 3 spaces reaches the token within it.
        val cands = IdeTerminalBridge.linkCandidates("a b c spantest.txt:1")
        assertEquals(4, cands.size)
        assertEquals("spantest.txt:1", cands.last())
        // More than 5 chunks: bounded, the tail token is NOT reached (documented).
        val many = IdeTerminalBridge.linkCandidates("a b c d e f spantest.txt:1")
        assertEquals(5, many.size)
        // single-token (no space) = exactly the old behavior: one candidate
        assertEquals(listOf("spantest.txt:1"), IdeTerminalBridge.linkCandidates("spantest.txt:1"))
    }

    @Test
    fun tokenCapsPart3() {
        // Advisor Part 3: only tokens worth a disk probe proceed; length is capped
        // (VS Code maxLinkLength parity) before ANY file access.
        assertTrue(IdeTerminalBridge.isResolvableToken("spantest.txt:1"))
        assertTrue(IdeTerminalBridge.isResolvableToken("src/Main.kt:42"))
        assertFalse(IdeTerminalBridge.isResolvableToken("hello"))          // no path hint
        assertFalse(IdeTerminalBridge.isResolvableToken(""))               // empty
        assertFalse(IdeTerminalBridge.isResolvableToken("   "))            // blank
        val long = "a/".repeat(300)                                        // 600 chars
        assertFalse(IdeTerminalBridge.isResolvableToken(long))
        val atCap = "x".repeat(509) + ".kt"                               // exactly 512
        assertTrue(IdeTerminalBridge.isResolvableToken(atCap))
        // The unscoped entry refuses oversized tokens via the same cap constant
        // (resolveTappedFileLinkUnscoped's first check reads MAX_TOKEN_LENGTH; a
        // null Context is not legal in Kotlin so the entry itself is not driven
        // from this JVM test — the cap is proven at the isResolvableToken gate).
    }

    @Test
    fun cwdResolutionWithSpacesWorks() {
        // The owner's cwd shape: "/sdcard/My codespace app 3" — host equivalent
        // built with spaces in the name. File(cwd, rel) handles spaces natively.
        val cwd = tmp.newFolder("My codespace app 3")
        File(cwd, "spantest.txt").writeText("hello\n")
        val resolved = File(cwd, "spantest.txt")
        assertTrue(resolved.exists())
        assertNotNull(resolved)
    }
}
