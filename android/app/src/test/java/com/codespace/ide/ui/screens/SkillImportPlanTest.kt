package com.codespace.ide.ui.screens

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** PHASE 6b: what a picked file becomes, without a picker or a ContentResolver. */
class SkillImportPlanTest {

    @Test
    fun aMarkdownFileKeepsItsHeadingAsTheName() {
        val plan = SkillImportPlan.fromFile("notes.md", "# Deploy the app\n\nrun gradle then install")
        assertEquals("Deploy the app", plan?.name)
        assertTrue(plan!!.body.contains("run gradle"))
    }

    @Test
    fun aPlainTextFileIsNamedAfterTheFile() {
        val plan = SkillImportPlan.fromFile("nightly-checks.txt", "step one\nstep two")
        assertEquals("nightly-checks", plan?.name)
        assertEquals("step one", plan?.description)
    }

    @Test
    fun aSkillJsonIsReadAsASkill() {
        val json = """{"name":"Run the tests","description":"gradle suite","body":"step 1","slug":"run-the-tests","enabled":true}"""
        val plan = SkillImportPlan.fromFile("run-the-tests.json", json)
        assertEquals("Run the tests", plan?.name)
        assertEquals("gradle suite", plan?.description)
        assertEquals("step 1", plan?.body)
    }

    @Test
    fun anEmptyFileIsRefusedRatherThanSavedBlank() {
        assertNull(SkillImportPlan.fromFile("empty.md", "   \n  "))
    }

    @Test
    fun aFileWithNoExtensionStillGetsAName() {
        val plan = SkillImportPlan.fromFile("checks", "do the thing")
        assertEquals("checks", plan?.name)
    }
}
