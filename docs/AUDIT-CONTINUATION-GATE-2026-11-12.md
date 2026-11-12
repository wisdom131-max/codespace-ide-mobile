# Audit find: the continuation guard guarded nothing

`ChatSession.continuationInFlight` was declared with a comment describing it as the guard for "between
Continue and the first event of the continuation stream", set when a continuation starts, cleared on
`EngineEvent.Completed`. **Nothing ever read it.**

## Why that mattered

`continueTurn` refused a second continuation only when `ui.value.streaming` was true. Between
`sendCore(...)` returning and the first stream delta arriving, `streaming` is not yet true. A double
tap, or a tap after a slow provider handshake, started a **second** continuation for the same turn:
two requests, two answers, two sets of possible side effects on the same content.

## The fix

- `Continuation.mayStart(streaming, inFlight)` is the pure rule, so it can be tested without a
  session: nothing running **and** nothing already sent.
- `continueTurn` uses it, which is what makes `continuationInFlight` live rather than decorative.
- `EngineEvent.Failed` now clears the flag and sets `CONTINUATION_FAILED`. Before, a failed
  continuation left the flag `true` forever, so Continue would have been refused for the rest of the
  session once the flag was actually read. The bug and the fix had to land together.
- `isVisibleContinuation` now offers Continue in `CONTINUATION_FAILED`. A failed attempt is a reason
  to show the control again, not to remove it.

## Tests

`ContinuationGateTest` (4): free when idle, refused while streaming, refused inside the double-tap
window, and retryable after a failure. `ContinueBarStateTest` gained
`aFailedContinuationOffersRetry` and its negative list no longer claims FAILED is hidden, which was
the old contract. `ContinuationState.CONTINUING` still shows the control because the bar renders its
loading state from it.

Full suite 2523 tests, one known `BackupTarTest` root-sandbox failure (green in CI).

## Still not covered, and why

`continueTurn` needs an Android `Context` and a live provider, so the wiring is reviewed by hand and
its decision function is what is tested. Adding a fake provider would mean a test double of the
engine, which the project has deliberately avoided; the pure rule is the durable part.
