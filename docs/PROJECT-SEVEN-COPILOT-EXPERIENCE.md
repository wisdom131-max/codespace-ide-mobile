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

## Checkpoint 3 — 7.9 measured contrast and 7.6 picker wording

**CI: run `43923652452` on `df2ba3e`, 26/26 steps, success.** An earlier dispatch (`43923037166` on
`0edf5d3`) was queued without ever being assigned a runner, the same stall seen before on this repo;
it was superseded by the replacement run rather than being counted as a result.


### 7.9A — contrast, measured instead of guessed

`ContrastAudit` computes the WCAG ratio from the actual `ChatTokens` values the renderer uses, for both
themes, over the ten pairs the chat really draws. The result, first run:

| Pair | Dark | Light | AA |
|---|---|---|---|
| body text on background | 11.25:1 | 16.67:1 | pass |
| body text on surface | 10.33:1 | 15.29:1 | pass |
| secondary text on surface | 5.65:1 | 4.89:1 | pass |
| secondary text on background | 6.15:1 | 5.33:1 | pass |
| accent on surface | 4.99:1 | 5.79:1 | pass |
| error on error surface | 4.57:1 | 4.83:1 | pass |
| warning on warning surface | 6.24:1 | 5.14:1 | pass |
| success on surface | 7.52:1 | 4.73:1 | pass |
| **hairline border on surface** | **1.60:1** | **1.41:1** | **fails 3:1** |

The one failure is real and it is a divider, not a control: WCAG 1.4.11 covers controls and states, so
the hairline stays a hairline (tested as such, so it cannot silently drift), and `strongBorder` derives a
3:1-capable border from the palette's own direction for edges that DO carry meaning, such as a focused
input. Tests assert both the exemption and the derived border.

### 7.9E — streaming announcements

`CopilotAccessibility.announcement` announces the start of a reply, then at most once per 400 characters,
plus every terminal transition (complete, failed, interrupted) with a bounded tail. Small growth is
silent, which is the point: announcing per token drowns the events that matter.

### 7.6 — the picker names provider and model separately

`ModelPickerSection`'s header said "Model". It now shows `Google Gemini · Gemini 2.5 Pro` and carries a
TalkBack description stating what is in effect ("Using ... Choosing another model replaces it.").
`ModelPickerPresentation` supplies the rest of the wording from the existing `PickerModel` data:

- capability lines never invent a value: an unknown context window reads "not known for this model. VN
  Code will not invent one";
- a custom endpoint opens with "its capabilities are not verified. Nothing here assumes them", and its
  reasoning line reads "not known for this endpoint" rather than "not offered";
- an alternative is offered as "Try ... instead? Nothing changes unless you pick it.";
- dismissing without choosing keeps the previous selection (`selectionAfterDismiss`), tested;
- an empty search says "The list is filtered, not empty", so a filter never reads as a provider failure.

No selection logic was added: `ModelPickerSpec` and Project Five's catalogue remain the only source, and
nothing here can switch provider, model, endpoint or key on its own.

## Tests (checkpoint 3)

21 new cases:
- `Stage79ContrastTest` (6): the ratio formula against known values (21:1, 1:1), every text pair AA in
  both themes, the derived strong border reaching 3:1 in both themes, the hairline documented as
  decorative, and the audit reporting the pairs it measured.
- `Stage79AnnouncementTest` (6): first token announces the start, small growth is silent, a 460-character
  jump announces once with progress, all three terminal transitions announce, the tail is bounded, an
  empty update is silent.
- `Stage76PickerPresentationTest` (9): provider/model identity separate, no-selection stated, unknown
  context said to be unknown, custom endpoint capabilities assumed by nothing, reasoning support stated
  not guessed, alternatives offered as choices, dismissal keeps the selection, unsupported lines invent
  no limit, empty search is a filter not a failure.

## Manual verification still required for 7.9

These cannot be measured from a JVM, and are not claimed:

1. Font scale 1.3 and 2.0 (Settings, Display, Font size): tree header, tool row and picker header must
   wrap without clipping; no control may fall below 48dp.
2. Landscape and a narrow column: composer, message list and expanded tree remain reachable; nothing
   hides behind the keyboard or navigation bar.
3. TalkBack: focus enters the tree header, reads the group counts, expands, and returns focus to the
   header after a dialog dismisses.
4. Streaming with TalkBack on: confirm silence between the 400-character announcements and one
   announcement per terminal transition.
5. Long response (10k+ characters) with the tree expanded: scrolling stays smooth, no frame stalls at
   the bound.

## Existing UI preservation — what this branch actually changed, and what it did not

Owner rule (2026-11-16): Project Seven improves the existing Copilot UI. It is not a redesign. Approved
components stay as they are unless a demonstrated defect requires a change, and then the smallest one.

Every surface touched by this branch, with the defect and the exact change:

| Surface | Defect demonstrated | Change made | What did NOT change |
|---|---|---|---|
| Thinking Tree header (`ChatCards`) | the chevron alone was the tap target, about 12dp, and TalkBack read "▸" with no meaning | header Row became the control at `heightIn(min = 48.dp)` with one merged semantics description | chevron glyphs, fonts, colours, padding, grouping rules, the separate `/tools` catalogue |
| Tree child line | state was carried by colour and by expanding a row | `childLine` prefixes a `[state]` word | detail text, indentation, expand behaviour |
| Single tool row (`ChatCards`) | same ~12dp target; detail unbounded when a call had large arguments | 48dp header, bounded detail with the cut stated | card shape, radius, padding, dismiss rules (still no dismiss x) |
| Tool row creation (`ChatSession.runTools`) | the row could receive an unbounded argument summary | detail passes through `CopilotPresentation.toolRow` | the execution path, gates, approvals, ordering, ids |
| Picker header (`ModelPickerSection`) | header said "Model", so provider and model were indistinguishable, which 7.6 explicitly asks to fix | text became `Provider · Model` with a TalkBack description of what is in effect | row layout, search field, effort menu, Auto-absence rule, selection logic |

Untouched, deliberately: the composer and input box, attachment chips and their controls, message bubble
layout, copy rows, Sources rows, empty state, overflow menu, and every dialog shape. No component was
restyled, relocated or simplified for visual consistency.

The picker header is the one visible text change on an approved component. It was requested by the 7.6
brief ("distinguish provider identity from model identity"), it is one string plus a semantics
description, and it is listed here so it can be vetoed and reverted on its own without touching anything
else.

## Checkpoint 4 — 7.6 list honesty and the rest of 7.9 that could be fixed without redesign

### Finding that removes work instead of adding it: there is no live model fetch

7.6 A asks for an honest loading state. The audit found there is nothing to be loading: `ModelPickerData`
assembles the list from the preset default, the owner's saved ids, and the ids the endpoint reported on
the *last* fetch, all read from disk synchronously in `ChatPanelScreen`. Adding a spinner or a
"Loading models…" line would be theatre, and would claim a request the app never makes. It was not added.

What was added is the sentence that is actually true when a list is thin:

- endpoint has never reported a list: "This endpoint has not reported its model list. Rows marked Other
  are the built-in catalogue, not a promise about what the endpoint serves."
- nothing stored at all: "No models are stored for this provider yet. Add one, or pick from the catalogue."
- store unreadable: no note, because `ModelListUnreadableNotice` already says it better and offers Retry.
- otherwise: no note. A normal picker looks exactly as it did.

`listNote` is wired in `ChatPanelScreen` from the reads that actually happened, and the section renders it
only when non-null, so the approved layout is byte-identical in the ordinary case.

### 7.6 B — error states, classified and secret-free

`ModelPickerPresentation.listError` covers network, authentication, rate limit, empty, malformed and
custom-endpoint failures. Tests assert that no message contains a status code, an `Authorization` or
`Bearer` fragment, or a promise that something was switched. Auth and rate-limit messages state that the
selection is unchanged; the custom-endpoint message keeps capabilities explicitly unknown.

Honest limitation: the picker itself makes no request, so these are the sentences available to whichever
surface does fetch later. They are not wired to a fetch path that does not exist.

### 7.9 A — truncation that hides information, fixed non-visually

Source rows draw the title with `maxLines = 1` and an ellipsis. At a large font scale that ellipsis eats
the part that identifies the source. The row is intentional in the layout, so it was left alone
visually; the row now carries a merged `contentDescription` with the full title and domain and the fact
that it opens. Appearance is unchanged; TalkBack reads what the screen cannot show.

### 7.9 C — dialog titles were body text

Audited `RowActionsDialog`, `FilterDialog` and `KeysManagerDialog`: each draws a title as ordinary text,
so TalkBack reads it inline and heading navigation cannot reach it. All three titles now carry
`semantics { heading() }`. Nothing visual changed.

`ChatLogDialog` was inspected and its title is drawn inside the same column as its actions; adding a
heading there is safe but it was left for a dedicated pass rather than changed blind, and is listed below.

### 7.9 D / E — verified, not changed

- Message bodies are already read verbatim by TalkBack, which is what a reader wants. `messageLabel`
  truncates to 200 characters and is therefore only suitable for compact summaries and navigation; it was
  deliberately NOT applied to message bodies, because truncating the assistant's answer for a screen
  reader would be worse than reading it in full.
- Copy rows are separate composables with their own click action, so they stay independently reachable and
  are not merged into the message.
- `MarkdownView` already memoises parsing on the exact text (`remember(text)`), code blocks are capped by
  `TextRenderLimits`, and streaming rows update one Text node. No repeated parsing or retained per-token
  state was found, so nothing was restructured for performance.

### 7.9 B — narrow screens and landscape: documented, not redesigned

The layout is column-based with `fillMaxWidth` rows and no fixed text heights in the chat surfaces. The
things that would break at a narrow width are the composer and attachment strip, which are approved and
were not touched. Manual checks are listed below rather than pre-emptively restyling approved components.

## Tests (checkpoint 4)

7 new cases in `Stage76ListStateTest`: no note for a healthy list, "not reported" note never claiming a
load, unreadable left to its own notice, the empty-list sentence, one distinct message per error kind with
no status codes or credentials in any of them, auth and rate-limit stating the selection is unchanged, and
custom endpoints keeping capabilities unknown.

## Manual verification still required (unchanged from checkpoint 3, plus)

1. Font scale 1.3 and 2.0: source rows ellipsise visually but read in full to TalkBack; tree header and
   tool row wrap without clipping; no control below 48dp.
2. Landscape and narrow width: composer, message list, expanded tree and picker remain reachable; nothing
   hides behind the keyboard or navigation bar.
3. Dialogs: TalkBack announces each title as a heading; focus enters the dialog, stays within it, and
   returns to the invoking control on dismiss. `ChatLogDialog` needs its heading added and checked.
4. Streaming with TalkBack: silence between 400-character announcements, one announcement per terminal
   transition.
5. Long response (10k+) with the tree expanded: scrolling stays smooth.
6. Picker keyboard: Tab reaches rows, Enter selects, Escape dismisses and focus returns to the chip.

## Not delivered in this pass — stated plainly

Project Seven is **not complete**. The following areas were not implemented here:

- **7.1** typography/spacing/contrast sweep and TalkBack labelling of message rows.
- **7.2** composer and keyboard refinements beyond what already ships.
- **7.3** ~~binding~~ — DONE in checkpoint 2 (header, child lines, ids).
- **7.4** ~~wiring `toolRow`~~ — DONE in checkpoint 2 at the row creation site; execution truth added.

- **7.6** started in checkpoint 3: header, capability honesty, alternative framing, dismissal.
  Still open: loading/error/unavailable states of the live model fetch, keyboard navigation inside the
  picker, and binding the remaining capability lines into every row.
- **7.8** research and document presentation.
- **7.9** partially done: checkpoint 2 (48dp targets, TalkBack, state words) and checkpoint 3 (measured
  contrast, strong border, streaming announcements). Manual device checks listed above remain.
  Still open: contrast measurement, text scaling, landscape/small screens, dialog semantics, streaming
  announcement throttling, long-message performance.
- **7.10** voice surfacing (no greetings were added, and none exist).
- **7.11** the full cross-surface integration verification.

The blocker is not a technical one: this delivery ran out of session budget before those areas could be
implemented and tested properly. Writing them as stubs would have been worse than leaving them undone.
Nothing above is claimed as device-verified; no APK was built or installed.
