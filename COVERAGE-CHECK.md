# Coverage self-check: every source package vs every audited group

**State:** source-tree enumeration + name-grep of every file/class against all 12 group docs (GROUP-EDITOR/EXPLORER/TABS/SEARCH/INTELLISENSE/LSP/PROBLEMS/DEBUGGER/SCM/CHAT/TERMINAL/EXTENSIONS/PERFORMANCE.md), done 2026-09-24 BEFORE opening the Settings/Testing/Integration/Recovery audits. Method: list every top-level package under `android/app/src/main/java/com/codespace/ide/`, then for each file check whether any group doc's feature inventory, citation, or boundary names it. "Claimed" means a group owns it in an inventory row or an explicit boundary sentence — a passing mention counts only when the group clearly scoped it (marked "partial" otherwise). The vendored `com/termux/` stack is owned by Terminal; `backend/` by Terminal (TP01/TP02, terminal.gateway) and Chat (backend IDE endpoints) as cited; this check covers the app Kotlin tree.

## Package ownership table

| Package | Files / lines | Owner(s) among the 12 | Unclaimed remainder |
|---|---|---|---|
| `agent/` | 10 / 2,112 | Terminal (AgentApiServer, McpShellProfile, AutoInstructionsProvider), Chat (AgentTools, AgentFlowGate, ChatPermissionStore, McpClientManager) | **AgentConnectorManager, AgentEntityManager, AgentMemory, AgentScheduler** (integration cluster) |
| `ai/` | 1 / 287 | none | **WorkspaceContextProvider** (integration cluster) |
| `build/` | 3 / 708 | Problems (BuildRunner, GradleErrorParser, BuildEnvironment) | none |
| `chat/` + `chat/providers/` | 30 / 3,905 | Chat (CF01-CF20: providers, keys, endpoints, sessions, slash, skills, plans, staging, retry/export) | none |
| `data/` | 7 / 1,927 | Chat+Terminal+Perf (NotificationStore), SCM (GitHubAuth, SecureTokenStore), Editor (SessionStateStore, SessionHandoffManager — view-state restore only) | **ApiService, ConnectorsApiClient** (integration); SessionHandoff/SessionState's WORKSPACE-level restore → Recovery |
| `debug/` | 7 / 3,241 | Debugger (DG01-DG14) | none |
| `diagnostics/` | 8 / 1,279 | Problems (DiagnosticManager/Converter/Publisher, ProblemMatcher, LintChecker), Performance (PerformanceMonitor), Debugger (AppOutputLog) | **PortsScanner** (integration cluster) |
| `domain/` | 2 / 105 | Tabs (Models.kt) | **Language.kt** (shared enum — claim by any group that uses it; recommend IntelliSense addendum note, not a group of its own) |
| `editor/` | 93 / 21,392 | Editor (E01-E18: buffer, find/replace, cursors, formatting, undo, view state, overlays), IntelliSense (completions, signatures, hover, snippet popups), Search (FileIndexer, PathCompletion), Perf (PerfProbe) | **textmate/ (9 files, 1,768 lines — the tokenization ENGINE)**, **settings/ (3 files)**, **decorations/BlockLineOverlay.kt**, **TestLensDetector.kt** (E18 deferred the RENDERING to Testing), **PowerUserAnalyzer.kt** |
| `environment/` | 1 / 271 | Terminal (IdeEnvironment, TF10) | none |
| `lsp/` | 18 / 6,941 | LSP (LP/LS gaps: lifecycle, handlers, watchdog) | none |
| `preview/` | 1 / 413 | none | **LivePreviewServer** (preview cluster) |
| `project/` | 9 / 2,203 | Problems (TaskRunner, TaskRunnerPanel; BuildRunner adjacent) | **ProjectWizard, ProjectTemplates, DownloadCenter, ToolchainManager, EnvironmentProfiles, BuildArtifactManager, BuildHistoryStore** |
| `scm/` | 4 / 1,596 | SCM (SG01-SG16) | none |
| `ssh/` | 1 / 264 | none | **SshManager** (integration cluster) |
| `terminal/` | 18 / 4,277 | Terminal (TF01-TF20) | **SshProfile, SshProfileStore** (integration cluster — Terminal's inventory never took them) |
| `ui/` | 102 / 46,608 | Editor/Explorer/Tabs/Search/IntelliSense/Problems/Debugger/SCM/Chat/Terminal/Extensions/Perf claim their panes/panels/screens (full list below) | **see unclaimed clusters** |
| `util/` | 6 / 1,332 | Explorer/SCM (WorkspaceManager, WorkspaceRootsStore), Editor/Chat (ProjectPathResolver), Tabs/SCM/Chat (VersionHistoryV2) | **AxmlDecoder** (power-user cluster) |
| root | 2 / 611 | Performance (MainActivity:205-230 crash-log fix, PF06) | **CodeSpaceApplication.kt**; MainActivity's startup/restore flow → Recovery |

`ui/` claimed panes/screens (verified): EditorPane, EditorTabClose/Colors/Sync, EditorExtraKeysRow, EditorStripQuickActions, ExplorerPane, FindReplaceBar-family, SearchPanel set (ProjectFileSearchPanel, SymbolSearchPanel, SymbolSearch), ProblemsPanel, AdvancedProblemsPanel, BuildPanel, TaskRunnerPanel, TerminalPane, TerminalRootMenu, SourceControlPane, DiffViewer, TimelinePanel, PackageManagerPane, McpServersSection, Chat screens (CopilotChatPanelOverlay, AiKeysSection, Chat* 20 files), DebugToolbarOverlay, DebugConsoleSection, VariableInspectorPanel, AttachDebugDialog, BreakpointConditionDialog, McpPanel family, OutlinePanel, DiagnosticsOverlay, CompletionPopup family, KeybindingSettingsPanel — **claimed? see below**, NotificationStore-adjacent, LegacySnapshotPreview — **see below**, SplitViewStore, AiReviewStrip.

## Unclaimed clusters (the actual finding)

Total unclaimed Kotlin: **~22,900 lines across 5 clusters** — items 1-4 of the remaining plan cover ~9,000; **~13,900 lines fit NONE of the four** and would have been silently missed by a group-name-only completeness check.

### Cluster S — Settings surfaces (item 1's scope) — 3,930 lines
- `ui/screens/SettingsScreen.kt` (non-AI/container parts: Chat/CF claimed AI keys + MK sections; Terminal cited :363-460 container restore — but theme, keybindings, general app config, clear-data, about remain unowned)
- `ui/Theme.kt` (theme engine — no group owns it)
- `ui/panes/KeybindingSettingsPanel.kt` (keybindings UI)
- `editor/settings/` (JsonSettingsStore.kt, SettingsSchema.kt, SettingsMigration.kt — the in-editor settings persistence)
- `ui/screens/InProjectSettingsDialog.kt` (1,797 lines; Editor claimed only the project-settings overlap; dialog-wide audit unowned)
- `ui/screens/PinLockScreen.kt` (app lock/security)
- Boundary notes: FeatureToggleStore claimed by Editor+IntelliSense; AiKeysSection/CustomEndpointsSection belong to Chat CF; MCP servers config → Extensions XF18. Item 1 must claim per-screen what genuinely already lives elsewhere and audit the rest.

### Cluster T — Testing (item 2's scope) — small
- `editor/TestLensDetector.kt` (E18 deferred rendering; Editor's boundary sentence: "Their runners/metrics belong to Testing")
- TaskRunner's `test` task: claimed as ONE row (Problems PB11, 8-task catalogue) — test DISCOVERY, lens UX, per-test results, and debug-test flows are unowned
- Nothing else in the tree is test-specific: no test runner, no discovery service. This is the smallest group of the four.

### Cluster I — Integration (item 3's scope) — 4,456 lines. What "Integration" actually IS in this source:
- **Connectors hub** (P54): `data/ConnectorsApiClient.kt`, `ui/screens/ConnectorsHubSheet.kt`, `ui/screens/ConnectorPatDialog.kt`
- **SSH stack**: `ssh/SshManager.kt`, `ui/panes/SshManagerSheet.kt`, `terminal/SshProfile.kt`, `terminal/SshProfileStore.kt`
- **Agent connector cluster**: `agent/AgentConnectorManager.kt`, `AgentEntityManager.kt`, `AgentMemory.kt`, `AgentScheduler.kt` (4 files, none referenced by Chat despite `agent/` ownership)
- **Backend client**: `data/ApiService.kt` (beyond the two gateway citations)
- **External tool acquisition**: `project/DownloadCenter.kt` + `DownloadCenterPanel.kt`, `ToolchainManager.kt` + `ToolchainPanel.kt`, `EnvironmentProfiles.kt`, `BuildArtifactManager.kt` + `ArtifactPanel.kt`, `BuildHistoryStore.kt` + `BuildHistoryPanel.kt`
- **Misc services**: `diagnostics/PortsScanner.kt`, `ui/panels/ProjectServicesPanel.kt`, `ai/WorkspaceContextProvider.kt`
This answers the user's question: Integration in this app = outbound service/tool connections (SSH, connectors, backend API, toolchain/download), NOT webhooks (none in source) and NOT the MCP tool integration (already owned by Chat CH-series + Extensions XF18).

### Cluster R — Recovery (item 4's scope) — 1,174 lines + boundaries
- `project/CloudBackupManager.kt` + `ui/panels/CloudBackupPanel.kt` (Editor's T5 mentioned them; no group owns them)
- `data/SessionHandoffManager.kt`, `data/SessionStateStore.kt` — Editor claimed VIEW-state restore only (C06); workspace/session-level restore identity belongs to Recovery (Editor's boundary sentence: "Project/workspace restore ... must not be silently folded into a tab UI test")
- `ui/panes/LegacySnapshotPreview.kt` (old snapshot preview — TB01/SG02 chains own the STORE; the preview surface is unowned)
- App-shell startup/restore: `CodeSpaceApplication.kt` (fully unclaimed), `MainActivity.kt` startup flow + `crash_logs` handling (Performance claimed only the main-thread fix)
- `ui/screens/NotificationDrawerOverlay.kt` (notification tray UI — stores owned by Chat/Problems citations, drawer surface unowned; closest home: Recovery's "what the user sees after a crash/restart" or app-shell)
- Already claimed as boundaries: Terminal TF13/TF14 (session-death forensics, crash-count restore guard), TB-series (tab dirty/backup), VersionHistoryV2 (TB01/SG02), container restore (TP06).

### Cluster O — ORPHANS that fit none of the four — ~13,900 lines (flagged for owner decision)
- **O1. Preview/media surfaces (~3,000 lines):** `preview/LivePreviewServer.kt`, `ui/panes/PreviewPane.kt`, `MarkdownPreviewRouter.kt` (Tabs mentioned it in passing only), `MediaViewers.kt`, `PdfViewerDialog.kt`
- **O2. Power-user binary inspection suite (~6,400 lines):** `ui/panes/PowerUserPanels.kt`, `editor/PowerUserAnalyzer.kt`, `util/AxmlDecoder.kt`, and the viewer dialogs: ElfViewer, DexViewer, SmaliViewer, HexViewer, ApkAnalyzer, ArchiveViewer, BinaryInspector, BinaryDiffViewer, DisassemblyViewer, EntropyHeatmap, NetworkViewer, StringsViewer, SqliteViewer, AndroidRuntimeViewer, AiModelViewer, LogcatPanel, plus FileDetector, FileInfoDialog
- **O3. App shell/onboarding (~4,000 lines):** `ui/screens/HomeScreen.kt`, `AuthScreen.kt`, `project/ProjectWizard.kt`, `ProjectTemplates.kt`, `CodeSpaceApplication.kt`, `ui/CodeSpaceApp.kt`, `WorkspaceShapes.kt`, `di/AppModule.kt`, `domain/Language.kt`, `ui/panes/ShellHistorySearchOverlay.kt`, `TextExpansionSheet.kt` (store claimed by TF20; sheet unowned), `ImageGenDialog.kt` + `ImageGenService.kt` (AI image generation)
- **O4. TextMate tokenization engine (1,768 lines):** `editor/textmate/` (9 files: TextMateEngine, TmTokenizer, TmGrammarLoader, TmTheme, TmIntegration, TmRule, TmStateStack, OnigRegexFactory, TextMateEngineHolder) — the app's syntax tokenization core; Editor/IntelliSense audited the RENDERING and CONSUMERS, never the engine (the parity sweep's "tokenization audit" real-project entry was never claimed by a group)

## Proposed work plan (for owner ruling; one group per turn as before)

1. **Settings** (Cluster S, ~3.9k lines) — audit the whole settings surface; per screen, state explicitly what Chat/Extensions/Terminal already own.
2. **Testing** (Cluster T, small) — test lens + test task + debug-test flow; will be the shortest group doc.
3. **Integration** (Cluster I, ~4.5k lines) — SSH, connectors hub, backend client, toolchain/download, agent connectors.
4. **Recovery** (Cluster R, ~1.2k lines + boundaries) — crash recovery, session/workspace restore, cloud backup, app-shell restore flow.
5. **Proposed group 18: "Viewers, Preview & App Shell"** (Clusters O1-O3 minus noted exceptions, ~10k lines) — one group for the read-only inspection surfaces, preview/media stack, and the shell/onboarding screens. Alternative: distribute as addenda (TextMate O4 → Editor addendum; ImageGen → Chat/AI addendum; ShellHistorySearchOverlay + TextExpansionSheet → Terminal addendum; onboarding → Explorer or Settings). My recommendation: run group 18 for O1-O3 (they share the read-only-inspection/app-shell pattern and no existing group's scope covers them), and handle O4 TextMate as an **Editor addendum** in the same turn (it is editor-core tokenization, not viewer tooling), with ImageGen as a Chat-group addendum row. Owner decides.
6. Then **MASTER-CONNECTIONS.md**, now covering 18 groups instead of 13 — the ~13,900-line orphan finding is the reason this check ran first.
