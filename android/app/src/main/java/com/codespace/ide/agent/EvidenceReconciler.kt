package com.codespace.ide.agent

import java.security.MessageDigest

/**
 * PROJECT FOUR, STAGE 4.6 (2026-11-16): two observations of the same thing are compared, not averaged.
 *
 * A later read that disagrees with an earlier one is not automatically newer truth. It may be a real
 * change, a stale record, or a genuine contradiction that means neither observation can be trusted.
 * This decides which, using the facts already recorded: conversation, plan revision, order, and
 * whether a verified write happened in between.
 */
object EvidenceReconciler {

    enum class Verdict {
        /** The same answer twice, so the evidence agrees with itself. */
        SAME,
        /** A verified change happened in between, so the difference is the change, not a conflict. */
        LEGITIMATE_CHANGE,
        /** Different answers about the same thing with no write in between: neither is trustworthy. */
        CONTRADICTION,
        /** From another conversation or an older plan, so it cannot be compared as current evidence. */
        STALE,
        /** There is not enough recorded to say anything. */
        NOT_COMPARABLE,
    }

    data class Observation(
        val resource: String,
        val digest: String,
        val at: Long,
        val session: String,
        val revision: Int,
        val tool: String,
    )

    data class Finding(val verdict: Verdict, val note: String) {
        /** Only a contradiction blocks: a legitimate change is exactly what a write was for. */
        val blocksSatisfaction: Boolean get() = verdict == Verdict.CONTRADICTION
    }

    /** Bounded: only the last observation of each resource is remembered, up to a hard ceiling. */
    private const val CAP = 200
    private val last = LinkedHashMap<String, Observation>()

    @Synchronized
    fun clear() = last.clear()

    @Synchronized
    fun observed(): Map<String, Observation> = HashMap(last)

    fun digest(value: String): String {
        val normalized = value.trim()
        return MessageDigest.getInstance("SHA-256")
            .digest(normalized.toByteArray())
            .joinToString("") { "%02x".format(it) }.take(16)
    }

    /** The resource a tool's result describes, or null when the tool says nothing comparable. */
    fun resourceOf(tool: String, path: String?): String? {
        if (tool !in COMPARABLE) return null
        val raw = path?.trim().orEmpty()
        if (raw.isEmpty()) return null
        return raw.replace('\\', '/').trimEnd('/')
    }

    private val COMPARABLE = setOf(
        "read_file", "grep_search", "list_dir", "file_exists", "checksum", "zip_list",
        "git_status", "git_log", "git_diff",
    )

    /**
     * Compare a new observation with what was last seen for the same resource.
     */
    @Synchronized
    fun compare(
        resource: String,
        value: String,
        session: String,
        revision: Int,
        tool: String,
        verifiedWriteSince: Boolean,
        now: Long = System.currentTimeMillis(),
    ): Finding {
        val previous = last[resource]
        val current = Observation(resource, digest(value), now, session, revision, tool)
        put(resource, current)
        if (previous == null) {
            return Finding(Verdict.NOT_COMPARABLE, "this is the first observation of " + resource)
        }
        return when {
            previous.session.isNotBlank() && session.isNotBlank() && previous.session != session ->
                Finding(Verdict.STALE, "the earlier observation came from another conversation")
            previous.revision > 0 && revision > 0 && previous.revision != revision ->
                Finding(Verdict.STALE, "the earlier observation was made under an older plan")
            previous.digest == current.digest ->
                Finding(Verdict.SAME, "the same result was observed again for " + resource)
            verifiedWriteSince ->
                Finding(Verdict.LEGITIMATE_CHANGE, resource + " changed after a verified write, which is the change, not a conflict")
            else ->
                Finding(
                    Verdict.CONTRADICTION,
                    resource + " is described differently by two observations with no verified write between them (" +
                        previous.tool + " then " + tool + ")",
                )
        }
    }

    private fun put(resource: String, observation: Observation) {
        if (last.size >= CAP && !last.containsKey(resource)) {
            last.remove(last.keys.first())
        }
        last[resource] = observation
    }
}
