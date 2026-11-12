# Copilot areas 4-6 — state on 2026-11-12

Registry: **118 tools**. Tool Groups, `/tools` catalogue, Git recovery and debugger tools are complete and CI-verified.

| Area | Status |
| --- | --- |
| Tool Groups | Complete |
| `/tools` picker | Complete |
| Git tools and recovery | Complete — `0197002`, run 43170924773 green |
| Debugger integration | Complete — `0197002`, run 43170924773 green |
| Chat presentation | Implemented and wired — `28553f4`, `32ea140`, `d7e40e0` green |
| Conversation reliability | Stream ownership implemented — `471081f`; cancellation/retry tool-side coverage still open |
| Output limits and continuation | Partial — turn summary refuses to say "Done"; detection/continuation still open |

## What shipped

**Area 4.** `ToolResultPresentation` is the single ruler for a tool result: kind (RESULT / PARTIAL / EMPTY / ERROR), bounded title, bounded summary, collapsed body, and an explicit withheld-line count. Only the tail of a result is inspected for cap markers, so a body that merely contains the word "truncated" stays a normal result. `ToolActivityRow` now draws those fields: verdict in the error colour, reason visible without expanding, "(partial)" for a capped result, and a stated withheld count for a bounded body. `ToolActivity.ok` defaults to true so every existing producer stayed valid.

**Area 5.** `RequestGate` numbers each turn; `onEvent` drops any event whose number is not current before it can touch rows, tool calls or the buffer. One rule covers Stop with bytes in flight, a replacing send, an armed 429 retry firing late, and a session switch — the gate is per session object, so switching stops the old stream without touching the one on screen.

## Tests

- `ToolResultTest` — 13.
- `ChatPresentationIntegrationTest` — 7, tying `ChatTranscript.cap` to the presentation layer so a capped result cannot reopen as complete.
- `RequestGateTest` — 6.

## Known limitations

- Continuation (area 6C) is not implemented. A turn cut by the output ceiling still reports honestly via `turnSummary` but cannot be resumed in place.
- Provider-reported finish reasons are not yet threaded into the truncation decision; detection currently rests on the existing cap markers.
- Tool-side retry classification (never-started vs failed vs completed-but-response-lost) is not implemented.
- The umbrella name for the four Tool System branches could not be recovered from repository records and is deliberately left unnamed.

## Continuation update (area 6)

`FinishReason` normalizes each adapter's own stop value (OpenAI `finish_reason`, Anthropic
`stop_reason`, Gemini `finishReason`) into one enum whose `complete` flag is the ONLY thing the
transcript may use to call a turn finished. Absent, blank or unrecognized values normalize to
`UNKNOWN`, never to NATURAL: no reason is not evidence of finishing. Only OUTPUT_LIMIT is
`continuable`; a content filter and a dropped connection are not "continued around".

`Continuation.plan` builds the continuation prompt for a ceiling: it forbids repeating delivered
content, forbids re-opening the answer, forbids re-running an already-executed tool and carries a
mode-bounded tail (ask 600 / edit 900 / agent 1200 chars) so the seam is joined without dragging
the whole answer back through the prompt. A provider that cannot continue is reported as
NEEDS_CONTINUATION with an honest instruction instead of a silent failure.

`ToolRetry` is the retry matrix: outcome x effect -> EXECUTE / REUSE_PERSISTED /
RECONCILE_FIRST / REFUSE. Cancellation during execution and an unknown outcome after start are
NOT treated as proof that nothing happened; a side-effecting call in either state reconciles
first. A persisted result is reused, never re-dispatched. Effect comes from the registry's
side-effect metadata, never from a tool's description.

Tests: FinishReasonTest 16, ToolRetryTest 15, ContinuationTest 9.

## Dispatch integration (this pass)

`EngineEvent.Completed` now carries `FinishReason` (default UNKNOWN). `ChatEngineImpl.finishOf`
is the single place a stream ending becomes a verdict: a cut-off end reason is OUTPUT_LIMIT,
otherwise the provider value is normalized. `ChatSession` records it and, when the finish is not
complete, sets the SAME sticky incomplete flag that already survives every save, so a ceiling
turn cannot reopen as finished.

Still NOT wired: `Continuation` is not invoked from the dispatch path and `ToolRetry` is not
consulted by `AgentTools.executeTool`. Area 5 and Area 6 therefore remain IN PROGRESS.

## Real dispatch integration (a418aaf)

**Continuation.** `ChatSession.continueTurn()` calls `Continuation.plan` and dispatches through the
SAME `sendCore` lifecycle: a `continuationPrefix` seeds the stream buffer with the delivered text
plus a visible seam, so the continuation appends instead of replacing, and no user bubble is added.
It returns NEEDS_CONTINUATION without dispatching when the finish is not a ceiling, and reports
the state through `continuationState` (COMPLETE / NEEDS_CONTINUATION / CONTINUING /
UNKNOWN_OR_INTERRUPTED). A continuation that hits the ceiling again stays incomplete.

**Tool retry.** `AgentTools.executeTool` takes an optional stable `callId`. Before the body runs it
consults `ToolCallLedger` (persisted, bounded at 200 entries, 4 KB result cap) and obeys
`ToolRetry.decide`: REUSE_PERSISTED returns the stored result, RECONCILE_FIRST and REFUSE return a
stated refusal and execute nothing. Cancellation during execution is recorded as
CANCELLED_DURING_EXECUTION, never as "did not run". An unreadable ledger reads as empty, so a
duplicate starts from UNKNOWN_AFTER_START rather than looking safe.

**Limitations, stated plainly.** Reconciliation is refusal, not repair: there is no generic way to
ask the world whether a write landed, so an uncertain side effect is refused with its state rather
than reconciled automatically. The ledger's tests exercise it directly; the AgentTools wrapper is
verified by compilation and by the ledger/classification suites, not by an instrumented
instrumentation test. Continuation is wired but not yet surfaced in the composer UI.

Tests: ToolCallLedgerTest 10, ToolEffectIndexTest 3, ContinuationDispatchTest 6.
Full suite: 2296 tests, 1 failure (BackupTarTest.failureMessageCarriesRealTotals, root-sandbox only).

## Final CI evidence

- `a418aaf` (dispatch integration) — run 43177377367 — **success**.
- `98aca66` (documentation) — run 43177388902 — **success**.
- Baseline `0a19ccb` — run 43176200916 — **success**.

Areas 5 and 6 are wired into the real dispatch paths. Their remaining gaps are the ones listed
directly above: no automatic reconciliation repair, no composer surface for continuation, and the
AgentTools wrapper verified by compilation plus the ledger suites rather than an instrumented test.

## Closing pass: dispatch proof, narrow reconciliation, composer surface

**Tool dispatch is now tested at the boundary.** `AgentTools.executeTool` delegates to
`ToolDispatchGuard`, and the guard is driven with counted fake bodies: a first call runs, a repeated
completed call returns the stored result and the body runs exactly once, an uncertain write is
refused with nothing executed, an uncertain read may run, a throwing body is recorded as
COMPLETED_ERROR, cancellation records CANCELLED_DURING_EXECUTION, a blank or null call id gets a
fresh id instead of a reuse, two concurrent calls sharing one id run the side effect once, and a
restored ledger still blocks the duplicate.

**Reconciliation, narrowly and honestly.** `ToolReconciler.fileWrites` proves one class of operation
by reading the target back: if the file already holds the requested content the call is recorded as
COMPLETED_OK with that evidence and no write happens. If the content differs, or the file cannot be
read, nothing is claimed and the refusal stands. Every other operation class (commands, git, network,
package installs, archives) has no reliable read-only evidence, so it keeps the refusal and says so.
This is deliberately not a generic reconciler: assuming success is how a side effect gets applied
twice.

**Continuation is in the composer.** `ChatContinueBar` sits above the input strip and reads
`session.continuationUi`. It appears only for NEEDS_CONTINUATION or CONTINUING; while running the
button disappears rather than disabling, so a second tap cannot start a second continuation. The
bar calls `continueTurn()`, which appends to the cut-off answer behind the visible seam.

**Limits.** No Compose UI harness in this project, so the composer is verified through
`isVisibleContinuation` and `continuationStateForTest` rather than by rendering. The AgentTools
wrapper is exercised through the guard it delegates to, not through a shell-backed tool body.
Restored-session continuation relies on the existing sticky incomplete flag; `lastFinish` itself is
per-session memory.

Tests added this pass: ToolDispatchGuardTest 10, ToolReconcilerTest 6, ContinueBarStateTest 8,
CopilotLifecycleTest 5.
Full suite: 2325 tests, 1 failure (BackupTarTest.failureMessageCarriesRealTotals, root-sandbox only).

## Restoration: the verdict is now persisted

The gap in the previous pass was that `lastFinish` was per-session memory and restored sessions
leaned on the sticky incomplete flag, which cannot distinguish a ceiling from a filter or a
dropped connection.

`StoredMessage` now carries `finish` (the `FinishReason` name), written onto the LAST assistant
message of a conversation and read back on restore by `ChatSession.restoreFinish`. File format is
`v2`: `role \t complete \t atMs \t finish \t text`. Compatibility:

- a `v1` file still loads, its messages intact, with `finish = null`;
- a four-field line under a v2 header is read as v1 rather than skipped;
- `null`, `-` or an unknown name restores as UNKNOWN, which is neither complete nor continuable,
  so an older cut-off reply keeps its incomplete marker but does NOT get a guessed Continue action;
- `FinishReason.entries` round-trips by name, so eligibility after a reopen is identical to
  eligibility before the close.

Restored eligibility therefore comes from recorded state, never from inference on the sticky flag.
The request gate is untouched: a continuation after restoration still takes a fresh number.

Tests: FinishRestorationTest 11 (round trip per reason, v1 file, torn file, tricky text, per-turn
verdict, fresh gate, repeated ceiling, completion persistence, no duplicated user bubble).
Full suite: 2337 tests, 1 failure (BackupTarTest.failureMessageCarriesRealTotals, root-sandbox only).

Note on the umbrella: the "Tool Groups" row in this document refers to the catalogue branch of the
VN Code Dynamic Tool Loading System, whose authoritative table lives in `IMPLEMENTED.md`. It is not
the Thinking Tree and not the chevron grouping.
