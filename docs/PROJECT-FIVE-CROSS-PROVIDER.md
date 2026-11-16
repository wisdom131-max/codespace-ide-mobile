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

## Next stage (5.3)

Cross-provider tool compatibility and streaming normalisation: normalise tool definitions and tool-call
event shapes per provider, prove providers cannot bypass `AgentTools.executeTool`, and normalise
finish reasons and partial streams without touching the Project Four delivery guarantees.
