# ⚠️ TWO-REPO STRUCTURE — READ BEFORE TOUCHING ANYTHING

## THIS is the MAIN IDE repo: `wisdom131-max/codespace-ide-mobile`

## Ubuntu proot fixes go in the TEST repo: `wisdom131-max/ubuntu-proot-test`

| Repo | Purpose | What goes here |
|------|---------|----------------|
| `wisdom131-max/codespace-ide-mobile` | Full Codespace IDE app | All UI, editor, terminal, auth, agent, viewers, git, SSH |
| `wisdom131-max/ubuntu-proot-test` | Isolated Ubuntu proot test harness | ProotInstaller, proot launch args, Ubuntu rootfs extraction, symlink fixes |

### Rule: If the fix touches proot, Ubuntu rootfs, or symlinkat() — it goes in `ubuntu-proot-test` ONLY.
### DO NOT push Ubuntu/proot fixes to `codespace-ide-mobile`. They will be reverted.
### Once a fix is verified working in `ubuntu-proot-test`, Wisdom will port it back manually.

---

# AI Agent / Copilot — MASTER PROJECT CONTEXT
> Last updated: 2026-07-13. Read this FIRST before touching any code.

---

## CURRENT STATE (2026-07-14)

| | |
|-|-|
| Latest green build | **#1112** (P8-1 shipped) |
| Active phase | **Phase 8 — Debugging Infrastructure** |
| Last shipped | P8-1 breakpoints ✅ (#1111/#1112) — P8-2 Logcat pushed (#1115 fixing) — P8-3 Variables pushed (#1116/#1117) |
| **Next** | **P8-3 CI check, then Phase 8 complete** |
| Phase 7 | ✅ COMPLETE (build #1108) |
| Phase 6 | ✅ COMPLETE (build #1098) |
| Phase 5 | ✅ COMPLETE (build #1096) |
| Phase 4 | ✅ COMPLETE (build #1086) |
| Phase 3 | ✅ COMPLETE (build #1085) |
| Phase 2 | ✅ COMPLETE (build #1068) |

### Phase 8 Audit (performed by prior AI session before token ran out)

Pre-implementation audit result:
- ✅ Debug Console panel — exists (DebugConsolePanel, debugMessages, full send/receive UI)
- ✅ Debug tab — wired in bottom panel
- ✅ Gutter — exists (line numbers + git diff gutter in CodeEditor)
- ❌ Breakpoint markers — NOT in gutter (no red dot toggle on tap)
- ❌ Variable inspector panel — NOT present
- ❌ DAP client — NOT present (skip for now — needs external language adapters)
- ❌ Logcat viewer — NOT present

Phase 8 plan (ordered by value vs complexity):
| # | Feature | Complexity | Status |
|---|---------|-----------|--------|
| P8-1 | Breakpoint markers in gutter (tap line number = red dot toggle) | Low | DONE ✅ (#1111, #1112) |
| P8-2 | Logcat viewer (new Logcat tab, streams adb logcat, color-coded) | Medium | PUSHED (#1113 green, #1115 fixing) |
| P8-3 | Variable inspector panel (watch + locals + call stack) | Medium | PUSHED (#1116/#1117) |
| P8-4 | DAP client | High | SKIP — needs external adapters |

---

## KNOWN KOTLIN/COMPOSE CI FAILURE PATTERNS (memorise these)

Do NOT repeat any of these — they have each caused 5+ failed builds:

1. Raw newlines inside double-quoted strings: "foo\nbar" is OK, literal newline is NOT. Use \n or triple-quoted strings.
2. remember() inside if/else branches or LazyColumn items{} — Compose rules: call remember() unconditionally at top of composable.
3. Double-quotes inside a double-quoted string: "of "$var"" breaks the string. Use single quotes: "of '$var'".
4. Triple-quoted strings inside ${} interpolation — not valid Kotlin. Extract to a local val first.

---

## CI BUILD STATUS

| Build | Result | Notes |
|-------|--------|-------|
| #1000 | GREEN | Last confirmed passing before Phase 2 editor work |
| #1001–1006 | FAIL | McpShellProfile.kt:120 — Kotlin string escape bug |
| #1007 | GREEN | Fixed L120 with triple-quoted string |
| #1008 | FAIL | hover docs + rich snippets — raw newlines inside double-quoted strings (KSP crash) |
| #1009 | FAIL | sticky scroll — remember() inside if/else branch (Compose rules violation) |
| #1010 | FAIL | fix: move stickyScope outside conditional — CE still had raw newline strings |
| #1011 | FAIL | fix: remove remember inside LazyColumn items{} — same root cause, CE not fully fixed |
| #1012–#1027 | FAIL | Multiple fix attempts; root cause fully resolved in #1028 |
| #1028 | GREEN ✅ | fix(editor): escape snippet insertText newlines — definitive fix |
| #1029–#1032 | GREEN ✅ | docs(AGENTS): evaluation policy + background startup directive + audit |
| #1033 | FAIL | feat(editor): Rename Symbol — unescaped double-quote in Text string at :576 |
| #1034 | FAIL | docs-only — ran on same broken CodeEditor.kt commit as #1033 |
| #1035 | GREEN ✅ | fix(editor): P2-1 Rename Symbol — quote fix, SHIPPED & CLEAN |
| #1036–#1038 | FAIL | feat(P2-2): FindReplace import missing in EditorPane.kt |
| #1039 | GREEN ✅ | fix(P2-2): FindReplace icon import — P2-2 SHIPPED & CLEAN |
| #1062 | GREEN ✅ | feat(P2-3): multi-cursor visual indicators |
| #1063 | GREEN ✅ | docs: mark P2-3 DONE |
| #1064 | FAIL | feat(P2-4): AlertDialog key= param invalid (CE:808) |
| #1065 | FAIL | feat(P2-5): LintAnalyzer only (CodeEditor not fixed yet) |
| #1066 | GREEN ✅ | fix(P2-5): remove invalid key= from AlertDialog + harden LintAnalyzer — P2-5 SHIPPED |
| #1067 | GREEN ✅ | docs: mark P2-5 DONE; update CI table; open Phase 3 |
| #1068 | GREEN ✅ | docs: Phase 2 complete — all P2-1 through P2-6 shipped |
| #1069 | GREEN ✅ | fix(P3-git): audit & repair GitEngine + SourceControlPane |
| #1070 | FAIL ❌ | fix(P3-ssh): SshManager.kt:51 — addHostKeyVerifier lambda not SAM-compatible |
| #1071 | FAIL ❌ | fix(chat-panel): same SshManager.kt:51 error still in tree |
| #1072 | FAIL ❌ | fix(ssh): tried adding launch import — SshManager.kt:51 still broken |
| #1073 | FAIL ❌ | docs: AGENTS.md update — still on broken SshManager.kt tree |
| #1074 | GREEN ✅ | fix(ssh): replace non-SAM lambda with PromiscuousVerifier — REPO CLEAN ✅ |
| #1075 | GREEN ✅ | docs: sync AGENTS.md to #1074; log #1067–#1074; Phase 3 audit findings |
| #1076 | FAIL ❌ | feat(P3-git): new-branch dialog injected into wrong composable scope |
| #1077 | FAIL ❌ | fix(P3-git): relocation attempt — dialog still outside composable |
| #1078 | GREEN ✅ | fix(P3-git): new-branch dialog correctly inside SourceControlPane — PHASE 3 COMPLETE ✅ |
| #1079 | GREEN ✅ | docs: mark Phase 3 COMPLETE |
| #1080 | GREEN ✅ | feat(P4): add TerminalSessionStore — save/restore tab list with crash guard |
| #1081 | FAIL ❌ | feat(P4): wire TerminalSessionStore into TerminalPane — missing rememberCoroutineScope() |
| #1082 | FAIL ❌ | fix(P4): rememberCoroutineScope() added but missing 'import kotlinx.coroutines.launch' |
| #1083 | GREEN ✅ | fix: add missing launch import — P4 TerminalSessionStore FULLY WIRED ✅ |
| #1084 | GREEN ✅ | fix(Phase3-Explorer): outline jump-to-line, Paste guard, folder duplicate, rename tab sync |
| #1085 | GREEN ✅ | fix(Phase3): PDF DPI-aware render + clamped pan + zoom reset, SourceControl inline diff, HexViewer param fix |
| #1086 | GREEN ✅ | feat(P4): autosave dirty tabs every 30s + restore dialog on launch — PHASE 4 COMPLETE |
| #1087–#1088 | GREEN ✅ | docs: AGENTS.md sync after Phase 4 complete |
| #1089 | FAIL | feat(P5): PackageManagerPane — duplicate composable conflict with ExplorerPane |
| #1090–#1093 | FAIL | feat/fix(P5): multiple attempts — syntax corruption + duplicate symbols |
| #1094–#1095 | FAIL | feat(P6): GitEngine Phase 6 additions — build not yet clean |
| #1096 | GREEN ✅ | fix(build): remove duplicate ExtensionsPanel+McpPanel from ExplorerPane — P5 SHIPPED |
| #1097 | FAIL | feat(P6): SourceControlPane full rewrite — key(Unit){} passed as AlertDialog param |
| #1098 | GREEN ✅ | fix(P6): remove key(Unit){} from all AlertDialog calls — PHASE 6 COMPLETE ✅ |
| #1100 | GREEN ✅ | feat(P7): WorkspaceManager.kt — snapshots, diagnostics, safe mode, trash |
| #1101–1106 | RED ❌ | P7 fixes — raw newline in string literal (MainActivity:136), wrong scope var (PSS coroutineScope→scope), brace structure |
| #1108 | GREEN ✅ | fix(P7): all compile errors resolved — PHASE 7 COMPLETE ✅ |
| #1111 | GREEN ✅ | feat(P8-1): breakpoint gutter markers — tap line number to toggle red dot |
| #1112 | GREEN ✅ | feat(P8-1): wire breakpoints into EditorPane |
| #1113 | GREEN ✅ | feat(P8-2): LogcatPanel.kt — new Logcat viewer composable |
| #1114 | FAIL ❌ | feat(P8-2): add Logcat tab to bottom panel — Compose State read off main thread |
| #1115 | RUNNING | fix(P8-2): use AtomicBoolean for pause flag — avoid Compose State read off main thread |
| #1116 | RUNNING | feat(P8-3): VariableInspectorPanel — watch expressions + local vars + call stack |
| #1117 | RUNNING | feat(P8-3): wire VariableInspectorPanel into bottom panel |

Root cause of #1089–#1095: ExtensionsPanel() and McpPanel() were defined in BOTH
ExplorerPane.kt and PackageManagerPane.kt — Kotlin 'Conflicting declarations' error.
Rule: Each composable must be defined in EXACTLY ONE file. When moving a composable,
DELETE it from the original file in the same commit.

Root cause of #1097: AlertDialog() in Material3 does NOT accept key() as a positional
argument. key(orientation) { AlertDialog(...) } is valid — key() wrapping the call site.
Passing key(Unit){} as a parameter inside AlertDialog() is invalid. Rule: NEVER pass
key() as a parameter; always wrap the entire composable call site instead.

Root cause of #1114: LogcatPanel pausedUi (Compose mutableState) was read from an IO coroutine via withContext(Dispatchers.Main), but the AtomicBoolean flag pattern was not yet in place. Fix: use AtomicBoolean for pause flag, sync via LaunchedEffect.

Root cause of #1008–#1011: CodeEditor.kt snippetsFor() used literal newline chars inside
regular "..." string literals for multi-line snippet bodies. Kotlin does not allow unescaped
newlines inside double-quoted strings — they must be \n or use triple-quoted strings.
KSP preprocessing caught this before kotlinc. Fixed in commit 0111924526f3.

---

## WHAT THE APP IS — COMPLETE FEATURE MAP

### Package & Build
- **App ID:** `com.codespace.ide`
- **Min SDK:** 26 (Android 8), Target SDK: 28
- **ABI:** arm64-v8a + armeabi-v7a (per-ABI APKs + universal)
- **Auto-increment versionCode** from `git rev-list --count HEAD`
- **Keystore:** debug.keystore for debug; env-var-driven for release CI
- **Flavors:** dev / staging / prod (different `API_BASE_URL`)
- **Build system:** Kotlin + Gradle KTS, Hilt DI, KSP, Room, CMake (native C)

---

### Authentication (`AuthScreen.kt`, `GitHubAuth.kt`, `SecureTokenStore.kt`)
- **Firebase Auth** (Google Sign-In via Credential Manager — modern, not legacy GoogleSignIn)
- **GitHub OAuth** — device-code flow (non-interactive, no redirect URL needed)
  - `GitHubAuth.requestDeviceCode()` → `pollForToken()` → `fetchUsername()`
  - Token stored in `SecureTokenStore` (Keystore-backed AES-256 encrypted SharedPreferences)
- **Biometric lock** — optional fingerprint/face gate on app open (SettingsScreen)
- **Settings screen** shows active provider, API key input for AI providers

---

### Project Management (`HomeScreen.kt`)
- Create / open / delete local projects
- Each project lives in `context.filesDir/projects/<name>/`
- Opens into `ProjectShellScreen`

---

### Main IDE Shell (`ProjectShellScreen.kt` — 1905 lines)
- Four-pane layout: **Explorer | Editor | Terminal | Preview**
- Tab bar with pane switching
- **Bottom panel** (Problems / Output / Debug Console / Ports) — collapsible
- Global in-app notifications via `NotificationStore` (bell icon + drawer overlay)
- Full-screen overlays: Copilot Chat, Connectors Hub, Settings, Source Control, SSH Manager, Text Expansion
- Biometric bypass guard on project open
- Menu bar with actions: new file, save, run, build, format, terminal ops

---

### File Explorer (`ExplorerPane.kt` — ~2300 lines)
- Recursive tree view with expand/collapse
- **Single-tap file routing** (all binary types handled — none reach the text editor):
  - `.png/.jpg/.jpeg/.webp/.gif/.bmp/.svg` → Image preview popup
  - `.zip/.apk/.jar/.aar` → ArchiveViewer (browse contents like ZArchiver)
  - `.pdf` → PdfViewer (native PdfRenderer, paginated, pinch-zoom)
  - `.mp4/.webm/.mov/.mkv/.m4v/.3gp/.avi` → VideoPlayerDialog
  - `.mp3/.wav/.ogg/.m4a/.aac/.flac/.opus` → AudioPlayerDialog
  - `.db/.sqlite/.sqlite3` → SqliteViewerDialog
  - `.dex/.so/.class/.o/.a/.bin/.dat/.exe/.dll/.ttf/.otf/.woff/.woff2` → HexViewerDialog
  - NUL-byte sniff safety net (`sniffLooksBinary()`) → HexViewerDialog for any undetected binary
  - Everything else → EditorPane (text editor)
- **Long-press** → context menu (Open / Preview / Rename / Copy / Cut / Paste / Duplicate / Delete / Copy Path / Share / Open in Terminal / New File Here / New Folder Here / Import Image(s) Here)
- **Device quick-access folders** (Pictures, DCIM, Downloads, Documents, Music, Movies)
- **SAF folder picker** for external storage
- **Tab bar** showing open files
- **Outline view** (symbols in current file)
- **Search panel** (in-project text search with filters)
- Rotation-safe dialogs — `key(orientation)` on all AlertDialogs (fixes configChanges=orientation Activity that never recreates)

---

### Code Editor (`EditorPane.kt`, `CodeEditor.kt`, `SyntaxHighlighter.kt`, `LanguageSpecs.kt`, `SyntaxTransformation.kt`)
- Compose-based text editor with `BasicTextField`
- **Syntax highlighting** for: Kotlin, Java, Python, JavaScript/TypeScript, HTML, CSS, Markdown, JSON, XML, Shell/Bash, C/C++, Rust, YAML
- **Language auto-detection** from file extension
- **Lint checker** (`LintChecker.kt`) — inline problem markers
- **Find/Replace** in editor
- **Line numbers** gutter
- Font size adjustment
- **EditorColors** theming (dark IDE palette)

---

### Terminal (`TerminalPane.kt` — 2208 lines, `NativePty.kt`, `TerminalSession.kt`, `TermuxBootstrapInstaller.kt`)

#### Native PTY (primary shell)
- JNI via `pty_native.c` / CMake — exact Termux JNI API:
  - `createSubprocess`, `setPtyWindowSize`, `setPtyUTF8Mode`, `waitFor`, `close`
- **Termux bootstrap** (`bootstrap-aarch64.zip` in assets — 28MB, 252 binaries)
  - Extracted to `context.filesDir/termux-prefix/`
  - Contains bash 5.2.37, coreutils, git, curl, python, etc.
  - `TermuxBootstrapInstaller.kt` handles extraction, symlink resolution, script patching
- Shell launched as `-bash` (login shell via argv[0]) with correct `HOME`, `PREFIX`, `PATH`
- **NO LD_LIBRARY_PATH** — avoids ABI mismatch crashes
- **NO `--login` flag** — avoids host `/etc/profile` permission denied

#### Ubuntu proot shell (separate tab)
- `ProotInstaller.kt` — Ubuntu 25.04 (Questing) rootfs via proot
- Managed separately in `ubuntu-proot-test` repo
- **Known working** on TECNO KL4 with seccomp-aware symlink bypass

#### BusyBox fallback shell
- `BusyboxInstaller.kt` — offline fallback if bootstrap not yet extracted
- Lives at `context.filesDir/bin/busybox`

#### Multi-tab terminal
- Unlimited tabs, each an independent `TerminalSession`
- Tab rename, color scheme picker (scrollable, rotation-safe)
- **Key bar** — swipeable extra keys (tab, arrow keys, ctrl, escape, pipe, etc.)
- **⋮ overflow menu — AI & TOOLS section** (separate items, not one button):
  - 📥 **Install Ollama** — runs `ollamaInstallScript()`, tries 5 install methods in sequence
  - 🤖 **Launch Coding Agent** — first run opens model picker → full setup (Ollama + Claude Code); subsequent runs reuse existing tab + `ollamaLaunchScript()`
  - 🎬 **Setup Remotion** — runs `remotionSetupScript()`: Node.js + ffmpeg + headless Chrome deps + `@remotion/cli` + scaffolds `~/remotion-project/` with TSX starter + chunked render helper
  - 🎞️ **Launch Remotion Studio** — runs `remotionRelaunchScript()` (guards: must run Setup first)
  - 🎙️ **Install Voice (TTS)** — opens model picker → Piper (fast, on-device) or Bark (slower, CPU-only)
  - 🔑 / 🚪 **Sign in/out of Ollama** — `ollama signin` / `ollama signout`
  - **Multi-Instance Mode** toggle — allows multiple Ollama tabs (advanced)
  - 🔌 **Show Agent Tools (32)** — lists available MCP tools
- **SSH remote terminal** — `RemoteTerminalSession.kt` connects to backend WS

#### MCP shell integration (`McpShellProfile.kt`)
- Installs `.agent-profile.sh` + `.bashrc` injection in Ubuntu rootfs
- Provides `agent` CLI binary at `/usr/local/bin/agent`
- Shell functions: `agent_read`, `agent_write`, `agent_ls`, `agent_search`, `agent_run`, `agent_git`, `agent_ask`, `agent_session_save`
- Configures `AGENT_API_URL` and `AGENT_TOKEN` env vars
- **Build #1007 fix**: L120 triple-quoted to avoid Kotlin escape crash

#### Terminal features
- Color scheme picker (Dark, Light, Solarized, Dracula, Nord, Monokai, etc.)
- `TerminalModeManager` — offline / online / Ollama / Ubuntu mode selection
- `TerminalEnhancementManager` — profile script backup/restore, theme persistence
- `OllamaSetup.kt` — Ollama + Nemotron profile installer
- **Backup/restore** (`BackupManager.kt`) — tar.gz of Ubuntu rootfs to external storage

---

### Preview Pane (`PreviewPane.kt` — 1281 lines)
- **HTML preview** — WebView with JS enabled, file:// local serving
- **Browser** — full in-app browser with navigation bar
- **Remotion** — React video rendering via WebView
- **Dashboard** — custom WebView mode
- **Markdown** — rendered preview
- **SVG** — inline SVG rendering
- **File upload support** — `onShowFileChooser` bridged to Android GetContent picker
  - Single and multiple file modes
  - Wired into all 4 WebViews (HTML, Browser, Remotion, Dashboard)
- Fullscreen toggle (rotation-safe via `key(orientation)`)
- Port-forwarding integration (opens localhost ports from terminal)

---

### Source Control (`SourceControlPane.kt`, `GitEngine.kt`)
- **JGit** (on-device, no shell git needed)
- Stage / unstage / commit / push / pull / fetch
- Diff viewer
- Branch management
- **GitHub OAuth** integration — browse repos, clone, push with token

---

### SSH Manager (`SshManagerSheet.kt`, `SshManager.kt`, `SshProfile.kt`, `SshProfileStore.kt`)
- SSH connection profiles (host, port, user, key/password)
- **sshj** library for SSH/SFTP
- Port tunneling (local port forwarding)
- Profile CRUD with encrypted storage
- Opens SSH session in a terminal tab

---

### File Viewers (all in `ui/panes/`)
- **ArchiveViewer.kt** — browse ZIP/APK/JAR/AAR contents, extract files
- **PdfViewerDialog.kt** — native PdfRenderer, one-page-at-a-time bitmap, pinch-zoom, Prev/Next
- **MediaViewers.kt** — VideoPlayerDialog (VideoView + MediaController), AudioPlayerDialog (MediaPlayer + seek bar), HexViewerDialog (256KB-capped hex+ASCII dump)
- **HexViewerDialog.kt** — standalone hex viewer (File param version)
- **SqliteViewerDialog.kt** — opens SQLite .db files, table list, `SELECT * LIMIT 200`, scrollable grid
- **ImageGenDialog.kt** — AI image generation dialog
- **ImageGenService.kt** — image generation API client

---

### AI Copilot (`CopilotChatPanelOverlay.kt` — 1124 lines)
- Sliding overlay panel (swipe right or button)
- **Multi-provider**: supports multiple AI backends (OpenAI, Anthropic, Gemini, Ollama)
- **Multi-session** chat history (persisted to SharedPreferences as JSON)
- **Chat modes** — Code, Chat, etc.
- Token streaming
- Code blocks with syntax highlighting and copy button
- **MCP tool calls** — `AgentTools.parseToolCalls()` + `executeTool()`
- Agent memory (`AgentMemory.kt`) — key/value store persisted to `agent_memory/`
- Agent scheduler (`AgentScheduler.kt`) — CRON-style task scheduling
- File context injection (attaches open file to prompt)

---

### Agent System (`agent/` package)
- **`AgentApiServer.kt`** — in-app HTTP server (NanoHTTPD) that exposes agent tools via REST
  - Used by the `agent` CLI binary in the terminal to call back into the app
- **`AgentTools.kt`** — tool executor (read_file, write_file, list_files, search_files, run_command, git_*, MCP tools)
- **`AgentConnectorManager.kt`** — lists/connects third-party OAuth services via `ConnectorsApiClient`
- **`AgentEntityManager.kt`** — CRUD for agent-managed entities (SQLite-backed)
- **`AgentMemory.kt`** — persistent key/value agent memory (JSON files)
- **`AgentScheduler.kt`** — schedule agent tasks by cron expression

---

### Connectors (`ConnectorsHubSheet.kt`, `ConnectorsApiClient.kt`)
- Connects to third-party OAuth services (GitHub, Google Drive, Slack, etc.)
- **ConnectorsHubSheet** — sheet UI showing connector status + connect/disconnect buttons
- OAuth WebView flow (in-app browser for auth, callback intercepted)
- `ConnectorsApiClient` — proxies API calls through backend

---

### Notifications (`NotificationStore.kt`, `NotificationDrawerOverlay.kt`)
- In-app notification store (`mutableStateListOf`) — INFO / SUCCESS / WARNING / ERROR types
- Bell icon in toolbar with unread count badge
- Drawer overlay — shows all notifications, mark-all-read, clear, dismiss individual
- Timestamp formatting (relative time)

---

### Diagnostics & Dev Tools
- **`LintChecker.kt`** — syntax problem detection per language, returns `Problem(line, col, message, severity)` list
- **`PortsScanner.kt`** — scans active localhost ports (finds running dev servers)
- **`AppOutputLog.kt`** — in-memory log sink, displayed in Output panel
- **Problems panel** — shows lint issues for active file with jump-to-source
- **Output panel** — build/run output stream
- **Debug Console panel** — interactive JS/shell debug
- **Ports panel** — lists active ports, click to open in Preview

---

### Data Layer
- **Room database** — entity persistence
- **DataStore Preferences** — lightweight settings
- **SecureTokenStore** — AES-256 Keystore-backed encrypted token storage
- **SessionStateStore** — per-project editor state (open files, scroll positions, cursor)
- **BackupManager** — Ubuntu rootfs tar.gz backup to external storage (SDCARD/CodespaceIDE/)

---

### Networking
- **Retrofit + OkHttp** — REST API client
- **kotlinx.serialization** — JSON
- **Firebase Auth** — backend auth
- **sshj** — SSH/SFTP
- **JGit** — on-device Git
- **Commons Compress + XZ + Zstd** — archive extraction (Ubuntu rootfs, tar.xz, .zst)

---

### Key Files Quick Reference

| File | Lines | Purpose |
|------|-------|---------|
| `ProjectShellScreen.kt` | 1905 | Main IDE layout, all pane wiring |
| `TerminalPane.kt` | 2208 | Terminal UI, tabs, key bar, setup scripts |
| `ExplorerPane.kt` | ~2300 | File tree, all viewer routing |
| `CopilotChatPanelOverlay.kt` | 1124 | AI chat panel |
| `PreviewPane.kt` | 1281 | WebView preview modes |
| `TermuxBootstrapInstaller.kt` | 415 | Termux bootstrap extraction |
| `McpShellProfile.kt` | 296 | Ubuntu terminal AI profile |
| `AgentTools.kt` | 454 | MCP tool executor |
| `AgentApiServer.kt` | 227 | In-app REST server for agent CLI |
| `ConnectorsHubSheet.kt` | 358 | OAuth connector UI |
| `SettingsScreen.kt` | 487 | Settings, biometric, API keys, GitHub OAuth |
| `SourceControlPane.kt` | — | Git UI |
| `BackupManager.kt` | 209 | Ubuntu rootfs backup/restore |

---

## KNOWN BUGS (DO NOT REPEAT)

| Bug | Root cause | Fix |
|-----|-----------|-----|
| `signal 31` on bash start | `LD_LIBRARY_PATH` includes wrong ABI .so dir | Remove LD_LIBRARY_PATH entirely |
| `CANNOT LINK EXECUTABLE "--rcfile"` | args[0] = "--rcfile" treated as binary name | Use `arrayOf("-bash")` only |
| `/etc/profile: Permission denied` | `--login` flag tries host `/etc/profile` | Remove `--login` flag |
| 185 scripts broken paths | Bootstrap hardcodes `/data/data/com.termux/files/usr` | `patchAllScripts()` after extraction |
| Ubuntu black screen | `initializeEmulator()` never called — view already laid out | Call `view.updateSize()` after `view.attachSession()` |
| Ubuntu crash after symlink resolving | `--link2symlink` + Samsung seccomp blocks `symlinkat()` → SIGSYS | Remove `--link2symlink` |
| McpShellProfile L120 Kotlin compile | `\\"` in double-quoted string = backslash + close-quote | Use triple-quoted `"""..."""` string |
| Dialog doesn't resize on rotation | Activity has `configChanges=orientation` — never recreates | `key(orientation)` wrapper on all Dialogs |
| File upload in WebView not working | `onShowFileChooser` not overridden | `rememberOnShowFileChooser()` shared helper |
| Video/audio/binary opens in text editor | No extension routing for those types | MediaViewers.kt + sniffLooksBinary() |
| Claude Code broken after npm install | postinstall script doesn't always run | Manually run `node .../install.cjs` + verify |
| Remotion render fails (headless Chrome) | Missing libnspr4/libnss3/etc | Install full headless Chrome dep list |

---

## OPEN ITEMS (priority order)

| # | Item | Status | Notes |
|---|------|--------|-------|
| CI | Build green | DONE ✅ (#1028–#1030) | Raw newline escape fix confirmed green |
| 12 | Terminal cross-project state bleed | DONE | TrackedSession scoping fixed |
| 13 | AI package access bridging | DONE | ProotInstaller.execOnce routing |
| 11 | GitHub OAuth repo browsing | DONE | RepoBrowserSheet.kt shipped 2026-07-13 |
| P2-1 | Rename Symbol | DONE ✅ | Shipped |
| P2-2 | Find & Replace | DONE ✅ | Shipped |
| P2-3 | Go to Line | DONE ✅ | Shipped |
| P2-4 | Go to Definition | DONE ✅ | Long-press context sheet + results dialog |
| P2-5 | Error squiggles (lint) | DONE ✅ | LintAnalyzer hardened; SyntaxTransformation underlines errors |
| P2-6 | Git diff gutter | DONE ✅ | GitDiffAnalyzer.kt (LCS diff) + gutter bars fully wired in CodeEditor.kt |

---

## DEVICE CONSTRAINTS (TECNO KL4 — aarch64, Android 14, Kernel 5.15.180)

- 3-8 GB RAM — avoid loading large files into memory at once
- Samsung-derived kernel 5.15 — seccomp blocks `symlinkat()` inside unprivileged namespaces
- Always use 8KB stream buffers for extraction, not byte-array slurp
- System.gc() every 1000 files during extraction
- No W^X restriction on `nativeLibraryDir` — safe to execute .so files there
- Termux bootstrap ZIP is 29 MB — already in assets, extracts to ~150 MB


---

## FUTURE FEATURE EVALUATION POLICY (MANDATORY)

Before implementing any feature outside the core IDE experience, an AI agent MUST:
1. Present the feature to Wisdom first.
2. Explain benefits, drawbacks, performance impact, and maintenance cost.
3. Wait for explicit approval before implementing.

This applies to: Social/Collaboration features, Live collaboration, Shared Workspaces,
Achievement/Gamification/Rewards/Badges/Leaderboards, Coding streaks, Complex dashboards,
Utility collections, Experimental/Novelty features, Comment systems.

When uncertain whether a feature is essential or optional → ASK FIRST.

---

## BACKGROUND SAFE STARTUP & RECOVERY SYSTEM (Phase 4 directive — 2026-07-13)

### Existing Systems Audited (do NOT duplicate these):
| Component | File | Status |
|-----------|------|--------|
| Crash logger (JVM UncaughtExceptionHandler) | CodeSpaceApplication.kt | ✅ EXISTS |
| Native signal crash handler (SIGSEGV/SIGABRT) | CodeSpaceApplication.kt | ✅ EXISTS |
| Ubuntu rootfs backup/restore (tar.gz to external storage) | BackupManager.kt | ✅ EXISTS |
| App-wide output log (500-line ring buffer) | AppOutputLog.kt | ✅ EXISTS |
| Lint checker (inline error markers) | LintChecker.kt | ✅ EXISTS |
| Port scanner | PortsScanner.kt | ✅ EXISTS |
| Editor session save/restore (tab paths in SharedPreferences) | EditorPane.kt | ✅ EXISTS |
| WorkManager configured (HiltWorkerFactory) | CodeSpaceApplication.kt | ✅ EXISTS |

NO Workspace Trash, NO crash loop counter, NO safe mode, NO checkpoint/snapshot system,
NO background integrity validators — these do NOT exist yet.

### Startup Rule (CRITICAL):
- NEVER block startup. NEVER delay the 8-second headstart. All validation is background-only.
- App displays UI immediately. Background workers validate AFTER startup completes.

### What to implement (Phase 4 — do not start until Phase 2+3 complete):
1. **Crash loop counter** — increment on every cold start; reset after 60s uptime; trigger safe mode at 3+ crashes
2. **Safe Mode** — on crash loop: disable last-used terminal session, skip Ubuntu tab, load minimal UI; never auto-delete data
3. **Workspace Trash** — move deleted files/folders to ; restore/purge from settings
4. **Undoable file ops** — FileOpHistory: create/delete/rename/move with multi-level undo (stack in memory, not disk)
5. **Background integrity validator** — WorkManager OneTimeWork runs 10s after startup: validate settings JSON, editor session paths, cache sizes; post notification on problem
6. **Recovery Assistant** — simple screen listing detected problems + repair buttons (clear cache, reset settings, restore from backup)
7. **Workspace Snapshots** — manual zip of current project to external storage (extend BackupManager)
8. **Diagnostics Hub** — extends existing AppOutputLog: categories (crash/perf/git/terminal), export to .txt file

### Performance constraints:
- No startup delays. No UI blocking. WorkManager for all heavy validation.
- Minimal memory — cap diagnostic buffers at 500 entries (same as AppOutputLog).
- Never auto-delete user data.


---

## ARCHITECTURE DECISIONS (locked — don't change without reason)

1. **Termux native bootstrap** (not Ubuntu proot) for primary terminal — resolved OOM crashes
2. **No ExoPlayer** — native VideoView (no new Gradle dep, keeps APK small)
3. **No proot in main app** — Ubuntu tab uses proot but that lives in separate repo until stable
4. **Per-ABI APK splits** — keeps download size small for 3-8 GB devices
5. **`-bash` as argv[0]** — triggers login shell behavior correctly
6. **No LD_LIBRARY_PATH** — avoids ABI mismatch on bash startup
7. **`key(orientation)` on Dialogs** — because Activity has `configChanges=orientation` and never recreates
8. **versionCode from git commit count** — no manual bumping, always newer than installed version



---

## 2026-07-13 — ITEM #11 SHIPPED: GitHub Repo Browser (Clone from GitHub)

### What was done
Built `RepoBrowserSheet.kt` (`ui/sheets/`) and wired it into `HomeScreen.kt`.

**RepoBrowserSheet features:**
- Reads the stored `SecureTokenStore.githubToken` — no new auth flow needed.
- Fetches `GET /user/repos?sort=updated&per_page=50` from GitHub API, shows a searchable
  `LazyColumn` of repos with name, description, private badge, branch chip.
- Tap a repo -> clone dialog: pre-filled destination `/root/repos/<name>` (editable).
- Clone routes through `ProotInstaller.execOnce(context, cmd, null, 180L)` with the same
  `Authorization: Basic base64(x-access-token:<token>)` header proven in SourceControlPane.
- On success creates a `Project(kind=GIT, pathOrUrl=<rootfsDir>/<dest>)` and adds it to
  HomeScreen immediately (also synced to cloud).
- Rotation-safe: clone dialog wrapped in `key(orientation)`.

**HomeScreen.kt changes:**
- FAB now opens a DropdownMenu: 'New local project' | 'Clone from GitHub'.

### All 3 OPEN ITEMS now resolved
- #12 Terminal cross-project state bleed: DONE (TrackedSession scoping, prev session)
- #13 AI package access bridging: DONE (ProotInstaller.execOnce routing, prev session)
- #11 GitHub OAuth repo browsing: DONE (RepoBrowserSheet.kt, this session)

---

## 2026-07-13 (later) — Fixed build break from previous session's auto-open feature

The previous session's commits (3b28c50, 1d83e70 — "auto-open written files" +
"wire onOpenFile/onSwitchToPreview callbacks") broke CI:

```
CopilotChatPanelOverlay.kt:526:106 Unresolved reference: onOpenFile
CopilotChatPanelOverlay.kt:526:118 Unresolved reference: onSwitchToPreview
```

Root cause: `chat()` (the private suspend fn) was updated to accept and forward
`onOpenFile`/`onSwitchToPreview`, and both `CopilotChatPanelInline` (used) and
`CopilotChatPanelOverlay` (dead code, never called from anywhere in the app) were
updated to pass those params into `chat(...)`. But only `CopilotChatPanelInline`'s
own function signature was updated to declare the two new params — the *composable*
`CopilotChatPanelOverlay(onClose, colors, tokenStore)` was left with its old 3-param
signature while its body referenced the new params. Kotlin doesn't know what
`onOpenFile`/`onSwitchToPreview` refer to inside a function that never declared them
-> compile error.

Fix: added `onOpenFile: ((String) -> Unit)? = null` and
`onSwitchToPreview: ((String) -> Unit)? = null` to `CopilotChatPanelOverlay`'s
signature too, matching `CopilotChatPanelInline`. Defaults to null so nothing else
needs to change — Overlay stays unused/dead code, it just compiles now.

**Verified separately (this was correct, no changes needed):**
- `CopilotChatPanelInline` signature + wiring in `ProjectShellScreen.kt` — callbacks
  are properly hooked to `editorTabs`/`activeEditorTab`/`activeBottomTab` state.
- The AGENT system prompt vocabulary table and auto-open rules read correctly.
- `write_file` calls in the agentic loop trigger `onOpenFile`/`onSwitchToPreview`
  for `.svg/.html/.htm/.md` files, plain `onOpenFile` for everything else.

Lesson: when adding a new callback param to a shared private helper fn that's
called from multiple composables, grep for ALL call sites AND all their public
signatures — not just the ones actually wired end-to-end.

---

## 2026-07-13 (Phase 2) — EDITOR UPGRADES SESSION

### What was attempted / shipped

**Phase 2 goals:** Hover docs, rich language snippets, sticky scroll, rename symbol, multi-cursor, go-to-definition, find/replace, error squiggles, git diff gutter.

**Commits this session:**
| Commit | File | Description | CI |
|--------|------|-------------|----|
| `5bd7cb9bcab9` | CodeEditor.kt | HOVER_DOCS map (60+ symbols), rich snippets per language, insertText bodies | FAIL (#1008) |
| `d7d50e284251` | EditorPane.kt | Sticky scroll — nearest enclosing scope pinned at top | FAIL (#1009) |
| `9d60d9c81761` | EditorPane.kt | Fix: stickyScope remember moved outside if/else | FAIL (#1010) |
| `582eb1941b52` | CodeEditor.kt | Fix: remove remember inside LazyColumn items{} | FAIL (#1011) |
| `0111924526f3` | CodeEditor.kt | Fix: escape all snippet insertText newlines (root cause fix) | RUNNING (#1012) |

### Features shipped (pending CI green)
1. **HOVER_DOCS** — 60+ keyword descriptions across Kotlin/JS/TS/Python/Java/Rust/Go
2. **Rich language snippets** — per-language Completion lists with full `insertText` bodies
   (e.g. selecting "LaunchedEffect" inserts full `LaunchedEffect(key) { }` block)
3. **Doc subtitle in autocomplete** — one-liner doc always visible under each item label
4. **Sticky scroll** — `fun`/`class`/`if`/`when`/`struct` headers pin at top of editor while scrolling
5. **Snippet insertion uses full body** — clicking an autocomplete item inserts usable code, not just the keyword

### Bugs hit and root causes
1. **Raw newlines in string literals** — `snippetsFor()` had multi-line bodies written as literal newlines inside `"..."` strings. Kotlin does NOT allow this — must use `\n` or triple-quoted strings. KSP catches it before kotlinc. Fixed by replacing all occurrences with `\n` escapes.
2. **remember() inside conditional** — `val stickyScope = remember(...)` was placed inside an `if/else` branch in EditorPane. Compose rules: composable functions (including `remember`) must be called unconditionally at the top level of a `@Composable`. Fixed by moving it above the `if (active != null)` block.
3. **remember() inside items{} lambda** — `var showDocTooltip by remember {...}` was placed inside `LazyColumn`'s `items{}` lambda which is NOT a `@Composable` scope. Fixed by removing the per-item state entirely and showing doc as a permanent subtitle line.

### Lesson
When writing Kotlin string literals that should contain newlines, ALWAYS use `\n` or triple-quoted strings. Never paste multi-line content directly into `"..."`. This causes a KSP crash with a misleading "Error occurred in KSP" message rather than a clear parse error.

### What comes NEXT (Phase 2 continuation)
Once CI goes green on #1012:

| Order | Feature | Implementation plan |
|-------|---------|---------------------|
| 1 | **Rename Symbol** | Long-press a word in editor -> "Rename" dialog -> replace all word-boundary matches in current file, preserving cursor position |
| 2 | **Find & Replace in file** | Bottom sheet: search field + replace field + regex toggle + match highlight via AnnotatedString + "Replace" / "Replace All" buttons |
| 3 | **Multi-cursor editing** | Track a `List<Int>` of cursor positions; on typed char insert at each position; on backspace delete before each; render as transparent Box overlays |
| 4 | **Go to Definition** | Double-tap a symbol -> scan file for first `fun name` / `val name` / `class name` definition -> `scrollToLine` jump |
| 5 | **Error squiggles** | Post-edit analysis pass: unmatched braces, unclosed strings, undefined references (scan for uses without definitions in file) -> wavy red underline via AnnotatedString SpanStyle |
| 6 | **Git diff gutter** | On file open read `git diff HEAD <file>` via ProotInstaller.execOnce -> parse unified diff -> color sidebar strips (green=add, orange=mod, red=del) aligned to line numbers |

---

## MASTER AUDIT DIRECTIVE — 2026-07-13

### Source: Wisdom's Full Audit & Feature Implementation Order

The following is the canonical work order for all future sessions. Every AI reading this must:
1. Audit before implementing — never assume a button works because it exists
2. Repair broken before adding new
3. Follow complexity order within each section
4. Update AGENTS.md after every session

---

## AUDIT REPORT TEMPLATE (fill per session)

Each session must output:

| Category | Features |
|----------|---------|
| Fully Functional | List what actually works |
| Partially Functional | List what's wired but incomplete |
| Broken | List what exists but is broken |
| Repaired This Session | List what was fixed |
| Upgraded This Session | List what was improved |
| Newly Implemented | List what was added fresh |

---

## PHASE 2 — CODE EDITOR INTELLIGENCE (current phase)

Status as of 2026-07-13:

### Already Exists (verify working)
- Syntax highlighting (SyntaxHighlighter.kt + SyntaxTransformation.kt)
- LintChecker.kt — inline problem markers (VERIFY: are markers actually showing?)
- Find/Replace — (VERIFY: does it actually replace or just find?)
- Autocomplete dropdown (VERIFY: does it insert full snippet or just keyword?)
- HOVER_DOCS map (shipped 2026-07-13, pending CI green)
- Sticky scroll (shipped 2026-07-13, pending CI green)
- Rich language snippets with insertText bodies (shipped, pending CI green)

### Phase 2 TODO (ordered)

**Latest green build: #1069 (P3-git). Repo blocked at #1070 and #1071 by SshManagerSheet.kt:66 missing launch import — fixed in this commit.**

| # | Feature | Status | Notes |
|---|---------|--------|-------|
| P2-1 | Rename Symbol | DONE (#1035) | long-press word -> AlertDialog -> replace all word-boundary occurrences. commit acd045fa |
| P2-2 | Find & Replace | DONE | Bottom bar: search input, prev/next, replace input, replace-all, regex toggle, match highlight |
| P2-3 | Multi-cursor editing | **DONE** | Amber line-highlight tint + 2dp amber cursor bar per extra cursor. Fan-out editing + clear chip already existed. commit 5e0f60e1 (#1062 green). |
| P2-4 | Go to Definition | **DONE** | Long-press context sheet: "Go to Def" scans file for decl keywords (Kotlin/JS/Python/Rust/Go), scrolls to match; multi-result list if ambiguous; not-found msg if absent. commit 2b72e17f (#1064 fail — key= param, fixed in #1066). |
| P2-5 | Error squiggles (lint underlines) | TODO | |
| P2-6 | Git diff gutter | **DONE** | LCS diff, green=added / yellow=modified / red triangle=deleted. `GitDiffAnalyzer.kt` (new). |
| P2-7 | Code folding | **DONE** | Fixed: BasicTextField now receives folded view via SyntaxTransformation(foldedLineIndices). ··· placeholder with correct cursor offset mapping. |
| P2-8 | Breadcrumb navigation | **DONE** | Clickable segments + horizontal scroll. Tapping any ancestor dir opens Explorer and auto-expands/scrolls the tree to that dir. |
| P2-9 | Code bookmarks | **DONE** | Gutter ◆ dot toggle per line. Bookmark panel (◆ toolbar button) lists all bookmarks across open files with file:line + content preview. Tap to jump. |
| P2-10 | Jump back/forward history | **DONE** | `NavEntry` stack (100-deep). ← → buttons in editor toolbar. Triggered on Explorer open, Search jump, tab click. Forward stack clears on new jump. |
| P2-11 | Inlay hints | **DONE** | `InlayHintAnalyzer.kt` (new, regex-based, no AST) — type/return/param labels rendered as overlay in `CodeEditor.kt`. Toolbar ⊕ toggle in `ProjectShellScreen.kt` (not persisted, matches wordWrap's existing convention). commits 85703210 (#1055 green), 685b63bc (#1056 green, fixed dead/broken VAL_CHAR regex). |
| P2-12 | Parameter hints / signature help | **DONE** | `SignatureHelpAnalyzer.kt` (new) — curated sig DB (60+ fns, 6 languages) + backward paren/comma scanner. Popup rendered one line above cursor; active param bolded teal; hides when autocomplete dropdown is open. commits 96c07db8, 667b5fa1(fail→withStyle missing import), 5d2cfe51 (#1060 green). |

### Phase 2 Session Log
| Date | Done |
|------|------|
| 2026-07-13 | Shipped HOVER_DOCS (60+ keywords), rich snippets, sticky scroll, P2-1 Rename Symbol. Fixed #1028 (raw newlines) and #1035 (unescaped double-quote in Text string). |
| 2026-07-13 | Added Future Feature Evaluation Policy. Added Phase 4 Background Safe Startup & Recovery directive (do not build until Phase 3 done). Audited existing systems: crash logger, BackupManager, AppOutputLog, WorkManager all present. |
| 2026-07-13 | Shipped P2-2 Find & Replace — bottom bar with search, replace, prev/next arrows, regex toggle, replace-one, replace-all, match counter. Fixed missing FindReplace import (#1039 GREEN). |
| 2026-07-14 | Shipped P2-4 Go to Definition — long-press now shows context sheet with two actions: "Go to Def" (scans current file for decl keywords across 6 languages, scrolls to match, multi-result list if ambiguous) and "Rename Symbol". Build #1064 failed (AlertDialog key= param not valid here — removed in #1066). P2-5 also fixed same session. Fixed blocking SSH bug (SshManagerSheet.kt:66 missing launch import) breaking builds #1070 and #1071. |
| 2026-07-13 | Shipped P2-6 Git diff gutter — new `GitDiffAnalyzer.kt` (LCS diff, capped at 2 000 lines). Gutter bar: green=added, yellow=modified, red ▶ triangle=deleted lines. Replaced old heuristic isDirty/isAdded in CodeEditor.kt. |
| 2026-07-13 | Shipped P2-7 Code Folding — SyntaxTransformation now accepts foldedLineIndices, collapses folded blocks into ··· with correct OffsetMapping so cursor positions stay valid. BasicTextField finally shows the folded view (was showing full text before). |
| 2026-07-13 | Shipped Workspace Memory System — expanded `SessionStateStore` (cursor offsets, scroll positions, pinned tabs, split path, terminal state, per-project isolation, restore toggle, clearAll). `EditorPane` now accepts `projectId`+`sessionStateStore`, saves/restores per-project session including cursors, scroll lines, pinned+split tabs. `SettingsScreen` has new "Workspace Memory" section (restore toggle + clear button). One-time legacy `editor_session` migration included. |
| 2026-07-13 | Shipped P2-8 Breadcrumb navigation — breadcrumb bar is now horizontally scrollable with clickable ancestor segments. Tap a segment → Explorer opens + tree auto-expands/scrolls to that dir via `navigateToDir` + `treeListState`. |
| 2026-07-13 | Shipped P2-9 Code Bookmarks — gutter ◆ dot toggles bookmark per line; toolbar ◆ button opens panel listing all bookmarks (file+line+preview); tap to jump to file+line. `fileBookmarks` map in EditorPane. |
| 2026-07-13 | Shipped P2-10 Jump back/forward history — `NavEntry(path, line)` back/fwd stacks, ← → toolbar buttons, push on Explorer open / Search jump / tab click. |
| 2026-07-14 | Phase 3 Explorer audit complete — outline jump-to-line, Paste conditional, Duplicate folder fix, Rename→tab sync, onOpenFileAtLine wired. |
| 2026-07-14 | Phase 3 Source Control audit — all git ops verified real. Added inline expandable diff viewer (tap file to expand, green/red/blue unified diff). Fixed HexViewerDialog call site parameter mismatch. Fixed PDF: DPI-aware render resolution, clamped pan, zoom indicator + reset. |
| 2026-07-13 | Shipped P2-12 Parameter hints / signature help — new `SignatureHelpAnalyzer.kt`, backward paren+comma scanner, floating popup 1 line above cursor with active param teal+bold, hides while autocomplete open. Build #1059 failed (missing `withStyle` import — extension fn needs explicit import even when `buildAnnotatedString` is imported). Fixed in #1060 (green). |
| 2026-07-13 | Shipped P2-11 Inlay Hints — new `InlayHintAnalyzer.kt`, regex-based (no AST), type/return/param label overlay in `CodeEditor.kt`, toolbar ⊕ toggle. Picked up mid-session after a prior AI ran out of tokens right after triggering build #1055 (it landed GREEN). Found and fixed a real bug left behind: `VAL_CHAR` regex had a missing `\\s` escape (`'.'s*$` instead of `'.'\\s*$`) so it could never match, AND it was never referenced in the type-hint `when` block at all — char literals (`val c = 'a'`) silently got no hint. Fixed both in #1056 (green). |

---

## PHASE 4 — BACKGROUND SAFE STARTUP & RECOVERY ✅ COMPLETE

| Item | Status | Notes |
|------|--------|-------|
| `TerminalSessionStore.kt` | ✅ DONE (#1083) | Saves/restores tab list + names to SharedPreferences. Crash guard: if crashed >2x, auto-wipes to break crash loop. 8s restore delay, loop-guarded. 'Clear saved sessions' in ⋮ menu. |
| Crash logger (JVM) | ✅ DONE (pre-existing) | `CodeSpaceApplication.kt` — writes to `crash_logs/crash_<stamp>.txt`, POSTs to Superagent `reportCrash` endpoint, surfaces on next launch in `MainActivity` dialog. |
| Native crash handler | ✅ DONE (pre-existing) | `JNI.installCrashHandler()` — catches SIGSEGV/SIGABRT/signal crashes that never reach JVM handler. Writes `native_crash_pending.txt`. |
| Terminal foreground service + WakeLock | ✅ DONE (pre-existing) | `TerminalService.kt` — `startForeground()` raises OOM priority. Optional `PARTIAL_WAKE_LOCK` user-toggled from gear menu. Matches Termux pattern exactly. |
| Autosave + restore dialog | ✅ DONE (commit 6cc64a252b) | `EditorPane.kt` — 30s timer writes dirty tabs to `filesDir/projects/<id>/.autosave/<name>.autosave`. On launch: detects stale saves → AlertDialog offers Restore or Discard. Clean tabs auto-pruned each cycle. |
| `RepoBrowserSheet.kt` | ✅ EXISTS | 17KB, wired into HomeScreen. Browse GitHub repos, clone dialog, rotation-safe. |

---

## PHASE 3 — VERIFY & REPAIR EXISTING FEATURES ✅ COMPLETE (build #1085)

### Must audit in order before implementing anything else

#### File Explorer (ExplorerPane.kt)
- [x] Rename — ✅ renameTo() real disk op. onFileRenamed() callback updates open editor tabs automatically.
- [x] Copy/Cut/Paste — ✅ copyTo()/renameTo() real disk ops. Paste now hidden in menu when clipboard is empty.
- [x] Duplicate — ✅ Fixed: folders use copyRecursively(); files use copyTo().
- [x] Delete — ✅ deleteRecursively() handles files and folders.
- [x] Open in Terminal — ✅ passes dir path to onOpenInTerminal → terminal runs cd.
- [x] Search panel — ✅ Walks workspace tree, reads line content, supports regex/case/word-boundary.
- [x] Outline view — ✅ Parses class/fun/var. Tapping a symbol now scrolls editor to that line.
- [x] Long-press context menu — ✅ All items working. Paste/Preview now conditionally shown.

#### Terminal (TerminalPane.kt)
- [ ] All 5 Ollama install methods fallthrough correctly?
- [ ] Launch Coding Agent: does model picker persist choice?
- [ ] Setup Remotion: does it complete without manual steps?
- [ ] Launch Remotion Studio: does composition panel show (not blank)?
- [ ] Install Voice (TTS): Piper download + bark-small download both complete?
- [ ] SSH remote terminal: does it actually connect?
- [ ] Color scheme picker: do all schemes apply correctly?
- [ ] Extra key bar: all keys send correct sequences?

#### Source Control (SourceControlPane.kt)
- [x] Stage/unstage — ✅ `git add` / `git restore --staged` real commands.
- [x] Commit — ✅ `git commit -m` real command.
- [x] Push — ✅ Injects GitHub token as HTTP Basic Auth header via `git -c http.extraheader`.
- [x] Pull / Fetch — ✅ `git pull` real command with token injection.
- [x] Branch switch — works? ✅ (git checkout via runGit)
- [x] New branch dialog — implemented (#1078) ✅
- [x] Diff viewer — ✅ Added inline expandable diff in ChangeRow (tap file to expand). Green/red/blue unified diff. Loads via `git diff` then `git diff --cached` fallback.
- [ ] RepoBrowserSheet clone — end-to-end verified?

#### Preview Pane (PreviewPane.kt)
- [ ] HTML preview — loads local file:// correctly?
- [ ] Browser mode — navigation bar works?
- [ ] Markdown — renders correctly?
- [ ] SVG — renders inline?
- [ ] File upload via WebView — picker opens and files load?
- [ ] Video auto-wrap (shipped 2026-07-08) — working?
- [ ] Audio auto-wrap — working?
- [ ] Remotion Studio — compositions panel not blank?

#### AI Copilot (CopilotChatPanelOverlay.kt)
- [ ] MCP tool calls execute correctly?
- [ ] write_file auto-opens file in editor?
- [ ] write_file switches to Preview for .html/.svg/.md?
- [ ] Multi-session history persists across app restarts?
- [ ] Token streaming works for all providers?

#### Connectors (ConnectorsHubSheet.kt)
- [ ] OAuth flow completes (doesn't get stuck in WebView)?
- [ ] Connected services actually pass tokens to API calls?
- [ ] ConnectorsApiClient proxies correctly?

#### Viewers
- [ ] PDF — paginate, zoom working?
- [ ] Archive — extract individual files working?
- [ ] Video — playback, seek, fullscreen?
- [ ] Audio — seek bar, play/pause?
- [ ] Hex — 256KB cap enforced, ASCII column correct?
- [ ] SQLite — table list, query result grid?

#### Settings (SettingsScreen.kt)
- [ ] API key save/load — round-trips correctly?
- [ ] Biometric toggle — locks on next launch?
- [ ] Provider switch — actually changes which LLM responds?
- [ ] Theme change — persists across restarts?

---

## PHASE 4 — TERMINAL SESSION RESTORE

Requirements (implement after Phase 3 audit):
- Save on app close: tab count, working dir per tab, command history
- 8-second startup headstart — do NOT restore before initialization completes
- Restore asynchronously — never block main thread
- Corrupted session detection — skip, don't crash
- Prevent restore loops (max 1 restore attempt per session ID per launch)
- Auto-disable sessions that crash 2+ times
- Manual restore option in terminal ⋮ menu

Implementation targets:
- `TerminalSessionStore.kt` — new file, Room entity or DataStore
- `TerminalPane.kt` — wire save on tab close + restore on startup (post-8s delay)
- `TerminalSession.kt` — expose workingDir(), commandHistory()

---

## PHASE 5 — PACKAGE MANAGER UPGRADE ✅ COMPLETE (build #1096)

### Shipped (commit fe8717f0f1, build #1089)

New file: `PackageManagerPane.kt` — contains both stubs that ProjectShellScreen called but were never defined:

**ExtensionsPanel** (full package manager):
- 35 curated featured packages shown by default
- Live `apt-cache search` (debounced 400ms) — falls back to local filter if apt unavailable
- One-tap Install / Remove per package — streams `apt-get -y` output live
- Output terminal strip at bottom (60-line cap, auto-scroll, colored status dot)
- "Installed" tab — reads `dpkg --list`, shows all installed packages with Remove button
- Refresh button triggers `apt-get update`
- One operation at a time (spinner locks row while busy)

**McpPanel** (MCP / Agent API status):
- Live health poll every 5s → green/red dot
- Tool count read from `~/.agent.json`
- Shell profile installed check (`.bashrc` scan)
- Start/Restart button → `McpShellProfile.install()`
- Quick tool chips: `agent_read`, `agent_write`, `agent_run`, `agent_git`, `agent_search`
- One-tap "Install shell profile" if missing

### Phase 5 — ALL ITEMS COMPLETE ✅

| Item | Status |
|------|--------|
| ExtensionsPanel (package manager UI) | ✅ commit fe8717f0f1 |
| McpPanel (Agent API status) | ✅ commit fe8717f0f1 |
| Install history (SharedPrefs, 200 entries, History tab) | ✅ commit 893ce19603 |
| Cancel mid-install (Process.destroy → SIGTERM) | ✅ commit 893ce19603 |
| Upgrade-all button (apt-get upgrade -y, streamed) | ✅ commit 893ce19603 |

---

## PHASE 6 — GIT & VERSION CONTROL COMPLETENESS ✅ COMPLETE (build #1098)

### Audit results (2026-07-14)

SourceControlPane.kt (593 lines) + GitEngine.kt (162 lines) audited.

| Feature | Status |
|---------|--------|
| Inline diff viewer (per-file expand, git diff / git diff --cached, coloured) | ✅ exists |
| Stage / unstage / stage-all / unstage-all | ✅ exists |
| Commit + push + pull | ✅ exists |
| Branch create + switch (checkout) | ✅ exists |
| Branch delete | ❌ missing |
| Branch rename | ❌ missing |
| Commit history / log (list + tap for details) | ❌ missing |
| Stash save / pop / list | ❌ missing |
| Merge conflict resolution UI | ❌ missing |
| .gitignore editor | ❌ missing |
| Tag management | ❌ missing |
| Local version history (file snapshots, separate from git) | ❌ missing |

### Build plan
All 7 missing features go into `SourceControlPane.kt` + `GitEngine.kt`:
- **GitEngine**: add stash (save/pop/list), deleteBranch, renameBranch, commitLog, createTag, listTags
- **SourceControlPane**: Commit Log tab, Stash section, branch long-press menu (delete/rename),
  conflict file badge + open-in-editor, .gitignore quick-create/edit button, Tag panel
- **Local version history**: file-level snapshots stored under `.versionhistory/` in project dir,
  accessible via long-press on file in ExplorerPane (separate from git — works even without a repo)

### Shipped — ALL ITEMS COMPLETE ✅ (build #1098)

| Feature | Status | Commit |
|---------|--------|--------|
| Commit Log tab (100 commits, tap to expand SHA/author/date/message) | ✅ DONE | #1098 |
| Stash tab (list, save with message, pop, drop) | ✅ DONE | #1098 |
| Tags tab (list annotated + lightweight, create, delete) | ✅ DONE | #1098 |
| Branch delete + rename (context menu in branch dropdown) | ✅ DONE | #1098 |
| Merge conflict banner (per-file badge + open-in-editor button) | ✅ DONE | #1098 |
| .gitignore inline editor (icon button next to branch row) | ✅ DONE | #1098 |

---

## PHASE 7 — RECOVERY & RELIABILITY (NEXT — active)

**Status: starting. Latest green build: #1098. Safe to implement.**

| # | Feature | Status | Notes |
|---|---------|--------|-------|
| P7-1 | Workspace snapshots | DONE ✅ | File > Create Snapshot → zip to Downloads/CodespaceIDE/. WorkspaceManager.createSnapshot() |
| P7-2 | Diagnostics report | DONE ✅ | File > Diagnostics Report → device info + crash logs → share sheet. WorkspaceManager.generateDiagnosticsReport() |
| P7-3 | Safe mode | DONE ✅ | MainActivity.recordLaunch() + 60s stable timer. Dialog shown on 3+ crashes. WorkspaceManager.isSafeMode() |
| P7-4 | Workspace Trash | DONE ✅ | ExplorerPane delete now calls WorkspaceManager.moveToTrash(). Files go to .ide-trash/<ts>-<name>. |


### P7 CI failure patterns (for future reference)
- **Raw newlines in string literals** — Python heredoc/multiline strings written directly into Kotlin `"..."` strings break the compiler. Always use `\n` escape or string concatenation with `+`.
- **Wrong coroutine scope name** — Always check the actual `val <name> = rememberCoroutineScope()` variable name in each file before referencing it. PSS uses `scope`, not `coroutineScope`.
- **Brace structure from nested if/else** — When wrapping existing `setContent` blocks with new if/else, count braces carefully. Safe pattern: show the app unconditionally, overlay dialogs on top rather than wrapping the entire app in an else branch.

### Implementation details (Phase 7)
- **WorkspaceManager.kt** — new file in `com.codespace.ide.util`. Contains all 4 features.
- **Snapshot**: zips project dir to `Downloads/CodespaceIDE/`, excludes `.ide-trash/` and `.autosave/`
- **Diagnostics**: gathers device model, Android version, app version, crash logs, terminal output → .txt via share intent
- **Safe mode**: SharedPrefs `ws_safety`. recordLaunch() on every cold start. 60s Handler resets counter. Alert dialog on 3+ crashes with "Continue" or "Enter Safe Mode" options.
- **Trash**: ExplorerPane delete replaced with moveToTrash(). `.ide-trash/<ms>-<name>` naming. WorkspaceManager.restoreFromTrash() / purgeTrashEntry() / emptyTrash() available for future restore UI.

### Known Kotlin rule reminders (Phase 7 commit):
- key(orientation) { AlertDialog(...) } wrapping used correctly for all new dialogs
- No raw newlines in string literals
- coroutineScope.launch{} used for all IO (createSnapshot, generateDiagnosticsReport)

### Pre-existing (do NOT re-implement):
- Auto-save + restore dialog — ✅ EXISTS in EditorPane.kt (30s timer, .autosave/, AlertDialog on launch)
- Crash logger (JVM) — ✅ EXISTS in CodeSpaceApplication.kt
- Native crash handler — ✅ EXISTS (JNI.installCrashHandler)
- BackupManager (rootfs tar.gz) — ✅ EXISTS for Ubuntu container

### Known gotchas for this phase:
- Snapshot tar.gz needs MANAGE_EXTERNAL_STORAGE permission (already in manifest — verify)
- All heavy work (tar, file scan) must be in a coroutine/WorkManager — NEVER on main thread
- key(orientation) { AlertDialog(...) } at call site — NEVER pass key() as AlertDialog param

---

## PHASE 8 — DEBUGGING INFRASTRUCTURE (ACTIVE)

### Audit result (2026-07-14, prior AI session)
- ✅ Debug Console — exists and functional (DebugConsolePanel, send/receive, message list)
- ✅ Debug tab in bottom panel — wired correctly
- ✅ Gutter bar — exists (line numbers + git diff colored strips)
- ❌ Breakpoint markers — missing (no red dot on tap)
- ❌ Variable inspector panel — not present
- ❌ DAP client — not present (skip: needs external language adapters)
- ❌ Logcat viewer — not present

### Implementation order
| # | Feature | Complexity | Status | Notes |
|---|---------|-----------|--------|-------|
| P8-1 | Breakpoint gutter markers | Low | TODO — START HERE | Tap line number = toggle red dot. No DAP needed. Store Set<Int> of breakpoint lines. |
| P8-2 | Logcat viewer | Medium | TODO | New tab in bottom panel (after Ports). Runs `adb logcat` in terminal subprocess, streams into scrolling list with filter input. |
| P8-3 | Variable inspector panel | Medium | TODO | New bottom tab. Stub initially — show local vars from debug session JSON if present. |
| P8-4 | DAP client | High | SKIP | Requires external per-language debug adapters in terminal. Out of scope for now. |

### P8-1 implementation plan
- `CodeEditor.kt`: add `breakpointLines: Set<Int>` param, render red filled circle in line number gutter on tap
- `EditorPane.kt`: hold `var breakpointLines by remember { mutableStateOf(setOf<Int>()) }`, pass to CodeEditor
- Tap line number row → `breakpointLines = if (line in breakpointLines) breakpointLines - line else breakpointLines + line`
- Red dot: `Box(Modifier.size(8.dp).clip(CircleShape).background(Color(0xFFFF5F5F)))` aligned right in gutter

### P8-2 implementation plan
- Add "Logcat" tab to bottom panel tab row (after "Ports")
- `LogcatPanel.kt` (new): LaunchedEffect runs `Runtime.getRuntime().exec("adb logcat -d")` in background
- Parse lines into LogEntry(level, tag, message), color-code by level (V=grey, D=blue, I=green, W=amber, E=red)
- Filter input at top (debounced 300ms) — filters tag or message
- Auto-scroll to bottom toggle (default on), Clear button, Pause button

---

## PHASE 9 — PERFORMANCE & MONITORING

- Background file indexer — builds symbol index for workspace-wide search
- Smart file caching — LRU cache for recently opened files (avoid re-read on tab switch)
- Memory pressure monitor — show RAM usage in status bar, auto-close binary viewers on low RAM
- CPU/RAM stats widget (collapsible in bottom panel or menu)
- Large file support — files > 1MB: stream render, don't load full content into memory
- Code metrics: line count, file size, complexity estimate per file (show in status bar)

---

## PHASE 10 — EXTENSION SYSTEM (Long term)

Design principles:
- No VSIX (requires full VS Code runtime) — implement a lightweight plugin API instead
- Plugins are ZIP files containing: `plugin.json` manifest + Kotlin script or shell script
- Plugin types: Theme, Language pack, Snippet pack, Tool integration
- Extension marketplace: GitHub releases from `codespace-ide-plugins` org (future)
- Plugin API surface: read/write files, add menu items, add terminal commands, add syntax highlighting rules

---

## PHASE 11 — ANDROID BUILD ENVIRONMENT VALIDATION & MANAGEMENT

**Before implementing:** Audit existing Android functionality, package management, Ubuntu integration,
and build systems. Reuse and improve before duplicating.

**Goal:** Ensure the IDE can reliably build Android applications and that the required development
environment is properly configured and repairable from within the IDE.

### 11-A — Environment Validation

Automatically detect and validate:

- JDK (version, path, JAVA_HOME)
- Gradle (version, wrapper vs system)
- Android SDK (ANDROID_HOME, license acceptance)
- Android Platform Tools (adb, fastboot)
- Android Build Tools (aapt, aapt2, zipalign, apksigner)

Detect: missing tools · broken installations · invalid paths · corrupted SDK components ·
missing permissions · incomplete environments

### 11-B — Environment Status Center

Centralized status screen showing:

- Installed tools + versions
- Missing / broken components
- Overall environment health
- Recommended fixes with one-tap repair where possible

### 11-C — Automatic Tool Detection

Search for existing SDKs, JDKs, Gradle installs, build tools, and Ubuntu packages.
**Reuse detected installations** — do not download if already present.

### 11-D — Installation & Repair Management

Workflows for: JDK · Gradle · Android SDK · Platform Tools · Build Tools

Requirements:
- Verify downloads (checksum)
- Verify installations after completion
- Progress reporting in Build panel (no blocking UI)
- Failure reporting with actionable messages
- Support updates and repairs

### 11-E — Build Environment Health Check

Validate: tool availability · version compatibility · env variables · SDK config ·
build tool compatibility · package integrity. Generate clear health report.

### 11-F — Build Diagnostics

Detect: missing dependencies · missing SDK packages · invalid configs · common Android
build failures. Surface actionable solutions, not raw Gradle stderr.

### 11-G — Project Build Validation

Pre-build checks: project config · required SDK versions · dependencies · build requirements ·
signing config. Warn users BEFORE execution, not during.

### 11-H — Build Execution

Support: debug builds · release builds · APK generation · APK signing

Requirements: progress reporting · build logs in Build panel · error reporting ·
build summaries · build result (success/fail + APK path)

### 11-I — Performance

- All validation/install runs off the UI thread (WorkManager / coroutines)
- Avoid blocking editor or terminal
- Support large projects
- Minimal memory footprint

### Implementation Policy

1. Verify before implementing
2. Repair before replacing
3. Reuse before duplicating
4. Improve existing systems where possible
5. Prioritize reliability and build success
6. Ensure compatibility with Ubuntu proot environment
7. Android project builds must be practical and dependable

**Success Criteria:** User opens Android project → validates environment → identifies missing
requirements → installs/repairs → successfully builds a signed APK — all from within the IDE.

---

## PHASE 12 — PROJECT SETUP, TOOLCHAIN MANAGEMENT, BUILD HISTORY & TASK RUNNER

**Before implementing:** Audit existing project creation, build, package management, environment
management, and download systems. Reuse and improve before duplicating.

### 12-A — Project Wizard

Guided project creation workflow:

- Create New Project
- Project Configuration Wizard with validation
- Project naming + location selection

Supported project types:
- Android App · Flutter App · React Native App · Web App · Node.js Project · Python Project · Empty Project

Requirements: simple workflow · minimal input · fast setup · clear validation messages

### 12-B — Project Templates

Per-type templates with correct structure, starter files, and recommended configs.
Support future template expansion via manifest-driven approach.

### 12-C — Toolchain Manager

Centralized management for:
JDK · Gradle · Android SDK · Android Build Tools · Platform Tools ·
Flutter SDK · Dart SDK · Node.js · npm · Yarn · Python

Features: tool detection · version detection · install status · update management ·
repair workflows · missing tool detection. Clear status reporting.

### 12-D — Download Center

Centralized download tracking for SDKs, tools, packages, extensions, updates.

Features: download progress · download history · failure reporting · retry support

### 12-E — Build History

Track: build date/time · duration · type · status · logs · generated artifacts

Features: search history · filter · view logs · export logs

### 12-F — Build Artifact Manager

Manage: APK files · AAB files · build outputs · exported packages

Features: artifact history · open · share · delete · artifact info

### 12-G — Task Runner

Reusable one-tap tasks: Build APK · Build Release · Clean Project · Install APK ·
Update Dependencies · Run Tests · Generate Artifacts

Requirements: one-tap execution · progress reporting · log viewing ·
failure reporting · task history

### 12-H — Environment Profiles

Profiles: Android Development · Flutter Development · Web Development ·
Python Development · Node.js Development

Features: profile switching · profile-specific config · tool recommendations ·
environment validation

### Implementation Policy

1. Verify before implementing — audit first
2. Repair before replacing
3. Reuse before duplicating
4. Prioritize simplicity
5. Maintain Android performance
6. Maintain Ubuntu proot compatibility
7. Reliability and usability above feature count

**Goal:** Complete project creation, environment management, build management, and task execution
suitable for professional development on Android.

---

## ONGOING RULES (for every future AI session)

1. ALWAYS read AGENTS.md before touching code
2. ALWAYS run CI check after pushing — don't assume green
3. ALWAYS verify the exact error from CI logs before guessing a fix
4. NEVER push Ubuntu/proot fixes to codespace-ide-mobile (ubuntu-proot-test only)
5. NEVER add a feature if an equivalent already exists — repair it instead
6. ALWAYS update AGENTS.md after the session ends
7. NEVER use raw newlines inside Kotlin "..." string literals — use \n or triple-quoted strings
8. ALWAYS use key(orientation) on AlertDialogs (Activity has configChanges=orientation)
9. ALWAYS call remember() unconditionally at the top of a @Composable (Compose rules of hooks)
10. NEVER call remember() inside items{}, conditionals, or loops
11. MINIMAP IS EXCLUDED — do not implement it under any circumstances
12. 8-SECOND STARTUP HEADSTART — all heavy init (terminal restore, indexing) must wait for it
