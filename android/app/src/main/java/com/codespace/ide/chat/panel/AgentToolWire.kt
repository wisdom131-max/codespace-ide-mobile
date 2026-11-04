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

    /**
     * 2026-11-04 (device finding, Stage 1): models served through a custom OpenAI-compatible
     * endpoint do not all speak the JSON dialect above. Qwen (and any Hermes-derived template:
     * Nous-Hermes, OpenHermes, many fine-tunes) emits an XML dialect instead:
     *
     *   <tool_call>
     *   <function=read_file>
     *   <parameter=path>
     *   wire-test/notes.txt
     *   </parameter>
     *   </function>
     *   </tool_call>
     *
     * That text was not matched by CALL_REGEX, so visibleText() left it untouched and the
     * transcript rendered the protocol as if the assistant had said it out loud. Parsing it
     * here - and stripping it in visibleText - is the whole fix; the model was asking for the
     * tool correctly in the dialect its template uses.
     */
    private val HERMES_CALL_REGEX = Regex(
        """<tool_call>\s*<function=([A-Za-z0-9_]+)>\s*(.*?)\s*</function>\s*</tool_call>""",
        RegexOption.DOT_MATCHES_ALL,
    )
    private val HERMES_PARAM_REGEX = Regex(
        """<parameter=([A-Za-z0-9_]+)>\s*(.*?)\s*</parameter>""",
        RegexOption.DOT_MATCHES_ALL,
    )

    /**
     * Protocol leftovers that carry no parseable call: an unclosed block, a stray half of one,
     * or a fragment split by a dropped chunk. Stripped from the visible answer so markup can
     * never be read as prose, and never turned into an execution.
     */
    private val PROTOCOL_FRAGMENT = Regex(
        """</?(tool_call|tool|function|parameter)[^>]*>""",
        RegexOption.IGNORE_CASE,
    )

    /** The first opening tag of either dialect: `<tool_call>`, `<tool>`, `<function=`, `<parameter=`. */
    private val PROTOCOL_OPENER = Regex(
        """<(tool_call|tool|function|parameter)[^>]*>""",
        RegexOption.IGNORE_CASE,
    )
    private val NAME_REGEX = Regex(""""name"\s*:\s*"([A-Za-z0-9_]+)"""")
    private val ARGS_KEY = Regex(""""arguments"\s*:\s*\{""")

    /**
     * Batch 1: READ-ONLY tools. Ask mode gets these and nothing else.
     *
     * Why Ask gained tools: the mode's own promise is "answer questions about the open
     * project", and a workspace block is a LISTING, not file contents. Without read
     * tools, Ask could only ever answer from filenames and would have to guess at file
     * bodies, which is precisely the dishonesty the workspace block exists to prevent.
     * Nothing here can write, execute or reach the network.
     */
    // PHASE 1 (2026-10-30): the hand-maintained sets that used to live here are gone.
    // ToolRegistry is now the single source of truth for which name a mode may run, so
    // the advertised list and the accepted list can never drift apart again.
    // Ask reads (the whole read-only set, not just three names); Edit adds writes but
    // still never executes; Agent adds execution and still never touches secrets.
    fun allowsTool(mode: ChatMode, toolName: String): Boolean =
        ToolRegistry.allows(mode, toolName)

    /** Every mode now has at least one permitted tool, so the protocol is always sent. */
    fun modeRunsTools(mode: ChatMode): Boolean = true

    /**
     * Batch 2: a write in EDIT mode is a PROPOSAL, not a write.
     *
     * Edit mode's own prompt promises "nothing is applied until the user accepts it", and
     * the app already has the machinery for exactly that (PendingChangesStore + the
     * in-editor AiReviewStrip). What was missing was the producer: the staged store had
     * zero callers, so Edit mode executed write_file straight to disk like Agent mode
     * while telling the model and the user it had not. This function is the decision that
     * separates the two: in EDIT the write is STAGED for review; in AGENT it executes
     * behind the approval gate exactly as before.
     */
    fun isStagedWrite(mode: ChatMode, toolName: String): Boolean =
        mode == ChatMode.EDIT && toolName == "write_file"

    /** Human line for a staged proposal: the file and the size, never "applied". */
    fun stagedNote(relPath: String, chars: Int): String =
        "Proposed edit staged: " + relPath + " (" + chars + " chars). Nothing has been written to " +
            "disk. Review it in the editor strip and Apply or Discard there."

    /**
     * Tool calls in [text], in the order they appear, in either dialect.
     *
     * Unparseable blocks are skipped - and because visibleText() strips the same spans, a
     * skipped block leaves nothing behind in the answer either.
     */
    fun parse(text: String): List<ParsedToolCall> {
        val found = ArrayList<Triple<Int, Int, ParsedToolCall>>()
        for (m in CALL_REGEX.findAll(text)) {
            val body = m.groupValues[2]
            val name = NAME_REGEX.find(body)?.groupValues?.get(1) ?: continue
            val args = jsonArgumentsFrom(body) ?: "{}"
            found.add(Triple(m.range.first, 0, ParsedToolCall(name, args)))
        }
        for (m in HERMES_CALL_REGEX.findAll(text)) {
            val name = m.groupValues[1]
            val args = hermesArguments(m.groupValues[2])
            found.add(Triple(m.range.first, 1, ParsedToolCall(name, args)))
        }
        return found.sortedWith(compareBy({ it.first }, { it.second })).map { it.third }
    }

    /**
     * The `arguments` object out of a JSON-dialect body, brace-balanced.
     *
     * The old regex was `\{.*\}` greedily anchored to the end of the body, so a reply of the
     * exact shape the protocol documents - {"name":..,"arguments":{..}} - handed the dispatcher
     * `{..}}`: one closing brace too many, because the greedy match swallowed the call's own
     * terminator. Balanced counting is the only version that survives a nested object (a patch
     * body, a JSON string argument) without guessing.
     */
    private fun jsonArgumentsFrom(body: String): String? {
        val key = ARGS_KEY.find(body) ?: return null
        val start = key.range.last
        var depth = 0
        var inString = false
        var escaped = false
        for (i in start until body.length) {
            val c = body[i]
            if (inString) {
                when {
                    escaped -> escaped = false
                    c == '\\' -> escaped = true
                    c == '"' -> inString = false
                }
                continue
            }
            when (c) {
                '"' -> inString = true
                '{' -> depth++
                '}' -> {
                    depth--
                    if (depth == 0) return body.substring(start, i + 1)
                }
            }
        }
        return null
    }

    /**
     * XML parameters -> the JSON object the dispatcher already accepts. Values are kept
     * verbatim except for JSON escaping: a file path or a patch body must not be altered.
     */
    internal fun hermesArguments(body: String): String {
        val sb = StringBuilder("{")
        var first = true
        for (p in HERMES_PARAM_REGEX.findAll(body)) {
            val key = p.groupValues[1]
            val value = p.groupValues[2]
            if (!first) sb.append(',')
            first = false
            sb.append(jsonString(key)).append(':').append(jsonString(value))
        }
        return sb.append('}').toString()
    }

    private fun jsonString(value: String): String {
        val sb = StringBuilder(value.length + 2).append('"')
        for (c in value) {
            when (c) {
                '"' -> sb.append("\\\"")
                '\\' -> sb.append("\\\\")
                '\n' -> sb.append("\\n")
                '\r' -> sb.append("\\r")
                '\t' -> sb.append("\\t")
                else -> if (c < ' ') sb.append("\\u").append(
                    c.code.toString(16).padStart(4, '0')) else sb.append(c)
            }
        }
        return sb.append('"').toString()
    }

    /**
     * The reply with its tool blocks removed: what a human should actually read.
     *
     * Both complete dialects are removed whole; anything left of a protocol tag is removed
     * too, because an unclosed block is still protocol, not an answer.
     */
    fun visibleText(text: String): String {
        var out = CALL_REGEX.replace(text, "")
        out = HERMES_CALL_REGEX.replace(out, "")
        // An OPENING protocol tag that no complete block consumed is the head of a truncated or
        // malformed call. Everything from it onward is protocol: cut the tail rather than leave
        // its parameter values ("notes.txt") standing as if the assistant had written them.
        val tail = PROTOCOL_OPENER.find(out)
        if (tail != null) out = out.take(tail.range.first)
        out = PROTOCOL_FRAGMENT.replace(out, "")
        return out.replace(Regex("""\n{3,}"""), "\n\n").trim()
    }

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
        val block = ToolRegistry.promptBlock(mode)
        if (block.isEmpty()) return ""
        return block +
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

    /** Every tool the chat agent may ask for. Exposure per mode lives in ToolRegistry. */
    val ALL_TOOLS: List<String> get() = ToolRegistry.names()

    /** Shown in the transcript for each executed step, so the work is visible, not implied. */
    fun stepLabel(call: ParsedToolCall): String = when (call.name) {
        "run_command" -> "Running a command"
        "write_file" -> "Writing a file"
        "read_file" -> "Reading a file"
        "list_files" -> "Listing files"
        "search_files" -> "Searching the project"
        else -> ToolRegistry.spec(call.name)?.displayName ?: call.name
    }
}
