# M3, M4, M5 — chat rebuild completion report

Date: 2026-10-30. Base: `899edc2` (M0/M1). This document covers M2 through M5; M2 has its own
report (`vncode-m2-sessions-panel-2026-10-30.md`).

## M3 — overflow surface, real behaviours

Six items, exact order and labels, from the recording's menu:

1. **Undo Last Edit** — one action over BOTH AI write paths. Journal sets (agent tool writes) are
   undone through the existing `AgentWriteJournal.undo`, unchanged, which verifies by re-reading
   the file and refuses when the agent's content is already gone. Staged proposals (Edit mode) go
   through `PendingChangesStore.undoLastApply`, unchanged.
2. **Redo Last Edit** — NEW. `AgentWriteJournal.redo` mirrors undo's safety rule: it refuses when
   the disk no longer holds what the undo left behind, so a hand edit between undo and redo is
   never clobbered. The staged path is redone from a byte snapshot captured at undo time
   (`LastEditActions`), with the same verify-before-and-after rule; files over 512 KB are reported
   rather than snapshotted.
3. **Move Chat into Editor Area** — the chat becomes a real editor TAB identified by
   `ChatTabSupport.TAB_ID` ("chat://panel", a scheme a file path can never be). The tab renders the
   same `ChatPanelScreen`; opening it closes the sidebar so there is one chat surface and therefore
   one composer. Closing the tab returns the chat to the sidebar rather than losing it.
4. **Move Chat into New Window** — a genuine second Android TASK: `ChatWindowActivity` declared
   with `documentLaunchMode="always"` (own Recents entry) and `resizeableActivity="true"` (float or
   split on devices that support it). Full screen is not used as a substitute and the label is
   unchanged.
5. **Show Voice Transcripts** — opens the real transcript list for this conversation (time +
   text), with Clear. Nothing is fabricated: an empty list says so.
6. **Show View by Default** — persisted preference (`show_chat_by_default`) that decides the
   panel's OPENING state; the header and the sidebar toggle still control the current window.

Undo/redo and the editor-area item are disabled WITH A REASON when they genuinely cannot act; no
item is ever a dead button.

### Voice dictation (owner decision: option A)

`RecognizerIntent` (the same mechanism the terminal uses), triggered by `Mic` in the chat header.
The recognised text is appended to the composer for review, never auto-sent, and recorded in
`VoiceTranscriptStore` keyed by (project, conversation). Outcomes are honest per case: no
recogniser installed, recogniser error, nothing recognised, and user cancel are distinct. The mic
sits beside the composer rather than inside its 40 dp toolbar row because that row is
video-faithful and width-sensitive; moving it is a one-line change if preferred.

### Multi-window safety

* One `ChatRuntime` per process, reference counted (`RefCounter`, unit-tested): the manager is
  created for the first host and closed only when the LAST host leaves, so closing a window cannot
  cancel a stream running in the other.
* One writer per transcript: the manager caches exactly one `ChatSession` per
  (projectId, conversationId), so both windows render the same state object rather than two writers
  racing on one file.
* A staged file opened from the detached window is routed to the main task's editor through
  `ChatFileOpenRequest`, so the tap is not a silent no-op.

## M4 — empty conversation surface

Composition from the recording: mark, welcome heading, supporting guidance, centred above the
existing composer. The mark is this product's own `ic_splash_logo`, not the VS Code editor
watermark, which the design rules out. The heading does not borrow the reference's feature name
("Build with Agent" is a capability this app does not have); the same slot carries VN Code's own
sentence. No link or button is drawn, because nothing in that state has a real action.

Shown only when nothing is happening: a stream, a staged attachment, a setup problem or an
unreadable-settings state each keep their own honest surface.

## M5 — memory scope

* `MemoryScope`: `CURRENT_SESSION` ("Current session only") and `ALL_SESSIONS` ("All sessions"),
  persisted as `memory_scope` in `ChatSettingsPrefs` and read AT EACH ACCESS, so a change applies
  to the next tool call without a restart.
* Attribution: `MemoryAttribution` records the owning session per key in
  `agent_memory/attribution.tsv`, beside the unchanged `memory.json` (old builds still read every
  value).
* Enforcement is in the read path, not the UI: `read_memory`, `delete_memory` and `get` filter by
  scope, so a hidden memory never reaches the model's context and cannot be deleted blind.
* Legacy memories (written before attribution) are treated as GLOBAL and stay visible in both
  scopes: an upgrade must not silently erase knowledge the owner already had.
* Control: a labelled "Memory scope" row in the Sessions panel that spells out the current value
  and what it means, and cycles + persists on tap.

## Verification

| Check | Result |
|---|---|
| `:app:compileDevDebugKotlin` | BUILD SUCCESSFUL |
| `:app:testDevDebugUnitTest` | 1132 tests, 1 failure: `BackupTarTest.failureMessageCarriesRealTotals` (expected 2, got 0) — the known root-sandbox failure, green on CI's non-root runner |
| `:engine:test` | BUILD SUCCESSFUL, 0 failures |
| New M3 tests | `ChatOverflowSpecTest` (7), `VoiceTranscriptStoreTest` (8), `LastEditRedoTest` (8), `M3WindowHostTest` (6) |
| New M5 tests | `MemoryScopeTest` (10) |
| M2 tests | 27 (spec, view logic, wiring) |

Not verifiable in this sandbox, deferred to the owner's device round by instruction: the real
dictation round trip (needs a recogniser), the two-task lifecycle (Recents, closing either task),
and the on-screen composition of the welcome screen and the editor-area tab.

## Known limitations

1. Side-by-side chat + sessions columns are not implemented; the sidebar's 600 dp ceiling cannot
   fit two readable columns (the composer alone is specified at a 280 dp minimum).
2. Staged proposals (`PendingChangesStore`) are still attributed per project, not per conversation,
   so `Undo Last Edit` covers the project's staged batch. Session-correct attribution needs a
   migration of existing staged records and is the natural next step.
3. Content search across sessions needs the Phase 2 retrieval index; search covers titles and ids.
4. The Journals' undo/redo history is in-memory (process lifetime), unchanged from before.
5. Voice transcripts are text only; no audio is stored.
