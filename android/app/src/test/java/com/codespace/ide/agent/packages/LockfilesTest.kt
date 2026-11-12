package com.codespace.ide.agent.packages

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Every format claimed in the docs is parsed here, offline, from a literal fixture. */
class LockfilesTest {

    private val packageLockV3 = """
        {"name":"app","version":"1.0.0","lockfileVersion":3,
         "packages":{
           "":{"name":"app","dependencies":{"express":"^4.18.2"},"devDependencies":{"jest":"^29"}},
           "node_modules/express":{"version":"4.18.2","resolved":"https://registry.npmjs.org/express/-/express-4.18.2.tgz","integrity":"sha512-abc"},
           "node_modules/jest":{"version":"29.7.0","dev":true,"integrity":"sha512-def"},
           "node_modules/body-parser":{"version":"1.20.1","optional":true},
           "node_modules/express/node_modules/qs":{"version":"6.11.0"}
         }}
    """.trimIndent()

    @Test
    fun npmLockV3ReadsPackagesWithDirectAndDevFlags() {
        val p = Lockfiles.parse("package-lock.json", packageLockV3)
        assertEquals("3", p.lockfileVersion)
        assertEquals(4, p.packages.size)
        val express = p.packages.first { it.name == "express" }
        assertEquals("4.18.2", express.version)
        assertEquals(true, express.direct)
        assertTrue(express.integrity!!.startsWith("sha512"))
        assertTrue(express.source!!.contains("express-4.18.2.tgz"))
        assertEquals(true, p.packages.first { it.name == "jest" }.dev)
        assertEquals(true, p.packages.first { it.name == "body-parser" }.optional)
        assertEquals("transitive qs is not direct", false, p.packages.first { it.name == "qs" }.direct)
    }

    @Test
    fun npmLockV1WalksNestedDependencies() {
        val v1 = """
            {"name":"app","lockfileVersion":1,"dependencies":{
              "express":{"version":"4.18.2","integrity":"sha1-x","dependencies":{"qs":{"version":"6.11.0"}}}
            }}
        """.trimIndent()
        val p = Lockfiles.parse("package-lock.json", v1)
        assertEquals(2, p.packages.size)
        assertEquals(true, p.packages.first { it.name == "express" }.direct)
        assertEquals(false, p.packages.first { it.name == "qs" }.direct)
    }

    @Test
    fun npmShrinkwrapUsesTheSameParser() {
        val p = Lockfiles.parse("npm-shrinkwrap.json", packageLockV3)
        assertEquals(Lockfiles.Manager.NPM, p.manager)
        assertEquals(4, p.packages.size)
    }

    @Test
    fun yarnClassicBlocksAreParsed() {
        val yarn = """
            # yarn lockfile v1
            express@^4.18.2:
              version "4.18.2"
              resolved "https://registry.yarnpkg.com/express/-/express-4.18.2.tgz#abc"
              integrity sha512-xyz
              dependencies:
                qs "6.11.0"

            "body-parser@^1.20.1":
              version "1.20.1"
              resolved "https://registry.yarnpkg.com/body-parser/-/body-parser-1.20.1.tgz"
        """.trimIndent()
        val p = Lockfiles.parse("yarn.lock", yarn)
        assertEquals(2, p.packages.size)
        assertEquals("4.18.2", p.packages.first { it.name == "express" }.version)
        assertTrue(p.packages.first { it.name == "express" }.integrity!!.contains("sha512"))
        assertEquals("1.20.1", p.packages.first { it.name == "body-parser" }.version)
    }

    @Test
    fun yarnBerryUsesColonSyntax() {
        val berry = """
            __metadata:
              version: 8

            "express@npm:^4.18.2":
              version: 4.18.2
              resolution: "express@npm:4.18.2"
              checksum: 10c0/abc
        """.trimIndent()
        val p = Lockfiles.parse("yarn.lock", berry)
        assertTrue(p.format.contains("berry"))
        assertEquals(1, p.packages.size)
        assertEquals("4.18.2", p.packages[0].version)
    }

    @Test
    fun pnpmLockParsesThePackagesSection() {
        val pnpm = """
            lockfileVersion: '6.0'
            dependencies:
              express:
                specifier: ^4.18.2
                version: 4.18.2
            packages:
              /express@4.18.2:
                resolution: {integrity: sha512-abc}
              /@types/node@20.1.0:
                resolution: {integrity: sha512-def}
        """.trimIndent()
        val p = Lockfiles.parse("pnpm-lock.yaml", pnpm)
        assertEquals("6.0", p.lockfileVersion)
        assertEquals(2, p.packages.size)
        assertEquals("4.18.2", p.packages.first { it.name == "express" }.version)
        assertTrue("scoped name survives", p.packages.any { it.name == "@types/node" })
    }

    @Test
    fun poetryLockReadsPackagesAndDevCategory() {
        val poetry = """
            [[package]]
            name = "requests"
            version = "2.32.3"
            optional = false
            python-versions = ">=3.8"

            [package.dependencies]
            idna = ">=2.5"

            [[package]]
            name = "pytest"
            version = "8.2.0"
            category = "dev"
        """.trimIndent()
        val p = Lockfiles.parse("poetry.lock", poetry)
        assertEquals(2, p.packages.size)
        assertEquals("2.32.3", p.packages.first { it.name == "requests" }.version)
        assertEquals(">=3.8", p.packages.first { it.name == "requests" }.markers)
        assertEquals(true, p.packages.first { it.name == "pytest" }.dev)
        assertNull("poetry cannot say what is direct", p.packages[0].direct)
    }

    @Test
    fun pipfileLockSeparatesDefaultAndDevelop() {
        val pipfile = """
            {"_meta":{"pipfile-spec":6},
             "default":{"requests":{"version":"==2.32.3","hashes":["sha256:abc"]},
                        "gitdep":{"git":"https://example.com/x.git","ref":"main"}},
             "develop":{"pytest":{"version":"==8.2.0"}}}
        """.trimIndent()
        val p = Lockfiles.parse("Pipfile.lock", pipfile)
        assertEquals(3, p.packages.size)
        assertEquals("2.32.3", p.packages.first { it.name == "requests" }.version)
        assertTrue(p.packages.first { it.name == "requests" }.integrity!!.contains("sha256"))
        assertEquals(true, p.packages.first { it.name == "pytest" }.dev)
        assertTrue("git dependency keeps its source", p.packages.first { it.name == "gitdep" }.source!!.contains("x.git"))
    }

    @Test
    fun uvAndPdmLocksUseTheTomlShape() {
        val uv = """
            [[package]]
            name = "httpx"
            version = "0.27.0"
            source = { registry = "https://pypi.org/simple" }

            [[package]]
            name = "anyio"
            version = "4.4.0"
        """.trimIndent()
        val uvParsed = Lockfiles.parse("uv.lock", uv)
        assertEquals(2, uvParsed.packages.size)
        assertEquals("0.27.0", uvParsed.packages.first { it.name == "httpx" }.version)
        assertTrue(uvParsed.packages.first { it.name == "httpx" }.source != null)

        val pdm = uv.replace("[[package]]", "[[package]]")
        val pdmParsed = Lockfiles.parse("pdm.lock", pdm)
        assertEquals(2, pdmParsed.packages.size)
    }

    @Test
    fun requirementsTxtIsReadAsADependencyFile() {
        val req = """
            # comment
            requests==2.32.3  # trailing comment
            uvicorn[standard]>=0.30
            click
            -r other.txt
            pywin32; sys_platform == "win32"
        """.trimIndent()
        val p = Lockfiles.parse("requirements.txt", req)
        assertEquals(4, p.packages.size)
        assertEquals("2.32.3", p.packages.first { it.name == "requests" }.version)
        assertEquals(">=0.30", p.packages.first { it.name == "uvicorn" }.version)
        assertEquals("(unpinned)", p.packages.first { it.name == "click" }.version)
        assertTrue("platform marker kept", p.packages.first { it.name == "pywin32" }.markers!!.contains("win32"))
        assertTrue("an unpinned requirement is called out", p.problems.any { it.contains("not an exact version") })
    }

    @Test
    fun anEmptyFileSaysItIsEmpty() {
        val p = Lockfiles.parse("package-lock.json", "   ")
        assertTrue(p.packages.isEmpty())
        assertTrue(p.problems.any { it.contains("empty") })
    }

    @Test
    fun aCorruptJsonLockfileIsReportedNotThrown() {
        val p = Lockfiles.parse("package-lock.json", "{\"packages\": {")
        assertTrue(p.packages.isEmpty())
        assertTrue(p.problems.any { it.contains("corrupt or truncated") })
    }

    @Test
    fun anUnsupportedFileNameIsRefusedWithTheListOfSupportedOnes() {
        val p = Lockfiles.parse("Cargo.lock", "[[package]]")
        assertTrue(p.packages.isEmpty())
        assertTrue(p.problems.first().contains("package-lock.json"))
    }

    @Test
    fun duplicateVersionsOfTheSamePackageAreDetected() {
        val doc = """
            {"lockfileVersion":3,"packages":{
              "node_modules/a":{"version":"1.0.0"},
              "node_modules/b/node_modules/a":{"version":"2.0.0"},
              "node_modules/c":{"version":"1.0.0"}}}
        """.trimIndent()
        val p = Lockfiles.parse("package-lock.json", doc)
        assertEquals(setOf("1.0.0", "2.0.0"), p.duplicates()["a"]!!.toSet())
        assertNull("no false duplicates", p.duplicates()["c"])
    }

    @Test
    fun aLargeLockfileParsesWithoutBlowingUp() {
        val sb = StringBuilder("{\"lockfileVersion\":3,\"packages\":{\"\":{}")
        for (i in 0 until 1500) sb.append(",\"node_modules/p$i\":{\"version\":\"1.0.$i\"}")
        sb.append("}}")
        val p = Lockfiles.parse("package-lock.json", sb.toString())
        assertEquals(1500, p.packages.size)
    }
}
