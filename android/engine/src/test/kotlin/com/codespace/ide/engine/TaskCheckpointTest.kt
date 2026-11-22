package com.codespace.ide.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class TaskCheckpointTest {

    private val state = TaskCheckpoint.State(
        goal = "fix the retry loop",
        stepIndex = 2,
        totalSteps = 5,
        done = listOf(
            TaskCheckpoint.Done("s1", "read the adapter"),
            TaskCheckpoint.Done("s2", "reproduce the failure"),
        ),
        nextAction = "patch the backoff",
        updatedAt = 1_800_000_000_000L,
    )

    @Test
    fun aCheckpointSurvivesARoundTrip() {
        val restored = TaskCheckpoint.read(TaskCheckpoint.write(state))
        assertTrue(restored is TaskCheckpoint.Restore.Ok)
        assertEquals(state, (restored as TaskCheckpoint.Restore.Ok).state)
    }

    @Test
    fun awkwardTextSurvivesToo() {
        val nasty = state.copy(
            goal = "line one\nline two \\ backslash \u001F separator",
            nextAction = "a\\nb",
        )
        val restored = TaskCheckpoint.read(TaskCheckpoint.write(nasty))
        assertEquals(nasty, (restored as TaskCheckpoint.Restore.Ok).state)
    }

    @Test
    fun remainingIsDerivedNotStored() {
        assertEquals(3, state.remaining)
        assertTrue(state.isComplete.not())
        assertTrue(state.copy(stepIndex = 5).isComplete)
    }

    @Test
    fun anEmptyCheckpointIsUnreadableRatherThanReset() {
        assertTrue(TaskCheckpoint.read("") is TaskCheckpoint.Restore.Unreadable)
    }

    @Test
    fun aTruncatedHeaderIsReported() {
        assertTrue(TaskCheckpoint.read("1\u001F2") is TaskCheckpoint.Restore.Unreadable)
    }

    @Test
    fun aNewerFormatIsRefusedNotGuessed() {
        val future = TaskCheckpoint.write(state.copy(version = 99))
        val r = TaskCheckpoint.read(future)
        assertTrue(r is TaskCheckpoint.Restore.Unreadable)
        assertTrue((r as TaskCheckpoint.Restore.Unreadable).reason.contains("newer format"))
    }

    @Test
    fun aNegativeCounterIsRefused() {
        val bad = "1\u001F-4\u001F5\u001F0\u001Fgoal\u001F\n"
        assertTrue(TaskCheckpoint.read(bad) is TaskCheckpoint.Restore.Unreadable)
    }
}
