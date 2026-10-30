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
