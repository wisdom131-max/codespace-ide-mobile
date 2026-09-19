# No-Undo Notice Plan (APPLY without checkpoint) — PLAN ONLY, no code until Wisdom approves

Status: PLAN v2 2026-09-19 — Wisdom review round 1 answered: (a) CONFIRMED as
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
   GATE: no code until Wisdom says go, and it WAITS for his #2844 device test
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
