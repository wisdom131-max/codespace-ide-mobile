package com.codespace.ide.chat.ui

/**
 * UI-model rows the chat renderer receives. The panel maps engine events and
 * session state into these; the renderer never sees transport details.
 */
sealed interface ChatRowModel {
    data class User(val text: String) : ChatRowModel

    /**
     * What a stream row IS, which the tree needs and the transcript did not say before.
     *
     * PROGRESS is the narration a model writes before it acts ("Reading the manifest"). ANSWER is
     * the reply. They looked identical, so grouping treated mid-task narration as the end of the
     * turn and everything after it escaped the tree. Default ANSWER keeps every existing call site
     * honest: a stream that does not say it is progress IS the answer.
     */
    enum class StreamKind { PROGRESS, ANSWER }

    data class AssistantStream(
        val text: String,
        val cursorOn: Boolean,
        val kind: StreamKind = StreamKind.ANSWER,
        /**
         * STAGE 7 (Checkpoint 0.5): which logical reply this row belongs to.
         *
         * Empty on a row restored from a transcript written before ids existed; the panel then
         * falls back to the old text rule. A non-empty id is authoritative: the same id means the
         * same answer being extended, a different id means a different answer.
         */
        val replyId: String = "",
        /**
         * STAGE 8 (Checkpoint 0.5): this reply did not end on its own terms.
         *
         * Set on a restored reply whose stored state says it was cut off, cancelled, failed or
         * still streaming when the app closed. The text is kept and the reader is told, instead of
         * a half answer being shown as if it had finished.
         */
        val interrupted: Boolean = false,
    ) : ChatRowModel
    /**
     * OWNER REPORT (2026-11-23): the thinking tree's body was ALWAYS empty. The row was built with
     * a duration and no steps, and the renderer looked for notes AFTER the row - but this row is
     * appended at the END of the transcript, so there was never anything after it. The steps are
     * now carried on the row itself.
     */
    /**
     * VS CODE'S CONVERSATION PROGRESS (owner decision 2026-11-24: "use exactly vs codes rendering
     * for the tree leave nothing out").
     *
     * Reasoning and tool calls are ONE collapsed section per turn, not a separate tree beside the
     * transcript. [tree] is the whole turn's work; [liveLabel] is the activity happening right now,
     * which the collapsed header names while running; [running] keeps the header live until the
     * turn settles. Nothing is rendered until the section is opened.
     */
    data class Thinking(
        val seconds: Int,
        val steps: List<String> = emptyList(),
        val tree: List<TreeStep> = emptyList(),
        val liveLabel: String = "",
        val running: Boolean = false,
    ) : ChatRowModel
    data class Failed(val verdict: String, val detail: String, val canRetry: Boolean) : ChatRowModel
    object NoCredits : ChatRowModel
    object Offline : ChatRowModel
    data class OverBudget(val estimate: String) : ChatRowModel
    /** WP6: visible, honest system note (e.g. key switched on opt-in failover). */
    /**
     * STAGE 9 (Checkpoint 0.5): WHAT a note is, instead of guessing from its wording.
     *
     * The thinking tree used to take every note and then remove two literal sentences by string
     * match, so any new notice whose wording was not on that list appeared inside the tree as if
     * the copilot had done it. A note now says what it is.
     */
    enum class NoticeKind {
        /** About the app or the conversation: saved, not sent twice, could not save. */
        APP_NOTICE,
        /** The provider or the engine speaking about the reply itself. */
        ENGINE_NOTICE,
        /** Real work: a tool plan, a tool verdict, a refusal, a skip. Only these become steps. */
        WORK_STEP,
    }

    data class InfoNote(
        val text: String,
        val kind: NoticeKind = NoticeKind.APP_NOTICE,
    ) : ChatRowModel

    /**
     * One step the assistant took: the tool and what it did. Rendered as a compact line with a
     * chevron for the detail and an x to dismiss, which is the shape VS Code gives its tool
     * activity in the transcript. Kept apart from InfoNote so a note the user must read can
     * never be dismissed by the same gesture.
     */
    /**
     * One step the assistant took. [id] is assigned once, when the step is created, so the tree can
     * collapse and expand by identity instead of by position — the old behaviour reset the group on
     * every streaming update because the key was an index.
     */
    data class ToolActivity(
        val label: String,
        val detail: String,
        val id: String = "",
        /** The executor's verdict. Defaults true so existing producers stay valid. */
        val ok: Boolean = true,
    ) : ChatRowModel

    /**
     * 2026-11-11: the sources a research answer used, as tappable rows. Additive: it sits AFTER the
     * answer it belongs to and never inside the Thinking Tree, so the tree's grouping rules are
     * untouched (a Sources row ends an activity run exactly like an answer row does).
     */
    data class Sources(val items: List<com.codespace.ide.agent.research.ResearchSources.Item>) : ChatRowModel

    data class Attachments(val chips: List<AttachmentChipModel>) : ChatRowModel
}

/**
 * One step inside a turn's progress section. [turnId] groups steps into turns, [id] is stable so a
 * step can expand and collapse by identity, and [detail] is what the step actually did.
 */
data class TreeStep(
    val id: String,
    val turnId: String,
    val label: String,
    val detail: String = "",
    val ok: Boolean = true,
)
