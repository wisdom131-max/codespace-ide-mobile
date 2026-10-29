package com.codespace.ide.chat.panel

/**
 * U3 - what the send control shows and does, as pure data.
 *
 * From the VS Code references: idle shows a send arrow (disabled-looking when the field
 * is empty); while a request runs it becomes a STOP square with a chevron beside it, and
 * the chevron opens "Stop and Send / Add to Queue / Steer with Message".
 *
 * ENGINE HONESTY (owner ruling): the queue and steer engines are not built. The menu is
 * real UI, but those two entries are shown DISABLED with a plain "Not available yet"
 * reason and do nothing. "Stop and Send" IS real, because it is cancel followed by a
 * normal send, both of which exist. Nothing here fakes behaviour.
 */
internal enum class SendMode {
    /** No setup yet: the control opens provider setup instead of sending. */
    SETUP,
    /** A model must be chosen first. */
    PICK_MODEL,
    /** Empty field, nothing to send. */
    IDLE_EMPTY,
    /** Has text, ready. */
    READY,
    /** A request is running and the field is empty: stop only. */
    STREAMING,
    /** A request is running and the user has typed: stop-and-send becomes meaningful. */
    STREAMING_WITH_TEXT,
}

internal enum class SendAction { SEND, STOP, OPEN_SETUP, OPEN_MODEL_PICKER, NONE }

internal enum class SendMenuEntry(val label: String) {
    STOP_AND_SEND("Stop and Send"),
    ADD_TO_QUEUE("Add to Queue"),
    STEER("Steer with Message"),
}

internal data class SendMenuItem(
    val entry: SendMenuEntry,
    val enabled: Boolean,
    /** Shown under a disabled entry. Null when enabled. */
    val reason: String?,
    /** VS Code's keyboard hint shown right-aligned; informational on a phone. */
    val shortcutHint: String?,
)

internal data class SendControlSpec(
    val mode: SendMode,
    /** What a tap on the main control does. */
    val tapAction: SendAction,
    /** True when the main control is drawn as the stop square. */
    val showsStop: Boolean,
    /** True when the chevron beside it is drawn. */
    val showsChevron: Boolean,
    /** The chevron menu, in the order of the reference (empty when no chevron). */
    val menu: List<SendMenuItem>,
    /** Accessibility / label text for the main control. */
    val label: String,
    /** The control looks dimmed because there is nothing to send. */
    val dimmed: Boolean,
)

internal object ChatSendState {

    const val NOT_AVAILABLE = "Not available yet"

    fun resolve(
        streaming: Boolean,
        needsSetup: Boolean,
        modelBlank: Boolean,
        hasText: Boolean,
    ): SendControlSpec {
        val mode = when {
            needsSetup -> SendMode.SETUP
            modelBlank -> SendMode.PICK_MODEL
            streaming && hasText -> SendMode.STREAMING_WITH_TEXT
            streaming -> SendMode.STREAMING
            hasText -> SendMode.READY
            else -> SendMode.IDLE_EMPTY
        }
        return when (mode) {
            SendMode.SETUP -> SendControlSpec(mode, SendAction.OPEN_SETUP, false, false, emptyList(), "Set up", false)
            SendMode.PICK_MODEL ->
                SendControlSpec(mode, SendAction.OPEN_MODEL_PICKER, false, false, emptyList(), "Pick model", false)
            SendMode.IDLE_EMPTY -> SendControlSpec(mode, SendAction.NONE, false, false, emptyList(), "Send", true)
            SendMode.READY -> SendControlSpec(mode, SendAction.SEND, false, false, emptyList(), "Send", false)
            SendMode.STREAMING, SendMode.STREAMING_WITH_TEXT -> SendControlSpec(
                mode = mode,
                tapAction = SendAction.STOP,
                showsStop = true,
                showsChevron = true,
                menu = streamingMenu(hasText),
                label = "Stop",
                dimmed = false,
            )
        }
    }

    /**
     * The reference order: Stop and Send, Add to Queue, Steer with Message.
     * Stop and Send needs text; queue and steer are engine work not built yet.
     */
    fun streamingMenu(hasText: Boolean): List<SendMenuItem> = listOf(
        SendMenuItem(
            SendMenuEntry.STOP_AND_SEND,
            enabled = hasText,
            reason = if (hasText) null else "Type a message first",
            shortcutHint = null,
        ),
        SendMenuItem(SendMenuEntry.ADD_TO_QUEUE, enabled = false, reason = NOT_AVAILABLE, shortcutHint = "Alt+Enter"),
        SendMenuItem(SendMenuEntry.STEER, enabled = false, reason = NOT_AVAILABLE, shortcutHint = "Enter"),
    )
}
