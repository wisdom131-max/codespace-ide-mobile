# Chat rebuild — batches 1 to 8, verified record

Date: 2026-10-29. Scope: the chat parity remediation listed in
`docs/chat-parity-report-2026-10-29.md`. Nothing here restores the archived panel
(`archive/vn-code-archive`) and nothing redesigns the current UI.

## Final CI verdict

| | |
|---|---|
| Commit | `0aad66f` |
| Run | **40934863655 — SUCCESS** |
| Suite | 871 unit tests, 0 failures |

No green claim is made for any earlier commit. The honest history follows, because the
first pushes on this chain were RED and the reasons matter.

## Commit chain, in order

| Commit | Batch | Result |
|---|---|---|
| `ad79d63` | 1 (workspace block `Unit` return) + 2 (EDIT-mode staging) | **RED** — two compile errors, see below |
| `54060ac` | fix: `TranscriptSink` public; `gen` reaches `runTools` | RED — next visibility error |
| `665b574` | fix: `TranscriptLoad` / `StoredMessage` public | RED — 5 unit tests failed |
| `0aad66f` | fix: the 5 real failures | **GREEN 40934863655** |

Batches 3-8 were authored earlier as `130e4c0` (auto-scroll, GREEN run 40923841502),
`6ac4ec3` (persistence), `c758268` (status rows), `2c65917` (429 retries),
`8f31cf5` (Markdown) and `f698d7f` (model picker). They were pushed on a base that did
not compile, so their own runs were red; they are green as part of `0aad66f`.

## What the compile errors were (both pre-dated batches 5-8)

1. `ChatSession`'s public constructor takes the persistence seam while `TranscriptSink`
   was `internal`. Kotlin forbids that, so every push on top inherited the red base.
   The CONTRACT is now public; the implementation (`TranscriptFiles`, `ChatTranscriptStore`,
   the row/message mapping) stays internal.
2. Batch 6 threaded the send generation through `attemptSend` and `onEvent` but not
   `runTools`, so the tool continuation had no generation to pass and a retry armed after a
   tool round could not belong to the same message.

## What batch 2 does, and where it was checked

Verified by reading the current tree, not by commit message:

- EDIT-mode `write_file` is decided by `AgentToolWire.isStagedWrite(mode, name)`; the only
  producer is `ChatSession.stageProposal`, which calls `PendingChangesStore.stage(...)`
  (`chat/panel/ChatSession.kt:554-565`). There is no fall-through to `AgentTools.writeFile`.
- Nothing reaches disk: `stage()` writes a private pending record. The user's Apply is the
  authorisation and runs through `PendingChangesStore.applyAll`, the existing drift-safe path.
- The model is told the truth: `AgentToolWire.stagedNote` says what was proposed, its size,
  and "Nothing has been written to disk". A `FAILED` staging result is reported as failed and
  the model is never told a write happened.
- The row: `ChatStagedChangesRow(files, onOpen, onApplyAll, onDiscardAll, note)` renders only
  when proposals exist, with Open, Apply all and Discard all.
- Open goes through the shell's `onOpenFile`, which adds the tab and sets `activeEditorTab`
  (the editor column renders it); it does not bypass the binary-open guard.
- Session boundary: the scope is stamped from the session's `projectId` before staging, so a
  project switch cannot mix proposals.
- Tests: `AgentToolWireTest.editModeStagesWritesInsteadOfTouchingDisk` and
  `aStagedNoteNeverClaimsTheFileWasWritten`.

### Still unverified for batch 2

- No UI-level test exists for the staged row (the panel composables are not covered by the
  JVM suite; that needs the Robolectric dependency noted in the parity report).
- Multi-file apply, a mid-batch copy failure and "Discard all while the file changed on disk"
  are covered by the store's own logic and `StagedApplySummary`, not re-exercised here.
- Applying across a project switch, and a proposal left pending while the app is killed, are
  device checks.

## Batch 3 (auto-scroll) — already green

`TranscriptAutoScroll` keeps the newest content visible only while the user is following;
`isScrollInProgress` cancels follow mode so scrolling up is never overridden, and returning
to the bottom re-arms it. Green as `130e4c0`, CI 40923841502, and re-verified green inside
`0aad66f`. No change was needed in this pass.

## Batches 4-8, one line each

- **4 persistence:** `ChatTranscript` + `ChatTranscriptStore`, atomic write with `.bak`
  recovery, interrupted replies labelled, restore guarded against overwriting a live
  conversation.
- **5 status rows:** `ChatStatusMapping` maps the error taxonomy to cards; stale status cards
  are cleared on send; `HistoryTrimmed` reaches the UI; a resolved wait card is removed even
  when a Thinking row sits between it and the answer.
- **6 429 retries:** at most 2 automatic attempts, honours `Retry-After` up to 30s, 5s then
  15s ladder otherwise, generation-guarded so Stop kills an armed retry. Stop now also clears
  `streaming` (was: composer stuck busy forever after a wait).
- **7 Markdown:** pure parser with flanking rules, fence lengths and verbatim code; renderer
  with Copy and Insert (Insert routes through `EditorInsertBus`). Closing markers are
  consumed, not re-read.
- **8 model picker:** `ModelPickerData` + `ModelPickerSection` put the tested `ModelPickerSpec`
  behind the dialog: search, preferred models, Other Models group, no invented prices. A
  provider change is delegated to `SelectionResolver`; gemini and anthropic are not
  `ProviderPresets`, so deriving their endpoint in the picker was wrong and is removed.

## Device checks outstanding

Composer proportions and the Add-context chip, the staging row (Open / Apply all / Discard
all on a real file), Stop during a 429 wait, Markdown Copy/Insert into the editor, the
searchable picker against a real endpoint, and conversation restore after a process kill.

## Deliberately not implemented

Document extraction and cross-session AI memory, per the owner's scope ruling.
