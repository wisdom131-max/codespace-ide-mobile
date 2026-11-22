package com.codespace.ide.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AgentMeshTest {

    private val peers = listOf(
        AgentMesh.Peer("a", "reader"),
        AgentMesh.Peer("b", "writer"),
        AgentMesh.Peer("c", "checker"),
        AgentMesh.Peer("d", "extra"),
    )

    @Test
    fun theFanoutIsCapped() {
        assertEquals(3, AgentMesh.fanOut(peers, "find the bug", maxFanout = 3).size)
    }

    @Test
    fun aPeerListedTwiceGetsOneTask() {
        val dupes = listOf(AgentMesh.Peer("a", "reader"), AgentMesh.Peer("a", "reader"))
        assertEquals(1, AgentMesh.fanOut(dupes, "task").size)
    }

    @Test
    fun anEmptyTaskAssignsNobody() {
        assertTrue(AgentMesh.fanOut(peers, "   ").isEmpty())
    }

    @Test
    fun roundRobinRotatesAndWraps() {
        assertEquals("a", AgentMesh.nextRoundRobin(peers, 0)?.id)
        assertEquals("d", AgentMesh.nextRoundRobin(peers, 3)?.id)
        assertEquals("a", AgentMesh.nextRoundRobin(peers, 4)?.id)
    }

    @Test
    fun noPeersIsNull() {
        assertNull(AgentMesh.nextRoundRobin(emptyList(), 0))
    }
}
