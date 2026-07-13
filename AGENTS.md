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

## CI BUILD STATUS

| Build | Result | Notes |
|-------|--------|-------|
| #1000 | ✅ GREEN | Last confirmed passing |
| #1001–#1006 | ❌ FAIL | All same error: `McpShellProfile.kt:120` — Kotlin string escape bug |
| #1007 | 🟡 RUNNING | Fix pushed 2026-07-13 — L120 switched to triple-quoted string |

**Root cause of #1001–#1006 failures:** `McpShellProfile.kt` line 120 used `\\"` inside a Kotlin double-quoted string. `\\"` = `\\` (literal backslash) + `"` (CLOSES string early). Fix: switched to `"""..."""` triple-quoted string where backslash is literal.

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
| CI | Build green | 🟡 RUNNING (#1007) | L120 McpShellProfile fix pushed |
| 12 | Terminal cross-project state bleed | ⏳ TODO | Switching projects mirrors keystrokes between terminals — needs TerminalSession scoping audit |
| 13 | AI package access bridging | ⏳ TODO | Terminal AI can't read/write project files directly — bridge AgentApiServer to filesystem |
| 11 | GitHub OAuth repo browsing | ✅ DONE | RepoBrowserSheet.kt — browse repos, clone into proot /root/repos/<name> via git + auth header (2026-07-13) |

---

## DEVICE CONSTRAINTS (TECNO KL4 — aarch64, Android 14, Kernel 5.15.180)

- 3-8 GB RAM — avoid loading large files into memory at once
- Samsung-derived kernel 5.15 — seccomp blocks `symlinkat()` inside unprivileged namespaces
- Always use 8KB stream buffers for extraction, not byte-array slurp
- System.gc() every 1000 files during extraction
- No W^X restriction on `nativeLibraryDir` — safe to execute .so files there
- Termux bootstrap ZIP is 29 MB — already in assets, extracts to ~150 MB

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
