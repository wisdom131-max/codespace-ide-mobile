# B03 — FILE EXPLORER & WORKSPACE

> VS Code parity research, batch 3 of 14.
> VS Code: `microsoft/vscode` @ `main`, verified live 2026-09-14 (GitHub API listings + code search).
> Ours: `codespace-ide-mobile` @ `187d36c`, grep-verified.

## §0 Scope & sources inspected

VS Code: `workbench/contrib/files/browser/views/` (explorerView.ts, openEditorsView.ts, emptyView.ts, explorerViewer.ts, explorerDecorationsProvider.ts), `workbench/browser/parts/editor/` (editorGroupView.ts, editorActions.ts, editorAutoSave.ts, breadcrumbs.ts + breadcrumbsControl/Model/Picker, editorDropTarget.ts, auxiliaryEditorPart.ts, binaryEditor/binaryDiffEditor, editorCommands.ts), `workbench/services/textfile/common/` (textFileEditorModel.ts, textFileEditorModelManager.ts, textFileSaveParticipant.ts, encoding.ts), `workbench/services/workingCopy/common/` (workingCopy.ts, workingCopyBackupService.ts, workingCopyBackupTracker.ts, storedFileWorkingCopy*, untitledFileWorkingCopy*), `workbench/contrib/timeline` + `services/workspaces/common/workspaceTrust.ts` + `platform/workspace/common/workspaceTrust.ts`.

Ours: `ui/panes/ExplorerPane.kt` (3,699 lines), `ui/panes/EditorPane.kt` (2,965), `EditorStripQuickActions.kt`, `EditorTabClose.kt`, `EditorTabColors.kt`, `ui/panes/TimelinePanel.kt`, `ui/panes/ProjectFileSearchPanel.kt`, `FileDetector.kt`, `editor/FileIndexer.kt`, `SplitViewStore.kt`, session restore (SessionStateStore + ProjectPathResolver re-bind).

## §1 What VS Code has (citations)

- **Explorer** (`files/browser/views/explorerView.ts` + `explorerViewer.ts`): tree with lazy children, decorations (`explorerDecorationsProvider.ts` — git/ignored/error status per file), open-editors section (`openEditorsView.ts`) as its own view, drag-copy/move, cut/paste files, context-menu contributed actions, "empty view" state.
- **Editor groups** (`browser/parts/editor/editorGroupView.ts`, `auxiliaryEditorPart.ts`): N groups in any split config, per-group active editor + preview tabs, pinned tabs, group move/join, watermark hints (`editorGroupWatermark.ts`), drag between groups (`editorDropTarget.ts`), per-group view-state persistence, sticky tabs.
- **Breadcrumbs** (`breadcrumbs.ts`, `breadcrumbsModel.ts`, `breadcrumbsControl.ts`, `breadcrumbsPicker.ts`): path + symbol crumbs, symbol picker dropdown, config per-window.
- **Text files** (`services/textfile/common/textFileEditorModel.ts` + manager): per-file working copy model — dirty tracking, save participants (`textFileSaveParticipant.ts` — post-save format-on-save etc.), encoding detection (`encoding.ts`), revert to disk, conflict handling (dirty vs on-disk change).
- **Working copy & backup** (`services/workingCopy/common/`): unified working-copy abstraction for ALL editors (text, custom, notebooks); `workingCopyBackupService.ts` + `workingCopyBackupTracker.ts` = hot exit: unsaved buffers restored across crashes/restarts; untitled-file working copies with backing in-memory model.
- **Autosave** (`editorAutoSave.ts`): after-delay/window-focus/editor-focus-off modes via config.
- **Timeline** (`contrib/timeline`): unified per-file timeline (git history + local history providers).
- **Workspace trust** (`platform/workspace/common/workspaceTrust.ts` + `workbench/services/workspaces/common/workspaceTrust.ts`): restricted-mode gating of features in untrusted folders.
- **Workspace folders** (`services/workspaces/common/workspaceEditing.ts`): multi-root workspaces, add/remove folder.

## §2 Architecture — shared state & connections

- The `IWorkingCopyService` is the pivot: EVERY editor registers a working copy → dirty events, backup tracker, hot-exit restore, "save all", and R6-style checkpoint systems all subscribe to it. Text files are just one working-copy flavor.
- Explorer/open-editors/tabs all read the shared `IEditorGroupService` model; decorations are injected by providers (git → B07), not owned by the tree.
- Breadcrumbs model = file path + `IDocumentSymbol` from LSP providers (→ B09).
- Editor drop target enables tab-to-group moves — group state is the unit, tabs are decorations of it.

## §3 What OUR app has (verified)

- **Explorer**: `ExplorerPane.kt` — tree navigation, new file/folder, rename/delete (with P17-D Trash — `TrashEntry`, restore browser), file info, copy path, per-project root switching, local history section (.versionhistory; 20s explorer loop), `FileDetector.kt` per-type handling, `FileIndexer.kt` name index. No explorer git decorations (blame/diff lives in editor only), no drag-copy, no cut/paste-file.
- **Open editors**: section exists in ExplorerPane (grep hit); no grouping/preview-tab concept.
- **Tabs/splits**: EditorPane strip + `SplitViewStore.kt` (≤4 views/file, PAD-2, cascade close), `EditorTabClose.kt` (dirty guard), `EditorTabColors.kt` theming, `EditorStripQuickActions.kt`. No tab drag reorder (no drag/reorder code found), no multi-group grid — one group, splits are views-of-file not groups-of-files.
- **Breadcrumbs**: exist (EditorPane/ExplorerPane/ProjectShellScreen) incl. the split-button for adding views (PAD-2); no symbol-segment picker (path-only).
- **Dirty/unsaved**: dirty indicators + close guards (EditorPane, CodeEditor, CopilotChatPanel overlays); **save is manual only** — no autosave (grep: no autoSave anywhere), no save participants.
- **Backup/hot exit**: PARTIAL-equivalent via session persistence: open tabs, per-view scroll/cursor (PERSIST-A), find state, locks/folds (SessionStateStore) restored on restart; but unsaved buffer CONTENT is not restored (write-on-save model; EditorBufferStore is a cache keyed to disk content).
- **Timeline**: `TimelinePanel.kt` — .versionhistory snapshots incl. AI pre-apply backups (I1 wiring at EditorPane:60).
- **Workspace trust**: MISSING (no gating anywhere).
- **Multi-root**: MISSING — single project root + root switching.
- **Untitled files**: MISSING (grep: no untitled working copy).
- **Recent files**: palette "Go to File" recent list (ProjectShellScreen/ProjectFileSearchPanel).

## §4 Verdict table

| Feature (VS Code) | Verdict | Gap |
|---|---|---|
| File tree explorer + CRUD (explorerView.ts) | HAVE | richest pane we have; new/rename/delete/trash |
| Explorer decorations from git (explorerDecorationsProvider.ts) | MISSING | no modified/ignored status in tree (only editor-side diff) |
| Open Editors view (openEditorsView.ts) | PARTIAL | list exists; no per-group ordering/preview editing |
| Editor groups + drop between (editorGroupView, editorDropTarget) | PARTIAL | PAD-2 multi-VIEW of one file ≠ groups; no file→file splits, no drag |
| Pinned/sticky/preview tabs | MISSING | — |
| Breadcrumbs (breadcrumbs*.ts) | PARTIAL | path crumbs + split button; no symbol segment/picker |
| Working-copy abstraction for all editors (workingCopy.ts) | MISSING | text-file-only ad-hoc dirty state; no unified service |
| Dirty tracking + close guards | HAVE | per-tab guard on close |
| Save participants (textFileSaveParticipant.ts) | PARTIAL | format-on-save exists? not found — flagged §6 |
| Autosave modes (editorAutoSave.ts) | MISSING | manual save only |
| Hot-exit backup of unsaved content (workingCopyBackupTracker) | MISSING | view state survives, buffer content does not |
| Untitled scratch files | MISSING | — |
| Encoding detection (encoding.ts) | PARTIAL | assume UTF-8; binary/hex viewers exist (HexViewerDialog) — charset detect unknown |
| File revert to disk / conflict handling | PARTIAL | R6 drift-check covers the AI path; user-side revert unknown §6 |
| Timeline (contrib/timeline) | PARTIAL | TimelinePanel = local-history only; git commits not merged in |
| Workspace trust | MISSING | N/A-ish for local device app — noted as intentional |
| Multi-root workspaces | MISSING | single-root by design |
| Recent files quick access | PARTIAL | palette recent list only |

## §5 Cross-subsystem connection edges

- explorer ↔ **B07 SCM**: decorations gap (git status in tree) is the single most user-visible explorer gap; TimelinePanel↔git merge second.
- working-copy pivot ↔ **B13 chat**: R6 PendingChangesStore is a bespoke mini-working-copy (staged edits + apply + drift) — VS Code routes the same problem through the working-copy service + chat edit sessions.
- tabs/splits ↔ **B01**: SplitViewStore + EditorViewStateEffects own per-view state (see B01 §3).
- breadcrumbs ↔ **B09**: symbol segment needs LSP document symbols (same provider as sticky scroll).
- hot-exit ↔ **B14 lifecycle**: unsaved-content backup is the missing half of PERSIST-A (view state done, content not).
- explorer ↔ **B05**: FileIndexer + ProjectFileSearchPanel are file-name search; content search is B05 scope.

## §6 Open questions / on-device verification

1. Does the Explorer 20s snapshot loop write .versionhistory for ALL open files or only active? (affects timeline parity claim)
2. Save flow: any format-on-save? If not, `textFileSaveParticipant` analog is a cheap parity win.
3. Tab long-press menu — does it offer "move to other view" style actions?
4. Untitled/scratch buffer: was "new file" flow always disk-bound? (design question for scratch files)

## Status

**DONE** — 2026-09-14. Next: B04 IntelliSense.
