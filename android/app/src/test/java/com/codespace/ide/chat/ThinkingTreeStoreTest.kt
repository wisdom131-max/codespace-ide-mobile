package com.codespace.ide.chat

import com.codespace.ide.chat.ui.ChatRowModel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * STAGE 10 — Thinking Tree persistence.
 *
 * These tests are about IDENTITY and HONESTY, not layout: a node keeps its id, a retry cannot
 * duplicate it, an unfinished step never comes back as finished, and one conversation's tree can
 * never appear in another's.
 */
class ThinkingTreeStoreTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private fun step(id: String, label: String = "read_file", state: String = "DONE", ok: Boolean = true) =
        ThinkingTreeStore.Step(
            nodeId = id,
            turnId = "t1",
            label = label,
            detail = "detail for " + id,
            ok = ok,
            state = state,
            atMs = 1_000L,
        )

    @Test
    fun `a step round trips with its identity and state`() {
        val root = tmp.newFolder("chat")
        assertTrue(ThinkingTreeStore.appendTo(root, "p1", "c1", step("r1c1")))
        val loaded = ThinkingTreeStore.loadFrom(root, "p1", "c1")
        assertEquals(1, loaded.size)
        assertEquals("r1c1", loaded[0].nodeId)
        assertEquals("t1", loaded[0].turnId)
        assertEquals("DONE", loaded[0].state)
        assertEquals(1_000L, loaded[0].atMs)
        assertFalse(loaded[0].interrupted)
    }

    @Test
    fun `appending the same node id twice does not duplicate it`() {
        val root = tmp.newFolder("chat")
        ThinkingTreeStore.appendTo(root, "p1", "c1", step("r1c1"))
        ThinkingTreeStore.appendTo(root, "p1", "c1", step("r1c1"))
        ThinkingTreeStore.appendTo(root, "p1", "c1", step("r1c1"))
        assertEquals(1, ThinkingTreeStore.loadFrom(root, "p1", "c1").size)
    }

    @Test
    fun `a repeated write updates the node instead of adding one`() {
        val root = tmp.newFolder("chat")
        ThinkingTreeStore.appendTo(root, "p1", "c1", step("r1c1", state = "ACTIVE"))
        ThinkingTreeStore.appendTo(root, "p1", "c1", step("r1c1", state = "DONE"))
        val loaded = ThinkingTreeStore.loadFrom(root, "p1", "c1")
        assertEquals(1, loaded.size)
        assertEquals("DONE", loaded[0].state)
    }

    @Test
    fun `order is preserved across a reload`() {
        val root = tmp.newFolder("chat")
        listOf("r1c1", "r1c2", "r2c1").forEach { ThinkingTreeStore.appendTo(root, "p1", "c1", step(it)) }
        assertEquals(
            listOf("r1c1", "r1c2", "r2c1"),
            ThinkingTreeStore.loadFrom(root, "p1", "c1").map { it.nodeId },
        )
    }

    @Test
    fun `two conversations never share a tree`() {
        val root = tmp.newFolder("chat")
        ThinkingTreeStore.appendTo(root, "p1", "c1", step("r1c1"))
        ThinkingTreeStore.appendTo(root, "p1", "c2", step("r9c9"))
        assertEquals(listOf("r1c1"), ThinkingTreeStore.loadFrom(root, "p1", "c1").map { it.nodeId })
        assertEquals(listOf("r9c9"), ThinkingTreeStore.loadFrom(root, "p1", "c2").map { it.nodeId })
    }

    @Test
    fun `two projects never share a tree`() {
        val root = tmp.newFolder("chat")
        ThinkingTreeStore.appendTo(root, "p1", "c1", step("r1c1"))
        assertTrue(ThinkingTreeStore.loadFrom(root, "p2", "c1").isEmpty())
    }

    @Test
    fun `a step still running when the app died restores as interrupted`() {
        val root = tmp.newFolder("chat")
        ThinkingTreeStore.appendTo(root, "p1", "c1", step("r1c1", state = "ACTIVE"))
        val loaded = ThinkingTreeStore.loadFrom(root, "p1", "c1").single()
        assertTrue("an unfinished step must not come back as finished", loaded.interrupted)
        val row = ThinkingTreeStore.toRows(listOf(loaded)).single() as ChatRowModel.ToolActivity
        assertFalse("an interrupted step must not render as a success", row.ok)
        assertTrue(row.detail.contains(ThinkingTreeStore.INTERRUPTED_MARK))
    }

    @Test
    fun `an unknown timestamp stays unknown`() {
        val root = tmp.newFolder("chat")
        ThinkingTreeStore.appendTo(root, "p1", "c1", step("r1c1").copy(atMs = 0L))
        assertEquals(0L, ThinkingTreeStore.loadFrom(root, "p1", "c1").single().atMs)
    }

    @Test
    fun `storage is bounded and keeps the newest steps`() {
        val root = tmp.newFolder("chat")
        repeat(ThinkingTreeStore.MAX_STEPS + 25) { i ->
            ThinkingTreeStore.appendTo(root, "p1", "c1", step("n" + i))
        }
        val loaded = ThinkingTreeStore.loadFrom(root, "p1", "c1")
        assertEquals(ThinkingTreeStore.MAX_STEPS, loaded.size)
        assertEquals("n" + (ThinkingTreeStore.MAX_STEPS + 24), loaded.last().nodeId)
    }

    @Test
    fun `a missing file is an empty tree`() {
        val root = tmp.newFolder("chat")
        assertTrue(ThinkingTreeStore.loadFrom(root, "p1", "nope").isEmpty())
    }

    @Test
    fun `a corrupt file is an empty tree and never invents steps`() {
        val root = tmp.newFolder("chat")
        ThinkingTreeStore.appendTo(root, "p1", "c1", step("r1c1"))
        ThinkingTreeStore.fileFor(root, "p1", "c1").writeText("{not json at all")
        assertTrue(ThinkingTreeStore.loadFrom(root, "p1", "c1").isEmpty())
    }

    @Test
    fun `a record without a node id is skipped rather than guessed`() {
        val root = tmp.newFolder("chat")
        val f = ThinkingTreeStore.fileFor(root, "p1", "c1")
        f.parentFile?.mkdirs()
        f.writeText("""[{"label":"no id here"},{"nodeId":"r1c1","label":"ok"}]""")
        val loaded = ThinkingTreeStore.loadFrom(root, "p1", "c1")
        assertEquals(1, loaded.size)
        assertEquals("r1c1", loaded[0].nodeId)
    }

    @Test
    fun `a blocked write reports failure`() {
        val root = tmp.newFolder("chat")
        val target = ThinkingTreeStore.fileFor(root, "p1", "c1")
        target.mkdirs()
        File(target, "keep.txt").writeText("x")
        assertFalse(ThinkingTreeStore.appendTo(root, "p1", "c1", step("r1c1")))
    }

    @Test
    fun `restored steps are merged before the reply they served`() {
        val rows = listOf<ChatRowModel>(
            ChatRowModel.User("do it"),
            ChatRowModel.AssistantStream("done", cursorOn = false),
        )
        val merged = ThinkingTreeStore.mergeRows(rows, listOf(step("r1c1")))
        assertEquals(3, merged.size)
        assertTrue(merged[1] is ChatRowModel.ToolActivity)
        assertTrue(merged[2] is ChatRowModel.AssistantStream)
    }

    @Test
    fun `a conversation with no reply yet hides its steps`() {
        // OWNER REPORT (2026-11-24): a fresh chat and a provider switch both showed a thinking tree
        // before anything was asked. Steps are still STORED - that is the persistence - they are just
        // not rendered until there is an assistant row for them to belong to.
        val rows = listOf<ChatRowModel>(ChatRowModel.User("do it"))
        val merged = ThinkingTreeStore.mergeRows(rows, listOf(step("r1c1")))
        assertEquals(1, merged.size)
        assertTrue(merged.none { it is ChatRowModel.ToolActivity })
    }

    @Test
    fun `no steps leaves the restored rows untouched`() {
        val rows = listOf<ChatRowModel>(ChatRowModel.User("hi"), ChatRowModel.AssistantStream("yo", cursorOn = false))
        assertEquals(rows, ThinkingTreeStore.mergeRows(rows, emptyList()))
    }

    @Test
    fun `node ids survive a reload unchanged`() {
        val root = tmp.newFolder("chat")
        val ids = listOf("r1c1", "r1c2", "r1c3")
        ids.forEach { ThinkingTreeStore.appendTo(root, "p1", "c1", step(it)) }
        assertEquals(ids, ThinkingTreeStore.loadFrom(root, "p1", "c1").map { it.nodeId })
        // A second restore must return the SAME ids, not renumber them by position.
        assertEquals(ids, ThinkingTreeStore.loadFrom(root, "p1", "c1").map { it.nodeId })
    }
}
