package com.codespace.ide.agent

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * Wave 1 Group D — coverage_read, entity_schema_read, memory_search.
 *
 * Every case here is about NOT inventing data: no report means no numbers, an unreadable report is
 * refused, a missing entity is named as missing, and an empty memory says it is empty.
 */
class InsightToolsTest {

    @get:Rule val tmp = TemporaryFolder()

    private val jacocoXml = """
        <?xml version="1.0" encoding="UTF-8"?>
        <report name="demo">
          <package name="com/demo">
            <sourcefile name="Full.kt">
              <line nr="1" mi="0" ci="3" mb="0" cb="0"/>
              <line nr="2" mi="0" ci="2" mb="0" cb="0"/>
              <counter type="LINE" missed="0" covered="2"/>
              <counter type="BRANCH" missed="1" covered="3"/>
            </sourcefile>
            <sourcefile name="Half.kt">
              <line nr="10" mi="2" ci="0" mb="0" cb="0"/>
              <line nr="11" mi="0" ci="2" mb="0" cb="0"/>
              <counter type="LINE" missed="1" covered="1"/>
            </sourcefile>
          </package>
        </report>
    """.trimIndent()

    // ── coverage ───────────────────────────────────────────────────────────

    @Test fun `jacoco xml is summarised with lines, branches and worst files first`() {
        val s = InsightTools.parseJacocoXml(jacocoXml, null)
        assertNotNull(s)
        assertEquals(3, s!!.lineCovered)
        assertEquals(4, s.lineTotal)
        assertEquals(3, s.branchCovered)
        assertEquals(4, s.branchTotal)
        assertEquals("com/demo/Half.kt", s.files.first().name)
        assertTrue(s.render().contains("lines: 3/4"))
        assertTrue(s.render().contains("branches: 3/4"))
    }

    @Test fun `missing branch counters are reported as unavailable, not as zero`() {
        val xml = """
            <report><package name="p"><sourcefile name="A.kt">
            <counter type="LINE" missed="1" covered="1"/>
            </sourcefile></package></report>
        """.trimIndent()
        val s = InsightTools.parseJacocoXml(xml, null)!!
        assertNull(s.branchTotal)
        assertTrue("a metric the report does not carry must not look like 0%",
            s.render().contains("unavailable"))
    }

    @Test fun `file filtering narrows the report`() {
        val all = InsightTools.parseJacocoXml(jacocoXml, null)!!
        val one = InsightTools.parseJacocoXml(jacocoXml, "Full")!!
        assertEquals(2, all.files.size)
        assertEquals(1, one.files.size)
        assertEquals(2, one.lineTotal)
    }

    @Test fun `a malformed report is refused rather than half-read`() {
        assertNull(InsightTools.parseJacocoXml("<not-a-report", null))
        assertNull(InsightTools.parseJacocoXml("<report></report>", null))
    }

    @Test fun `lcov is parsed with branches when present`() {
        val lcov = """
            SF:/p/A.kt
            DA:1,1
            DA:2,0
            BRDA:3,0,0,1
            BRDA:3,0,1,0
            BRF:2
            BRH:1
            end_of_record
            SF:/p/B.kt
            DA:5,3
            end_of_record
        """.trimIndent()
        val s = InsightTools.parseLcov(lcov, null)!!
        assertEquals(2, s.lineCovered)
        assertEquals(3, s.lineTotal)
        assertEquals(1, s.branchCovered)
        assertEquals(2, s.branchTotal)
        assertEquals(2, s.files.size)
    }

    @Test fun `lcov without branch records reports branches as unavailable`() {
        val s = InsightTools.parseLcov("SF:/p/A.kt\nDA:1,1\nend_of_record", null)!!
        assertNull(s.branchTotal)
    }

    @Test fun `jacoco csv is accepted when it is the only report`() {
        val csv = "GROUP,PACKAGE,CLASS,INSTRUCTION_MISSED,LINE_MISSED,LINE_COVERED\n" +
            "demo,com.demo,Thing,10,2,8\n"
        val s = InsightTools.parseJacocoCsv(csv, null)!!
        assertEquals(8, s.lineCovered)
        assertEquals(10, s.lineTotal)
    }

    @Test fun `a csv without line columns is refused`() {
        assertNull(InsightTools.parseJacocoCsv("GROUP,PACKAGE,CLASS\nx,y,z\n", null))
    }

    @Test fun `no report means no numbers, and says how to get one`() {
        val module = tmp.newFolder("module")
        val out = InsightTools.coverage(module, null)
        assertTrue(out.contains("No coverage report found"))
        assertTrue("must not imply zero coverage", out.contains("NOT zero coverage"))
        assertTrue(out.contains("JaCoCo"))
    }

    @Test fun `a report on disk is found and read`() {
        val module = tmp.newFolder("m2")
        val dir = File(module, "build/reports/coverage").apply { mkdirs() }
        File(dir, "report.xml").writeText(jacocoXml)
        val out = InsightTools.coverage(module, null)
        assertTrue(out.contains("lines: 3/4"))
        assertTrue(out.contains("report.xml"))
    }

    @Test fun `an unreadable report is reported rather than silently skipped`() {
        val module = tmp.newFolder("m3")
        val dir = File(module, "build/reports/coverage").apply { mkdirs() }
        File(dir, "report.xml").writeText("<report></report>")
        val out = InsightTools.coverage(module, null)
        assertTrue(out.contains("none could be read"))
    }

    @Test fun `file list is bounded`() {
        val many = buildString {
            append("<report><package name=\"p\">")
            for (i in 0 until InsightTools.MAX_FILES + 20) {
                append("<sourcefile name=\"F$i.kt\"><counter type=\"LINE\" missed=\"1\" covered=\"1\"/></sourcefile>")
            }
            append("</package></report>")
        }
        val s = InsightTools.parseJacocoXml(many, null)!!
        assertEquals(InsightTools.MAX_FILES, s.files.size)
        assertTrue(s.filesTruncated)
    }

    // ── entity schema ──────────────────────────────────────────────────────

    private fun store(name: String = "agent_data"): File = File(tmp.root, name).apply { mkdirs() }

    private fun record(root: File, entity: String, id: String, json: String) {
        val dir = File(root, entity).apply { mkdirs() }
        File(dir, "$id.json").writeText(json)
    }

    @Test fun `a known entity reports its fields types and requiredness`() {
        val root = store()
        record(root, "task", "1", """{"id":"1","title":"a","done":false}""")
        record(root, "task", "2", """{"id":"2","title":"b","done":true,"due":5}""")
        val out = InsightTools.entitySchemaFromStore(root, "task")
        assertTrue(out.contains("Entity \"task\": 2 records"))
        assertTrue(out.contains("title: string (in every sampled record)"))
        assertTrue(out.contains("done: boolean"))
        assertTrue(out.contains("due: number (in 1 of 2)"))
        assertTrue(out.contains("[store-managed]"))
    }

    @Test fun `listing entities names them`() {
        val root = store()
        record(root, "task", "1", """{"id":"1"}""")
        record(root, "note", "1", """{"id":"1"}""")
        val out = InsightTools.entitySchemaFromStore(root, "")
        assertTrue(out.contains("Entities (2)"))
        assertTrue(out.contains("note"))
        assertTrue(out.contains("task"))
    }

    @Test fun `an unknown entity lists what does exist`() {
        val root = store()
        record(root, "task", "1", """{"id":"1"}""")
        val out = InsightTools.entitySchemaFromStore(root, "widget")
        assertTrue(out.contains("No entity named"))
        assertTrue(out.contains("task"))
    }

    @Test fun `an entity name that tries to escape is refused`() {
        val root = store()
        val out = InsightTools.entitySchemaFromStore(root, "../etc")
        assertTrue(out.contains("Invalid entity name"))
    }

    @Test fun `an empty entity has no observable schema and says so`() {
        val root = store()
        File(root, "empty").mkdirs()
        val out = InsightTools.entitySchemaFromStore(root, "empty")
        assertTrue(out.contains("no observable schema"))
    }

    @Test fun `a store that does not exist is distinguished from an empty one`() {
        val out = InsightTools.entitySchemaFromStore(File(tmp.root, "nothing"), "task")
        assertTrue(out.contains("No entity store yet"))
    }

    @Test fun `invalid records are counted rather than trusted`() {
        val root = store()
        record(root, "broken", "1", "{not json")
        val out = InsightTools.entitySchemaFromStore(root, "broken")
        assertTrue(out.contains("none are valid JSON"))
    }

    @Test fun `schema output is bounded`() {
        val root = store()
        val wide = (0 until InsightTools.MAX_SCHEMA_FIELDS + 30).joinToString(",") { "\"f$it\":1" }
        record(root, "wide", "1", "{$wide}")
        val out = InsightTools.entitySchemaFromStore(root, "wide")
        val fieldLines = out.lines().count { it.trimStart().startsWith("f") }
        assertTrue("field rows must be capped", fieldLines <= InsightTools.MAX_SCHEMA_FIELDS)
    }

    // ── memory search ──────────────────────────────────────────────────────

    private val memories = mapOf(
        "project_root" to "/data/projects/codespace",
        "preferred_shell" to "bash",
        "android_sdk" to "/opt/android-sdk",
        "user_name" to "Wisdom",
    )

    @Test fun `a key match outranks a value match`() {
        val out = InsightTools.rank(mapOf(
            "colour" to "the shell theme",
            "shell_theme" to "dark",
        ), "shell")
        val lines = out.lines().filter { it.startsWith("  ") }
        assertTrue(lines.first().contains("shell_theme"))
        assertTrue(lines[1].contains("colour"))
    }

    @Test fun `an exact key beats a partial one`() {
        val out = InsightTools.rank(mapOf(
            "shell" to "zsh",
            "shell_theme" to "dark",
        ), "shell")
        assertTrue(out.lines().first { it.startsWith("  ") }.contains("shell: zsh"))
    }

    @Test fun `no match is stated with the number searched`() {
        val out = InsightTools.rank(memories, "kubernetes")
        assertTrue(out.contains("No memory matches"))
        assertTrue(out.contains("searched 4 visible entries"))
    }

    @Test fun `an empty query asks for one instead of matching everything`() {
        assertTrue(InsightTools.rank(memories, "  ").contains("Give a word or phrase"))
        assertTrue(InsightTools.rank(emptyMap(), "anything").contains("Memory is empty"))
    }

    @Test fun `results are ranked and limited`() {
        val out = InsightTools.rank(
            mapOf("proj_a" to "x", "proj_b" to "x", "proj_c" to "x"), "proj_", limit = 2)
        assertTrue(out.contains("more"))
    }

    @Test fun `a term with no letters is refused`() {
        assertTrue(InsightTools.rank(memories, "!!!").contains("nothing searchable"))
    }

    @Test fun `long values are clipped in the result`() {
        val out = InsightTools.rank(mapOf("key" to "x".repeat(900)), "key")
        assertTrue(out.lines().first { it.startsWith("  k") }.length < 350)
    }
}
