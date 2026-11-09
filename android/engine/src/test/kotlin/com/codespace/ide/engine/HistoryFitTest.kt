package com.codespace.ide.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Priority history fitting: the newest message always travels, a dropped question takes its answer
 * with it, oldest goes first, and budget 0 keeps today's behaviour exactly.
 */
class HistoryFitTest {

    private var n = 0
    private fun msg(role: ChatRole, text: String) =
        ChatMessage(id = "m" + (n++), role = role, text = text)

    /** A conversation of [turns] user/assistant pairs, each side [size] chars. */
    private fun turns(n: Int, size: Int): List<ChatMessage> =
        (1..n).flatMap { listOf(msg(ChatRole.USER, "u$it".padEnd(size, 'x')), msg(ChatRole.ASSISTANT, "a$it".padEnd(size, 'y'))) }

    @Test
    fun noBudgetKeepsTodaysBehaviour() {
        val h = turns(5, 100)
        val f = HistoryBudget.fit(h, HistoryMode.FULL, systemChars = 9_999, budgetChars = 0)
        assertEquals("nothing dropped", 0, f.droppedMessages)
        assertEquals(h, f.messages)
    }

    @Test
    fun theOldestTurnsGoFirst() {
        val h = turns(10, 100)   // 2,000 chars
        val f = HistoryBudget.fit(h, HistoryMode.FULL, systemChars = 1_000, budgetChars = 1_600)
        val texts = f.messages.map { it.text.trim() }
        assertTrue("newest survives: $texts", texts.any { it.startsWith("u10") })
        assertTrue("oldest is gone: $texts", texts.none { it.startsWith("u1x") || it.startsWith("u2x") })
        assertTrue("something was dropped", f.droppedMessages > 0)
        assertTrue(f.messages.sumOf { it.text.length } <= 600)
    }

    @Test
    fun theNewestMessageIsNeverDroppedForSize() {
        val h = listOf(msg(ChatRole.USER, "x".repeat(5_000)))
        val f = HistoryBudget.fit(h, HistoryMode.FULL, systemChars = 100, budgetChars = 500)
        assertEquals("kept anyway", 1, f.messages.size)
        assertEquals(5_000, f.messages.first().text.length)
    }

    @Test
    fun aDroppedQuestionTakesItsAnswerWithIt() {
        val h = turns(4, 500)
        val f = HistoryBudget.fit(h, HistoryMode.FULL, systemChars = 0, budgetChars = 1_100)
        val texts = f.messages.map { it.text.trim() }
        // Compare by TURN NUMBER: 'u4...' and 'a4...' are the same turn.
        val keptUsers = texts.filter { it.startsWith("u") }.map { it[1] }.toSet()
        val keptAnswers = texts.filter { it.startsWith("a") }.map { it[1] }.toSet()
        assertEquals("an answer only survives with its question", keptUsers, keptAnswers)
    }

    @Test
    fun theDropIsReportedNotHidden() {
        val f = HistoryBudget.fit(turns(6, 400), HistoryMode.FULL, systemChars = 400, budgetChars = 1_000)
        println("[fit] droppedMessages=" + f.droppedMessages + " droppedChars=" + f.droppedChars)
        assertTrue(f.droppedMessages >= 2)
        assertTrue(f.droppedChars >= 800)
    }
}
