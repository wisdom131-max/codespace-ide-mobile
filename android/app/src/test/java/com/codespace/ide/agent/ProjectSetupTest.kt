package com.codespace.ide.agent

import org.junit.Assert.assertTrue
import org.junit.Test

/** get_project_setup_info must only report what a real marker file proves. */
class ProjectSetupTest {

    @Test
    fun gradleMarkerYieldsBuildTestAndRunCommands() {
        val text = ProjectSetup.describe(listOf("gradlew", "build.gradle.kts", "app", "gradle"))
        assertTrue(text.contains("Gradle"))
        assertTrue(text.contains("build: ./gradlew build"))
        assertTrue(text.contains("test:  ./gradlew test"))
    }

    @Test
    fun androidWrapperIsReportedAsAndroid() {
        val text = ProjectSetup.describe(listOf("gradlew", "settings.gradle.kts"))
        assertTrue(text.contains("Android / Gradle"))
    }

    @Test
    fun nodeMarkerYieldsNpmCommands() {
        val text = ProjectSetup.describe(listOf("package.json"))
        assertTrue(text.contains("npm test"))
    }

    @Test
    fun noMarkerIsSaidOutLoudInsteadOfGuessed() {
        val text = ProjectSetup.describe(listOf("src", "README.md"))
        assertTrue(text.contains("No build marker found"))
        assertTrue("must not invent a framework", !text.contains("Gradle"))
    }
}
