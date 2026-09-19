# .versionhistory Name-Only Collision — Plan (NO CODE until Wisdom approves)

Status: PLAN ONLY, v2 2026-09-19 (v1 + Wisdom review fixes: legacy fallback made owner-safe, R6 retention cross-delete verdict added).
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
  checkpoints. Retention is "keep newest 20 PER DIR" (line 259), i.e. PER SHARED DIR:
  one file's active editing can DELETE the other file's checkpoints (cross-deletion).
- APPLY / DISCARD: do not read the dir (drift check reads the working file itself). Safe.
- UNDO: `undoLastApply` (PendingChangesStore.kt:266-285) restores from recorded
  `(path -> checkpointFile)` pairs captured at write time — NOT from a name-based lookup.
  **Undo therefore cannot restore the wrong file.**
- Net: the AI's own apply/undo path is pair-keyed and safe; the collision bites through
- CAN RETENTION DELETE R6 DATA FOR ANOTHER FILE? (Wisdom review question 1b) — READ,
  not suspect: both retention passes delete keep-newest-20 in the SHARED dir with no
  owner check (ExplorerPane.kt:1515 loop, PendingChangesStore.kt:259 checkpoints), and
  undoLastApply (PendingChangesStore.kt:276-279) only restores pairs whose bak STILL
  exists. So YES: heavy snapshotting of same-named file X can delete file Y's
  _prechat.bak, and Y's Undo then degrades to "No checkpoints found to restore" —
  undo DATA lost, but never WRONG content restored (pair-keyed). Trigger needs
  same-named files + ~20 new snapshots while a batch is still undoable. Per-file
  dirs (Option A) fix this automatically: retention becomes per-file.
  (a) retention cross-deletion and (b) the two human Restore UIs (Timeline + Local History).

## 3. Complete reader/writer census (READ)

Writers:
- W1 ExplorerPane.kt:1500-1519 — 20s autosave loop, `<stamp>.bak`, retention delete :1515
- W2 PendingChangesStore.kt:250-262 — AI pre-apply `_prechat.bak`, retention delete :259

Readers (Restore-capable):
- R1 TimelinePanel.kt:65-72 — snapshot list for current file
- R2 TimelinePanel.kt:229-236 — Restore into File(filePath)  **data-loss path**
- R3 ExplorerPane.kt:1686-1693 — Local History dialog list
- R4 ExplorerPane.kt:2338 — Local History Restore into hFile **data-loss path**
- R5 PendingChangesStore.undoLastApply:266-285 — pair-keyed, safe

Visibility-only mentions (not data paths): ExplorerPane.kt:109 (DEFAULT_HIDDEN_NAMES),
ChatAttachPicker.kt:71 (skip list).

## 4. Options — now, after, risk

### Option A — relative-path folders (RECOMMENDED)
`a/main.py` -> `.versionhistory/a/main.py/<stamp>.bak` (dir tree mirrors project tree).
- Now: one shared dir per NAME. After: one dir per FILE PATH.
- Legacy (V2 REVISION 2026-09-19, Wisdom review question 1a): the original
  fallback sketch — "also read the old name-only dir, merged" — does NOT avoid the
  bug: a legacy name-only dir can hold snapshots of SEVERAL same-named files, so
  falling back for b/main.py would list a/main.py's snapshots and Restore would
  repeat the exact data-loss this plan exists to close. REVISED fallback:
  (1) The rel-path dir is the ONLY Restore source. Period.
  (2) A legacy name-only dir is consulted only for VIEWING, and only owner-safely:
      - UNIQUE OWNER: if exactly ONE file with that name exists anywhere in the
        project (name-count walk), the legacy dir is MIGRATED (moved) into that
        file's rel-path dir on first read — snapshots become first-class and
        restorable. This is the common case: most projects have unique names.
      - AMBIGUOUS (two or more same-named files): legacy entries are shown in a
        separate "Legacy snapshots (pre-migration, owner unknown)" section with
        content PREVIEW ONLY — tap to view, NO Restore button. Restoring content
        of unknown ownership into any owner is exactly the gamble this plan
        forbids. A human who recognizes the content can copy it manually.
  (3) Writers never write the legacy form.
- Root-level files: `main.py` at project root writes to `.versionhistory/main.py/` —
  identical to the legacy dir by construction, so root files keep their history as-is
  (and a root file IS the unique owner whenever no same-named file exists elsewhere).
- Rename/move: new path -> new empty history; old snapshots orphaned under the old
  rel-path dir (same as VS Code local history, which keys by resource path). Optional
  later cleanup pass can purge orphans.
- Delete: snapshots remain on disk (harmless; .versionhistory is default-hidden and
  excluded from the 20s loop, search, and attach picker).
- Retention: "keep 20 per dir" becomes truly per-file automatically. Fixes cross-deletion.
- Risk: LOW. Nested mkdirs are fine on Android. Not human-hostile: `ls -R` readable.
  Edge: a file literally named `.versionhistory` cannot appear inside itself (hidden
  excluded), no recursion.

### Option B — hash of relative path (VS Code-style)
`.versionhistory/<sha256(relPath) first 16 hex>/`. Mirrors how VS Code local history
hashes resource URIs. Flat and collision-proof, but NOT human-readable on-device
(Wisdom greps with ls/cat) — debugging harder. Legacy fallback identical to A.
Rename/move/delete behavior identical to A. Risk: LOW, minus debuggability.

### Option C — flat prefixed folders
`.versionhistory/a__main.py/`. Human-readable, flat, cheapest change. Risk: name
ambiguity when a real file name contains `__` (root `x__y.py` vs `x/y.py`) — rare but
real; resolvable only by an extra canonical check. NOT recommended as primary.

**Recommendation: A**, with legacy read-fallback. B is the VS Code-parity alternative
if on-device debuggability is deemed less important than exact parity.

## 5. Tap-by-tap tests (real content, run AFTER implementation is approved)

Setup: one project, two files with SAME NAME, different folders, unmistakable content:
- `a/main.py` containing: `print("AAA alpha bravo charlie")` and `print("AAA line 2")`
- `b/main.py` containing: `print("BBB delta echo foxtrot")` and `print("BBB line 2")`

T1 ISOLATION: open both files; edit both (add a comment line each) so the 20s loop
snapshots both; wait ~25s. Timeline of a/main.py: every listed snapshot restores and
the editor shows only AAA content after Restore. Same for b/main.py with BBB.
No snapshot from one ever restores into the other.

T2 LEGACY: BEFORE upgrading the APK, let some name-only snapshots accumulate for
a/main.py. After the fix, a/main.py's Timeline must still list them (legacy fallback)
and Restore must return a/main.py content. b/main.py's Timeline must NOT list them.

T3 RETENTION: edit both files repeatedly for several minutes (> 20 snapshot cycles
across the pair). Each file must retain ITS OWN newest ~20; no file's history shrinks
to make room for the other's.

T4 LOCAL HISTORY ENTRY POINT: long-press b/main.py -> Local History -> every snapshot
Restore returns BBB content (pre-fix this dialog shows a/main.py's snapshots).

T5 AI UNDO (safety stays): apply an AI edit to each file, then Undo last apply —
each restores its own pre-chat content (pair-keyed undo, expected unchanged).

T6 RENAME/MOVE: move a/main.py to c/main.py — c/main.py's Timeline starts empty
(plus nothing wrong restores); a/main.py's old snapshots stay on disk, hidden.

T7 DELETE: delete b/main.py — no crash, a/main.py Timeline unaffected.

## 6. Out of scope / notes

- Timeline layer 1 (keyed remember) already shipped — closes the STALE-FILE window;
  this plan closes the SAME-NAME window. Both are needed for a fail-closed Timeline.
- `.ide-trash` naming not audited here (separate infra, no name-only pattern reported).
- SUSPECT (not audited in this plan): whether any other feature walks
  `.versionhistory` content directly (grep found none beyond the census above).
