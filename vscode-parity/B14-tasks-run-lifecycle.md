# B14 — TASKS, RUN & LIFECYCLE

> VS Code parity research, batch 14 of 14 (final subsystem batch; cross-batch review follows).
> VS Code: `microsoft/vscode` @ `main`, verified live 2026-09-14 (GitHub API listings).
> Ours: `codespace-ide-mobile` @ `17607f6`, grep-verified.

## §0 Scope & sources inspected

VS Code: `workbench/contrib/tasks/browser/` (verified: abstractTaskService, taskService, terminalTaskSystem, taskProblemMonitor, taskTerminalStatus, taskQuickPick, tasksQuickAccess, runAutomaticTasks, task.contribution), `workbench/services/workingCopy/browser/` (verified: workingCopyBackupService, workingCopyBackupTracker, **workingCopyHistoryService**), `workbench/services/textfile/common/textfiles.ts` (autoSave + hotExit configuration), `platform/files` watcher (parcel watcher), lifecycleService + storageService (window persistence), contrib/debug debugTaskRunner (B08).

Ours: `project/` package — TaskRunner.kt (TaskId, Task w/ icon enum BUILD/RELEASE/CLEAN/LINT/TEST/INSTALL/BUNDLE/ALL, RunState IDLE/RUNNING/SUCCESS/FAILED, TaskRun, suspend run), BuildArtifactManager, BuildHistoryStore, CloudBackupManager, DownloadCenter, EnvironmentProfiles, ProjectTemplates, ProjectWizard, ToolchainManager; `ui/panels/TaskRunnerPanel.kt`; `BuildPanel.kt`; agent run_command (FlowGate); `editor/FileIndexer.kt`, `preview/LivePreviewServer.kt`; version-history snapshots (30s, SourceControlPane); PERSIST-A view-state persistence; pending PERSIST-B audit.

## §1 What VS Code has (citations)

- **Tasks** (`contrib/tasks`): tasks.json (declarative + programmatic providers), tasks run IN TERMINALS (`terminalTaskSystem.ts`), **taskProblemMonitor.ts** — task output parsed by problem matchers into the Problems panel (the B11 §6 open question's VS Code source), task status in terminal tabs (`taskTerminalStatus.ts`), quick-pick + quick-access task launch, `runAutomaticTasks` (configure-default-build-task flow), auto-detect tasks from package.json/etc.
- **Run lifecycle**: pre-launch tasks for debug (debugTaskRunner, B08), task dependsOn/ordering (dependsOrder), background watching tasks.
- **Working copies** (`services/workingCopy`): unified model for ALL dirty state (editors, terminals, custom editors) — `workingCopyBackupService` (hot-exit backups of unsaved content), `workingCopyBackupTracker` (backup on shutdown/continuous), `workingCopyHistoryService` (LOCAL FILE HISTORY as a service — the direct ancestor of our 30s .versionhistory).
- **autoSave / hotExit** (`textfiles.ts` + files configuration): afterDelay/onFocusChange/onWindowChange autoSave modes; hotExit restores unsaved edits across restarts.
- **File watching** (`platform/files` parcel watcher): recursive native watcher, event coalescing, correlation IDs for compute moves.
- **Window lifecycle**: lifecycleService w/ shutdown reasons; storageService serializes per-window/workspace state (B12's counterpart); multiple aux windows.

## §2 Architecture — shared state & connections

- Task → terminal → problems is a three-subsystem pipeline (B06+B11+B14): the terminal isn't just display, it's the task RUNNER and the problem SOURCE.
- WorkingCopyService is the single hub for "what is dirty?" — save/backup/history/revert all read it. Our PendingChangesStore (R6) + buffer stores are per-surface analogs; the unification is the difference.
- Lifecycle + storage: window state (layout, view states — PERSIST-A/B) restores on boot via the same lifecycle hook that flushes backups.

## §3 What OUR app has (verified)

- **TaskRunner stack**: 8 task types (BUILD/RELEASE/CLEAN/LINT/TEST/INSTALL/BUNDLE/ALL) w/ RunState + TaskRun records, `BuildArtifactManager` (artifacts), `BuildHistoryStore` (history), `ToolchainManager` (toolchain mgmt), `EnvironmentProfiles`, TaskRunnerPanel UI — a rich, Android-project-flavored task system.
- **Agent run_command**: chat-executed tasks run in-terminal w/ FlowGate gating (R6 discipline).
- **Local history**: 30s snapshots + TimelinePanel + per-apply checkpoints (R6) — functionally `workingCopyHistoryService`-adjacent; ours is file-copy based, not service-unified.
- **AutoSave: MISSING** (grep-empty — no autoSave concept; save is manual). **Hot exit: PARTIAL** — view state restores (PERSIST-A) but unsaved-buffer restore §6; terminal layout survives, processes don't (B06).
- **File watching: PARTIAL** — FileIndexer (index-based, editor-driven), LivePreviewServer (live reload for preview), no general recursive watcher.
- **Problem matchers: MISSING** (grep-empty) — build output goes to build channel only; B11's §6-3 now answered.
- **Task problem/status integration**: build channel + TaskRunnerPanel; no taskTerminalStatus analog (tasks don't mark terminal tabs).
- **Lifecycle**: onDestroy hooks in TerminalPane/ExplorerPane/McpClientManager (dispose per-surface); no unified lifecycleService; PERSIST-B audit pending for full state restore.
- **HAVE-UNIQUE**: CloudBackupManager + CloudBackupPanel (cloud project backup), DownloadCenter, ProjectWizard/ProjectTemplates (project scaffolding — VS Code needs extensions/walkthroughs), **LivePreviewServer** (built-in live preview — VS Code's is an extension).

## §4 Verdict table

| Feature (VS Code) | Verdict | Gap |
|---|---|---|
| Declarative tasks (tasks.json) | PARTIAL | TaskRunner is code-defined; per-project task config §6 |
| Task types + run state | HAVE | 8 types + RunState + history |
| Tasks run in terminals | HAVE | run_command + FlowGate; user tasks in TerminalPane |
| Task → problem matchers → Problems panel | MISSING | grep-verified; B11 edge now confirmed open |
| Task quick-pick / auto-detect | PARTIAL | TaskRunnerPanel buttons; palette integration §6 |
| Pre-launch task for debug | MISSING | debug launch doesn't chain tasks |
| Depends-on task ordering | PARTIAL | BuildPipeline order implicit §6 |
| Local file history service | PARTIAL-HAVE | 30s snapshots + checkpoints; not unified w/ SCM timeline (B03/B07) |
| autoSave modes | MISSING | manual save only — candidate feature |
| Hot exit (unsaved restore) | UNKNOWN | PERSIST-B audit must answer §6 |
| WorkingCopyService unification | PARTIAL | PendingChangesStore + buffer stores per-surface; no hub |
| File watcher (recursive native) | PARTIAL | FileIndexer + LivePreviewServer only |
| Lifecycle service + storage serialization | PARTIAL | per-surface onDestroy; PERSIST-A done, B pending |
| Multi-window / aux windows | N/A | mobile |
| Cloud backup / download center / project wizard / live preview | HAVE-UNIQUE | outside VS Code core entirely |

## §5 Cross-subsystem connection edges

- task→terminal→problems pipeline ↔ **B06+B11**: problem matchers would close the loop from BuildPanel output into ProblemsPanel (both batches flag it; cheap-ish: regex matchers on build channel lines).
- WorkingCopy hub ↔ **R6/B03**: unifying PendingChangesStore + buffer + backup + history under one dirty-state model is the architectural lesson of workingCopyService.
- lifecycle ↔ **PERSIST-B**: the audit should check hot-exit-ness (unsaved buffer restore) explicitly — B14's answer determines the verdict upgrade.
- autoSave ↔ **B01**: would ride the same content-change path; interplay w/ R6 staging (staged vs autosaved divergence) needs design care.
- LivePreviewServer ↔ **B03 preview system**: built-in — VS Code's Live Preview is an extension; noted HAVE-UNIQUE.

## §6 Open questions / on-device verification

1. Hot exit: does an unsaved editor buffer survive app kill/restart today? (PERSIST-B item — explicit test: type, don't save, kill app, reopen)
2. TaskRunner: are tasks per-project configurable (Gradle variants?) or fixed 8?
3. Build output regex → Problems: what do our build errors look like (Kotlinc pattern) — matcher feasibility?
4. EnvironmentProfiles: env-var switching for builds — scope?
5. Does BuildHistoryStore persist across restarts?
6. LivePreviewServer: which file types trigger reload — HTML only or Markdown too?

## Status

**DONE** — 2026-09-15. All 14 subsystem batches complete. Next: FINAL CROSS-BATCH REVIEW (consolidated cheap-wins + real-projects priority list).
