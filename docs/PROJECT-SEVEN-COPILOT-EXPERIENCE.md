# Project Seven — Copilot experience (IN PROGRESS, partial delivery)

## Starting point

`main` at `b9b4008` (the integrated Project Four Stage 4.8 + Projects Five/Six tree). Branch
`project-seven` created from it. Nothing on `main` was modified.

## What the audit found already existed

A large part of the brief is already implemented and shipped, and re-building it would have been the
biggest risk in this project:

| Brief area | Already present before this project |
|---|---|
| 7.1 message presentation | `ChatRowModel`, Markdown/code-block rendering, copy actions, long-response handling |
| 7.2 streaming/composer | `ChatComposer`, stop control, `ChatInputLayout`, send-state guards, `ProviderStreamNormaliser`, output-limit notes |
| 7.3 Thinking Tree | task grouping and `/tools` catalogue kept deliberately separate |
| 7.4 tool results | `ApprovalPresentation`, `ChatApprovalCard`, `ToolExecutionGateway`, `ToolHealth` |
| 7.5 errors/recovery | `ChatFailureAdvice` + `RecoveryPlanner` + `ProviderRetry` (Stage 5.3), `SideEffectRecovery` |
| 7.6 provider/model | `ProviderSelector`/`ProviderCatalogue` with explicit selection and no silent switching |
| 7.7 context/sessions | `Continuation`, `SessionHandoff`, `SummaryStore`, `HandoffGuard`, `HistoryRetriever` |
| 7.8 research/documents | research source ledger, archive extraction safety |

The genuine gaps were not "missing screens". They were places where a surface had runtime state in hand
and no single, tested way to turn it into words without overstating what happened.

## What this pass implemented

### 7.5 — outcome truth (implemented, locally tested)

`CopilotPresentation.outcome` turns a `ToolOutcome` into a headline, whether the operation **ran**
(YES / NO / **UNKNOWN**), whether a retry is safe, and which action to offer:

- `UNCERTAIN` reads "may have run ... result is unknown", `retrySafe = false`, action "Verify before
  retrying". Never "failed", never a plain Retry.
- `CANCELLED` reads unknown, `retrySafe = false`, because cancellation does not prove nothing happened.
- A gate refusal says it never ran and is retryable after adjusting.
- `SUCCESS` offers no action at all.

This is the wording layer the brief asks for; the decisions still come from `ProviderRetry` /
`RecoveryPlanner` for provider failures and from `SideEffectRecovery` for writes.

### 7.4 — tool rows bounded before rendering (implemented, locally tested)

`CopilotPresentation.toolRow` flattens a result, cuts at 600 characters **and states the cut** with both
numbers, so a 40,000-character output cannot reach a low-memory chat pane and freeze a scroll frame.

### 7.3 — task state presentation (implemented, locally tested, NOT yet bound to the Tree UI)

`taskState` maps the runtime's own words to QUEUED / ACTIVE / DONE / FAILED / BLOCKED / **UNKNOWN**, where
an unreadable state is UNKNOWN and never DONE. `taskTree` groups children by the runtime's own ids
(stable keys), and `progress` prints "1 of 3 done, 1 with no state reported" rather than rounding up.
**This is a presenter only: the Thinking Tree still renders from its existing source.** Binding it is
outstanding work, stated below rather than implied to be done.

### 7.7 — carried-context notice (implemented, WIRED to the real handoff)

`startHandoffSession` previously returned "Session 2 from git - <describe(handoff)>". It now returns a
line built from the summary the successor actually received:

> Session 2 from git carries 6 labelled line(s), including 2 unresolved item(s). 1 decision · 2 open
> tasks · 1 failure · 2 historical tool exchanges (inert data, never re-run) · this summary is
> memory-only: it will not survive a restart

`kindsFromRenderedSummary` reads the labels back out of the engine's own rendered summary, so the counts
are the runtime's output, not a second guess. Whether the summary is already on disk is **checked**
(`handoffRetentionFileExists`), so "memory-only" appears only when that is true. `exchangeCount` is
passed as 0 because the handoff path does not expose the exchange list, and a number nobody measured
would be the exact sin this project is trying to stop.

## Tests

13 new cases in `Stage71CopilotPresentationTest`: uncertain never called failed or safe, cancelled is
unknown, refusal never ran and retryable, success offers no action, huge output cut with numbers, small
output untouched, unreadable task state is UNKNOWN, progress counts only reported states, stable child
keys, empty carry says "nothing was carried", carry counts kinds and surfaces unresolved + inert
exchanges, labels recovered from a rendered summary, and the handoff line of the real path.

## Checkpoint 2 — 7.3 and 7.4/7.5 bound to the real surfaces, 7.9 started

### 7.3 — the Thinking Tree now reads its states from the rows

`CopilotBindings.taskState` maps a REAL `ChatRowModel.ToolActivity` to a state, and the mapping is
deliberately conservative:

| Row says | State | Why |
|---|---|---|
| `ok = false` | FAILED | the executor's own verdict |
| text says refused / not run / declined | BLOCKED | it never ran, so "failed after running" would be a lie |
| text says uncertain / no response / may have run | UNKNOWN | neither success nor failure was established |
| `ok = true` and nothing else | **ACTIVE** | `ok` means "did not report failure", not "finished" |
| `[failed]`/`error` in the detail | FAILED | the detail carries the failure |

`ChatCards` now renders the group header through `CopilotBindings.groupHeader(...)`, so the header
carries a counted line ("0 of 3 done · 1 with no state reported"), and each child through
`childLine(...)`, which spells the state out: `[running]`, `[failed]`, `[refused]`, `[outcome unknown]`.
Identity is the runtime's own row id, so a streaming update cannot renumber a step. The chevron
grouping and the separate `/tools` catalogue remain two different features, untouched.

### 7.4 / 7.5 — the tool row is bounded and its outcome is stated

The row is created in `ChatSession.runTools` from the real call, and its detail now goes through
`CopilotPresentation.toolRow`, so the panel cannot receive an unbounded argument dump: the cut is
applied at creation and the row text states how much was omitted. `CopilotBindings.outcomeOf` /
`nextAction` classify the finished step, so a refused step says it never ran and may be adjusted and
retried, while an uncertain step says it may have run and offers verification instead of a retry.

Honest note: an earlier pass had already added `ToolResultPresentation` for this row (styling, "(partial)"
marker). This checkpoint adds the execution-truth classification and the bound, and does not replace it.

### 7.9 — first accessibility fixes, from a real audit of this file

| Finding | Fix |
|---|---|
| the tree chevron was the only tap target, roughly 12dp | the whole header Row is the control, `heightIn(min = 48.dp)`, `fillMaxWidth` |
| two competing clickables inside the header | inner ones removed, one gesture, one announcement |
| TalkBack read "▸" and a summary with no meaning | merged semantics with a description naming the group, its counts and the expand gesture |
| single tool row had the same tiny chevron target | same 48dp treatment plus a description naming the step and its outcome |
| status conveyed by colour | every state already renders a word; `statusText` makes the words distinct and tested |
| no labels for message rows | `messageLabel` (bounded to 200 characters) ready for the message composables |

Not done in this checkpoint: contrast measurement, text-scale layouts, landscape/small-screen review,
dialog/bottom-sheet semantics, streaming announcement throttling, and the message-row labels are not
yet bound to their composables.

## Tests (checkpoint 2)

15 cases in `Stage72BindingsTest`, built on real `ChatRowModel` rows and the real `ActivityGrouping`
output: `ok = true` never reads as done, `ok = false` is failed, a refusal is blocked and retryable after
adjustment, an uncertain step refuses a plain retry, task identity is the runtime id, the group header
counts only reported states, child lines spell states out, a 10,000-character result is bounded with both
numbers, an unfinished step claims no success, TalkBack labels name step + state + gesture, group
expanded/collapsed announcements, bounded message descriptions, the 48dp constant, and one distinct word
per state.

## Not delivered in this pass — stated plainly

Project Seven is **not complete**. The following areas were not implemented here:

- **7.1** typography/spacing/contrast sweep and TalkBack labelling of message rows.
- **7.2** composer and keyboard refinements beyond what already ships.
- **7.3** ~~binding~~ — DONE in checkpoint 2 (header, child lines, ids).
- **7.4** ~~wiring `toolRow`~~ — DONE in checkpoint 2 at the row creation site; execution truth added.

- **7.6** provider/model picker wording from `ProviderCatalogue` reasons.
- **7.8** research and document presentation.
- **7.9** started in checkpoint 2 (48dp header targets, TalkBack descriptions, non-colour state words).
  Still open: contrast measurement, text scaling, landscape/small screens, dialog semantics, streaming
  announcement throttling, long-message performance.
- **7.10** voice surfacing (no greetings were added, and none exist).
- **7.11** the full cross-surface integration verification.

The blocker is not a technical one: this delivery ran out of session budget before those areas could be
implemented and tested properly. Writing them as stubs would have been worse than leaving them undone.
Nothing above is claimed as device-verified; no APK was built or installed.
