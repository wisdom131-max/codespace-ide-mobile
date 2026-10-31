# VN Code — 19-requirement audit (Phase 1, read-only)

Date: 2026-10-30. Base commit: `d6461e2` (`main`, CI green run 41083805157, versionCode 3545).
No source file was changed in this phase. Method: source inspection only; every status below names
the file and line that produced it, and statuses I could not settle by reading are marked
`NEEDS-CHECK` rather than guessed.

## Classification

| # | Requirement | Status | Evidence (file:line) |
|---|---|---|---|
| 1 | AI terminal activity dismissal (X) | MISSING | `agent/AgentTerminalFeed.kt` (feed only), `ui/panes/TerminalAgentRunStrip.kt:45-68` (per-run expand, no dismiss), no `onDismiss*` symbol anywhere in `chat/` or `agent/` |
| 2 | Compact New Session row | DEFECTIVE | `chat/panel/sessions/SessionsPanel.kt:359-376`: `fillMaxWidth` + `heightIn(min = TOUCH_TARGET_DP)` + `PAD_VERTICAL` top and bottom |
| 3 | Automatic chat scrolling | PARTIAL (corrected after reading `panel/TranscriptAutoScroll.kt`) | follow-at-bottom, stop-on-user-scroll and resume already exist; the missing part was any way BACK to the newest message |
| 4 | Attachment picker redesign | BLOCKED | picker is the OS sheet: `chat/panel/ChatPanelScreen.kt:183-184` `ActivityResultContracts.GetContent()`; owner's design reference not available to me (see Questions) |
| 5 | Message presentation | PARTIAL | `chat/ui/MarkdownView.kt` (209), `chat/ui/ChatCards.kt` (287), `ChatListRenderer.kt`, `ChatRowModel.kt` |
| 6 | Sessions row padding | PARTIAL | `chat/panel/ChatPanelHeader.kt:42-44`: `padding(horizontal = PAD_HORIZONTAL, vertical = PAD_VERTICAL)` on the header+sessions row |
| 7 | Replace chat logo | MISSING | only `res/drawable/ic_splash_logo.xml` exists; chat reuses it (`ChatEmptyState.kt:43`) |
| 8 | Obsolete logo references | NEEDS-CHECK | no chat-specific logo drawable to purge; launcher/mipmap + `ChatDesignGalleryActivity.kt` refs still to be enumerated |
| 9 | Local approvals into header menu | PARTIAL | `agent/ChatPermissionStore.kt:25-108` (MANUAL/AUTO_SAFE/AUTO_ALL), `ChatApprovalCard` at `ChatPanelScreen.kt:266`; surface "Auto-all" row already removed (`ChatPanelScreen.kt:305`); header-menu entry not yet verified |
| 10 | VS Code capabilities across modes | PARTIAL | `chat/panel/ToolRegistry.kt:19-375` (per-mode exposure, schemas, hidden reasons) + prompt injection `AgentToolWire.kt:144`; actual execution per mode unverified |
| 11 | Code-block actions | DEFECTIVE | `chat/ui/MarkdownView.kt:125-186`: full-width "Copy" and "Insert" buttons per block |
| 12 | Collapsible AI activity | PARTIAL | `TerminalAgentRunStrip.kt:45-68` expands/collapses per run (terminal pane only); no chevron in the chat activity surface |
| 13 | Complete attachment picker | BLOCKED | same as 4 |
| 14 | File-manager navigation | BLOCKED | same as 4; no in-app file browser exists for attachments |
| 15 | Attachment persistence | PARTIAL | staged list `chat/panel/ChatSession.kt:375`; restart persistence unverified; chip-after-retry defect still open from the 2026-10-24 queue |
| 16 | Plugins architecture (connector-based) | PARTIAL (mostly there) | `ToolRegistry.kt` (central registration + parity tests), `agent/AgentConnectorManager.kt`, `agent/McpClientManager.kt` (690), `agent/AgentTools.kt` (580) |
| 17 | Session management (content search, staged attribution, pinning) | PARTIAL | search covers titles/ids only (M2 report), no `pin`/`pinned` symbol anywhere, staged proposals keyed per project in `PendingChangesStore.kt` |
| 18 | Per-session mode persistence | MISSING | no `selectedMode`/`lastMode` in `ChatSettingsPrefs` or `ConversationPrefs` |
| 19 | Persistent undo history | PARTIAL | `agent/AgentWriteJournal.kt` (378) keeps history in memory only |

## Additional integration items

| Item | Status | Evidence |
|---|---|---|
| Git branch indicator | COMPLETE | `ui/screens/ProjectShellScreen.kt:4626-4645` reads `.git/HEAD` at runtime, keyed on `projectRootPath`; detached-HEAD wording still to verify |
| Ask/Edit/Agent real functionality | PARTIAL | mode gating exists in `ToolRegistry`; end-to-end execution and approval path per mode unverified |
| AI activity progress/expand/collapse | PARTIAL | `AgentTerminalFeed` + `TerminalAgentRunStrip` expand/collapse; no dismissal, no chevron in chat |

## What this means for sequencing

Items 4/13/14 cannot start without the owner's picker reference. Everything else can proceed in
the proposed order (chat surface → attachments → tools/modes → sessions/persistence), and items
16 and the branch indicator are largely done, so the work is concentrated rather than sprawling.

Two findings worth flagging now, because they change how items 10 and 12 should be built:

1. A central registry already exists and the model is already told about the exposed tools
   (`AgentToolWire.kt:144`). Item 16 is therefore mostly wiring and verification, not new
   architecture.
2. Terminal activity already collapses per run. What is missing is a dismissal control and a
   chat-side activity view, which is exactly the ambiguity in item 1 (see Questions).

## Phase 2a — landed (2026-10-30)

| # | Requirement | Now | Evidence |
|---|---|---|---|
| 1 | AI terminal activity dismissal | COMPLETE | `AgentTerminalFeed.dismiss(id)` refuses a running run; X rendered only on finished rows (`TerminalAgentRunStrip.kt`); 5 new tests |
| 2 | Compact New Session row | COMPLETE | pill 34 dp inside a 40 dp target, label to FONT_SMALL (`SessionsPanel.kt`); assertions in `ChatDensityTest` |
| 6 | Sessions row padding | COMPLETE | row vertical padding 10 dp -> 2 dp, chips keep 40 dp (`ChatPanelHeader.kt`) |
| 11 | Code-block actions | COMPLETE | word buttons replaced by small copy/insert glyphs with the same actions and feedback (`MarkdownView.kt`) |
| 3 | Automatic chat scrolling | COMPLETE | `FollowFlag` + `JumpToLatestButton`, shown only while scrolled away (`TranscriptAutoScroll.kt`, `ChatPanelScreen.kt`); 5 new tests |
| 12 | Collapsible AI activity | PARTIAL | strip side done (per-run chevron + X); the chat-side activity section is next, per the owner's "both surfaces" ruling |

Still open by owner ruling: 4/13/14 (waiting for the picker reference), 7/8 (waiting for the icon).

## Phase 2b — landed (2026-10-30, owner rulings applied)

| # | Requirement | Now | Evidence |
|---|---|---|---|
| 1 | AI terminal activity dismissal | WITHDRAWN by owner | The X was removed at the owner's ruling: activity rows carry the chevron only, because a close glyph beside a running command reads as "cancel". `AgentTerminalFeed.dismiss()` and its 5 tests are kept, so the capability can be re-exposed in one line if asked. |
| 12 | Collapsible AI activity | COMPLETE (chevron only) | `TerminalAgentRunStrip.kt`: per-run chevron, no close control |
| 7 | Replace chat logo | COMPLETE | New `res/drawable/ic_chat_panel.xml` from the owner's reference: two overlapping rounded bubbles, uniform stroke, round caps/joins, front tail bottom-right, back tail bottom-left. Stroke-only (no fill) so it tints to the panel theme; used at `ChatEmptyState.kt` |
| 8 | Obsolete logo references | COMPLETE | Chat no longer draws `ic_splash_logo`; that asset stays for launch branding only, which is not a chat reference |
| 18 | Per-session mode persistence | COMPLETE | `SessionMeta.mode` + `SessionMode` tokens; index format v2 (v1 still reads, record width tied to header); `SessionIndexStore.setMode`; `ChatSessionManager.sessionMode/setSessionMode`; panel restores per (project, session). 8 tests |

Index format note: v2 appends a mode column. A v1 file reads unchanged (mode defaults to ASK); a
v2 record missing its last column is STILL treated as a torn write, and an unknown mode token
falls back to ASK rather than discarding the conversation.

## Phase 2c — attachment picker + connector card + icon refinement (2026-10-31)

Owner supplied the picker reference and the icon reference; both are now implemented.

| # | Requirement | Now | Evidence |
|---|---|---|---|
| 4 | Attachment picker redesign | COMPLETE | `chat/panel/attachments/AttachmentPickerDialog.kt` (dark modal, three-across tiles, type badges, folder drill-down, "＋ Add from Phone", ↑ Up + ✕), driven by `AttachmentPickerModel.kt` |
| 13 | Picker functionality | COMPLETE (code) | Files stage through the same `ChatSession.addPending` gate as the system sheet: 10 MB cap, credential-name refusal, model-capability gate. Refusals are returned to the sheet and shown, never swallowed |
| 14 | File-manager navigation | COMPLETE | `AttachmentPicker.parentOf`/`isInsideRoot`: back stops at the project root, unrelated paths are refused, unreadable folders report a reason instead of looking empty |
| 7 | Chat logo | COMPLETE | `res/drawable/ic_chat_panel.xml`: transparent, stroke-only, tinted at the call site, 1.2 stroke, 1.9 corner radius |
| 9 / connector | Connector card | DEFECTIVE → COMPLETE | `AgentConnectorManager.requestConnectorCard` set a side channel consumed only by the DEAD `CopilotChatPanelInline`; the live panel now consumes it and draws `ConnectorConnectCard`, whose Connect opens `ConnectorsHubSheet(focusServiceId)` and whose Reject dismisses |

Tests added: `AttachmentPickerTest` (9) — kind mapping, sort order, cap+truncation, missing folder vs empty folder, file-as-folder refusal, back-root clamp, sibling-prefix escape, badges, size labels.

## Phase 3 — conversation memory across a closed panel (2026-10-31)

### The defect, precisely

The panel restored the ROWS from `ChatTranscriptStore` on open, so a reopened conversation
looked continuous. The engine never learned about them: `ChatSession` builds a new
`ChatEngineImpl`, whose `SessionState` starts empty, and `send()` only appends the new turn.
`SessionState` had no way to accept an existing conversation, so every request carried one
user message and nothing else.

Confirmed by: `ChatSession.init` -> `transcripts.load` -> `ChatTranscript.messagesToRows`
(rows only), then `engine.send(userText, ...)`; `SessionState` exposed append/update/snapshot
and no restore path.

### The fix

1. `SessionState.restore(history)` replaces the in-memory list (replace, not append: a restore
   that doubled up would send the same turn twice to a paid model).
2. `ChatEngine.restoreHistory(...)` with a no-op default, implemented by `ChatEngineImpl`.
3. `ChatSeed.dialogue(stored)` decides what crosses over: user/assistant turns only, in order.
   "note" rows are this UI's own annotations and are dropped; an interrupted reply is replayed
   as stored, marker included, so the model sees what the user was reading.
4. `ChatSession` seeds the engine right after the restore, before the first send.
5. `clearConversation()` now also clears the engine. Rows were the visible half of a clear; the
   engine held the other half, so a cleared conversation still answered from memory.

### Tests

* `engine/.../HistoryRestoreTest.kt` (4) — asserts on the JSON bytes handed to the transport:
  a restored conversation rides the next request, a double restore does not duplicate it, an
  unseeded engine carries only the new turn, and a restored engine keeps appending.
* `app/.../ChatSeedTest.kt` (6) — ordering and roles, notes excluded, unique ids across a
  restore, blanks/unknown roles dropped, interrupted replies kept, empty transcript no-op.

### Limits worth knowing

Replayed history is bounded twice: the transcript cap (300 messages, 40 000 chars per message)
and the model's own history window (`HistoryBudget`), which is why a very long conversation
reopens with its most recent stretch rather than everything. That limitation is reported to the
user by the existing "trimmed" note instead of being hidden.

### Still to be researched (not claimed)

How the VS Code chat panel binds a chat session to workspace context and memory is the
10/16 research deliverable and has not been written yet; nothing above is presented as a copy
of that design.

## Phase 4 - session pinning (item 17, part) 2026-10-31

Pinning is in: Pin/Unpin leads each row's action menu, pinned rows lead the list under BOTH
sorts while each half keeps the order the user asked for, a pinned row is marked with a filled
diamond next to its title (a pin you cannot see is indistinguishable from a sort bug), and the
pin survives a restart.

Design choice worth recording: pins live in `pins.tsv` next to the index rather than as an
index column. The index is version-tied so that a torn write is refused rather than read as
"no sessions"; a view preference does not deserve another format bump and another way to reject
somebody's real table. Pins are pruned when their session is deleted, so the file cannot
accumulate ids that no row can ever match.

Tests: 7 pin tests (restart persistence, selective unpin, refusal for a session that does not
exist, damaged pins file, pinned-first under both sorts, empty pin set changes nothing, pin
does not outlive a delete) plus a view-logic test for the marker and the menu order.

Still open in 17: cross-session content search (title/id search only today), staged-proposal
attribution per session rather than per project.

## Research note

`docs/vscode-chat-context-and-memory-research-2026-10-31.md` records what the VS Code
documentation actually says about workspace context and semantic search, what I could not read
(the Memory page returned 404 at both plausible paths), and what VN Code does instead. It
deliberately contains no claims about Copilot's closed implementation.
