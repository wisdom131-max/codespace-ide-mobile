# C12 PANEL SPEC: Full Chat-Attach Panel, Two Stages (owner ruling 2026-10-08)

**Status:** SPEC for owner approval. NO build until approved AND round-1 device results are back (standing hold). Stage 1 lands at S6 (easy round); Stage 2 after V2 editor IO.
**Owner ruling:** build the panel FULLY — no reduced version; two stages, nothing dropped. View + edit of the copy that rides the send is v1 (Option 1); write-back to the real file is Stage 2 (Option 2), sequenced AFTER V2 so the full capability ships.

---

## 0. Correction to the earlier C12-a finding (READ, honesty first)

Today's persistence layer stores LESS than my earlier report implied: FILE attachments
persist as DESCRIPTORS ONLY (path/relPath/name/kind — msgToJson ~185), and content is
re-read from disk on every request (buildBlock). What DOES ride session blobs + the
/sdcard prefs backup in plaintext today: SELECTION snippets (`s`, capped 4000) and the
message text. The shipped design persists MORE (the stored copy of FILE content), so
the secrets rules below apply to the new copies AND retroactively cover the existing
selText plaintext leak.

---

## 1. Current surface (READ-verified)

- `chat/ChatAttachment.kt` — model (path, relPath, name, kind FILE/SELECTION/IMAGE/AUDIO,
  selText, mimeType). No content field for FILE.
- `ui/screens/ChatAttachPicker.kt` — ChatAttachmentChips:102 (removable chips, NOT
  tappable), ChatAttachPickerDialog:156 (search + tap-to-attach).
- `chat/ChatAttachmentInjector.kt` — buildBlock: re-reads files at send; caps
  12000/attachment, 24000/message; "#path" token resolution.
- `ui/screens/CopilotChatPanelOverlay.kt` — attachments state :1079, chips clear on
  send, msgToJson/msgFromJson persist descriptors + selText ≤4000, restored
  conversations re-send from re-read files (CH04 parity).
- Persistence: `copilot_chat` SharedPreferences blobs; backupPrefs copies every
  shared_prefs *.xml to /sdcard (so everything persisted rides backups).

---

## 2. STAGE 1 (S6) — complete panel, five revertable commits

### C12-s1-a. `chat/AttachmentSecrets.kt` + JVM tests (pure Kotlin, no UI)
- Filename EXCLUSION list: `.env`, `.env.*`, `*.pem`, `id_rsa*`, `*.key`,
  `credentials*`, `secrets*` → persisted copy becomes the marker
  `[content not stored: secret-looking file]`.
- Token REDACTION (best-effort, stated in plan and sheet copy): `sk-`, `AKIA`,
  `hf_`, `AIza`, `xox`, `eyJ` (JWT), `ghp_`, `-----BEGIN PRIVATE KEY-----` blocks.
- Eviction: per session, keep stored copies for the LAST ~10 attachments or ~200 KB
  total, whichever hits first; older copies → marker that says WHY:
  `[content evicted to save space — attach again to view]`. FAILED/EXCLUDED entries
  never advance the byte budget beyond their marker.
- JVM tests: every pattern, exclusion match, eviction order, byte-budget accounting.

### C12-s1-b. Attach-time copy + tappable chips + the AttachmentSheet (pre-send)
- Attach time: read up to 64 KB (text sniff: invalid UTF-8 or NUL in first 8 KB →
  BINARY flag) into an in-memory `copy` — the copy that will ride the send.
- Chips become TAPPABLE → open the sheet (× removal stays).
- NEW `ui/panels/AttachmentSheet.kt`:
  - Header: relPath, size, kind.
  - CHANGE NOTICE: first-8 KB hash at attach vs now — "no change detected" (exact
    ruling wording) or "file changed since attaching — the edited copy still rides
    the send".
  - "showing X of N characters" counter over the capped view (X = what the send will
    use given the 12000/24000 caps; N = full copy size).
  - EDITOR: multiline field editing the COPY. SELECTION kind edits its snippet the
    same way. IMAGE/AUDIO: info row only (sent as a part), no editor.
  - Actions: "Reset to attached version", "Remove attachment", close.
- Build-block unchanged in this commit (copy consumed in C12-s1-e) — sheet edits are
  live in state but the send still reads disk for ONE commit, then flips. (Keeps each
  commit single-purpose; the interim state is honest: the sheet's counter already
  reflects what WILL ride the send.)

### C12-s1-c. Binary/non-text attach-time warning (own tiny commit, per ruling)
- On attaching a binary: dialog "Attached as binary — content cannot be edited or
  shown in the sheet." In the sheet: editor disabled, read-only notice.

### C12-s1-d. Persistence: stored copy + exclusion/redaction/eviction + labels
- msgToJson persists, per FILE/SELECTION attachment, the copy that rode the send
  (≤12000): excluded-by-filename → marker; else redacted best-effort; `redacted`
  flag when anything was excluded/redacted; eviction applied per session budget.
- Post-send chips become tappable → the sheet in POST-SEND mode: READ-ONLY, header
  "stored copy" or "stored copy, secrets redacted" (exact ruling wording) whenever
  anything was excluded/redacted, "showing X of N characters" over the stored view.
  The "byte-exact by construction" claim does NOT appear for excluded/redacted cases.
- msgFromJson ignores unknown fields both ways (old blobs load; new blobs on old
  builds degrade to descriptor behavior).
- Restored conversations WITHOUT a stored copy (evicted/excluded/old blob) fall back
  to today's behavior: re-read from disk (CH04 parity preserved, honestly stated).

### C12-s1-e. Send path consumes the copy
- buildBlock FILE branch: use the (possibly edited) copy, not a fresh disk read;
  unchanged caps; unreadable-missing-file class disappears for pre-send attaches
  (the copy is in memory) and stays for restored-descriptor fallback.
- The copy that rode the send is exactly what s1-d persisted.

### Removed / replaced
- Nothing removed (ruling expectation confirmed). Superseded: non-tappable chips;
  silent plaintext selText persistence (now redacted); absent post-send view.

### Rollback per commit
a: revert, nothing depends on it yet. b: revert sheet + chip-tap wiring; send path
untouched. c: revert warning. d: revert persistence (old-style blobs keep loading).
e: revert send path (disk read returns; sheet still works on the copy for display).

### READ / SUSPECT (Stage 1)
- READ: everything in §1; ModalBottomSheet precedent exists in the app's sheets.
- SUSPECT: exact in-memory cap 64 KB (proposal — owner may adjust); chip tap vs
  long-press conflict with the existing × remove gesture (build resolves by tap =
  open, × = remove); hash cost on huge files (bounded to first 8 KB by design);
  whether the composer and message-row chips share one sheet component (plan: one
  component, two modes).

### Device checks (S6 easy round)
1. Attach a text file → chip tap → sheet shows content + "no change detected" +
   counter; edit → send → request carries the EDITED copy (visible in the request
   log / response); post-send chip tap → read-only stored copy.
2. Edit the file on disk after attaching → notice flips to "file changed since
   attaching".
3. Attach a binary → warning dialog; sheet editor disabled.
4. Attach a `.env`-style file → send → post-send sheet shows marker + "stored copy,
   secrets redacted".
5. Restart the app → stored copies survive (and evicted ones show the why-marker).

---

## 3. STAGE 2 (after V2 editor IO) — write-back + Open in editor

### Capabilities
- "Save to file" in the sheet writes the copy back to the REAL project file through
  the GATED write path only (R6 staging / V2 editor IO). The chat surface never
  performs an ungated disk write — it calls an injected writer API
  (`EditorFileWriter.write(path, content, baseHash)`); the V2 integration owns the
  actual IO. Exact writer signature re-READ against V2's shipped API at build time.
- "Open in editor" action: opens the file in the editor pane (onOpenFileAtLine
  family; the path-form normalization lessons apply — host/guest/proot forms).
- Conflict handling: if the file changed on disk since attaching (same first-8 KB
  hash vs current disk), the save dialog states it explicitly and shows WHICH
  VERSION WILL BE KEPT (the edited sheet copy replaces the disk version), requiring
  an explicit confirmation tap ("Overwrite disk version") before any write; cancel
  keeps both (no write).

### Files touched (Stage 2, planned now, READ at build time)
- ui/panels/AttachmentSheet.kt (actions row), chat/ChatAttachment.kt (base hash),
  the V2 editor-IO writer surface (dependency), post-send + pre-send wiring.
- Commits: s2-a writer hookup + Save-to-file with conflict gate; s2-b Open-in-editor.
  Each revertable; no data migrations; nothing removed.

### Standing dependencies
- V2 editor IO must be live (no ungated writes, no chat-surface staging reimplementation).
- Stage 2 needs its own quick READ pass against V2's shipped API before the build.
