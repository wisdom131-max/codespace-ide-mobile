package com.codespace.ide.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

/**
 * A skill is stored as a file named after itself, so the slug is a filesystem decision, not a
 * cosmetic one: it must never contain a separator, a dot, or a leading dash that a shell would
 * read as a flag.
 */
class SkillStoreNamingTest {

    @Test
    fun aNameBecomesAFilenameSafeSlug() {
        assertEquals("api-review", SkillStore.slugOf("API Review"))
        assertEquals("release-notes", SkillStore.slugOf("release notes"))
    }

    @Test
    fun pathSeparatorsAndDotsCannotEscapeTheDirectory() {
        val slug = SkillStore.slugOf("../../etc/passwd")
        assertFalse("no separators", slug.contains("/"))
        assertFalse("no traversal", slug.contains(".."))
        assertFalse("no dot at all", slug.contains("."))
    }

    @Test
    fun punctuationRunsCollapseAndEdgesAreTrimmed() {
        assertEquals("a-b", SkillStore.slugOf("  a  ---  b  "))
        assertEquals("hello-world", SkillStore.slugOf("**hello** world!"))
    }

    @Test
    fun anEmptyNameStillProducesAUsableFile() {
        assertEquals("skill", SkillStore.slugOf(""))
        assertEquals("skill", SkillStore.slugOf("!!!"))
    }
}
