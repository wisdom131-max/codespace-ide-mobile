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
     * PROJECT EIGHT 8.7 (catalog 13): the tool name a model uses to say it has finished.
     *
     * Deliberately NOT in ToolRegistry: nothing executes it, so registering it would advertise a
     * capability that does nothing and put it in the approval path for no reason. It reaches the
     * model through the instruction instead, and is recognised here.
     */
    const val DONE_TOOL: String = "task_complete"

    /**
     * PROJECT EIGHT 8.11 (catalog 13): tools that only ASK about the world and never change it.
     *
     * A batch made up entirely of these is waiting, not working. Raising the step cap from 6 to 25
     * made long jobs survivable but did not remove the cause: asking "is the build finished yet"
     * cost a step exactly like writing a file did, so one long build could eat the whole budget
     * while doing nothing. Poll-only batches are now free.
     */
    val POLL_TOOLS: Set<String> = setOf("job_status", "terminal_list")

    fun isPollTool(name: String): Boolean = name in POLL_TOOLS


    /**
     * PROJECT EIGHT 8.7 (catalog 13), second half: recognising a declaration is useless until the
     * model is told to make one.
     *
     * The negative half matters as much as the positive. A model that is told only "say when you are
     * done" will say it whether or not it finished, which is worse than silence because it makes a
     * half-done task look complete. So the same sentence names the other exit: if it stopped early,
     * say so in words. That is the difference between "finished" and "gave up" actually being
     * distinguishable, which is the whole point of the signal.
     */
    fun doneInstruction(): String = buildString {
        append("## FINISHING\n")
        append("When the request is fully done, call ").append(DONE_TOOL)
        append(" with no arguments, instead of simply stopping.\n")
        append("If you stopped early - blocked, out of steps, unsure, or waiting on something - do NOT ")
        append("call it: say in plain words what is unfinished and what you need. Declaring completion ")
        append("on unfinished work is a failure, not a shortcut.")
    }

    /**
     * Rounds per user message. A runaway agent loop is a real failure mode (each round is a
     * paid API call and possibly a destructive command), so it is capped and the cap is
     * announced to the model rather than silently enforced.
     */
    /**
     * PROJECT EIGHT 8.3 (catalog 13): how many tool rounds one request may take.
     *
     * This was 6, and 6 is the reason long work "just stops": a poll, a build, a test run or any
     * multi-file edit needs more rounds than that, so the loop hit the ceiling and told the owner
     * "send again to continue" - which looks like the agent losing the thread, not a cap. A real
     * coding turn routinely needs 15-25 rounds.
     *
     * 25 is a runaway guard, not a working limit: it is high enough that ordinary work never meets
     * it, and the message when it is met still names the number so the owner can tell a loop from
     * a long job.
     */
    const val MAX_ROUNDS = 25

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
    /**
     * PROJECT EIGHT 8.20 (catalog 58 caveat): the NAMES, without the prose.
     *
     * When the request already carries full function schemas, the long description of every tool
     * is paid for twice. This keeps the names in the prompt as a cheap fallback - an
     * OpenAI-compatible server that silently ignores the `tools` field still leaves the model
     * knowing what exists - while dropping the descriptions that the schemas already carry.
     */
    fun compactNames(visible: Set<String>): String =
        if (visible.isEmpty()) "" else
            "\n\n## TOOLS\nThese are supplied as callable function schemas; call them directly " +
                "rather than writing a tool request as text. Available: " +
                visible.sorted().joinToString(", ") + "."

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
        WritePolicy.decide(mode, toolName) == WritePolicy.Decision.STAGE

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
            // PHASE 8c: a call whose arguments never closed is skipped here too, so the
            // preview path and the execute path cannot disagree about what got read.
            val outcome = argsOutcome(body)
            if (outcome === ArgsOutcome.Unbalanced) continue
            val args = (outcome as? ArgsOutcome.Found)?.json ?: "{}"
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
    private fun argsOutcome(body: String): ArgsOutcome {
        val key = ARGS_KEY.find(body) ?: return ArgsOutcome.Absent
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
                    if (depth == 0) return ArgsOutcome.Found(body.substring(start, i + 1))
                }
            }
        }
        // PROJECT EIGHT 8.44 (catalog 60 wiring): an unbalanced argument object used to DROP the
        // call, so one missing brace cost the entire reply. Repair is attempted on the same tail
        // Found uses before giving up. If it cannot be made valid the call is still skipped,
        // exactly as before, so nothing that used to fail now passes silently.
        val repaired = com.codespace.ide.engine.ArgumentRepair.repair(body.substring(start))
        return when (repaired) {
            is com.codespace.ide.engine.ArgumentRepair.Result.Ok -> ArgsOutcome.Found(repaired.json)
            is com.codespace.ide.engine.ArgumentRepair.Result.Failed -> ArgsOutcome.Unbalanced
        }
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
    fun visibleText(text: String, keep: Set<IntRange> = emptySet()): String {
        val ranges = ArrayList<IntRange>()
        for (m in CALL_REGEX.findAll(text)) ranges.add(m.range)
        for (m in HERMES_CALL_REGEX.findAll(text)) ranges.add(m.range)
        // Slice around removed blocks instead of blanking them: a blank leaves a hole in the
        // sentence ("Here you go           done"), which reads as a bug of its own.
        val sb = StringBuilder(text.length)
        var cursor = 0
        for (r in ranges.sortedBy { it.first }) {
            if (r.first < cursor) continue
            val kept = keep.any { it.first == r.first && it.last == r.last }
            if (kept) continue
            sb.append(text, cursor, r.first)
            cursor = r.last + 1
        }
        sb.append(text, cursor, text.length)
        var out = sb.toString()
        if (keep.isEmpty()) {
            // An OPENING protocol tag that no complete block consumed is the head of a truncated
            // or malformed call. Everything from it onward is protocol: cut the tail rather than
            // leave its parameter values standing as if the assistant had written them.
            val tail = PROTOCOL_OPENER.find(out)
            if (tail != null) out = out.take(tail.range.first)
            out = PROTOCOL_FRAGMENT.replace(out, "")
        }
        return out.replace(Regex("""\n{3,}"""), "\n\n").trim()
    }

    /**
     * Decide what a finished reply means.
     *
     * [round] is 1-based and counts completed exchanges in this request. At the cap the
     * calls are refused instead of executed: the honest stop, not a silent extra round.
     */
    fun decide(
        mode: ChatMode,
        reply: String,
        round: Int,
        /**
         * 2026-11-04 guard (a): text-dialect calls are read only when the request actually
         * offered tools. A model that was never given the protocol cannot be answering in it,
         * so any markup in its reply is someone else's words.
         */
        toolsOffered: Boolean = true,
        /**
         * 2026-11-04 guard (c): everything the model was SHOWN this turn - tool results, file
         * reads, attachment text, injected project context. A call whose markup (or, for the
         * text dialect, whose arguments) appears verbatim in here is an echo of untrusted
         * content, not the model's own intent, so it is never executed.
         */
        turnContext: List<String> = emptyList(),
    ): ToolDecision {
        // (d) a call written inside a fenced code block or a quoted block is documentation,
        // never a directive: those regions are masked before anything is parsed.
        val scannable = maskQuotedAndFenced(reply)
        val detailed = parseDetailed(scannable)
        val echoRanges = HashSet<IntRange>()
        val echoes = ArrayList<ParsedToolCall>()
        val calls = ArrayList<ParsedToolCall>()
        val broken = ArrayList<DetailedCall>()
        for (d in detailed) {
            if (toolsOffered && !isEcho(d, turnContext)) {
                if (d.broken) broken.add(d) else calls.add(d.call)
            } else { echoes.add(d.call); echoRanges.add(d.range) }
        }
        // An echo is shown as literal text (the user must see what the model repeated), so its
        // block is exempt from the strip that normally keeps protocol out of prose.
        val visible = visibleText(reply, keep = echoRanges)
        // PHASE 8c: a call whose arguments never closed is not run, and neither is the rest of
        // that reply: the same cut that truncated one call can truncate the next. Saying which
        // tool was affected is the difference between a retry and a silent wrong edit.
        if (broken.isNotEmpty()) {
            return ToolDecision.Refused(
                visible,
                "A " + broken.first().call.name + " call arrived with arguments that never " +
                    "closed, so nothing was run. Send again - a shorter task or a smaller file " +
                    "usually finishes the call before the reply is cut.",
            )
        }
        // PROJECT EIGHT 8.7 (catalog 13): a completion declaration is not a tool call.
        //
        // The loop ends only when a reply carries no call, so from outside "finished" and "gave up"
        // are indistinguishable: the model that did the work and the model that lost the thread both
        // simply stop emitting calls. task_complete makes finishing an explicit statement instead of
        // an absence. It is filtered here rather than added to the registry because it needs no
        // execution - declaring IS the behaviour - and a declaration arriving beside real calls must
        // not stop those calls running, so only the declaration is removed and the rest proceed.
        calls.removeAll { it.name == DONE_TOOL }
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
    fun protocol(mode: ChatMode, only: Set<String>? = null): String {
        // 2026-11-11 (Wave 1 Group A budget fix): this took NO filter, so every turn advertised the
        // whole mode catalogue no matter what Lean Tool Mode had offered, and ChatSession appended
        // the filtered block ON TOP - the prompt carried both, catalogue first. Measured: Agent
        // 13,170 chars, Ask 5,848. The protocol now describes exactly the offered set, which is the
        // one place definitions enter the prompt; [only] = null still means "the whole mode" for
        // callers that genuinely want the catalogue (tests, /tools).
        val block = ToolRegistry.promptBlock(mode, only)
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
            "write_file", "docx_create", "docx_edit", "xlsx_write", "pptx_create", "media_info", "image_convert", "video_frames", "image_ocr", "read_file", "list_files", "search_files" -> "path"
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
        "docx_create" -> "Creating a Word document"
        "docx_edit" -> "Appending to a Word document"
        "xlsx_write" -> "Creating an Excel workbook"
        "pptx_create" -> "Creating a PowerPoint deck"
        "media_info" -> "Inspecting a media file"
        "image_convert" -> "Converting or shrinking an image"
        "video_frames" -> "Taking frames from a video"
        "image_ocr" -> "Reading text in an image"
        "task_plan" -> "Updating the plan"
        "grep_replace_all" -> "Replacing across files"
        "read_file" -> "Reading a file"
        "list_files" -> "Listing files"
        "search_files" -> "Searching the project"
        else -> ToolRegistry.spec(call.name)?.displayName ?: call.name
    }

    // ─────────────────────────────────────────────────────────────────────────────
    // 2026-11-04 device finding #3 (raw markup in the chat) - the parse GUARD.
    //
    // The wire is text, so a reply can contain markup that did not come from the model's own
    // intent: a file it read, a README, an attachment, a page it fetched. VS Code never faces
    // this because tool calls arrive as structured parts, not as text it must interpret
    // (chatParticipants.ts:158 gates on capabilities.supportsToolCalling). VN Code chose the text
    // wire (AgentToolWire.kt header) to work with any OpenAI-compatible endpoint, so it has to
    // pay for that choice with an explicit guard.
    // ─────────────────────────────────────────────────────────────────────────────

    internal data class DetailedCall(
        val call: ParsedToolCall,
        val range: IntRange,
        /** true for the XML/Hermes dialect, whose arguments are the echo risk. */
        val textDialect: Boolean,
        /** The tag text exactly as the model wrote it; the echo check compares this whole block. */
        val text: String = "",
        /**
         * PHASE 8c: the block closed but its arguments did not parse. It must never be executed -
         * a call read as `{}` is a real invocation of a real tool with every parameter missing.
         */
        val broken: Boolean = false,
    )

    /** What the arguments region of a call turned out to be. */
    internal sealed class ArgsOutcome {
        /** A balanced JSON object: the call may run. */
        data class Found(val json: String) : ArgsOutcome()
        /** No `arguments` key at all: a legitimately parameterless tool. */
        object Absent : ArgsOutcome()
        /** The key is there and the object never closes: a truncated or corrupted call. */
        object Unbalanced : ArgsOutcome()
    }

    /** Fenced code blocks and quoted blocks are masked so nothing inside them is parsed. */
    internal fun maskQuotedAndFenced(text: String): String {
        val sb = StringBuilder(text)
        var i = 0
        while (i < sb.length) {
            if (sb.startsWith("```", i)) {
                val end = sb.indexOf("```", i + 3)
                val stop = if (end < 0) sb.length else end + 3
                val innerStart = i + 3
                val innerEnd = if (end < 0) sb.length else end
                // OWNER FIX (2026-11-28): EVERY fence used to be blanked, so a model that wrapped
                // its call in ```json had the call erased. Nothing ran, the reply read as a normal
                // answer, and the turn ended - the "it says it will search, then stops" report.
                // A fence whose whole body is one call is a PAYLOAD and stays scannable. A fence
                // with prose in it is still documentation and is still masked.
                if (isPureCallPayload(sb.substring(innerStart, innerEnd))) {
                    for (k in i until innerStart) if (sb[k] != '\n') sb.setCharAt(k, ' ')
                    if (end >= 0) for (k in innerEnd until stop) if (sb[k] != '\n') sb.setCharAt(k, ' ')
                } else {
                    for (k in i until stop) if (sb[k] != '\n') sb.setCharAt(k, ' ')
                }
                i = stop
                continue
            }
            if (sb[i] == '>' && (i == 0 || sb[i - 1] == '\n')) {
                var j = i
                while (j < sb.length && sb[j] != '\n') { sb.setCharAt(j, ' '); j++ }
                i = j
                continue
            }
            i++
        }
        return sb.toString()
    }

    /**
     * OWNER FIX (2026-11-28): true only when a fenced body is a single call and nothing else.
     *
     * The language tag on the first line is ignored (`json`, `tool`, or none). Anything left over
     * after removing the call - prose, a second block, an example caption - makes it documentation
     * again, so only an unambiguous payload can ever be executed.
     */
    internal fun isPureCallPayload(fencedBody: String): Boolean {
        val body = fencedBody.substringAfter('\n', fencedBody).ifBlank { fencedBody }
        if (body.isBlank()) return false
        val found = parseDetailed(body)
        if (found.size != 1) return false
        val call = found.first()
        val rest = StringBuilder(body)
        for (k in call.range) if (k in rest.indices) rest.setCharAt(k, ' ')
        return rest.isBlank()
    }

    /** Same calls [parse] returns, plus where they were and which dialect they came from. */
    internal fun parseDetailed(text: String): List<DetailedCall> {
        val found = ArrayList<DetailedCall>()
        for (m in CALL_REGEX.findAll(text)) {
            val body = m.groupValues[2]
            val name = NAME_REGEX.find(body)?.groupValues?.get(1) ?: continue
            // PHASE 8c: absent arguments stay "{}" (a tool with no parameters); a PRESENT but
            // unbalanced object is not "no arguments", it is a call that was cut mid-JSON, and
            // running it would invoke the tool with every parameter silently missing.
            val args = when (val outcome = argsOutcome(body)) {
                is ArgsOutcome.Found -> outcome.json
                ArgsOutcome.Absent -> "{}"
                ArgsOutcome.Unbalanced -> "{}"
            }
            found.add(
                DetailedCall(
                    ParsedToolCall(name, args),
                    m.range,
                    textDialect = false,
                    text = m.value,
                    broken = argsOutcome(body) === ArgsOutcome.Unbalanced,
                ),
            )
        }
        for (m in HERMES_CALL_REGEX.findAll(text)) {
            found.add(
                DetailedCall(
                    ParsedToolCall(m.groupValues[1], hermesArguments(m.groupValues[2])),
                    m.range,
                    textDialect = true,
                    text = m.value,
                ),
            )
        }
        return found.sortedBy { it.range.first }
    }

    /**
     * (c) THE ECHO CHECK.
     *
     * Suppressed when the call's own markup appears verbatim in what the model was shown this
     * turn. For the text dialect, the arguments alone are enough: a file that contains a Hermes
     * block is exactly how an instruction-injection payload travels, and a path that was printed
     * by an earlier tool result is indistinguishable from one the model invented.
     */
    internal fun isEcho(d: DetailedCall, context: List<String>): Boolean {
        if (context.isEmpty()) return false
        val haystack = context.joinToString("\n").let(::squash)
        if (haystack.isEmpty()) return false
        // WHOLE BLOCK OR NOTHING (advisor ruling 2026-11-04, after the false positive below).
        //
        // The first rule also matched a bare argument value, and that ate the commonest agent
        // flow there is: list_dir names app.conf, then the model reads app.conf. An injected
        // file repeats the entire call, tags and all, so the whole block is the honest test.
        val block = squash(d.callNameAndArgs())
        if (block.isNotEmpty() && haystack.contains(block)) return true
        val asWritten = squash(d.raw)
        return asWritten.isNotEmpty() && haystack.contains(asWritten)
    }

    /** The exact tag text this call was matched from, as the model wrote it. */
    private val DetailedCall.raw: String get() = text

    private fun DetailedCall.callNameAndArgs(): String =
        "<tool_call><function=" + call.name + ">" + call.argumentsJson + "</function></tool_call>"

    /** The first string value in the argument object, unescaped enough for comparison. */
    private fun primaryArgument(argsJson: String): String {
        val m = Regex("\"([^\"]+)\"\\s*:\\s*\"([^\"]*)\"").find(argsJson) ?: return ""
        return m.groupValues[2]
    }

    private fun squash(value: String): String = value.replace(Regex("\\s+"), " ").trim()
}
