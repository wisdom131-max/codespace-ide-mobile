# M2 — Sessions interface: implementation report

Date: 2026-10-30. Base commit `899edc276d5dd8be396cf9b6228b57ab3f61f00c` (M0/M1, accepted).
UI reference: `Screen_Recording_20261030_133417.mp4` (1600x720, 45.6 s) and the inventory in
`docs/vncode-multisession-chat-ui-design-2026-10-30.md`.

STATUS: M2 IMPLEMENTED in the working tree. Not committed, not pushed, no CI run, no APK.

## 1. What shipped

| Surface element | Implementation | Writes? |
|---|---|---|
| "Sessions" header title, refresh, search, filter with count badge | `SessionsPanelSpec` (pure, width-aware) + `SessionsHeaderRow` | no |
| Full-width "New Session" | creates a real index record, becomes active | yes |
| Inline search, "Type to search" | filters the view by title and id; never writes | no |
| Sort: Created / Updated | `SessionQuery.sort` via `SessionListLogic.sorted` | no |
| Filters: all eight, in reference order | `SessionFilter` entries; External and Failed are listed, marked "Nothing can produce this yet", and are NOT selectable | no |
| Session rows: title, relative time, status dot, archived tag, unread dot, preview | `SessionsViewLogic.rows` + off-thread transcript tail | no |
| Row tap | `switchTo` (persisted), then `markRead`, then the chat re-keys | yes |
| Long press | Rename / Archive or Unarchive / Delete | yes |
| Delete | two-step confirm; a failed byte delete is reported, never shown as success | yes |
| Empty list | distinguishes "no sessions yet" from "nothing matches this view" | no |
| Active view line | "1 filter · Archived · Sort by: Created" so a filtered list is never silent | no |

## 2. Decisions taken, with the reason

1. **The pane swaps instead of splitting into two columns.** The reference (1600 dp wide) shows
   chat and Sessions side by side. VN Code's chat is a sidebar the owner drags between 0 and
   600 dp; the composer alone is tested for a 280 dp minimum. At a 360 dp sidebar two columns
   would be about 180 dp each, which is below that minimum, so the same control set is shown
   one column at a time. This is a width limitation of the host, not a design preference.
2. **All eight filters are present; two cannot be selected.** External and Failed have no
   producer in VN Code (no remote agent; failed sends are deliberately not persisted). Removing
   them would silently shrink the requested inventory; enabling them would be a fake control.
   They are shown with their reason and are inert.
3. **No second toggle inside the search row.** The recording shows two toggles beside the search
   field. The sandbox has no OCR and the frame text was not readable, so only toggles whose
   behaviour is defined shipped: the filter shortcut and close. A third control with an invented
   meaning was not added.
4. **No overflow button and no expand control yet.** They belong to M3 and M4 respectively and
   would be dead controls today.
5. **`+` creates a session immediately.** The reference shows `+` with a chevron. A chevron menu
   would duplicate the Sessions pane that the adjacent button already opens.
6. **Manager lifetime is the panel's, not the chat body's.** Opening Sessions unmounts the chat
   body. If the manager were owned by the body, that would cancel a running stream; it now
   survives, so a reply in progress keeps streaming while the user browses sessions.

## 3. Files

New:
- `chat/panel/sessions/SessionsPanelSpec.kt` — strings, widths, badge, action inventory.
- `chat/panel/sessions/SessionsViewLogic.kt` — rows, relative time, status derivation, filter
  and sort mutation helpers, empty-state text.
- `chat/panel/sessions/SessionsPanel.kt` — the surface.
- `chat/panel/sessions/SessionsDialogs.kt` — row actions, filter, sort, rename, delete confirm.
- `chat/panel/ChatPanelHeader.kt` — header row: title, Sessions, `+`, `✕`.
- Tests: `SessionsPanelSpecTest`, `SessionsViewLogicTest`, `SessionsPanelWiringTest`.

Modified:
- `chat/panel/ChatPanelScreen.kt` — split into `ChatPanelScreen` (host: manager, active id,
  Sessions swap) and `ChatBody` (the existing conversation surface, unchanged apart from the
  header). New `onClosePanel` parameter.
- `chat/panel/ChatSession.kt` — `activeSessionFlow`, `publishActive`, `transcriptTail`; active-id
  changes from create, switch and delete all publish to the flow.
- `ui/screens/ChatRebuildPlaceholder.kt`, `ui/screens/ProjectShellScreen.kt` — pass the panel's
  close callback so the header X closes the same panel the drag handle does.

## 4. Tests

27 new tests, all green:

- spec: reference strings, header at 280/320/360/480/600 dp, no action dropped at any width,
  primary-action threshold, badge cap, touch-target floors.
- logic: relative time including future timestamps and the date fallback; updated vs created
  ordering and the time label following the sort; active and unread marking; eight filters with
  the two unusable ones; unusable filters cannot be selected; archived is visibility not a
  badge; search by title and id with the input list unmodified; status priority; row actions per
  archive state; empty-state distinction; view summary.
- wiring against the real store: create/switch/delete publish the active id; a refused switch
  leaves it alone; delete publishes a real successor; browsing leaves the index byte-identical;
  rename and archive round-trip through disk.

Full suite: `:app:testDevDebugUnitTest` 1093 tests, 1 failure (`BackupTarTest`, the known
root-sandbox failure that passes on CI's non-root runner); `:engine:test` 106, 0 failures.

## 5. Known limitations

1. Side-by-side columns are not implemented (width math in section 2.1).
2. Staged proposals (`PendingChangesStore`) are still keyed by project id, not conversation id,
   so proposals written in one session appear in another. This must be resolved with M3's
   undo/redo attribution, which needs per-session ownership anyway.
3. Search covers titles and ids only. Content search needs the Phase 2 retrieval index.
4. The status dot can only mark "In Progress" and "Attention Needed" for the live session; a
   stored session cannot be either while the app is closed, and nothing invents it.
5. Row previews require a transcript read per session; capped implicitly by the list length and
   read off the main thread.

## 6. Open decisions for M3 (asked, not assumed)

1. **Show Voice Transcripts.** VN Code has voice only as terminal command dictation
   (`RecognizerIntent` in `TerminalPane`). There is no voice conversation in chat, so the item
   has no data to show. Options: (a) add real voice dictation to the chat composer and a
   transcript view, (b) hide the item until voice chat exists, (c) scope it to terminal command
   transcripts. Recommendation: (a) if the item must act, otherwise (b).
2. **Move Chat into New Window.** Android cannot float a child of the same Activity. A real
   second window means a separate task (`documentLaunchMode`, `resizeableActivity`), which on a
   phone appears in recents rather than over the editor, and requires the session manager to be
   process-wide so both windows share one conversation state. Confirm that a second task is the
   intended behaviour, or prefer the full-screen toggle the shell already supports.
3. **Move Chat into Editor Area.** Implementable as an editor tab hosting the chat. Confirm the
   tab and the right-hand sidebar are mutually exclusive (opening one moves the chat, not
   duplicates it).
