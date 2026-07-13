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
| #1000 | GREEN | Last confirmed passing before Phase 2 editor work |
| #1001–1006 | FAIL | McpShellProfile.kt:120 — Kotlin string escape bug |
| #1007 | GREEN | Fixed L120 with triple-quoted string |
| #1008 | FAIL | hover docs + rich snippets — raw newlines inside double-quoted strings (KSP crash) |
| #1009 | FAIL | sticky scroll — remember() inside if/else branch (Compose rules violation) |
| #1010 | FAIL | fix: move stickyScope outside conditional — CE still had raw newline strings |
| #1011 | FAIL | fix: remove remember inside LazyColumn items{} — same root cause, CE not fully fixed |
| #1012–#1027 | FAIL | Multiple fix attempts; root cause fully resolved in #1028 |
| #1028 | GREEN ✅ | fix(editor): escape snippet insertText newlines — definitive fix |
| #1029 | GREEN ✅ | docs(AGENTS): CI status + Phase 2 session log |
| #1030 | GREEN ✅ | docs(AGENTS): full audit directive + 11-phase roadmap |

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
| CI | Build green | DONE ✅ (#1028) | Raw newline escape fix confirmed green — 0111924526f3 |
| 12 | Terminal cross-project state bleed | DONE | TrackedSession scoping fixed |
| 13 | AI package access bridging | DONE | ProotInstaller.execOnce routing |
| 11 | GitHub OAuth repo browsing | DONE | RepoBrowserSheet.kt shipped 2026-07-13 |
| P2-1 | Rename Symbol | NEXT 🔜 | CI is green — start this feature in next session |
| P2-2 | Multi-cursor editing | TODO | Alt+click to add cursor; insert/delete synced across cursors |
| P2-3 | Go to Definition | TODO | Tap symbol -> jump to its definition line in same file |
| P2-4 | Find and Replace in file | TODO | Dialog with regex toggle, match highlight, replace one/all |
| P2-5 | Error squiggles (lint) | TODO | Pattern match undefined vars, missing braces -> red underline |
| P2-6 | Git diff gutter | TODO | Green/orange/red sidebar bars vs HEAD |

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
| # | Feature | Status |
|---|---------|--------|
| P2-1 | Rename Symbol | NEXT 🔜 — CI GREEN (#1028), ready to implement |
| P2-2 | Find & Replace (full: regex, highlight, replace-all) | TODO |
| P2-3 | Multi-cursor editing | TODO |
| P2-4 | Go to Definition | TODO |
| P2-5 | Error squiggles (visual underlines for lint) | TODO |
| P2-6 | Git diff gutter | TODO |
| P2-7 | Code folding | TODO |
| P2-8 | Breadcrumb navigation | TODO |
| P2-9 | Code bookmarks | TODO |
| P2-10 | Jump back / forward navigation history | TODO |
| P2-11 | Inlay hints (type annotations inline) | TODO |
| P2-12 | Parameter hints / signature help | TODO |

---

## PHASE 3 — VERIFY & REPAIR EXISTING FEATURES

### Must audit in order before implementing anything else

#### File Explorer (ExplorerPane.kt)
- [ ] Rename — does it actually rename the file on disk?
- [ ] Copy/Cut/Paste — do these actually move bytes?
- [ ] Duplicate — does it copy the file?
- [ ] Delete — does it delete recursively for folders?
- [ ] "Open in Terminal" — does it cd to the correct directory?
- [ ] Search panel — does in-project search return accurate results?
- [ ] Outline view — does it actually parse symbols from the current file?
- [ ] Long-press context menu — all 13 items working?

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
- [ ] Stage/unstage — works?
- [ ] Commit — works?
- [ ] Push — uses GitHub token correctly?
- [ ] Pull / Fetch — works?
- [ ] Branch switch — works?
- [ ] Diff viewer — shows actual diff?
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

## PHASE 5 — PACKAGE MANAGER UPGRADE

Current state (AUDIT FIRST):
- Likely shows a list and copies `apt install <pkg>` to clipboard
- Does NOT actually run the command

Target implementation:
- One-tap install → runs `apt install -y <pkg>` in active terminal via PTY write
- Package search (apt-cache search)
- Show installed packages (dpkg --list)
- Remove packages
- Update packages (apt upgrade)
- Download progress (parse apt output)
- Error reporting inline
- Installation history (Room entity)
- Cancel mid-install (SIGINT via PTY)
- Dependency resolution (apt handles this, surface the output)

---

## PHASE 6 — GIT & VERSION CONTROL COMPLETENESS

Audit JGit implementation then fill gaps:
- [ ] Diff viewer — side-by-side or unified? Is it scrollable?
- [ ] Commit history — shows list? Tap to view commit details?
- [ ] Branch management — create, delete, rename, switch all working?
- [ ] Merge conflict resolution — does it open conflict markers in editor?
- [ ] Stash — save/pop working?
- [ ] Local version history — file-level history (separate from git, snapshots)
- [ ] .gitignore editor — create/edit from Source Control pane
- [ ] Tag management

---

## PHASE 7 — RECOVERY & RELIABILITY

- Auto-save editor content every 60 seconds to `<project>/.autosave/`
- Crash recovery — on next launch, detect `.autosave/` files → offer restore dialog
- Workspace snapshots — manual "Create Snapshot" → tar.gz of project dir
- Diagnostics report generator — collects: device info, app version, recent logs, crash stack → shareable text file
- Emergency recovery mode — if app crashes on startup 2+ times → safe mode (no project auto-open, no terminal auto-start)

---

## PHASE 8 — DEBUGGING INFRASTRUCTURE

Only if not already present after full audit:
- DAP (Debug Adapter Protocol) client — requires language-specific debug adapter in terminal
- Breakpoint markers in editor gutter (UI only initially, wire to DAP later)
- Variable inspector panel (Bottom panel new tab)
- Debug Console (already in bottom panel — VERIFY it works)
- Logcat viewer (Android device logs via `adb logcat` or `/proc` on rooted)

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

## PHASE 11 — ANDROID DEVELOPMENT TOOLS (Long term)

Only if not already present:
- ADB bridge via terminal (`adb` in Termux bootstrap — check if already included)
- Device detection — `adb devices` output parsed into a device picker
- Logcat viewer (structured, filterable — separate from raw terminal)
- APK build trigger — runs Gradle via terminal, surfaces output in Build panel
- APK signing — keytool + jarsigner or apksigner via terminal
- APK install — `adb install` with progress

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
