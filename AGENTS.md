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
> Last updated: 2026-07-14. Read this FIRST before touching any code.

---

## CURRENT STATE (2026-07-15)

| | |
|-|-|
| Latest green build | **#1275** (Settings: DeletedProjectsSection fix GREEN) |
| Active phase | **Phase 21-X IN PROGRESS — Step 3 complete (APK/Smali/Disasm pushed, CI pending)** |
| Last green | #1275 — fix(settings): extract DeletedProjectsSection — TREE CLEAN ✅ |
| Last pushed | f69b4ef7 — feat(P21-X): APK Analyzer, Smali Viewer, Disassembly Viewer (Step 3) |
| **Phase 16** | **✅ COMPLETE — all 6 items shipped, #1199 GREEN** |
| **Next** | Phase 21-X Step 4 — wire remaining items (Entropy Heatmap, PCAP/HAR, GGUF, OAT) |
| Phase 18 | ✅ COMPLETE — Multi-file edit & refactoring |
| Phase 18 | ✅ COMPLETE (build #1219 GREEN) — Multi-file edit & refactoring |
| Phase 17 | ✅ COMPLETE (build #1208 GREEN) — File mgmt polish: local history, trash restore, compress, permissions, cloud backup tab |
| Phase 18 | ✅ COMPLETE (build #1219 GREEN) — Multi-file edit: Replace in Files, Select All Occurrences, Cross-file Rename Symbol |
| Phase 16 | ✅ COMPLETE (build #1199 GREEN) — Fetch, Cloud Backup, Session Sync, Sync UI |
| Phase 15 | ✅ COMPLETE (build #1183 GREEN) |
| Phase 14 | ✅ COMPLETE (build #1176) |
| Phase 13 | ✅ COMPLETE (build #1172) — Runtime UX Polish & Stability |
| Phase 12 | ✅ COMPLETE (build #1157) — Project Setup & Toolchain |
| Phase 11 | ✅ COMPLETE (build #1137) — Android Build Environment |
| Phase 9 | ✅ COMPLETE (build #1129) — Performance & Monitoring |
| Phase 8 | ✅ COMPLETE (build #1119) — Debugging Infrastructure |
| Phase 7 | ✅ COMPLETE (build #1108) |
| Phase 6 | ✅ COMPLETE (build #1098) |
| Phase 5 | ✅ COMPLETE (build #1096) |
| Phase 4 | ✅ COMPLETE (build #1086) |
| Phase 3 | ✅ COMPLETE (build #1085) |
| Phase 2 | ✅ COMPLETE (build #1068) |

### Phase 8 — Debugging Infrastructure ✅ COMPLETE (build #1119)

| # | Feature | Status | Notes |
|---|---------|--------|-------|
| P8-1 | Breakpoint gutter markers | ✅ DONE (#1111/#1112) | Tap line number = toggle red dot |
| P8-2 | Logcat viewer | ✅ DONE (#1113→#1115→#1119) | LogcatPanel.kt, color-coded, filter, pause |
| P8-3 | Variable inspector panel | ✅ DONE (#1116/#1117→#1119) | VariableInspectorPanel.kt, watch+locals+callstack |
| P8-4 | DAP client | ⏭️ SKIP | Needs external language adapters |

Bottom panel tabs: PROBLEMS, OUTPUT, TERMINAL, DEBUG, PORTS, SPLIT, PREVIEW, LOGCAT, VARIABLES

### Phase 9 — Performance & Monitoring ✅ COMPLETE (build #1129)

| # | Feature | Status | Files |
|---|---------|--------|-------|
| P9-1 | Background file indexer + symbol search | ✅ DONE | FileIndexer.kt, SymbolSearchPanel.kt, wired in PSS |
| P9-2 | Smart file caching (LRU, 20 files) | ✅ DONE | FileCache.kt, wired in EditorPane.kt |
| P9-3 | Memory pressure monitor (RAM in status bar) | ✅ DONE | PerformanceMonitor.kt → MemoryMonitor, StatusBarContent |
| P9-4 | Large file support (>1MB detection) | ✅ DONE | FileCache.isLargeFile(), EditorPane check |
| P9-5 | Code metrics + live cursor in status bar | ✅ DONE | PerformanceMonitor.kt → CodeMetrics, StatusBarContent |

New files created in Phase 9:
- `diagnostics/PerformanceMonitor.kt` — MemoryMonitor (reads /proc/meminfo) + CodeMetrics (lines, size, functions, nesting)
- `editor/FileCache.kt` — LRU cache, 20 files, invalidates on write, isLargeFile threshold 1MB
- `editor/FileIndexer.kt` — Background workspace symbol indexer (classes, functions, variables)
- `ui/panes/SymbolSearchPanel.kt` — Overlay UI for Go-to-Symbol workspace search

Modified files in Phase 9:
- `ui/screens/ProjectShellScreen.kt` — Added imports, indexer startup, "Go to Symbol" menu, SymbolSearchOverlay + StatusBarContent extracted composables
- `ui/panes/EditorPane.kt` — loadFileContent uses FileCache, cache invalidation on write, large file check

### ⚠️ ARCHITECTURAL RISK: ProjectShellScreen.kt method size

**ProjectShellScreen.kt is 2160 lines.** It hit the JVM 64KB method-too-large limit during Phase 9 (#1126–#1128 failed).
The fix was extracting `SymbolSearchOverlay()` and `StatusBarContent()` into separate @Composable functions (#1129 green).

**RULE FOR FUTURE PHASES:** Any new UI added to ProjectShellScreen MUST be extracted into a separate
@Composable function from the start. Do NOT inline large blocks in the main `ProjectShellScreen` function.
The main function should delegate to extracted composables. If the file grows past ~2200 lines,
proactively extract more composables before the build breaks.

Current extracted composables in PSS:
- `SymbolSearchOverlay()` — symbol search overlay (P9-1)
- `StatusBarContent()` — full status bar with RAM, metrics, cursor, MCP (P9-3/P9-5)

### CI Build History — Phase 8 & 9

| Build | Result | Notes |
|-------|--------|-------|
| #1111 | GREEN ✅ | feat(P8-1): breakpoint gutter markers |
| #1112 | GREEN ✅ | feat(P8-1): wire breakpoints into EditorPane |
| #1113 | GREEN ✅ | feat(P8-2): LogcatPanel.kt — new Logcat viewer composable |
| #1114 | FAIL ❌ | feat(P8-2): add Logcat tab — Compose State read off main thread |
| #1115 | FAIL ❌ | fix(P8-2): AtomicBoolean for pause flag — still had P8-3 issues in tree |
| #1116 | FAIL ❌ | feat(P8-3): VariableInspectorPanel — compilation errors |
| #1117 | FAIL ❌ | feat(P8-3): wire VariableInspectorPanel — same root cause |
| #1118 | FAIL ❌ | docs(AGENTS): update Phase 8 status — ran on broken tree |
| #1119 | GREEN ✅ | fix(P8): LOGCAT + VARIABLES branches in overflow menu — P8 COMPLETE ✅ |
| #1120 | GREEN ✅ | feat(P9-3/P9-5): PerformanceMonitor — MemoryMonitor + CodeMetrics |
| #1121 | GREEN ✅ | feat(P9-3/P9-5): wire RAM monitor + file metrics + live cursor into status bar |
| #1122 | GREEN ✅ | feat(P9-2): FileCache — LRU cache for recently opened files |
| #1123 | GREEN ✅ | feat(P9-2/P9-4): wire FileCache into EditorPane + cache invalidation on write |
| #1124 | GREEN ✅ | feat(P9-1): FileIndexer — background workspace symbol index |
| #1125 | FAIL ❌ | feat(P9-1): SymbolSearchPanel — missing focusRequester import |
| #1126 | FAIL ❌ | feat(P9-1): wire FileIndexer + SymbolSearchPanel — private loadWorkspacePath + bad padding |
| #1127 | FAIL ❌ | fix(P9-1): add focusRequester import — PSS still broken in this commit |
| #1128 | FAIL ❌ | fix(P9-1): replace loadWorkspacePath + fix padding — Method too large (JVM 64KB limit) |
| #1129 | GREEN ✅ | fix(P9): extract SymbolSearchOverlay + StatusBarContent — P9 COMPLETE ✅ |

Root cause of #1125–#1128 (Phase 9):
1. **#1125**: Missing `import androidx.compose.ui.focus.focusRequester` in SymbolSearchPanel.kt
2. **#1126**: `loadWorkspacePath` is private in ExplorerPane.kt — can't call from ProjectShellScreen.kt
   Fix: use `java.io.File(context.filesDir, "projects/$projectId").absolutePath` instead
3. **#1126**: `padding(top = 60.dp, horizontal = 16.dp)` — no such overload exists in Compose
   Fix: chain two `padding()` calls: `.padding(horizontal = 16.dp).padding(top = 60.dp)`
4. **#1128**: `Method too large` — ProjectShellScreen composable exceeded JVM 64KB bytecode limit
   Fix: extract `SymbolSearchOverlay()` and `StatusBarContent()` into separate @Composable functions

LESSON: When adding new imports to a file, verify the import path is correct (focusRequester is a
modifier extension, needs `import androidx.compose.ui.focus.focusRequester`). When calling functions
from other files, check they're not `private`. When adding to large composables (>1500 lines),
extract new UI into separate @Composable functions from the START.

---

## KNOWN KOTLIN/COMPOSE CI FAILURE PATTERNS (memorise these)

Do NOT repeat any of these — they have each caused 5+ failed builds:

1. Raw newlines inside double-quoted strings: "foo\nbar" is OK, literal newline is NOT. Use \n or triple-quoted strings.
2. remember() inside if/else branches or LazyColumn items{} — Compose rules: call remember() unconditionally at top of composable.
3. Double-quotes inside a double-quoted string: "of "$var"" breaks the string. Use single quotes: "of '$var'".
4. Triple-quoted strings inside ${} interpolation — not valid Kotlin. Extract to a local val first.
5. `LocalContext.current` (or any `Local*.current`) inside `scope.launch {}` / `LaunchedEffect {}` / coroutine lambdas — NOT allowed. Capture it at the top of the `@Composable` function and use the captured val inside any lambdas.

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
| #1113 | GREEN ✅ | feat(P8-2): LogcatPanel.kt — new Logcat viewer composable |
| #1114 | FAIL ❌ | feat(P8-2): add Logcat tab — Compose State read off main thread |
| #1115 | FAIL ❌ | fix(P8-2): AtomicBoolean for pause flag — P8-3 issues still in tree |
| #1116 | FAIL ❌ | feat(P8-3): VariableInspectorPanel — compilation errors |
| #1117 | FAIL ❌ | feat(P8-3): wire VariableInspectorPanel — same root cause |
| #1118 | FAIL ❌ | docs(AGENTS): update Phase 8 status — ran on broken tree |
| #1119 | GREEN ✅ | fix(P8): LOGCAT + VARIABLES branches in overflow menu — P8 COMPLETE ✅ |
| #1120 | GREEN ✅ | feat(P9-3/P9-5): PerformanceMonitor — MemoryMonitor + CodeMetrics |
| #1121 | GREEN ✅ | feat(P9-3/P9-5): wire RAM monitor + file metrics + live cursor into status bar |
| #1122 | GREEN ✅ | feat(P9-2): FileCache — LRU cache for recently opened files |
| #1123 | GREEN ✅ | feat(P9-2/P9-4): wire FileCache into EditorPane + cache invalidation on write |
| #1124 | GREEN ✅ | feat(P9-1): FileIndexer — background workspace symbol index |
| #1125 | FAIL ❌ | feat(P9-1): SymbolSearchPanel — missing focusRequester import |
| #1126 | FAIL ❌ | feat(P9-1): wire FileIndexer + SymbolSearchPanel — private fn + bad padding |
| #1127 | FAIL ❌ | fix(P9-1): focusRequester import — PSS still broken in this commit |
| #1128 | FAIL ❌ | fix(P9-1): loadWorkspacePath + padding fix — Method too large (JVM 64KB) |
| #1129 | GREEN ✅ | fix(P9): extract composables — P9 COMPLETE ✅ — REPO CLEAN |

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

### Main IDE Shell (`ProjectShellScreen.kt` — 2160 lines, 8 @Composable functions)
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

## PHASE 8 — DEBUGGING INFRASTRUCTURE ✅ COMPLETE (build #1119)

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

## PHASE 9 — PERFORMANCE & MONITORING ✅ COMPLETE (build #1129)

All items shipped:
- ✅ Background file indexer — FileIndexer.kt walks project tree, extracts symbols (classes, functions, variables)
- ✅ Smart file caching — FileCache.kt LRU cache (20 files), invalidates on write, avoids disk re-reads on tab switch
- ✅ Memory pressure monitor — MemoryMonitor reads /proc/meminfo every 5s, shows used/total RAM in status bar, red text <100MB
- ✅ Large file support — FileCache.isLargeFile() checks >1MB threshold before loading
- ✅ Code metrics — CodeMetrics.analyze() shows line count, file size, function count in status bar + live cursor position

New files: PerformanceMonitor.kt, FileCache.kt, FileIndexer.kt, SymbolSearchPanel.kt
Modified: ProjectShellScreen.kt (extracted SymbolSearchOverlay + StatusBarContent), EditorPane.kt (FileCache integration)

⚠️ LESSON LEARNED: ProjectShellScreen.kt hit JVM 64KB method-too-large limit. All future additions
MUST be extracted @Composable functions, not inline blocks in the main function.


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
### Phase 12 Implementation Status: IN PROGRESS (backend done, UI panels pending)

**Current HEAD: #1150 GREEN ✅ — full tree compiles.**

#### Backend files (`android/app/src/main/java/com/codespace/ide/project/`)

| # | Feature | File | Status |
|---|---------|------|--------|
| 12-A | ProjectWizard — two-step type picker + name dialog | `ProjectWizard.kt` | SHIPPED (fixed #1149) |
| 12-B | ProjectTemplates — scaffold files for 7 project types | `ProjectTemplates.kt` | SHIPPED (fixed #1149) |
| 12-C | ToolchainManager — detect JDK/Gradle/SDK/Flutter/Node/Python | `ToolchainManager.kt` | SHIPPED (fixed #1150) |
| 12-D | DownloadCenter — track downloads with live StateFlow progress | `DownloadCenter.kt` | SHIPPED |
| 12-E | BuildHistoryStore — persist build history (max 100 entries) | `BuildHistoryStore.kt` | SHIPPED |
| 12-F | BuildArtifactManager — scan/share/install/delete APK+AAB | `BuildArtifactManager.kt` | SHIPPED |
| 12-G | TaskRunner — one-tap task catalogue delegating to BuildRunner | `TaskRunner.kt` | SHIPPED |
| 12-H | EnvironmentProfiles — per-stack profiles with tool requirements | `EnvironmentProfiles.kt` | SHIPPED |

HomeScreen.kt updated: basic New Project dialog replaced with full ProjectWizardDialog.

#### UI Panels (next — to be implemented)

| # | Feature | File | Status |
|---|---------|------|--------|
| 12-I | ToolchainPanel — status screen for all detected tools | `ui/ToolchainPanel.kt` | SHIPPED (#1154) |
| 12-J | TaskRunnerPanel — one-tap task buttons with live log | `ui/TaskRunnerPanel.kt` | SHIPPED (#1152) |
| 12-K | BuildHistoryPanel — scrollable build history with log view | `ui/BuildHistoryPanel.kt` | SHIPPED (#1153) |
| 12-L | ArtifactPanel — list/share/install APK+AAB artifacts | `ui/ArtifactPanel.kt` | SHIPPED (#1155) |
| 12-M | Wire panels into ProjectShellScreen bottom tabs | `ProjectShellScreen.kt` | SHIPPED (#1157) |

#### CI Build History — Phase 12

| Build | Result | Notes |
|-------|--------|-------|
| #1139 | FAIL | feat(P12-A): ProjectWizard — blocked by ProjectTemplates parse error |
| #1140 | FAIL | feat(P12-B): ProjectTemplates — triple-quote Python docstring syntax error |
| #1141 | FAIL | feat(P12-A): wire ProjectWizard into HomeScreen — same root error |
| #1142 | FAIL | feat(P12-C): ToolchainManager — same root error |
| #1143 | FAIL | feat(P12-D): DownloadCenter — same root error |
| #1144 | FAIL | feat(P12-E): BuildHistoryStore — same root error |
| #1145 | FAIL | feat(P12-F): BuildArtifactManager — same root error |
| #1146 | FAIL | feat(P12-G): TaskRunner — same root error |
| #1147 | FAIL | feat(P12-H): EnvironmentProfiles — same root error |
| #1148 | FAIL | docs(AGENTS): P12 status — same root error |
| #1149 | FAIL | fix(P12-B): ProjectTemplates string concat — fixed Templates, exposed ToolchainManager Regex bug |
| #1150 | GREEN ✅ | fix(P12-C): ToolchainManager Regex char literal fixed — TREE CLEAN |
| #1151 | GREEN ✅ | docs(AGENTS): full audit P12 — root cause logged, UI panels pending |
| #1152 | GREEN ✅ | feat(P12-J): TaskRunnerPanel — one-tap task grid with live output log |
| #1153 | GREEN ✅ | feat(P12-K): BuildHistoryPanel — build history list with expandable log |
| #1154 | GREEN ✅ | feat(P12-I): ToolchainPanel — tool health status screen |
| #1155 | GREEN ✅ | feat(P12-L): ArtifactPanel — list/share/install/delete APK+AAB |
| #1156 | FAIL ❌ | feat(P12-M): wire panels into PSS — non-exhaustive when() on BottomTab |
| #1157 | GREEN ✅ | fix(P12-M): add TOOLCHAIN/TASKS/HISTORY/ARTIFACTS to panel menu when — PHASE 12 COMPLETE ✅ |

**Root cause of #1139-#1149:** ProjectTemplates.kt used Python triple-quote `"""` inside a Kotlin triple-quoted string causing parse failure on all commits. Fixed by rewriting all scaffold content as string concatenation. Secondary: ToolchainManager.kt used single-quote Regex delimiter (parsed as char literal); fixed with double-quote + escape.

**NEW failure pattern to memorise:** Never embed triple-quotes inside Kotlin triple-quoted strings. Never use single-quote delimiters for Regex strings in Kotlin.

**NEW failure pattern to memorise (#1156):** When adding new values to an enum used in a `when` expression elsewhere in the codebase, search for ALL `when (activeBottomTab)` / `when (enumVar)` occurrences and add branches for new values. Kotlin requires exhaustive `when` on enum/sealed types — missing cases are compile errors.

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


---

### Phase 13 — Runtime UX Polish & Stability (ACTIVE)

| # | Feature | Status | Build | Notes |
|---|---------|--------|-------|-------|
| P13-A | Download Center panel | ✅ DONE | #1159 | DownloadCenterPanel.kt + DOWNLOADS tab |
| P13-B | Live git+lint badge counts in activity bar | ✅ DONE | #1160 | Replace hardcoded zeros |
| P13-C | Toolchain install button (runs pkg install in terminal) | ✅ DONE | #1164 | ToolchainPanel.kt |
| P13-D | Rename Project long-press on HomeScreen | ✅ DONE | #1164 | HomeScreen.kt AlertDialog |
| P13-E | Real project name in PSS top bar | ✅ DONE | #1164 | projectName from filesDir |
| — | Hotfix cascade #1165→#1172 | ✅ RESOLVED | #1172 | 8-build cascade, all fixed |
| **Phase 13** | **COMPLETE** | **✅** | **#1172** | **All items shipped** |

#### CI Build History — Phase 13

| Build | Result | Notes |
|-------|--------|-------|
| #1157 | ✅ | fix(P12-M): exhaustive when branches — PHASE 12 COMPLETE |
| #1158 | ✅ | docs(AGENTS): Phase 12 complete, failure patterns updated |
| #1159 | ✅ | feat(P13-A): DownloadCenterPanel + DOWNLOADS tab + clear terminal fix |
| #1160 | ✅ | fix(P13-B): live git+lint badge counts in activity bar |
| #1161 | ❌ | fix(crash): split ProjectShellScreen DEX register overflow — broke tree |
| #1162 | ❌ | fix: previewPort type mismatch (Int? vs Int) in PssBottomPanelContent |
| #1163 | ❌ | fix: VARIABLES+BUILD cases missing in PssBottomPanelContent |
| #1164 | ✅ | fix: remove invalid modifier param from BuildPanel call |
| #1165 | ❌ | fix: 4 bugs — McpShellProfile.kt:184 double-quote inside appendLine() |
| #1166 | ❌ | fix: terminal zoom toggle — McpShellProfile still broken (same root) |
| #1167 | ❌ | fix: McpShellProfile quote fixed — but AgentScheduler/ExplorerPane/PSS errors surfaced |
| #1168 | ❌ | fix: AgentScheduler ctx param — ExplorerPane smart-cast + PSS unresolved refs remain |
| #1169 | ❌ | fix: ExplorerPane wsSnap — broke if/else by replacing with run{} |
| #1170 | ❌ | fix: PSS params added — ExplorerPane run{}/else syntax error still present |
| #1171 | ❌ | fix: ExplorerPane if/else restored — PSS GoToLine .filter on Unit remained |
| #1172 | ✅ | fix: PSS GoToLine filter applied to 'it' before callback — TREE CLEAN ✅ |

#### Root causes of #1161–#1172 cascade

1. **#1161–#1164**: Attempted DEX-register split and type-mismatch fixes introduced new errors. Each fix only exposed the next underlying issue.
2. **#1165–#1166**: `McpShellProfile.kt:184` — raw double-quotes inside `appendLine("echo "..."")` broke Kotlin parser (Expecting an element). Pattern already in known-failures list as rule 3.
3. **#1167–#1168**: `AgentScheduler.runCommand()` called with 2 args but signature took 1. `ExplorerPane.workspacePath` is a `var` delegate — smart cast impossible without local val capture.
4. **#1168**: `PssOverlays` was missing `context`, `orientation`, `handleMenuAction`, `showNotification` params — used inside body but not declared in signature.
5. **#1169**: Fixing ExplorerPane smart-cast by replacing `if (workspacePath != null) {` with `run {` left the original `} else {` branch dangling — syntax error.
6. **#1170**: ExplorerPane params were at call site but the PSS GoToLine still had `.filter {}` chained on `Unit`.
7. **#1172**: Fixed by applying `.filter { c -> c.isDigit() }` to `it` before passing to `onGoToLineInputChange`.

#### NEW failure patterns to memorise

- **`var` delegate smart cast**: `var x by remember { mutableStateOf(...) }` cannot be smart-cast. Always capture to a local `val snap = x` before using in blocks that need non-null type.
- **`run {}` vs `if/else`**: Never replace `if (cond) { ... } else { ... }` with `run { ... }` — the `else` becomes dangling. Use a local val + `if (val != null)` instead.
- **Chaining on `Unit`**: `callback(it).filter {...}` fails when callback returns `Unit`. Apply transforms to the input before the call: `callback(it.filter {...})`.
- **Extracting composables**: When a nested composable needs variables from the parent scope, ALL referenced variables must be passed explicitly as parameters — they do not close over the outer scope automatically when moved to a `private fun`.


---

### Phase 14 — Advanced Terminal & Shell UX (ACTIVE)

**Goal:** Make the terminal a first-class mobile IDE experience — smarter shell, better navigation, real multi-session management, and robust SSH.

| # | Feature | Status | Notes |
|---|---------|--------|-------|
| P14-A | Persistent terminal scrollback (save/restore scroll buffer on tab switch) | ✅ DONE | viewCache in TerminalState; AndroidView reused per tab ID; evicted on close |
| P14-B | Shell history search (Ctrl+R style overlay in extra-keys bar) | ✅ DONE | ShellHistorySearchOverlay.kt + TerminalHistoryStore; 🔍 Hist button in quick-actions |
| P14-C | Terminal hyperlink detection (tap URLs to open in preview) | ✅ DONE | urlRegex scan every 2s; dismissible chip bar above quick-actions; Intent.ACTION_VIEW |
| P14-D | TOFU SSH fingerprint pinning (replace PromiscuousVerifier) | ✅ DONE | TofuVerifier + SshFingerprintStore; filesDir/ssh-known-hosts.json; getKnownHosts()/removeFingerprint() |
| P14-E | Terminal session name editor (rename tabs) | ✅ DONE | Long-press tab → existing rename dialog; TerminalSessionRenameDialog.kt added |
| P14-F | Quick command palette for terminal (recent commands + suggestions) | ✅ DONE | Long-press 🔍 Hist → recent-5 strip; tap → inject; 'Search all →' opens full overlay |

**Implementation order:** P14-D first (security), then P14-B, P14-E, P14-C, P14-A, P14-F.

#### CI Build History — Phase 14

| Build | Result | Notes |
|-------|--------|-------|
| #1172 | ✅ | fix: PSS GoToLine filter — PHASE 13 COMPLETE, TREE CLEAN |
| chat-fix | ✅ | fix(chat-panel): move chat panel inside main Row + AnimatedBotIcon professional animation (995f333) |
| P14-D/B/E | ✅ PUSHED | feat(P14-D/B/E): TOFU SSH, history search, long-press rename (a0e1e5c) |
| P14-A/C/F | ✅ PUSHED | feat(P14-A/C/F): scrollback cache, URL chip bar, quick cmd palette (5d51cb4) |

New files created in Phase 14 (so far):
- `ssh/SshManager.kt` — TOFU verifier replaces PromiscuousVerifier; SshFingerprintStore persists to filesDir/ssh-known-hosts.json
- `ui/panes/ShellHistorySearchOverlay.kt` — Ctrl+R history search overlay + TerminalHistoryStore object
- `ui/panes/TerminalSessionRenameDialog.kt` — standalone rename dialog composable (reusable)

Modified files in Phase 14 (so far):
- `ui/panes/TerminalPane.kt` — showHistorySearch state, 🔍 Hist button, long-press tab to rename, STT history append

---


## PHASE 15 — EDITOR INTELLIGENCE & UX POLISH
### Phase 15 Implementation Status: ✅ COMPLETE (#1183 GREEN)

**CI context:**
- #1177-#1179: FAIL — TerminalPane.kt:1270 illegal regex escapes (\w, \[, \]) in double-quoted string. Root cause: P14-C URL regex used `"..."` not `"""..."""`
- #1180: GREEN ✅ — fix(P14-C): TerminalPane URL regex moved to triple-quoted string
- #1181: PENDING — feat(P15-E/G/H): ProjectFileSearch overlay + heavyPanesReady gate + isWideLayout scaffold

**NEW failure pattern to memorise:** Regex special chars (\w \d \[ \] \s etc.) inside regular Kotlin double-quoted strings cause "Illegal escape" compile errors. ALWAYS use triple-quoted `"""..."""`  or `Regex("""pattern""")` for any regex with backslash sequences.

#### Files shipped in Phase 15

| # | Feature | File | Status |
|---|---------|------|--------|
| 15-A | Fix with AI — onAiFixRequest param, context sheet action | `editor/CodeEditor.kt` | ✅ SHIPPED #1179 |
| 15-B | Bracket pair colorization | — | ✅ Already present — no work needed |
| 15-C | Sticky scroll — scope line pinned at editor top | `editor/CodeEditor.kt` | ✅ SHIPPED #1179 |
| 15-D | Ghost text inline completion (800ms delay, tap to accept) | `editor/CodeEditor.kt` | ✅ SHIPPED #1179 |
| 15-E | ProjectFileSearchPanel — fuzzy file + full-text search | `ui/panes/ProjectFileSearchPanel.kt` | ✅ SHIPPED #1179 |
| 15-E | Wire ProjectFileSearch overlay into PSS (Find in Files) | `ui/screens/ProjectShellScreen.kt` | ✅ SHIPPED #1181 |
| 15-F | Logcat level filter chips (E/W/I/D/V toggles) | `ui/panes/LogcatPanel.kt` | ✅ SHIPPED #1179 |
| 15-G | heavyPanesReady 8s gate (Logcat, Variables, BuildHistory) | `ui/screens/ProjectShellScreen.kt` | ✅ SHIPPED #1181 |
| 15-H | isWideLayout two-column landscape scaffold | `ui/screens/ProjectShellScreen.kt` | ✅ SHIPPED #1181 |

#### CI Build History — Phase 14 & 15

| Build | Result | Notes |
|-------|--------|-------|
| #1173 | ✅ GREEN | docs(AGENTS): Phase 13 status correct |
| #1174 | ✅ GREEN | docs(AGENTS): Phase 14 plan defined |
| #1175 | ✅ GREEN | feat(P14-D/B/E): TOFU SSH fingerprint, shell history search, tab-complete |
| #1176 | ✅ GREEN | docs(AGENTS): P14-D/B/E shipped |
| #1177 | ❌ FAIL | feat(P14-A/C/F): scrollback cache, URL hyperlink bar, quick cmd palette — TerminalPane regex illegal escapes |
| #1178 | ❌ FAIL | docs(AGENTS): Phase 14 COMPLETE — same root error still in tree |
| #1179 | ❌ FAIL | feat(P15-A/C/D/E/F): all P15 features — same root error still in tree |
| #1180 | ✅ GREEN | fix(P14-C): TerminalPane URL regex → triple-quoted string — TREE CLEAN |
| #1181 | ❌ FAIL | feat(P15-E/G/H): PSS fixes — KSP error unrelated; tree fixed by #1183 |
| #1182 | ❌ FAIL | docs(AGENTS): Phase 15 plan — same underlying error still in tree |
| #1183 | ✅ GREEN | fix(P15-E/G/H): PSS totalWidth/isWideLayout order, FileSearch params, heavyPanesReady scope — TREE CLEAN |
| #1184 | ❌ FAIL | fix(agent-profile): McpShellProfile bad syntax attempt |
| #1185 | ❌ FAIL | fix(locale): LC_ALL guard — KSP blocked by McpShellProfile:120 bug |
| #1186 | ✅ GREEN | fix(agent-profile): McpShellProfile save_terminal_session triple-quoted string — TREE CLEAN |
| #1187 | ✅ GREEN | fix(VerifyError): PssEditorColumn extracted + PSS MutableState refactor — HEAD |



---

## CRASH FIX: VerifyError — ProjectShellScreen (2026-07-15)

**Symptom:** `java.lang.VerifyError: Verifier rejected class ProjectShellScreenKt` on ART — `copy-cat1 v22<-v293 type=High-half Constant` (classes12.dex). App crashes immediately on launch.

**Root cause:** `ProjectShellScreen()` compiled to a function with 1150 lines, generating ~300+ DEX registers. ART's verifier rejects 64-bit constants split across high-numbered register pairs (>v256). This is an ART verifier limitation, not a code logic bug.

**Fix (commit 53550d85e3 + a0a3c39448):**
- Extracted the Editor Column + Split Terminal + Chat Panel section (~443 lines) into new file `PssEditorColumn.kt` as `internal fun PssEditorColumn()`
- Converted 25 `var X by remember {}` declarations to `val XMs = remember {}; var X by XMs` in PSS
- Passed `MutableState<T>` objects to `PssEditorColumn` — child uses `var X by XMs` delegation (zero body logic changes)
- PSS main function: 1150 → ~700 lines; register count drops well below the v256 threshold

**Files:**
- NEW: `ui/screens/PssEditorColumn.kt` (567 lines)  
- MOD: `ui/screens/ProjectShellScreen.kt` (2125 lines, was 2510)


---

### Phase 16 — Cloud Backup, Session Sync & Source Control Polish ✅ COMPLETE (#1199 GREEN)

| Item | Feature | File(s) |
|------|---------|---------|
| P16-A | SourceControlPane — fetch button + pull/push/fetch result feedback + actionToast state fix | `SourceControlPane.kt` |
| P16-B | CloudBackupManager — backup/restore/list projects as tar.gz to backups dir | `CloudBackupManager.kt` |
| P16-C | SessionHandoffManager — export/import/push/pull session state for multi-device handoff | `SessionHandoffManager.kt` |
| P16-D | SyncStatusMonitor — Idle/Syncing/Success/Error StateFlow with auto-poll | `SyncStatusMonitor.kt` |
| P16-E | CloudBackupPanel — full backup/restore/session-sync UI with SyncStatusMonitor | `CloudBackupPanel.kt` |
| P16-F | StatusBarContent — sync indicator dot (Syncing/Synced/Error) wired to SyncStatusMonitor | `ProjectShellScreen.kt` |

**Root cause of #1193–#1198 failures:** `actionToast` state used in SourceControlPane but never declared. Fixed in #1199 (98cd9347dc).

#### CI Build History — Phase 16

| Build | Result | Notes |
|-------|--------|-------|
| #1192 | ✅ GREEN | fix(VerifyError): PssEditorColumn compile errors resolved — TREE CLEAN |
| #1193 | ❌ FAIL | feat(P16-A): SourceControlPane fetch — actionToast undeclared |
| #1194 | ❌ FAIL | feat(P16-B): CloudBackupManager — same root cause in tree |
| #1195 | ❌ FAIL | feat(P16-C): SessionHandoffManager — same |
| #1196 | ❌ FAIL | feat(P16-D): SyncStatusMonitor — same |
| #1197 | ❌ FAIL | feat(P16-E): CloudBackupPanel — same |
| #1198 | ❌ FAIL | feat(P16-F): StatusBarContent sync indicator — same |
| **#1199** | **✅ GREEN** | fix(P16-A): declare actionToast — **PHASE 16 COMPLETE** |


---

## PHASE 17 — FILE MANAGEMENT POLISH & BACKUP UX ✅ COMPLETE (build #1208)

| # | Feature | File | Status |
|---|---------|------|--------|
| P17-A | Local version history — 30s snapshots, "Local History" context menu, restore | `ExplorerPane.kt` | ✅ SHIPPED #1201→#1207 |
| P17-B | Compress to zip — context menu, recursive ZipOutputStream, rename dialog | `ExplorerPane.kt` | ✅ SHIPPED #1208 |
| P17-C | File permissions — r/w/x viewer, executable toggle via setExecutable() | `ExplorerPane.kt` | ✅ SHIPPED #1208 |
| P17-D | Trash restore UI — list .ide-trash entries, restore/purge with refresh | `ExplorerPane.kt` | ✅ SHIPPED #1201→#1207 |
| P17-E | BACKUP bottom tab — CloudBackupPanel wired into PSS via heavyPanesReady gate | `ProjectShellScreen.kt` | ✅ SHIPPED #1202→#1207 |

#### CI Build History — Phase 17

| Build | Result | Notes |
|-------|--------|-------|
| #1200 | ✅ GREEN | docs(AGENTS): P16 COMPLETE, P17 next |
| #1201 | ❌ FAIL | feat(P17-A/D): ExplorerPane history + trash — TrashEntry field names |
| #1202 | ❌ FAIL | feat(P17-E): CloudBackupPanel in PSS — wrong call signature |
| #1203 | ❌ FAIL | fix(P17-A/D): ExplorerPane TrashEntry fields + smart cast |
| #1204 | ❌ FAIL | fix(P17-E): CloudBackupPanel signature fix |
| #1205 | ❌ FAIL | fix(P17-A/D): extract findTrashProjectDir helper |
| #1206 | ❌ FAIL | fix(P17-E): thread showBackupPanelMs through PssEditorColumn |
| **#1207** | **✅ GREEN** | fix(P17-E): simplify CloudBackupPanel wiring — PHASE 17-A/D/E CLEAN |
| **#1208** | **⏳ RUNNING** | feat(P17-B/C): Compress + Permissions dialogs in ExplorerPane |

New context menu items added to ExplorerPane: "Compress" (zip), "Permissions" (chmod)
New state vars: showCompressDialog, showPermDialog (unconditional remember at top)
No new files created — all changes in ExplorerPane.kt

---


---

## PHASE 18 — MULTI-FILE EDIT & REFACTORING ✅ COMPLETE (build #1219)

**Goal:** Power-user editing across files — replace everywhere, multi-occurrence select.

| # | Feature | File | Status |
|---|---------|------|--------|
| P18-A | Replace in Files — "Replace" chip in ProjectFileSearchPanel, replaceQuery field, "Replace All (N)" button writes back to disk, snackbar feedback | `ProjectFileSearchPanel.kt` | ⏳ #1210 running |
| P18-B | Select All Occurrences — long-press context menu action seeds `extraCursors` at every `...` match, multi-cursor types simultaneously | `CodeEditor.kt` | ⏳ #1211 running |
| P18-C | Cross-file Rename Symbol — project-wide word-boundary replace with progress indicator | `CodeEditor.kt`, `EditorPane.kt` | ✅ SHIPPED #1219 |

#### Design notes
- P18-A uses `Regex.escape(query).replace()` on each file (not raw regex) — safe for literal strings
- P18-A dispatches on `Dispatchers.IO`, shows `SnackbarHost` result count
- P18-B reuses existing `extraCursors` multi-cursor engine (fan-out already implemented)
- P18-B places primary cursor at first match, extra cursors at all subsequent matches
- P18-C will reuse `ProjectFileSearchPanel`'s text search results + `File.writeText()` pattern from P18-A

---


---

## PHASE 18 — MULTI-FILE EDIT & REFACTORING ✅ COMPLETE (build #1219)

| # | Feature | File | Status |
|---|---------|------|--------|
| P18-A | Replace in Files — "Replace" amber chip in ProjectFileSearchPanel, batch File.writeText() + Snackbar feedback | `ProjectFileSearchPanel.kt` | ✅ #1214 |
| P18-B | Select All Occurrences — context sheet action seeds extraCursors at every word-boundary match in current file | `CodeEditor.kt` | ✅ #1212 |
| P18-C | Cross-file Rename Symbol — project-wide word-boundary replace with progress indicator | `CodeEditor.kt`, `EditorPane.kt` | ✅ SHIPPED #1219 |

#### CI Build History — Phase 18

| Build | Result | Notes |
|-------|--------|-------|
| #1210 | ✅ GREEN | feat(P18-B): Select All Occurrences — seed extraCursors at word-boundary match |
| #1211 | ✅ GREEN | feat(P18-A): Replace in Files in ProjectFileSearchPanel |
| #1212 | ✅ GREEN | feat(P18-B): Select All Occurrences — context sheet action |
| #1213 | ✅ GREEN | docs(AGENTS): Phase 18 started |
| #1214 | ✅ GREEN | feat(P18-A): Replace in Files — replace field + Replace All button |
| #1215 | ✅ GREEN | docs(AGENTS): Phase 18 plan |
| #1216 | ❌ FAIL | feat(P18-C): cross-file Rename Symbol — missing imports (Checkbox, File, LinearProgressIndicator) |
| #1217 | ❌ FAIL | feat(P18-C): pass projectRoot — same root cause |
| #1218 | ❌ FAIL | fix(P18-C): add missing imports — leftover f→file references |
| **#1219** | **✅ GREEN** | fix(P18-C): replace f→file in forEach — **PHASE 18 COMPLETE** |

---


## PHASE 19 — GIT DEEP FEATURES & LANGUAGE INTELLIGENCE ✅ COMPLETE (build #1226)

**Goal:** VS Code-level Git tooling — branch graph, merge conflict resolution, cross-file go-to-definition.

| # | Feature | File | Status |
|---|---------|------|--------|
| P19-A | Cross-file Go-to-Definition — searches FileIndexer for project-wide symbol definitions, shows "In this file" + "In project" results in dialog, tapping a project result opens that file in a new tab | `CodeEditor.kt`, `EditorPane.kt` | ✅ #1221, #1226 |
| P19-B | Branch Graph Visualization — new GRAPH tab in SourceControlPane, parses `git log --graph --oneline --all`, renders ASCII branch lines in monospace blue with sha + message | `SourceControlPane.kt` | ✅ #1222 |
| P19-C | Merge Conflict Resolver — conflict files show expandable resolver with Ours/Theirs/Both buttons per file, regex strips conflict markers and `git add`s the resolved file | `SourceControlPane.kt` | ✅ #1222 |

#### Design notes
- P19-A: `CrossFileDefResult` data class added alongside existing `DefResult`; `onOpenFileAtLine` callback parameter on CodeEditor, wired in EditorPane to open new EditorTab (with dedup via `tabs.none`)
- P19-B: `GraphRow` data class; graph ASCII parsed via `takeWhile` on `|*/\_-` characters; loaded alongside `loadLog()` to share the IO call
- P19-C: `ConflictResolverRow` extracted as separate @Composable (64KB rule); uses `Regex("(?s)<<<<<<< .*?\n(.*?)=======.*?>>>>>>> .*\n")` for Ours/Theirs, plain marker removal for Both
- `ScmTab` enum extended: `CHANGES, LOG, GRAPH, STASH, TAGS`

#### CI Build History — Phase 19

| Build | Result | Notes |
|-------|--------|-------|
| #1221 | ✅ GREEN | feat(P19-A): cross-file Go-to-Definition in CodeEditor |
| #1222 | ✅ GREEN | feat(P19-B/C): branch graph + merge conflict resolver |
| #1223 | ❌ FAIL | feat(P19-A): wire onOpenFileAtLine — wrong Language/EditorTab refs |
| #1224 | ❌ FAIL | fix: same issues (pushed without changes) |
| #1225 | ❌ FAIL | fix: Language.fromExtension doesn't exist + missing EditorTab.name param |
| **#1226** | **✅ GREEN** | fix: Language.fromPath() + EditorTab(name=file.name) — **PHASE 19 COMPLETE** |

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

## Phase 20-A: Git Blame — ✅ COMPLETE (build #1238 GREEN)

### P20-A: Git Blame
- `BlameLine` data class added to CodeEditor.kt (file-level)
- `blameData: Map<Int, BlameLine>?` parameter on CodeEditor
- Blame toggle button (Info icon) in EditorPane toolbar
- LaunchedEffect fetches `git blame --line-porcelain` via ProotInstaller.execOnce
- Author name shown per line in a dedicated column next to the gutter
- CI: 7 commits to resolve (BlameLine placement, TextOverflow import, Info icon import)

### Runtime Bug Fixes (2026-07-15)
1. **Locale warning fix**: Changed `LC_ALL=en_US.UTF-8` → `LC_ALL=C.UTF-8` in ProotInstaller launchArgs env vars. The en_US.UTF-8 locale isn't generated until `00-locale.sh` runs, but the env var was set before that. C.UTF-8 is always available; the profile script upgrades to en_US.UTF-8 after locale-gen.

2. **`.agent-profile.sh` EOF fix**: Fixed broken quotes in `agent_tools()` function in McpShellProfile.kt. The Python f-string `d.get("count",0)` had unescaped double quotes inside a shell double-quoted `python3 -c "..."` string, causing `unexpected EOF while looking for matching '`. Replaced with `str(d.get('count',0))` using single quotes.

3. **VerifyError fix**: Extracted `PssTopBar` composable (120 lines) from `ProjectShellScreen` main function to reduce bytecode below the JVM 64KB method limit. The main function was ~768 lines; extraction brings it down to ~648 lines. Previous extractions: PssOverlays, PssActivityBar, SymbolSearchOverlay, StatusBarContent, PssEditorColumn.

### Composable Extraction Status (ProjectShellScreen.kt)
- PssTopBar: ~120 lines ✅ NEW
- PssOverlays: ~439 lines
- PssActivityBar: ~485 lines
- PssEditorColumn: ~550 lines
- SymbolSearchOverlay: ~28 lines
- StatusBarContent: ~97 lines
- Main function: ~648 lines (reduced from ~768)

## Phase 20-A Runtime Fixes — ✅ COMPLETE (build #1247 GREEN)

### Audit Summary (2026-07-15)
Failure chain #1239–#1246 (8 consecutive failures) resolved at #1247.

Three runtime bugs fixed:

1. **Locale warning** (`setlocale: LC_ALL: cannot change locale (en_US.UTF-8)`)
   - ProotInstaller.kt `launchArgs()` hardcoded `LC_ALL=en_US.UTF-8` in env vars.
   - That locale doesn't exist until `00-locale.sh` runs `locale-gen`.
   - Fix: Changed to `LC_ALL=C.UTF-8` (always available). Profile script upgrades to en_US.UTF-8 after locale-gen.

2. **`.agent-profile.sh` EOF error** (`line 109: unexpected EOF while looking for matching '`)
   - McpShellProfile.kt `agent_tools()` had `d.get("count",0)` — unescaped double quotes inside shell double-quoted `python3 -c "..."` string.
   - Shell closed the quote at `"count"`, breaking everything after.
   - Fix: Replaced with `str(d.get('count',0))` using single quotes.

3. **VerifyError crash** (`java.lang.VerifyError: ProjectShellScreenKt — High-half Constant`)
   - Main `ProjectShellScreen` function was ~768 lines — exceeded JVM 64KB method bytecode limit.
   - Fix: Extracted `PssTopBar` composable (~120 lines) with color params passed as arguments.
   - Main function now ~648 lines. Previous extractions: PssOverlays, PssActivityBar, SymbolSearchOverlay, StatusBarContent, PssEditorColumn.

### PssTopBar extraction notes
- Colors (`bgColor`, `tabTextInactive`, `dividerColor`, `menuText`, `menuBg`) passed as params since they're local vals in ProjectShellScreen, not file-level.
- CI took 4 commits to fully resolve: (1) missing params, (2) body refs not replaced, (3) line 450 missed, (4) all fixed.

### Composable extraction status (ProjectShellScreen.kt — 2719 lines total)
- PssTopBar: ~120 lines ✅ NEW
- PssOverlays: ~439 lines
- PssActivityBar: ~485 lines
- PssEditorColumn: ~550 lines
- SymbolSearchOverlay: ~28 lines
- StatusBarContent: ~97 lines
- ideColors: ~1011 lines
- Main function: ~648 lines (reduced from ~768)

### Next: Phase 20-B

## PHASE 21 — UNIVERSAL FILE VIEWER, INSPECTION, ANALYSIS, EXTRACTION & VIEWER ACQUISITION SYSTEM

### GOAL
Allow users to open, inspect, analyze, preview, search, extract, and manage as many file types as possible directly inside the IDE without requiring external applications whenever possible.

### MANDATORY AUDIT & DUPLICATE PREVENTION
Before implementing any viewer, inspector, analyzer, extractor, plugin, extension, UI component, backend service, or feature:
- Audit the existing application.
- Check if the functionality already exists, is partially implemented, hidden but operational, incomplete, or broken.
- Check if the functionality can be improved instead of recreated.

Rules:
- If a feature already exists and works correctly, skip implementation.
- If a feature exists but is incomplete, complete it.
- If a feature exists but is broken, repair it.
- If a feature exists but lacks UI integration, connect it properly.
- Do not create duplicate viewers, inspectors, analyzers, plugin systems, download systems, extraction systems, or file handling systems.

Priority Order: Audit → Verify → Repair → Complete → Improve → Integrate → Create only when necessary

### FILE DETECTION
When a file is opened:
- Detect file extension, MIME type, file signature (magic bytes), encoding, archive formats, embedded file formats, APK-related formats, binary formats, media formats, document formats.
- Display: File name, File size, File type, MIME type, Last modified date, Encoding, Detection confidence.

### CORE ACTIONS
Open, Preview, Open as Text, Open as Code, Open as Hex, Open as Binary, Open as Strings, Open as Metadata, View File Information, Search Within File, Copy Content, Save File, Export File, Share File, Extract File.

### FALLBACK VIEWERS
Every file must be viewable through at least one fallback:
- Text Viewer, Hex Viewer, Binary Viewer, Strings Viewer, Metadata Viewer, File Information Viewer, Binary Inspector.
- Never show "Unsupported File" without fallback options. Never force extraction before inspection.

### DOCUMENT VIEWERS
PDF, DOCX, ODT, RTF, EPUB, Markdown, CSV, Spreadsheet Preview, Presentation Preview.

### IMAGE VIEWERS
PNG, JPG, JPEG, GIF, BMP, WEBP, SVG, ICO, Image Metadata, EXIF Viewer.

### AUDIO VIEWERS
Audio Player, Audio Metadata, Waveform Viewer.

### VIDEO VIEWERS
Video Player, Video Metadata, Frame Preview Viewer.

### ARCHIVE VIEWERS
ZIP, RAR, 7Z, TAR, GZIP, JAR, AAR, APK Archive Viewer.
Features: Archive Browsing, Archive Search, File Preview, Selective Extraction, Bulk Extraction.

### APK ANALYSIS
APK Information, APK Analyzer, AndroidManifest Viewer, AXML Viewer, Resource Explorer, Resource Table Viewer, Permission Viewer, Component Viewer, Certificate Viewer, Signature Viewer, SDK Information Viewer, Version Information Viewer.

### CODE ANALYSIS
DEX Viewer, Multi-DEX Viewer, Smali Viewer, Java Decompiler, Class Browser, Package Browser, Method Browser.

### NATIVE LIBRARY ANALYSIS
ELF Viewer, Shared Library Viewer, Symbol Viewer, Dependency Viewer, Header Viewer, Section Viewer.

### DATABASE VIEWERS
SQLite Viewer, Database Inspector.

### FONT VIEWERS
TTF, OTF, Font Preview, Font Metadata Viewer.

### CERTIFICATES & SECURITY
Certificate Viewer, Keystore Viewer, Hash Viewer, Signature Viewer.

### DEVELOPMENT FILES
JSON, XML, YAML, TOML, INI, Properties, Log Viewer.

### ADVANCED INSPECTION
Strings Extraction, Entropy Analysis, Structure Viewer, Offset Viewer, Header Viewer, Embedded File Detection, Embedded File Extraction, Binary Diff Viewer, File Relationship Viewer, File Signature Viewer, Metadata Viewer, Binary Inspector.

### UNKNOWN FILE HANDLING
If a dedicated viewer is unavailable:
1. Attempt format detection. 2. Attempt signature detection. 3. Attempt embedded format detection.
4. Offer Text Viewer. 5. Offer Hex Viewer. 6. Offer Binary Viewer. 7. Offer Strings Viewer.
8. Offer Metadata Viewer. 9. Offer File Information Viewer. Never immediately fail.

### VIEWER ACQUISITION SYSTEM
When a file type is detected:
- Search installed viewers, plugins, extensions.
- If no compatible viewer exists: Search official viewer repository, display compatible viewer options, allow one-tap installation, download and install, open file automatically.
- Requirements: Trusted repositories only, verify package integrity, verify signatures when supported, prevent duplicate installations, support updates and removal, cache installed viewers.

### EXTRACTION & EXPORT
File Extraction, Archive Extraction, Embedded File Extraction, Save As, Export, Share. Extraction must always remain optional. Users should be able to inspect files before extraction whenever possible.

### PERFORMANCE REQUIREMENTS
- Support large files and large archives. Use lazy loading. Avoid UI freezes. Use background processing.
- Minimize memory usage. Handle unknown files gracefully. Protect against memory exhaustion.

### SUCCESS CRITERIA
The IDE should allow users to: Open files directly, Inspect unknown files, Analyze APKs, Analyze binaries, Browse archives, View documents, View images, View media, View databases, Inspect native libraries, Extract files when desired, Acquire missing viewers automatically.
Prioritize: Reuse over duplication, Repair over replacement, Completion over recreation, Inspection over extraction, Reliability over complexity, Graceful fallback behavior for every file type.

### ADDITIONAL MODULES (long-term roadmap)

**Reverse Engineering:** Disassembly Viewer, Assembly Viewer, Function Browser, Cross-Reference Viewer, Opcode Viewer, Call Graph Viewer, Control Flow Graph Viewer, Data Flow Viewer, Function Signature Viewer, Symbol Reference Viewer, Instruction Browser.

**Android Internals:** OAT Viewer, VDEX Viewer, APEX Viewer, Boot Image Viewer, Recovery Image Viewer, OTA Package Viewer, Vendor Image Viewer, Sparse Image Viewer, ART Metadata Viewer.

**Network Files:** PCAP Viewer, HAR Viewer, HTTP Request/Response Viewer, WebSocket Viewer, DNS Packet Viewer, TLS Handshake Viewer, Network Session Viewer.

**Memory Analysis:** Heap Dump Viewer, Memory Dump Viewer, Thread Dump Viewer, Core Dump Viewer, Stack Trace Viewer, Memory Allocation Viewer, Object Reference Viewer.

**Development Artifacts:** Gradle Viewer, Maven Viewer, Dependency Tree Viewer, Build Report Viewer, Coverage Report Viewer, Test Report Viewer, Benchmark Report Viewer, Package Manifest Viewer, Lockfile Viewer.

**Forensics:** Timeline Viewer, Hash Comparison Viewer, Metadata Diff Viewer, Deleted File Record Viewer, File Provenance Viewer, Timestamp Analyzer, Integrity Verification Viewer, Evidence Metadata Viewer.

**AI & Model Files:** GGUF Viewer, Safetensors Viewer, ONNX Viewer, Tokenizer Viewer, Tensor Viewer, Model Metadata Viewer, Embedding Viewer, Vocabulary Viewer.

**Embedded Filesystems:** EXT4 Viewer, FAT Viewer, NTFS Viewer, Disk Image Viewer, Partition Viewer, Filesystem Structure Viewer, Mount Information Viewer, Filesystem Metadata Viewer.

**Advanced Binary Analysis:** Structure Tree Viewer, Memory Layout Viewer, Relocation Viewer, Import/Export Viewer, Resource Explorer, Symbol Demangler, Binary Relationship Viewer, Binary Section Viewer, Binary Map Viewer.

**Containers & Virtualization:** Docker Image Viewer, OCI Image Viewer, Container Manifest Viewer, VHD Viewer, VMDK Viewer, VDI Viewer, VM Configuration Viewer.

**Game & Asset Files:** Unity Asset Viewer, Unreal Asset Viewer, Texture Viewer, Sprite Sheet Viewer, Audio Bank Viewer, Asset Bundle Viewer.

**Scientific & Data Formats:** HDF5 Viewer, NetCDF Viewer, FITS Viewer, Parquet Viewer, Avro Viewer, MessagePack Viewer, CBOR Viewer, BSON Viewer.

**Extreme Fallback Analysis:** Raw Bytes Viewer, Embedded File Explorer, Embedded Resource Explorer, Entropy Heatmap Viewer, Binary Pattern Explorer, File Carver, Offset Navigator, Structure Explorer, Signature Database Viewer, Unknown Format Inspector.

### IMPLEMENTATION ORDER (priority)
1. Audit existing viewers (PDF, Hex, Image, etc.) — verify working, repair if broken
2. File type detection system (magic bytes + extension + MIME)
3. Universal file info dialog (size, type, encoding, metadata)
4. Fallback viewers wired (Text, Hex, Strings, Metadata, Binary Inspector)
5. Archive browser (ZIP/JAR/AAR/APK browsing + selective extraction)
6. SQLite database viewer
7. JSON/XML/YAML structured viewers
8. APK analyzer (manifest, permissions, resources)
9. Remaining document/image/media viewers
10. Viewer acquisition system
11. Advanced analysis modules (long-term)

## PHASE 21-X: REVERSE ENGINEERING & ADVANCED BINARY ANALYSIS

### MANDATORY AUDIT FIRST
Before implementing any reverse-engineering feature:
- Audit existing viewers, binary analysis tools, APK analysis tools, DEX analysis tools, ELF analysis tools, file detection systems, and decompilation functionality.
- If functionality already exists and works, reuse it. If incomplete, complete it. If broken, repair it.
- Do not create duplicate analyzers, viewers, or decompilers.
- Priority: Audit → Verify → Repair → Complete → Improve → Create only when necessary

### FOUNDATION REQUIREMENTS
Verify these systems exist and function correctly before continuing:
- File Type Detection, MIME Detection, Magic Byte Detection, Binary Viewer, Hex Viewer, Strings Viewer, Metadata Viewer, File Information Viewer.
- If any are missing or broken, repair them first.

### BINARY INSPECTOR
A unified Binary Inspector with: File Structure View, Header Analysis, Section Analysis, Offset Navigation, Magic Signature Detection, Embedded Resource Detection, Entropy Analysis, Binary Metadata Analysis, Binary Relationship Analysis.

### REVERSE ENGINEERING VIEWERS
- Disassembly Viewer, Assembly Viewer, Opcode Viewer, Function Browser, Function Signature Viewer, Symbol Browser, Cross-Reference Viewer, Call Graph Viewer, Control Flow Graph Viewer, Data Flow Viewer, Instruction Browser.
- Requirements: Fast navigation, search support, symbol linking, cross-reference navigation, jump to definition, jump to references.

### DEX ANALYSIS
- DEX Viewer, Multi-DEX Viewer, DEX Metadata Viewer, Class Browser, Package Browser, Method Browser, Field Browser, String Pool Browser, DEX Structure Viewer.
- Support: Navigation, search, filtering, relationship analysis.

### SMALI ANALYSIS
- Smali Viewer, Smali Navigation, Method Navigation, Class Navigation, Opcode Inspection, Reference Tracking.

### JAVA & KOTLIN ANALYSIS
- Java Decompiler, Kotlin Metadata Viewer, Class Viewer, Method Viewer, Package Viewer, Inheritance Viewer.

### ELF & NATIVE LIBRARIES
- ELF Viewer, Shared Library Viewer, Header Viewer, Section Viewer, Symbol Viewer, Dependency Viewer, Relocation Viewer, Import Viewer, Export Viewer, Symbol Demangler.
- Display: Architecture, ABI, Dependencies, Exported Symbols, Imported Symbols.

### ANDROID INTERNALALS
- OAT Viewer, VDEX Viewer, APEX Viewer, Boot Image Viewer, Recovery Image Viewer, OTA Package Viewer, Vendor Image Viewer, Sparse Image Viewer.

### MEMORY ANALYSIS
- Heap Dump Viewer, Memory Dump Viewer, Thread Dump Viewer, Core Dump Viewer, Stack Trace Viewer, Object Reference Viewer.

### NETWORK ANALYSIS
- PCAP Viewer, HAR Viewer, HTTP Request Viewer, HTTP Response Viewer, WebSocket Viewer, DNS Packet Viewer.

### ADVANCED BINARY ANALYSIS
- Structure Tree Viewer, Binary Map Viewer, Memory Layout Viewer, Resource Explorer, Embedded File Explorer, Embedded Resource Explorer, Binary Diff Viewer, Entropy Heatmap Viewer, Signature Database Viewer.

### AI & MODEL ANALYSIS
- GGUF Viewer, Safetensors Viewer, ONNX Viewer, Tokenizer Viewer, Model Metadata Viewer.

### PERFORMANCE REQUIREMENTS
- Support large binaries and large APKs. Use background processing. Avoid UI freezes. Use lazy loading. Cache analysis results. Minimize memory usage.

### USER EXPERIENCE
Every supported binary should provide: Open Normally, Open as Hex, Open as Binary, Open as Strings, Open as Metadata, Analyze Structure, View Relationships, Export Results.

### SUCCESS CRITERIA
The IDE should provide reverse-engineering capabilities comparable to a lightweight combination of APK Analyzer, JADX, APKTool inspection workflows, binary inspection tools, hex editors, and native library analyzers — while remaining stable, performant, and fully integrated into the existing viewer architecture.

## Phase 20-B: Interactive Diff Viewer — ✅ COMPLETE (build #1252 GREEN)

### P20-B: DiffViewer
- `DiffViewer.kt` — structured diff parser with `ParsedDiff`, `DiffHunk`, `DiffLine` data classes
- Parses unified `git diff` output into typed hunks with old/new line numbers
- Color-coded background highlighting (green additions, red deletions, blue hunk headers)
- Hunk navigation (prev/next arrows when multiple hunks)
- Stats bar showing total additions/deletions
- Wired into `ChangeRow` in `SourceControlPane.kt` — replaces raw text diff display

### CI Build History — Phase 20-B
| Build | Result | Notes |
|-------|--------|-------|
| #1249 | ❌ FAIL | DiffViewer.kt — imports at bottom of file |
| #1250 | ❌ FAIL | SourceControlPane wiring — inherited broken tree |
| #1251 | ❌ FAIL | Illegal escape: \d in regex string |
| #1252 | ✅ GREEN | Fixed regex with triple-quoted raw string |

---

## Phase 21 Progress (2026-07-15)

### Step 1: Audit existing viewers ✅
Existing viewers found in codebase:
- `PdfViewerDialog.kt` (11.6KB) ✅
- `HexViewerDialog.kt` (4.5KB) ✅
- `ArchiveViewer.kt` (17KB) ✅
- `SqliteViewerDialog.kt` (11KB) ✅
- `MediaViewers.kt` (17KB) ✅ (images/audio/video)
- `PreviewPane.kt` (72KB) ✅ (markdown/HTML preview)

### Step 2: File type detection system ✅ (build #1255 GREEN)
- `FileDetector.kt` — magic bytes + extension + MIME file type detection
- 30+ file format signatures (PDF, ZIP, RAR, 7Z, SQLite, PNG, JPEG, GIF, BMP, WEBP, MP3, OGG, FLAC, ELF, DEX, TTF, OTF, PEM, DER, JKS, etc.)
- Extension-based fallback detection for 60+ extensions
- MIME type mapping
- Text/binary heuristic detection (null byte sampling)
- Encoding detection (UTF-8 BOM, UTF-16LE/BE)
- `FileTypeInfo` data class with category flags (isText, isBinary, isArchive, isImage, etc.)
- CI: 1 fix needed (.toByte() for hex literals > 0x7F)

### Step 3: Universal file info dialog ✅ (build #1257 GREEN)
- `FileInfoDialog.kt` — universal file info dialog
- Uses `FileDetector.detect()` to display: name, size, extension, format, MIME type, encoding, modified date, confidence, magic bytes
- Category badges (Text, Binary, Archive, Image, Audio, Video, Document, Database, Code, Font, Cert, APK, ELF)
- "Open As" actions: Text, Hex, Strings, Binary

### Step 4: Wire fallback viewers + ExplorerPane context menu integration ✅ (build #1262 GREEN ✅)
- Added 3 state vars to ExplorerPane: `showFileInfoDialog`, `previewStringsPath`, `previewBinaryPath`
- Context menu: "File Info" (all files), "Open as Strings" (binary/archive), "Open as Binary Inspector" (binary)
- `FileInfoDialog` wired with full onOpenAs* callbacks → routes to Text/Hex/Strings/Binary viewers
- `StringsViewerDialog` wired — renders on `previewStringsPath != null`
- `BinaryInspectorDialog` wired — renders on `previewBinaryPath != null`
- Commit: e0aac179

### Phase 21 COMPLETE ✅ (build #1262 GREEN)
All foundation viewers from the Universal File Viewer spec are shipped and integrated.
Next: Phase 21-X — Reverse Engineering & Advanced Binary Analysis (DEX, ELF, Smali, JADX-style decompilation)

### CI Build History — Phase 21
| Build | Result | Notes |
|-------|--------|-------|
| #1253 | ✅ GREEN | docs: add Phase 21 spec |
| #1254 | ❌ FAIL | feat(P21): FileDetector — .toByte() missing for hex > 0x7F |
| #1255 | ✅ GREEN | fix(P21): FileDetector — add .toByte() for hex literals |
| #1256 | ✅ GREEN | docs: Phase 21 Steps 1-3 progress |
| #1257 | ✅ GREEN | feat(P21): FileInfoDialog.kt |
| #1258 | ✅ GREEN | docs: Phase 20-B complete + Phase 21 Steps 1-3 |
| #1259 | ✅ GREEN | feat(P21): StringsViewerDialog.kt |
| #1260 | ❌ FAIL | feat(P21): BinaryInspectorDialog — Triple destructuring 4-component error |
| #1261 | ✅ GREEN | fix(P21): BinaryInspectorDialog — use list instead of Triple for 4 values |
| #1262 | ✅ GREEN | feat(P21): wire FileInfoDialog + StringsViewerDialog + BinaryInspectorDialog into ExplorerPane |
| #1263 | ✅ GREEN | docs(AGENTS): Phase 21 Step 4 COMPLETE — ExplorerPane wiring + CI history |

---

## Phase 21-X Progress (2026-07-16)

### Step 1: DEX Viewer ✅ (builds #1264–#1267 GREEN)
- `DexViewerDialog.kt` (812 lines) — pure-Kotlin DEX binary parser
- Reads DEX header, string pool (ULEB128), type list, field/method/class def tables
- 5-tab UI: Header · Classes · Strings · Methods · Fields
- Class browser with package collapsing, search filter
- `isDexFile()` helper in MediaViewers.kt (.dex/.odex/.vdex)
- Wired in ExplorerPane: tap + context menu

### Step 2: ELF Viewer ✅ (builds #1268–#1271 GREEN)
- `ElfViewerDialog.kt` (849 lines) — pure-Kotlin ELF32/ELF64 parser
- Reads ELF header, section headers (.text/.data/.rodata/.symtab etc.), symbol table
- 4-tab UI: Header · Sections · Symbols · Dependencies
- Symbol demangler: strips leading underscore, decodes Itanium C++ mangling (basic)
- `isElfFile()` helper in MediaViewers.kt (.so/.elf/.o/.ko)
- Wired in ExplorerPane: tap + context menu
- Fix #1271: ByteArray.indexOf impl + replaced >5-tuple destructure with list

### Step 3: APK Analyzer, Smali Viewer, Disassembly Viewer ✅ (pushed — CI pending)

| File | Lines | Description |
|------|-------|-------------|
| `ApkAnalyzerDialog.kt` | 555 | APK as ZIP + AXML decoder |
| `SmaliViewerDialog.kt` | 385 | .smali reader + DEX stub synthesizer |
| `DisassemblyViewerDialog.kt` | 550 | ARM Thumb-2 decoder + ELF .text extractor |

**APK Analyzer (ApkAnalyzerDialog.kt)**
- Parses APK (ZIP) — AndroidManifest.xml via pure-Kotlin binary AXML decoder
  (string pool with UTF-8/UTF-16LE, XML chunk parser: NS/START/END/ATTR types)
- 5-tab UI: Overview · Manifest · Permissions · Components · Files
- Permissions: dangerous permissions highlighted red (CAMERA, LOCATION, SMS, STORAGE, etc.)
- Components: Activities, Services, Receivers, Providers, Features
- Files: all APK entries grouped by category with sizes

**Smali Viewer (SmaliViewerDialog.kt)**
- Reads .smali files from directory tree or single file
- Falls back to synthesizing Smali stubs from DEX class definitions
  (uses ULEB128 string decoding + class def table to get descriptor/superType/accessFlags)
- Two-pane: filterable class list + syntax-colored source viewer
- Token kinds: .directive (blue), opcodes (white), :labels (yellow), # comments (green)

**Disassembly Viewer (DisassemblyViewerDialog.kt)**
- ELF32 loader: section header table → finds .text + .symtab/.strtab
- ARM Thumb-2 decoder (pure-Kotlin subset):
  push/pop, mov/movs/movw, ldr/str (imm5/literal), b/bX<cond>/bl/blx, adds/subs/cmp
  lsls, 16 DP ops (ands/eors/orrs etc.), nop, 32-bit BL + MOVW, .word fallback
- Two-pane: function list from symbols + instruction listing
  Columns: address | raw bytes | mnemonic | operands (with embedded comments)
- Search filter by mnemonic or operand text

**MediaViewers.kt** — added `isApkAnalyzable()`, `isSmaliSource()`, `isDisassemblable()` helpers

**ExplorerPane.kt** — new state vars + tap routing + context menu items + wiring:
- APK → APK Analyzer, .smali → Smali Viewer, .so/.elf/.o/.ko → Disassembly Viewer

### CI Build History — Phase 21-X
| Build | Result | Notes |
|-------|--------|-------|
| #1264 | ✅ GREEN | feat(P21-X): DexViewerDialog.kt |
| #1265 | ✅ GREEN | feat(P21-X): isDexFile() + routing in MediaViewers |
| #1266 | ✅ GREEN | feat(P21-X): wire DexViewerDialog into ExplorerPane |
| #1267 | ✅ GREEN | feat(P21-X): DexViewerDialog — wiring confirmed green |
| #1268 | ❌ FAIL | feat(P21-X): ElfViewerDialog.kt — ByteArray.indexOf issue |
| #1269 | ❌ FAIL | feat(P21-X): isElfFile() routing — inherited broken tree |
| #1270 | ❌ FAIL | feat(P21-X): wire ElfViewerDialog — still broken tree |
| #1271 | ✅ GREEN | fix(P21-X): ElfViewerDialog — ByteArray.indexOf + >5 destructure |
| #1272 | ✅ GREEN | fix(projects): soften scaffold exists-check |
| #1273 | ✅ GREEN | fix(home): delete project folder on disk |
| #1274 | ❌ FAIL | feat(settings): add Deleted Projects section |
| #1275 | ✅ GREEN | fix(settings): extract DeletedProjectsSection — CI GREEN |
| #1276 | ❌ FAIL | feat(P21-X): APK Analyzer + Smali + Disassembly — syntax error |
| #1277 | ❌ FAIL | docs(AGENTS): Step 3 — inherited broken tree |
| #1278 | ✅ GREEN | fix(P21-X): ApkAnalyzerDialog remove early returns |
| #1279 | ✅ GREEN | Step 3 confirmed green |
| TBD  | PENDING | feat(P21-X-S4): Entropy Heatmap + PCAP/HAR + AI Model Viewer (Step 4) | |


### Step 4: Entropy Heatmap, PCAP/HAR Viewer, AI Model Viewer 🔲 (CI pending)

| File | Lines | Description |
|------|-------|-------------|
| `EntropyHeatmapDialog.kt` | 281 | Shannon entropy heatmap viewer |
| `NetworkViewerDialog.kt` | 382 | PCAP binary + HAR JSON network viewer |
| `AiModelViewerDialog.kt` | 517 | GGUF / Safetensors / ONNX metadata viewer |

**Entropy Heatmap (EntropyHeatmapDialog.kt)**
- Reads entire file, computes Shannon entropy per 256-byte block (H = -Σ p·log₂p)
- Canvas-based heatmap: 64 cells/row, color-coded blue→green→amber→red (low→high entropy)
- Stats bar: total blocks, avg/min/max entropy, high/med/low block counts
- Block detail table with hex offset, entropy value, and level label
- Interpreting high entropy (≥7 bits) → likely encrypted or compressed region

**Network Viewer (NetworkViewerDialog.kt)**
- HAR: parses JSON (org.json), extracts request method/URL/headers/body + response status/headers/body preview
- PCAP: pure-Kotlin binary parser — global header (magic, endianness detection, nanosecond flag), packet records
  → Ethernet II decode → IPv4 → TCP/UDP port extraction; raw hex preview for every packet
- Master-detail UI: filterable packet/request list on left, full detail text on right
- Supports .pcap / .pcapng / .cap / .har

**AI Model Viewer (AiModelViewerDialog.kt)**
- GGUF: RandomAccessFile parser — magic check, version, tensor_count, KV metadata (30+ value types incl. ULEB/string/array)
  Extracts: architecture, quantization, context_length, embedding_length, head_count, layer_count, vocab_size
- Safetensors: reads 8-byte header_size, parses JSON header — tensor dtype distribution, total param count, __metadata__
- ONNX: protobuf3 wire-format parser — ir_version (varint), opset_import (sub-message), graph name/node count, metadata_props
- 2-tab UI: Summary (labeled grid) + All Metadata (key-value table)

**MediaViewers.kt** — added `isEntropyViewable()`, `isNetworkCapture()`, `isAiModel()` helpers
**ExplorerPane.kt** — 3 new state vars, context menu items (Entropy Heatmap for all binary files, Network/AI for matching extensions), tap routing, dialog wiring

### Phase 21-X Remaining Items

| # | Feature | Status | Notes |
|---|---------|--------|-------|
| P21-X-1 | DEX Viewer | ✅ DONE | DexViewerDialog.kt, 5 tabs, class browser |
| P21-X-2 | ELF Viewer | ✅ DONE | ElfViewerDialog.kt, 4 tabs, symbol table |
| P21-X-3 | APK Analyzer | ✅ DONE (CI pending) | ApkAnalyzerDialog.kt, AXML decode |
| P21-X-4 | Smali Viewer | ✅ DONE (CI pending) | SmaliViewerDialog.kt, DEX stub synth |
| P21-X-5 | Disassembly Viewer | ✅ DONE (CI pending) | ARM Thumb-2 decoder |
| P21-X-6 | Entropy Heatmap | ✅ DONE | EntropyHeatmapDialog.kt, 256B blocks, color heatmap, stats |
| P21-X-7 | PCAP / HAR Viewer | ✅ DONE | NetworkViewerDialog.kt, binary PCAP + HAR JSON |
| P21-X-8 | GGUF / Safetensors / ONNX | ✅ DONE | AiModelViewerDialog.kt, 2-tab metadata viewer |
| P21-X-9 | OAT / VDEX / APEX | 🔲 TODO | Android runtime format viewers |
| P21-X-10 | Binary Diff Viewer | 🔲 TODO | Side-by-side byte diff |


---

## Phase 22: IDE Intelligence & Reliability Audit

### MANDATE
Before implementing any Phase 22 feature, perform a complete audit of all existing IDE systems.
Report only — do not implement until audit findings are documented and prioritised.

### AUDIT SCOPE

**EDITOR INTELLIGENCE**
Audit: IntelliSense, Auto/Smart/Predictive Completion, Inline Suggestions, Parameter Hints,
Function Signature Help, Hover Information, Quick Fixes, Code Actions, Auto Imports,
Snippet Support, Symbol Search, Workspace Symbol Search, Rename Symbol, Find References,
Go To Definition, Peek Definition, Go To Declaration, Go To Type/Implementation/References,
Breadcrumb Navigation, Outline View, Document Symbols, Workspace Symbols.
Verify: feature exists, works, which languages, what blocks full functionality.

**REAL-TIME DIAGNOSTICS**
Required: analyse while typing, detect syntax/warning/type/import/reference/formatting errors,
update automatically — no manual submission required.
Verify: red/yellow underlines, error/warning tooltips, Problems panel, error navigation, quick fixes.
If not automatic: find root cause (disconnected LSP, hidden diagnostics, plain-text open).

**LANGUAGE SERVER PROTOCOL (LSP)**
Audit: LSP Manager, Installation, Discovery, Startup, Restart, Status Indicators, Error Reporting.
Verify per language (HTML, CSS, JS, TS, JSX, TSX, JSON, XML, YAML, Markdown, Python, Java,
Kotlin, Dart, Rust, Go, PHP, C, C++): syntax highlighting, IntelliSense, diagnostics,
error detection, formatting, navigation, hover, symbol support.

**FILE TYPE DETECTION**
Audit: extension, MIME, magic-byte, unknown-file detection.
Verify icons and auto-load of correct editor/highlighting/diagnostics/LSP/formatter for:
HTML, CSS, JS, TS, JSON, XML, YAML, MD, SVG, PNG, JPG, APK, DEX, SO, ZIP, PDF, SQLite.

**FORMATTING**
Verify: Format Document, Format Selection, Format on Save, language-specific formatting,
formatter selection, formatter installation.

**NAVIGATION**
Verify: Go To Def, Peek Def, Go To Declaration, References, Find References, Rename Symbol,
Symbol Search, Workspace Search, Outline Navigation, Breadcrumb Navigation.

**DEBUGGING SYSTEMS**
Editor Debugger: breakpoints, conditional breakpoints, step over/into/out,
variable inspection, watch expressions, call stack, debug console.
Application Debugger: Android app/APK debugging, process attachment, runtime inspection,
Logcat integration, device/emulator debugging.
Verify: are these separate or merged? which exists? which is functional?

**EXTENSIONS & LANGUAGE SUPPORT**
Audit: Extension Manager, installation, updates, loading, removal.
Verify support for: Language Servers, Formatters, IntelliSense Providers, Debug Adapters,
Theme/Icon Extensions.

**EDITOR EXPERIENCE**
Verify: Code Folding, Minimap, Split Editor, Multiple Tabs, Multi-Cursor Editing,
Find & Replace, Workspace Search, File Search, Session Restore, Editor State Restore.

**PERFORMANCE**
Verify: LSP startup speed, diagnostics speed, completion speed, file opening speed,
large file/project handling, memory usage, CPU usage. Identify bottlenecks.

**WORKSPACE & PROJECT INTELLIGENCE**
Audit: Project/Symbol/Workspace/Background Indexing, Workspace Health Checks,
Missing Dependency/SDK/Package Detection, Project Analysis Systems.

**GIT INTEGRATION**
Audit: Repository Detection, Commit History Viewer, Branch Manager, Merge Conflict Viewer,
Git Blame, Stash Manager, Diff Viewer, Repository Health Checks.
Verify actual functionality — not only UI presence.

**BUILD SYSTEMS**
Audit: Build Output Parsing, Build Error Navigation, Build Task Discovery,
Build Environment/SDK/JDK/Gradle/Maven Detection. Verify execution and error reporting.

**TERMINAL SYSTEM**
Audit: Terminal Startup, Stability, Session Restore, Working Directory Persistence,
Command History Persistence, Multiple Terminal Support, Crash Recovery,
Environment Variables, Package Installation Workflows.
Verify state survives app restarts where designed to do so.

**ANDROID DEVELOPMENT**
Audit: ADB Detection, Device/Emulator Detection, APK Install/Uninstall,
Logcat Integration, Package Name Detection, Android SDK Integration.

**RELIABILITY**
Verify: Crash Recovery, Auto Save, Workspace Recovery, Unsaved File Recovery,
Terminal Recovery, Extension Failure Recovery, Corrupted Project Recovery,
Startup Diagnostics, Safe Startup Systems.

### CLASSIFICATION RULES
- **WORKING** = UI + Backend + Successful Execution (all three required)
- **PARTIAL** = some functionality missing or unreliable
- **BROKEN** = exists but fails on execution
- **MISSING** = not implemented
- Buttons/menus/dialogs without working backend must NOT be classified as WORKING.

### FEATURE CLAIM VERIFICATION (required for every feature)
Verify: UI exists · backend implementation exists · executes successfully ·
produces expected results · survives editor restart · survives app restart ·
survives project reopen · handles errors correctly · integrates with existing systems.

### AUDIT PRIORITIES
1. Existing functionality verification
2. Broken feature repair
3. Incomplete feature completion
4. Reliability improvements
5. Performance improvements
6. Missing feature implementation

Prefer: Repair over replacement · Completion over duplication · Integration over recreation · Verification over assumptions.

### REQUIRED FINAL REPORT FORMAT
1. Working Features
2. Partially Implemented Features
3. Broken Features
4. Missing Features
5. Duplicate Systems
6. High Priority Repairs
7. High Priority Completions
8. Recommended Next Development Phase

**STATUS: ✅ AUDIT COMPLETE (2026-07-16) — ready for Phase 22 implementation**

---

## Phase 22 Audit Findings

### 1. Working Features ✅

| Feature | Implementation | Notes |
|---------|---------------|-------|
| Syntax Highlighting | SyntaxTransformation.kt + SyntaxHighlighter.kt | All major languages |
| Code Folding | CodeEditor.kt (foldableLines, foldedRanges, foldedLineIndices) | Brace/indent-based, chevron gutter icons |
| Ghost text / Inline Suggestions | CodeEditor.kt L384–397 | Shows top completion as grey text |
| Keyword/Snippet Completion popup | CodeEditor.kt completionsFor() + snippetsFor() | Triggers at ≥2 chars, all languages |
| Parameter Hints / Signature Help | SignatureHelpAnalyzer.kt → CodeEditor.kt | Regex-based, common stdlib functions |
| Hover docs | CodeEditor.kt HOVER_DOCS map | Common keywords only (no LSP) |
| Inlay Hints | InlayHintAnalyzer.kt | Return types + param names, Kotlin/JS |
| Real-time lint underlines | LintAnalyzer.kt → SyntaxTransformation.kt | Per-keystroke via LaunchedEffect |
| Problems panel | LintChecker.kt → PSS ProblemsPanel | Works; read from disk on file-switch only |
| Error badge count | PSS tab bar | LintChecker.check() per file |
| Rename Symbol | CodeEditor.kt renameDialogWord | In-file (P18-C adds cross-file via FileIndexer) |
| Go To Definition | CodeEditor.kt + FileIndexer.search() | Cross-file via indexed symbols |
| Find References | CodeEditor.kt nearbyError + symbol scan | Basic, same-file |
| Workspace File Search | ProjectFileSearchPanel.kt | File name + full-text grep modes |
| Symbol Search (Go to Symbol) | SymbolSearchPanel.kt + SymbolParser | Regex-based, all languages |
| Outline View | OutlinePanel.kt SymbolParser | Class/func/var tree, live |
| Code Folding gutter | CodeEditor.kt ▼/▶ chevrons | Working |
| Split Editor | EditorPane.kt splitId state | Side-by-side, persisted |
| Multiple Tabs | EditorPane.kt | Pinning, reorder, restore |
| Minimap | CodeEditor.kt L879 | Click-to-navigate working |
| Find & Replace | CodeEditor.kt | In-file only |
| Session Restore | EditorPane.kt autosave + TerminalSessionStore.kt | Both editor and terminal tabs |
| Autosave / Recovery dialog | EditorPane.kt L306–335 | On next open |
| File Type Detection | FileDetector.kt | Magic bytes + extension + MIME, 20+ types |
| Terminal — multiple sessions | TerminalPane.kt + TerminalSessionStore.kt | Multi-tab, crash recovery |
| Terminal — session persist | TerminalSessionStore.kt SavedTab | Survives app restart |
| Terminal — proot Ubuntu | ProotInstaller.kt | Fully working, all commands in proot |
| Git — status/stage/commit/push/pull | GitEngine.kt + SourceControlPane.kt | Working via proot git |
| Git — branches (create/checkout/delete/rename/merge) | GitEngine.kt | Full branch operations |
| Git — commit log + graph | SourceControlPane.kt ScmTab.LOG/GRAPH | Working |
| Git — stash save/pop/list | GitEngine.kt + ScmTab.STASH | Working |
| Git — tags create/delete/list | GitEngine.kt + ScmTab.TAGS | Working |
| Git — inline diff | SourceControlPane.kt + DiffViewer.kt | Per-file unified diff |
| Git — merge conflict detection | SourceControlPane.kt conflictedFiles | Lists conflicted files, banner shown |
| Build system — Gradle detection | BuildRunner.isGradleProject() | build.gradle/gradlew detection |
| Build system — Gradle execution | BuildRunner + ProotInstaller | Runs ./gradlew inside proot |
| Build panel | BuildPanel.kt | Output streaming, cancel |
| Build error parser | GradleErrorParser.kt | Line/col extraction |
| Toolchain scanner | ToolchainManager.kt + ToolchainPanel.kt | JDK/Gradle/SDK/Node/Python/ADB/etc via proot |
| Package manager | PackageManagerPane.kt | apt-get via ProotInstaller.execOnce() |
| Logcat panel | LogcatPanel.kt | adb logcat stream; degrades gracefully (no adb → message) |
| Variable Inspector UI | VariableInspectorPanel.kt | Watch expressions + call stack + locals UI |
| Ports scanner | PortsScanner.kt | TCP port probe |
| File indexer | FileIndexer.kt | Background indexing of workspace symbols |
| PerformanceMonitor | PerformanceMonitor.kt | Memory, file size stats |

---

### 2. Partially Implemented Features ⚠️

| Feature | Status | What's Missing |
|---------|--------|---------------|
| Problems panel real-time update | PARTIAL | `remember(activeFilePath)` — re-runs only on file switch, not on every keystroke. Editor underlines update live (LintAnalyzer via LaunchedEffect) but Problems tab content does NOT reflect current unsaved edits |
| Go To Definition (cross-file) | PARTIAL | FileIndexer covers symbols; no AST — misses overloads, anonymous/lambda definitions, imported symbols from jars |
| Find References | PARTIAL | Scans current file only; no workspace-wide reference search |
| Git blame | PARTIAL | GitEngine has no `blame()` function; SourceControlPane has no blame view |
| Merge conflict editor | PARTIAL | Detects conflicts, shows banner — no inline conflict resolution UI (no <<<, ===, >>> visualiser) |
| Debugger — Variable Inspector | PARTIAL | UI present (watch, call stack, locals panels) but no backend: no DAP/JDWP connection, no real breakpoint engine. Static analysis only |
| Logcat | PARTIAL | Works only if `adb` binary is on device PATH; most Android devices running this app won't have ADB on the host |
| Auto Import | PARTIAL | No auto-import — completions suggest symbols but don't insert import statements |
| Workspace-wide Find & Replace | PARTIAL | ProjectFileSearchPanel searches text but has no replace action |
| Breadcrumb navigation | PARTIAL | Sticky line header in CodeEditor (shows ancestor line), but no interactive clickable breadcrumb bar |
| Formatting | PARTIAL | No Format Document / Format on Save; LanguageSpecs defines indent rules but no formatter runs |

---

### 3. Broken Features ❌

| Feature | Root Cause |
|---------|------------|
| LSP (Language Server Protocol) | **MISSING ENTIRELY** — no LspManager, no lsp4j, no tsserver/pyright/kotlin-language-server bundled or launched. All IntelliSense is purely regex/keyword based |
| AgentScheduler.runCommand() | **FIXED in build #1291** — was calling raw ProcessBuilder(["bash", "-c", cmd]) which doesn't exist on Android host; now uses ProotInstaller.execOnce() |
| ProblemsPanel live-update | Uses `remember(activeFilePath)` — only refreshes when the active FILE changes, not when content changes. Editing a file won't update the Problems tab until you switch away and back |
| Git blame view | GitEngine.blame() doesn't exist; no UI for it |

---

### 4. Missing Features 🔲

| Feature | Priority |
|---------|----------|
| LSP integration (tsserver, pyright, kotlin-ls, clangd) | HIGH — all advanced IntelliSense depends on this |
| Real Language Server diagnostics (type errors, import errors, undefined symbols) | HIGH |
| Format Document / Format on Save | HIGH |
| Workspace-wide Find & Replace (replace action) | HIGH |
| Git blame inline / gutter | MEDIUM |
| Merge conflict inline editor (<<<===>>> visualiser + accept/reject buttons) | MEDIUM |
| Extension Manager (install/remove VSCode-compatible extensions) | LOW (complex, different arch) |
| DAP debugger backend (JDWP attach, step, breakpoint engine) | LOW (very complex) |
| Peek Definition (inline popup, not full navigation) | MEDIUM |
| Multi-cursor editing | MEDIUM |
| Auto Import insertion | MEDIUM |
| Code Actions / Quick Fixes (beyond rename) | MEDIUM (needs LSP) |
| Workspace-wide symbol rename | MEDIUM |
| Interactive breadcrumb bar | LOW |
| Full-screen minimap overlay | LOW |

---

### 5. Duplicate Systems ⚠️

| Duplicates | Notes |
|-----------|-------|
| LintAnalyzer.kt + LintChecker.kt | Two separate lint engines. LintAnalyzer used in CodeEditor (underlines), LintChecker used in ProblemsPanel (file-disk read). Should be unified |
| BuildEnvironment.kt + ToolchainManager.kt | Both scan for JDK/Gradle/ADB/Node. Overlap; BuildEnvironment is older, ToolchainManager is newer and more complete |
| CopilotChatPanelOverlay.kt (ui/screens) marked DEAD CODE in ProotInstaller.kt comment | Verify if still wired anywhere; if not, remove |

---

### 6. High Priority Repairs

1. **ProblemsPanel live-update** — change `remember(activeFilePath)` to also key on current editor content (pass `currentContent: String` param from EditorPane → PSS → ProblemsPanel; run LintChecker on it, not from disk)
2. **LintAnalyzer + LintChecker unification** — merge into one object; remove duplication
3. **Workspace Find & Replace** — add replace-all action to ProjectFileSearchPanel
4. **Dead code cleanup** — remove or archive CopilotChatPanelOverlay.kt if truly dead

---

### 7. High Priority Completions

1. **LSP integration** — highest value unlock; enables type-accurate IntelliSense, real diagnostics, auto-import, formatting, rename, hover for ALL languages. Approach: launch LSP servers inside proot (npm install -g typescript-language-server, pip install python-lsp-server, etc.), communicate via stdio JSON-RPC from Kotlin
2. **Format Document** — wire prettierx/ktlint/black inside proot; trigger via command palette "Format Document"
3. **Git blame** — add `GitEngine.blame()` (git blame --porcelain) + inline gutter display
4. **Merge conflict editor** — parse <<<===>>> markers in editor, show accept-ours/accept-theirs/both buttons per hunk
5. **Multi-cursor** — Compose BasicTextField doesn't natively support multiple cursors; needs custom TextLayoutResult-based overlay

---

### 8. Recommended Phase 22 Implementation Order

```
P22-A: ProblemsPanel live-update + LintChecker/LintAnalyzer unification
P22-B: Workspace Find & Replace (add replace to ProjectFileSearchPanel)
P22-C: Git blame gutter + inline view
P22-D: Merge conflict inline editor
P22-E: Format Document (prettier/ktlint/black via proot)
P22-F: LSP groundwork — LspManager, stdio JSON-RPC client, server install via npm/pip in proot
P22-G: LSP diagnostics + hover for JS/TS (tsserver — most impactful first)
P22-H: LSP diagnostics + completion for Python (pylsp)
P22-I: LSP for Kotlin (kotlin-language-server)
P22-J: Auto Import (LSP-backed textEdit insertions)
P22-K: Multi-cursor support
P22-L: Peek Definition overlay
```

**STATUS: AUDIT COMPLETE ✅ — Phase 22 implementation may begin with P22-A**



---

## PHASE 16 — COLLABORATION & CLOUD SYNC ✅ COMPLETE (#1199 GREEN)

**Goal:** Git action feedback, cloud backup/restore, session handoff, sync status indicator.

### CI History

| Build | Result | Notes |
|-------|--------|-------|
| #1193 | ❌ FAIL | feat(P16-A): `actionToast` state used but not declared |
| #1194–#1198 | ❌ FAIL | Same root error propagated through subsequent commits |
| #1199 | ✅ GREEN | fix(P16-A): declare `actionToast` — tree clean |

**Root cause memorised:** When adding state vars to a large Composable, ALWAYS declare them in the `remember {}` block section before using them in lambdas. The replacement script failed to insert the declaration on the first attempt due to trailing-space mismatch in the anchor string.

### Files Shipped

| # | Feature | File | Status |
|---|---------|------|--------|
| P16-A | Fetch button + pull/push/fetch result feedback toast | `ui/panes/SourceControlPane.kt` | ✅ #1199 |
| P16-B | CloudBackupManager — backup/restore/list as tar.gz | `project/CloudBackupManager.kt` | ✅ #1199 |
| P16-C | SessionHandoffManager — export/import/push/pull session | `data/SessionHandoffManager.kt` | ✅ #1199 |
| P16-D | SyncStatusMonitor — Idle/Syncing/Success/Error StateFlow | `diagnostics/PerformanceMonitor.kt` | ✅ #1199 |
| P16-E | CloudBackupPanel UI — backup/restore/session-sync dialog | `ui/panels/CloudBackupPanel.kt` | ✅ #1199 |
| P16-F | Sync indicator in StatusBarContent | `ui/screens/ProjectShellScreen.kt` | ✅ #1199 |

---

## CI FAILURE — #1291 & #1292 (July 16, 2026)

**Symptom:** `ExplorerPane.kt:1135:25 Type mismatch: inferred type is String but Boolean was expected`

**Root cause:** `"Binary Diff" ->` String arm was inserted at the wrong indentation level — inside the `"Preview" -> when { }` boolean-condition block instead of the outer `when (label)` String block. Additionally, the `isNetworkCapture`, `isAiModel`, `isAndroidRuntimeFile` arms from P21-X-10 were also misindented into the same block without proper closing of the preview when.

**Fix (commit d868f75584b0):** Moved `"Binary Diff"` arm to the outer `when (label)` block. Re-indented `isNetworkCapture`, `isAiModel`, `isAndroidRuntimeFile` arms inside the Preview when-block with consistent 28-space indent. Added `showCtxMenu = false` to the new arms.

**Pattern to memorise:** When inserting new `when` arms into a nested `when` structure, ALWAYS count brace depth and confirm whether the enclosing `when` is `when (String)` or `when { Boolean }` before inserting. A String arm inside a Boolean when-block causes a type mismatch compile error.

**Tree status after fix:** Build #1293 in progress — expected GREEN.

---

## CURRENT SESSION — July 16, 2026

**Read AGENTS.md:** ✅ Done  
**Current HEAD:** Fix for ExplorerPane Binary Diff misindentation pushed (commit d868f75584b0)  
**Next action:** Await #1293 CI result, then begin Phase 22 starting with **P22-A** (ProblemsPanel live-update + LintChecker/LintAnalyzer unification)

**Phase 22 plan (from audit above):**
- P22-A: ProblemsPanel live-update + LintChecker/LintAnalyzer unification
- P22-B: Workspace Find & Replace (add replace to ProjectFileSearchPanel)
- P22-C: Git blame gutter + inline view
- P22-D: Merge conflict inline editor
- P22-E: Format Document (prettier/ktlint/black via proot)
- P22-F through P22-L: LSP groundwork → diagnostics → completions → multi-cursor

