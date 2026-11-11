package com.codespace.ide.editor

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.withTimeoutOrNull

/**
 * VN CODE COMMAND DISPATCHER (owner request 2026-11-11) - the native replacement for VS Code's
 * `run_vscode_command`, whose hidden reason was "internal command ids have no mobile equivalent.
 * The real actions here are separate tools, not string ids" (ToolRegistry, run_vscode_command).
 *
 * That reason was right about STRING IDS and wrong about the value of a compound action: VS Code
 * gets real mileage from "save all, then format", because the model does not have to guess which
 * separate tool to call. So this keeps the useful half and drops the dangerous half:
 *
 *   - an EXPLICIT ALLOWLIST ([VnCommand]) - never an arbitrary id, never a prefix match;
 *   - one defined argument shape per command, validated before dispatch ([VncodeCommands.parse]);
 *   - the action itself is the app's own existing one, reached by the same function the toolbar and
 *     the keyboard use. Nothing is re-implemented here.
 *
 * WHY A BUS: the actions live in EditorPane's composition state (tabs, find bar, go-to-line). A tool
 * executes outside the composition, so it asks through one registered sink - the same shape as
 * EditorInsertBus / EditorUndoBus / ChatHandoff. `attached` is the honesty bit: with no editor
 * mounted the tool says "no editor is open" instead of pretending it ran.
 */
enum class VnCommand(val id: String, val summary: String) {
    SAVE("vncode.command.save", "Save the active file"),
    SAVE_ALL("vncode.command.saveAll", "Save every modified file"),
    FORMAT("vncode.command.format", "Format the active file with its language formatter"),
    FIND("vncode.command.find", "Open Find, optionally with a query"),
    GO_TO_LINE("vncode.command.goToLine", "Open Go to Line, optionally at a line");

    companion object {
        fun from(id: String): VnCommand? = entries.firstOrNull { it.id == id.trim() }
        /** The allowlist, in the one place a reader can audit it. */
        val ids: List<String> get() = entries.map { it.id }
    }
}

/** One command's answer. Never a bare boolean: the model needs the sentence. */
data class CommandOutcome(val ok: Boolean, val message: String) {
    companion object {
        fun ok(message: String) = CommandOutcome(true, message)
        fun failed(message: String) = CommandOutcome(false, message)
    }
}

/** A parsed, already-validated request. Constructing one is only possible through parse(). */
data class CommandRequest(val command: VnCommand, val query: String?, val line: Int?)

object VncodeCommands {

    sealed interface Parse {
        data class Ready(val request: CommandRequest) : Parse
        data class Rejected(val reason: String) : Parse
    }

    private const val MAX_QUERY = 500

    /**
     * Validate raw tool arguments. Unknown ids are REJECTED with the allowlist printed, not
     * "silently ignored": a model that asked for `vncode.command.deleteEverything` must learn why
     * nothing happened, and the transcript is the only place it can learn it.
     */
    fun parse(commandRaw: String?, queryRaw: String?, lineRaw: Int): Parse {
        val id = commandRaw?.trim().orEmpty()
        if (id.isEmpty()) {
            return Parse.Rejected("No command given. Allowed: " + VnCommand.ids.joinToString(", ") + ".")
        }
        val cmd = VnCommand.from(id)
            ?: return Parse.Rejected(
                "'" + id + "' is not an allowed command. Allowed: " + VnCommand.ids.joinToString(", ") + "."
            )
        val query = queryRaw?.trim()?.takeIf { it.isNotEmpty() }
        if (query != null && query.length > MAX_QUERY) {
            return Parse.Rejected("The query is longer than " + MAX_QUERY + " characters.")
        }
        // A line of 0 or less cannot be scrolled to; refuse rather than clamp silently.
        if (lineRaw != 0 && lineRaw < 0) return Parse.Rejected("line must be 1 or greater.")
        val line = lineRaw.takeIf { it > 0 }
        return when (cmd) {
            VnCommand.FIND -> if (query != null && line != null) {
                Parse.Rejected("find takes a query or a line, not both.")
            } else {
                Parse.Ready(CommandRequest(cmd, query, line))
            }
            VnCommand.GO_TO_LINE -> Parse.Ready(CommandRequest(cmd, null, line))
            VnCommand.SAVE, VnCommand.SAVE_ALL, VnCommand.FORMAT ->
                if (query != null || line != null) {
                    Parse.Rejected(cmd.id + " takes no arguments.")
                } else {
                    Parse.Ready(CommandRequest(cmd, null, null))
                }
        }
    }
}

object EditorCommandBus {

    private class Pending(val request: CommandRequest, val reply: CompletableDeferred<CommandOutcome>)

    private val channel = Channel<Pending>(Channel.UNLIMITED)

    /** True while an editor pane is mounted and collecting. Read by the tool to answer honestly. */
    @Volatile
    var attached: Boolean = false
        private set

    /** 5s: long enough for a formatter round trip, short enough that a stuck UI cannot hang a turn. */
    private const val TIMEOUT_MS = 5_000L

    suspend fun run(request: CommandRequest): CommandOutcome {
        if (!attached) {
            return CommandOutcome.failed(
                "No editor is open, so " + request.command.id + " could not run. Open a file first."
            )
        }
        val reply = CompletableDeferred<CommandOutcome>()
        channel.send(Pending(request, reply))
        return withTimeoutOrNull(TIMEOUT_MS) { reply.await() }
            ?: CommandOutcome.failed(
                request.command.id + " was sent to the editor but it did not answer within 5s."
            )
    }

    /** Called once by the mounted editor pane; runs until the composition leaves. */
    suspend fun serve(handler: (CommandRequest) -> CommandOutcome) {
        attached = true
        try {
            for (pending in channel) {
                pending.reply.complete(
                    try {
                        handler(pending.request)
                    } catch (e: Exception) {
                        CommandOutcome.failed(pending.request.command.id + " failed: " + (e.message ?: e.javaClass.simpleName))
                    }
                )
            }
        } finally {
            attached = false
        }
    }
}
