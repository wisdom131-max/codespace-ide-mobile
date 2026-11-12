package com.codespace.ide.agent

import com.codespace.ide.agent.packages.Lockfiles
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** The tool layer: containment, detection, filtering, pagination and output bounds. */
class PackageToolsTest {

    private fun tmp(): File = kotlin.io.path.createTempDirectory("pkgtools").toFile()

    private fun lock(dir: File, body: String, name: String = "package-lock.json"): File =
        File(dir, name).apply { writeText(body) }

    private val smallLock = """
        {"lockfileVersion":3,"packages":{
          "":{"dependencies":{"express":"^4"}},
          "node_modules/express":{"version":"4.18.2","resolved":"https://registry.npmjs.org/express/-/express-4.18.2.tgz"},
          "node_modules/qs":{"version":"6.11.0","dev":true},
          "node_modules/express/node_modules/qs":{"version":"6.9.0"}}}
    """.trimIndent()

    @Test
    fun aMissingLockfilePathIsRefusedWithTheSupportedList() {
        val root = tmp()
        val out = PackageTools.lockfile(root, null, null, null, false, false, false, 0, 40)
        assertTrue(out, out.contains("No supported lockfile found"))
        assertTrue(out, out.contains("package-lock.json"))
    }

    @Test
    fun theOnlyLockfileIsFoundAutomatically() {
        val root = tmp()
        lock(root, smallLock)
        val out = PackageTools.lockfile(root, null, null, null, false, false, false, 0, 40)
        assertTrue(out, out.startsWith("package-lock.json (lockfile v3)"))
        assertTrue(out, out.contains("express 4.18.2 [direct]"))
    }

    @Test
    fun aPathOutsideTheWorkspaceIsRefused() {
        val root = tmp()
        val out = PackageTools.lockfile(root, "../../etc/package-lock.json", null, null, false, false, false, 0, 40)
        assertTrue(out, out.startsWith("Refused:"))
    }

    @Test
    fun anAbsolutePathOutsideTheWorkspaceIsRefused() {
        val root = tmp()
        val out = PackageTools.lockfile(root, "/etc/package-lock.json", null, null, false, false, false, 0, 40)
        assertTrue(out, out.startsWith("Refused:"))
    }

    @Test
    fun aSupportedFileInsideTheWorkspaceIsAcceptedByPath() {
        val root = tmp()
        File(root, "sub").mkdirs()
        lock(File(root, "sub"), smallLock)
        val out = PackageTools.lockfile(root, "sub/package-lock.json", null, null, false, false, false, 0, 40)
        assertTrue(out, out.contains("express"))
    }

    @Test
    fun anUnsupportedFileNameIsNamed() {
        val root = tmp()
        lock(root, "x", "Cargo.lock")
        val out = PackageTools.lockfile(root, "Cargo.lock", null, null, false, false, false, 0, 40)
        assertTrue(out, out.contains("not a lockfile this tool parses"))
    }

    @Test
    fun directingTheToolAtTheWrongFormatIsAnError() {
        val root = tmp()
        lock(root, smallLock)
        val out = PackageTools.lockfile(root, null, "poetry", null, false, false, false, 0, 40)
        assertTrue(out, out.contains("not 'poetry'"))
    }

    @Test
    fun theDevFilterAndTheDirectFilterNarrowTheList() {
        val parsed = Lockfiles.parse("package-lock.json", smallLock)
        val dev = PackageTools.render(parsed, null, null, false, false, true, 0, 40)
        assertTrue(dev, dev.contains("qs 6.11.0 [dev]"))
        assertTrue("express is not dev", !dev.contains("express 4.18.2"))
        val direct = PackageTools.render(parsed, null, null, true, false, false, 0, 40)
        assertTrue(direct.contains("express 4.18.2 [direct]"))
        assertTrue("transitive qs filtered out", !direct.contains("\n- qs "))
    }

    @Test
    fun aNameFilterMatchesOnSubstring() {
        val parsed = Lockfiles.parse("package-lock.json", smallLock)
        val out = PackageTools.render(parsed, null, "exp", false, false, false, 0, 40)
        assertTrue(out.contains("express"))
        assertTrue(!out.contains("\n- qs "))
    }

    @Test
    fun duplicatesAreCalledOutInTheAnswer() {
        val parsed = Lockfiles.parse("package-lock.json", smallLock)
        val out = PackageTools.render(parsed, null, null, false, false, false, 0, 40)
        assertTrue(out, out.contains("Duplicate records"))
        assertTrue(out, out.contains("6.11.0") && out.contains("6.9.0"))
    }

    @Test
    fun paginationSaysWhereToContinueAndHonoursOffset() {
        val sb = StringBuilder("{\"lockfileVersion\":3,\"packages\":{\"\":{}")
        for (i in 0 until 60) sb.append(",\"node_modules/p").append(i).append("\":{\"version\":\"1.0.0\"}")
        sb.append("}}")
        val parsed = Lockfiles.parse("package-lock.json", sb.toString())
        val first = PackageTools.render(parsed, null, null, false, false, false, 0, 10)
        assertTrue(first, first.contains("Showing 1-10 of 60"))
        assertTrue(first.contains("offset=10"))
        val second = PackageTools.render(parsed, null, null, false, false, false, 10, 10)
        assertTrue(second, second.contains("Showing 11-20 of 60"))
        assertTrue("different page", !second.contains("- p0 1.0.0"))
    }

    @Test
    fun outputStaysBounded() {
        val sb = StringBuilder("{\"lockfileVersion\":3,\"packages\":{\"\":{}")
        for (i in 0 until 400) sb.append(",\"node_modules/package-with-a-long-name-").append(i)
            .append("\":{\"version\":\"12.34.56\",\"integrity\":\"sha512-").append("z".repeat(80)).append("\"}")
        sb.append("}}")
        val parsed = Lockfiles.parse("package-lock.json", sb.toString())
        val out = PackageTools.render(parsed, null, null, false, false, false, 0, 200)
        assertTrue("under the prompt cap: ${out.length}", out.length <= PackageTools.MAX_OUTPUT)
    }

    @Test
    fun resolvingInsideKeepsPathsInTheWorkspaceOnly() {
        val root = tmp()
        File(root, "a.txt").writeText("x")
        assertNotNull(PackageTools.resolveInside(root, "a.txt"))
        assertNull(PackageTools.resolveInside(root, "../a.txt"))
        assertNull(PackageTools.resolveInside(root, "/etc/hostname"))
    }

    @Test
    fun everySupportedNameHasAParser() {
        Lockfiles.supportedNames.forEach { name ->
            assertNotNull("no manager for $name", Lockfiles.managerFor(name))
        }
    }
}
