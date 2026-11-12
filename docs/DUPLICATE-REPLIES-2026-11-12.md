# Duplicate Copilot Reply Prevention

Investigated 2026-11-12 against the actual lifecycle: provider stream -> `EngineEvent` -> rows ->
`ChatTranscript.rowsToMessages` -> `ChatTranscriptStore` -> restore -> render. What follows is what
was found, what already existed, what was missing, and what was changed.

## What already existed and why it was not enough

`RequestGate` numbers every turn. Any event whose number is not the current one is dropped, which
already covered: Stop with bytes still in flight, a new send superseding an old stream, an armed 429
retry waking after the user moved on, and a session switch. `ToolDispatchGuard` covers duplicate tool
execution. `ChatTranscriptStore.shouldApplyRestore` guards the restore path.

That left four real gaps, all about the ASSISTANT bubble and the send entry point:

1. **No send-time ownership check.** `send()` never asked whether a request was already streaming.
   Two overlapping requests - a second tap, the Continue action, a retry path - each got their own
   generation and each appended `AssistantStream` rows, so two answers streamed into one transcript.
2. **Duplicate completion events were applied twice.** `EngineEvent.Completed` ran the tool and
   continuation handler with no record of which generation had already completed. A provider that
   repeats its completion (or a retry that replays it) re-ran that handler.
3. **No rule for the user bubble.** A failover retry reuses the same user text; without a rule, the
   bubble could be added again and the question appeared twice.
4. **Restore had no streaming guard at the row level.** `shouldApplyRestore` compared counts but did
   not refuse while a stream was live.

## The rules that now decide it (`ReplyOwnership`, pure and tested)

- `startAction(running, sameText, elapsedMs)`: not running -> FRESH. Running with identical text
  inside a 1.5 s double-tap window -> IGNORE_DUPLICATE_TAP, because a double tap is not a decision to
  ask twice. Anything else -> REPLACE_RUNNING, which cancels the running job and clears the buffer,
  and the generation bump makes its later deltas stale.
- `mayComplete(gen, lastCompletedGen, owns)`: a completion applies once per generation and never for
  a stale generation.
- `addsUserBubble(isContinuation, isFailoverRetry, isFreshQuestion)`: continuations and failover
  retries add no bubble; a genuine second question does.
- `mayApplyRestore(restoreFinished, streaming, currentRowCount, storedCount)`: never while streaming,
  never when the live rows are already longer than the stored copy.

## Legitimate repeats still work

Two identical messages sent a minute apart are two questions: FRESH (nothing running) and both get a
bubble (`anEmptyTranscriptAcceptsTheStoredHistory` and `identicalConsecutiveMessagesAreBothAnswered`
pin this). Nothing in the rules compares message CONTENT across turns.

## Evidence

12 deterministic tests in `ReplyOwnershipTest`. Full suite 2453 tests, 1 failure:
`BackupTarTest.failureMessageCarriesRealTotals` (root-sandbox only, green in CI).

## Limitations, stated honestly

- This is verified by unit tests and by tracing the lifecycle, NOT on a device. Whether the provider
  in use ever repeats `Completed` is not observable from here; the guard makes it harmless either way.
- The double-tap window is a heuristic. A deliberate identical re-send within 1.5 s is treated as a
  duplicate tap; two seconds later it is a new question.
- Persistence has no per-message id, so duplicate protection is ownership-based, not id-based. Adding
  stable message ids would be the stronger fix and is not attempted here.
