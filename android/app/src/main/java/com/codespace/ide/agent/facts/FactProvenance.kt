package com.codespace.ide.agent.facts

import java.io.File

/**
 * STAGE 4 (2026-11-19) — temporal provenance for a saved fact.
 *
 * Five times exist around a fact and they are NOT interchangeable:
 *  - [conversationStartedAt] when the conversation began (v3 header);
 *  - [sourceConversationAt] the same instant, kept as the value facts are dated by;
 *  - [sourceMessageAt] when the source message was first observed by persistence;
 *  - the fact's own createdAt / updatedAt (FactsStore);
 *  - supersededAt, written only by Stage 5.
 *
 * The rule this type enforces is that an unknown time is 0 and stays 0. Nothing here falls back to
 * "now", to createdAt, to a file's modification time, or to a transcript rewrite time.
 */
enum class TimeSource {
    /** The conversation date came from a real Transcript v3 header record. */
    TRANSCRIPT_VERIFIED,

    /** A date the user stated explicitly. Never inferred from message text. */
    USER_STATED,

    /** No reliable provenance: dates stay unknown. */
    UNKNOWN;

    companion object {
        fun parse(raw: String?): TimeSource =
            entries.firstOrNull { it.name == raw?.trim()?.uppercase() } ?: UNKNOWN
    }
}

/**
 * What a save path knows about where a fact came from.
 *
 * [NONE] is the honest default: a manual fact, an imported fact or a tool call with no transcript
 * behind it is UNLINKED, not "linked to whatever conversation happened to be open".
 */
data class FactProvenance(
    val sourceConversationId: String? = null,
    val conversationStartedAt: Long = 0L,
    val sourceConversationAt: Long = 0L,
    val sourceMessageId: String? = null,
    val sourceMessageAt: Long = 0L,
    val timeSource: TimeSource = TimeSource.UNKNOWN,
) {
    companion object {
        val NONE = FactProvenance()

        /**
         * Build provenance from a decoded transcript and an OPTIONAL source message id.
         *
         * [com.codespace.ide.chat.ChatTranscript.DecodedTranscript] is passed as primitives on
         * purpose: this package must not depend on the chat package's internals, and a caller that
         * only has a header can still produce an honest conversation date.
         */
        fun fromTranscript(
            conversationId: String?,
            version: String,
            headerStartedAtMs: Long,
            messageId: String?,
            messageSentAtMs: Long,
            messageFound: Boolean,
            messageIdIsSynthetic: Boolean,
        ): FactProvenance {
            // A v1/v2 file has no header record, so it has no verified conversation date. The
            // absence is preserved as 0 rather than back-filled from the first message.
            val verifiedStart = if (version == "v3" && headerStartedAtMs > 0L) headerStartedAtMs else 0L
            // A synthetic legacy id identifies the row for de-duplication but was never in an
            // original record, so it can never be transcript-verified source identity.
            val verifiedMessageId =
                messageId?.takeIf { messageFound && it.isNotBlank() && !messageIdIsSynthetic }
            val verifiedMessageAt =
                if (messageFound && verifiedMessageId != null && messageSentAtMs > 0L) messageSentAtMs else 0L
            return FactProvenance(
                sourceConversationId = conversationId?.takeIf { it.isNotBlank() },
                conversationStartedAt = verifiedStart,
                sourceConversationAt = verifiedStart,
                sourceMessageId = verifiedMessageId,
                sourceMessageAt = verifiedMessageAt,
                timeSource = if (verifiedStart > 0L) TimeSource.TRANSCRIPT_VERIFIED else TimeSource.UNKNOWN,
            )
        }
    }
}

/**
 * STAGE 4 — which conversation the CURRENT tool call belongs to.
 *
 * Claimed by the chat panel exactly like [com.codespace.ide.agent.MemoryScopeState], so a save made
 * by Copilot can be linked to the conversation that asked for it without threading a conversation
 * id through five layers. If nothing is claimed, the result is [FactProvenance.NONE]: an unlinked
 * fact, never a guessed one.
 */
object FactSourceContext {

    @Volatile
    private var conversationId: String = ""

    @Volatile
    private var projectId: String = ""

    @Volatile
    private var transcriptRoot: File? = null

    fun claim(conversationId: String, projectId: String?, transcriptRoot: File?) {
        this.conversationId = conversationId
        this.projectId = projectId ?: ""
        this.transcriptRoot = transcriptRoot
    }

    fun clear() {
        conversationId = ""
        projectId = ""
        transcriptRoot = null
    }

    /**
     * STAGE 7 — provenance for a specific USER message, resolved by CONTENT and only when the match
     * is unique. A repeated sentence is ambiguous, and an ambiguous match is reported as no match:
     * an unset sourceMessageId is a fact we can state honestly, a wrong one is not.
     */
    fun currentForUserMessage(text: String): FactProvenance {
        val base = current()
        if (base == FactProvenance.NONE) return base
        val root = transcriptRoot ?: return base
        val conv = conversationId
        if (conv.isBlank() || text.isBlank()) return base
        return try {
            val file = File(File(root, com.codespace.ide.chat.TranscriptFiles.safeSegment(projectId)), conv + ".txt")
            if (!file.isFile) return base
            val decoded = com.codespace.ide.chat.ChatTranscript.decode(file.readText())
            val wanted = text.trim().replace(Regex("\\s+"), " ")
            val hits = decoded.messages.filter { it.role == "user" && it.text.trim().replace(Regex("\\s+"), " ") == wanted }
            if (hits.size != 1) return base
            val m = hits.single()
            if (m.syntheticId || m.id.isBlank()) return base
            base.copy(
                sourceMessageId = m.id,
                sourceMessageAt = m.sentAtMs,
                // A message time that was never observed stays 0 rather than becoming "now".
                timeSource = base.timeSource,
            )
        } catch (_: Throwable) {
            base
        }
    }

    /**
     * Resolve provenance for the claimed conversation. [messageId] is optional: the tool call does
     * not know which message caused it, and inventing one would be worse than leaving it unset.
     */
    fun current(messageId: String? = null): FactProvenance {
        val root = transcriptRoot ?: return FactProvenance.NONE
        val conv = conversationId
        val proj = projectId
        if (conv.isBlank()) return FactProvenance.NONE
        return try {
            val file = File(File(root, com.codespace.ide.chat.TranscriptFiles.safeSegment(proj)), conv + ".txt")
            if (!file.isFile) return FactProvenance.NONE
            val decoded = com.codespace.ide.chat.ChatTranscript.decode(file.readText())
            val message = messageId?.let { id -> decoded.messages.firstOrNull { it.id == id } }
            FactProvenance.fromTranscript(
                conversationId = conv,
                version = decoded.version,
                headerStartedAtMs = decoded.meta?.startedAtMs ?: 0L,
                messageId = messageId,
                messageSentAtMs = message?.sentAtMs ?: 0L,
                messageFound = message != null,
                messageIdIsSynthetic = message?.syntheticId ?: (messageId?.startsWith("legacy-") == true),
            )
        } catch (_: Throwable) {
            FactProvenance.NONE
        }
    }
}

/**
 * STAGE 6 — the instant a fact is ABOUT, as an extension so the data class stays a data class.
 *
 * Precedence is deliberate: a transcript-verified conversation date beats any other date, an
 * unknown stays unknown, and `createdAt` is used only as a last resort because it is the save time,
 * not the time the fact describes.
 */
fun Fact.factTime(): Long = when {
    sourceConversationAt > 0L -> sourceConversationAt
    sourceMessageAt > 0L -> sourceMessageAt
    createdAt > 0L -> createdAt
    else -> 0L
}

/** True when [factTime] came from a transcript header rather than from the save path. */
fun Fact.hasVerifiedTime(): Boolean =
    timeSource == TimeSource.TRANSCRIPT_VERIFIED && sourceConversationAt > 0L

/**
 * STAGE 11 — the date a user may be SHOWN. 0 means genuinely unknown.
 *
 * Deliberately different from [factTime], which falls back to createdAt so ordering always has a
 * key: a save time is fine for sorting and is never a conversation date.
 */
fun Fact.knownTime(): Long = when {
    sourceConversationAt > 0L -> sourceConversationAt
    sourceMessageAt > 0L -> sourceMessageAt
    else -> 0L
}
