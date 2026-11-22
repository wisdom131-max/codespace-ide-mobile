package com.codespace.ide.engine

/**
 * PROJECT NINE 9.21 (catalog 9) - hand work to several peers with a cap, not to all of them.
 *
 * Delegation was fan-out to every configured peer, which multiplies cost by the number of peers
 * and makes a five-peer setup unusable on a phone. A mesh assigns a bounded number and rotates, so
 * the same peers take turns instead of all answering at once.
 *
 * Duplicates are collapsed by id: the same peer listed twice must not receive the same task twice.
 */
object AgentMesh {

    data class Peer(val id: String, val role: String)

    data class Assignment(val peer: Peer, val task: String)

    fun fanOut(peers: List<Peer>, task: String, maxFanout: Int = 3): List<Assignment> {
        if (task.isBlank() || maxFanout <= 0) return emptyList()
        return peers.distinctBy { it.id }.take(maxFanout).map { Assignment(it, task) }
    }

    fun nextRoundRobin(peers: List<Peer>, round: Int): Peer? {
        val unique = peers.distinctBy { it.id }
        if (unique.isEmpty() || round < 0) return null
        return unique[round % unique.size]
    }
}
