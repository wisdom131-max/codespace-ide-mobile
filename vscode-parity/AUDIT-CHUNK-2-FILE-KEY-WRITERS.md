# AUDIT-CHUNK-2 — FILE & KEY WRITERS (2026-09-20, audit only)

READ = verified in repo this session. Device status = #2854 results only.
Cross-refs: chunk 1 = foundations (AUDIT-CHUNK-1-FOUNDATIONS.md); NO-UNDO plan v3 tiers; R6_PREPLAN v2 decisions.

---

## W1. 20s autosave loop (ExplorerPane)

- Entry: ui/panes/ExplorerPane.kt:1502-1524 (READ). Every 20s: files modified <5min, <1MB, excluding .versionhistory/.ide-trash, take(20), copy to v2 dir, trimGrouped.
- ROOT-SOURCE BUG (READ): loop calls ExplorerPane's loadWorkspacePath (:97) which is PREFS-ONLY (`workspace_path_$id` from workspace_prefs) and does `?: return@withContext` (:1504) — a project whose root is resolved via pathOrUrl metadata or the legacy fallback (i.e. MOST projects, per chunk-1 F1 resolver order) gets NO autosave snapshots at all. TerminalPane has its OWN loadWorkspacePath (:595) = full ProjectPathResolver — two resolvers, different tiers, same name.
- Device: A-14 environment — UNVERIFIED which resolver tier BETA project was in; ls -R paste contents not yet re-examined.
- VS Code: localHistoryService takes entries from file save events (READ localHistoryService.ts :760-802, comparison (f)) — event-driven, no root re-derivation possible.
- Gap size: MEDIUM. Risk: data-loss adjacent (snapshot coverage silently zero for prefs-less projects).

## W2. VersionHistoryV2 — snapshot dir scheme

- Entry: util/VersionHistoryV2.kt:22-117 (READ all). v2DirFor = canonical rel-path dir under `.versionhistory/v2/<rel>`; fail-closed on outside-root/`..`; legacyDirFor = view-only; trimGrouped = separate newest-20 groups for .bak vs _prechat.bak (Q1 fix); isSnapshotOf = tap-time Restore assertion (plan v3 cond 1); listSnapshots = isFile rule + newest-first.
- Root-level files: rel = bare filename → v2 dir = `v2/<filename>` (distinct from legacy `.versionhistory/<filename>`) — same-named files in nested dirs no longer collide.
- Device: A-14 dir scheme VERIFIED working on #2854 (snapshots listed; restore is the failing part — see W3).
- VS Code: local history entries in userDataDir, keyed by resource (comparison (f)).
- Gap size: SMALL now (v3 landed). Risk: low.

## W3. TimelinePanel — list + Restore (B-α/B-β)

- Entry: ui/panes/TimelinePanel.kt:52-110 (keyed state, layer-1 fix), :208-275 LocalSnapshotsSection (READ).
- Restore sequence (:260-265): isSnapshotOf guard → PendingChangesStore.discard → snap.copyTo(File(filePath)) → bumpExternalRestore. Disk write is guarded and correct.
- B-β ROOT CAUSE (READ-CONFIRMED, EditorPane.kt:236-249): the appliedTick observer refreshes tabs ONLY through `lastAppliedPaths()` = `lastApplied.value` — populated by chat APPLY, NOT by bumpExternalRestore (:302 just bumps tick + revision). So after a Timeline restore: appliedPaths is EMPTY → `if (appliedPaths.isNotEmpty())` guard fails → no tab refresh. Disk changed, open tab shows old content. This is the #2854 A-14 "Restore did not work" visible symptom (verified failing; root cause now pinned).
- B-α (Local History dialog nested-file root discovery, from #2854 audit): dialog file NOT yet read this session — SUSPECT, lands in chunk 3 (editor surface) or 5.
- VS Code: localHistoryCommands.ts restore() → fileService.writeFile + editor reopens resource (comparison (f)) — URI-keyed, no tick indirection.
- Gap size: MEDIUM (one-line class of fix: observer should refresh open tabs by reading disk for ALL tabs on external tick, or bumpExternalRestore should push paths into lastApplied).
- Risk: data-loss adjacent (user believes restore failed, redoes work; undo gate interplay).

## W4. AI Apply — PendingChangesStore.apply / forceApply / applyAll

- Entry: chat/PendingChangesStore.kt:147-190 apply (READ), :216-248 forceApply, :194-208 applyAll (STOPS at first non-Applied).
- apply: drift verify disk-staged entries only (decision #3); unreadable disk = FAIL CLOSED Blocked (:157-163); checkpoint (1MB cap, decision #5); write = tmp file + rename (:171-178); success bookkeeping = remove pending + undoGatePaths.add + lastApplied append + appliedTick++ + revision.
- forceApply: bypasses drift verify BY DESIGN (user chose), still checkpoints; rename-failure surfaced as Failed.
- state: `pending: linkedMapOf<String, PendingChange>` (:81) keyed by path STRING as staged (chunk-1 F8 identity caveat applies: same file, two spellings = two entries; Apply resolves them independently).
- VS Code: chat editing sessions apply through working copy service with undo entries (MEMORY, integration map); no in-app precedent for drift dialog-free — plan v3 named that divergence deliberately.
- Device: R6-1..R6-11 retest on #2854 → UNVERIFIED; drift/blocked rows not re-tested.
- Gap size: SMALL-to-MEDIUM (path-string keying is the only latent issue). Risk: data-loss LOW (fail-closed verified in code).

## W5. AI checkpoints + Undo last apply

- Entry: PendingChangesStore.kt:251-270 writeCheckpoint (activeProjectRoot :254; 1MB cap :256; null checkpoint = Apply proceeds without undo, Q2 reported); :275-296 undoLastApply (copy back per batch, appliedTick++); :302-305 bumpExternalRestore; :313-319 consumeUndoGate (CodeEditor pushes ONE force undo snapshot; VS Code SingleModelEditStackElement precedent, decision #2).
- activeProjectRoot is a @Volatile var set by the shell (:60) — an app-global, NOT per-project: switching projects without reset would checkpoint into the wrong tree (SUSPECT: find all writers in chunk 4/5).
- VS Code: local history + chat undo services (comparison (f)/(g)); grouped retention = our own fix, no upstream analog needed.
- Device: UNVERIFIED (undo-last-apply not in #2854 batch).
- Gap size: SMALL. Risk: data-loss LOW-MEDIUM (activeProjectRoot global).

## W6. EditorBufferStore — staging base

- Entry: editor/EditorBufferStore.kt:1-33 (READ). Global path→buffer registry; sync() from EditorPane on every tab mutation; contentOf feeds stage() base (decision #3: buffer-centric when open).
- Same path-string keying caveat as W4.
- VS Code: IWorkingCopyService tracks open working copies by URI (MEMORY).
- Gap size: SMALL. Risk: low.

## W7. Agent write paths — STAGED vs DIRECT (bypass)

- CHAT AGENT mode (READ CopilotChatPanelOverlay.kt:617-640): write_file STAGES into PendingChangesStore (:624), staging UNGATED (R6 decision #1), only Apply writes. Plan tool same class (:629).
- DIRECT WRITER (READ agent/AgentTools.kt:243-248): writeFile() = `file.parentFile?.mkdirs(); file.writeText(content)` — no staging, no containment check, no checkpoint.
- Reachable via agent/AgentApiServer.kt POST /tool/write_file (:141+) — the local HTTP API server exposes AgentTools.executeTool, so ANY caller on the LAN loopback (or whatever network the server binds) can overwrite arbitrary app-writable paths with NO gate. Also any NON-agent chat mode path that dispatches write_file directly.
- VS Code: extensions can write the extension host sandbox, but chat edits ALWAYS go through the edit pipeline (chatEditingSessions; comparison (g)) — there is no parallel unguarded writer.
- Device: UNVERIFIED (no #2854 case exercised the API server).
- Gap size: MEDIUM-LARGE (architecture divergence: two write_file semantics in one app).
- Risk: DATA-LOSS + SECURITY. Tier classification (NO-UNDO v3): direct writer must be classified; recommend force-through-FlowGate + contain to active project root, or route to stage.

## W8. Agent run_command — execOnce workdir

- Entry: agent/AgentTools.kt:222-225 → ProotInstaller.execOnce (terminal/ProotInstaller.kt:1356, READ): `cd = "[ -d \"$workdir\" ] && cd \"$workdir\"; "` — workdir is used RAW in the GUEST, no hostToGuest translation. A host path (filesDir/projects/… or /storage/emulated/0/…) fails the `-d` check silently → command runs in guest HOME. A /host-files/projects path works.
- This matches the #2854 C-4 symptom class (agent in in-app project fell back to "here are the commands to copy") — SUSPECT pending chunk 4's read of what workdir the chat agent actually passes (model free-text vs derived from project root).
- VS Code: no analog (extension host runs in-process).
- Gap size: MEDIUM. Risk: UX (silent wrong-cwd), data-loss adjacent (git ops against wrong repo).

## W9. Explorer file ops — trash vs PERMANENT delete

- Single delete: dialog "moved to .ide-trash/ and can be restored" (ExplorerPane:2427) → WorkspaceManager.moveToTrash (util/WorkspaceManager.kt:202-213, READ): rename into trash + TrashEntry(originalPath REL to projectDir, deletedAtMs).
- MULTI-SELECT DELETE (READ ExplorerPane:873-878): `selectedFiles.forEach { f.isDirectory() → deleteRecursively() else delete() }` — PERMANENT, bypasses .ide-trash entirely. Same-touch-distance inconsistency; user has no warning text on this path.
- restoreFromTrash (:239-245): dest-exists → `_restored` suffix; listTrash (:215-229) best-effort name-only (original dir structure lost for nested deletes — comment admits).
- Rename: ExplorerPane:2311 contextFile.renameTo; clipboard cut :1631/:1654 renameTo.
- VS Code: workingCopyFileService.delete(useTrash) + explorer contributions (MEMORY) — trash is the DEFAULT everywhere; permanent delete requires shift+del.
- Device: UNVERIFIED on #2854 (E-group didn't cover multi-select).
- Gap size: MEDIUM. Risk: DATA-LOSS (multi-select delete is one tap from unrecoverable).

## W10. SCM — git operations

- Entry: scm/GitCommandExecutor.kt:33-85 (READ): every run prepends `safe.directory='*'` (:40-42, "dubious ownership" on proot repos); workdir = GUEST path (callers must translate, Timeline :97 passes hostToGuestPath result — READ TimelinePanel:99-104); runRaw shells `git -c safe.directory='*' $command`.
- Agent git tools (AgentTools :181-188) take repo_dir free-text from the model — same workdir caveat as W8.
- SourceControlPane feature set: commit history, stash, branch mgmt, conflict resolution, tags, 30s snapshots (memory, shipped earlier) — pane code re-read due in chunk 5.
- VS Code: git extension spawns git with workspace root; "dubious ownership" is a desktop-IDE concept (MEMORY).
- Device: C-4 VERIFIED-ish (agent git degraded in in-app project; exact failure pending W8/chunk 4). SCM pane itself not re-tested on #2854.
- Gap size: SMALL (centralized executor = good). Risk: low (aside from W8 workdir).

## W11. Key writers — save_secret / setAiKey / endpoint keys

- AgentTools save_secret (:189) → SecureTokenStore (chunk-1 F10, encrypted) — OK.
- ChatKeyPool.addKey / removeKey (:99/:115) write encrypted slots + plain prefs order index (chunk-1 F10).
- CustomEndpointStore key slots per endpoint (chunk-1 F11).
- No key ever written to plaintext prefs VERIFIED in grep census (19 prefs files; only labels/order plain).
- VS Code: ISecretStorageService (secrets.ts :88-101 READ).
- Gap size: NONE for storage; D-3/D-4 MK retests pending. Risk: low.

## W12. Terminal writes

- Interactive terminal = guest bash, writes anything inside proot (by design; user-driven; root-lock guards open-file only, not writes — chunk 5 covers lock store).
- OSC 7777 open + resolveTappedFileLink: read-only (F3).
- Agent run_command (W8) is the only machine-driven guest writer.
- VS Code: terminal = OS process, same trust class. Gap: SMALL.

## W13. Local History dialog (SourceControlPane 30s snapshots)

- From #2854 audit: B-α nested-file root discovery bug SUSPECT lives here or in EditorPane local-history dialog; FILE NOT YET READ this session — explicit chunk 3/5 TODO. Feature shipped earlier (memory): 30s snapshots + restore.
- VS Code: Timeline pane merges local history + git log (comparison (f)) — our TimelinePanel now does too (:82-108).

---

## CHUNK-2 WRITER MATRIX (who can change a file on disk / a key)

| Writer | Path source | Containment | Checkpoint | Trash | Silent on fail? |
|---|---|---|---|---|---|
| CodeEditor save (Ctrl-S / autosave) | tab.path | none | .versionhistory 20s loop (indirect) | no | surfaced toast (chunk 3 verifies) |
| Explorer single delete | workspace tree | project-implicit | no | YES .ide-trash | no |
| Explorer MULTI delete :876 | selection list | none | no | **NO — permanent** | no |
| Explorer rename/cut | workspace tree | none | no | rename | silent false |
| Timeline Restore :262 | activeFilePath | isSnapshotOf guard | n/a (restores one) | n/a | **YES** (catch {} swallow) — B-β hides it |
| PendingChangesStore.apply :147 | staged path | drift + 1MB + activeProjectRoot | YES _prechat.bak | n/a | NO (outcomes surfaced) |
| forceApply :216 | staged path | NONE (by design) | YES | n/a | NO |
| AgentTools.writeFile :243 | model free-text | **NONE** | **NO** | no | NO ("Wrote N chars") |
| AgentApiServer /tool/write_file | HTTP body | **NONE** | **NO** | no | NO |
| Agent run_command :222 | guest bash | root-lock N/A | no | no | **workdir `-d` silent skip** (W8) |
| SourceControlPane commit etc. | repo guest path | safe.directory | git itself | n/a | surfaced |
| save_secret / setAiKey | caller | n/a (encrypted store) | n/a | n/a | no |

## Top chunk-2 findings (ranked)

1. **W9 multi-select delete = permanent deleteRecursively** — data-loss, user-invisible difference from single delete. Recommend routing through moveToTrash (listTrash already supports dirs).
2. **W7 AgentTools.writeFile + AgentApiServer = ungated direct writer** bypassing the R6 staging decision — tier-classify in NO-UNDO v3 and either gate or route to stage.
3. **W3 B-β root cause pinned** (appliedTick observer reads lastAppliedPaths; external restore never registers paths) — one-spot fix, restores confidence in Timeline.
4. **W1 20s loop uses prefs-only resolver and silently skips** most projects — swap to ProjectPathResolver (one-line, matches TerminalPane :595).
5. **W8 raw guest workdir** — translate host→guest (hostToGuestPath) or pass guest paths only.

## Device-verified ledger (chunk 2)

- A-14 Timeline Restore: VERIFIED FAILING on #2854; disk-level restore correct, B-β tab-refresh root cause READ-CONFIRMED; B-α dialog still SUSPECT (file unread).
- C-4 agent git in in-app project: VERIFIED degraded; W8 = primary suspect, chunk 4 confirms workdir source.
- Everything else: UNVERIFIED on #2854.

Done: 2. Next: 3 — editor + LSP (CodeEditor, diagnostics, jump/completion/hover, EditorPane dialogs incl. B-α hunt).
