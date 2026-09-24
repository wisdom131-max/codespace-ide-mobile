# WHERE-TO-LOOK — symptom index (paste-ready, no repo access needed)

A symptom → group → shared state → first-file index distilled from MASTER-CONNECTIONS.md. Full wiring detail lives there; this is the fast lookup for when a bug comes up. Gap IDs refer to MASTER-GAPS.md; the "first file to open" is the highest-value place to start reading.

| Symptom (as reported) | Likely gaps | Group doc | Shared state / seam | First file to open |
|---|---|---|---|---|
| Edited text visible but file on disk didn't change; save "succeeds" | G01 (top tier) | GROUP-EDITOR | EditorBufferStore vs disk write | `ui/panes/EditorPane.kt` (write path ~2081) |
| Closed a tab, edits gone, no dirty warning | TB03 → TB01 chain | GROUP-TABS | buffer + backup deletion | EditorPane close path + `editor/EditorBufferStore.kt` |
| "Restore" reported success but file unchanged | SG02 (timeline) or CH01 (chat) | GROUP-SCM / GROUP-CHAT | TimelinePanel copies / PendingChangesStore | `ui/panes/TimelinePanel.kt`, then `chat/PendingChangesStore.kt` |
| AI edit applied, then "No checkpoints found to restore" | CH01 + CH05 | GROUP-CHAT | stage() failure falls through to direct write | `chat/PendingChangesStore.kt` + `agent/AgentTools.kt` writeFile |
| Extracted zip/tar files appear outside target folder, or odd paths | EX05 / RG03 / RG04 | GROUP-EXPLORER / GROUP-RECOVERY | containment (`startsWith` dialects) | `ExplorerPane.kt` extraction ~1737; `CloudBackupManager.kt` tar ~271 |
| New file/rename landed outside the project | EX04 | GROUP-EXPLORER | same containment family | `ExplorerPane.kt` 2146-2334 |
| Delete removed more than expected (root scope) | EX07 | GROUP-EXPLORER | delete scope | ExplorerPane delete path |
| Project created offline vanished from Home; deleted project reappeared | OG01 + OG02 (compound pair) | GROUP-APPSHELL | SharedPreferences `projects` index | `ui/screens/HomeScreen.kt` sync ~166 |
| New project silently destroyed old trashed project with same name | OG03 | GROUP-APPSHELL | `.ide-trash` reuse cleanup | `project/ProjectTemplates.kt` 44-50 |
| "Could not read file: denied" when jumping to a problem | path-dialect mismatch (PB14 pattern) | GROUP-PROBLEMS / GROUP-EDITOR | host vs guest vs tab-path dialects | `terminal/ProotInstaller.kt` (hostToGuestPath) then EditorPane `onOpenFileAtLine` |
| Search Replace All reported N replaced but file unchanged | SR03 / SR04 | GROUP-SEARCH | two SEPARATE Replace All impls | `ExplorerPane.kt` and `ProjectFileSearchPanel` (both) |
| Build status wrong: cancel didn't stop it / SUCCESS with no changes / late failures missing | PR01 / PR03 | GROUP-PROBLEMS | BuildRunner process + 2000-line cap | `build/BuildRunner.kt` |
| Clone with token failed oddly, or token text visible in a notification | SG16 / SG04 | GROUP-SCM | token in bash argv / NotificationStore | `scm/` clone path + `ui/NotificationStore` |
| Terminal command ran without consent (scheduled) | IG02 | GROUP-INTEGRATION | FlowGate bypass via execOnce | `agent/AgentScheduler.kt` |
| Scheduled task never ran after app restart | IG03 | GROUP-INTEGRATION | restoreAll never called | `agent/AgentScheduler.kt` |
| Downloads panel always empty; cancel/retry do nothing | IG04 / IG05 | GROUP-INTEGRATION | orphaned download() engine | `project/DownloadCenter.kt` |
| Toolchain says a tool is missing though it works in terminal | IG08 | GROUP-INTEGRATION | exec exception → "" | `project/ToolchainManager.kt` |
| SSH: no fingerprint prompt ever appears | IG06 | GROUP-INTEGRATION | sshj+TOFU stack orphaned; live path is ssh CLI | `terminal/SshProfile.kt` |
| Live preview reachable from other devices / suspicious pages | VG01 (top tier) | GROUP-VIEWERS | ServerSocket 0.0.0.0 | `LivePreviewServer.kt` (~329, :155) |
| App crash opening a downloaded/malformed APK | VG03 / VG04 | GROUP-VIEWERS | uncapped AXML decode (2 impls) | `AxmlDecoder.kt` (delete the inline duplicate) |
| Viewer crashed on big file | VG05 | GROUP-VIEWERS | uncapped readBytes | the 4 named viewers |
| Settings reset themselves to defaults | SK01 | GROUP-SETTINGS | non-atomic settings write | `editor/settings` JsonSettingsStore |
| Settings change silently didn't apply | SK02 | GROUP-SETTINGS | facade swallowed exceptions | SettingsFacade |
| Keybinding recording does nothing | SK09 | GROUP-SETTINGS | recorder never assigned | Keybindings settings |
| Test lens button does nothing (one log line) | TG01 | GROUP-TESTING | decorative lens | `EditorPane.kt` 2671-2690 |
| Restore says "✓ Restored" but files missing | RG02 / TB03 family | GROUP-RECOVERY | wipe-first restore + swallowed errors | `BackupManager` / `CloudBackupManager.kt` |
| Crash reporter: no crash ever arrives anywhere | RG01 (verified dead) | GROUP-RECOVERY | CrashLog store, 0 records | `reportCrash` caller chain |
| Colors wrong after adding a custom grammar; theme never applies | TM01 / TM05 / TM03 | GROUP-EDITOR addendum | TmTokenizer + dual registries | `editor/textmate/TmIntegration.kt` |
| Generated image has mangled filename / key in URL | IM01-IM03 | GROUP-CHAT addendum | ImageGenService | `ui/panes/ImageGenService.kt` |
| Extension installed but commands don't appear | XG05 / XG02 | GROUP-EXTENSIONS | prose success match + scoped API | PackageManager / extensions wiring |
| Debug session shows wrong file/line on jump | DG02 / DG09 / DG11 | GROUP-DEBUGGER | guest-path conversion on one path only | the DAP adapters' live-update path |
| LSP: server requests (code actions from server) never answered | LS01 (same class as DG09) | GROUP-LSP | no "request" dispatch branch | `lsp/JsonRpcClient.kt` |
| UI stutter on idle (fans/chips blink/poll) | PG01 / PG02 / PG04 / PG05 | GROUP-PERFORMANCE | fixed-cadence recomposition/polling | CodeEditor blinkTick, LSP 2s poll |
| Credentials gone after uninstall-reinstall | RG05 (selective backup) | GROUP-RECOVERY | prefs-backup vs token store | `BackupManager` prefs list |

*Generated 2026-09-24 from the audit; if a symptom doesn't match, go to MASTER-GAPS.md §1 (shared state stores) — most cross-surface bugs live where two writers touch one store.*
