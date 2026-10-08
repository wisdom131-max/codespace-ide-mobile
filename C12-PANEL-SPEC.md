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
- Eviction, TWO tiers (advisor item 5): PER SESSION, keep stored copies for the
  LAST ~10 attachments or ~200 KB total, whichever hits first; PLUS a GLOBAL cap
  across ALL sessions (proposal: ~1 MB total, owner may adjust), OLDEST evicted
  first (by message timestamp). Evicted copies → marker that says WHY:
  `[content evicted to save space — attach again to view]`. FAILED/EXCLUDED entries
  never advance the byte budget beyond their marker.
- JVM tests: every pattern, exclusion match, eviction order, per-session AND global
  byte-budget accounting, oldest-first ordering.

### C12-s1-b. Attach-time copy + tappable chips + the AttachmentSheet (pre-send)
- Attach time: read up to 64 KB (text sniff: invalid UTF-8 or NUL in first 8 KB →
  BINARY flag) into an in-memory snapshot — the VIEW/COMPARE basis, with an EDITED
  flag that turns on the moment the user changes anything in the sheet.
- **Advisor item 1 (send semantics):** the UNEDITED attachment keeps the FRESH DISK
  READ at send time (original behavior); the in-memory copy rides the send ONLY
  when the user EDITED it (override). A stale attach-time snapshot is never sent
  silently — the edited state is always user-caused and visible in the sheet.
- Chips become TAPPABLE → open the sheet (× removal stays). **Advisor item 6:**
  chip body = one large tap target (opens the sheet); the × is a separate trailing
  icon button with a ≥48 dp touch target and clear spacing from the chip body,
  so open-tap and remove-tap cannot be confused on a phone.
- NEW `ui/panels/AttachmentSheet.kt`:
  - Header: relPath, size, kind.
  - CHANGE NOTICE: first-8 KB hash at attach vs now — "no change detected" (exact
    ruling wording) or "file changed since attaching — the edited copy still rides
    the send".
  - "showing X of N characters" counter over the capped VIEW.
  - **Advisor item 2 (send-portion notice):** when the send caps will truncate, the
    sheet states exactly what rides: "Only the first N characters will be sent; the
    rest is not included" (N computed from the 12000/24000 caps at send time). Edits
    BEYOND the cap are allowed (the copy stays whole) but carry a persistent flag
    line in the sheet ("content past character N is not sent") so nothing is sent
    silently less than shown.
  - EDITOR: multiline field editing the copy (turns the EDITED flag on). SELECTION
    kind edits its snippet the same way. IMAGE/AUDIO: info row only (sent as a
    part), no editor.
  - Actions: "Reset to attached version" (clears the EDITED flag back to the
    snapshot), "Remove attachment", close.
- Build-block unchanged in this commit (copy consumption lands in C12-s1-e) — each
  commit stays single-purpose; the sheet's notices already reflect what WILL ride
  the send.

### C12-s1-c. Binary/non-text attach-time warning (own tiny commit, per ruling)
- On attaching a binary: dialog "Attached as binary — content cannot be edited or
  shown in the sheet." In the sheet: editor disabled, read-only notice.

### C12-s1-d. Persistence: stored copy + exclusion/redaction/eviction + labels
- msgToJson persists, per FILE/SELECTION attachment, the content that ACTUALLY rode
  the send (≤12000 — per item 1: the fresh disk read for unedited attachments, the
  edited copy for overrides): excluded-by-filename → marker; else redacted
  best-effort; `redacted` flag when anything was excluded/redacted; eviction applied
  per session AND global budgets.
- Post-send chips become tappable → the sheet in POST-SEND mode: READ-ONLY, header
  "stored copy" or "stored copy, secrets redacted" (exact ruling wording) whenever
  anything was excluded/redacted, "showing X of N characters" over the stored view.
  The "byte-exact by construction" claim does NOT appear for excluded/redacted cases.
- msgFromJson ignores unknown fields both ways (old blobs load; new blobs on old
  builds degrade to descriptor behavior).
- **ONE-TIME SCRUB (advisor item 4):** on SESSION LOAD, the redaction rules run
  over every EXISTING persisted text field — old selText snippets first (today's
  plaintext leak), then any stored copies — and the scrubbed sessions are
  rewritten to storage in the same pass. Idempotent; no marker added when nothing
  matched.
  **What it changes:** in-app storage (the `copilot_chat` prefs blobs) stops
  carrying old secrets from the first load after this ships, and stays scrubbed.
  **What it does NOT change:** backup files ALREADY on shared storage keep their
  pre-scrub copies until the next backup cycle replaces them — the prefs-backup
  folder is rewritten by onAppStart's backupPrefs at every launch (grant-gated),
  so the /sdcard copy turns over at the first app start after the scrub; anything
  copied or extracted elsewhere before then is out of reach and stays until
  overwritten. Stated in the sheet plan and changelog so expectations are honest.
- Restored conversations WITHOUT a stored copy (evicted/excluded/old blob) fall back
  to today's behavior: re-read from disk (CH04 parity preserved, honestly stated).

### C12-s1-e. Send path: edited override or fresh disk read (advisor item 1)
- buildBlock FILE branch: EDITED attachments → the in-memory copy rides the send
  (the override, visibly chosen in the sheet); UNEDITED attachments → FRESH DISK
  READ at send time (unchanged original behavior — no stale snapshot is ever sent
  silently). Caps unchanged. Unreadable-missing-file class stays for unedited and
  restored-descriptor attachments (honest skip, as today).
- Whatever actually rode the send (disk-read content or edited copy) is exactly
  what s1-d persists.

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

**Status: amended per advisor item 3 (2026-10-08) — RETURNS FOR APPROVAL; not yet
owner-approved.**

### Capabilities
- "Save to file" in the PRE-SEND sheet writes the copy back to the REAL project
  file through the GATED write path only (R6 staging / V2 editor IO). The chat
  surface never performs an ungated disk write — it calls an injected writer API
  (`EditorFileWriter.write(path, content, baseLen, baseHash)`); the V2 integration
  owns the actual IO. Exact writer signature re-READ against V2's shipped API at
  build time.
- **Advisor item 3a:** Save to file is DISABLED unless the ENTIRE file was loaded
  into the in-memory copy (no truncation — files at/over the 64 KB snapshot cap or
  send caps show Save disabled with the reason: "only part of this file is loaded").
- **Advisor item 3b:** Save writes ONLY from the in-memory EDITING copy — never
  from a stored/redacted/persisted copy (the post-send sheet has no Save action at
  all; its copies are display-only by design).
- **Advisor item 3c:** the conflict gate compares FULL FILE LENGTH + FULL HASH of
  the disk file vs the attach-time snapshot (not the first-8 KB view hash — that
  hash remains view-only), and the WRITE preserves the original line endings and
  encoding of the in-memory copy (no newline translation, no re-encoding).
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
