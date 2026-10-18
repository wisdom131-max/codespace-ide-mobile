package com.codespace.ide.debug

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.io.File

/**
 * BATCH6-P2c (advisor 2c): the shared frame-file normalizer, tested per
 * LANGUAGE SHAPE — the four forms DAP adapters actually send:
 *  - Python (debugpy):  absolute guest source.path -> passthrough;
 *  - Java (jdap/JDWP):  BARE source name -> resolve against the debuggee's
 *                        own launched dir/ancestors/project root, ambiguity = null;
 *  - Node (js-debug):   absolute path, or source.name fallback;
 *  - any adapter:       sourceReference-ONLY frame (no source.path/name) ->
 *                        normalize input is empty -> null (needs a DAP `source`
 *                        request, proposed separately; never guessed).
 */
class DebugFrameResolverNormalizeTest {

    private fun existsNow(): (String) -> Boolean = { File(it).exists() }

    private fun withTempTree(block: (String, String) -> Unit) {
        val root = File(System.getProperty("java.io.tmpdir"),
            "dfr-norm-" + System.nanoTime())
        val proj = File(root, "proj")
        assert(proj.mkdirs())
        File(proj, "src").apply { mkdirs() }
        File(proj, "Main.py").apply { writeText("x = 1\n") }
        File(proj, "DebugJava.java").apply { writeText("class X {}\n") }
        File(proj, "Main.java").apply { writeText("class Main {}\n") }
        try {
            block(root.absolutePath, proj.absolutePath)
        } finally {
            root.deleteRecursively()
        }
    }

    @Test fun pythonShape_absolutePath_isPassedThroughAsIs() {
        // debugpy sends source.path absolute (guest dialect); the normalizer's
        // job is passthrough — host translation is the caller's canonicalHostKey.
        assertEquals("/root/proj/Main.py",
            DebugFrameResolver.resolveFrameFile("/root/proj/Main.py", "/anything/launched.py"))
    }

    @Test fun javaShape_bareName_resolvesAgainstLaunchedDir() {
        withTempTree { _, proj ->
            val launched = "$proj/Main.java"
            val r = DebugFrameResolver.resolveFrameFile("DebugJava.java", launched, listOf(proj), existsNow())
            assertEquals("$proj/DebugJava.java", r)
        }
    }

    @Test fun javaShape_bareNameInAncestorDir_resolves() {
        withTempTree { root, proj ->
            // launched DEEP inside the project; the bare frame name lives at the
            // project root — the bounded ancestor walk must find it.
            val nested = File(proj, "src"); assert(nested.isDirectory)
            val launched = "$proj/src/Main.java"
            val r = DebugFrameResolver.resolveFrameFile("Main.py", launched, listOf(), existsNow())
            assertEquals("$proj/Main.py", r)
        }
    }

    @Test fun javaShape_ambiguousBareName_isNull_noBand() {
        withTempTree { _, proj ->
            // TWO existing files with the same bare name (project root + one
            // depth-1 root) -> ambiguity = null, never a wrong-file band.
            val other = File(proj, "other"); assert(other.mkdirs())
            File(other, "Main.py").apply { writeText("y = 2\n") }
            val r = DebugFrameResolver.resolveFrameFile("Main.py", "$proj/Main.java", listOf(proj, other.absolutePath), existsNow())
            assertNull(r)
        }
    }

    @Test fun relativePath_resolvesAgainstLaunchedDirAndRoot() {
        withTempTree { _, proj ->
            // e.g. a workspace-relative "src/Main.java" frame form.
            val r = DebugFrameResolver.resolveFrameFile("Main.py", "$proj/Main.java", listOf(), existsNow())
            assertEquals("$proj/Main.py", r)
            // a genuinely relative path that exists only via the root hint
            val r2 = DebugFrameResolver.resolveFrameFile("src/Main.java", "$proj/Main.java", listOf(), existsNow())
            assertEquals("$proj/src/Main.java", r2)
        }
    }

    @Test fun relativePath_missing_isNull() {
        withTempTree { _, proj ->
            assertNull(DebugFrameResolver.resolveFrameFile("nope/DoesNotExist.kt", "$proj/Main.java", listOf(proj), existsNow()))
        }
    }

    @Test fun sourceReferenceOnly_blankInput_isNull() {
        // A frame with ONLY sourceReference (no source.path, no source.name)
        // reaches the normalizer as "" -> null: NEVER guess a file identity.
        assertNull(DebugFrameResolver.resolveFrameFile("", "/proj/Main.java"))
        assertNull(DebugFrameResolver.resolveFrameFile("   ", "/proj/Main.java"))
    }

    @Test fun noLaunchedPath_relativeAndBare_isNull() {
        // No debuggee context (should not happen with an active session) ->
        // fail closed, no band.
        assertNull(DebugFrameResolver.resolveFrameFile("Main.java", ""))
        assertNull(DebugFrameResolver.resolveFrameFile("src/Main.java", ""))
    }
}
