# AUDIT-CHUNK-1 — SHARED FOUNDATIONS (2026-09-20, audit only)

Every claim tagged READ (verified in repo/vscode-src this session), MEMORY (prior verified read),
SUSPECT (needs read to confirm). Device status: only #2854 device results count as verified.

Legend for the 5 chunks: (1) foundations [this file] (2) file/key writers (3) editor+LSP
(4) chat/AI/settings/models (5) everything else.

---

## F1. ProjectPathResolver — project-root resolution

- Entry: util/ProjectPathResolver.kt:71 `resolveProjectRoot(context, projectId)`; :113 `getAllWorkspaceRoots`; :147 `resolveProjectRootFile`.
- Depends on: SharedPreferences("projects") metadata (`pathOrUrl`, read via readPathOrUrl), SharedPreferences("workspace_prefs" — PREFS_WORKSPACE) keys `workspace_path_$id`, `workspace_roots_$id` (READ :74-77, :124-125).
- Depended on by: LSP initialize (workspace roots), Timeline/Local History root, SourceControlPane, ProjectShellScreen session restore, terminal lock validation, SCM clone flows (READ comment :120-125 listing [TAP] fallback + terminal roots + lock validation callers).
- State: keyed by projectId in two prefs stores. Nothing cached in memory.
- Failure: exception → null + log (:98-101); folder missing → null + AppOutputLog [PATH] line (:91) — MESSAGED, not silent, but callers vary in handling.
- Device: UNVERIFIED on #2854 for multi-root; single-root resolve verified working (CW7/session tests passed on earlier builds; #2854 audit did not retest).
- VS Code: IWorkspaceService/IWorkspaceContextService, `getWorkspace().folders`, workspace.ts (MEMORY, file-level); root = URI, identity via IUriIdentityService — no file-exists probing needed because roots are authoritative.
- Gap size: MEDIUM (resolver order = prefs override → metadata → legacy fallback works, but no canonicalization: `/sdcard` vs `/storage/emulated/0` project roots are distinct strings).
- Risk: UX (wrong root = "folder missing" dead ends).

## F2. ProotInstaller.guestToHostPath / hostToGuestPath — guest↔host translation

- Entry: terminal/ProotInstaller.kt:85 `guestToHostPath`, :121 `hostToGuestPath`.
- Depends on: `rootfsDir(context)` (filesDir/ubuntu-rootfs), filesDir; /host-files projects containment check via canonicalPath compare (READ :96-107).
- Depended on by (READ comment :82-84): LspManager, AgentTools git checks, Problems-panel jump-to-source, terminal OSC 7777 open + tapped-link resolution; also Restore/Apply paths via PendingChangesStore (SUSPECT — chunk 2 reads).
- State: none (pure functions).
- Failure: unknown guest path → rootfs-relative nonexistent File (callers exists() refuse) — SILENT by design; hostToGuestPath → null for unbindable paths (explicit).
- Device: /sdcard case + /host-files/projects case = Fix-batch 3 (bbd6567, build #2844) + #2854 build; UNVERIFIED (BUG-A retest pending). BUG-A root-cause itself was VERIFIED failing on #2854 pre-fix.
- VS Code: no analog needed (single filesystem); URI canonicalization = IUriIdentityService (MEMORY). Our translation layer is a CodeSpace-specific necessity.
- Gap size: MEDIUM (three translators exist with overlapping rules — see coupling map).
- Risk: SECURITY (root-lock containment depends on this), data-loss (wrong path = write to wrong place via Apply).

## F3. IdeTerminalBridge.guestPathToHostFile — terminal tap translation

- Entry: terminal/IdeTerminalBridge.kt:121.
- Depends on: ProotInstaller.guestToHostPath; File.exists() validation.
- Depended on by: resolveTappedFileLink (plain-text `path:line` tokens, A-3/A-5), OSC 7777 handler (SUSPECT, chunk 5).
- State: none. Failure: nonexistent → null → SILENT no-open (A-11 fail-loud gap).
- Device: A-5 VERIFIED FAILING on #2854 (space-delimited paths never resolve; File "…", line N grammar unsupported).
- VS Code: terminalLocalLinkDetector.ts detect() (READ :95-160, comparison doc (e)) — whole-line suffix grammar + trim loop + FS validation, links precomputed per line.
- Gap size: LARGE (word-tap vs line grammar).
- Risk: UX.

## F4. LspManager.fileUriFromHostPath / hostPathFromFileUri — LSP URI translation

- Entry: lsp/LspManager.kt:3373 `fileUriFromHostPath`, :2668 `hostPathFromFileUri` (BUG-4 fix, READ :2660-2666 comment: guest file:// URI → host path; without it cross-file Go-to-Definition silently failed).
- Depends on: ProotInstaller host mappings (SUSPECT inside; chunk 3 reads).
- Depended by: go-to-def/declaration (:2546-2558 EditorPane), references, diagnostics URI mapping (chunk 3).
- Failure: non-file:// → null; decode failure → null. Silent nulls downstream.
- Device: A-9 VERIFIED FAILING on #2854 (def-at-line-1 lands line 2; discriminating retest pending: usage on line 5).
- VS Code: getExtension(languageId)→ExtHost LC; URIs never translated (one filesystem) — our double translation is the structural divergence.
- Gap size: MEDIUM. Risk: UX.

## F5. bugaResolve — tab-path canonicalization + suffix match (onOpenFileAtLine)

- Entry: ui/panes/EditorPane.kt:2160-2195 (BUG-A FIX 2026-09-16, READ).
- Depends on: projectRootPath, File.canonicalFile, tabs list.
- Depended on by: Problems-panel jump (A-3), go-to-def cross-file (:2560), terminal `ide open` (chunk 2/5).
- State: none. Resolution: absolute-vs-root join → canonical → exact → suffix match → new tab only if absent.
- Failure: exception → raw string passthrough (:2173 catch) — SILENT.
- Device: BUG-A fix shipped in #2844/#2854; retest PENDING → UNVERIFIED. Pre-fix A-3 VERIFIED FAILING on #2854 ("Could not read file: denied").
- VS Code: editorService.openCodeEditor({resource…}) — URI identity, no string matching needed (READ gotoCommands.ts :201-238, comparison (b)).
- Gap size: MEDIUM (canonicalization exists HERE only; tab ids themselves are raw paths — see F8).
- Risk: UX (+ duplicate-tab confusion).

## F6. FileCache — content cache

- Entry: editor/FileCache.kt:11 `object FileCache`, `get(path)`; MAX_ENTRIES=20, LARGE_FILE_THRESHOLD=1MB (READ :13-40); LRU via removeEldestEntry; staleness check via lastModified.
- Depended on by: loadFileContent (EditorPane :119-121), terminal/completion paths (SUSPECT).
- State: in-memory only; keyed by RAW path string (no canonicalization) — a /sdcard spelling and /storage/emulated/0 spelling of one file = two cache entries.
- Failure: read error → exception to caller → loadFileContent catches → "// Could not read file: …" placeholder content (READ :118-121) — the A-3/A-11 symptom string.
- Device: A-3 denial symptom VERIFIED on #2854 (pre-fix), fix UNVERIFIED.
- VS Code: TextFileEditorModelManager `mapResourceToModel = new ResourceMap<TextFileEditorModel>()` (READ textFileEditorModelManager.ts :72) — one model per canonical resource, event-driven.
- Gap size: MEDIUM. Risk: UX (stale/duplicate views), potential data-loss if a write path ever trusts cache copy (chunk 2 checks).

## F7. SessionStateStore — per-project view persistence (scrolls/cursors/splits/locks/folds/terminal)

- Entry: data/SessionStateStore.kt:33 prefs "session_state"; keys `cursors_$id` :238, `scrolls_$id` :239, `splits_$id` :241, `folds_$id` :243, `locks_$id` (PAD-1 :162-163), `terminal_$id`, `locks_`… clear-all sweeps :79-80; per-project isolation comment :28.
- Depends on: EditorViewStateEffects.kt (:33-66) — DisposableEffect per view, `onViewStateCapture(viewKey, scrollLine, cursorOffset)`, key = liveViewKey.
- Inner keying: viewKey = tab.id (:978, :1087, :2099 EditorPane) = the tab's PATH STRING (READ :623 `id = path`, :713, :2190 `id = resolved`). NOT canonicalized at creation in legacy paths → PERSIST-B risk (same file, two spellings → separate scroll/cursor histories; A-14 mismatch class).
- Depended on by: EditorPane mount-restore (:600, :636, :2100), session restore.
- Failure: missing/corrupt JSON → emptyMap (READ loadCursors/loadScrollPositions) — silent benign.
- Device: PERSIST-A/B behavior not in #2854 batch → UNVERIFIED (PERSIST-B follow-up is on roadmap).
- VS Code: EditorMemento.saveEditorState keyed by resource.toString() + group (READ editorPane.ts :245-268, comparison (c)); IStorageService scopes (READ storage.ts :19,:61).
- Gap size: MEDIUM (raw-path keys). Risk: UX; data-loss adjacent (restore-to-wrong-file class).

## F8. EditorTab identity (tab.id = path)

- Entry: domain/Models.kt:40 `data class EditorTab`; id assignment sites EditorPane :623/:713/:2190 (READ).
- The app's de-facto per-file key everywhere (activeId, viewKey, scrollToLine target, undo stack). Canonical ONLY at the bugaResolve-created tabs (:2190); legacy/explorer-opened tabs use the path string as given.
- VS Code: editor inputs are URI-keyed; identity never a free string (MEMORY, comparison (c)).
- Gap size: LARGE (identity normalization is the root of A-3/A-14/PERSIST-B classes) — this is why FIX-PLAN Phase 3 defines ONE canonical resolver used at ALL key times.
- Risk: UX + data-loss (dup tabs, restore-to-wrong-tab).

## F9. DecorationStore — per-editor decoration versions

- Entry: editor/DecorationStore.kt:53 `update(newData): VersionedDecoration`, :87 updateSyntax, :92 updateDiagnostics, :97 updateSemanticTokens, :104 updateSearch, :121 updateCursor (READ).
- Depends on: per-CodeEditor instance. Depended by: rendering layer + diagnostics.
- State: in-memory per editor instance; NOT keyed by path — a NEW CodeEditor (tab switch remount) starts EMPTY (squiggle loss on reopen, A-6 opposite face).
- Failure: none (pure state).
- Device: A-6 VERIFIED FAILING on #2854 (cross-tab leak comes from the EditorPane-side unkeyed lspSquiggles, chunk 3 details).
- VS Code: markerDecorationsService per-model decorations rebuilt from resource-keyed markerService (READ, comparison (a)).
- Gap size: MEDIUM (right idea, missing the resource-keyed source of truth).
- Risk: UX.

## F10. SecureTokenStore + ChatKeyPool — key stores

- Entry: data/SecureTokenStore.kt:17 EncryptedSharedPreferences-backed (READ :74 `ai_$provider`, :81 projectSecret, :89 setAiKey, :110 clear); chat/ChatKeyPool.kt:26 init, :33 `index_<provider>` in prefs "chat_key_pool", slots `ai_<ID>_2..` first-free, :70 keys() = ACTIVE first then slot order, :99 addKey, :115 removeKey, :81-90 active-slot persistence.
- Depended by: all 7 provider transports, ChatModelSelection.resolveAuto (:82), settings UI.
- Failure: missing key → null → provider marked unavailable (messaged in UI).
- Device: MK retest batch pending on #2854 → UNVERIFIED (MK-delete + Refetch D-3/D-4 pending).
- VS Code: BaseSecretStorageService (READ secrets.ts :88-101) + encryption platform service (READ dir listing) — OS keychain, never prefs for secrets.
- Gap size: SMALL for the encrypted part; the ORDER/label side data lives in PLAIN prefs "chat_key_pool" (labels + order, not keys) — acceptable, documented.
- Risk: SECURITY (only store with real risk; currently encrypted = OK).

## F11. ChatModelSelection + CustomEndpointStore — model selection

- Entry: chat/ChatModelSelection.kt:29-67 prefs-backed get/set/getForMode (`selected_model_<MODE>`), togglePin, :82 resolveAuto (AUTO sentinel resolved at send); chat/CustomEndpointStore.kt v2 registry: CRUD endpoints {id,label,baseUrl} (READ :18, :49, :56), per-endpoint key slots, version counter.
- Depended by: send path, gauge, picker (chunk 4).
- Failure: stale manual model IDs → vendor 404 "Invalid model: X" (VERIFIED class, memory: Mistral AUTH-first behavior) — messaged, but root = no pending-then-rebind.
- Device: D-group retest pending → UNVERIFIED on #2854.
- VS Code: chatInputModelSelectionController initialize (:169-212 READ) — pending selection + _restoreRememberedModel rebind; chatModelConfigurationStore resolution order (:80-130 READ, comparison (g)).
- Gap size: LARGE (one-slot legacy replaced by registry, but stale-ID send still possible = Phase 5).
- Risk: UX.

## F12. FeatureToggleStore — editor feature toggles

- Entry: editor/FeatureToggleStore.kt:40 `states: MutableMap<String, MutableState<Boolean>>`, :42 init from prefs "feature_toggles", :49 get with defaults, :51 set (persists), :57 state() (Compose-observable), :64 toEditorFeatureToggles.
- Keys include "word_wrap" etc. (EditorStripQuickActions.kt:76 writes it, READ).
- Depended by: CodeEditor (toggles param), quick-actions strip, settings.
- Failure: unknown key → default true (silent).
- Device: E-group/strip untested on #2854 → UNVERIFIED.
- VS Code: IConfigurationService + configuration registry (settings.json), context keys via ContextKeyExpr (MEMORY; = RP1 action-registry plan).
- Gap size: MEDIUM (string keys, no schema, no when-conditions). Risk: UX/cosmetic.

## F13. NotificationStore

- Entry: prefs "notification_settings"; NotificationStore.clearAll()/toggleDoNotDisturb wired at ProjectShellScreen.kt :1196-1215 (READ).
- Device: 0-7 VERIFIED working on #2854 (user confirmed "Notifications: Clear All" works from palette — via handleMenuAction branch :1198-1200).
- VS Code: INotificationService + notification center (MEMORY). Gap: SMALL. Risk: cosmetic.

## F14. handleMenuAction — the shared command dispatcher

- Entry: ProjectShellScreen.kt:1029-1242 (READ, full grep); callers: menu bar (:1306, :1332), gear/person/run menus (:2504…), bottom panels (:1730, :1985), command palette (:2302).
- State: reads/writes ~40 top-level compose state vars (activePanel, showBottomPanel, zenMode, fontSize…).
- Failure: NO case → `else -> {}` (:1241) — SILENT. VERIFIED #2854: 18 of 35 palette labels dead (incl. Toggle Word Wrap, Open Folder, Collapse All in Explorer — work only via their own surfaces; hamburger "Collapse All" :5234 is notification-only TRAP, READ).
- VS Code: CommandsRegistry.registerCommand + MenuRegistry contributions; ContextKeyExpr `when` (MEMORY; RP1_PREPLAN v3, option B locked for P2).
- Gap size: LARGE (static list + string when + silent fall-through; = RP1 registry motivation).
- Risk: UX.

## F15. Persistence strategy census (READ grep, all prefs files)

- 19 distinct SharedPreferences files; counts: projects(8 readers/writers), terminal_prefs(3), app_prefs(3), terminal_history(2), session_state(2), custom_cmds(2), ai_chat_history, vncode_prefs, terminal_mode, terminal_enhancements, search_history, project_settings, notification_settings, keybindings, feature_toggles, editor_session(legacy, self-clearing migration READ :79-84), custom_endpoint, copilot_chat, chat_key_pool.
- Secrets: ONLY SecureTokenStore is encrypted (F10). Everything else plaintext MODE_PRIVATE (app-private sandbox; acceptable on-device).
- File-backed stores (chunk 2): .versionhistory/v2 (Timeline), SYMLINKS-style no; filesDir projects, workspace backups.
- VS Code: IStorageService with scopes (GLOBAL/PROFILE/WORKSPACE) + target (USER/MACHINE) (READ storage.ts :19-61), secrets via secrets service (F10). Our census = no scope concept: project data and app data share filespace; "session_state" is the only project-scoped store (keyed by projectId).
- Gap size: MEDIUM (no scope taxonomy; chunk 2 security tiers interact with this).
- Risk: SECURITY (cross-project reads are all in-app trusted; external root shell can read all plaintext).

## F16. PendingChangesStore (foundation surface; writers detailed in chunk 2)

- Entry: chat/PendingChangesStore.kt:81 `pending = linkedMapOf<String, PendingChange>` (path → staged content), :76 undoGatePaths, :90 stage, :147 apply, :194 applyAll; outcomes Applied/Drift/Blocked/NotFound/Failed (:51-55 READ).
- Session-scoped per chat session; overlayFor (:116) feeds editor read path.
- VS Code: chat editing sessions + working copy service (MEMORY, integration map I-rounds).
- Chunk 2 covers Apply/Undo/checkpoints + tier classification (NO-UNDO plan v3).

---

## COUPLING MAP — shared functions/state with all readers & writers (chunk-1 slice)

| Shared item | Writers | Readers | Notes |
|---|---|---|---|
| ProotInstaller.guestToHostPath (:85) | — (pure) | LspManager, AgentTools git, Problems jump, IdeTerminalBridge.guestPathToHostFile (:136 fallback), OSC 7777 handler | Single implementation post-BUG-A; F3 duplicates the /sdcard case itself (:127) |
| IdeTerminalBridge.guestPathToHostFile (:121) | — | resolveTappedFileLink, `ide open` | Duplicate translator #2 |
| LspManager.hostPathFromFileUri (:2668) / fileUriFromHostPath (:3373) | — | go-to-def/decl/references, diagnostics mapping | Translator #3, URI-flavored |
| bugaResolve (EditorPane :2164) | — (local) | onOpenFileAtLine only | NOT shared: Problems jump, terminal, restore all re-derive their own resolution — 4th mini-translator |
| tab.id (= path string) | EditorPane :623/:713/:2190 (legacy: raw; :2190: canonical) | activeId, viewKey→SessionStateStore, scrollToLine targeting, undo stack, appliedTick observer | THE un-normalized identity at the base of A-3/A-14/PERSIST-B |
| scrollToLine (EditorPane :483) | go-to-def/decl (:2556/:2583), onOpenFileAtLine (:2202), Problems jump, OSC `ide open`; cleared by 1s/6s coroutines | CodeEditor :1078 LaunchedEffect → band+cursor+scroll; EditorPane sticky :1959 | Shared mutable Int across all jump sources = the "band crosstalk" suspect (A-9/A-6) |
| FeatureToggleStore | EditorStripQuickActions :76, Settings UI | CodeEditor toggles param, strip UI | Compose-state singleton |
| handleMenuAction (:1029) | palette/menu rows (input only) | ~40 state vars | Silent else — dead-ends |
| SecureTokenStore | Settings key UI, ChatKeyPool slots | 7 provider transports, resolveAuto | Encrypted |
| ChatKeyPool (plain prefs chat_key_pool) | Settings CRUD | keys() ordering everywhere | Labels/order only |
| FileCache | (implicit on get) | loadFileContent :119, terminal/completion | Raw-path keys |
| SessionStateStore | EditorViewStateEffects capture | EditorPane restore :600/:636/:2100 | projectId-keyed JSON |
| CustomEndpointStore | Settings CRUD | provider transport, picker | version counter v2 |
| appliedTick (EditorPane) | PendingChangesStore.apply via observer | tabs refresh (lastAppliedPaths only) | B-β: restore does NOT bump it (chunk 2) |

## VS Code cross-chunk baseline (foundations)

Upstream's foundations are uniformly: resource URI as identity (ResourceMap in markerService :27, textFileEditorModelManager :72, EditorMemento :245), scoped storage service (storage.ts :19), secrets service (secrets.ts :101), command registry (MEMORY/RP1). Ours: string paths, 4 overlapping translators, 19 unscoped prefs, one dispatcher with silent fall-through. Every F-item gap above traces to one of those four roots — consolidated ranking comes after chunk 5.

## Chunk-1 build/plan cross-references

- PLAN A per-file store (approved direction) = replaces scrollToLine/lspSquiggles keys: see FIX-PLAN-VSCODE-ALIGNED.md Phase 3 + design rule (this conversation).
- RP1 action registry = F14. R6/Apply tiers = F16 (chunk 2). CW7/feature toggles = F12. PERSIST-B = F7/F8.
