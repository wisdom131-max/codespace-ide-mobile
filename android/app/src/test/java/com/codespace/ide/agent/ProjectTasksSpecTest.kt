package com.codespace.ide.agent

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** VS Code tasks.json: what it reads, what it refuses, and how it orders. */
class ProjectTasksSpecTest {

    private fun read(json: String): List<ProjectTasksSpec.Task> {
        val result = ProjectTasksSpec.parse(json)
        assertTrue("expected a readable file, got " + result, result is ProjectTasksSpec.ReadOk)
        return (result as ProjectTasksSpec.ReadOk).tasks
    }

    @Test
    fun `comments are legal and are stripped`() {
        val tasks = read(
            """
            {
              // the build entry
              "version": "2.0.0",
              "tasks": [
                /* one task */
                { "label": "build", "command": "./gradlew", "args": ["assembleDebug"] }
              ]
            }
            """.trimIndent()
        )
        assertEquals(1, tasks.size)
        assertEquals("build", tasks[0].label)
        assertEquals(listOf("assembleDebug"), tasks[0].args)
    }

    @Test
    fun `a url inside a string is not a comment`() {
        val tasks = read("""{"tasks":[{"label":"fetch","command":"curl http://x/y"}]}""")
        assertTrue(tasks[0].command.contains("http://x/y"))
    }

    @Test
    fun `group and dependsOn are read`() {
        val tasks = read(
            """{"tasks":[
              {"label":"a","command":"one","group":{"kind":"build","isDefault":true}},
              {"label":"b","command":"two","group":"test","dependsOn":["a"]},
              {"label":"c","command":"three","dependsOn":"b"}
            ]}"""
        )
        assertEquals(ProjectTasksSpec.Group.BUILD, tasks[0].group)
        assertEquals(ProjectTasksSpec.Group.TEST, tasks[1].group)
        assertEquals(listOf("a"), tasks[1].dependsOn)
        assertEquals(listOf("b"), tasks[2].dependsOn)
    }

    @Test
    fun `a broken file says why instead of throwing`() {
        val result = ProjectTasksSpec.parse("""{"tasks": [ {"label": }""")
        assertTrue(result is ProjectTasksSpec.ReadFailed)
    }

    @Test
    fun `a file with no tasks array says so`() {
        val result = ProjectTasksSpec.parse("""{"version":"2.0.0"}""")
        assertTrue(result is ProjectTasksSpec.ReadFailed)
    }

    @Test
    fun `args are quoted so a space does not become two arguments`() {
        val task = ProjectTasksSpec.Task(
            label = "t", type = "shell", command = "./run.sh",
            args = listOf("--name", "two words"), cwd = null,
            group = ProjectTasksSpec.Group.NONE, dependsOn = emptyList(), problemMatcher = null,
        )
        assertEquals("./run.sh --name 'two words'", ProjectTasksSpec.commandLine(task, "/p"))
    }

    @Test
    fun `a quote inside an argument cannot break out`() {
        val task = ProjectTasksSpec.Task(
            label = "t", type = "shell", command = "echo",
            args = listOf("it's"), cwd = null,
            group = ProjectTasksSpec.Group.NONE, dependsOn = emptyList(), problemMatcher = null,
        )
        assertEquals("echo 'it'\\''s'", ProjectTasksSpec.commandLine(task, "/p"))
    }

    @Test
    fun `workspaceFolder expands and cwd is applied`() {
        val task = ProjectTasksSpec.Task(
            label = "t", type = "shell", command = "\${workspaceFolder}/gradlew",
            args = emptyList(), cwd = "app", group = ProjectTasksSpec.Group.NONE,
            dependsOn = emptyList(), problemMatcher = null,
        )
        assertEquals("cd app && /repo/gradlew", ProjectTasksSpec.commandLine(task, "/repo"))
    }

    @Test
    fun `dependencies run before the task they serve`() {
        val tasks = read(
            """{"tasks":[
              {"label":"test","command":"t","dependsOn":["build"]},
              {"label":"build","command":"b"}
            ]}"""
        )
        val order = ProjectTasksSpec.executionOrder("test", tasks).map { it.label }
        assertEquals(listOf("build", "test"), order)
    }

    @Test
    fun `a dependency cycle stops instead of looping`() {
        val tasks = read(
            """{"tasks":[
              {"label":"a","command":"one","dependsOn":["b"]},
              {"label":"b","command":"two","dependsOn":["a"]}
            ]}"""
        )
        val order = ProjectTasksSpec.executionOrder("a", tasks).map { it.label }
        assertEquals(listOf("b", "a"), order)
    }

    @Test
    fun `contributed task types are listed but refused, with a reason`() {
        val tasks = read("""{"tasks":[{"label":"npm build","type":"npm","command":"build"}]}""")
        // A contributed type has a command but nothing in this app can execute it, and saying
        // "supported" would be a promise the run cannot keep.
        assertFalse(ProjectTasksSpec.isSupported(tasks[0]))
        assertTrue(ProjectTasksSpec.unsupportedReason(tasks[0]).contains("npm"))
        val gulp = ProjectTasksSpec.Task(
            label = "g", type = "gulp", command = "build", args = emptyList(), cwd = null,
            group = ProjectTasksSpec.Group.NONE, dependsOn = emptyList(), problemMatcher = null,
        )
        assertFalse(ProjectTasksSpec.isSupported(gulp))
        assertTrue(ProjectTasksSpec.unsupportedReason(gulp).contains("gulp"))
    }

    @Test
    fun `order is build then test then the rest`() {
        val tasks = read(
            """{"tasks":[
              {"label":"zeta","command":"z"},
              {"label":"tests","command":"t","group":"test"},
              {"label":"build","command":"b","group":"build"}
            ]}"""
        )
        assertEquals(listOf("build", "tests", "zeta"), ProjectTasksSpec.ordered(tasks).map { it.label })
    }
}
