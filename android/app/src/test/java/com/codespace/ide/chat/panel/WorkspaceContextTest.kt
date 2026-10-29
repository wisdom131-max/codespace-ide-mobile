package com.codespace.ide.chat.panel

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File

/**
 * Batch 1a — workspace intelligence, pure layer.
 *
 * What these assertions protect:
 *  - the model is told WHERE it is (every root, the active one marked) and that a
 *    listing is not file contents, so it cannot describe a file it never read;
 *  - a credential-shaped file is WITHHELD and says so, rather than looking empty;
 *  - empty and unreadable are different facts;
 *  - the tree is bounded (depth, entry count) and never descends into build or VCS dirs;
 *  - the block stays inside its character budget.
 */
class WorkspaceContextTest {

    private lateinit var root: File

    @Before
    fun setUp() {
        root = File.createTempFile("ws-ctx", "").let { f ->
            f.delete()
            f.mkdirs()
            f
        }
    }

    @After
    fun tearDown() {
        root.deleteRecursively()
    }

    private fun snap(
        activeRoot: String? = root.absolutePath,
        roots: List<String> = listOf(root.absolutePath),
        currentFile: String? = null,
        text: String? = null,
        withheld: Boolean = false,
        openFiles: List<String> = emptyList(),
        instructions: String = "",
    ) = WorkspaceSnapshot(
        projectId = "p1",
        activeRoot = activeRoot,
        roots = roots,
        currentFile = currentFile,
        currentFileText = text,
        currentFileWithheld = withheld,
        openFiles = openFiles,
        instructionsBlock = instructions,
    )

    @Test
    fun everyRootIsListedAndTheActiveOneIsMarked() {
        val other = File(root, "second-root").apply { mkdirs() }
        val out = WorkspaceContext.build(
            snap(roots = listOf(root.absolutePath, other.absolutePath))
        )
        assertTrue(out.contains("## WORKSPACE CONTEXT"))
        assertTrue(out.contains(root.absolutePath + " [ACTIVE]"))
        assertTrue(out.contains(other.absolutePath))
        // The non-active root must NOT be marked as active.
        assertFalse(out.contains(other.absolutePath + " [ACTIVE]"))
    }

    @Test
    fun anUnresolvedWorkspaceSaysSoInsteadOfInventingAPath() {
        // A file is open from outside any resolved root: this is the case that must be
        // explicit, because the model would otherwise guess a directory.
        val out = WorkspaceContext.build(
            snap(activeRoot = null, roots = emptyList(), currentFile = "/elsewhere/Loose.kt", text = null)
        )
        assertTrue(out.contains("UNRESOLVED"))
        assertTrue(out.contains("Do not invent a path"))
        assertFalse("must not claim a project", out.contains("Project:"))
    }

    @Test
    fun noWorkspaceAndNoFileBuildsNothingAtAll() {
        val out = WorkspaceContext.build(snap(activeRoot = null, roots = emptyList(), currentFile = null))
        assertEquals("", out)
    }

    @Test
    fun theBlockStatesThatListingsAreNotFileContents() {
        File(root, "a.kt").writeText("fun main() {}")
        val out = WorkspaceContext.build(snap())
        assertTrue(out.contains("LISTING, not file contents"))
        assertTrue(out.contains("Never claim to have read or changed a file"))
    }

    @Test
    fun projectTypeIsDetectedFromMarkerFiles() {
        File(root, "build.gradle.kts").writeText("plugins {}")
        assertTrue(WorkspaceContext.build(snap()).contains("Type: Android/Gradle"))

        File(root, "build.gradle.kts").delete()
        File(root, "go.mod").writeText("module x")
        assertTrue(WorkspaceContext.build(snap()).contains("Type: Go"))
    }

    @Test
    fun theCurrentFileIsRelativeAndCarriesImportsButNeverItsBody() {
        val f = File(root, "src").apply { mkdirs() }.resolve("Main.kt")
        f.writeText(
            "package demo\n" +
                "import kotlinx.coroutines.launch\n" +
                "import java.io.File\n" +
                "fun main() { println(\"SECRET BODY LINE\") }\n"
        )
        val out = WorkspaceContext.build(snap(currentFile = f.absolutePath, text = f.readText()))
        assertTrue(out.contains("Current file: src/Main.kt"))
        assertTrue(out.contains("Language: Kotlin"))
        assertTrue(out.contains("kotlinx.coroutines.launch"))
        assertTrue(out.contains("java.io.File"))
        assertFalse("the body must not be inlined", out.contains("SECRET BODY LINE"))
    }

    @Test
    fun anEmptyFileIsReportedAsEmptyAndAnUnreadOneAsNotRead() {
        val f = File(root, "Empty.kt").apply { writeText("") }
        val empty = WorkspaceContext.build(snap(currentFile = f.absolutePath, text = ""))
        assertTrue(empty.contains("(empty file)"))
        assertFalse(empty.contains("(not read)"))

        val unread = WorkspaceContext.build(snap(currentFile = f.absolutePath, text = null))
        assertTrue(unread.contains("(not read)"))
        assertFalse(unread.contains("(empty file)"))
    }

    @Test
    fun aCredentialShapedCurrentFileIsWithheldAndSaysWhy() {
        val f = File(root, ".env").apply { writeText("API_KEY=sk-live-123") }
        val out = WorkspaceContext.build(
            snap(currentFile = f.absolutePath, text = null, withheld = true)
        )
        assertTrue(out.contains("withheld: credential-shaped file"))
        assertFalse("no secret material may appear", out.contains("sk-live-123"))
    }

    @Test
    fun sensitiveNamesAreRecognisedButOrdinaryFilesAreNot() {
        assertTrue(WorkspaceContext.isSensitiveName(".env"))
        assertTrue(WorkspaceContext.isSensitiveName(".env.local"))
        assertTrue(WorkspaceContext.isSensitiveName("id_rsa"))
        assertTrue(WorkspaceContext.isSensitiveName("server.pem"))
        assertTrue(WorkspaceContext.isSensitiveName("release.keystore"))
        assertTrue(WorkspaceContext.isSensitiveName("secrets.txt"))
        assertFalse(WorkspaceContext.isSensitiveName("Main.kt"))
        assertFalse(WorkspaceContext.isSensitiveName("environment.md"))
        assertFalse(WorkspaceContext.isSensitiveName("keeper.kt"))
    }

    @Test
    fun theTreeIsBoundedByDepthAndEntryCountAndSkipsBuildFolders() {
        // Two real source dirs, one build dir that must never appear, and a deep chain.
        File(root, "src/main").mkdirs()
        File(root, "src/main/App.kt").writeText("class App")
        File(root, "node_modules/junk").mkdirs()
        File(root, "node_modules/junk/index.js").writeText("x")
        File(root, "build").mkdirs()
        File(root, "build/out.bin").writeText("x")
        File(root, ".git/config").mkdirs()
        File(root, ".env").writeText("X=1")

        val out = WorkspaceContext.build(snap())
        assertTrue(out.contains("src/"))
        assertTrue(out.contains("App.kt"))
        assertFalse("dependency trees must not be walked", out.contains("node_modules"))
        assertFalse("build output must not appear", out.contains("out.bin"))
        assertFalse("VCS metadata must not appear", out.contains(".git"))
        assertFalse("credential names must not be advertised", out.contains(".env"))
    }

    @Test
    fun theTreeStopsAtTheEntryCapInsteadOfDumpingTheProject() {
        repeat(120) { i -> File(root, "file$i.kt").writeText("x") }
        val tree = WorkspaceContext.buildTree(root)
        val lines = tree.lines().filter { it.isNotBlank() }
        assertTrue("capped at $WorkspaceContext.MAX_TREE_FILES", lines.size <= WorkspaceContext.MAX_TREE_FILES)
    }

    @Test
    fun deeplyNestedFilesAreNotListedBeyondTheDepthCap() {
        val deep = File(root, "a/b/c/d/e").apply { mkdirs() }
        File(deep, "TooDeep.kt").writeText("x")
        val tree = WorkspaceContext.buildTree(root)
        assertFalse(tree.contains("TooDeep.kt"))
    }

    @Test
    fun importsAreParsedPerLanguageAndDeduplicated() {
        val py = WorkspaceContext.importsOf(
            "from os import path\nimport sys\nfrom os import path\n", "m.py"
        )
        assertEquals(listOf("os", "sys"), py)

        val ts = WorkspaceContext.importsOf(
            "import React from 'react'\nconst fs = require('fs')\n", "a.tsx"
        )
        assertTrue(ts.contains("react"))
        assertTrue(ts.contains("fs"))

        val c = WorkspaceContext.importsOf("#include <stdio.h>\n#include \"local.h\"\n", "a.c")
        assertEquals(listOf("stdio.h", "local.h"), c)

        assertEquals(emptyList<String>(), WorkspaceContext.importsOf("", "a.kt"))
    }

    @Test
    fun importRenderingReportsTheTotalWhenCapped() {
        val imports = (1..40).joinToString("\n") { "import pkg.Type$it" }
        val f = File(root, "Big.kt").apply { writeText(imports) }
        val out = WorkspaceContext.build(snap(currentFile = f.absolutePath, text = imports))
        assertTrue(out.contains("Imports (15 of 40)"))
    }

    @Test
    fun openFilesAreRelativeAndCappedWithAnHonestRemainder() {
        val files = (1..20).map { i -> File(root, "F$i.kt").apply { writeText("x") } }
        val out = WorkspaceContext.build(snap(openFiles = files.map { it.absolutePath }))
        assertTrue(out.contains("F1.kt"))
        assertTrue(out.contains("(+8 more)"))
    }

    @Test
    fun instructionFilesRideTheSameBlock() {
        val out = WorkspaceContext.build(snap(instructions = "## PROJECT INSTRUCTIONS\nuse spaces"))
        assertTrue(out.contains("## PROJECT INSTRUCTIONS"))
        assertTrue(out.contains("use spaces"))
    }

    @Test
    fun theBlockNeverExceedsItsCharacterBudget() {
        repeat(200) { i -> File(root, "longname$i.kt").writeText("x") }
        val out = WorkspaceContext.build(snap(), maxChars = 600)
        assertTrue("was " + out.length, out.length <= 600 + 60) // marker allowance
        assertTrue(out.contains("[... workspace context truncated"))
    }

    @Test
    fun languageIsLabelledAndUnknownExtensionsAreNot() {
        assertEquals("Kotlin", WorkspaceContext.languageFor("A.kt"))
        assertEquals("TypeScript (React)", WorkspaceContext.languageFor("A.tsx"))
        assertNull(WorkspaceContext.languageFor("A.unknownext"))
    }

    @Test
    fun relativePathsFallBackToAbsoluteOutsideTheRoot() {
        val outside = File(System.getProperty("java.io.tmpdir"), "elsewhere.kt").absolutePath
        assertEquals(outside, WorkspaceContext.relativeTo(root.absolutePath, outside))
        assertEquals(
            "src/A.kt",
            WorkspaceContext.relativeTo(root.absolutePath, File(root, "src/A.kt").absolutePath)
        )
    }
}
