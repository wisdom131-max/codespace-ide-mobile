# Project Five — Cross Provider Intelligence

Implementation record. Status vocabulary used throughout: **IMPLEMENTED** (code exists), **LOCALLY
TESTED** (green in this sandbox), **CI-GREEN** (workflow run on the commit), **DEVICE-VERIFIED**
(nothing here is). Following `AGENTS.md`: one stage per commit, dated rows in the changelog, limits
stated at the point they are created.

## Repository rules found before starting

`AGENTS.md` (7,288 lines) is the authority: `## RULES`, `## CURRENT STATE` with a Latest-commit row,
`## KNOWN LIMITATIONS`, then `## CHANGE LOG` entries shaped
`### [YYYY-MM-DD HH:MM WAT] [CI-GREEN <run> @ <sha>] TITLE — detail`. Documentation lives in `docs/`,
with `docs/IMPLEMENTED.md` as the row-per-feature ledger and per-stage reports as separate files. No
new format was invented; this file plus a changelog entry plus an `IMPLEMENTED.md` row follow it.

## Architecture audit (what already exists, so nothing is duplicated)

- `PanelSelection` (`chat/panel/ChatSession.kt`) already carries the provider identity: `providerId`,
  `keySlot` (`ai_GEMINI` style), `modelId`, `baseUrl`, plus the Anthropic and OpenAI-compatible base
  constants. Provider choice is per-selection, not global.
- `CustomEndpointStore` (`chat/CustomEndpointStore.kt`) owns user-added OpenAI-compatible endpoints,
  their manual and live model lists, and read-abort-on-unreadable discipline.
- `SecureTokenStore` holds provider keys by slot; nothing here reads a key.
- `ChatSession` + `AgentToolWire` + `AgentTools.executeTool` already form the single execution gate
  that Project Four hardened. Provider code is upstream of that gate and must stay there.
- No provider capability model existed: nothing knew a context window, a tool-call guarantee or a
  structured-output restriction. That was the honest gap.

## Stage 5.1 — provider and model capability intelligence (IMPLEMENTED, LOCALLY TESTED, CI-GREEN)

`chat/provider/ProviderCapabilities.kt`

- `ProviderCapability` (tool calls, streaming, structured output, reasoning, vision, cancellation) and
  `ModelLimits` with **nullable** numbers. A limit that has not been checked is `null`; it is never a
  plausible-looking guess, because a selector sizing a request against an invented window is worse
  than one that admits it does not know.
- `ModelProfile` / `ProviderProfile` carry `verified` and per-model `notes`; `restrictions()` surfaces
  them. Declared facts are only for families the picker already ships (Gemini flash/pro, Anthropic
  Opus), and the Anthropic note records that its structured output is a tool call, not a response
  format.
- `ProviderCatalogue.resolve(...)` returns `unknown()` for anything else, which is the truthful answer
  for a user-supplied base URL.
- `TaskRequirement` describes what the turn needs (estimated tokens, tools, structured output, vision).
- `ProviderSelector.assess` returns SUITABLE / RISKY / UNSUITABLE with reasons. Unknown capability is
  RISKY, never UNSUITABLE: an unverified provider has not been proved broken.
- `ProviderSelector.choose` returns **the user's own model**, its assessment, and at most one
  alternative that would fit, marked as offered only. There is no code path that switches the provider;
  a switch would send the same conversation and its credentials somewhere the user did not ask for.

**Limits of this stage.** The catalogue numbers are documentation-sourced declarations, not values read
from a live provider API at runtime; nothing probes them. Selection is an assessment, not a policy
engine, and the alternative is not yet surfaced in the UI. Areas C–G of the brief (tool compatibility,
streaming normalisation, error intelligence, conversation continuity, key protection) are untouched so
far and are the next stages.

**Tests.** 11 cases in `Stage51ProviderCapabilitiesTest`: known models carry both limits; an
uncharacterised endpoint reports unknown rather than guessed; a fitting request is suitable with no
reasons; an oversized request is unusable and states the window; over-80% is risky but allowed; tools on
an unknown model are risky; a verified gap (Anthropic structured output) is unusable; the user's choice
is never switched even when it cannot work; a suitable choice offers no alternative; the offered
alternative genuinely fits and can call tools; restrictions are reportable.

**CI.** Branch `stage-5.1` pushed, remote head `27a08b6`. The workflow triggers on `main`, tags and pull
requests, so the branch push started nothing and run `43850633942` was dispatched on ref `stage-5.1`; job
`build` (25 steps) **success** on SHA `27a08b6`. CI validates the build and the JVM suite in a real
Gradle environment; it is not device verification and it does not exercise a live provider.

**Results.** 3,219 tests on this branch (main plus Stage 5.1; the Stage 4.8 tests live on `stage-4.8`),
`BackupTarTest.failureMessageCarriesRealTotals` the only failure, unchanged and reproducible on a clean
tree as a sandbox artifact. Dev-debug compile and D8 dex green. CI-green: run `43850633942`. Not device-verified.

## Stage 5.2 — one tool-call shape, one execution door, honest stream endings (IMPLEMENTED, LOCALLY TESTED, CI-GREEN)

`chat/provider/ToolCallNormaliser.kt`, `chat/provider/StreamNormaliser.kt`, `chat/provider/ToolExecutionGateway.kt`.

**Providers found in the repository (not assumed).** `engine/GeminiAdapter.kt`, `engine/AnthropicAdapter.kt`, `engine/OpenAIAdapter.kt` with `ProviderAdapter`/`SseParser`/`FinishReason`, plus user-added OpenAI-compatible endpoints via `CustomEndpointStore`. Text-protocol tool calls already arrive through `AgentToolWire`. Three dialects, one internal shape.

**A. Normalisation.** `NormalisedToolCall(id, name, argumentsJson, provider, complete, issues)` with `fromGemini` (`parts[].functionCall{name,args}`), `fromOpenAI` (`tool_calls[]`, arguments as a STRING), `fromAnthropic` (`content[]` blocks of type `tool_use`), and `fromWire` for the text protocol this repo already parses. Provider ids are kept where the provider sends one; otherwise a stable synthetic id is derived from provider, index, name and arguments. Multiple calls per response, malformed arguments, blank names and over-size arguments are all represented rather than thrown away.

**B. Validation before execution.** `verdict(call, known)` refuses an incomplete call, a call naming no tool, arguments that are not a JSON object, arguments over 128k characters, and any name absent from `AgentTools.toolNames()`. `uniqueById` drops repeated ids inside a round.

**C. One execution door.** `ToolExecutionGateway.run` validates, claims the call identity once per round, then delegates to the executor it is handed, which in the app is `AgentTools.executeTool` — unchanged, so role checks, approval, MCP approval, prerequisites, health, the duplicate-write ledger, evidence validation and side-effect verification all still apply. The gateway adds no permission of its own and cannot grant one.

**D. Results.** `ToolResultNormaliser.fromExecution` returns `ToolResultEnvelope(callId, name, outcome, result, error, cancelled)`, keeping the original call id, `FAILED` staying failed and a throw becoming `FAILED` rather than an empty success.

**E. Streams.** `ProviderStreamNormaliser` emits `TextDelta`, `ReasoningDelta`, `ToolCallStarted`, `ToolCallArgsDelta`, `ToolCallCompleted`, `Usage`, `Ended(StreamEnd)`, `Failed`, `Cancelled`. `StreamEnd` keeps NATURAL, TOOL_CALLS, OUTPUT_LIMIT, CANCELLED, PROVIDER_ERROR, INCOMPLETE_TOOL_CALL and UNKNOWN apart, so a truncated answer is never reported as finished. Partial tool calls never emit `ToolCallCompleted`; `finish()` closes the stream as INCOMPLETE_TOOL_CALL (a late chunk is then ignored), and a stream that simply stops is UNKNOWN. Replayed Gemini parts are de-duplicated by id, and argument buffers are capped at 64k per call with at most 16 pending calls.

**Runtime integration.** `ChatSession.runTools` now routes each call through the gateway before `AgentTools.executeTool`; a refused call returns `FAILED: <reason>` (which `ToolFailurePolicy` already classifies) and never reaches the executor.

**Tests.** 25 cases in `Stage52CrossProviderTest`: the three dialects plus the text protocol, multiple calls, unclosed arguments, blank name, non-object arguments, unknown tool never reaching the executor, duplicate ids, gateway success/failure/throw, same-round repeat running once, OpenAI fragment assembly, a stream cut mid-arguments, Anthropic `input_json_delta` completion, Gemini finish mapping, OUTPUT_LIMIT not read as natural, replayed chunk, provider error, cancellation plus late chunk, silent stop = UNKNOWN, bounded buffers, usage metadata.

**Results.** 3,244 tests on `stage-5.2`; `BackupTarTest.failureMessageCarriesRealTotals` the only failure, unchanged. Dev-debug compile and D8 dex green. NOT CI-green (branch unpushed). Not device-verified.

**Component-tested versus runtime-tested.** The gateway boundary, normalisers and stream state machine are component-tested with fake executors and fake payloads. The `ChatSession` wiring compiles and is exercised only as far as existing component tests reach: `runTools` needs an Android `Context`, so no JVM test drives a real dispatch. No live provider was contacted, so real Gemini/Anthropic/OpenAI stream behaviour remains unverified.

## Stage 5.2b — the normaliser reaches the live path (IMPLEMENTED, LOCALLY TESTED, RUNTIME-TESTED IN THE ENGINE, CI-GREEN)

`4ea3b96` added the normalisers and wired `ToolExecutionGateway` into `ChatSession`, but
`ProviderStreamNormaliser` ran only in unit tests. Tracing the real route showed why that mattered:
native tool calls never reached it.

**The live route, before this commit.**

1. `ChatSession.send` -> `ChatEngineImpl.send` (`engine/ChatEngineImpl.kt`).
2. `transport.stream(url, headers, payload)` (`engine/ChatTransport.kt`).
3. `SseParser.feed(chunk)` (`engine/SseParser.kt`) -> one `data:` payload at a time.
4. Per payload: `ProviderAdapter.endReason`, `extractDelta`, `isDoneSentinel` (`GeminiAdapter`, `OpenAIAdapter`, `AnthropicAdapter`).
5. Deltas -> `EngineEvent.Delta`; end -> `Completed` / `Truncated` / `Failed`.
6. `ChatSession` collects events and appends the text to the reply row.
7. `AgentToolWire.decide(text)` -> `parse` -> `ChatSession.runTools` -> `ToolExecutionGateway` -> `AgentTools.executeTool`.

Tool calls were therefore only ever the TEXT protocol. A native `functionCall` / `tool_calls` /
`tool_use` payload has no text delta, so `extractDelta` returned null and the call was silently
dropped — the adapter was blind to it by design.

**What this commit changed.**

- The dialects moved into the engine (`engine/ToolCalls.kt`, `engine/ProviderStreamNormaliser.kt`),
  because that is where the payloads arrive. The engine stays dependency-free, so a hand-rolled
  `engine/JsonLite.kt` replaced `org.json`: an `org.json` jar in this module would collide with
  Android's own `org.json` at dex time.
- `ProviderAdapter.streamKind` (default null) lets an adapter name its dialect; Gemini, OpenAI and
  Anthropic override it. Their existing parsing is untouched.
- `ChatEngineImpl` feeds every payload to the normaliser alongside the adapter. A call is rendered
  into the same text protocol the reply path already parses only once it is COMPLETE, so the route
  after the engine is unchanged and there is still exactly one path to `AgentTools.executeTool`.
- A call still arriving when the transport stops is never rendered; the user gets one
  `EngineEvent.Truncated("tool_call_incomplete", ...)` instead of a silently dropped action.
- The app keeps only what is app's: `chat/provider/WireCalls.kt` (text wire -> normalised call) and
  `ToolExecutionGateway` (validate, claim once per round, delegate to `AgentTools.executeTool`).

**The live route, after.** Steps 1-3 unchanged; step 4 also runs the normaliser; step 5 carries any
completed native call inside the reply text; steps 6-7 unchanged. A partial call cannot execute
because it is never rendered; an unknown tool cannot execute because the gateway refuses it against
`AgentTools.toolNames()`.

**Runtime evidence.** `engine/src/test/kotlin/com/codespace/ide/engine/Stage52RuntimeStreamTest.kt`
drives the real `ChatEngineImpl.send` with a fake `ChatTransport`, the real `SseParser` and the real
adapters: a Gemini `functionCall` arrives as one parseable call, a complete Anthropic `tool_use`
becomes exactly one call, an incomplete one becomes none, a text-only OpenAI stream is untouched, and
a replayed payload yields one call.

**Still not proven.** `ChatSession.runTools` needs an Android `Context`, so the step from reply text to
`AgentTools.executeTool` remains component-tested only; no Robolectric or instrumentation suite was
added. No live provider was contacted, so real streaming against Gemini, Anthropic or an
OpenAI-compatible endpoint remains unverified and needs a device round.

## CI — Stage 5.2 branch

Branch `stage-5.2` pushed from `bb480c5` with `4ea3b96` then `8998135`. The workflow triggers on
`main`, tags and pull requests, so the branch push started nothing; run `43863485364` was dispatched on
ref `stage-5.2` and its job `build` (25 steps) succeeded on SHA `8998135`. The job's own test steps ran:
the JVM unit suites and the ChatEngine contract suites. Neither step reaches a live provider.

**What CI proves and does not prove.** CI proves the tree compiles and the JVM suites pass in a real
Gradle environment on `8998135`, which now includes `Stage52RuntimeStreamTest` (engine runtime tests
with a fake transport) and `Stage52CrossProviderTest` (component tests). CI is not device verification:
no Android runtime, no UI, no real network to Gemini, Anthropic or an OpenAI-compatible endpoint.
`ChatSession.runTools` remains component-tested only, because it needs an Android `Context`.

## Stage 5.3 — error intelligence and recovery (IMPLEMENTED, LOCALLY TESTED, CI-GREEN)

`engine/ProviderErrorModel.kt`, `chat/provider/SideEffectRecovery.kt`, and the advice path in
`chat/panel/ChatFailureAdvice.kt`.

**The evidence, not the guess.** `ProviderErrors.extract` reads the three real shapes — Gemini
`{"error":{"code","status","message"}}`, Anthropic `{"type":"error","error":{"type","message"}}`,
OpenAI-compatible `{"error":{"message","type","code"}}` — into `ProviderErrorFacts`, and `classify`
reads the provider's own code, type and message BEFORE the status, because a 400 can be a context
overrun (recoverable by shortening) or a malformed request (not recoverable at all). A symbolic code
wins over Gemini's numeric one. A body that matches nothing stays `UNKNOWN`; a malformed body is read
only from the status. Transport failures classify from their message (timeout, DNS, reset, offline,
TLS). `Redaction` strips keys, bearer tokens and credential-bearing query strings from every message
that could be shown or logged.

**The decision.** `RecoveryPlanner.plan` returns one of RETRY_SAFE, WAIT_AND_RETRY,
USER_ACTION_REQUIRED, CONFIGURATION_REQUIRED, CONTEXT_REDUCTION_REQUIRED, ALTERNATIVE_MODEL_AVAILABLE,
OPERATION_OUTCOME_UNKNOWN, PERMANENT_FAILURE, CANCELLED, with a sentence and, where relevant, a wait.
The retry arithmetic is NOT re-implemented: the attempt ceiling, backoff and the cap on a provider's
Retry-After stay in the existing `ProviderRetry.waitOrNull`/`backoff`. An `OPERATION_OUTCOME_UNKNOWN`
outranks every other consideration.

**Side-effect safety.** `ToolOutcome` gained `UNCERTAIN`. A throwing executor is no longer reported as
`FAILED`: `ToolExecutionGateway` now returns `UNSURE: ...`, `ToolResultNormaliser` maps it to
`UNCERTAIN`, and `ChatSession` shows it as "Unsure -" instead of "Done -". `SideEffectRecovery` turns
the outcome plus an optional `SideEffectVerifier` verdict into NEVER_STARTED, COMPLETED,
VERIFIED_FAILED or UNCERTAIN, and `mayRetry` refuses a completed or uncertain write while still
allowing an uncertain read. The execution door is unchanged: `AgentTools.executeTool`, once per
claimed call.

**User-facing advice.** `ChatFailureAdvice.adviceFor` maps the verdicts that already imply facts (429
with its Retry-After, 402, 401) into the planner, so a temporary limit keeps the plain row, a long
Retry-After is explained rather than held open, quota exhaustion asks the user to act, and an
already-executed write suppresses every retry suggestion. Nothing is ever sent on the user's behalf,
and no provider or model is switched: an alternative is an `alternativeModel` field, an offer.

**Tests.** 20 cases in `engine/Stage53ErrorRecoveryTest` (three formats, custom unknown, malformed
body, overloaded vs limited, redaction in messages and query strings, throwable classes, and every
decision: key rejection, temporary limit, Retry-After, absurd Retry-After, quota, attempt ceiling,
context overrun, unknown limits, alternative offer, no silent switch, side-effect override, dropped
stream, malformed request). 13 cases in `app/Stage53SideEffectRecoveryTest` (throwing write is
uncertain, uncertain write refused, uncertain read allowed, verified completion, refuted write
retryable, refused call never started, and the advice sentences). One Stage 5.2 case was REWRITTEN,
not weakened: `aThrowingExecutorBecomesAFailureNotASuccess` became
`aThrowingExecutorBecomesAnUncertainResultNotASuccess`, because the old expectation is exactly the bug
this stage fixes.

**Results.** 3,257 app tests with `BackupTarTest.failureMessageCarriesRealTotals` the only failure;
engine suite green. Dev-debug compile and D8 dex green. Not CI-green; not device-verified.

**Known limitations.** `ChatError` carries no status or provider code, so the app planner only knows
what a verdict already implies; full fact extraction runs at the engine boundary and is not yet
surfaced as a typed event. Context reduction is advised, not performed: no automatic history trim is
triggered from a context error. `SideEffectRecovery` is wired to the gateway outcome and the existing
`SideEffectVerifier` type, but no new read-back is performed inside `ChatSession` for an uncertain
write. No live provider was contacted, so real provider error bodies remain unverified against the
shapes above.

## Stage 5.3b — the evidence reaches the app (IMPLEMENTED, LOCALLY TESTED, CI-GREEN)

The limitation Stage 5.3 recorded — "`ChatError` carries no status or provider code, so the app planner
only knows what a verdict already implies" — is closed.

**Where the information was lost.** `ChatEngineImpl` caught `TransportHttpException` and called
`ErrorTaxonomy.classify(status, headers, body, path, providerLabel, keySlotName)`. That function read
the body for wording and for a token limit, then threw the rest away: the provider's `code`/`type`, the
parsed `Retry-After`, the raw status and the sanitized message never left the engine. So the app saw
`ChatError.BadRequest("...")` and could not tell a context overrun from a malformed request, and
`ChatError.RateLimited(sec)` could not tell a temporary limit from an exhausted quota.

**The typed carrier.** `ChatError` now holds `facts: ProviderErrorFacts?`, written once by the
classifier (`attachFacts`, `private set`, `internal` setter) and read by the app. It is null when no
provider body was read — for example a locally built `ChatError.Server(500)` — and null means nothing
may be inferred. `ErrorTaxonomy.classify` extracts the facts BEFORE choosing the verdict, so even the
status-only branches carry them, and `ChatEngineImpl` now passes `providerId` and `modelId` so the
facts name the provider and model that were actually used. `ErrorTaxonomy.network` attaches transport
facts the same way.

**Runtime integration.** `ChatSession` builds the failure row with `ChatFailureAdvice.adviceFor(err)`
instead of `noteFor(err)`, and `adviceFor` reads `err.facts` first, falling back to what a bare verdict
proves, then to the existing `ProviderRetry` sentence. `planFor(err)` exposes the whole decision for
callers that want the action rather than the sentence. The retry arithmetic is untouched:
`ProviderRetry` remains the single policy for attempt ceilings, backoff and the Retry-After cap.

**One classification correction.** A `400` whose body yields no code, type or message is now UNKNOWN
rather than INVALID_REQUEST. "The request was rejected" is not "the request was malformed", and
inventing the second from the first turned an unreadable custom endpoint into a permanent-failure
claim about the user's own message. Unambiguous statuses (401, 403, 402, 404, 408, 413, 429, 5xx) still
classify without a body.

**Tests.** 10 cases in `engine/Stage53bTypedFactsTest` (retry-after and code carried through, key
rejection code, context overrun inside a 400, HTML body safe, custom body unknown, credential stripped,
transport facts, no evidence invents nothing, planner answers from facts, quota not turned into a wait)
and 9 in `app/Stage53bErrorPathTest` (context asked for reduction, malformed request called permanent,
temporary limit keeps a plain row, long Retry-After explained, quota asks the user, rejected key names
the key, unknown custom endpoint never invented into a retry, executed write outranks the provider
error, verdict without evidence still answers). Both suites start at `ErrorTaxonomy.classify`, which is
the production engine boundary, and end at `ChatFailureAdvice`, which is what `ChatSession` renders.

**Results.** 3,266 app tests with `BackupTarTest.failureMessageCarriesRealTotals` the only failure;
engine suite green. Dev-debug compile and D8 dex green. Not CI-green; not device-verified.

**Still limited.** Context reduction remains advised, never automatic. `SideEffectRecovery` still
performs no new read-back for an uncertain write inside `ChatSession`. No live provider was contacted,
so the real error bodies remain unverified against these shapes — only the documented formats are.

## CI — Stage 5.3 branch

`stage-5.3` was pushed from `13d415b` with `52f36e1` (error intelligence and recovery) and `6b95b70`
(typed facts past the engine boundary). The workflow triggers only on `main`, tags and pull requests, so
the branch push started nothing; run `43868731369` was dispatched on ref `stage-5.3` and its job `build`
succeeded on SHA `6b95b70`. `52f36e1` is covered by the same run because it is an ancestor of the tested
SHA, which is what a single-run verdict on a linear branch means.

The job's own test steps ran the JVM unit suites and the ChatEngine contract suites, so
`Stage53ErrorRecoveryTest`, `Stage53bTypedFactsTest`, `Stage53SideEffectRecoveryTest` and
`Stage53bErrorPathTest` were all executed in the CI environment rather than only here.

**What CI proves and does not prove.** CI proves the tree compiles and those suites pass on a clean
Gradle runner at `6b95b70`. It does not prove device behaviour, and it contacted no provider: the error
bodies exercised are the documented formats, not live responses.

## Stage 5.4 — tool pairs survive a provider change (IMPLEMENTED, LOCALLY TESTED)

`engine/ConversationContinuity.kt`, a `payloadWithHistory` hook on `ProviderAdapter`, overrides in
Gemini/OpenAI/Anthropic, and one new constructor argument on `ChatEngineImpl`.

**The gap, verified.** The request history was `List<ChatMessage>`: role plus text, nothing else. A tool
call and its result therefore had NO representation in a request. The model was given prose about a
tool run, not a call/result pair, so Anthropic (which requires pairing) and OpenAI (which keys on ids)
were being sent a history that could not express what had happened. Stage 5.2 made a call reach the
executor; it did not make the exchange reach the next request.

**The canonical type.** `ToolExchange(callId, name, argumentsJson, status, output, complete)` with
`ExchangeStatus` OK / FAILED / UNCERTAIN / REFUSED. `resultText()` prefixes the status in plain words, so
an uncertain write is never presented to a model as a success. `dedupe` keeps the first occurrence of an
id, so a replayed stream cannot produce two calls with one identity.

**Per-provider rendering.**
- Gemini: one `model` turn of `functionCall` parts followed by one `user` turn of `functionResponse`
  parts, which is the order Gemini requires.
- OpenAI-compatible: an `assistant` message with `tool_calls`, then one `role:"tool"` message per
  `tool_call_id`, in order.
- Anthropic: `tool_use` blocks in the assistant turn and `tool_result` blocks in the user turn, with
  `is_error: true` for anything that was not OK.
- An unknown endpoint (`streamKind == null`) is REFUSED with a reason naming how many results cannot be
  sent, rather than having them dropped silently. An adapter that has not opted in keeps sending the
  plain history, so nothing claims a capability it lacks.

**How it reaches the wire.** `ConversationContinuity.splice` appends the rendered objects to the
provider's own message array, bracket- and string-aware so a `]` inside message text cannot truncate a
request. The adapters keep their existing builders; they gained `payloadWithHistory` overrides instead
of a second request builder. `ChatEngineImpl` takes `exchanges` (default empty, byte-identical to the
old behaviour) and passes it to both the main and the retry payload call, so a retry after a failure
also carries the pair.

**Tests.** 15 cases in `engine/Stage54ContinuityTest`: Gemini call-then-response order, OpenAI ids and
one tool message per call, Anthropic pairing plus `is_error` on exactly the failed result, status words
never softened, an incomplete call never rendered as a call, an unknown endpoint refused instead of
dropped, dedupe by id, splice with a bracket inside text, splice with no such key, the three adapter
bodies, an adapter that has not opted in, and the end-to-end case where `ChatEngineImpl.send` puts the
pair on the transport payload. That last one is the runtime evidence: it asserts what a fake transport
actually received.

**Results.** 3,266 app tests with `BackupTarTest.failureMessageCarriesRealTotals` the only failure;
engine suite green. Dev-debug compile and D8 dex green. Not CI-green; not device-verified.

**Known limitations.** The producer side is not wired: `ChatSession` keeps its own transcript and does
not yet build `ToolExchange` lists from it across turns, so the exchanges are available and tested but
reach the engine only when a caller supplies them. Context-window compatibility (Area E) is NOT
implemented beyond what Stage 5.3 established: known limits are used where the catalogue has them, an
unknown limit stays unknown, and no history is trimmed automatically. Interruption replay (Area D) was
audited only as far as existing Stage 4.8/5.2 protections reach; no new replay mechanism was added.
Custom endpoints are refused rather than guessed at.

## Stage 5.4 part 2 — the producer is wired (IMPLEMENTED, LOCALLY TESTED)

`chat/provider/ToolExchangeLog.kt`, written from `ChatSession.noteAndRecord` and read when the request
is built.

**Where it is written.** `ChatSession.noteAndRecord` is the single point in the app where a tool result
is recorded, and it already held everything needed: the parsed call (name, arguments), the call's index
in its batch, and the result text the user sees. The log is keyed by the provider's own call id when the
wire carried one, or by a stable synthetic id `wire-<turn>-<index>` when it did not, so a replayed stream
cannot create a second entry for one call and a retry cannot either. The status is read from the same
text the transcript shows, so "Unsure" in the row and UNCERTAIN on the wire cannot disagree:
`UNSURE:` and `CANCELLED` are UNCERTAIN, `FAILED` is FAILED, `REFUSED` is REFUSED, blank is UNCERTAIN
(an outcome nobody can read is not evidence of success), everything else is OK. The log is bounded at 60
entries and drops the oldest, because a long session must not grow it without limit on a phone.

**How it reaches the engine.** `ChatEngineImpl` takes a SUPPLIER, not a captured list:
`exchanges: () -> List<ToolExchange>`, read at payload time. A snapshot captured at construction would
be empty for the turn in progress, and the engine is constructed from `ChatSession`'s constructor.

**Two defects this found, both real.**
1. The first wiring stored the log as an ordinary `val` below `buildEngine(...)` in the constructor, so
   the log was null when the engine was built. The repo's own `ChatModeRestartTest` caught it with five
   failures. A `by lazy` did not help (the delegate holder itself was not yet assigned); passing a
   supplier is the correct fix, and it also fixes the staleness a captured list would have had.
2. The engine's retry payload call site still passed the supplier object rather than calling it, which
   the compiler caught.

**Tests.** 12 cases in `app/Stage54bProducerTest`: OK/FAILED/UNCERTAIN/REFUSED mapping, cancelled and
blank results treated as uncertain, a replayed id recorded once, the provider's own id preserved, an
incomplete call carried as incomplete, the bound keeping the newest entries, call order, and an
end-to-end case where a log built from transcript results drives `ChatEngineImpl.send` and the payload
the fake transport received contains exactly two `tool_call_id` entries with `UNSURE` on the write.

**Results.** 3,278 app tests with `BackupTarTest.failureMessageCarriesRealTotals` the only failure;
engine suite 230 tests with none failing; dev-debug compile and D8 dex green. Not CI-green; not
device-verified.

**Known limitations.** `ChatSession`'s tool execution needs an Android `Context`, so no JVM test drives
a real tool call through `runTools` into the log; the end-to-end case starts from the same result texts
the log records, not from `AgentTools.executeTool`. Refusals that never reach `noteAndRecord` are not
logged (`recordRefused` exists for a future call site). The log is session-scoped, not persisted: after
a process restart the exchanges start empty, which is honest but means a restored session's first
request does not carry older pairs. Area E context reduction remains unimplemented.

## Next stage (5.5)

Cross-provider tool compatibility and streaming normalisation: normalise tool definitions and tool-call
event shapes per provider, prove providers cannot bypass `AgentTools.executeTool`, and normalise
finish reasons and partial streams without touching the Project Four delivery guarantees.
