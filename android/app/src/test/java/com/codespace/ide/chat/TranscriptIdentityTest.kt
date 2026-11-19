package com.codespace.ide.chat

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * STAGE 3.1 — persistent message identity.
 *
 * The invariant: an id belongs to a MESSAGE, not to a position. Everything below is one way of
 * moving a message around a list while it must keep the id it already had.
 */
class TranscriptIdentityTest {

    private fun u(t: String) = StoredMessage("user", t)
    private fun a(t: String) = StoredMessage("assistant", t)

    /** First save: every message gets an id. */
    private fun seed(list: List<StoredMessage>) = ChatTranscript.carryIdentity(list, emptyList(), "c", 1_000L)

    @Test
    fun `deleting a message does not change the survivors' ids`() {
        val first = seed(listOf(u("one"), a("two"), u("three")))
        val second = ChatTranscript.carryIdentity(listOf(first[0], first[2]), first, "c", 2_000L)
        assertEquals(first[0].id, second[0].id)
        assertEquals(first[2].id, second[1].id)
    }

    @Test
    fun `pruning the head does not renumber the rest`() {
        val first = seed(listOf(u("one"), a("two"), u("three"), a("four")))
        val pruned = ChatTranscript.carryIdentity(first.drop(2), first, "c", 2_000L)
        assertEquals(first[2].id, pruned[0].id)
        assertEquals(first[3].id, pruned[1].id)
    }

    @Test
    fun `reordering keeps every id with its message`() {
        val first = seed(listOf(u("one"), a("two"), u("three")))
        val reordered = ChatTranscript.carryIdentity(
            listOf(first[2], first[0], first[1]), first, "c", 2_000L,
        )
        assertEquals(first[2].id, reordered[0].id)
        assertEquals(first[0].id, reordered[1].id)
        assertEquals(first[1].id, reordered[2].id)
    }

    @Test
    fun `editing a message in place keeps its id`() {
        val first = seed(listOf(u("one"), a("two")))
        val edited = ChatTranscript.carryIdentity(listOf(u("one"), a("two, corrected")), first, "c", 2_000L)
        assertEquals(first[1].id, edited[1].id)
    }

    @Test
    fun `appending does not disturb existing ids`() {
        val first = seed(listOf(u("one"), a("two")))
        val grown = ChatTranscript.carryIdentity(first + u("three"), first, "c", 2_000L)
        assertEquals(first.map { it.id }, grown.take(2).map { it.id })
        assertNotEquals(grown[0].id, grown[2].id)
    }

    @Test
    fun `an edit after a deletion gets a NEW id rather than a dead one`() {
        val first = seed(listOf(u("one"), a("two"), u("three")))
        // "two" deleted AND "three" edited: the surviving edit must not inherit the deleted id
        val after = ChatTranscript.carryIdentity(listOf(first[0], u("three, edited")), first, "c", 2_000L)
        assertNotEquals(first[1].id, after[1].id)
        assertNotEquals(first[2].id, after[1].id)
        assertEquals(first[0].id, after[0].id)
    }

    @Test
    fun `duplicate ids in a file are detected and only one survives`() {
        val raw = "v3\nm\tm1\tuser\t1\t0\t5\t-\ta\nm\tm1\tassistant\t1\t0\t6\t-\tb\nm\tm2\tuser\t1\t0\t7\t-\tc\n"
        val d = ChatTranscript.decode(raw)
        assertEquals(2, d.messages.size)
        assertEquals(1, d.duplicateRecords)
    }

    @Test
    fun `ids stay unique across a full save cycle with a deletion`() {
        val first = seed(listOf(u("a"), u("b"), u("c")))
        val after = ChatTranscript.carryIdentity(listOf(first[0], first[2], u("d")), first, "c", 3_000L)
        assertEquals(3, after.map { it.id }.toSet().size)
    }

    @Test
    fun `legacy messages migrate deterministically and keep their flag`() {
        val raw = "v2\nuser\t1\t111\t-\told one\nassistant\t1\t222\t-\told two\n"
        val a1 = ChatTranscript.decode(raw)
        val a2 = ChatTranscript.decode(raw)
        assertEquals(a1.messages.map { it.id }, a2.messages.map { it.id })
        assertTrue(a1.messages.all { it.syntheticId && it.sentAtMs == 0L })
        // re-saving keeps the migrated ids and does not stamp a send time
        val resaved = ChatTranscript.carryIdentity(a1.messages, a1.messages, "c", 9_000L)
        assertEquals(a1.messages.map { it.id }, resaved.map { it.id })
        assertTrue(resaved.all { it.sentAtMs == 0L })
        assertTrue(resaved.all { it.syntheticId })
    }

    @Test
    fun `a message with no previous match is new, not a guess`() {
        val first = seed(listOf(u("one")))
        val after = ChatTranscript.carryIdentity(listOf(a("brand new")), first, "c", 5_000L)
        assertNotEquals(first[0].id, after[0].id)
        assertEquals(5_000L, after[0].sentAtMs)
    }

    @Test
    fun `surviving messages keep their first-observed time through a deletion`() {
        val first = seed(listOf(u("one"), a("two"), u("three")))
        val after = ChatTranscript.carryIdentity(listOf(first[0], first[2]), first, "c", 9_999L)
        assertEquals(first[0].sentAtMs, after[0].sentAtMs)
        assertEquals(first[2].sentAtMs, after[1].sentAtMs)
    }
}
