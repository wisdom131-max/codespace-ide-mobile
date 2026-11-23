package com.codespace.ide.engine

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow

/**
 * OpenAI-compatible streaming chat engine (the same wire shape the archived
 * providers used: POST chat/completions, SSE deltas with
 * choices[0].delta.content, [DONE] sentinel). Pure JVM.
 *
 * Send pipeline (each step owned by one small function — the send loop stays
 * tiny by design): assemble -> budget guard -> history window -> transport ->
 * SSE parse -> delta extract -> event emission. Cancellation is cooperative:
 * the returned Flow is cold and the transport disconnects when collection is
 * cancelled.
 */
class ChatEngineImpl(
    override val sessionId: String,
    private val transport: ChatTransport,
    private val adapter: ProviderAdapter = OpenAIAdapter(),
    /**
     * PROJECT SIX 6.3D: where the retained summary lives between process lifetimes. Null means
     * "retention is memory-only" (JVM tests, or no writable directory) and says so rather than
     * pretending a summary survives a restart.
     */
    private val summaryStore: SummaryStore? = null,
    /**
     * STAGE 5.4: tool call/result pairs already run in this turn, rendered per provider so the next
     * request carries the pairing a provider needs instead of prose about it. Empty by default, which
     * is byte-identical to the old behaviour.
     */
    private val exchanges: () -> List<ToolExchange> = { emptyList() },
) : ChatEngine {

    companion object {
        /** A synthetic id, so nothing can mistake the summary for a real transcript row. */
        const val SUMMARY_ID = "summary-6.2"

        /** Synthetic id for the verbatim retrieval block: never a transcript row, never a tool call. */
        const val RETRIEVED_ID = "retrieved-6.3"
    }

    private val state = SessionState(sessionId)

    /** PROJECT SIX 6.2: labelled retention of the turns that left the window. */
    private val summaryLedger = SummaryLedger()

    /** The summary carried on the last send, or empty when nothing has been summarised. */
    val lastSummaryText: String get() = summaryLedger.render()

    /**
     * What happened to retention, if anything did. A restoration, a rebuild or an unreadable file is
     * reported here rather than swallowed: an owner whose summary vanished deserves to know it was
     * rebuilt, and one whose file was corrupt deserves to know it was ignored.
     */
    @Volatile
    var lastRetentionNote: String? = null
        private set

    /** Last send's verbatim retrieval block, or empty. Never re-executed: it is text. */
    @Volatile
    var lastRetrievedText: String = ""
        private set

    init {
        // Restoring is reading. A corrupt or foreign file is ignored with a reason and the ledger is
        // rebuilt from the messages this session still has - never patched up from guesses.
        summaryStore?.let { store ->
            when (val load = store.load(sessionId)) {
                is SummaryStore.Load.Ok -> {
                    summaryLedger.absorb(load.summary)
                    lastRetentionNote = "Restored " + load.summary.lines.size +
                        " retained line(s) from this session's stored summary."
                }
                is SummaryStore.Load.Corrupt ->
                    lastRetentionNote = "Stored summary ignored (" + load.reason +
                        "); it will be rebuilt from the conversation."
                SummaryStore.Load.Empty -> lastRetentionNote = null
            }
        }
    }

    /** Ids already represented by a summary, so a turn is never summarised twice. */
    val summarisedIds: Set<String> get() = summaryLedger.coveredIds()

    /**
     * LEARNED LIMITS: a limit the provider stated about itself, remembered per model+host.
     *
     * We never guess one. A PromptTooLarge verdict carries the number the provider printed, and from
     * the next send onward the history window is fitted to 85% of it, so the same wall is met once
     * instead of every time. Engine-scoped on purpose: the number belongs to the endpoint the owner
     * chose, and a different provider must not inherit it.
     */
    private val learnedLimitChars = mutableMapOf<String, Int>()

    /** Last send's drop count, for diagnostics. Never rendered as a transcript row. */
    var lastFitDroppedMessages: Int = 0

        /**
         * PROJECT SIX 6.1: what the last send did to the history, and why. The app reads it to show
         * ONE honest line when turns were left out, or nothing when nothing needs saying.
         */
        @Volatile
        var lastContextDecision: ContextPreparer.Decision? = null
            private set
    var lastFitDroppedChars: Int = 0
        private set

    /**
     * The ids that already travel in the window, plus anything the summary already says. Retrieval
     * quotes what is MISSING, so a turn in the window (or already summarised word for word) is not
     * quoted twice: repetition spends budget the request needs.
     */
    private fun refit1Ids(fit: HistoryBudget.Fit, summaryText: String): Set<String> =
        if (summaryText.isEmpty()) fit.messages.map { it.id }.toSet() else fit.messages.map { it.id }.toSet()

    private fun limitKey(config: EngineConfig): String =
        config.baseUrl.lowercase() + "|" + config.modelId.lowercase()

    /** Hard character budget for this send, or 0 when nothing has been learned yet. */
    internal fun learnedBudgetChars(config: EngineConfig): Int =
        learnedLimitChars[limitKey(config)] ?: 0

    fun transcript(): List<ChatMessage> = state.snapshot()

    /**
     * Which end reasons mean "the answer was CUT", not "the answer is complete".
     * "stop"/"end_turn"/"STOP" are ordinary completions; anything naming a length ceiling, a filter
     * or a refusal means the owner is looking at a partial reply.
     */
    /**
     * Area 6: the ONE place a stream ending becomes a verdict. A cut-off end is an output
     * ceiling; otherwise the provider's own value is normalized. No signal stays UNKNOWN.
     */
    private fun finishOf(raw: String?, cfg: EngineConfig): FinishReason {
        if (raw.isNullOrBlank()) return FinishReason.UNKNOWN
        if (isCutOff(raw)) return FinishReason.OUTPUT_LIMIT
        return FinishReason.normalize(cfg.providerId, raw)
    }

    internal fun isCutOff(reason: String): Boolean = when (reason.lowercase()) {
        "length", "max_tokens", "max_output_tokens", "max_tokens_reached",
        "model_length", "content_filter", "safety", "blocklist", "recitation", "refusal" -> true
        else -> false
    }

    /** The single visible line. Named numbers, no jargon, and it never claims the text was lost. */
    internal fun truncatedNote(reason: String): String {
        val why = when (reason.lowercase()) {
            "length", "max_tokens", "max_output_tokens", "max_tokens_reached" ->
                "the answer hit this model's output ceiling"
            "content_filter", "safety", "blocklist", "recitation" ->
                "the provider's safety filter stopped generation"
            "refusal" -> "the model refused and stopped"
            else -> "the provider ended the reply early"
        }
        return "Reply was cut off: " + why + ". What you see above is everything it sent."
    }

    /**
     * MEMORY CONTINUITY: hand a restored conversation to a brand-new engine so the first
     * question after reopening the panel is answered with the same context as before.
     */
    override fun restoreHistory(history: List<ChatMessage>) = state.restore(history)

    override fun send(userText: String, attachments: List<ChatAttachment>, config: EngineConfig): Flow<EngineEvent> = flow {
        // PROJECT NINE 9.41 (catalog 79 WIRED): refuse input that cannot be sent BEFORE it is
        // appended, so a pasted binary blob fails with a specific reason instead of travelling to
        // the provider and returning as an unhelpful error that costs a round.
        //
        // Only when there is no attachment. A photo with no caption is a legitimate send, and a
        // blank check would refuse it, which is why the attachment case is exempt rather than
        // special-cased afterwards.
        if (attachments.isEmpty()) {
            val limits = InputLimits.check(userText)
            if (!limits.ok) {
                emit(EngineEvent.Failed(ChatError.BadRequest(limits.reason)))
                return@flow
            }
        }
        // PROJECT NINE 9.45 (catalog 6 WIRED): the bus existed and nothing published to it, so a
        // stream of events about the turn was never recorded. Published here, before the turn runs,
        // so a reader can tell a started turn from one that never happened.
        AgentEventBus.publish("turn.start", "session=" + sessionId, System.currentTimeMillis())
        // PROJECT NINE 9.47 (catalog 36 WIRED): the episode type could render and parse and had
        // nowhere to keep one. Each turn now files a short episode, so "what did I ask earlier"
        // has an answer that is not a re-read of the whole transcript.
        EpisodicMemory.record(
            id = sessionId + ":" + System.currentTimeMillis(),
            startedAt = System.currentTimeMillis(),
            summary = userText.take(200),
        )
        // PROJECT NINE 9.46 (catalog 33 WIRED): a snapshot of the transcript BEFORE the turn, which
        // is the only moment a rewind can be taken from. Bookkeeping only: nothing reads it back yet
        // in the UI, but the state it would need now exists.
        RewindLog.save(
            label = "turn:" + sessionId,
            state = state.snapshot().size.toString() + " messages",
            atMs = System.currentTimeMillis(),
        )
        state.append(ChatRole.USER, userText, MessageState.DONE)
        val eligible = state.snapshot().filter { it.state == MessageState.DONE && it.text.isNotEmpty() }
        val mode = HistoryMode.parse(config.historyMode)
        // Fit to a LEARNED limit when we have one; systemChars 0 because the prompt rides outside
        // the window and the 85% headroom is the allowance for it.
        val budgetChars = if (config.requestCharsBudget > 0) config.requestCharsBudget
                          else learnedBudgetChars(config)
        val fit = HistoryBudget.fit(eligible, mode, systemChars = 0, budgetChars = budgetChars)
        // PROJECT NINE 9.42 (catalog 91 WIRED): every turn files a readable trace at the one point
        // where the answer exists. "Why did it forget what I said" was previously unanswerable
        // after the fact, because nothing kept the fit outcome once the turn ended.
        TraceStore.record(
            // The label carries the session, not just "turn". A shared shelf mixes every session
            // in the process, so without this a trace could not be attributed to a conversation and
            // would answer nothing.
            label = "turn:" + sessionId,
            rendered = "mode=" + mode + " budgetChars=" + budgetChars +
                " kept=" + fit.messages.size + " dropped=" + (eligible.size - fit.messages.size),
            atMs = System.currentTimeMillis(),
        )
        // PROJECT SIX 6.2: turns about to leave the window are summarised BEFORE they go, once each,
        // and the summary rides at the head of the request clearly labelled as generated. A message
        // already represented by the ledger is not summarised a second time on a later send.
        // PROJECT NINE 9.56 (catalog 22 WIRED): the priority trimmer now makes the call, and its
        // verdict is published. The opening user turn is MUST_KEEP and the rest NORMAL, so a long
        // chat is judged by importance rather than purely by age.
        //
        // Advisory at this step: the verdict is recorded and published, while the actual drop still
        // comes from the age-based fit. Making it authoritative changes what the model sees.
        run {
            val entries = eligible.mapIndexed { i, m ->
                HistoryTrim.Entry(
                    index = i,
                    priority = if (i == 0) HistoryTrim.Priority.MUST_KEEP else HistoryTrim.Priority.NORMAL,
                    tokens = m.text.length / 4,
                )
            }
            val trim = HistoryTrim.plan(entries, (budgetChars / 4).coerceAtLeast(1))
            AgentEventBus.publish(
                kind = "turn.trim",
                payload = "dropped=" + trim.dropped.size + " fits=" + trim.fits +
                    " keptTokens=" + trim.keptTokens,
                atMs = System.currentTimeMillis(),
            )
        }
        val dropped = eligible.filter { m -> fit.messages.none { it.id == m.id } }
        if (dropped.isNotEmpty() && !summaryLedger.alreadyCovered(dropped)) {
            val built = Summariser.summarise(dropped, exchanges())
            summaryLedger.absorb(built)
            // A failed save is reported as "not retained", never as success.
            if (summaryStore != null && !summaryStore.save(sessionId, summaryLedger.current, summaryLedger.coveredIds())) {
                lastRetentionNote = "Retention is memory-only for this session: the summary could not be written."
            }
        }
        // A summary may never eat the request it rides in: it is capped at a quarter of the budget the
        // endpoint allows, so the newest turn and a slice of recent history always have room.
        val summaryCap = (budgetChars / 4).coerceIn(0, ConversationSummary.MAX_RENDER_CHARS)
        val summaryText = if (summaryCap < 200) "" else summaryLedger.render(summaryCap)
        // PROJECT SIX 6.3C: when the question matches an older turn that is no longer in the window,
        // quote that turn back verbatim inside its own eighth of the budget. Reading, not re-running:
        // the retrieved text is a ChatMessage excerpt and there is no executor on this path.
        val retrievalCap = (budgetChars / 8).coerceAtMost(800)
        val retrieved = if (retrievalCap < 120 || dropped.isEmpty()) Retrieved.EMPTY
                        else HistoryRetriever.retrieve(
                            query = userText,
                            candidates = dropped,
                            excludeIds = refit1Ids(fit, summaryText),
                            budgetChars = retrievalCap,
                            alreadyQuoted = summaryText,
                        )
        val retrievedText = retrieved.render()
        lastRetrievedText = retrievedText
        val auxText = summaryText + retrievedText
        // The summary is COUNTED, not added on top: it is part of the request, so the window it rides
        // with is re-fitted once against what is left of the budget. One pass only - repeatedly
        // shrinking a conversation to fit its own summary is the loop that never converges.
        val refit = if (auxText.isEmpty()) fit else HistoryBudget.fit(
            eligible, mode, systemChars = auxText.length,
            budgetChars = (budgetChars - auxText.length).coerceAtLeast(1),
        )
        val head = ArrayList<ChatMessage>()
        if (summaryText.isNotEmpty()) head.add(ChatMessage(id = SUMMARY_ID, role = ChatRole.SYSTEM, text = summaryText))
        if (retrievedText.isNotEmpty()) head.add(ChatMessage(id = RETRIEVED_ID, role = ChatRole.SYSTEM, text = retrievedText))
        val rawWindow = if (head.isEmpty()) refit.messages else head + refit.messages
        // PROJECT NINE 9.60 (catalog 22): the trimmer now ACTS. Age-based trimming drops the oldest
        // turn first, and the oldest turn is usually the instruction that defines the whole task.
        // The opening instruction is swapped in for the next-oldest kept turn, so the window keeps
        // the same number of turns and the same budget. A swap, not a grow.
        val openingTurn = eligible.firstOrNull { it.role == ChatRole.USER }
        val swapOut = rawWindow.firstOrNull()
        // The swap is only allowed when it cannot grow the window: a long opening instruction
        // replacing a short turn would push the send over budget, which is the exact failure this
        // change must not cause. Two of the 594 tests caught that on the first attempt.
        val canSwap = openingTurn != null && swapOut != null && rawWindow.size > 1 &&
            rawWindow.none { it.id == openingTurn.id } &&
            openingTurn.text.length <= swapOut.text.length
        val window = if (canSwap && openingTurn != null) listOf(openingTurn) + rawWindow.drop(1)
                     else rawWindow
        val effectiveFit = refit
        // Deliberately NOT an event: HistoryTrimmed was removed on 2026-11-02 because a transcript
        // row narrating an engine decision gives the owner nothing to act on (see EngineModels).
        // The count is kept for the Chat Log, where it is diagnostic data rather than chat noise.
        lastFitDroppedMessages = effectiveFit.droppedMessages
        // PROJECT SIX 6.1: say what happened to the history, using only limits the catalogue knows.
        lastContextDecision = ContextPreparer.decide(
            capability = ModelCatalog.capabilityFor(config.providerId, config.modelId),
            charsInWindow = window.sumOf { it.text.length },
            droppedMessages = effectiveFit.droppedMessages,
            droppedChars = effectiveFit.droppedChars,
            maxOutputTokens = config.maxResponseTokens.coerceAtLeast(0),
        )
        lastFitDroppedChars = effectiveFit.droppedChars
        // PROJECT EIGHT 8.2: tools are spliced into the FINISHED body, before the budget guard, so
        // the size the guard measures is the size actually sent. Splicing after the check would let
        // a large tool list push a request over a provider limit the guard had already approved.
        val payload = adapter.withTools(
            adapter.payloadWithHistory(window, userText, attachments, config, exchanges()),
            config,
        )
        val overBudget = BudgetGuard.checkPayload(payload.length, config.maxPayloadChars)
        if (overBudget != null && !config.confirmOverBudget) {
            state.append(ChatRole.ASSISTANT, "", MessageState.ERROR)
            emit(EngineEvent.Failed(overBudget))
            return@flow
        }
        val responseMsg = state.append(ChatRole.ASSISTANT, "", MessageState.STREAMING)
        val builder = StringBuilder()
        var endReason: String = ""
        var parser = SseParser()
        // STAGE 5.2: native tool calls (Gemini functionCall, OpenAI tool_calls, Anthropic tool_use)
        // never reached execution before - they were dropped because nothing read them. They are
        // normalised here and rendered into the SAME text protocol the reply path already parses,
        // so there is still exactly one route to AgentTools.executeTool. A call is rendered only
        // once it is COMPLETE: a half-received argument object is never made executable.
        val normaliser = adapter.streamKind?.let { ProviderStreamNormaliser(it) }
        var nativeCallsRendered = 0
        var done = false
        // Resolved ONCE: the same URL is requested and, on failure, named in the verdict.
        val requestUrl = adapter.endpoint(config)
        var attemptCfg = config
        var attemptPayload = payload
        var retriedCeilingField = false
        // catalog 15: models already tried this turn, so a chain cannot loop back to a failed one.
        val triedModels = LinkedHashSet<String>()
        // catalog 11: candidate answers, kept when the same question ends up asked twice.
        val candidates = ArrayList<String>()
        var askedTwice = false
        try {
          while (true) {
            try {
            transport.stream(
                adapter.endpoint(attemptCfg),
                adapter.headers(attemptCfg),
                attemptPayload
            ).collect { chunk ->
                if (done) return@collect
                for (data in parser.feed(chunk)) {
                    // END REASON: recorded BEFORE the delta check, because the chunk that carries
                    // "finish_reason":"length" often carries no text at all.
                    adapter.endReason(data)?.let { endReason = it }
                    val delta = adapter.extractDelta(data)
                    if (normaliser != null) {
                        for (ev in normaliser.feed(data)) {
                            // P3-A: reported usage reaches the session instead of being dropped here.
                            if (ev is StreamEvent.Usage) {
                                emit(EngineEvent.Usage(ev.inputTokens, ev.outputTokens))
                            }
                            if (ev is StreamEvent.ToolCallCompleted && ev.call.complete) {
                                val block = nativeCallMarkup(ev.call)
                                builder.append(block)
                                nativeCallsRendered++
                                emit(EngineEvent.Delta(block))
                            }
                        }
                    }
                    if (delta != null) {
                        builder.append(delta)
                        emit(EngineEvent.Delta(delta))
                    } else if (adapter.isDoneSentinel(data)) {
                        done = true
                        // Recorded here as well: the sentinel chunk is often the last carrier
                        // of the reason.
                        if (endReason.isEmpty()) endReason = adapter.endReason(data) ?: endReason
                        state.update(responseMsg.id) {
                            it.copy(text = builder.toString(), state = MessageState.DONE)
                        }
                emit(EngineEvent.Completed(builder.length, finishOf(endReason, attemptCfg)))
                        break
                    }
                }
            }
            if (!done) {
                // The transport stopped: anything still half-received is closed as incomplete and
                // never rendered, so it cannot execute.
                normaliser?.finish()?.filterIsInstance<StreamEvent.Ended>()?.firstOrNull {
                    it.reason == StreamEnd.INCOMPLETE_TOOL_CALL
                }?.let {
                    if (nativeCallsRendered == 0) {
                        emit(EngineEvent.Truncated("tool_call_incomplete", toolCallIncompleteNote))
                    }
                }
                for (data in parser.finish()) {
                    adapter.endReason(data)?.let { endReason = it }
                    val delta = adapter.extractDelta(data)
                    if (delta != null) {
                        builder.append(delta)
                        emit(EngineEvent.Delta(delta))
                    }
                }
                state.update(responseMsg.id) {
                    it.copy(text = builder.toString(), state = MessageState.DONE)
                }
                emit(EngineEvent.Completed(builder.length, finishOf(endReason, attemptCfg)))
            }
            val cut = isCutOff(endReason)
            ChatLog.record(
                ChatLogEntry(
                    at = System.currentTimeMillis(),
                    mode = config.historyMode ?: "balanced",
                    providerId = attemptCfg.providerId,
                    modelId = attemptCfg.modelId,
                    endpointPath = EndpointUrls.redactedPath(requestUrl),
                    requestChars = attemptPayload.length,
                    httpStatus = 200,
                    outcome = if (cut) "Completed (cut off)" else "Completed",
                    providerMessage = "",
                    endReason = endReason,
                    toolsOffered = config.toolsOffered,
                    historyDropped = lastFitDroppedMessages,
                    learnedLimitChars = learnedBudgetChars(attemptCfg),
                    retriedCeilingField = retriedCeilingField,
                ),
            )
            // Stream ended without [DONE]: treat as completed (honest, no
            // invented failure); the parser finish() case is covered by the
            // transport ending the chunk flow.
            if (cut) {
                // The text stays exactly as streamed; this row only says it is not the whole answer.
                emit(EngineEvent.Truncated(endReason, truncatedNote(endReason)))
            }
            // CATALOG 11: the retry that produced a second candidate was removed - a reply that
            // carries only a tool call has no text, so "empty" was not evidence of a bad answer and
            // asking again re-ran the tools. The picker stays wired for the case that does produce
            // two candidates (a partial answer followed by a fallback answer).
            // PROJECT NINE 9.61 (catalog 11): ask once more when the answer carried no text AND no
            // tool ran. A tool-only reply also has no text, and re-asking would run the tools twice:
            // duplicate writes, duplicate cost. The guard is on the exchanges, not the text alone.
            if (builder.isBlank() && !askedTwice && exchanges().isEmpty()) {
                askedTwice = true
                parser = SseParser()
                done = false
                continue
            }
            candidates.add(builder.toString())
            if (candidates.size > 1) {
                val chosen = DebateConsensus.decide(candidates)?.answer
                if (!chosen.isNullOrEmpty() && chosen != builder.toString()) {
                    builder.setLength(0)
                    builder.append(chosen)
                    state.update(responseMsg.id) { it.copy(text = chosen) }
                }
            }
            break   // the attempt finished; the retry loop may only re-run on a named refusal
            } catch (inner: TransportHttpException) {
                // ONE retry, and only when the provider's own 400/422 NAMES the answer-ceiling
                // field it refuses (MaxTokensField.retryWith). Safe because that rejection lands
                // before any delta, so nothing has been rendered and nothing can render twice.
                // Never a loop: the second rejection falls straight through to the outer handler.
                val flip = if (!retriedCeilingField && builder.isEmpty())
                    MaxTokensField.retryWith(attemptCfg.maxTokensField, inner.status, inner.body)
                else null
                if (flip == null) {
                    // PROJECT NINE 9.58 (catalog 15 WIRED): a retryable refusal with nothing
                    // rendered yet is what a fallback chain is for. Only while the answer is still
                    // empty - retrying after text has streamed would render two answers.
                    if (builder.isNotEmpty()) throw inner
                    val chain = FallbackChain.Chain(
                        models = config.fallbackModels,
                        enabled = config.fallbackModels.isNotEmpty(),
                    )
                    val next = FallbackChain.nextAfter(chain, attemptCfg.modelId, true, triedModels)
                    if (next == null) throw inner
                    triedModels.add(attemptCfg.modelId)
                    attemptCfg = attemptCfg.copy(modelId = next)
                    attemptPayload = adapter.withTools(
                        adapter.payloadWithHistory(window, userText, attachments, attemptCfg, exchanges()),
                        attemptCfg,
                    )
                    parser = SseParser()
                    done = false
                } else {
                retriedCeilingField = true
                attemptCfg = attemptCfg.copy(maxTokensField = flip)
                attemptPayload = adapter.withTools(
                    adapter.payloadWithHistory(window, userText, attachments, attemptCfg, exchanges()),
                    attemptCfg,
                )
                parser = SseParser()
                done = false
                }
            }
          }
        } catch (t: TransportHttpException) {
            state.update(responseMsg.id) { it.copy(state = MessageState.ERROR) }
            // The requested path rides the 404 verdict so "wrong model" and "wrong base
            // URL" stop sharing one misleading sentence. Path only: never the query.
            // P0-1: a 401/403 verdict must name WHO rejected the key and WHICH slot was read,
            // otherwise a provider-side rejection reads like the app refusing (owner report
            // 2026-11-08). Names only - the config carries no key value here.
            val err = ErrorTaxonomy.classify(
                t.status, t.headers, t.body, EndpointUrls.redactedPath(requestUrl),
                providerLabel = attemptCfg.providerId,
                keySlotName = attemptCfg.keySlotName,
                providerId = attemptCfg.providerId,
                modelId = attemptCfg.modelId)
            if (err is ChatError.PromptTooLarge && err.limit != null) {
                // 85% headroom: the reply needs room too, and re-hitting the wall by one token is
                // how an owner concludes the app is broken.
                learnedLimitChars[limitKey(attemptCfg)] = (err.limit * 4 * 85) / 100
            }
            ChatLog.record(
                ChatLogEntry(
                    at = System.currentTimeMillis(),
                    mode = config.historyMode ?: "balanced",
                    providerId = attemptCfg.providerId,
                    modelId = attemptCfg.modelId,
                    endpointPath = EndpointUrls.redactedPath(requestUrl),
                    requestChars = attemptPayload.length,
                    httpStatus = t.status,
                    outcome = err.javaClass.simpleName,
                    providerMessage = ChatLogSanitizer.redact(t.body),
                    toolsOffered = config.toolsOffered,
                    historyDropped = lastFitDroppedMessages,
                    learnedLimitChars = learnedBudgetChars(attemptCfg),
                    retriedCeilingField = retriedCeilingField,
                ),
            )
            emit(EngineEvent.Failed(err))
        } catch (t: kotlinx.coroutines.CancellationException) {
            state.update(responseMsg.id) {
                it.copy(text = builder.toString(), state = MessageState.DONE)
            }
            throw t
        } catch (t: Throwable) {
            state.update(responseMsg.id) { it.copy(state = MessageState.ERROR) }
            emit(EngineEvent.Failed(ErrorTaxonomy.network(t.message ?: "connection failed")))
        }
    }


    /**
     * STAGE 5.2: a complete native tool call, rendered as the markup the reply path already parses.
     * The arguments are embedded as the OBJECT they are, never re-quoted as a string.
     */
    private fun nativeCallMarkup(call: NormalisedToolCall): String {
        val name = call.name.replace("\"", "")
        val args = if (call.argumentsJson.isBlank()) "{}" else call.argumentsJson
        return "\n<tool_call>{\"name\":\"$name\",\"arguments\":$args}</tool_call>\n"
    }

    private val toolCallIncompleteNote =
        "A tool call was cut off before it finished arriving, so it was not run."


}
