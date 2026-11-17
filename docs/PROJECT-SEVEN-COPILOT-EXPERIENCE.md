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

## Checkpoint 4 — 7.6 list honesty

**CI: run `43938472824` on `5c3f59e`, 26/26 steps, success.**
 and the rest of 7.9 that could be fixed without redesign

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

## Checkpoint 5 — 7.9 closed, and the three 7.1 defects the audit actually found

**CI: run `43947449477` on `4d3641f`, 26/26 steps, success.**

### 7.9C closed: `ChatLogDialog`

Its title was plain body text, so TalkBack read it inline and heading navigation could not reach it. One
modifier (`semantics { heading() }`) on the existing `Text`, weight and layout untouched. Every Project
Seven dialog title is now a heading: `RowActionsDialog`, `FilterDialog`, `KeysManagerDialog`,
`ChatLogDialog`.

### 7.1D — the message list had NO follow behaviour at all

The transcript is a `Column` with `verticalScroll(rememberScrollState())`. Nothing followed new content, so
a streaming reply could grow entirely below the fold and the user had to scroll manually to read it.
Following unconditionally would be the opposite defect: it yanks the reader to the bottom while they are
reading earlier content.

The rule is now explicit and tested: follow only when the reader is within one line (96dp) of the live end;
otherwise keep still. `ChatScrollFollow.shouldFollow` holds the comparison, `ChatPanelScreen` uses it in a
`LaunchedEffect` keyed on row count and streaming, and a cancelled scroll (the reader taking over) is
swallowed rather than surfaced as an error. No new state, no change to the list's appearance.

### 7.1C — the copy glyph was roughly a 30x24dp target

Measured from the code: a micro-font glyph with 8dp/4dp padding. It now carries
`minimumInteractiveComponentSize()` (Material's 48dp touch area, applied without resizing or moving the
glyph) and `role = Role.Button`, so TalkBack announces a button and `onClickLabel = "Copy message"`
supplies the action. The copy row looks identical; its target grew.

Copy content itself was audited and is correct: `CopyIconRow` receives the message text and nothing else,
so no UI label can be folded in, and a code block's Copy still copies the WHOLE block while the display is
capped by `TextRenderLimits` (that distinction was already correct and is documented in the block itself).

### 7.1 A/B/E and 7.2 — audited, found already correct, not rebuilt

This is the honest part of the checkpoint: most of 7.1 and 7.2 already works, and rewriting it would have
been the redesign this brief forbids.

| Requirement | Where it lives | Evidence |
|---|---|---|
| message types distinguished by data, not text | `ChatRowModel` sealed rows (`User`, `AssistantStream` with `StreamKind`, `Failed`, `InfoNote`, `ToolActivity`, `Sources`, `Attachments`) | renderer switches on the type |
| incomplete vs complete replies | `ChatSession` sticky incomplete flag; M1 keeps an interruption incomplete until the next send | `ChatSessionInterruptTest`, `FinishRestorationTest` |
| output-limited replies | the ceiling marks the reply incomplete through the same sticky flag | `ChatSession` ceiling path |
| Markdown | `MarkdownParser.parse` memoised on the exact text | `MarkdownView` |
| large code blocks | `TextRenderLimits.cap` draws a head and states the cut; copy keeps the source | the block's own comment and cap note |
| one send per action, no duplicate replies | `DeliveryLedger` / `ReplyOwnership` on the real session paths | Stage 4.8 suites |
| cancellation reaching the active request | `ChatSession.cancel()` cancels the execution and bumps the generation | Stage 4.8 suites |
| stale stream events | generation bump + ownership check | Stage 4.8 suites |
| tool messages tied to their reply | tool rows are created inside the executing round with round/step ids | `Stage72BindingsTest` |
| streaming announcements | `CopilotAccessibility.announcement`, 400-character policy | `Stage79AnnouncementTest` |

Not rebuilt, and not claimed as newly implemented.

### 7.2 C/D — keyboard, focus and input integrity: manual checks, not code changes

The composer is approved and was not touched. Focus after send, modifier restoration, attachment
association and back-button behaviour need a real device; the exact checks are listed below.

## Tests (checkpoint 5)

5 new cases in `Stage71ScrollFollowTest`: at the bottom follows, within one line follows, scrolled back does
not follow, an unscrollable list follows, and the tolerance is one line rather than half the screen.
The dialog heading and the 48dp copy target are semantics-only changes and cannot be asserted from a JVM;
both are listed under manual verification instead of being claimed.

## Manual verification still required

1. Read a reply, scroll up, keep reading while more text streams: the view must NOT jump.
2. Stay at the bottom while a reply streams: it must follow, once per row change.
3. Copy glyph: a thumb tap anywhere in its 48dp area copies; TalkBack says "button, Copy message".
4. Dialog titles: TalkBack announces "Chat log, heading" and heading navigation reaches it.
5. Font scale 1.3 and 2.0, landscape, narrow width: unchanged components wrap without clipping.
6. Picker keyboard, streaming announcement silence, long-response scrolling (unchanged from checkpoint 4).

## Checkpoint 6 — Copy target restored to 30x24dp at the owner's request; Area 7.8

**CI: run `43952601203` on `60bd32d`, 26/26 steps, success.**

### The Copy button is 30x24dp because the owner said so

Checkpoint 5 expanded the Copy glyph's touch target to Material's 48dp interactive size. The owner reviewed
it and asked for the original size back. That is a design decision, not a defect to argue with, so it was
reverted exactly:

- `minimumInteractiveComponentSize()` removed, and its import removed with it.
- The click area is the glyph plus its own padding again: 14dp glyph + 8dp horizontal padding = **30dp**
  wide; 16dp glyph + 4dp vertical padding = **24dp** tall.
- The glyph itself was never resized or moved, so the visible row is byte-identical to before checkpoint 5.
- `role = Role.Button` and `onClickLabel = "Copy message"` were kept: they cost no pixels and still tell
  TalkBack what the control does.
- No parent hitbox, gesture layer or invisible expansion was added to defeat the request.

`CopyTarget` publishes the numbers and `Stage71CopyTargetTest` pins them, including a source guard that fails
if `minimumInteractiveComponentSize` reappears in `CopyIconRow.kt`. That guard exists because this size was
already changed once by an accessibility pass; the owner's decision now outranks it in code.

Everything else from the accessibility work stays: tree and tool-row 48dp targets, dialog headings,
source-row TalkBack descriptions, the contrast audit and `strongBorder`, and the 400-character streaming
announcement policy.

### 7.8 A/B — sources: audited, mostly already correct, one real gap closed

`ResearchSources.sanitise` already does the honest work, and it was verified rather than rebuilt: it drops
URLs that cannot be opened, removes duplicates by normalised URL, derives a missing domain from the URL, and
falls back to the domain (then the URL) when a title is blank. Sources are attached to the answer that
produced them, not to the session, so they cannot drift to a later reply.

The gap was size. `SourcesRow` drew every item, and research can return a hundred. It now renders at most 40
and states the rest: "+60 more sources are cited in the answer but not listed here." Nothing is silently
dropped, the number is exact, and the ordinary answer draws exactly as before because the line is null when
nothing was omitted.

`ResearchPresentation` also supplies the header count (including "No sources"), the per-source TalkBack
sentence (title, domain, and whether it opens or cannot be opened), and `emptyNote` for research that
returned nothing usable, which is a result rather than a success.

### 7.8 C/D — documents and media: no presentation invented

The audit found no fabricated surface to fix. Document and media work happens through existing tools whose
rows carry their own execution status (running, ok, failed, blocked, uncertain) and whose detail is bounded at
creation. No claim of full document inspection, image analysis, transcription or video inspection exists in
the presentation layer, and none was added: there is no such capability behind it. Extraction partiality is
carried by the tool's own result status, which the row already distinguishes rather than smoothing into
"done".

### 7.8 E/F — large content and accessibility

Large source lists are bounded as above. Long extracted text is bounded by `TextRenderLimits` at creation with
the cut stated, and copy keeps the full source. Source semantics keep the full title available to TalkBack
even though the row ellipsises it at one line, which was checkpoint 4 work and is unchanged.

## Tests (checkpoint 6)

12 new cases: `Stage71CopyTargetTest` (4) pins 30x24dp, asserts neither dimension reaches 48dp, fails if the
Material expansion returns to the file, and asserts the role and action label survive. `Stage78ResearchPresentationTest`
(8) covers an unbounded list drawing normally, a 100-item list bounding to 40 with the exact 60 stated,
exactly-at-the-bound withholding nothing, the per-source description, missing metadata stated rather than
blank, an unopenable URL described as such, honest header counts, and empty research reported as a result.

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

## Checkpoint 7 — 7.10 Live Voice

**CI: run `44000347897` on `3fbedd4`, 26/26 steps, success.**
: audited first, then the one thing that was fake

### The voice stack already existed, and I did not rebuild it

`agent/voice/` was already ~2,500 lines: `VoiceTurnMachine` (phases, mic, barge-in flag, mute),
`VoiceController`, `VoiceRecognizer` (SpeechRecognizer), `VoiceCapture` (AudioRecord), `VadPolicy`
(real dBFS RMS, adaptive floor, utterance boundaries), `AndroidTtsProvider` (one utterance at a time,
stop, unavailable state), `VoiceScript` (segmenting), `VoiceModeScreen` (Canvas orb), `VoiceSettings`.
A microphone entry point already sits in the panel header and opens `VoiceModeLayer`, which submits
into the SAME session. So the brief's scaffolding mostly exists; the honest job was to verify it and
fix only what was untrue.

### What was NOT real: the orb while Copilot speaks

`VoiceTurnMachine` set `energy01 = 1f` on entering SPEAKING and at each reply event. A constant 1.0
is not audio reactivity, and calling it synchronised would have been the exact lie this brief warns
about. Android exposes no amplitude for another engine's output: the TTS audio session is not
app-visible and `Visualizer` on session 0 is refused on modern platforms.

What the platform DOES deliver is `UtteranceProgressListener.onRangeStart`, which fires at the moment
the engine begins each spoken word. That is real playback data, so:

- `SpeakingPulse` (new) turns a word boundary into a decaying level: peak at the word, decay across
  the MEASURED gap to the next word, clamped to 0..1.
- `AndroidTtsProvider` now overrides `onRangeStart` and reports `onWordStart()` on its listener, with
  a default no-op so existing listeners are unchanged.
- `VoiceController` measures the real gap between consecutive words and dispatches a new
  `Event.WordBoundary(atMs, gapMs)`.
- `VoiceTurnMachine.State` remembers `lastWordAtMs`/`wordGapMs`; a boundary never changes phase.
- `VoiceOrb` computes speech energy from `SpeakingPulse.energyAt` on a 60ms read of the decay, so the
  orb pulses with the cadence of what is actually being said.

Honest limit, stated in the code: this is word-boundary driven, not waveform driven. It follows
speech rhythm and cannot reflect loudness. Listening energy remains the real microphone RMS through
`VadPolicy`, which was already honest and was not touched.

### Not changed

The composer, attachments, bubbles, copy rows, sources rows, dialogs, Thinking Tree, tool rows and
picker are untouched. The mic button stays where it already was in the panel header. 7 new cases
(`Stage710SpeakingPulseTest`).

## Checkpoint 8 - Area 7.11

**CI: run `44003217965` on `782f74b`, 26/26 steps, success.**, the cross-component audit and the two seams it broke open

### Defect 1 (fixed): a refused tool was presented as a failure
`CopilotBindings.outcomeOf` mapped `BLOCKED` to `ToolOutcome.FAILED`, so a step the runtime refused
rendered as "git_push failed before completing" with a plain Retry. The presenter already had a
`refusal` branch that says "was refused before it ran" with "Adjust and retry"; the binding never
passed it. Reproduction: any refused tool row. Fix: `isRefusal()` reads the runtime's own words and
`resultRow`/`nextAction` now pass `refusal = true`, so refused, failed and unanswered are three
different answers. Regression test: Stage711IntegrationTest.

### Defect 2 (fixed): a successful step was described as "may have run"
`resultRow` used the state mapping, which treats "ok with no verdict word" as ACTIVE, so a step that
plainly returned output was shown as uncertain and offered a verification step it did not need.
Fix: `resultOutcome()` treats a result row that returned ok, carries no failure marker and has real
output as SUCCESS, while a step with no output stays UNCERTAIN. `nextAction(activity, output)` is the
result-aware overload; the no-output overload stays conservative on purpose.

### Audit evidence, area by area
| Area | Implemented | Runtime data driving it | Tests | Device-verified |
|---|---|---|---|---|
| 7.1 chat/presentation | yes | ChatRowModel from ChatSession | Stage711, presentation suites | no |
| 7.2 streaming/composer | pre-existing | real stream events, DeliveryLedger | ChatSessionInterrupt, ChatTranscript | no |
| 7.3 thinking tree | yes | ToolActivity rows via CopilotBindings | Stage72, Stage711 | no |
| 7.4 tool rows | yes | real ToolActivity + output, 600-char bound | Stage72, Stage711 | no |
| 7.5 errors/recovery | yes | ToolOutcome + refusal from runtime text | Stage711 | no |
| 7.6 picker | yes | saved/live reads, no silent switch | Stage76, Stage76ListState | no |
| 7.7 continuation | pre-existing + carried line | SessionHandoff summary, HandoffGuard | Stage63, Stage711 (guard) | no |
| 7.8 research/docs | yes | ResearchSources sanitised + bounded 40 | Stage78, Stage711 | no |
| 7.9 accessibility | partial | measured contrast, semantics, 48dp tree | Stage79Contrast, Stage71CopyTarget | no |
| 7.10 voice | pre-existing + pulse | mic level, TTS word boundaries | Stage710, Stage711 | no |
| 7.11 integration | this checkpoint | real objects across seams | Stage711 | no |

### Voice audit findings
Entry point wired (header `onVoice` -> `showVoice` -> VoiceModeLayer). Recognition enters
`submit = { session.send(transcript) }`, so voice uses the same engine, provider and tool gate as
typing. Replies reach TTS through `controller.onAssistantText`, and while voice mode is open the
panel's own read-aloud is suppressed, so a reply is not spoken twice. `SubmitTurn` is guarded by a
`submitted` set, so a recogniser that repeats a final result still submits once. Word boundaries reach
`SpeakingPulse` through the controller listener.

### Limitations held, not resolved
Orb is word-boundary-synchronised, not amplitude-synchronised. Capture is muted while speaking as the
echo strategy. Platform recognition is turn-based. Nothing here is device-verified.
