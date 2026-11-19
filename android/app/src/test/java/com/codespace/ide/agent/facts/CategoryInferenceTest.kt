package com.codespace.ide.agent.facts

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * STAGE 2 — the ambiguous statements that used to be filed under the wrong category.
 *
 * These are regression tests, not preferences: each one failed before the precedence change, and
 * the cost of the old behaviour was a hard constraint being retrieved as if it were build trivia.
 */
class CategoryInferenceTest {

    @Test
    fun `a build constraint is a constraint, not build trivia`() {
        assertEquals(
            FactCategory.CONSTRAINT,
            FactCategory.infer("The build must not exceed 64KB per method"),
        )
    }

    @Test
    fun `a test constraint is a constraint, not a test note`() {
        assertEquals(
            FactCategory.CONSTRAINT,
            FactCategory.infer("Tests must never hit the network"),
        )
    }

    @Test
    fun `a naming rule about tests is a convention, not a test note`() {
        assertEquals(
            FactCategory.CONVENTION,
            FactCategory.infer("Test naming uses snake_case"),
        )
    }

    @Test
    fun `explicit never-use wording stays a preference`() {
        assertEquals(
            FactCategory.PREFERENCE,
            FactCategory.infer("Never use tabs for indentation"),
        )
    }

    @Test
    fun `a literal build command is still build`() {
        assertEquals(FactCategory.BUILD, FactCategory.infer("gradle assemble"))
    }

    @Test
    fun `a literal test tool is still test`() {
        assertEquals(FactCategory.TEST, FactCategory.infer("JUnit coverage target is 80%"))
    }

    @Test
    fun `an architecture statement is still architecture`() {
        assertEquals(
            FactCategory.ARCHITECTURE,
            FactCategory.infer("We use MVVM with a repository layer"),
        )
    }

    @Test
    fun `an unclassifiable statement stays general`() {
        assertEquals(
            FactCategory.GENERAL,
            FactCategory.infer("Something worth remembering"),
        )
    }

    @Test
    fun `every category id parses back to itself`() {
        for (c in FactCategory.entries) assertEquals(c, FactCategory.parse(c.id))
    }
}
