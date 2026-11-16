---
title: Project Six — Long Context Management
summary: Stage 6.1 landss provider-aware context compatibility in the engine, and states exactly where Project Five Area E ends and this project begins.
---

# Project Six — Long Context Management

## Stage 6.1 — context compatibility (IMPLEMENTED, LOCALLY TESTED, CI-GREEN)

`engine/ContextPreparer.kt`, recorded on every send as `ChatEngineImpl.lastContextDecision`.

### The relationship to Project Five Area E

Project Five Stage 5.4 Area E required provider-aware context compatibility but forbade inventing
limits and forbade silent trimming, and it deliberately stopped short of implementing reduction. That
work lands here, and the split is deliberate rather than a deferral of inconvenience:

- **Project Five built what must survive reduction.** A tool call and its result now travel as a pair
  with an id, a status (OK / FAILED / UNCERTAIN / REFUSED) and a truncation note (below). Five also
  established the refusal to guess: an unknown endpoint refuses to carry results rather than dropping
  them.
- **Project Six decides how much conversation may be sent.** It reads limits only from the catalogue,
  states when a limit is unknown, and reports every reduction.

Neither project may trim a tool pair apart, and Six does not strip exchanges: they are rendered after
the fitted window, so the call/result pairing added in Five survives regardless of how much text was
dropped.

### What it does

`ContextPreparer.decide(capability, charsInWindow, droppedMessages, droppedChars, maxOutputTokens)`
returns a `Decision` whose verdict is one of:

| Verdict | Meaning |
|---|---|
| `FITS` | everything the history setting wanted fits a known window |
| `REDUCED` | older turns were dropped to fit, and the notice says how many |
| `LIMIT_UNKNOWN` | the model is not in the catalogue, so nothing was trimmed on a guess |
| `OVERFLOW` | the conversation exceeds a known window; `blocking = true`, the notice says it was not sent |

Two rules are encoded rather than left to the caller. An output allowance is subtracted from the
*input* budget and never added to it, because confusing the two is how a request is rejected as "too
long" while the app insists it fits. And the estimate is a stated constant,
`CHARS_PER_TOKEN_ESTIMATE = 4`, so a character heuristic is never read as a token count.

Reserve is 25% of the window for the system prompt, workspace block and the new user turn.

### Tests

9 cases in `engine/Stage61ContextTest`: FITS with nothing to say, unknown model reported unknown with
no invented number, unknown model that lost turns explaining it without citing tokens, REDUCED naming
the count and the window, the output allowance lowering the input budget, OVERFLOW blocking and saying
"not sent", the estimate equal to chars / 4, the catalogue deciding whether a limit is known at all,
and a real send recording the decision it took.

### Limitations, honestly

- **No summarisation or compaction.** Reduction is oldest-first dropping by the existing
  `HistoryBudget` rules. Nothing is summarised, so nothing can be fabricated, but a long session loses
  old detail instead of compressing it.
- The estimate is characters, not tokens; no tokeniser is bundled.
- The user-facing surface is one transcript line (Project Seven 7.4). There is no control to choose a
  different policy per send, and no preview of what would be dropped.
- `OVERFLOW` is reported, not blocked at the request layer: the send still runs and the provider's own
  error is what the user sees next. Making the engine refuse before dispatch is the next small step.
- Limits come from the bundled catalogue; nothing is probed at runtime.

## CI

Run `43892624026` (job `build`, 25 steps, none failed) succeeded on `52d264b`, which carries this stage.
