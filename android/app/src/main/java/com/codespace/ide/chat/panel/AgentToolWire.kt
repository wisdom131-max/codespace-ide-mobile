package com.codespace.ide.chat.panel

/**
 * Section 6 of the owner spec: the chat panel finally ACTS, and does it visibly.
 *
 * The decision layer is pure on purpose. Whether a reply contains a tool call, whether the
 * current mode is allowed to run it, how many rounds a single request may take, and what
 * the model is told afterward are all testable without Compose, Android, or a network.
 * Execution lives in AgentTools (which already runs inside the proot guest and already
 * announces filesystem changes); this object only decides.
 *
 * The wire is TEXT, not native function calling: the model is given the tool protocol in
 * its system instruction and emits <tool>{"name":...,"arguments":{...}}</tool>. That keeps
 * one protocol for OpenAI, Anthropic and Gemini instead of three function-calling dialects.
 */
internal data class ParsedToolCall(val name: String, val argumentsJson: String)

/** What to do with a finished reply. */
internal sealed interface ToolDecision {
    /** Plain answer: nothing to execute. */
    data class Answer(val text: String) : ToolDecision

    /** Execute these, then continue. [visibleText] is what the user should still see. */
    data class Run(val visibleText: String, val calls: List<ParsedToolCall>) : ToolDecision

    /** A call was asked for but refused before execution, with the reason shown. */
    data class Refused(val visibleText: String, val reason: String) : ToolDecision
}

internal object AgentToolWire {

    /**
     * Rounds per user message. A runaway agent loop is a real failure mode (each round is a
     * paid API call and possibly a destructive command), so it is capped and the cap is
     * announced to the model rather than silently enforced.
     */
    const val MAX_ROUNDS = 6

    private val CALL_REGEX =
        Regex("""<(tool|tool_call)>\s*(\{.*?\})\s*</\1>""", RegexOption.DOT_MATCHES_ALL)
    private val NAME_REGEX = Regex(""""name"\s*:\s*"([A-Za-z0-9_]+)"""")
    private val ARGS_REGEX = Regex(""""arguments"\s*:\s*(\{.*\})\s*$""", RegexOption.DOT_MATCHES_ALL)

    /**
     * Batch 1: READ-ONLY tools. Ask mode gets these and nothing else.
     *
     * Why Ask gained tools: the mode's own promise is "answer questions about the open
     * project", and a workspace block is a LISTING, not file contents. Without read
     * tools, Ask could only ever answer from filenames and would have to guess at file
     * bodies, which is precisely the dishonesty the workspace block exists to prevent.
     * Nothing here can write, execute or reach the network.
     */
    private val READ_ONLY_TOOLS = setOf("read_file", "list_files", "search_files")

    /** Tools each mode may run. Ask reads; Edit edits; Agent does everything but secrets. */
    fun allowsTool(mode: ChatMode, toolName: String): Boolean = when (mode) {
        ChatMode.ASK -> toolName in READ_ONLY_TOOLS
        // Edit may touch files but must not execute anything.
        ChatMode.EDIT -> toolName in setOf("read_file", "write_file", "list_files", "search_files")
        ChatMode.AGENT -> toolName != "save_secret" && toolName != "get_secret"
    }

    /** Every mode now has at least one permitted tool, so the protocol is always sent. */
    fun modeRunsTools(mode: ChatMode): Boolean = true

    /** Tool calls in [text], in the order they appear. Unparseable blocks are skipped. */
    fun parse(text: String): List<ParsedToolCall> =
        CALL_REGEX.findAll(text).mapNotNull { m ->
            val body = m.groupValues[2]
            val name = NAME_REGEX.find(body)?.groupValues?.get(1) ?: return@mapNotNull null
            val args = ARGS_REGEX.find(body)?.groupValues?.get(1) ?: "{}"
            ParsedToolCall(name, args)
        }.toList()

    /** The reply with its tool blocks removed: what a human should actually read. */
    fun visibleText(text: String): String = CALL_REGEX.replace(text, "").trim()

    /**
     * Decide what a finished reply means.
     *
     * [round] is 1-based and counts completed exchanges in this request. At the cap the
     * calls are refused instead of executed: the honest stop, not a silent extra round.
     */
    fun decide(mode: ChatMode, reply: String, round: Int): ToolDecision {
        val calls = parse(reply)
        val visible = visibleText(reply)
        if (calls.isEmpty()) return ToolDecision.Answer(visible)

        if (!modeRunsTools(mode)) {
            return ToolDecision.Refused(
                visible,
                "Ask mode does not run tools. Switch to Agent to let it act.",
            )
        }
        if (round >= MAX_ROUNDS) {
            return ToolDecision.Refused(
                visible,
                "Stopped after $MAX_ROUNDS steps to avoid a loop. Send again to continue.",
            )
        }
        val blocked = calls.firstOrNull { !allowsTool(mode, it.name) }
        if (blocked != null) {
            val why = if (mode == ChatMode.ASK)
                "Ask mode only reads the project (read_file, list_files, search_files). " +
                    "Switch to Edit to propose changes or Agent to run them."
            else "${blocked.name} is not allowed in ${mode.label} mode."
            return ToolDecision.Refused(visible, why)
        }
        return ToolDecision.Run(visible, calls)
    }

    /**
     * The follow-up turn that tells the model what happened. Sent as a USER message because
     * a tool result is an instruction to continue, and every provider here accepts a user
     * turn - a native "tool" role would have to be spelled three different ways.
     */
    fun resultMessage(call: ParsedToolCall, output: String): String =
        "[tool result] " + call.name + " returned:\n" + output.trim() +
            "\n\nContinue the task. Use another <tool> block only if you still need to act."

    /**
     * The tool protocol, appended to the mode instruction so the model knows it may act and
     * in what format. Only the tools the mode permits are advertised: offering a command
     * tool to Edit and then refusing it would be a trap, not a boundary.
     */
    fun protocol(mode: ChatMode): String {
        val names = ALL_TOOLS.filter { allowsTool(mode, it) }
        if (names.isEmpty()) return ""
        return "\n\nYou can act on this project. To use a tool, emit exactly one block:\n" +
            "<tool>{\"name\":\"<tool>\",\"arguments\":{...}}</tool>\n" +
            "Available tools: " + names.joinToString(", ") + ".\n" +
            "Use relative paths inside the open project (the workspace context names the " +
            "ACTIVE root). After a tool runs you are given its " +
            "output and must continue until the task is finished. Never claim a command ran " +
            "or a file changed unless the tool result says so. Do not exceed " +
            MAX_ROUNDS + " tool steps for one request."
    }

    /** One line describing a call, for the approval card and the transcript. */
    fun summary(call: ParsedToolCall): String {
        val key = when (call.name) {
            "run_command" -> "command"
            "write_file", "read_file", "list_files", "search_files" -> "path"
            else -> null
        }
        val value = key?.let { k ->
            Regex("\"" + k + "\"\\s*:\\s*\"((?:[^\"\\\\]|\\\\.)*)\"").find(call.argumentsJson)
                ?.groupValues?.get(1)
        } ?: ""
        return if (value.isBlank()) call.name else call.name + ": " + firstLine(value)
    }

    /** First non-blank line, clipped: transcripts and cards must not carry a whole dump. */
    fun firstLine(text: String, max: Int = 120): String {
        val line = text.lineSequence().firstOrNull { it.isNotBlank() }?.trim() ?: ""
        return if (line.length <= max) line else line.take(max - 1) + "\u2026"
    }

    /** Every tool the chat agent may ask for (mode rules narrow this per call). */
    val ALL_TOOLS: List<String> = listOf(
        "run_command", "read_file", "write_file", "list_files", "search_files",
    )

    /** Shown in the transcript for each executed step, so the work is visible, not implied. */
    fun stepLabel(call: ParsedToolCall): String = when (call.name) {
        "run_command" -> "Running a command"
        "write_file" -> "Writing a file"
        "read_file" -> "Reading a file"
        "list_files" -> "Listing files"
        "search_files" -> "Searching the project"
        else -> call.name
    }
}
