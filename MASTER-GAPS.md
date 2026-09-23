# Master gap register (source-confirmed, device unverified)

A living fix-plan input, not a record of passed or failed device tests. Priority describes the order for *eventual* fix planning, not authority to change code. Each group audit adds its gaps here and links its detailed evidence. Source paths below are relative to `android/app/src/main/java/com/codespace/ide/`; line numbers are 1-based.

## Fix-plan ordering and shared failure pattern

**TOP TIER (co-priority, above every other listed gap): G01, EX04, EX05, EX07.** G01 is a silent data-loss risk; EX04 is file-creation/rename path escape, EX05 is archive extraction escape (zip-slip boundary), and EX07 is a destructive remove-root scope risk. Treat the latter three as security-relevant containment issues, not mere UI correctness. These rankings are for a later user-authorized fix plan, not permission to implement now. Do not use a real project to reproduce them.

**SYSTEMIC GAP S01: unchecked operation outcome and false success.** G01 (file write), EX01 (trash move), EX02 (nested restore) and EX06 (folder create) all permit a success-looking outcome without proving the requested file result. EX02's mechanism is specifically *lost original-path metadata plus the restore UI ignoring the Boolean result*, not an unchecked `mkdirs()` or write call. When planning fixes, prefer a shared typed result/verification contract for file operations and user-facing success notifications, preserving original paths for restore; do not assume one helper alone fixes EX02's metadata loss. Keep the four IDs as traceable manifestations and test them individually, but design the fix as one systemic workstream where feasible.

## Editor: accepted audit ([GROUP-EDITOR.md](GROUP-EDITOR.md))

| Gap | Priority | Source-confirmed behavior / risk | Evidence and check |
|---|---|---|---|
| **G01** | **TOP TIER, co-priority with EX04/EX05/EX07** | On each edit of an absolute-path file, EditorPane marks the tab dirty, attempts a disk write, and silently catches a write failure. The open buffer may show the new text while the disk does not, and Save can still clear dirty state. Treat this in the top fix-plan tier, ahead of all non-top-tier gaps; do not confuse a success notification with verified persistence. | `ui/panes/EditorPane.kt:2081-2092,305-316`; Editor T01/T18. |
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
| **EX04** | **TOP TIER, security-relevant path containment** | New File, New Folder and Rename accept slash/`..` inputs without canonical containment checks. User input can specify paths outside the chosen directory; device impact not tested. | `ui/panes/ExplorerPane.kt:2146-2334`; XT36 only in isolated scratch root. |
| **EX05** | **TOP TIER, security-relevant zip-slip containment** | ZIP extraction compares canonical paths with `startsWith(dirPath)` lacking a path-separator boundary, so a sibling `out2` can pass for output root `out`. Avoid untrusted archives in real workspaces until fixed. | `ui/panes/ExplorerPane.kt:1737-1778`; XT36 only in isolated scratch root. |
| **EX06** | MEDIUM | New Folder reports success without checking `mkdirs()` result. | `ui/panes/ExplorerPane.kt:2260-2296`; XT37. |
| **EX07** | **TOP TIER, security-relevant delete scope** | Cloned-root `Remove & delete files` uses `deleteRecursively()` after only a minimum path-depth check; it does not assert canonical containment within an allowed project root. | `ui/panes/ExplorerPane.kt:1162-1211`; XT18 disposable clone only. |
| **EX08** | MEDIUM | Multi-select All can append duplicate paths; it only walks displayed root and intentionally excludes internal files even if shown. | `ui/panes/ExplorerPane.kt:833-854`; XT11/XT12. |


## Tabs

Tabs source audit: [GROUP-TABS.md](GROUP-TABS.md). All BT01-BT36 checks are pending on disposable projects; the **co-top tier remains G01/EX04/EX05/EX07 above these gaps**.

| Gap | Priority | Source-confirmed behavior / risk | Evidence and check |
|---|---|---|---|
| **TB01** | HIGH, recovery/data loss | Scratch autosave files are named by URL-encoded *full path*, but Restore compares the encoded filename to `File(tab.path).name` and deletes the backup regardless of whether a tab matched, then reports success. Requires correct full-path decode, verified restore outcome and safe backup retention. Related to S01 false-success pattern but not fixed by a generic write-result check alone. | `ui/panes/EditorPane.kt:808-821,831-904`; BT25/BT26. |
| **TB02** | HIGH, persistence clobber | Pane and shell replace the same per-project `ShellState` JSON using different subsets and default-filled fields. Last writer can erase the other owner's shell/pinned state. | `ui/panes/EditorPane.kt:749-773`; `ui/screens/ProjectShellScreen.kt:900-918`; `data/SessionStateStore.kt:47-65,214-259`; BT22. |
| **TB03** | HIGH, dirty close | Strip Close/Close Others/Close All and root-removal close bypass the only dirty warning, which belongs to Android BackHandler. If G01 disk write failed, closing can discard the sole live buffer. Related to S01's unverified persistence, but requires explicit close policy. | `ui/panes/EditorTabClose.kt:34-81`; `ui/panes/EditorPane.kt:458-478,650-688,982-1039`; BT23/BT27. |
| **TB04** | HIGH, split ID collision | After creating split views 1..4, removing #2 then adding uses `existing.size+1` and recreates #4. Same ID can refer to two strip views; verified by applying code's ID formula, not a device result. | `editor/SplitViewStore.kt:60-106`; BT31. |
| **TB05** | MEDIUM, cross-project state | Process-global split views are not pruned on entering a project whose saved split list is empty, because the restore/prune call is conditional on a nonempty list. | `editor/SplitViewStore.kt:41-47,106-122`; `ui/panes/EditorPane.kt:636-647`; BT30/BT34. |
| **TB06** | HIGH, rename identity | Explorer rename updates shell mirror and LSP but does not rekey authoritative EditorPane tabs; old path can reappear or new path open as an extra tab. | `ui/screens/ProjectShellScreen.kt:1422-1450`; `ui/panes/EditorPane.kt:157-210,690-747`; BT28. |
| **TB07** | MEDIUM, mirrored close | Explorer OPEN EDITORS X removes a shell mirror path, not EditorPane's real tab, and pane sync can resurrect it. | `ui/screens/ProjectShellScreen.kt:1558-1568`; `ui/panes/EditorPane.kt:730-747`; BT10/BT11. |
| **TB08** | MEDIUM, linked to G03 | Raw string equality can duplicate one canonical file as separate tabs and split identities through host/guest aliases. Treat as a tab-level manifestation of G03, not a separate fix. | `ui/panes/EditorPane.kt:690-729`; `editor/SplitViewStore.kt:41-122`; BT35. |
