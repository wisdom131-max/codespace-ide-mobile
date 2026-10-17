package com.codespace.ide.debug

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.io.File

/**
 * BATCH6-P2 (advisor C): the bare-frame-name resolver — JVM tests with REAL
 * temp directories (the exists seam is the default File.exists).
 *
 * Advisor cases: bare name with launch context; two same-named files; package
 * folders. Plus the no-slash gate and the roots path.
 */
class DebugFrameResolverTest {

    private fun dir(name: String): File {
        val f = File(System.getProperty("java.io.tmpdir"), "dfr-test-" + name + "-" + System.nanoTime())
        assert(f.mkdirs())
        return f
    }

    private fun touch(parent: File, name: String): File {
        val f = File(parent, name)
        assert(f.createNewFile()) { "could not create $f" }
        return f
    }

    @Test fun bareName_launchContext_resolves() {
        val d = dir("same-dir")
        val launched = touch(d, "Main.java")
        val target = touch(d, "Debug.java")
        assertEquals(
            target.path,
            DebugFrameResolver.resolveBareFrameFile("Debug.java", launched.path),
        )
    }

    @Test fun packageFolders_resolvesViaAncestorWalk() {
        // launched in /src/com/x, frame file in the SAME package
        val root = dir("pkg")
        val pkg = File(root, "src/com/x")
        assert(pkg.mkdirs())
        val launched = touch(pkg, "App.java")
        val helper = touch(pkg, "Helper.java")
        assertEquals(
            helper.path,
            DebugFrameResolver.resolveBareFrameFile("Helper.java", launched.path),
        )
    }

    @Test fun twoSameNamedFiles_noBandNotTheWrongOne() {
        // Helper.java exists BOTH in the launched package AND one folder up:
        // ambiguity must return null (no band), never guess.
        val root = dir("ambig")
        val pkg = File(root, "src/com/x")
        assert(pkg.mkdirs())
        val launched = touch(pkg, "App.java")
        touch(pkg, "Helper.java")
        touch(File(root, "src/com"), "Helper.java")
        assertNull(DebugFrameResolver.resolveBareFrameFile("Helper.java", launched.path))
    }

    @Test fun sourceRoot_candidateViaRootsParam() {
        // The frame file lives in the project root, which is NOT an ancestor
        // of the launched file's folder — only the roots param finds it.
        val root = dir("roots")
        val launchedDir = File(root, "app")
        assert(launchedDir.mkdirs())
        val launched = touch(launchedDir, "Main.java")
        val target = touch(root, "Util.java")
        assertEquals(
            target.path,
            DebugFrameResolver.resolveBareFrameFile("Util.java", launched.path, roots = listOf(root.path)),
        )
    }

    @Test fun notFound_returnsNull() {
        val d = dir("missing")
        val launched = touch(d, "Main.java")
        assertNull(DebugFrameResolver.resolveBareFrameFile("Nope.java", launched.path))
    }

    @Test fun nameWithSlash_isNotBare_returnsNull() {
        val d = dir("slash")
        val launched = touch(d, "Main.java")
        touch(d, "Other.java")
        assertNull(DebugFrameResolver.resolveBareFrameFile("sub/Other.java", launched.path))
    }

    @Test fun blankName_returnsNull() {
        val d = dir("blank")
        val launched = touch(d, "Main.java")
        assertNull(DebugFrameResolver.resolveBareFrameFile("", launched.path))
        assertNull(DebugFrameResolver.resolveBareFrameFile(" ", launched.path))
    }
}
