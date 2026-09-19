# .versionhistory Name-Only Collision — Plan (NO CODE until Wisdom approves)

Status: PLAN ONLY, v3 2026-09-19 (v2 + Wisdom review round 2: legacy dirs are
view-only for EVERYONE including root files, unique-owner migration DROPPED,
new snapshots isolated under .versionhistory/v2/, path-canonicalization rules,
retention/timing facts, revised tests).
Everything below is READ from source unless marked SUSPECT.

## 1. What happens TODAY (READ)

All three snapshot writers store under `<projectRoot>/.versionhistory/<FILE NAME ONLY>/`:

- PendingChangesStore.kt:255 — `val vhDir = File(File(root, ".versionhistory"), f.name)`
- ExplorerPane.kt:1512 — `val vhDir = File(vhRoot, file.name)` (20s autosave loop)
- TimelinePanel.kt:67 — reads `File(projectDir, ".versionhistory/" + File(filePath).name)`

**Scenario: snapshots exist for `a/main.py`; open Timeline for `b/main.py`, tap Restore.**
1. TimelinePanel.kt:67 resolves the dir as `.versionhistory/main.py` — the SAME dir
   `a/main.py` writes to. The list therefore shows `a/main.py`'s snapshots.
2. Tapping Restore (TimelinePanel.kt:230-235) runs `snap.copyTo(File(filePath), overwrite = true)`
   where `snap` = a/main.py's snapshot and `filePath` = b/main.py.
3. **Result: a/main.py's old content is written into b/main.py**, silently, no
   confirmation. `PendingChangesStore.discard(b/main.py)` then drops b's staged overlay
   and `bumpExternalRestore()` refreshes the editor so the wrong content is displayed.

**Second entry point, same bug:** Explorer long-press → "Local History" (ExplorerPane.kt:1686)
lists `.versionhistory/<f.name>` and its Restore (ExplorerPane.kt:2338) runs
`snap.copyTo(hFile, overwrite = true)` — identical cross-file write for same-named files.

## 2. R6 AI checkpoints — same folders? Wrong-file risk? (READ)

- WRITE: `PendingChangesStore.writeCheckpoint` (PendingChangesStore.kt:250-262) writes
  `_prechat.bak` into the SAME name-only dir → two same-named files interleave their
  checkpoints. Retention is "keep newest 20 PER DIR" (line 261), i.e. PER SHARED DIR:
  one file's active editing can DELETE the other file's checkpoints (cross-deletion).
- APPLY / DISCARD: do not read the dir (drift check reads the working file itself). Safe.
- UNDO: `undoLastApply` (PendingChangesStore.kt:266-285) restores from recorded
  `(path -> checkpointFile)` pairs captured at write time — NOT from a name-based lookup.
  **Undo therefore cannot restore the wrong file.**
- Net: the AI's own apply/undo path is pair-keyed and safe; the collision bites through
  (a) retention cross-deletion and (b) the two human Restore UIs (Timeline + Local History).
- CAN RETENTION DELETE R6 DATA FOR ANOTHER FILE? (Wisdom review question 1b) — READ,
  not suspect: both retention passes delete keep-newest-20 in the SHARED dir with no
  owner check (ExplorerPane.kt:1517 loop, PendingChangesStore.kt:261 checkpoints), and
  undoLastApply (PendingChangesStore.kt:276-279) only restores pairs whose bak STILL
  exists. So YES: heavy snapshotting of same-named file X can delete file Y's
  _prechat.bak, and Y's Undo then degrades to "No checkpoints found to restore" —
  undo DATA lost, but never WRONG content restored (pair-keyed). Trigger needs
  same-named files + ~20 new snapshots while a batch is still undoable. Per-file
  dirs (Option A) fix this automatically: retention becomes per-file.

## 3. Complete reader/writer census (READ)

Writers:
- W1 ExplorerPane.kt:1500-1519 — 20s autosave loop, `<stamp>.bak`, retention delete :1517
- W2 PendingChangesStore.kt:250-262 — AI pre-apply `_prechat.bak`, retention delete :261

Readers (Restore-capable):
- R1 TimelinePanel.kt:65-72 — snapshot list for current file (take(20), :68-69)
- R2 TimelinePanel.kt:229-236 — Restore into File(filePath)  **data-loss path**
- R3 ExplorerPane.kt:1686-1693 — Local History dialog list
- R4 ExplorerPane.kt:2338 — Local History Restore into hFile **data-loss path**
- R5 PendingChangesStore.undoLastApply:266-285 — pair-keyed, safe

Visibility-only mentions (not data paths): ExplorerPane.kt:109 (DEFAULT_HIDDEN_NAMES),
ChatAttachPicker.kt:71 (skip list).

**Retention facts (READ, both writers):**
- W1 ExplorerPane.kt:1517 — `sortedByDescending(lastModified).drop(20)` → keeps the
  newest 20 files in the dir, deletes the rest, on EVERY write.
- W2 PendingChangesStore.kt:261 — identical keep-newest-20 on every checkpoint write.
- File-size cap in both writers: < 1MB (ExplorerPane.kt:1510 filter,
  PendingChangesStore.kt:253 early-return).
- W1 cadence: one pass every 20s; a pass snapshots only files modified within the last
  5 minutes (cutoff at :1508), max 20 files per pass (:1511 take(20)).
- **So YES — the "legacy history is small" claim holds (READ): every legacy name-only
  dir is bounded at ≤ 20 snapshot files, each ≤ 1MB, because both writers trim on every
  write and both shipped with that cap.**

## 4. Chosen design (Option A, v3) — `.versionhistory/v2/<relative path>/`

### 4.1 Storage rule

- NEW snapshots go ONLY under `<projectRoot>/.versionhistory/v2/<relative path to the
  project root>/<stamp>.bak` (AI checkpoints: `<stamp>_prechat.bak`).
  - `a/main.py` → `.versionhistory/v2/a/main.py/...`
  - root `main.py` → `.versionhistory/v2/main.py/...` — NO LONGER the same dir as the
    legacy `.versionhistory/main.py/`, closing the root-file hole (Wisdom point 1).
- The rel-path dir is the ONLY Restore source, for BOTH human Restore UIs:
  - Timeline (R1/R2) and Local History (R3/R4) both read `.versionhistory/v2/<rel>/`
    and NEITHER offers Restore from any legacy dir. Confirmed as a plan requirement
    on all four code sites (TimelinePanel.kt:67 reader + :230 restore;
    ExplorerPane.kt:1686 reader + :2338 restore) — Wisdom point 6.
- LEGACY top-level dirs (`.versionhistory/<name>/`, the pre-v2 form) are VIEW-ONLY for
  EVERY file, root files included:
  - Listed in a separate "Legacy snapshots (pre-v2, owner unknown)" section.
  - Tap = content PREVIEW (read-only viewer). NO Restore button, ever.
  - Reason (Wisdom point 2): "only one file with that name exists TODAY" proves nothing
    about WHO wrote a given legacy snapshot — a same-named file may have been deleted,
    renamed or moved earlier. UNIQUE-OWNER MIGRATION IS DROPPED (v2's §4 fallback is
    dead). No ownership inference of any kind; uniform view-only.
  - Bounded cost of never restorable: ≤ 20 files x ≤ 1MB per legacy dir (READ, §3).
- Marker-name edge (READ-analyzed): the literal `v2` segment can collide only with a
  project file/folder literally named `v2` — e.g. root file `v2` would use dir
  `.versionhistory/v2/v2/`, which is also the parent of folder-v2's snapshot dirs.
  This is cosmetic-only: snapshot files are `.bak` files directly in their dir, and
  readers list only FILES in their own dir, so no `.bak` from one owner can ever mix
  with another's. Readers must filter `isFile` (both already effectively do).

### 4.2 Path safety (same class as BUG-A) — Wisdom point 4

- Canonicalize BOTH sides before computing the relative path:
  `File(filePath).canonicalFile` and `File(projectRoot).canonicalFile`.
  On Android, `getCanonicalPath` resolves the `/sdcard` → `/storage/emulated/0`
  symlink, so both spellings of the same file produce the SAME rel path — the exact
  BUG-A failure class is eliminated at the source. (The proot guest-side
  /host-files <-> filesDir mapping stays a separate, already-fixed concern; v2 dirs
  are only ever computed from host-side canonical paths.)
- Containment assertion: compute `rel = file.relativeTo(root)` inside try/catch
  (mirrors the existing guard at TimelinePanel.kt:89-91 for the git rel path).
  REJECT and skip snapshotting if: `relativeTo` throws (file outside root), the rel
  path contains `..`, the rel path is absolute, or the resolved target dir is not
  under `.versionhistory/v2/` (re-check after mkdir via canonical comparison).
  Fail-closed = no snapshot, never a wrong snapshot.
- File NOT under the active project root (extra workspace folders):
  - Base root = the CONTAINING workspace root: walk the project's roots
    (ProjectPathResolver.getAllWorkspaceRoots, same source the terminal ROOT LOCK menu
    uses, TerminalRootMenu.kt:137/WorkspaceRootsSection), canonicalize each, pick the
    root that is an ancestor of the canonical file (longest match wins on ties).
    Snapshots for that file then live under THAT root's `.versionhistory/v2/<rel
    against that root>/` — multi-root workspaces get per-root history, same rule as
    the 20s loop's per-projectDir walk, just extended per-root.
  - File outside ALL workspace roots (ad-hoc device file, e.g. opened from Device
    Folders): NO snapshot is written and the Timeline local-snapshot section shows
    empty ("no local snapshots") — never an error dialog, never a wrong restore.
    Writers already scope themselves (the 20s loop only walks its projectDir;
    writeCheckpoint only fires for staged agent applies, which are workspace files),
    so this is a reader-side rule plus a writer-side containment assert.

### 4.3 What changes for rename/move/delete

- Rename/move: new path → new empty history; old v2 snapshots orphaned under the old
  rel-path dir (same as VS Code local history, which keys by resource path). Optional
  later cleanup pass can purge orphans.
- Delete: snapshots remain on disk (harmless; .versionhistory is default-hidden,
  excluded from the 20s loop, search, and attach picker).
- Retention: keep-newest-20 stays, but is now PER FILE (per v2 dir) — the R6
  cross-deletion (§2, READ) is fixed automatically.

### 4.4 Dropped alternatives (kept for the record)

- v2's merged legacy fallback + unique-owner migration: DROPPED (Wisdom points 1+2 —
  root-file dir collision + migration is unsafe).
- Hash of relative path (VS Code-style): still viable, less debuggable on-device.
- Flat prefixed folders (`a__main.py`): name ambiguity, not recommended.

## 5. Tap-by-tap tests (real content, run AFTER implementation is approved)

Setup: one project, two files with SAME NAME, different folders, unmistakable content:
- `a/main.py` containing: `print("AAA alpha bravo charlie")` and `print("AAA line 2")`
- `b/main.py` containing: `print("BBB delta echo foxtrot")` and `print("BBB line 2")`
- ALSO a root `main.py` containing: `print("ROOT root of the tree")` (three same-named
  files: root + a/ + b/ — stresses the root-file hole directly).

T1 ISOLATION (v2 dirs): open all three files; edit each (add a comment line each) so
the 20s loop snapshots them; wait ~25s. Timeline of a/main.py: every listed snapshot
restores and the editor shows only AAA content. Same for b/main.py (BBB) and root
main.py (ROOT). No snapshot from one ever restores into another.

T2 LEGACY VIEW-ONLY (Wisdom point 5a — both a root file AND a nested file): seed a
legacy dir manually via the project terminal (one command per line):

```
mkdir -p .versionhistory/main.py
echo "LEGACY marker 12345" > .versionhistory/main.py/20260101_000000.bak
ls .versionhistory/main.py
```

Then:
- Root main.py Timeline → "Legacy snapshots (pre-v2, owner unknown)" section lists
  the 20260101_000000.bak entry → tap → content PREVIEW shows "LEGACY marker 12345" →
  NO Restore button anywhere in the legacy section.
- Nested a/main.py Timeline → same legacy entry appears (owner genuinely unknown) →
  preview only, NO Restore button.
- The v2 snapshot list (restorable) stays separate and lists ONLY entries created
  after the migration build.
- Explorer long-press root main.py → "Local History" → same two sections, same rule:
  legacy = preview only.

T3 RETENTION (Wisdom point 5c — exact counts and wait times): the 20s loop snapshots
every file whose last-modified is within the 5-minute cutoff, once per 20s pass.
Procedure: edit BOTH a/main.py and b/main.py every ~30 seconds (alternate: touch a,
touch b, repeat — any tiny change) for 12 minutes ≈ 24 snapshot passes per file
(> the keep-20 cap). Then:

```
ls .versionhistory/v2/a/main.py | wc -l
ls .versionhistory/v2/b/main.py | wc -l
```

Expected: BOTH print exactly 20 (retention trims on every write; the count never
exceeds 20 after the 21st write). Verify the survivors are the NEWEST 20: the oldest
files (first ~4 stamps) are gone from each dir, and each dir's newest entry timestamp
is within the last minute. Each file keeps ITS OWN 20 — a's count must never shrink
because of b (the pre-fix shared-dir cross-deletion, §2).

T4 LOCAL HISTORY ENTRY POINT: long-press b/main.py → Local History → every v2
snapshot Restore returns BBB content; the legacy section shows preview only.

T5 AI UNDO (safety stays): apply an AI edit to each file, then Undo last apply —
each restores its own pre-chat content (pair-keyed undo, expected unchanged).
Checkpoints now land in `.versionhistory/v2/a/main.py/..._prechat.bak` and
`.versionhistory/v2/b/main.py/..._prechat.bak` respectively (verify with ls).

T6 RENAME/MOVE: move a/main.py to c/main.py — c/main.py's Timeline starts empty;
a/main.py's old snapshots stay on disk, hidden. No wrong restore offered.

T7 DELETE: delete b/main.py — no crash, a/main.py Timeline unaffected.

T8 OUT-OF-ROOT FILE (Wisdom point 5b): open a file that is NOT under any workspace
root (Device Folders → pick a file outside the project, e.g. from Download) →
Timeline local-snapshot section shows empty ("no local snapshots"), no crash; edit it
and wait 40s → NO snapshot created under ANY project's .versionhistory (verify with
`ls .versionhistory/v2` in each project — no entry for it). If the project has an
EXTRA workspace folder configured: a file in that extra root gets snapshots under the
EXTRA root's own `.versionhistory/v2/<rel>/` with rel computed against the extra root,
and they restore correctly (canonicalization test: open the file via a /sdcard-spelled
path and via its /storage/emulated/0 spelling — same snapshot list both ways).

T9 PATH-FORM PARITY (/sdcard vs /storage/emulated/0 — the BUG-A class): open
a/main.py normally, snapshot it (edit + wait ~25s), then open the SAME file through
the other path form (e.g. via Device Folders under /storage/emulated/0/... if the
project was opened via /sdcard, or vice versa). Timeline must show the SAME snapshot
list in both forms, and Restore in either form writes the right file. No duplicate
empty history, no "could not read" restore.

## 6. Out of scope / notes

- Timeline layer 1 (keyed remember) already shipped (#2849) — closes the STALE-FILE
  window; this plan closes the SAME-NAME window. Both are needed for a fail-closed
  Timeline; layer 2 (tap-time guard) becomes largely redundant under v3 dirs but a
  cheap "snap must live under .versionhistory/v2/<computed rel>/" assertion at Restore
  time is still worth adding as defense in depth.
- `.ide-trash` naming not audited here (separate infra, no name-only pattern reported).
- SUSPECT (not audited in this plan): whether any other feature walks
  `.versionhistory` content directly (grep found none beyond the census above).
