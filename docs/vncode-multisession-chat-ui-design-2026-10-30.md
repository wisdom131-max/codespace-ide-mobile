# VN Code multi-session Copilot Chat: design report

Date: 2026-10-30. Repository `wisdom131-max/codespace-ide-mobile`, HEAD `59bc57760f568790ac90f40235b190641fd306c3`.

STATUS: DESIGN AND INSPECTION ONLY. No source file was modified, nothing was staged or committed, and the Phase 2 proposal was not edited. This file is a new untracked document.

## 0. How to read this report

Three labels are used throughout and never mixed:

| Label | Meaning |
|---|---|
| EXISTS | Present in the code at HEAD, verified by reading the file at the cited line |
| NEW | Does not exist; this design proposes building it |
| DEFERRED | Deliberately not in the first milestones, with the reason |

A claim that is an opinion or a recommendation is marked as one. File and line citations were resolved by grepping the symbol, not remembered.

### 0.1 Limits of the video analysis, stated first

I extracted all 91 frames (2 per second) and measured frame-to-frame pixel change, which locates when the screen changes. I could directly inspect only the five sampled frames the platform supplied (2.28s, 11.40s, 22.81s, 34.21s, 43.33s). The sandbox has no OCR and no image viewer, so I could not read the toolbar and overflow menu text from the frames around 3 to 9 seconds.

Consequence for you: the six toolbar actions (Undo Last Edit, Redo Last Edit, Move Chat into Editor Area, Move Chat into New Window, Show Voice Transcripts, Show View by Default) and the eight filters and two sort options are taken from your written description of the recording. I have not independently confirmed their exact labels, order or grouping from pixels. Section 1 marks each video claim as OBSERVED (seen in a supplied frame), TIMING-ONLY (pixel change located but content not read), or REPORTED (from your message). Please confirm the menu order against the recording before the toolbar milestone.

## 1. Video reference breakdown

Recording: `Screen_Recording_20261030_133417.mp4`, 1600x720 landscape, 45.6 s. Source device clock 1:34 PM to 1:35 PM.

| Time | Evidence | What it shows | Design relevance |
|---|---|---|---|
| 2.28 s | OBSERVED | VS Code layout. Left activity bar. Right pane titled "Chat" with a tooltip chip. Centered logo mark above "Build with Agent", then "AI responses may be inaccurate", then a "Generate Agent Instructions" link. Composer at the bottom with a "Tip: Use /create-agent..." strip, a "Describe what to build" field, and a row: `+`, `Auto`, a tuning icon, a send arrow. Top-right of the pane: a `+` with a chevron, `...`, an expand icon, and `X` | The empty-state pattern and the pane header controls. The logo sits above the heading, centered in the area above the composer |
| 3 to 9 s | TIMING-ONLY (pixel change at 4.0 s and 7.0 s) and REPORTED | Overflow `...` menu opened. You report: Undo Last Edit, Redo Last Edit, Move Chat into Editor Area, Move Chat into New Window, Show Voice Transcripts, Show View by Default | Toolbar and overflow menu content. Needs your confirmation of order |
| 9.0 to 12.5 s | TIMING-ONLY (large change at 9.0 to 12.5 s) | The layout reflows into a two-column arrangement | Layout shift into the Sessions view |
| 11.40 s | OBSERVED | Expand icon highlighted. Chat area narrowed on the left, with the logo and heading still centered in it. A new right column titled "Sessions" with header icons (refresh, search, filter) and a full-width "New Session" button. Empty list area with a loading dot | Sessions panel anatomy. Note the chat keeps its empty state while Sessions is open beside it |
| 17.5 to 20.5 s | TIMING-ONLY | Filter and sort menus opening | Sort (Created, Updated) and the eight filters. REPORTED, not read |
| 22.81 s | OBSERVED | Same two-column layout. Composer field focused with a blue border. In the Sessions header the search or filter icon shows a pressed state | Focus ring on the composer. Header icon touch feedback |
| 31.0 to 32.5 s | TIMING-ONLY | Search field opening | Search interaction |
| 34.21 s | OBSERVED | Search field open below "New Session": a text field labelled "Type to search" with a filter toggle, a search-mode toggle and a close `X` | Session search anatomy: one inline field, two toggles, a close button |
| 38.0 to 43.0 s | TIMING-ONLY | Panel closes | Closing the secondary pane |
| 43.33 s | OBSERVED | Panel fully closed. Editor area with the dim VS Code watermark only | The "VS Code logo" in the editor area is the editor watermark, not the Copilot Chat logo. The Copilot logo seen at 2.28 s and 11.40 s is a separate, smaller mark inside the chat pane |

Important correction to the request: the large logo visible in the editor area at 2.28 s and 43.33 s is the VS Code application watermark. You asked for the Copilot Chat mark, not that. The mark that belongs in the chat empty state is the small one above "Build with Agent" at 2.28 s and 11.40 s. VN Code already draws the editor watermark separately (`R.drawable.vncode_watermark`, used at `ui/panes/EditorPane.kt:3295`), so the two are not confused today.

## 2. Current VN Code findings

### 2.1 The conversation id is a hardcoded constant

| Fact | Location |
|---|---|
| `ChatSession` takes `conversationId: String = "main"` | `chat/panel/ChatSession.kt:107` |
| The manager passes the constant `CONVERSATION_ID = "main"` | `chat/panel/ChatSession.kt:721`, used at `:708` |
| `ChatSessionManager` keeps `HashMap<String, ChatSession>` keyed by `projectId` only | `chat/panel/ChatSession.kt:673`, `session(projectId)` at `:683` |
| The panel obtains its one session with `remember(projectId) { manager.session(projectId) }` | `chat/panel/ChatPanelScreen.kt:63` |
| The code comment says the picker is deliberately absent: "no session-management UI is added here" | `chat/panel/ChatSession.kt` comment above `:107`, and `:716-720` |

So today there is exactly one conversation per project, named `main`. The earlier Phase 2 proposal said the chat UI "appears to fix rather than choose" the conversation id. That is now confirmed: it is a compile-time constant, not a UI limitation.

### 2.2 Storage is already keyed by conversation id

| Fact | Location |
|---|---|
| Path is `filesDir/chat/<safe(projectId)>/<safe(conversationId)>.txt` | `chat/ChatTranscriptStore.kt:49` and `:151` |
| `safe()` reduces an id to letters, digits, `.`, `-`, `_`, max 80 chars; blank becomes `"default"` | `chat/ChatTranscriptStore.kt:55` |
| Writes are atomic with a `.bak` copy | `TranscriptFiles.saveNow`, `chat/ChatTranscriptStore.kt:102` |
| An EMPTY message list deletes the file and its backup | `chat/ChatTranscriptStore.kt:106` |
| Bounded: 300 messages, 40,000 chars per message | `chat/ChatTranscript.kt:60-61` |
| `StoredMessage` carries `role`, `text`, `complete`, `atMs`; `atMs` is "kept for debugging and future ordering, never shown" | `chat/ChatTranscript.kt:36-44` |
| Restore is gated so a load cannot overwrite itself | `RestorePolicy`, `chat/ChatTranscript.kt:255` |

Consequences for the design:

1. The on-disk layout already supports many conversations per project with no migration of existing data. The existing `main.txt` simply becomes one session.
2. There is NO metadata. No title, no created time, no updated time, no status. The only time information is the per-message `atMs`.
3. There is NO enumeration. Nothing lists a project's directory. Load is by exact id.
4. Empty-list-deletes is a hazard for "New Session": a freshly created session has no messages, so by this rule it would have no file, and would vanish on restart. Section 7 resolves this with a separate metadata index.
5. `atMs` is real wall-clock data per message. "Updated" can be derived from the last message's `atMs` even before any metadata exists, which is useful for migrating `main`.

### 2.3 What does not exist

| Requirement | State at HEAD |
|---|---|
| Multiple sessions per project | Does not exist (constant id) |
| Session list, create, switch, rename, delete | Does not exist |
| Session title, created time, updated time | Does not exist |
| Session status (completed, in progress, failed, read, archived, external) | Does not exist, see 2.6 |
| Panel header controls | Header is a single text `"Chat"` (`ChatPanelScreen.kt:134-135`). No toolbar, no overflow menu |
| Empty-state UI | None. With zero rows the message list renders nothing. The panel is a blank area above the composer |
| Redo of AI edits | Does not exist anywhere (see 2.5) |
| Cross-session retrieval | Does not exist (Phase 2 proposal, design only) |

### 2.4 Panel placement and navigation

| Fact | Location |
|---|---|
| Chat is a right-hand secondary sidebar toggled by `showChatPanel` | `ui/screens/ProjectShellScreen.kt:5206` (`if (showChatPanel && !fullScreen)`) |
| Width is drag-resizable, clamped 0 to 600 dp, and dragging below a threshold closes it | `ProjectShellScreen.kt:5207` and the drag handler just above |
| The panel is hosted through a thin forwarder `ChatRebuildPlaceholder` | `ProjectShellScreen.kt:5241`, `ui/screens/ChatRebuildPlaceholder.kt` |
| System Back closes the chat panel before anything else on that rung | `ProjectShellScreen.kt:1384` |
| The Activity declares `configChanges` for orientation, screenSize, keyboard etc., so rotation does not recreate it | `AndroidManifest.xml:48` |
| `windowSoftInputMode="adjustResize"` | `AndroidManifest.xml:49` |
| Only two `<activity>` elements exist, no multi-window or `documentLaunchMode` attributes | `AndroidManifest.xml` |

### 2.5 Undo and redo of AI edits: two separate mechanisms, neither with redo

This is the most important finding for the toolbar requirement. There are two independent AI-edit undo systems, and they cover different write paths.

| | Direct tool writes | Staged proposals |
|---|---|---|
| Store | `AgentWriteJournal` (`agent/AgentWriteJournal.kt`) | `PendingChangesStore` (`chat/PendingChangesStore.kt`) |
| Written by | The `write_file` tool executing in Agent mode (`AgentTools.kt:326`) | Edit mode, which stages then applies |
| Undo entry point | `AgentWriteJournal.undo(setId)` at `:121` | `PendingChangesStore.undoLastApply()` at `:360` |
| Safety rule | Refuses if the file changed since the agent wrote it (`undoEntry`, `:163`); verifies by re-reading | Restores a pre-apply checkpoint copy (`writeCheckpoint`, `:334`); 1 MB cap, no checkpoint outside the project |
| UI today | Undo and Keep buttons in the Files Changed row (`ChatFilesChangedRow.kt:129,137`) | NO UI caller. `undoLastApply` is referenced only inside its own file and a comment at `EditorPane.kt:285` |
| Retention | In memory, `MAX_SETS = 3` (`AgentWriteJournal.kt:49`), lost on process death | Checkpoint files on disk; `lastApplied` list in memory (`:81`) |
| Scope | One process-global list (`sets`, `:54`), not per session | Keyed by `activeSessionId`, which is the PROJECT id (`:61`, set at `ChatPanelScreen.kt` effect) |
| Redo | None | None |

Findings that constrain the design:

1. "Undo Last Edit" has no single implementation to call. Hooking it to only one path would silently miss the other. The toolbar action must resolve to whichever of the two holds the most recent applied change for the ACTIVE session, and say so honestly when neither does.
2. `undoLastApply` is dead code from the user's point of view: the one-tap restore exists but nothing exposes it. Surfacing it is a legitimate NEW wiring task, not a new algorithm.
3. Redo does not exist in either system. The journal discards nothing on undo (it marks the set `undone` and keeps `before` and `after`), so a redo is mechanically possible for the journal. The staged path overwrites the file from a checkpoint and does not retain the "after" content, so redo there needs the pre-undo content captured first.
4. The journal is process-global, so with multiple sessions an "Undo Last Edit" in session B could revert a change made in session A. That is a correctness hazard introduced by multi-session, and the design must attribute journal sets to a session id (Section 7.4).
5. Process death loses the journal entirely. After a restart there is no Undo for the previous run's AI edits. That is acceptable for milestone 1 and should be stated in the UI, not hidden.
6. The editor has its own text undo (`editor/undo/SnapshotUndoManager.kt`, `SharedFileUndo.kt`, `editor/ToolbarUndoRedoHandler.kt`). That is keystroke-level editing history in a buffer, not AI-applied workspace edits. The request says Undo Last Edit must NOT be the editor's undo, so it must not reuse `ToolbarUndoRedoHandler`.

### 2.6 Session categories versus what the data model can support

The recording shows two sort options and eight filters. None is backed by a stored field today. My recommendation for each, with the reasoning:

| Category | Exists? | Can it be derived from existing data? | Recommendation |
|---|---|---|---|
| Sort by Created | No | Needs a created timestamp (first message `atMs` is a fallback) | NEW, stored in session metadata |
| Sort by Updated | No | Last message `atMs` | NEW, stored in metadata, updated on save |
| Local | No | Every VN Code session is local today | DERIVE as a constant `source = LOCAL`. Show the filter only if a second source ever exists |
| External | No | There is no remote or background agent in VN Code | DEFER. Hide. No external sessions exist, and an always-empty filter is a fake control |
| In Progress | Partially | `ChatPanelUiState.streaming` (`ChatSession.kt:30-36`) is true only for the active in-memory session | DERIVE for the live session only. Cannot be derived for a stored one, see 7.3 |
| Completed | No | Last assistant message has `complete = true` | DERIVE with a stated rule: last message is a complete assistant reply |
| Failed | No | Failed rows are deliberately NOT persisted (`ChatTranscript.kt` header, judgement call 1) | DEFER, or store a one-bit `lastSendFailed` flag. Cannot be derived from the transcript |
| Attention Needed | No | A pending approval card or staged proposals exist (`PendingChangesStore.pendingCount`, `ChatApprovalCard`) | DERIVE: pending staged files or an unresolved approval. Needs a per-session attribution of both |
| Read | No | No unread tracking | NEW, a stored `lastReadAtMs`; Read means `updatedAtMs <= lastReadAtMs` |
| Archived | No | None | NEW, a stored boolean. Cheap, and it is the safe alternative to hard delete |

I am not inventing status semantics silently. The proposed definitions above are recommendations awaiting your decision. First-milestone recommendation: Sort by Created/Updated, plus Archived and Read, plus a derived Completed. Defer External and Failed. Gate In Progress and Attention Needed on the live session only.

### 2.7 Tools, design system, and prefs

| Fact | Location |
|---|---|
| Colors, radii, paddings are generated from `android/chat-tokens.json` and must not be hand-edited | `chat/ui/ChatTokens.kt:1-2` |
| `RADIUS_PANEL = 12`, `RADIUS_CHIP = 8`, `PAD_HORIZONTAL = 12`, `PAD_VERTICAL = 10` (the MANDATORY UI RULE) | `chat/ui/ChatTokens.kt:66-72` |
| Existing composer toolbar is pure data tested at 280/360/480 dp, 40 dp touch target | `chat/panel/ChatToolbarSpec.kt` (`TOUCH_TARGET_DP = 40`) |
| That spec covers the COMPOSER row only (MODE, MODEL, TOOLS), not a panel header | `ChatToolbarSpec.kt:19-23` |
| Chat settings live in plain SharedPreferences `chat_settings` with pure encode/decode functions | `chat/panel/ChatSettingsPrefs.kt` |
| Existing pure-JVM test seam: a `TranscriptSink` fake on an Unconfined scope | `test/.../ChatSessionPersistenceTest.kt` |
| `ChatPanelScreen` closes the manager (cancels its scope) when the panel leaves composition | `chat/panel/ChatPanelScreen.kt:62` |

Point 7 of that table is a constraint with real consequences: closing the panel cancels `scope`, which kills any in-flight stream. In a multi-session world, a background session streaming while the user views another session would be killed if the panel closes. "In Progress" for a non-active session is therefore not truthful unless the manager's lifetime is separated from the panel's (Section 7.3, risk R3).

### 2.8 The six toolbar actions against the code

| Action | Exists? | Current behavior | Android feasibility | New work | First milestone? |
|---|---|---|---|---|---|
| Undo Last Edit | Partially | Two undo systems exist; only the journal one has UI (`ChatFilesChangedRow.kt:137`); `undoLastApply` has none | Yes, pure file logic | Unify behind one session-attributed entry point; wire `undoLastApply`; honest "nothing to undo" | Yes |
| Redo Last Edit | No | None | Yes for the journal (`before` and `after` are retained). Staged path needs pre-undo capture | New redo stack, conflict-checked symmetrically to undo | Yes, journal path first; staged path second |
| Move Chat into Editor Area | No | Chat is only a right sidebar (`ProjectShellScreen.kt:5206`) | Yes, as a tab | An editor-tab kind that hosts `ChatPanelScreen`; tab lifecycle and state sharing | No, DEFER to milestone 6 |
| Move Chat into New Window | No | None; the Activity is single-window | Partly. Android freeform or split-screen is device-dependent and `resizeableActivity` is not declared | A second Activity instance or multi-instance flag; two Compose hosts sharing one manager | DEFER. Offer "Full screen" as the Android alternative (the shell already has a `fullScreen` flag, `ProjectShellScreen.kt:1197`) |
| Show Voice Transcripts | No | Voice exists only in the terminal for dictating a command (`ui/panes/TerminalPane.kt:2711-2724`, `RecognizerIntent`) | The concept needs definition: there is no voice conversation in VN Code | A product decision first | DEFER. Do not ship a button for a feature with no definition |
| Show View by Default | No | The chat's open or closed state is not persisted as a preference | Yes | One boolean pref and shell read at startup | Yes, it is cheap and honest |

"Do not create fake buttons that do nothing" is respected by gating: only actions with a real implementation ship, and the deferred three are omitted rather than shown disabled.

## 3. Proposed Sessions UI layout

Reference anatomy from the recording (OBSERVED at 11.40 s and 34.21 s): a header row reading "Sessions" with refresh, search and filter icons on the right; a full-width prominent "New Session" button; the list below; an inline search field with two toggles and a close button.

### 3.1 Panel anatomy (phone)

| Region | Content | Notes |
|---|---|---|
| Header | Back chevron, "Sessions" title, search icon, filter and sort icon | 40 dp targets, 12 dp horizontal and 10 dp vertical padding, per the mandatory UI rule |
| Primary action | Full-width "New Session" button, 12 dp radius | Matches the prominent button in the recording |
| Search | Inline field under the button, shown on tap of the search icon, with a clear and close control | Replaces the header icons while open, does not stack a second bar |
| List | One row per session, newest first by the chosen sort | Row: title (single line, ellipsized), relative time, a one-line preview, a status dot where derivable |
| Row actions | Long press for a bottom sheet: Rename, Archive or Unarchive, Delete | Avoids tiny inline icons on a phone |
| Empty list | Short text plus the same New Session affordance | Not a blank area |
| Footer hint | None | Avoid clutter |

### 3.2 Sort and filter

Sort is a single-choice menu: Updated (default) and Created. Filters are a multi-select sheet with only the categories that have a real definition in 2.6 for the milestone: Archived toggle (default off, archived hidden), Read and Unread, Completed, plus Local only if a second source ever exists. Active filters show as a count badge on the filter icon. Sorting and filtering are views over the list and must not write conversation data (acceptance item 10), so they operate on an in-memory projection of the metadata index.

### 3.3 Session row model

| Field | Source |
|---|---|
| title | Stored; defaults to the first user message clipped to about 40 characters, else "New Session" |
| updated | Stored `updatedAtMs` |
| preview | First line of the last message, loaded lazily or stored as a short snippet |
| status dot | Derived per 2.6 |

## 4. Proposed toolbar and overflow menu

### 4.1 Panel header (new), narrow-screen behavior

The header gains, left to right: the surface title, a New Session `+`, a Sessions button, an overflow `...`, and a close `X` when not full screen. On widths below the threshold the title and `+` stay; Sessions moves into the overflow. This is the same pure-data approach as `ChatToolbarSpec`: a `PanelHeaderSpec.forWidth(widthDp)` function returning inline and overflow lists, unit tested at 280, 360 and 480 dp. It is a separate spec from the composer's, because the composer spec is about MODE, MODEL, TOOLS and must not grow unrelated items.

### 4.2 Overflow menu contents

| Item | Milestone | Behavior |
|---|---|---|
| Undo Last Edit | M3 | Applies the unified undo for the active session. Disabled with the reason "No AI edit to undo" when none |
| Redo Last Edit | M3 | Re-applies the last undone AI edit, with the same conflict check. Disabled when none |
| Rename Session | M2 | Edits the title only |
| Archive Session | M2 | Sets `archived`, does not delete data |
| Delete Session | M2 | Two-tap confirm, removes transcript, backup and index records (Section 7.5) |
| Full Screen | M4 | The Android alternative to "Move into New Window", reusing the shell `fullScreen` flag |
| Show View by Default | M3 | Persisted boolean |
| Move Chat into Editor Area | M6, deferred | Not shown until built |
| Show Voice Transcripts | Deferred | Not shown; needs a product definition |

Items the code cannot support yet are omitted, not greyed out. Disabled items appear only when the capability exists but is currently unavailable (nothing to undo).

### 4.3 Undo and redo semantics, in detail

An "AI-applied edit" is a change set recorded by either mechanism, attributed to a session id (Section 7.4). The unified action does:

1. Look up the newest not-yet-undone change set for the active session across both stores.
2. For the journal path, call the existing `AgentWriteJournal.undo` unchanged, so its refusal-on-conflict and verify-by-re-read rules are preserved exactly. Do not reimplement them.
3. For the staged path, call `PendingChangesStore.undoLastApply`, unchanged.
4. Redo applies only to a set whose outcome is `undone`, and only if the disk still equals the `before` content the undo left behind. Any divergence refuses with a reason, mirroring the undo rule.
5. Neither action touches chat messages.

What redo must never do: overwrite a file the user edited after the undo. That is the same guarantee the journal already makes for undo, applied in reverse.

## 5. Empty-state Copilot Chat logo

### 5.1 Behavior

Show the empty state when the active session has zero rows, no pending attachments, and setup is not required (`ResolvedSelection` is neither `NeedsSetup` nor `Unavailable`, which already render their own states at `ChatPanelScreen.kt`). Layout: the mark centered, a heading beneath it, one line of supporting guidance, and the existing composer unchanged at the bottom. It disappears the instant the first message is added.

Today the area is blank, so this is a pure addition inside the existing scroll column and removes nothing.

### 5.2 Legal and technical findings on reproducing the logo

I checked the primary sources rather than assuming:

| Source | What it says | Consequence |
|---|---|---|
| `microsoft/vscode-copilot-chat` `LICENSE.txt` | MIT, copyright Microsoft. The repository is archived (since 2026-05-20) and development moved into `microsoft/vscode` | MIT licenses the SOFTWARE. It does not state it licenses logos or trademarks |
| `microsoft/vscode-codicons` README, Legal Notices | Content is CC-BY-4.0 and code is MIT, but: "The licenses for this project do not grant you rights to use any Microsoft names, logos, or trademarks" | A codicon glyph may be reused with attribution, but a logo or trademark is expressly carved out |
| GitHub Brand Toolkit, Logo page | Marks are trademarks. "Do not use GitHub trademarks, logos, or artwork without GitHub's prior written permission." Also: do not "imply affiliation", do not use any GitHub logo "as the icon or logo for your ... project", do not modify it | Copying the Copilot mark into VN Code as its own empty-state brand is not permitted without written permission |
| GitHub Brand Toolkit, Copilot page | "Beginning in 2025, GitHub Copilot no longer has a standalone logo that heros the Copilot icon." Brand uses lockups with the GitHub mark | There is no official standalone Copilot icon to license, which weakens any argument that it is freely reusable |

Conclusion, which is my reading and not legal advice: I did not find a license that permits reproducing the Copilot Chat mark, and the primary sources point the other way. I recommend you do not ship the Microsoft or GitHub mark, and that you take legal or written-permission advice if you want it.

### 5.3 Recommended approach (opinion, flagged as such)

1. Ship an ORIGINAL VN Code chat mark, drawn as an Android vector drawable (`res/drawable/ic_vncode_chat.xml`), in the same visual treatment the recording shows: small, centered, monochrome, low-contrast against the dark surface, with no distortion. This delivers the user-visible treatment without the trademark risk. It is also consistent with the existing `ic_vscode_*` drawables in `res/drawable/`, which were presumably drawn for the same reason.
2. If you want the real Copilot mark, the only compliant route is written permission from GitHub. I would not start the work without it.
3. The vector must be a VectorDrawable, so it scales and tints with the theme at zero decode cost on a low-RAM device.

This deviates from your literal wording ("use the Copilot Chat logo/icon"). I am flagging that as an open decision (Section 12, D1), not resolving it silently.

## 6. Android interaction and navigation design

The recording is a landscape desktop layout where Chat and Sessions sit side by side. A phone portrait width cannot hold two columns, so the phone design is a two-destination flow inside the existing chat sidebar, not a shrunken copy.

### 6.1 Navigation model

| Aspect | Design |
|---|---|
| Destinations | `Chat` (active session) and `Sessions` (list). One visible at a time on a phone |
| Open Sessions | Header Sessions button, or swipe from the chat's left edge is NOT used, because the existing 12 dp left-edge drag handle already resizes and closes the panel (`ProjectShellScreen.kt` drag handler) |
| Open a session | Tap a row: switches the active session and returns to Chat |
| New Session | The prominent button: creates the session, switches to it, and returns to Chat showing the empty state |
| Wide layouts | If the panel width is at or above a threshold (landscape, tablet, freeform), show Sessions beside Chat as the recording does. The threshold is data in a pure spec, tested at 360, 600 and 840 dp |
| Back button | Rung order: close a sheet or dialog, then close search, then Sessions back to Chat, then the existing rung that closes the chat panel (`ProjectShellScreen.kt:1384`), then leave. Sessions must register BEFORE the panel-close rung |
| Rotation | `configChanges` is declared (`AndroidManifest.xml:48`), so rotation does not recreate the Activity. Navigation state, search text and scroll position must live in `rememberSaveable` or the view model, and the wide-layout switch must not drop the open session |
| Keyboard | `adjustResize` is set. Search field and rename dialog must stay above the IME. Opening Sessions must not auto-focus the field, so the keyboard does not cover the list on entry |
| Touch targets | 40 dp, from `ChatToolbarLayout.TOUCH_TARGET_DP`; row height at least 56 dp |
| Search and filter | Inline field with debounce around 250 ms; filters in a bottom sheet, not a popup, for thumb reach |
| Low RAM | The list renders with `LazyColumn`; previews come from the metadata index, never by loading transcripts. Transcripts load only for the active session |

### 6.2 What is not copied from desktop

Hover tooltips, drag-to-dock, multi-window drag, and the desktop "Move Chat into New Window" are not carried over. The Android alternatives are the full-screen toggle and, later, an editor-tab host.

## 7. Session data model and persistence

### 7.1 New: a per-project session index

Because the transcript file layout has no metadata and an empty session has no file, add a small index file per project, separate from the transcripts:

`filesDir/chat/<safe(projectId)>/sessions.json`

```
{ "version": 1,
  "activeId": "<id>",
  "sessions": [
    { "id": "s_<random>", "title": "...", "createdAtMs": 0, "updatedAtMs": 0,
      "archived": false, "lastReadAtMs": 0 } ] }
```

| Rule | Reason |
|---|---|
| Written atomically with the same tmp, rename and `.bak` approach as `TranscriptFiles.saveNow` | Reuses a proven pattern, and the strict-read lesson from P0-B applies: an unreadable index must abort writes and report, never be treated as empty |
| `id` is generated, never derived from the title | Satisfies "renaming must not change the conversation id" |
| The id is the transcript file stem | No change to `fileFor` |
| The legacy `main` conversation is adopted as the first session on first run | No data loss, no migration of the transcript file |
| `updatedAtMs` is written on each save, throttled with the existing 1.2 s debounce | Avoids a second write storm |

### 7.2 New: persisted in two files, not one

Metadata is separate from transcripts on purpose: listing, sorting and searching sessions must not read any transcript. Deleting a session removes its index entry and its `.txt` and `.bak`.

### 7.3 Manager lifetime (needed for honest "In Progress")

`ChatSessionManager` currently holds one session per project and its scope is cancelled when the panel disposes (`ChatPanelScreen.kt:62`, `ChatSession.kt:713`). For multi-session:

1. Key sessions by `(projectId, conversationId)`.
2. Decide whether a background session may keep streaming after the user switches away. Recommendation: NO in milestone 1. Switching cancels or detaches nothing silently: if a send is in flight, the switch is blocked with a short message, or the stream is stopped and the partial reply is stored `complete=false` as the existing interrupted-reply rule already does. This avoids the process-kill hazard and keeps "In Progress" meaningful for exactly one session.
3. Revisit parallel sessions later, as the recording's "Run multiple agent sessions in parallel" idea, only after the manager's lifetime is moved off the panel.

### 7.4 Attributing AI-edit state to a session

| Store | Change |
|---|---|
| `AgentWriteJournal.ChangeSet` | Add `sessionId`. `beginOperation()` (called at `ChatSession.kt:601`) records the active conversation id. Filter by it in the Files Changed row and in undo and redo |
| `PendingChangesStore` | Its `activeSessionId` is the PROJECT id today (`:61`). Make it the conversation id, or add a second key, so proposals from two sessions in one project do not mix |

Without this, the cross-session leak in 2.5 point 4 is real.

### 7.5 Delete

Delete must remove, in order: the transcript `.txt`, its `.bak`, the index entry, any search-index records for that session (Phase 2), and the session's journal sets. It reports failure of any step rather than claiming success, and it never deletes a transcript while its index write is failing.

### 7.6 Empty sessions

A session with no messages exists only in the index. It is shown in the list as "New Session" with no preview. Policy recommendation: an empty session older than the current app run, with zero messages, is pruned at startup so abandoned "New Session" taps do not accumulate. This is a decision for you (D4).

### 7.7 Project isolation

The index and transcripts both live under `chat/<safe(projectId)>/`, and every API takes `projectId` first, so a project cannot enumerate another's sessions by construction. A test must prove it (Section 11).

## 8. Sessions and conversation-aware memory

| Concept | Where it lives | Relation to sessions |
|---|---|---|
| Current session | Active `ChatSession.ui` rows plus its `.txt` | The unit of conversation. Always available, no setting needed |
| All sessions | The set of `.txt` files plus the index | Retrievable only when the user enables cross-session recall |
| Saved memory | `agent_memory/memory.json` (global today) | Independent of sessions. Never populated by switching sessions |

Required behavior, all of which the session design supports without any memory work:

1. A setting `Memory scope = Current session only | All sessions`, default Current session only, stored in `chat_settings` via the existing pure encode and decode pattern.
2. The Sessions UI is fully usable with the setting off. Nothing in M1 to M5 depends on retrieval.
3. A session switch swaps which transcript backs `ChatSession.ui`. It never concatenates histories. Switching constructs or restores a session by id, and the restore gate (`RestorePolicy`) already prevents a load from overwriting live rows.
4. When the setting is All sessions, the Phase 2 retrieval index is scoped per project and can only return hits from that project's sessions. Hits carry the conversation id, so a result can be shown as "from session: <title>".
5. Deleting a session must also delete its postings from the retrieval index, which is why 7.5 lists it.
6. Archived sessions: recommendation is to exclude them from retrieval by default, with the user able to include them. This is decision D6.

This answers the two blockers from the Phase 2 proposal: conversation selection now has a concrete mechanism (the index and a UI), and retention now has a natural lever (Archive, Delete, and the empty-session prune).

## 9. Implementation milestones in dependency order

Each milestone is independently shippable, keeps CI green, and ends with the device-round batching rule respected (one batched device round, not one per milestone).

| M | Scope | Depends on | Label |
|---|---|---|---|
| M0 | Pure session model and index codec: `SessionMeta`, encode and decode, strict-read result type, derive-title, sort and filter as pure functions. No UI, no wiring | none | NEW |
| M1 | `SessionIndexStore` (atomic file, strict read, adopt legacy `main`), manager keyed by `(projectId, conversationId)`, create, switch, rename, delete, block-switch-while-streaming | M0 | NEW |
| M2 | Sessions panel UI: header, New Session, list, search, sort, filter sheet, row actions, empty list, back-stack rung | M1 | NEW |
| M3 | Panel header and overflow: `PanelHeaderSpec`, Undo and Redo Last Edit (unified, session-attributed), Show View by Default, Rename and Archive and Delete in the overflow | M1 | NEW and wiring of EXISTS |
| M4 | Empty-state composable with the original vector mark; Full Screen action | M2 | NEW |
| M5 | Memory scope setting (Current session only, All sessions) wired to nothing yet but persisted and shown | M1 | NEW |
| M6 | Move Chat into Editor Area (tab host) | M2, M3 | DEFERRED |
| Later | Cross-session retrieval index (Phase 2), parallel background sessions, Move into New Window, Voice Transcripts, External and Failed filters | M1 to M5 | DEFERRED |

M3 is split in its own work item internally: journal undo and redo first (self-contained), then `undoLastApply` wiring.

## 10. Files expected to change per milestone

All paths under `android/app/src/main/java/com/codespace/ide/` unless noted. Counts are estimates from the code read, not commitments.

| M | New files | Modified files |
|---|---|---|
| M0 | `chat/session/SessionMeta.kt`, `chat/session/SessionIndexCodec.kt`, `chat/session/SessionListLogic.kt` | none |
| M1 | `chat/session/SessionIndexStore.kt` | `chat/panel/ChatSession.kt` (constructor id, manager keying, `CONVERSATION_ID` removed at `:721`), `chat/ChatTranscriptStore.kt` (delete-by-id helper), `chat/panel/ChatPanelScreen.kt` (session selection state) |
| M2 | `chat/panel/SessionsPanel.kt`, `chat/panel/SessionRow.kt`, `chat/panel/SessionFilterSheet.kt` | `chat/panel/ChatPanelScreen.kt`, `ui/screens/ProjectShellScreen.kt` (back rung at `:1384`) |
| M3 | `chat/panel/PanelHeaderSpec.kt`, `chat/panel/PanelHeader.kt`, `chat/panel/EditUndoCoordinator.kt` | `agent/AgentWriteJournal.kt` (sessionId, redo), `chat/PendingChangesStore.kt` (session key, redo capture), `chat/panel/ChatFilesChangedRow.kt`, `chat/panel/ChatPanelScreen.kt`, `chat/panel/ChatSettingsPrefs.kt` (view-by-default pref) |
| M4 | `chat/panel/ChatEmptyState.kt`, `res/drawable/ic_vncode_chat.xml` | `chat/panel/ChatPanelScreen.kt` |
| M5 | none | `chat/panel/ChatSettingsPrefs.kt`, settings UI |

JVM 64KB bytecode rule: `ChatPanelScreen.kt` is 596 lines and `ProjectShellScreen.kt` is far larger. Every addition goes into a separate composable file and is called once; nothing is added inline to a composable body. Tokens come from `ChatTokens` (generated, not hand-edited), and all new panels use the 8 to 12 dp radius and 12 dp horizontal and 10 dp vertical padding.

## 11. Tests and acceptance criteria

### 11.1 Per-requirement tests (pure JVM unless marked)

| # | Requirement | Test |
|---|---|---|
| 1 | New Session is independent | Two sessions in one project; send into A; B's rows stay empty; files `A.txt` and `B.txt` differ |
| 2 | Switching restores the chosen conversation | Switch A to B to A; A's rows equal the stored transcript |
| 3 | No merge across sessions | Rows from A never appear in B's saved file |
| 4 | Older session keeps its messages | Reopen A after activity in B |
| 5 | Survives restart | Rebuild the manager from the same directory; index and transcripts load |
| 6 | Rename keeps the id | Rename; id and file path unchanged |
| 7 | Delete removes everything | `.txt`, `.bak`, index entry, journal sets all gone; failure of any step is reported |
| 8 | Empty sessions | Created with no file; listed; survive a restart via the index; pruned per D4 |
| 9 | Search by title and content | Title match; content match using the Phase 2 index when enabled; title-only when disabled |
| 10 | Sort and filter are read-only | Hash the index bytes before and after; unchanged |
| 11 | Project isolation | Project A cannot list or load project B's sessions |
| 12 | Strict read | Corrupt `sessions.json`: no write is attempted, the error is surfaced, transcripts are untouched (the P0-B lesson) |
| 13 | Legacy adoption | A pre-existing `main.txt` appears as a session with a derived title and times from `atMs` |
| 14 | Undo and redo | Journal undo then redo restores; a user edit in between refuses redo with a reason; session A's undo never touches session B's set |
| 15 | Undo with nothing to undo | Action disabled, reason shown, nothing changes |
| 16 | Header layout | `PanelHeaderSpec` at 280, 360, 480 dp: inline and overflow lists, no clipping |
| 17 | Back stack | Pure reducer: sheet, search, Sessions, panel in the right order |
| 18 | Empty state | Visible only when rows are empty and setup is not needed; hidden after the first message |
| 19 | Memory scope | Default Current session only; switching sessions never changes the stored scope |
| 20 | Streaming switch rule | Switching while streaming is blocked or stored as an interrupted reply per 7.3 |

Compose UI behavior (focus, IME, rotation) cannot be proven on the JVM here, so it goes into the single batched device round, with a checklist per gap, as your rule requires.

### 11.2 Acceptance matrix against your list

| Criterion | Covered by |
|---|---|
| Multiple independent sessions | M1, tests 1 to 4 |
| New Session | M1, M2 |
| Switching | M1, M2, test 2 |
| Persistence after restart | M0, M1, test 5 |
| Rename and delete | M1, M3, tests 6 and 7 |
| Search | M2, test 9 |
| Created and Updated sorting | M0, M2 |
| Filters | M0, M2, with the scoped set from 2.6 |
| Toolbar and overflow | M3 |
| Undo and redo of AI edits | M3, test 14 |
| Centered logo in the empty chat | M4, with the legal caveat in 5.2 |
| Current-session and all-sessions settings | M5 |
| Android-friendly navigation | Section 6, M2, tests 16 and 17 |

## 12. Risks, unresolved decisions and recommendations

### 12.1 Risks

| ID | Risk | Mitigation |
|---|---|---|
| R1 | Journal is process-global; multi-session could undo another session's edit | Section 7.4, mandatory, with test 14 |
| R2 | Two undo systems; a toolbar bound to one hides the other | Unified coordinator in M3, honest empty state |
| R3 | Manager scope is cancelled on panel dispose, killing a background stream | Block or interrupt on switch in M1; defer parallel sessions |
| R4 | Empty-list-deletes rule would make a new session vanish | Separate index file (7.1) |
| R5 | `ChatPanelScreen` and `ProjectShellScreen` are large; inline additions risk the JVM 64KB limit | Extract at first write |
| R6 | Trademark exposure from copying the Copilot mark | Original vector mark (5.3) |
| R7 | Index and transcripts can disagree after a crash | Index is the source for listing; load tolerates a missing transcript as an empty session and reports it |
| R8 | Video labels were not read from pixels | Confirm menu order before M3 |
| R9 | A stale note in my own saved context mentions `CopilotChatPanelOverlay.kt`; that file does not exist at this HEAD | This report cites only files verified at HEAD |

### 12.2 Decisions I need from you (not resolved on your behalf)

| ID | Decision | My recommendation |
|---|---|---|
| D1 | Logo: original mark, or seek GitHub permission for the real one | Original mark |
| D2 | Confirm the overflow menu order and exact labels from the recording | Confirm before M3 |
| D3 | Switching while a reply is streaming: block, or stop and keep the partial reply | Stop and keep the partial reply, using the existing interrupted-reply path |
| D4 | Prune abandoned empty sessions at startup | Yes, empty and older than the current run |
| D5 | Filters for milestone 1 | Archived, Read, Completed; defer External, Failed; gate In Progress and Attention Needed on the live session |
| D6 | Archived sessions in cross-session retrieval | Excluded by default |
| D7 | Should Redo on the staged path be in M3 or later | Journal path in M3, staged path later |
| D8 | Title: auto from the first message, or always "New Session" until renamed | Auto from the first message, editable |

### 12.3 Recommendation

Approve M0 and M1 first. They carry all the data-integrity risk (independence, persistence, isolation, delete) and none of the UI risk, and everything else is a view over them. Hold M3 until D2 is answered, and hold M4's logo until D1 is.

## 13. What this report did not do

No source file, test, workflow or the Phase 2 proposal was modified. No commit or push was made. Memory retrieval was not started. No existing feature was removed or redesigned. The only file created is this report.
