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

## Not delivered in this pass — stated plainly

Project Seven is **not complete**. The following areas were not implemented here:

- **7.1** typography/spacing/contrast sweep and TalkBack labelling of message rows.
- **7.2** composer and keyboard refinements beyond what already ships.
- **7.3** binding `CopilotPresentation.taskTree` into the Thinking Tree composable.
- **7.4** wiring `toolRow` into the tool-result row builder (the helper exists and is tested; the row
  path is untouched).
- **7.6** provider/model picker wording from `ProviderCatalogue` reasons.
- **7.8** research and document presentation.
- **7.9** the accessibility and performance pass (48dp targets, contrast, recomposition).
- **7.10** voice surfacing (no greetings were added, and none exist).
- **7.11** the full cross-surface integration verification.

The blocker is not a technical one: this delivery ran out of session budget before those areas could be
implemented and tested properly. Writing them as stubs would have been worse than leaving them undone.
Nothing above is claimed as device-verified; no APK was built or installed.
