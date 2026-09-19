# No-Undo Notice Plan (APPLY without checkpoint) — PLAN ONLY, no code until Wisdom approves

Status: PLAN v3 2026-09-19 — Wisdom review round 2: tiers LOCKED (3-tier split of app-internal), canonical classification spec + tests, forceApply non-interference proven, test-round build ref fixed to #2854. Supersedes the round-1 text of section 3a; earlier sections still stand. Wisdom review round 1 had: (a) CONFIRMED as
direction; shared predicate CONFIRMED (one function serves both the notice
and writeCheckpoint); out-of-root analysis extended with app-private paths +
new options; surfaces + re-polish declared; BUILD WAITS for Wisdom's go after
his #2844 test round. All facts READ from source unless marked GUESS.

## 1. The three no-checkpoint cases — exactly what you see (READ)

The three cases (writeCheckpoint returns null, PendingChangesStore.kt:250-268):
  C1  file over 1MB (line ~252: length > 1_048_576L early return — pre-existing since R6)
  C2  activeProjectRoot is null (line ~254 — timing/rare, before the panel's
      LaunchedEffect binds it at CopilotChatPanelOverlay.kt:957)
  C3  out-of-root or containment-assert fail (v3: v2DirFor returns null — the
      file is not under the project root, or the path is unsafe)

All three look IDENTICAL to the user, and identical to a normal apply:

BEFORE Apply (review card):
- The file row (ChatDiffReviewCard.kt:95-135, PendingFileRow) shows the path,
  diff lines, and the Apply / Discard / Rediff buttons ("Force apply" only
  appears on BLOCKED). There is NO checkpoint indicator of any kind — nothing
  hints that this apply will not be undoable.

AFTER Apply:
- The Apply button (ChatDiffReviewCard.kt:101) calls
  PendingChangesStore.apply(entry.path) and DISCARDS the ApplyOutcome. On
  success the entry is removed from pending, so the row simply disappears on
  the next recompose. No success message.
- The footer "Undo last apply (N file(s))" appears (ChatDiffReviewCard.kt:110-114).

AFTER tapping that Undo:
- undoLastApply (PendingChangesStore.kt:276-294) walks the recorded pairs; a
  null or missing checkpoint is skipped; it RETURNS the string
  "No checkpoints found to restore" — but the ONLY call site
  (ChatDiffReviewCard.kt:113) discards the return value. NOTHING is displayed.
  The footer disappears (the batch is cleared) and the file keeps the applied
  content. The user sees a button that "did nothing", with no explanation.

So today the gap is silent TWICE: no pre-apply notice, and the Undo result
string is produced but never shown.

## 2. Can an agent-staged edit even reach a file outside all workspace roots? (READ)

YES. The chain has no path gate:
- STAGING: CopilotChatPanelOverlay.kt:624 calls
  PendingChangesStore.stage(toolArgs.getString("path"), ...) with the model's
  write_file path VERBATIM — no workspace-root validation. Staging is ungated
  by design (R6 decision #1: content that cannot reach disk needs no approval
  gate).
- APPLY: PendingChangesStore.apply (line 147+) has no path restriction either —
  the drift check just reads File(path), then writes temp+rename anywhere the
  process can write.
- The ONLY root binding is activeProjectRoot (set at
  CopilotChatPanelOverlay.kt:957), and it is used solely to locate the
  checkpoint dir (and, since v3, the v2 containment check). It never restricts
  what can be staged or applied.
- Where it is decided: the model's emitted path. The tool description
  (AgentTools.kt:43-44) shows absolute paths ("/path/to/file") with NO root
  constraint, so a model that emits /storage/emulated/0/Download/x.txt while
  the project lives elsewhere stages and applies cleanly — with no checkpoint
  under C3. The system prompt asking for project-relative paths is the only
  (soft) guard.

## 3. Options — now -> after -> risk

(a) Card notice before Apply (+ surface the Undo result string)
    Now: silent everywhere. After: the file row runs a read-only dry-run status
    check (file length + v2DirFor null-check + activeProjectRoot null — the
    SAME logic writeCheckpoint uses, shared helper so they cannot drift);
    if it would fail, a small muted warning line appears above the Apply
    button: "No undo for this file — over 1MB" / "outside the project history
    root" / "no project root bound". Additionally the Undo tap result string
    ("No checkpoints found to restore" / "Restored N file(s)") is shown inline
    on the card instead of being discarded. Risk: LOW — pure additive UI, no
    change to apply/undo semantics; the only behavior change is that a
    previously-invisible message becomes visible (called out here).
    Risk to watch: false-positive warnings if the dry-run drifts from the real
    writeCheckpoint — mitigated by sharing one status helper.

(b) Refuse Apply when no checkpoint is possible
    Now: silent success. After: apply() returns Blocked("no checkpoint
    available") and the row shows a BLOCKED banner; only Force apply could
    proceed. Risk: HIGH — it makes chat edits to any file over 1MB outright
    impossible (the 1MB cap exists because CHECKPOINTING a big file is
    expensive, not because applying is dangerous — R6 decision #5 capped the
    checkpoint, not the apply), and blocks out-of-root files entirely. The
    review card IS the approval surface; the user already decided by tapping
    Apply. Users would notice this as a regression.

(c) Keep as is
    Risk: the exact silent-safety gap flagged in review, plus a visibly
    dead Undo button with no explanation — worst trust outcome, zero cost.

RECOMMEND: (a). It matches VS Code's philosophy (apply/save always proceeds;
local history is best-effort and silently limited), keeps R6 decision #5
intact, and converts two silent failure modes into honest, glanceable text.

## 3a. Wisdom review round 1 — CONFIRMED DECISIONS + ANSWERS

1. SHARED PREDICATE — CONFIRMED. The plan adds ONE function,
   PendingChangesStore.checkpointStatus(path), which writeCheckpoint itself
   then calls to decide each branch (TOO_BIG / NO_ROOT / OUT_OF_ROOT / OK).
   The pre-Apply notice renders from the same function's result; the notice
   and the real checkpoint behavior CANNOT disagree because there is only one
   implementation. No duplicate threshold constants anywhere.

2. WHAT THE CARD SHOWS FOR AN OUT-OF-ROOT PATH (READ): PendingFileRow shows
   File(entry.path).name as the title and entry.path VERBATIM in 9sp muted
   text under it (ChatDiffReviewCard.kt ~:149/:155) — an out-of-root file
   displays e.g. /storage/emulated/0/Download/outside_root.txt in full. So the
   path IS visible today; what is missing is any signal that it is unusual.

   CAN THE MODEL STAGE APP-PRIVATE PATHS (filesDir, other project roots,
   databases)? YES — READ: stage() (CopilotChatPanelOverlay.kt:624) takes any
   path string; apply() writes temp+rename anywhere the process can write,
   which INCLUDES /data/user/0/com.codespace.ide.debug/** (filesDir, shared_prefs
   XML, databases/*.db) and filesDir/ubuntu-rootfs/** (the proot rootfs).
   No gate exists at any layer. Hazard classes, worst first:
     - filesDir/ubuntu-rootfs/** : an AI edit can CORRUPT the Ubuntu rootfs
       (breaks the terminal entirely).
     - databases/*.db : live sqlite corruption (sessions, crash logs).
     - shared_prefs XML : settings/session corruption.
     - OTHER project roots : user data, but NO checkpoint (wrong root) and NO
       20s loop coverage (the loop walks the ACTIVE project only) — a silent
       unrevertable change to that project.
   How the model learns such paths: tool outputs (read_file, run_command,
       ProotInstaller guest-host mappings) leak them; nothing forbids them.

   OPTIONS — now -> after -> risk (WISDOM DECIDES):
   (A) Tiered warning only. Now: silent everywhere. After: the same shared
       predicate returns a REASON plus a CLASS: app-internal (filesDir,
       shared_prefs, databases, ubuntu-rootfs) shows a STRONG warning
       ("App-internal file — applying can break the IDE; no undo"), other
       out-of-root shows the mild warning. Apply proceeds in both cases.
       Risk: LOW (pure display); residual risk: user can still tap through
       into rootfs corruption.
   (B) Apply REFUSES app-internal + Force-apply escape. Now: silent success.
       After: apply() returns Blocked for the app-internal class; the card
       shows the BLOCKED banner (existing pattern); explicit "Force apply"
       (manual decision) proceeds. Mild notice (A) still applies to other
       out-of-root paths. Risk: MEDIUM-LOW — reuses the existing
       Blocked/Force-apply UI, exact-path classification is simple (filesDir
       prefix checks); residual risk: Force apply remains a 2-tap escape, and
       a wrong classification would block a legit file (recoverable via Force).
   (C) Stage-time workspace gate. Now: anything stages. After: stage() itself
       refuses paths outside ALL known workspace roots; the model gets a tool
       result "path outside workspace". Risk: HIGH — changes R6 decision #1
       (staging ungated), breaks deliberate out-of-root writes (the N2 test
       case itself becomes impossible), and multi-root flows.
   RECOMMENDED: (B) for app-internal (real protection for IDE internals, with
   escape), (A) for everything else out-of-root. But Wisdom decides.

3. SURFACES TOUCHED + RE-POLISH DECLARED: ChatDiffReviewCard.kt (PendingFileRow
   warning line + the Undo footer now surfaces the result string), and
   PendingChangesStore.kt (checkpointStatus shared predicate + optional
   app-internal classification for (B)). CopilotChatPanelOverlay.kt is NOT
   touched under (A) or (B). The chat review-card surface is CLOSED (polished
   under Phase 1 authority) — this change DECLARES its re-polish pass: the
   notice line styling (muted, small, rounded container per UI rule) and the
   undo-result line are that pass, shipped together in one build.
   GATE: no code until Wisdom says go, and it WAITS for his #2854 device test
   round (T1-T9 + consolidated re-test) — do not stack a second build on an
   unverified surface.

## 4. Tap-by-tap test for (a) — real content

Setup: a project with hello.py containing: print("hello")

N1 — over 1MB (C1): in the project terminal, one line:
  yes 'print("pad line")' | head -c 1200000 > big.py
Chat: "Add a comment line at the very top of big.py".
- Review card: big.py row shows the diff AND the warning "No undo for this
  file — over 1MB" above Apply. hello.py rows (if any) show no warning.
- Tap Apply -> row disappears. Footer "Undo last apply (1 file(s))".
- Tap Undo -> inline text "No checkpoints found to restore" appears on the
  card; big.py STILL has the comment (reopen to verify); in terminal
  ls .versionhistory/v2/big.py -> "No such file or directory" (never created).

N2 — outside root (C3): Chat: "Write a file to /storage/emulated/0/Download/outside_root.txt containing exactly LINE-ONE".
- Card row shows warning "No undo for this file — outside the project history
  root". Tap Apply -> row gone. File exists in Download with LINE-ONE
  (check via Device Folders or terminal).
- Tap Undo -> "No checkpoints found to restore"; the Download file persists.

N3 — control, no false positive: Chat: "Change hello.py to print goodbye".
- Card: NO warning line. Apply -> Undo -> inline "Restored 1 file(s) to
  pre-chat state"; hello.py is back to print("hello").

N4 — C2 (root not yet bound): a launch-timing race, not reliably tappable;
  covered by code assert only (status helper returns "no project root bound"
  and the same warning renders). NOT an on-device test.

Failure checks: if the warning shows on a normal small in-project file (false
positive, N3), or if Apply behavior changed in ANY of the three cases (blocked,
toast, different flow), the phase failed.

---

## 3b. Wisdom review round 2 — LOCKED three-tier design (v3)

### Tier census — exact paths from source (READ)

TIER 1 — HARD BLOCK, no Force apply, apply() AND forceApply() both refuse:
  1. /data/user/0/<pkg>/shared_prefs/** — 42 source files call
     getSharedPreferences; this holds EVERYTHING sensitive:
     - data/SecureTokenStore.kt:24 — EncryptedSharedPreferences
       "codespace_secure" (refresh token, role, PIN hash, biometric-lock flag).
     - chat/ChatProvider.kt — second EncryptedSharedPreferences holding the AI
       key pool (ChatKeyPool slots ai_<ID>, the multi-key registry).
     - JsonSettingsStore, SessionStateStore, and the other ~39 plain-prefs
       files (settings, session state, UI state).
  2. /data/user/0/<pkg>/databases/** — no app-internal database CODE found by
     grep (the only SQLite opens are the SqliteViewer's read-only opens of
     USER project .db files, SqliteViewerDialog.kt:54/:90); the tier covers
     the dir defensively (WebView/keystore may create files there).
  3. App code: the APK install tree /data/app/** (read-only by the OS
     regardless; classified Tier 1 for explicitness) and bundled assets/lib
     (same read-only class).
  4. Everything else under /data/user/0/<pkg> EXCEPT the two named carve-outs
     below: files/ (logs, crash dumps, misc), cache/, code_cache/, no_backup/.
     RULE: app-internal = whole dataDir minus (ubuntu-rootfs -> Tier 2,
     projects -> Tier 3).

TIER 2 — BLOCK with Force apply + strong warning ("Ubuntu rootfs — applying
  can break the IDE; Force apply to proceed; no undo"):
  - filesDir/ubuntu-rootfs/** (ProotInstaller.kt:68). Editing config there is
    legitimate, so Force stays available.
  - IMPORTANT guest-path note (READ): the model usually speaks GUEST paths
    ("/etc/hostname"). A staged literal "/etc/hostname" is NOT under the
    rootfs on the host side — apply() fails its own write (host /etc is not
    writable), so it self-protects. Classification catches the cases where
    the model emits the HOST spelling of a rootfs path (learned from tool
    output), because classification runs AFTER guest->host translation.

TIER 3 — MILD notice only ("no undo — outside the active project's history
  root"), Apply proceeds as today:
  - filesDir/projects/<other project>/** (ProotInstaller.kt:102 — the
    host-files project store; other projects are user data, mild tier).
  - Everything else not under the active project root: /storage/emulated/0/**
    (Download, DCIM, ...), any other writable path.

### Canonical classification (how ".." tricks, spelling gaps, symlinks die)

Pipeline, in order (all four steps in ONE function, classifyTarget(path)):
  1. GUEST->HOST translation FIRST: reuse ProotInstaller.guestToHostPath
     (already carries the /sdcard <-> /storage/emulated/0 and /host-files
     special cases — the audit-F2-fixed reverse direction included).
  2. /sdcard spelling: folded into step 1's special case (no separate logic
     that could drift).
  3. File(path).canonicalFile — the OS resolves "..", ".", and EVERY existing
     symlink in the chain (this is how /sdcard, already a symlink to
     /storage/emulated/0, collapses; a symlink planted into dataDir would
     resolve to its real target before comparison).
  4. Compare the canonical path against CANONICAL tier roots
     (context.dataDir.canonicalPath, filesDir/ubuntu-rootfs.canonicalPath,
     filesDir/projects.canonicalPath, active project root canonicalPath).
     Tier roots themselves are canonicalized, so spelling/symlink games
     cannot desynchronize the two sides.
  NEW-FILE RULE (fail-open where creation is legit, fail-closed where not):
  write_file creates NEW files, whose full path may not exist yet. Rule:
  canonicalize the DEEPEST EXISTING ANCESTOR and classify by that ancestor's
  containment. A new file inside the active project = normal apply (with
  checkpoint); a new file "inside" dataDir = still Tier 1/2 (its ancestor is
  the blocked dir); a new file in Download = Tier 3.
  If canonicalization throws entirely: classify Tier 2 (block with Force) —
  fail closed with an escape, never silent.

### Q3 — proof Force apply changes nothing for existing drift blocks (READ)

  - DRIFT (disk changed since staging): apply() rebases the diff and the card
    shows "Apply anyway" (ChatDiffReviewCard.kt:165-171) — REGULAR apply
    path, NOT Force. forceApply() is never offered for drift. Untouched.
  - BLOCKED (disk unreadable -> cannot verify): markBlocked
    (PendingChangesStore.kt:323) + card shows "Force apply"/"Discard"
    (ChatDiffReviewCard.kt:172-178). forceApply (:220) skips ONLY the drift
    verify; it still runs the same temp+rename write and tolerates a null
    checkpoint. This exact semantics is preserved.
  - HOW THE TIERS AVOID TOUCHING IT: tier outcomes are a NEW field on the
    Blocked class (reason + forceAllowed flag). The existing unreadable-disk
    Blocked keeps forceAllowed=true exactly as today. Tier 2 sets
    forceAllowed=true with the strong warning; Tier 1 sets forceAllowed=false.
    DEFENSE IN DEPTH (not just UI hiding): apply() AND forceApply() both call
    classifyTarget() FIRST and return Blocked for Tier 1 even when invoked
    directly — a hidden button cannot write Tier 1 paths.
  - Test N5 below pins the existing behavior on device before any change.

### Tests added (v3) — canonical + tier + force non-interference

N5 (regression, run FIRST on #2854 BEFORE the tier build ships): stage an
  AI edit to a file, then in the terminal delete that file; tap Apply ->
  BLOCKED banner "could not read the file on disk" with Force apply + Discard
  EXACTLY as today; Force apply recreates the file with the staged content.
  (This pins today's Blocked semantics so the tier build can be diffed.)
N6 (".." trick): chat: "Write a file to <project>/../main.py" (one level
  above the root). Card shows TIER 3 mild notice if that lands outside the
  root (canonicalization exposes the escape); Apply behaves per Tier 3.
N7 (spelling): stage an edit to /sdcard/<project>/a.py when the project root
  is spelled /storage/emulated/0/<project> — the SAME file must be treated as
  INSIDE the project (checkpoint written, NO mild notice). This is the
  BUG-A-class regression guard.
N8 (symlink, if creatable on device): from the proot terminal try
  ln -s /data/data/<pkg>/files /sdcard/proj/shortcut (if the kernel allows
  it); a write_file to <project>/shortcut/target must classify by the
  RESOLVED target (Tier 1 if it truly lands in files/), never by the
  symlink's innocent spelling. If symlink creation is blocked by the kernel
  (as expected on this device), mark the test SKIPPED and rely on N7 + the
  canonicalFile code assert.
N9 (Tier 1, hard block): chat: "Write to
  /data/data/com.codespace.ide.debug/shared_prefs/codespace_secure.xml the
  text BROKEN" -> the card row shows the HARD-BLOCK banner, NO Apply button,
  NO Force apply button. In terminal, cat the file -> unchanged.
  Same for a databases/ path.
N10 (Tier 2, rootfs with Force): chat: "Write HELLO into the rootfs file
  /host-files/.../ubuntu-rootfs/tmp/greeting.txt" (or the host path shown by
  tools) -> card shows the strong rootfs warning + Force apply; Discard
  leaves tmp untouched; Force applies it (then delete it in terminal).
N11 (Tier 3 control): chat: "Write /storage/emulated/0/Download/notice.txt
  LINE-ONE" -> MILD notice only, plain Apply (no Force needed), applies.

Fix-log: the v2 text said the test round was on #2844 — corrected to #2854
everywhere in this file (Wisdom, round 2, item 4).
