---
title: Project Six — Long Context Management
summary: Stage 6.1 landss provider-aware context compatibility in the engine, and states exactly where Project Five Area E ends and this project begins.
---

# Project Six — Long Context Management

## Stage 6.3 — retention, recovery and historical retrieval (IMPLEMENTED, LOCALLY TESTED)

6.2 answered "can a summary invent a fact?" (no: it is extractive). 6.3 answers the two questions that
leaves open: **how sure is a line?** and **where did it come from after a restart?**

### 6.3A — provenance and certainty

`SummaryLine` now carries `status: ClaimStatus` (CONFIRMED / ASSUMED / UNRESOLVED / UNKNOWN) and
`origin: LineOrigin` (MESSAGE / TOOL_EXCHANGE / CARRIED_SUMMARY), plus `seq` so retention never
reorders what was said.

`ClaimStatus.of` reads the conversation's OWN wording, negatives first: "unverified" wins over a
"verified" in the same sentence. Absence of doubt is never certainty, so the default is UNKNOWN and the
rendered line says `[assumed]` or `[unresolved]` when the wording itself was unsure. A tool exchange
states its own status: `OK` is CONFIRMED, `UNCERTAIN` is UNRESOLVED.

Everything the brief lists is preserved by classification rather than by prose: instructions, decisions,
open tasks, failures/refusals, uncertainty, file paths and commits (`looksFactual`), tool outcomes with
status and `(incomplete)`, and provider/model decisions (they arrive as DECISION/FACT lines because the
markers are in the text). Ordering is `seq`, never a sort that shuffles.

### 6.3B — repeated summarisation

`SummaryLedger.absorb` is now correction-aware. A NEWER statement of the same subject replaces the older
one, so a summary cannot accumulate "decided X" and "decided Y" with no way to know which the work
follows. Same subject = at least three distinctive words in common AND 60% of the larger vocabulary
shared (containment, not union, because a correction normally ADDS words). Failures are excluded from
superseding: an earlier failure is evidence that it happened and is never overwritten by a later
success. The rendered summary states how many statements were replaced.

Repeated cycles remain safe: a covered message id is never summarised twice, an identical line is stored
once, and the bound still drops the oldest lines while saying so.

### 6.3C — historical retrieval

`HistoryRetriever` scores older messages against the question and quotes the best ones back VERBATIM,
labelled `[RETRIEVED]` under a header that separates them from a generated summary. It takes an
eighth of the budget, is capped at three turns, never quotes a turn already in the window, and skips a
turn whose opening the summary already states.

It cannot execute anything: input is `ChatMessage`, output is text, there is no executor and no approval
to inherit on that path. Retrieval is reading.

### 6.3D — storage, restore, recovery

`SummaryStore` writes one file per session (`filesDir/chat-retention`), keyed by session id:

| Concern | Handling |
|---|---|
| torn write | temp file + rename; a crash leaves the old file or the new one, never half of either |
| foreign session | the file states its session; a mismatch is `Corrupt("belongs to another session")`, not data |
| corrupt record | `Corrupt(reason)` with the reason surfaced in `ChatEngineImpl.lastRetentionNote`; rebuild from messages |
| duplicate records | one file per session, pruned oldest-first beyond 20 sessions |
| secrets | redacted before the disk (`ghp_`, `github_pat_`, `sk-`, `sk-ant-`, `AIza`, `Bearer`, `api_key=`) |
| no directory | `save` returns false and the engine says "retention is memory-only", never "saved" |

`ChatEngineImpl` loads on construction and saves after every compression, so a restart resumes with the
retained summary instead of an empty ledger.

### Tests

23 engine cases (`Stage63RetentionTest`) and 4 app cases (`Stage63HandoffGuardTest`): provenance and
origin, "unverified" beating "verified", assumptions staying assumptions, uncertain exchanges staying
unresolved, a correction replacing the older decision, a later success not deleting an earlier failure,
duplicates stored once, ordering preserved, store round-trip, foreign-session refusal, unknown-kind
corruption, torn-write survival, redaction, session bound, no-directory behaviour, verbatim retrieval,
retrieval never re-quoting the window, retrieval inside its budget, empty query, a real send carrying
summary plus retrieval inside the wall, restore-with-note, cross-session isolation, and a corrupt record
being ignored with a reason then rebuilt.

## Stage 6.4 — session continuation audit

| Requirement | Verdict |
|---|---|
| successor created correctly | yes, `startHandoffSession` (plus chip and overflow are the same path) |
| useful summary carried | yes, 6.2B `generatedSummary` -> `SessionHandoff` section |
| unresolved work carried | yes, `SessionHandoff.open` and constraints |
| exchanges inert | yes, records only; `Continuation` has no executor or tool name |
| never re-executed | yes, structurally: history reconstruction and handoff execute nothing |
| provider/model context | the successor uses the CURRENT selection, not the parent's; the selection is a user setting, not conversation state |
| cross-conversation leakage | prevented: retention is keyed by session id (test) |
| interrupted handoff | lineage writes are atomic; a failed create returns null and shows no notice |
| duplicate successors | **was a real gap** — a second tap minted a second "Session 2 from git". Fixed by `HandoffGuard`: an existing EMPTY successor is reused, one that already holds a turn is left alone and a new branch is created |
| UI feedback | the notice names the successor; the handoff text carries the labelled summary section |

## Stage 6.5 — closure audit

| Claim | Evidence |
|---|---|
| context budgeting | `ContextPreparer` + `HistoryBudget`, no third budget path |
| summary inside the send budget | counted, re-fitted once, `LearnedLimitTest` enforces it |
| 25% cap | `summaryCap = budgetChars / 4`, plus a 200-char floor below which no summary is attached |
| unknown limits stay unknown | `limitUnknown` decision, nothing trimmed, nothing invented |
| extractive only | no model writes a summary line |
| retention | `SummaryLedger` + `SummaryStore` |
| historical retrieval | `HistoryRetriever`, eighth of the budget, verbatim |
| repeated summarisation | covered-id guard + superseding |
| continuation | audited above, one gap fixed |
| tool exchange preservation | statuses carried in summary and exchanges |
| restoration | store load, note, rebuild on corruption |
| session isolation | store key and test |
| context recovery | rebuild from messages; unavailable content is stated, not invented |
| output and storage bounds | 24 lines, 6,000 chars, 20 sessions, 400-char retrieval quotes |

**Verdict: Project Six is closed for implementation, component tests and CI on `stage-5.3`.** It is NOT
device-verified, and the limitations below are real.

### Limitations

- No compression: old detail is retained as excerpts until a bound drops it.
- Continuation is UI-level; no JVM test drives `ChatSessionManager`.
- Retrieval scores by shared words. A question phrased without the original vocabulary finds nothing, and
  it does not read the stored summary, only messages still in memory.
- Summaries are per-session files; deleting a session does not delete its retained file (it ages out of
  the 20-session bound).
- Superseding is vocabulary-based. A correction phrased in entirely different words keeps both lines; the
  reader sees two decisions rather than one wrong one silently winning.

## Stage 6.2 — evidence-based summarisation, bounded retention, continuation (IMPLEMENTED, LOCALLY TESTED)

`engine/ConversationSummary.kt` plus the wiring in `ChatEngineImpl` and the continuation handoff.

### The design constraint, stated first

A summary must not be able to invent a fact. So this is **extractive, not abstractive**: every line is a
verbatim excerpt of a message or of a tool result that exists, carrying the ids it came from. No model
writes prose about the conversation, so nothing that was never said can appear in a summary. The cost is
real and stated: extraction cannot compress forty turns into three sentences. It can keep them bounded,
labelled and attributable.

Classification uses **literal markers only** (`FAILED`, `REFUSED`, `NOT RUN`, `UNSURE`, `TODO:`, `Decided:`,
`unverified`, a path, a hash, a number) checked with failures and uncertainty ahead of description. A
line with no supporting evidence keeps the neutral label `OTHER` rather than being promoted into a claim.

### What is preserved, and how

| Requirement | Mechanism |
|---|---|
| decisions, facts, instructions, unresolved tasks | labelled excerpts, `DECISION` / `FACT` / `INSTRUCTION` / `OPEN_TASK` |
| tool calls, results, statuses, provenance | each `ToolExchange` becomes a line with its status (`OK`, `FAILED`, `UNCERTAIN`, `REFUSED`) and `(incomplete)` when it was |
| uncertainty and failures never resolved away | `FAILURE` and `UNCERTAINTY` labels, and they outrank chatter when the bound bites |
| originals vs generated | the rendered block starts `GENERATED SUMMARY of earlier turns (excerpts, not verbatim; the original session remains the record)` and travels as a `SYSTEM` message with the synthetic id `summary-6.2` |
| bounded retention | `SummaryLedger`: 24 lines, 6,000 chars rendered, the summary capped at a **quarter of the send budget** |
| repeated summarisation | a message id already in the ledger is never summarised again |
| lost tool exchanges | exchanges are rendered from their own list, not through the window, and the summary restates their statuses |

### Budget discipline, and the bug it caught

The summary is part of the request, so it is **counted inside** the budget, not added on top: the window
is re-fitted once against what is left, and the summary is capped at 25% of that budget. The repo's own
`LearnedLimitTest.theWallIsHitOnceThenRespected` failed while this was wrong, with a 2,012-char payload
against a 1,020-char learned wall. That is the test doing its job, and the fix is in the engine, not in
the assertion.

### Continuation (Stage 6.2B)

`Continuation` carries the summary, the exchange list as **data**, and a label built by
`Continuation.labelFor(n, origin)` -> `Session 2 from git`. The existing P6 continuation path was extended
rather than duplicated: `SessionHandoff` gained a `generatedSummary` field rendered in its own section,
`ChatSession.generatedSummary` exposes what the engine retained, and `startHandoffSession` passes it, so
the plus button and "Continue in New Session" now start the successor with what the predecessor had to
leave out.

**Historical calls are never re-executed.** There is no executor in `Continuation`, no tool name to call
and no approval to inherit: the exchanges are inert records rendered as context lines. Replaying a write
because a session boundary moved would be exactly the duplicate side effect the rest of this codebase
spends its effort preventing.

### Tests

16 engine cases (`Stage62SummaryTest`) and 3 app cases (`Stage62HandoffSummaryTest`): instruction verbatim,
failure labelled, refused/unsure statuses preserved, uncertainty preserved, open work preserved, neutral
label when there is no evidence, the rendered block saying it is generated, a long line cut with the cut
stated, the line bound keeping safety lines first, re-summarising not duplicating, the ledger bound
admitting what fell off, the continuation's hop label and inert exchanges, a real send carrying
`GENERATED SUMMARY` to the provider, an untouched window producing no summary at all, and a summary that
only contains text that was actually said.

### Limitations, honestly

- **No compression.** Old detail is retained as excerpts, then dropped by the bound. Nothing is condensed
  into a shorter statement of the same meaning.
- Summaries are **not persisted**: a process restart starts with an empty ledger, so a restored session
  reconstructs from the transcript instead.
- Exchanges reach a continuation as labelled text inside the summary, **not** as provider tool pairs; a new
  session's engine starts with an empty exchange list by design, so nothing can be replayed.
- Continuation is UI-level (`startHandoffSession`, the plus chip and the overflow item). No JVM test drives
  the manager, because it needs Android storage.
- With an unknown model limit, nothing is trimmed and therefore nothing is summarised; retention is the
  history setting's business.

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
