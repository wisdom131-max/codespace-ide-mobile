# Master gap register (source-confirmed, device unverified)

A living fix-plan input, not a record of passed or failed device tests. Priority describes the order for *eventual* fix planning, not authority to change code. Each group audit adds its gaps here and links its detailed evidence. Source paths below are relative to `android/app/src/main/java/com/codespace/ide/`; line numbers are 1-based.

## Editor: accepted audit ([GROUP-EDITOR.md](GROUP-EDITOR.md))

| Gap | Priority | Source-confirmed behavior / risk | Evidence and check |
|---|---|---|---|
| **G01** | **HIGHEST, fix-plan priority #1** | On each edit of an absolute-path file, EditorPane marks the tab dirty, attempts a disk write, and silently catches a write failure. The open buffer may show the new text while the disk does not, and Save can still clear dirty state. Treat this as the first issue to resolve when the user authorizes an implementation plan; do not confuse a success notification with verified persistence. | `ui/panes/EditorPane.kt:2081-2092,305-316`; Editor T01/T18. |
| **G10** | **HIGH, distinct Save-route divergence** | **File > Save and Ctrl+S are different callbacks, not just different write timings.** File menu calls `formatOnSaveTrigger++`, immediately announces success, then an EditorPane effect consults `ProjectSettingsStore.formatOnSaveEnabled`; Ctrl+S routes through `CodeEditor.onSave` to `saveCurrentFile`, writes without that formatting branch. Thus when Format on Save is enabled, menu Save *may change the text* while Ctrl+S will not; both routes swallow write errors and clear dirty state, and both issue LSP `didSave`. A separate shell command/palette `Save` handler only announces success (`ProjectShellScreen.kt:1070`). Confirm the behavioral divergence in a disposable file, including actual disk bytes, not merely timestamps. | `ui/screens/ProjectShellScreen.kt:412,1070,1176`; `ui/panes/EditorPane.kt:263-316,2132`; `editor/CodeEditor.kt:3029-3037`; Editor T18. |
| G02 | HIGH | External history restore ticks editor refresh without recording the restored file among `lastAppliedPaths`; the refresh loop filters on those paths. An open tab need not refresh when restoring a snapshot. | `chat/PendingChangesStore.kt:302-308`; `ui/panes/EditorPane.kt:235-250`; `ui/panes/ExplorerPane.kt:2359-2373`; Editor T37. |
| G03 | HIGH | Path spellings split buffer/cache/view identities; suffix fallback may ambiguously choose one of two files. | `editor/EditorBufferStore.kt:14-34`; `editor/FileCache.kt:27-58`; `ui/panes/EditorPane.kt:2157-2209`; Editor T38. |
| G04 | MEDIUM | Per-mounted-view `DecorationStore` and pane-level LSP squiggles have distinct lifetimes; diagnostic matching permits basename fallback. | `editor/EditorDecorations.kt:15-42`; `ui/panes/EditorPane.kt:1360-1382,1550-1563`; Editor T27/T28. |
| G05 | MEDIUM | Shell and pane jump/viewport state have multiple writers and line conventions. | `ui/screens/ProjectShellScreen.kt:1518-1522,4642-4653`; `ui/panes/EditorPane.kt:484-490,2157-2209`; Editor T14/T15. |
| G06 | MEDIUM | Whole-buffer undo snapshots are per mounted editor view while split views share tab content. | `editor/CodeEditor.kt:994`; `ui/panes/EditorPane.kt:2051-2053`; Editor T39. |
| G07 | MEDIUM | FileCache uses raw path and last-modified time, and external history copies do not explicitly invalidate it. | `editor/FileCache.kt:27-58`; `ui/panes/ExplorerPane.kt:2368-2370`; Editor T37. |
| G08 | MEDIUM | Find query/toggle persistence is global rather than resource- or project-keyed. | `editor/EditorFindState.kt:12-52`; Editor T40. |
| G09 | MEDIUM | Direct, selected-range, LSP and save-time formatting take distinct paths; some write failures are swallowed. | `ui/panes/EditorPane.kt:263-308,1170-1282`; Editor T16-T18. Distinct G10 isolates Save-route behavior. |

## Explorer

Explorer source audit: [GROUP-EXPLORER.md](GROUP-EXPLORER.md). DATA-RISK checks are confined to disposable roots; no device result is asserted.

| Gap | Priority | Source-confirmed behavior / risk | Evidence and check |
|---|---|---|---|
| **EX01** | HIGH | `WorkspaceManager.moveToTrash` returns an entry without checking `renameTo` success. Single delete and bulk-delete counting can claim a move that did not occur. | `util/WorkspaceManager.kt:202-213`; `ui/panes/ExplorerPane.kt:2450-2470,2487-2500`; XT33. |
| **EX02** | HIGH | Trash listing reconstructs only the basename, so nested files restore to the root rather than their original folder; `_restored` collision fallback is only one level. | `util/WorkspaceManager.kt:215-244`; XT16/XT34. |
| **EX03** | MEDIUM | Cut/Paste drops clipboard even if `renameTo` fails; directory copy calls nonrecursive `copyTo`, and shell rename callback is not used. | `ui/panes/ExplorerPane.kt:1631-1653`; XT14/XT35. |
| **EX04** | HIGH, containment | New File, New Folder and Rename accept slash/`..` inputs without canonical containment checks. User input can specify paths outside the chosen directory; device impact not tested. | `ui/panes/ExplorerPane.kt:2146-2334`; XT36 only in isolated scratch root. |
| **EX05** | HIGH, containment | ZIP extraction compares canonical paths with `startsWith(dirPath)` lacking a path-separator boundary, so a sibling `out2` can pass for output root `out`. Avoid untrusted archives in real workspaces until fixed. | `ui/panes/ExplorerPane.kt:1737-1778`; XT36 only in isolated scratch root. |
| **EX06** | MEDIUM | New Folder reports success without checking `mkdirs()` result. | `ui/panes/ExplorerPane.kt:2260-2296`; XT37. |
| **EX07** | HIGH, destructive scope | Cloned-root `Remove & delete files` uses `deleteRecursively()` after only a minimum path-depth check; it does not assert canonical containment within an allowed project root. | `ui/panes/ExplorerPane.kt:1162-1211`; XT18 disposable clone only. |
| **EX08** | MEDIUM | Multi-select All can append duplicate paths; it only walks displayed root and intentionally excludes internal files even if shown. | `ui/panes/ExplorerPane.kt:833-854`; XT11/XT12. |
