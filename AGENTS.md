# AI Agent / Copilot — Project Context
> Read this FIRST before touching any code. Updated June 28, 2026.

---

## What This Project Is

**Visual Node Code** (display name) / **CodeSpace IDE** (package) — a VS Code-style Android IDE with built-in Ubuntu Linux terminal powered by proot. No root needed.

- **Package:** `com.codespace.ide.debug`
- **Repo:** `wisdom131-max/codespace-ide-mobile`
- **APK:** `app-prod-arm64-v8a-debug.apk`
- **Device:** TECNO KL4, Android 14, Samsung kernel 5.15.180-android13, arm64-v8a, 3GB RAM
- **Build:** GitHub Actions auto-builds on every push (~6-8 min)

---

## Communication Rules
- Short, direct answers. No fluff.
- Always say WHERE to paste code (Codespace terminal vs app bash tab vs Ubuntu tab).
- Never repeat a failed approach without explaining what changed.
- Check the file before patching. Read the whole relevant function, not just one line.
- Always say what a fix will do before applying it.
- `find /data/app` is PERMISSION DENIED on device. Use `unzip -l` on APK instead.
- Use `[ -f path ]` not `ls` in app bash tab.
- Always `git pull --rebase && git push`.
- NEVER load large files with `readBytes()` — OOM on 3GB device. Stream instead.
- Before patching, grep the whole codebase for any other references to functions you rename or remove.

---

## THREE SHELL ENVIRONMENTS — never confuse them
1. **Codespace terminal** (browser or `gh cs ssh`) — all code edits
2. **App bash tab** — ash shell via `libbusybox.so`
3. **Ubuntu tab inside the app** — Ubuntu/proot environment

---

## SAMSUNG KERNEL RESTRICTION — ROOT CAUSE OF ALL dpkg FAILURES

Device kernel 5.15.180-android13 blocks these syscalls inside proot ptrace:
- `chdir` / `getcwd` — every `cd` fails with ENOSYS (38)
- `fork` + `execve` for child processes — dpkg, tar, python all fail
- `setresuid` — fixed via `APT::Sandbox::User "root"`

What works: bash, file reads/writes, network, single-process commands.
What does not work: any command spawning subprocesses (dpkg, tar, python, ar), any `cd` command.
**`apt install` will NEVER work inside Ubuntu on this device. Host-side pre-install is the ONLY mechanism.**

---

## FULL AUDIT — ALL BUGS FOUND & FIXED (June 28, 2026)
> Comprehensive full-codebase audit + Termux binary reverse engineering. Every file read.

### SESSION COMMITS (newest first)

| Commit | What it fixed |
|--------|--------------|
| `bc168c47` | TerminalPane wired — `LaunchedEffect` calls `TermuxBootstrapInstaller.installIfNeeded()` on IO thread; `createTerminalSession()` uses `bashPath()+shellArgs()` when bootstrap ready, falls back to ash |
| `7af7a5d1` | Added `TermuxBootstrapInstaller.kt` — streams bootstrap-aarch64.zip from assets, extracts 3,490 entries to `termux-prefix/`, creates 1,146 symlinks, writes bash profile, exposes `shellArgs()` for bash tab |
| `b3abee6d` | Replaced placeholder bootstrap-aarch64.zip with full uncorrupted Termux bootstrap (3,490 entries: bash, curl, apt, dpkg, full Termux prefix, 28MB) extracted from libtermux-bootstrap.so |
| `28cd899` | createSession marked `internal` — fixes public API exposing internal type |
| `0024eed` | `Int::class.javaPrimitiveType` for setProcessGroup reflection (int args require primitive type) |
| `b9f2437` | setProcessGroup via reflection (hidden API not in public SDK) |
| `2eddbdb` | Correct ProotInstaller import (was proot package, is terminal package) |
| `024e817` | Full UI/UX/terminal audit — 7 bugs fixed (see below) |
| `9884a0e` | Manifest `specialUse` type + AGENTS.md research overhaul |
| `5a42de5` | TerminalService auto-acquires WakeLock on start — fixes signal 31 on all tabs |
| `82ffc15` | Removed curl pre-install block — crash during extraction |
| `59c4700` | AGENTS.md JNI audit, all null guards confirmed |

---

## TERMUX BINARY REVERSE ENGINEERING — June 28, 2026

### libtermux.so — VERIFIED FROM DEVICE (9,008 bytes, aarch64)

Extracted directly from the user's Termux installation via MT Manager + 7z.
**File is intact and uncorrupted** (e_machine=0x00B7 = proper aarch64).

**All 5 JNI exports confirmed present:**
```
Java_com_termux_terminal_JNI_close
Java_com_termux_terminal_JNI_createSubprocess
Java_com_termux_terminal_JNI_setPtyUTF8Mode
Java_com_termux_terminal_JNI_setPtyWindowSize
Java_com_termux_terminal_JNI_waitFor
```

**Syscalls Termux uses (confirmed from binary):**
`fork`, `execvp`, `setsid`, `grantpt`, `unlockpt`, `ptsname_r`, `ioctl`, `sigfillset`,
`sigprocmask`, `chdir`, `dup2`, `waitpid`, `clearenv`, `putenv`, `exit`, `asprintf`, `perror`

**CRITICAL FINDING:** Termux uses raw `fork()` — NOT `forkpty()`. Our `pty_native.c` already matches this exactly. Our implementation is architecturally identical to Termux's.

**Error strings from Termux binary (exact messages used):**
- `"GetStringUTFChars() failed for env"`
- `"Fork failed"`
- `"JNI call GetPrimitiveArrayCritical(processIdArray, &isCopy) failed"`
- `"Couldn't allocate argv array"`
- `"/dev/ptmx"`
- `"Cannot grantpt()/unlockpt()/ptsname_r() on /dev/ptmx"`
- `"Cannot open /dev/ptmx"`
- `"malloc() for envp array failed"`
- `"exec(\"%s\")"`
- `"GetStringUTFChars() failed for argv"`
- `"chdir(\"%s\")"`
- `"chdir()"`

**Build toolchain:** Android clang 11.0.5 (r399163b1), LLD 11.0.5

### libtermux-bootstrap.so — CORRUPTED BY MT MANAGER

The 53MB bootstrap ELF was corrupted when MT Manager re-zipped it treating binary as UTF-8 text.
- `e_machine` became `0xBFEF` instead of `0x00B7` — every `\xB7` byte replaced with `\xEF\xBF\xBD` (Unicode replacement char)
- The embedded bootstrap ZIP (at ELF offset 1516, exposed via `Java_com_termux_app_TermuxInstaller_getZip`) is unreadable
- Central directory size field reads 230MB but ZIP is only 53MB total — classic binary corruption

**What bootstrap contains (from partial scan before corruption):**
- 3,517 entries including full Termux prefix: `bin/bash`, `bin/curl`, `bin/dpkg`, coreutils, etc.
- SYMLINKS.txt with all symlink definitions
- The JNI function `Java_com_termux_app_TermuxInstaller_getZip` returns the ZIP bytes directly

**HOW TO GET UNCORRUPTED BOOTSTRAP (for user):**
MT Manager must copy `.so` files in **binary/raw mode**, not text mode.
Correct steps:
1. Open Termux APK as ZIP in MT Manager (long-press → Open as ZIP)
2. Go to `lib/arm64-v8a/`
3. Long-press `libtermux-bootstrap.so` → Copy (raw, not extract)
4. Paste to `/sdcard/`
5. Compress using MT Manager → **Store mode (method=0, no compression)**
6. Send that archive — bytes will be pristine

---

### BUG AUDIT — commit 024e817 (7 fixes)

#### 1. proot HOME env was host path (CRASH inside Ubuntu)
**File:** `ProotInstaller.kt` → `launchArgs()` envVars
**Was:** `HOME=${context.filesDir.absolutePath}` → `/data/data/com.codespace.ide.debug/files`
**Fix:** `HOME=/root` — correct path inside the chroot.

#### 2. TerminalModeManager DEFAULT_MODE was MODE_OLLAMA (3-sec startup delay)
**File:** `TerminalModeManager.kt`
**Was:** `DEFAULT_MODE = MODE_OLLAMA` — 3-second HTTP timeout on every terminal open
**Fix:** `DEFAULT_MODE = MODE_OFFLINE` — ash opens instantly.

#### 3. SplitTerminalPanel infinite lambda chain (memory leak)
**File:** `TerminalPane.kt` → `SplitTerminalPanel` → `AndroidView update{}`
**Fix:** Direct assignment without chaining.

#### 4. SplitTerminalPanel missing keepScreenOn
**File:** `TerminalPane.kt` → `SplitTerminalPanel` → mirror pane factory
**Fix:** Added `keepScreenOn = true` to mirror pane.

#### 5. BusyboxInstaller called on main thread (ANR risk)
**File:** `ProjectShellScreen.kt` → `handleMenuAction()`
**Fix:** Wrapped in `scope.launch { withContext(Dispatchers.IO) { ... } }`

#### 6. Ubuntu extraction thread was daemon (download dies when backgrounded)
**File:** `TerminalPane.kt` → `addUbuntuTab()`
**Fix:** `isDaemon = false; name = "UbuntuSetupThread"`

#### 7. Manifest missing permissions + no explicit hardwareAccelerated
**File:** `AndroidManifest.xml`
**Added:** `CHANGE_NETWORK_STATE`, `android:hardwareAccelerated="true"`

---

### EARLIER FIXES (pre-audit)

#### Signal 31 — WakeLock auto-acquire (commit 5a42de5)
**File:** `TerminalService.kt` → `onStartCommand()`
**Fix:** `actionAcquireWakeLock()` called automatically on every service start.

#### curl pre-install crash (commit 82ffc15)
**File:** `ProotInstaller.kt`
**Fix:** Removed entire pre-install block. curl already in ubuntu-questing rootfs.

#### PROOT_NO_SECCOMP=1
Samsung kernel 5.15 broken seccomp → use pure ptrace. See termux/proot#365.

#### Manifest foreground service type (commit 9884a0e)
`specialUse` + `FOREGROUND_SERVICE_SPECIAL_USE` + `PROPERTY_SPECIAL_USE_FGS_SUBTYPE = terminal_emulator`

---

## ARCHITECTURE

### Binaries (jniLibs/arm64-v8a/ → extracted to nativeLibraryDir)
All shipped as `.so` files to bypass Android W^X restriction.

| File | Role |
|------|------|
| `libproot.so` | Termux proot PIE binary (239KB) |
| `libproot-loader.so` | proot guest ELF loader (18KB) |
| `libtalloc.so` | talloc (SONAME patched, 34KB) |
| `libandroid-shmem.so` | Android shmem shim (14KB) |
| `libtermux-exec.so` | exec() path interceptor (7KB) — used in BOTH proot env AND ash tab |
| `libbusybox.so` | Static busybox (2.79MB) — applets: ash, sh, hush (NO bash, NO curl) |
| `libzstd-jni.so` | zstd native lib (767KB) |

### Ubuntu rootfs
- `ubuntu-questing-aarch64` (Ubuntu 25.04), v4.30.1
- Reset: `echo "" > /data/data/com.codespace.ide.debug/files/.ubuntu_version`

### Key files
- `ProotInstaller.kt` — rootfs download/extract + proot launch args
- `BusyboxInstaller.kt` — busybox symlinks, .ashrc/.profile, offline pkg script
- `TerminalService.kt` — foreground service, WakeLock+WifiLock, START_STICKY
- `TerminalPane.kt` — UI: tabs, AndroidView, SplitTerminalPanel, addUbuntuTab
- `TerminalSession.kt` (com.termux) — the REAL session class
- `NativePty.kt` + `pty_native.c` — JNI PTY layer (identical to Termux, plus extra null guards)
- `ProjectShellScreen.kt` — main IDE screen, menu actions, VS Code layout
- `DeviceCompatibility.kt` — RAM/storage checks (advisory only)
- `TerminalModeManager.kt` — mode: offline (default), ollama, ubuntu

---

## pty_native.c — TERMUX JNI AUDIT (verified June 28, 2026)

Our `pty_native.c` is verified against the real Termux `libtermux.so` binary from device.

### JNI exports implemented (matches Termux binary exactly):
- `Java_com_termux_terminal_JNI_createSubprocess` ✓
- `Java_com_termux_terminal_JNI_setPtyWindowSize` ✓
- `Java_com_termux_terminal_JNI_setPtyUTF8Mode` ✓
- `Java_com_termux_terminal_JNI_waitFor` ✓
- `Java_com_termux_terminal_JNI_close` ✓

### Our guards vs Termux upstream:
| Guard | Termux | Ours |
|-------|--------|------|
| null check on argv GetObjectArrayElement | ✗ | ✓ |
| null check on envp GetObjectArrayElement | ✗ | ✓ |
| ptm < 0 check before GetPrimitiveArrayCritical | ✗ | ✓ |
| correct jstring in ReleaseStringUTFChars for cwd | ✗ | ✓ |

### Architecture match:
- Uses raw `fork()` (not `forkpty()`) — same as Termux ✓
- setsid() after fork — same as Termux ✓
- sigfillset + sigprocmask SIG_UNBLOCK — same as Termux ✓
- clearenv() + putenv() loop — same as Termux ✓
- /proc/self/fd FD cleanup — same as Termux ✓
- IUTF8 + disable IXON/IXOFF — same as Termux ✓

---

## setProcessGroup — Phantom Process Killer Protection

**Location:** `TerminalPane.kt` → `SimpleTerminalSessionClient.setTerminalShellPid()`
and `TerminalService.kt` → `SimpleTerminalSessionClient.setTerminalShellPid()`

**Why:** Android 12+ LMKD kills child processes not in foreground cgroup. WakeLock alone doesn't protect child pids on TECNO/Samsung Android 14.

**Implementation:** Hidden API called via reflection (not in public SDK):
```kotlin
val m = android.os.Process::class.java.getMethod(
    "setProcessGroup",
    Int::class.javaPrimitiveType,   // MUST be javaPrimitiveType not java for int args
    Int::class.javaPrimitiveType
)
m.invoke(null, pid, 5)  // 5 = THREAD_GROUP_TOP_APP
// fallback: m.invoke(null, pid, 1)  // 1 = THREAD_GROUP_FOREGROUND
```
**CRITICAL:** `Int::class.java` compiles but throws `NoSuchMethodException` at runtime because Java reflection distinguishes `int` (primitive) from `Integer` (boxed). Always use `Int::class.javaPrimitiveType`.

**`createSession` must be `internal`** — it returns `Pair<TerminalSession, SimpleTerminalSessionClient>` and `SimpleTerminalSessionClient` is internal. Making the function `public` causes compile error: "public function exposes its internal return type argument".

---

## TerminalSession Lifecycle (from Termux smali ground truth)

**IMPORTANT:** `TerminalSession` constructor does NOT start the subprocess.
The process only starts when `updateSize()` → `initializeEmulator()` is called,
which happens inside `attachSession()` on the TerminalView.
TerminalView is only attached after layout inflation completes.

---

## Termux Smali Decompile Findings (June 28, 2026)

### TermuxTerminalSessionClient — Key callbacks:
**onTextChanged(session):**
```
if session != currentSession: return   ← GUARD — only update UI for active tab
terminalView.onScreenUpdated()
```

**onSessionFinished(session):**
```
if numSessions == 1 and removeOnExit: finish activity
else: removeFinishedSession(session)
```

**onServiceDisconnected():**
```kotlin
// Termux handles service disconnect gracefully
finishActivityIfNotFinishing()   ← exit cleanly, don't crash
```

### TermuxTerminalViewClient — Key callbacks:
**onSingleTapUp(e):**
```
if mActivity.isTerminalViewSelected and mActivity.shouldOpenTerminalTranscriptURLOnClick:
    openURL(url_from_transcript)
else:
    showSoftKeyboard()
```

**onKeyDown(keyCode, event, session):**
- Ctrl+Alt+N = new session
- Ctrl+Alt+P = prev session
- Ctrl+Alt+U = open drawer
- Ctrl+Alt+K = kill session dialog
- Ctrl+Alt +/- = font size

**onScale(scale):**
```
if scale < 0.9 or scale > 1.1:
    changeFontSize(scale > 1.0)
return 1.0f   ← no actual zoom, just font size change
```

**setTerminalCursorBlinkerState(start):**
```
if start:
    terminalView.setTerminalCursorBlinkerRate(prefs.getTerminalCursorBlinkRate())
    terminalView.setTerminalCursorBlinkerState(true, true)
else:
    terminalView.setTerminalCursorBlinkerState(false, true)
```

**onEmulatorSet() — CRITICAL:**
```
if !mTerminalCursorBlinkerStateAlreadySet:
    setTerminalCursorBlinkerState(true)
    mTerminalCursorBlinkerStateAlreadySet = true
```
→ Called when TerminalView.attachSession() sets up the emulator. This starts the cursor.
→ **We MUST wire this up — currently cursor blink never starts in our app.**

### TermuxActivity.onCreate() — COMPLETE:
```
isOnResumeAfterOnCreate = true
setActivityTheme()
super.onCreate(bundle)
setContentView(R.layout.activity_termux)
setTermuxTerminalViewAndClients()   ← creates SessionClient + ViewClient + finds TerminalView
startService(TermuxService)         ← start BEFORE bind
bindService(TermuxService, this, 0) ← bind; throws RuntimeException if fails
```

---

## WHAT OUR CODE IS STILL MISSING vs Termux

| Feature | Termux | Ours | Priority |
|---|---|---|---|
| onTextChanged active-tab guard | ✓ | ✅ FIXED | Done |
| onServiceDisconnected resilience | ✓ | ✅ FIXED | Done |
| MODE_OFFLINE menu item | ✓ | ✅ FIXED | Done |
| BusyboxInstaller off main thread | ✓ | ✅ FIXED | Done |
| setProcessGroup phantom kill protection | ✓ | ✅ FIXED | Done |
| createSession internal visibility | ✓ | ✅ FIXED | Done |
| Int::class.javaPrimitiveType for reflection | ✓ | ✅ FIXED | Done |
| Cursor blink via onEmulatorSet | ✓ | ✗ — cursor never starts | **Medium** |
| Font size from SharedPreferences | ✓ | ✗ — hardcoded | Low |
| Real bash in bash tab (Termux prefix) | ✓ | ✅ DONE — bootstrap extracted + TerminalPane wired (bc168c47): bash --login when ready, ash fallback | Done |
| Pinch-to-zoom changes font size | ✓ | ✗ — no pinch handler | Low |
| Back key with no emulator = finish | ✓ | ✗ — back does nothing | Low |
| Color scheme from colors.properties | ✓ | ✗ — hardcoded dark theme | Low |

---

## NEXT STEPS (priority order)

1. **Test on device** — APK build is green (`28cd899`). Install and verify:
   - Bash tab opens instantly (no 3-sec delay)
   - Ubuntu tab downloads + extracts without OOM crash
   - Terminal stays alive when screen off (WakeLock + setProcessGroup working)
   - Split terminal pane works without memory leak

2. ✅ **Bash tab wired to TermuxBootstrapInstaller** (bc168c47)

4. **Cursor blink fix** (Medium priority) — wire `onEmulatorSet()` callback to call `setTerminalCursorBlinkerState(true)` when emulator is attached

3. **Bootstrap integration** ✅ DONE (commit `b3abee6d`) — full uncorrupted `libtermux-bootstrap.so` extracted and pushed as `android/app/src/main/assets/bootstrap-aarch64.zip` (3,490 entries, 28MB). Bash 5.2, curl, apt, dpkg, full Termux prefix now ships in APK.

---

## HOW WE RESEARCHED TERMUX — REVERSE ENGINEERING WORKFLOW

> This section exists so future AI agents know exactly how we figured things out.
> Do NOT skip this — it saved us from guessing blindly at Termux internals.

### The problem
We needed to understand exactly how the real Termux app initializes its terminal,
sets environment variables, handles LD_PRELOAD, writes profiles, and structures its
prefix directory. GitHub source alone wasn't enough — compiled behaviour differs.

### Solution: On-device APK inspection with ZArchiver / MT Manager

The user decompiled the **live Termux APK** directly on their TECNO KL4 device using
**ZArchiver** or **MT Manager** (both can open .apk/.zip files on-device without a PC).

Steps used:
1. Locate the Termux APK: `Settings > Apps > Termux > Storage > show app APK path`
   (usually `/data/app/~~.../com.termux-.../base.apk`)
2. Open the APK in ZArchiver or MT Manager — it's just a zip file
3. Browse to:
   - `assets/` — bootstrap zip files, shell scripts
   - `lib/arm64-v8a/` — native .so files including `libtermux-exec.so`
   - `classes.dex` — decompile with jadx-gui on PC if needed
4. Cross-reference with GitHub source for full picture

### What we learned from this
- `libtermux-exec.so` lives inside `$PREFIX/lib/`, NOT in the app's `nativeLibraryDir`
- It is set via `/etc/profile.d/` shell scripts INSIDE the bootstrap, NOT from Java/Kotlin
- The Java code does NOT set LD_PRELOAD — it is entirely managed by the bootstrap
- bootstrap-aarch64.zip structure: `./bin/`, `./lib/`, `./etc/`, `./share/`, `./tmp/`
- Shell init: bash --login sources `/etc/profile` which sources `/etc/profile.d/*.sh`
- This is why setting LD_PRELOAD from Kotlin was wrong — we were copying a pattern
  that only works when the .so is already inside the prefix

### Google Drive integration for large file transfer
Base44 has a 5MB file paste limit in chat. For larger files (bootstrap zips, APKs,
decompiled source):
- Connect Google Drive to Base44 (OAuth connector)
- Upload the large file to Drive, share the link or let the agent read it directly
- The agent can then extract, inspect, and reference content from Drive
- This was used to share large ZIPs and decompiled code that exceeded the chat limit

### The meta-lesson
> When stuck on "how does X really work" — don't guess from docs alone.
> Open the actual binary on-device. ZArchiver + MT Manager = free on-device decompiler.
> This approach applies to ANY Android app you are reverse-engineering or forking.

---

## PLANNED: MASTER SKILL — "How to build an Android terminal app from scratch"

When the full app is complete (terminal + editor + all tabs working), the user will
create a Base44 Skill documenting the entire process end-to-end, including:

- How to fork Termux terminal library correctly
- How to bootstrap a prefix on Android (bootstrap-aarch64.zip + proot)
- How to avoid known pitfalls (LD_PRELOAD, Samsung kernel, signal 31, JNI null checks)
- The ZArchiver/MT Manager reverse engineering workflow
- How to handle APK size limits, Google Drive for large files, multi-session AI handoffs
- Test procedure for TECNO KL4 (ash prompt, Ubuntu extraction, curl check, apt update)

This skill will be reusable for ANY future terminal/IDE app build — dodge the same
bullets without rediscovering them from scratch.

---

## VS CODE REBRAND — FEATURE TRACKING (Updated 2026-06-28)

### Source material
- YouTube transcript: "VS Code for Absolute Beginners | 2026" (57KB)
- YouTube transcript: "Learn Visual Studio Code in 15 minutes: 2026 Official Beginner Tutorial" (18KB)
- 8 screenshots from VS Code 2026 UI

### Features confirmed IMPLEMENTED
- [x] Activity bar: Explorer, Search, Git, Run/Debug, Extensions
- [x] Command palette (click center title or Ctrl+Shift+P)
- [x] Status bar (bottom, colored by theme)
- [x] Breadcrumbs (file path below editor tabs)
- [x] Multiple themes (Dracula, Tokyo Night, Monokai, Nord, etc.)
- [x] Bottom panel: TERMINAL / OUTPUT / PROBLEMS / DEBUG / PORTS / SPLIT tabs
- [x] Person icon (account menu)
- [x] Gear icon (settings menu)
- [x] Extensions panel — now reads real dpkg status, shows INSTALLED/RECOMMENDED/MCP sections
- [x] Terminal menu — categorized (TERMINALS / AI & TOOLS / DEFAULT MODE / MANAGE)
- [x] Chat panel — right-side collapsible AI chat panel (toggle via chat icon in title bar)

### Features IN PROGRESS / NEXT
- [ ] IntelliSense autocomplete dropdown in code editor (language-aware completions)
- [ ] Hover documentation tooltip (type signature + description + MDN Reference link)
- [ ] Minimap (right side of editor showing code overview)
- [ ] Git diff view (inline +/- line indicators)
- [ ] Multi-cursor editing (tap + hold select)
- [ ] Code folding (collapse functions/blocks)
- [ ] Snippets support (user-defined shorthand)
- [ ] Extensions panel: star ratings + install count (like VS Code marketplace)
- [ ] Ports panel (real forwarded ports list)
- [ ] Run config panel (launch.json style)

### UI Layout (matches VS Code 2026)
```
[Activity Bar 48dp] | [Side Panel ~200dp] | [Editor Area] | [Chat Panel ~45%] (optional)
                                           |--- Tab Bar ---|
                                           |--- Breadcrumb-|
                                           |--- Editor  ---|
                                           |--- Bottom Panel: TERMINAL/OUTPUT/etc. ---|
[Status Bar 22dp — full width, blue background]
```

---

# ╔══════════════════════════════════════════════════════════════╗
# ║         MASTER ROADMAP — CODESPACE IDE MOBILE               ║
# ║         Updated: 2026-06-28 (Wisdom's full vision)          ║
# ╚══════════════════════════════════════════════════════════════╝

## Vision
A fully VS Code-branded mobile IDE that combines:
- Real VS Code UI/UX (activity bar, chat panel, extensions, IntelliSense)
- NewTermux terminal enhancements (zsh+OMZ, PiP tabs, autocorrect, STT, root, package manager)
- A powerful embedded AI Agent: GitHub Copilot + Claude + planning + MCP + Ollama models
- Connectors: image editing, app builder, GitHub, web browsing, file manager
- Full offline capability (proot Ubuntu, busybox ash, Ollama local models)

---

## PHASE 1 — VS Code UI Rebrand (IN PROGRESS)
Source: VS Code 2026 YouTube tutorials + screenshots

### Completed ✅
- [x] Activity bar: Explorer, Search, Git, Run/Debug, Extensions icons
- [x] Command palette (center clickable title bar)
- [x] Status bar (colored by theme, bottom 22dp)
- [x] Breadcrumbs (file path below tab bar)
- [x] 11 color themes (Dracula, Tokyo Night, Monokai, Nord, etc.)
- [x] Bottom panel: TERMINAL / OUTPUT / PROBLEMS / DEBUG / PORTS / SPLIT tabs
- [x] Person icon (account menu) + Gear icon (settings)
- [x] Terminal dropdown: categorized sections (TERMINALS / AI & TOOLS / DEFAULT / MANAGE)
- [x] Copilot Chat panel — right-side collapsible, chat toggle icon in title bar
- [x] Extensions panel — reads real dpkg/status file + searchable marketplace suggestions

### In Progress 🔄
- [ ] Fix ExtensionsPanel compile error (LaunchedEffect + coroutines missing import in ExplorerPane.kt)
- [ ] Extensions panel: INSTALLED / RECOMMENDED / MCP SERVERS collapsible sections
- [ ] IntelliSense autocomplete dropdown (language-aware, shows method/var/class with icons)
- [ ] Hover documentation tooltip (type + MDN reference, shows on long-press in editor)
- [ ] Minimap (right side of editor, code overview)
- [ ] Git diff inline indicators (+ / - line gutters)

### TODO 📋
- [ ] Breadcrumb navigation (tap any segment to jump)
- [ ] Multi-cursor editing
- [ ] Code folding (collapse blocks)
- [ ] Snippet support
- [ ] Ports panel (real port forwarding list)
- [ ] Run config (launch.json style tasks)
- [ ] Title bar: back/forward navigation arrows
- [ ] Search panel: Find + Replace with Aa / word / regex toggles (already visible in screenshots)
- [ ] Editor welcome screen ("Getting Started" tab with keybindings cheatsheet)

---

## PHASE 2 — NewTermux Feature Integration (NEXT)
Source: https://github.com/The412Banner/NewTermux

Integrate the following NewTermux features INTO the CodeSpace terminal (TerminalPane.kt):

### Terminal Shell
- [ ] **Zsh + Oh My Zsh** auto-setup on first terminal launch (download + configure in background)
- [ ] **Zsh plugins toggle**: autosuggestions + syntax highlighting (from bundled zip, no download)
- [ ] **Session PiP view**: mini live terminal previews in tab chips instead of plain text tabs
- [ ] **Session renaming**: long-press tab to rename it
- [ ] **Failsafe mode**: if main shell crashes, offer a fallback minimal shell

### Toolbar Buttons (terminal quick actions row)
- [ ] **AC** — toggle keyboard autocorrect (using AutoCorrectHandler)
- [ ] **Root** — switch to root shell via `su` (using RootToggleManager)
- [ ] **STT** — speech-to-text input into terminal (using SpeechInputManager)
- [ ] **Packages** — opens Package Manager panel (real dpkg/apt package browser)
- [ ] **Clear** — clears terminal screen
- [ ] **Export Screen** — save terminal output to file via system file picker
- [ ] **Make Script** — pick commands from history → save as .sh script

### Left Drawer (in terminal)
- [ ] Custom command buttons (up to 10 user-defined, long-press to edit, +/-)
- [ ] Pkg Update shortcut (runs `pkg update -y`)
- [ ] Backup/restore Termux data (.tar.gz)

### Extra Features
- [ ] **URL detection**: long-press URL in terminal → open in browser or copy
- [ ] **Startup script**: dot-sourced into every new session
- [ ] **Text expansion**: trigger shortcuts (`;ll` → `ls -la`) — already partially done
- [ ] **Autocorrect bar**: suggestion strip above keyboard for shell commands
- [ ] **9 accent presets + HSV color wheel** for terminal theming
- [ ] **6 terminal color themes + custom editor** (edit all 18 ANSI colors live)

---

## PHASE 3 — AI Agent System (MAJOR FEATURE)
Vision: A mix of GitHub Copilot + Claude + any local Ollama model + planning agent

### Core Architecture
- [ ] **AI Chat Panel** (already added as VS Code Copilot panel)
  - Connect to: Ollama (local, any model launched in terminal or AI tab)
  - Connect to: Remote APIs (Claude, OpenAI, Gemini — user provides key in Settings)
  - Model picker: dropdown shows all models currently running via `ollama list`
  - Mode selector: Ask / Agent / Plan

### Agent Modes
- [ ] **Ask mode**: single-turn Q&A about code (explain this function, what does this error mean)
- [ ] **Agent mode**: multi-step task execution (like GitHub Copilot Agent)
  - Reads open files in editor tabs
  - Can write/edit files via file manager
  - Can run commands in terminal tab
  - Can create new files/folders
- [ ] **Plan mode**: breaks large tasks into steps, asks for approval before executing
  - Shows step-by-step plan with checkboxes
  - User can approve/reject/modify individual steps
  - Tracks progress and can resume after interruption

### Context Sources (@ mentions)
- [ ] **@ Add Context**: current file, open tabs, workspace folder, git diff, terminal output
- [ ] **MCP (Model Context Protocol)**: connect AI to real tools
  - GitHub MCP — read repos, create issues, PRs
  - File system MCP — read/write workspace files
  - Terminal MCP — run commands and get output back
  - Web search MCP — fetch URLs, search the web
  - Custom MCP — user can add any MCP server endpoint

### Connectors (like Base44 superagent)
- [ ] **Image editing**: open image files, describe edits in chat, AI applies them
- [ ] **GitHub connector**: clone repos, push commits, review PRs from within the IDE
- [ ] **App builder**: scaffold a new project (React, Flask, Express, etc.) via chat
- [ ] **Web browser**: embedded browser tab, AI can scrape + summarize pages
- [ ] **File manager**: AI can read, create, move, delete files
- [ ] **Camera**: capture screenshot/photo and add to chat context

### Ollama Integration
- [ ] **Auto-detect running models**: poll `http://localhost:11434/api/tags` every 30s
- [ ] **Model switcher**: show available models in chat panel model picker
- [ ] **Stream responses**: show token-by-token output (not wait for full response)
- [ ] **System prompt**: configurable per-project system prompt
- [ ] **Chat history**: persisted per project/workspace in local JSON
- [ ] **Multi-model**: use different models for different tasks (coding vs planning vs image)

### Sessions Panel (like VS Code Copilot Sessions)
- [ ] Sessions list in chat panel header (like the screenshots show)
- [ ] Named sessions: "Debugging login bug", "Refactoring auth module"
- [ ] Timestamps + completion status
- [ ] Resume any past session

---

## PHASE 4 — Polish & Performance
- [ ] Smooth terminal font rendering (monospace, ligatures)
- [ ] Keyboard shortcut overlay (Ctrl+Shift+P etc. shown in welcome screen)
- [ ] Onboarding flow: first launch wizard (install bootstrap, choose theme, connect GitHub)
- [ ] App icon: VS Code style (your branding, not Microsoft's)
- [ ] Settings screen: full VS Code-style settings JSON editor
- [ ] Crash reporting + auto-recovery
- [ ] Performance: lazy-load panels, cache file tree

---

---

## PENDING FEATURES — June 28, 2026 (Wisdom's requests)

### 1. Search / Find in Editor (magnifier icon)
- [ ] The search icon in the toolbar must trigger real **find-in-file** inside the active editor
- [ ] Highlight all occurrences in the editor view (like VS Code)
- [ ] Navigate matches with Up/Down arrows
- [ ] Case-sensitive and whole-word toggles
- [ ] The existing `showFindBar` state + `findQuery` are already wired — confirm the icon tap actually sets `showFindBar = true` and the bar performs real text matching inside the CodeEditor composable

### 2. Source Control Panel — Git diff + real commit/push
- [ ] The 3-box connected icon (Source Control) must show **actual changed files** just like VS Code's SCM panel
- [ ] Each file entry must show: filename, change type badge (M = modified, A = added, D = deleted, U = untracked)
- [ ] Tapping a file shows an inline diff (old vs new) — colour-coded red/green lines
- [ ] Stage individual files or "Stage All" button
- [ ] Commit message text field + "Commit" button → runs `git commit -m "..."` in the proot Ubuntu shell
- [ ] "Push" button → runs `git push origin HEAD` in the proot shell
- [ ] "Pull" button → runs `git pull --rebase` in the proot shell
- [ ] Uses the Ubuntu tab shell (proot), not busybox ash — git is installed via apt in Ubuntu
- [ ] Auth: support HTTPS with stored token (read from SecureTokenStore) or SSH key in Ubuntu home

### 3. Explorer — Full File Manager (view, edit, delete any file on phone)
- [ ] Add a new "Phone Files" button/icon inside the Explorer pane header (next to the existing New File / New Folder buttons)
- [ ] Opens a full file browser rooted at `/storage/emulated/0` (external storage)
- [ ] Shows ALL files and folders — not just the workspace
- [ ] Each entry: icon (folder/file type), name, size, last modified
- [ ] **Long-press context menu**: Open in Editor, Rename, Delete, Copy Path, Move
- [ ] **Single tap on text/code file**: opens it in the editor tab
- [ ] **Images**: show name + path only in list (cannot edit). **Long-press 3s → full-size image preview overlay**
- [ ] **Edit**: opens file in editor. **Delete**: confirmation dialog before deleting
- [ ] Request `READ_EXTERNAL_STORAGE` / `WRITE_EXTERNAL_STORAGE` / `MANAGE_EXTERNAL_STORAGE` permissions as needed
- [ ] Breadcrumb navigation bar so user can tap up through folders

### 4. Command Palette — Smaller / tighter like real VS Code
- [ ] Current command palette is too large / takes up too much screen
- [ ] Target: max 60-70% screen height, appears from top (not full screen)
- [ ] Input field at top, results list below — compact rows (32dp height each)
- [ ] Background: semi-transparent dark overlay behind the palette card
- [ ] Font size: 12sp for results, 14sp for the input field
- [ ] Should feel like VS Code mobile — quick and lightweight, not a dialog

### IMPLEMENTATION NOTES
- All file manager operations must be on IO thread (`Dispatchers.IO`), never main thread
- Image long-press timer: use `pointerInput` with `detectTapGestures(onLongPress = {...})` + 3000ms delay
- Git operations: write command to the Ubuntu terminal session via `session.write("git ... && echo DONE
")` — parse output for result
- For push/commit: git credential helper must be pre-configured in Ubuntu (store mode or token in URL)
- Command palette: use `Dialog` composable with `usePlatformDefaultWidth = false` + fixed width fraction
- Screenshot-based UI sizing review coming — resize decisions deferred until screenshots received

---

---

## PENDING FEATURES — June 28, 2026 batch 2 (Wisdom's requests)

### 5. Replace Login Screen with Google Sign-In
- [ ] Remove the current PAT (GitHub token) text-field login from `AuthScreen.kt` entirely
- [ ] Replace with a single **"Sign in with Google"** button (Google Identity / Credential Manager API)
- [ ] On successful Google login: extract user name, email, Google account photo URL
- [ ] Send a Gmail notification to Wisdom's email with: user display name, email, login timestamp, device model
- [ ] After login, the GitHub PAT entry moves to the **gear icon → "Connect GitHub"** inside `ProjectShellScreen.kt`
- [ ] Store: Google ID token in `SecureTokenStore`, GitHub PAT separately keyed by Google user ID
- [ ] Future: users without subscription → their projects become public (flag in SharedPreferences: `is_subscribed`)
- [ ] Dependencies needed in `build.gradle`: `credentials-play-services-auth`, `googleid`, `play-services-auth`
- [ ] `AndroidManifest.xml`: add `<uses-permission android:name="android.permission.GET_ACCOUNTS" />`
- [ ] OAuth client ID must be in `google-services.json` (web client ID for Credential Manager)

### 6. Command Palette — Keyboard / Input Bug Fix
- [ ] When tapping the search field in the command palette, typing does nothing — only paste works
- [ ] Root cause likely: `BasicTextField` or `OutlinedTextField` inside a `Dialog` loses focus on Android
- [ ] Fix: use `focusRequester` + `LaunchedEffect` to auto-request focus on the field when palette opens
  ```kotlin
  val focusRequester = remember { FocusRequester() }
  LaunchedEffect(Unit) { focusRequester.requestFocus() }
  // add .focusRequester(focusRequester) to the TextField modifier
  ```
- [ ] Also ensure the `Dialog` has `properties = DialogProperties(usePlatformDefaultWidth = false)`
- [ ] Also ensure `keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search)` on the text field

### 7. GitHub Login → Gear Icon (move PAT entry out of splash)
- [ ] Remove GitHub token input from `AuthScreen.kt` (replaced by Google login — see #5)
- [ ] In `ProjectShellScreen.kt` gear menu → add **"Connect GitHub"** item
- [ ] Tapping it opens a bottom sheet with: GitHub PAT text field, "Validate & Save" button, "Get Token →" link
- [ ] On save: store via `SecureTokenStore`, show toast "GitHub connected as @username"
- [ ] GitHub avatar + username shown in gear menu header once connected
- [ ] All git operations (push/pull/clone) use this stored token

### 8. Gear Icon — Make All Buttons Functional
Current gear menu items and their required implementations:
- [ ] **Settings** → already navigates to `SettingsScreen` ✓
- [ ] **Color Theme** → already opens color theme picker ✓
- [ ] **Terminal Theme** → already opens terminal theme picker ✓
- [ ] **Setup Shell Profile** → runs Zsh + OMZ install script in Ubuntu terminal (write to session)
- [ ] **Setup Offline Shell** → installs offline proot bootstrap (existing ProotInstaller logic)
- [ ] **Install Offline Essentials** → runs: `apt install -y python3 nodejs git curl vim htop` in Ubuntu tab
- [ ] **Backup Shell Profile** → copies `~/.zshrc`, `~/.bashrc`, `~/.profile` to `/storage/emulated/0/codespace_backup/`
- [ ] **Restore Shell Profile** → reads from backup folder and writes back to Ubuntu home
- [ ] **Keyboard Shortcuts** → opens a modal/sheet listing all Ctrl+X shortcuts with their actions
- [ ] **Extensions** → switches the active side panel to `SidePanel.EXTENSIONS`
- [ ] **Connect GitHub** → opens GitHub PAT bottom sheet (see #7 above)

### 9. Full Project Persistence (auto-save everything)
- [ ] When a project is opened, restore EXACTLY the state from last time:
  - Active editor tabs (all of them, in order)
  - Active/focused tab
  - Scroll position of each tab's editor
  - Cursor position (line + column) per tab
  - Bottom panel open/closed + which tab (Terminal / Ubuntu / etc.)
  - Side panel open/closed + which panel (Explorer / Search / Git / etc.)
  - Side panel width
  - Chat panel open/closed
  - Editor font size
  - Any unsaved edits (buffer content saved to a temp file per tab)
- [ ] Save trigger: every time any of the above state changes (debounced 1s)
- [ ] Storage: extend `SessionStateStore.ShellState` with all the above fields
- [ ] Key: `session_state` SharedPreferences, key = `shell_state_{projectId}`
- [ ] Each tab's unsaved buffer → save to `context.filesDir/buffers/{projectId}/{filename}.buf`
- [ ] On open: read buffer file if it exists, load into editor instead of reading from disk
- [ ] Scroll + cursor position: store as `Map<filePath, Pair<Int,Int>>` (line, col) in ShellState JSON

### IMPLEMENTATION PRIORITY ORDER (updated)
1. Fix ExplorerPane.kt compile error (literal newline in McpPanel) — DONE ✅
2. Fix TerminalPane.kt + ProjectShellScreen.kt literal newlines — DONE ✅
3. Command palette keyboard focus fix (#6) — quick, high impact
4. Project persistence (#9) — foundational, enables everything else
5. Google login (#5) — needs google-services.json setup from Wisdom
6. GitHub PAT → gear icon (#7)
7. Gear buttons functional (#8)
8. Search panel git diff SCM (#2 from batch 1)
9. Explorer phone file manager (#3 from batch 1)
10. Command palette resize (#4 from batch 1)

---

---

## PENDING FEATURES — June 28, 2026 batch 3 (screenshot review)

### AGENT RULE — Always push ideas to AGENTS.md
> Every time Wisdom describes a feature, UI change, or idea — no matter how small —
> push it to AGENTS.md immediately before doing anything else. This is a hard rule.

### 10. Remove Run / Debug / Terminal / Split quick-action row
- [x] **DONE** — removed entirely from the toolbar above the editor area
- These actions are accessible via the menu bar (Run menu, Terminal menu) — no need for a dedicated row
- Freed vertical space for the editor

### 11. Immersive Fullscreen — Hide Status Bar
- [x] **DONE** — `MainActivity.kt` now hides the system status bar (time, battery, signal) on launch
- Android 11+: `WindowInsetsController.hide(statusBars)` with `BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE`
- Android <11: `SYSTEM_UI_FLAG_FULLSCREEN | SYSTEM_UI_FLAG_IMMERSIVE_STICKY`
- Swipe down from top → status bar appears briefly then auto-hides again
- Goal: maximum vertical space for the editor and terminal

### 12. Shrink Command Palette
- [x] **DONE** — palette is now compact:
  - Top padding reduced: 80dp → 40dp (sits closer to top)
  - Width: 0.95f → 0.90f
  - List max height: 320dp → 200dp
  - Item row padding: 16dp/10dp → 10dp/5dp
  - Font size: 13sp → 12sp
  - TextField height: 40dp, font 12sp
  - Removed bottom spacer
- Keyboard focus fix already in place (focusRequester + LaunchedEffect)

### 13. Shrink Bottom Panel Tab Row (PROBLEMS / OUTPUT / TERMINAL / DEBUG / PORTS / SPLIT)
- [x] **DONE** — tab row reduced:
  - Height: 28dp → 22dp
  - Horizontal padding: 8dp → 4dp
  - Tab padding: 10dp/4dp → 6dp/2dp
  - Font: 11sp → 10sp

### 14. "workspace ready" label
- The text "workspace ready" was part of the Run/Debug row — removed with it (#10 above)
- If it reappears elsewhere, remove it — wastes space, provides no value

### NOTES ON SCREENSHOTS
- Screenshot 1: Command palette full-screen (circled) → fixed in #12
- Screenshot 2: Run/Debug/Terminal/Split row (circled) → removed in #10
- Screenshot 3: Two crossed-out lines = the tab row above terminal → shrunk in #13.
  Also the system status bar → hidden fullscreen in #11

---

## BUILD SEQUENCE (ordered by value + dependencies)

1. **Fix ExtensionsPanel compile** (LaunchedEffect import) — blocks APK
2. **Zsh + OMZ auto-setup** in terminal (huge UX win, quick to add)
3. **STT button** in terminal extra keys (SpeechInputManager port)
4. **Root toggle** terminal button (RootToggleManager port)
5. **Ollama model auto-detect** (poll /api/tags, populate model picker in chat panel)
6. **Chat panel: real Ollama streaming** (HTTP POST to localhost:11434/api/chat)
7. **MCP: terminal MCP** (run commands, get output back in chat)
8. **Agent mode** (file read/write + terminal commands via chat)
9. **Extensions: INSTALLED/RECOMMENDED/MCP sections** + star ratings
10. **Session PiP view** in terminal tabs
11. **Plan mode** with step approval
12. **GitHub connector** (OAuth → clone/push/PR from IDE)
13. **Minimap** in code editor
14. **IntelliSense** autocomplete in editor

---

## CURRENT BLOCKER (resolved June 28, 2026)
~~ExtensionsPanel.kt in ExplorerPane.kt fails to compile~~ — Fixed.
Build still failing due to literal newlines in TerminalPane.kt + ProjectShellScreen.kt — patched in commits c43ae073 + a656f968.

## PREVIOUSLY:
```kotlin
import kotlinx.coroutines.withContext
import kotlinx.coroutines.Dispatchers
import androidx.compose.runtime.LaunchedEffect
```
These may not be in ExplorerPane.kt's existing imports. Fix = add them explicitly.

---

## OWNER / ADMIN RECOGNITION — June 28, 2026

### How it works (fully implemented)
1. User taps "Sign in with Google" on `AuthScreen.kt`
2. Firebase Credential Manager gets a Google ID token
3. App posts the Firebase ID token to `POST /api/v1/auth/google`
4. Backend (`auth.service.ts`) verifies the token with Firebase Admin SDK
5. If the email matches `OWNER_EMAIL` env var → user gets `role: "owner"` in DB
6. Backend returns `{ accessToken, refreshToken, role }` as JWT
7. `CodeSpaceApp.kt` stores `refreshToken` + `role` in `SecureTokenStore`
8. `tokenStore.isOwner` (true/false) is available anywhere via DI

### To activate owner role for Wisdom
Set this env var on the backend server:
```
OWNER_EMAIL=wisdom@gmail.com   # <-- replace with the actual Google account email
```

### Files involved
| File | Role |
|---|---|
| `backend/src/auth/auth.service.ts` | `OWNER_EMAIL` check → stamps `role=owner` |
| `backend/src/users/user.entity.ts` | `UserRole.OWNER / USER` enum + DB column |
| `android/.../ui/screens/AuthScreen.kt` | Google Sign-In, calls `/auth/google`, returns `AuthResult(role=...)` |
| `android/.../ui/CodeSpaceApp.kt` | Saves `result.role` to `tokenStore.userRole` on auth |
| `android/.../data/SecureTokenStore.kt` | `userRole: String`, `isOwner: Boolean` — persisted encrypted |

### Using isOwner in any screen
```kotlin
@Inject lateinit var tokenStore: SecureTokenStore
// then:
if (tokenStore.isOwner) { /* show admin options */ }
```

### Backend env vars needed (not yet set on production server)
```
OWNER_EMAIL=<wisdom's google email>
FIREBASE_PROJECT_ID=<from google-services.json>
FIREBASE_CLIENT_EMAIL=<firebase service account>
FIREBASE_PRIVATE_KEY=<firebase service account private key>
```


---

## BUILD HISTORY AUDIT — RUNS 549–582 (June 28, 2026)

### Summary
Investigated by: Agent session June 28, 2026 (this session).

| Run range | Root cause | Resolution |
|-----------|-----------|-----------|
| 549–558 | Literal `\n` inside Kotlin string literals in `ProjectShellScreen.kt` — `Expecting '"'` at compiler | Fixed run by run with unicode escapes (`\n` → `\n`) |
| 562–563 | `TerminalBuffer.mColumns` accessed directly — package-private, compiler error | Fixed in 564: switched to `getTranscriptText()` |
| 567–579 | `google-services.json` missing `com.codespace.ide.debug` app package | Fixed in 580 |
| 580–582 | ✅ GREEN — all issues resolved |

### Features shipped by other AI (all in current HEAD, all building):

| Feature | File(s) |
|---------|---------|
| Chat panel — Ollama (Ask/Agent/Plan modes, history) | `ProjectShellScreen.kt` |
| NewTermux toolbar — STT, Root, Zsh+OMZ, Clear, Export, Pkg Update, MCP start, Script maker | `TerminalPane.kt` |
| McpPanel — MCP marketplace in Extensions tab | `McpPanel.kt` (new file) |
| Immersive fullscreen (status bar hides, swipe to peek) | `ProjectShellScreen.kt` |
| Google Sign-In via Firebase + Credential Manager | `AuthScreen.kt` |
| Owner role system — `ijeziewisdom131@gmail.com` = owner | `SecureTokenStore.kt` |
| Backend auth: Firebase token verify → JWT + role stamp | `backend/src/auth/` |

### Our terminal fixes (from this session) — also in current HEAD (run 582 ✅):
All 9 features confirmed in HEAD `TerminalPane.kt`:
- Bell vibrate, pinch-to-zoom, URL tap-to-open, cursor blinking wired
- Tab title updates from escape sequences
- Session finished → `[exited]` tab marker
- `keepScreenOn = true` on TerminalView
- Layout change listener → SIGWINCH for vim/nano/htop
- `DisposableEffect` service lifetime — TerminalService alive for full pane lifetime

### Hard rule added:
Never use `TerminalBuffer.mColumns` directly — it is package-private.
Always use `screen.getTranscriptText()` or `emulator.screen.getTranscriptText()`.


---

## TERMINAL FEATURES — BATCH 2 (commit 7ad76069fa, June 28, 2026)

### Hardware keyboard shortcuts
Implemented in `SimpleTerminalViewClient.onKeyDown` — matches Termux's TermuxTerminalViewClient.

| Shortcut | Action |
|---|---|
| `Ctrl+Alt+N` | New tab |
| `Ctrl+Alt+W` | Close current tab |
| `Ctrl+Alt+P` or `Ctrl+Alt+←` | Previous tab |
| `Ctrl+Alt+→` | Next tab |
| `Ctrl+Alt+L` | Clear screen (sends `clear\n`) |
| `Ctrl+L` | Clear screen (sends `\f` form feed) |

Key modifier state now tracked properly — `ctrlKeyDown`/`altKeyDown`/`shiftKeyDown` fields.
`readControlKey()`, `readAltKey()`, `readShiftKey()` now return actual state (not hardcoded false).
Shortcut callbacks (`onNewTab`, `onCloseTab`, `onPrevTab`, `onNextTab`, `onClearScreen`) wired in AndroidView `update` block.

### Color scheme picker (5 built-in themes)
- `TerminalSchemes` object with `Scheme` data class
- Themes: **Dark** (default), **Dracula**, **Solarized Dark**, **Monokai**, **Gruvbox**
- Applied via `TerminalColors.COLOR_SCHEME.mDefaultColors` (the static singleton all emulators reset from)
- Live apply: `mColors.reset()` + `onScreenUpdated()` triggers immediate re-render
- Picker accessible from terminal dropdown menu → "🎨 Color Scheme: [current]"
- Dialog shows color swatch + checkmark for active theme

### Hard rules learned:
- `TerminalColors.COLOR_SCHEME` is static — changing `mDefaultColors` there applies to ALL sessions. Call `mColors.reset()` on the emulator after to propagate.
- `readControlKey()` must return live state — TerminalView uses it to decide whether Ctrl+key combos go to the emulator or the host app. Hardcoding `false` breaks all Ctrl sequences from hardware keyboards.
- Shortcut callback lambdas must be re-wired in `update {}` block (not `factory {}`) — after `attachSession()` — because `viewClient` is set in `factory` and survives session changes.

### Still missing (next batch):
- Custom font loading from TTF file
- Bell mode preference (vibrate/beep/ignore) — currently always vibrate
- Back key → Escape setting (user preference)
- Auto-close tab on exit code 0/130
- Transcript URL long-press list (show all URLs in scrollback)
- Session list drawer (left-swipe)

---

## TERMINAL FEATURES — BATCH 3 (commit 459c1f1668, run 589 ✅, June 28, 2026)

### Full laptop-style extra keys bar (2 rows)

**Row 1 — F1–F12:**
| Key | VT Sequence |
|-----|-------------|
| F1–F4 | `\u001BOP` – `\u001BOS` (SS3) |
| F5–F12 | `\u001B[15~` – `\u001B[24~` (CSI tilde) |

**Row 2 — Modifiers + Nav + Symbols + Ctrl combos (horizontal scroll):**
- **Sticky modifiers:** `CTRL`, `ALT`, `SHFT` — tap to arm (turns blue), next key sends with modifier, auto-disarms. Only one modifier can be armed at a time.
- **Nav cluster:** `ESC`, `TAB`, `HOME` (`\u001B[H`), `END` (`\u001B[F`), `INS` (`\u001B[2~`), `DEL` (`\u001B[3~`), `PGUP` (`\u001B[5~`), `PGDN` (`\u001B[6~`), `↑↓←→`
- **Symbols:** `|` `/` `\` `~` `` ` `` `-` `_` `=` `+` `[` `]` `{` `}` `(` `)` `<` `>` `;` `:` `'` `"` `!` `@` `#` `$` `^` `&` `*`
- **Ctrl combos:** `C-c` `C-d` `C-z` `C-a` `C-e` `C-k` `C-u` `C-l` `C-r` `C-w` `C-b` `C-f` `C-p` `C-n` `C-t`

### Extra keys bar hidden by default
`showExtraKeys = false` — bar only appears when user toggles "Show Extra Keys" from the terminal menu (⋮).

### Hard rules learned (runs 584–589):
- `\n` inside a Kotlin string literal written via Python heredoc becomes a real newline in the source → always use `\\n` in Python when targeting Kotlin string content.
- The public field on `TerminalView` is `mClient` (type `TerminalViewClient`), NOT `mTerminalViewClient`. Casting: `view.mClient as? SimpleTerminalViewClient`.
- Shortcut callback lambdas (`onClearScreen` etc.) must use `\\n` not literal newline.

### Build failure post-mortem (runs 575–588, all now resolved):

| Runs | Root Cause | Fix |
|------|-----------|-----|
| 575–579 | `processProdDebugGoogleServices` — `com.codespace.ide.debug` missing from `google-services.json` | Added debug package to `google-services.json` (run 580) |
| 584–587 | `TerminalPane.kt` line ~1035/1087 — `\n` heredoc split into real newline inside Kotlin string | Escaped to `\\n` in Python write |
| 588 | `Unresolved reference: mTerminalViewClient` | Replaced with `mClient` (run 589) |

### Still pending (next batch):
- Custom TTF font loading
- Bell mode preference (vibrate / beep / silent)
- Back key → Escape user setting
- Auto-close tab on process exit (code 0 or 130)
- URL long-press list from scrollback transcript
- Session list drawer (left-swipe)

---

## ONBOARDING WALKTHROUGH (commits 3dc8dc2d32 + 5c4a9f8289, June 28 2026)

### Overview
First-launch onboarding tour implemented across 2 files:
- **`OnboardingWalkthrough.kt`** (new) — self-contained 8-step modal dialog
- **`ProjectShellScreen.kt`** — reads/writes `onboarding_seen` SharedPrefs flag, renders walkthrough on first open

### How it works
- On first launch after login, `ProjectShellScreen` checks `prefs.getBoolean("onboarding_seen", false)`.
- If `false`, renders `OnboardingWalkthrough` as a full-screen dialog overlay.
- When user taps **Get Started** (last step) or **Skip**, sets `onboarding_seen = true` in `app_prefs` SharedPreferences — never shown again.
- Steps can be navigated forward (Next) or backward (Back).

### 8 steps covered

| Step | Topic | Key tip shown |
|------|-------|---------------|
| 1 | Explorer | File icon in sidebar toggles panel |
| 2 | Code Editor | Pinch to zoom, ⋮ menu for split view |
| 3 | Terminal | ⌨ menu button shows full keyboard bar (F1–F12, Ctrl combos) |
| 4 | AI Assistant | Chat bubble icon top-right |
| 5 | Source Control | Branch icon in sidebar |
| 6 | Run & Debug | ▶ icon in sidebar |
| 7 | Extensions | Puzzle piece icon at bottom of sidebar |
| 8 | Settings & Themes | ⚙ gear icon at bottom of sidebar |

### UI design
- Dark card dialog (`#1E1E1E`) with slide + fade animation between steps
- Progress dots at top (filled = visited, current = large blue, future = grey)
- Per-step circular icon with tinted background matching VS Code icon colors
- Yellow lightbulb tip chip at bottom of each card
- **Skip** on step 1 only; **Back** on steps 2–8; **Next** / **Get Started** on last step

### Hard rules:
- `prefs` is declared inside the composable using `remember { context.getSharedPreferences("app_prefs", 0) }` — same prefs instance already used by `CodeSpaceApp` for theme persistence.
- `OnboardingWalkthrough` uses `dismissOnBackPress = false` and `dismissOnClickOutside = false` — user must explicitly tap Skip or Get Started.
- `AnimatedContent` slide direction: forward = slide left, backward = slide right.

---

## PREVIEW PANEL (commits dd6c6ad288 + aad5044a1c, June 28 2026)

### Files changed
- **`PreviewPane.kt`** (new) — self-contained preview compositor
- **`ProjectShellScreen.kt`** — added `BottomTab.PREVIEW` and wired it

### 4 preview modes (tab bar at top of panel)

| Mode | Trigger | What it does |
|------|---------|--------------|
| HTML | `.html`, `.htm`, `.css`, `.js`, `.ts` | Renders file in WebView with JS enabled |
| Markdown | `.md`, `.markdown` | Renders via `marked.js` (CDN) with VS Code dark styling |
| SVG | `.svg` | Renders SVG centered, JS disabled |
| Browser | Manual | Embedded browser with address bar — point at `localhost:3000` or any URL |

### How it works
- `PreviewPane` reads the active editor file path from `activeEditorTab` state in `ProjectShellScreen`
- File content is loaded from disk via `produceState` — refreshes whenever path changes
- Language/mode is auto-detected from file extension
- User can manually switch modes using the tab bar in the panel header
- Refresh button reloads the current WebView
- CSS preview injects content into a demo page with sample elements
- JS/TS preview wraps code in a console-capture sandbox (output appears on screen)
- Markdown preview loads `marked.js` from CDN (requires internet) — offline fallback planned

### Hard rules for future AI:
- `PreviewPane` has no dependency on `EditorPane` internals — it reads from disk directly using `activeEditorTab` file path
- WebView `loadDataWithBaseURL` must use `"https://cdn.jsdelivr.net"` as base for Markdown (so CDN script tag loads)
- `SuppressLint("SetJavaScriptEnabled")` is required on every `@Composable` that creates a WebView with JS
- Internet permission is already in AndroidManifest — no changes needed

---

---

## NEW FEATURE SPECS — June 28, 2026 (from user)

### 1. Editable Preview Panel
The preview panel currently shows HTML/Markdown/SVG read-only.
**Goal:** Make it a two-way editor — user can edit HTML/CSS/JS/Markdown directly inside the preview panel and changes reflect live.
- Add a split-view toggle in PreviewPane header: [Preview | Edit | Split]
- In Edit mode: show a CodeEditor (same as EditorPane) inside PreviewPane
- In Split mode: left = editor, right = live WebView with JS bridge for hot-reload
- Changes in the edit view auto-save to disk and trigger WebView reload
- Wiring: PreviewPane already reads `activeEditorTab` from ProjectShellScreen — it needs a write-back lambda too

### 2. Notification Bell — wired + functional
Currently: `Icons.Default.Notifications` in top bar is a dead icon (no click handler).
**Goal:** Make it a full notification centre.

**Behaviour:**
- Bell icon in top bar shows a badge number (count of unread notifications)
- On tap: opens a notification drawer (slide down from top bar)
- Each notification is a small curved-edge breadcrumb pill
- Auto-dismiss after 3 seconds (already have showNotification() for transient toasts)
- Persistent notifications (repo changes, build events, debug logs) stay until dismissed

**Notification sources to wire:**
- App internal: file saved, build started/succeeded/failed, shell command output
- GitHub: new commits on watched branch, PR opened, review requested
- Debug: app crash logs, signal kills, terminal process exits
- MCP: tool call results, agent actions

**Badge numbering:** red circle on bell icon with unread count, resets to 0 on open

**Breadcrumb style:** `RoundedCornerShape(16.dp)`, small pill, 3s auto-close for transient

### 3. "Three-legged" icon (Activity Bar bottom) — GitHub + Connectors hub
Currently: `Icons.Default.Person` at bottom of activity bar → `showPersonMenu = true` (basic menu).
**Goal:** Rename / rebrand to a Connectors hub. Wire it to:

**a) GitHub login + repo management:**
- GitHub OAuth login (already partially in AuthScreen/GitEngine)
- After login: show list of user's repos (clone, open, switch)
- Per-repo actions: Pull, Push, Commit, Branch, PR, Issues
- Show current repo name + branch in status bar

**b) All connectors (TIER 2 from roadmap):**
- GitHub, GitLab, SSH, Firebase, Vercel, Netlify, Docker Hub, AI providers
- Each connector shows: connected/disconnected status, quick actions
- Add/remove connectors from this panel

**Implementation:**
- Replace `showPersonMenu` with `showConnectorsSheet = true`
- New file: `ConnectorsSheet.kt` — ModalBottomSheet with tabs: [GitHub | SSH | Services | AI Keys]
- GitHub tab: login button → OAuth flow → repo list with actions
- Credentials in SecureTokenStore (already exists)

### 4. MCP access to everything
Currently MCP wired in McpShellProfile.kt with `MCP_SERVER_URL` env var.
**Goal:** MCP tools should have access to ALL app capabilities:
- Read/write files in the project
- Run terminal commands (ash + Ubuntu)
- Read/write editor content
- Trigger git operations (commit, push, pull)
- Read notification log
- Open files in editor
- Access ConnectorStore (repo list, connection status)
- Control preview panel (switch mode, reload)

**Implementation:** Expand MCP tool definitions in McpShellProfile.kt to expose all the above as tool calls. Each tool maps to an existing Kotlin function already in the codebase.

### 5. Google sign-in error (screenshot, June 28)
Error: `Sign-in cancelled: During begin sign in, failure response from one tap: 10: [28444] Developer console is not set up correctly`
**Root cause:** Google OAuth Client ID in `google-services.json` is missing the SHA-1 fingerprint for the debug keystore, OR the OAuth consent screen is not configured in Google Cloud Console.
**Fix needed:**
1. In Google Cloud Console → APIs & Services → Credentials → OAuth 2.0 Client IDs
2. Add SHA-1 of the debug keystore: run `keytool -list -v -keystore ~/.android/debug.keystore -alias androiddebugkey -storepass android -keypass android`
3. Add that SHA-1 to the Android OAuth client
4. Re-download `google-services.json` and replace in repo
OR: Use GitHub OAuth as primary sign-in instead (already wired) and remove/skip Google sign-in for now.

---

## MASTER ROADMAP — Full IDE Intelligence Layer

> This section is the north star for all future AI sessions. Every item below is planned, not yet built. Work top-down.

### TIER 1 — Preview & Run (immediate next)
- [ ] **Hot-reload preview** — FileObserver on the active file, auto-refresh WebView on save
- [ ] **Port forwarding UI** — list of forwarded ports in the PORTS tab, clickable → opens in Browser preview
- [ ] **Run button** — detect `package.json` / `main.py` / `Makefile`, offer one-tap run in terminal

### TIER 2 — Connectors (external service integrations)
Each connector is a `ConnectorManager` singleton + a UI sheet. All credentials stored in `SecureTokenStore` (Android Keystore).

| Connector | Purpose | Key APIs |
|-----------|---------|----------|
| **GitHub** | Clone, push, PR, issues | GitHub REST + GraphQL |
| **GitLab** | Same as GitHub for GitLab repos | GitLab REST |
| **Firebase** | Deploy, view Firestore, read logs | Firebase CLI via terminal |
| **Vercel** | Deploy frontend projects | Vercel REST API |
| **Netlify** | Same for Netlify | Netlify REST API |
| **Docker Hub** | Pull/push images | Docker Registry API |
| **OpenAI / Anthropic / Gemini** | BYOK AI keys | AiRegistry (already wired) |
| **SSH** | Remote server access | SshManager (already built) |
| **Ngrok / Cloudflare Tunnel** | Expose localhost to internet | CLI via terminal |

**Architecture:** `ConnectorStore.kt` — stores connector configs (name, type, credentials, last-used). `ConnectorSheet.kt` — unified bottom sheet for add/edit/delete. Shown from Extensions tab.

### TIER 3 — Automations (in-app task scheduler)
Automations run shell scripts or AI prompts on a trigger. Stored as JSON in app storage.

| Trigger type | Example |
|-------------|---------|
| On file save | Auto-lint, auto-format, auto-commit |
| On schedule | Daily git pull, nightly backup |
| On terminal output | Alert when build fails / tests pass |
| On git event | Auto-push after commit |

**Architecture:** `AutomationStore.kt` — list of `Automation(id, name, trigger, action, enabled)`. `AutomationRunner.kt` — executes shell commands via NativePty. `AutomationSheet.kt` — UI to create/edit/delete. Accessible from Extensions or Settings menu.

### TIER 4 — Memory & Chat History
Already partially built (`ai_chat_history` SharedPrefs in `AiAssistantPane`). Needs upgrade:

| Feature | Implementation |
|---------|---------------|
| **Per-project chat history** | Key by `projectId` not just app-global |
| **File context injection** | Auto-attach open file content to AI prompt (already done via `fileCtx`) |
| **Conversation search** | Search past messages by keyword |
| **Memory pinning** | Pin key facts ("my backend URL is X") that persist across sessions |
| **Export chat** | Export conversation as Markdown |

**Architecture:** `ChatMemoryStore.kt` — replaces raw SharedPrefs. Stores `List<ChatSession>` per project. `MemoryPin.kt` — pinned facts injected into every system prompt.

### TIER 5 — Skills (reusable AI-powered commands)
A "skill" is a named prompt template + optional shell command, runnable from the command palette.

| Skill | What it does |
|-------|-------------|
| `explain` | AI explains selected code |
| `refactor` | AI rewrites for clarity |
| `write-tests` | AI generates unit tests for current file |
| `git-message` | AI writes commit message from staged diff |
| `summarize-file` | AI summarizes what the current file does |
| `docker-run` | Builds and runs project in Docker |
| `deploy-vercel` | Runs `vercel --prod` in terminal |
| `search-web` | Opens Browser preview with search query |

**Architecture:** `SkillRegistry.kt` — list of `Skill(id, name, icon, promptTemplate, shellCommand?)`. Skills appear in command palette (Ctrl+Shift+P) and AI quick-action bar. Custom skills saved to `custom_skills.json` in project storage. Skills can chain: prompt → insert output into editor OR run in terminal.

### TIER 6 — File Memory & Project Index
The AI currently only knows the open file. Full project awareness requires:

- `ProjectIndexer.kt` — walks project tree, extracts symbols (functions, classes, exports) per file
- `EmbeddingStore.kt` — stores text chunks with simple keyword index (no vector DB needed on-device)
- `RetrievalEngine.kt` — given a query, returns the top-N most relevant chunks from the project
- Inject retrieved chunks into `AiContext.retrievedChunks` (field already exists in `AiProvider.kt`)

### TIER 7 — Notifications & Background Agents
- `BuildNotifier.kt` — watches terminal output for `BUILD SUCCESSFUL` / `error:` patterns, fires OS notification
- `GitPollAgent.kt` — background job, polls remote every X min, notifies on new commits
- `CrashWatcher.kt` — monitors `logcat` output for the app's own package name, surfaces crash summary in Problems panel

### Hard rules for future AI building these tiers:
1. All credentials go through `SecureTokenStore` (Android Keystore) — never plain SharedPrefs for secrets
2. All network calls go through the existing `OkHttpClient` in `AppModule.kt` — do not create new instances
3. All new bottom sheets follow the `SshManagerSheet.kt` pattern (ModalBottomSheet + ViewModel-free state hoisting)
4. Automations must use `NativePty` (not `ProcessBuilder`) for shell execution
5. Skills that call AI use `AiRegistry.create()` — never hardcode API endpoints
6. Every new file gets documented in this AGENTS.md section before the PR is merged


---

## PREVIEW GUIDE + ONBOARDING STEP 9 (commits 08274be230 + 3c1429667d, June 28 2026)

### OnboardingWalkthrough.kt — step 9 added
- New `OnboardingStep` for "Preview Panel" (icon: Visibility, pink tint)
- `OnboardingStep` data class now has `bullets: List<String>` field — renders a bulleted list above the tip chip
- Preview step uses bullets to list all 4 modes: HTML, Markdown, SVG, Browser
- Tip: "Open any .html / .md / .svg file then tap PREVIEW in the bottom panel."

### PreviewPane.kt — in-panel how-to-use guide
- `?` (HelpOutline) icon added to the top-right control row of the preview header
- Tapping it opens an `AlertDialog` with a `PreviewGuideRow` for each of the 4 modes
- Each row shows: colored mode badge + plain-English description of what it does and what files trigger it
- Bottom tip chip: "Tap ↺ to manually refresh. The preview auto-updates when you switch files."
- `PreviewGuideRow` is a private helper composable at the bottom of the file

### Hard rules:
- `AlertDialog` `containerColor` must be explicitly set to `Color(0xFF1E1E1E)` — default Material3 container is white
- `bullets` field defaults to `emptyList()` so all existing steps are unaffected

---

## BUILD FIX SESSION — June 28, 2026 (Superagent / Base44)

### What failed (29 consecutive CI failures, all "Build Android APK")

Root cause: A large refactor split `ProjectShellScreen.kt` (600-line composable → separate overlay files).
The extraction left **private-in-file types being referenced across file boundaries** — a Kotlin visibility rule violation.

**Exact compiler errors fixed:**

| File | Error |
|------|-------|
| `ConnectorsHubSheet.kt` | `Unresolved reference: theme` (bad import path for IdeColors) |
| `ConnectorsHubSheet.kt` | `'internal' function exposes 'private-in-file' IdeColors` |
| `ConnectorsHubSheet.kt` | `Cannot access 'ConnectorRow': it is private in file` (×4 call sites) |
| `CopilotChatPanelOverlay.kt` | `Unresolved reference: theme` |
| `CopilotChatPanelOverlay.kt` | `'internal' function exposes 'private-in-file' IdeColors` |
| `NotificationDrawerOverlay.kt` | `'internal' function exposes 'private-in-file' NotifItem` (×3) |
| `ProjectShellScreen.kt` | `Unresolved reference: colors` (×2 — passing removed param) |

### Fixes applied (commits de86a5d2 → 39e97845, June 28 2026)

**`ConnectorsHubSheet.kt`**
- Removed `IdeColors` import + parameter entirely (it was unused — the sheet uses hardcoded VS Code colors)
- Moved `ConnectorRow` composable INTO this file as `internal` (was `private` in ProjectShellScreen)
- `ConnectorsHubSheet` itself is `internal` — correct, callable from same package

**`CopilotChatPanelOverlay.kt`**
- Removed `IdeColors` import + `colors: IdeColors` parameter entirely (unused)
- Replaced deprecated `Divider()` with `HorizontalDivider()` (Material3)
- `CopilotChatPanelOverlay` remains `internal`

**`NotificationDrawerOverlay.kt`**
- Moved `NotifItem` data class into THIS file as `internal data class NotifItem` (was `private` in ProjectShellScreen)
- Replaced deprecated `Divider()` with `HorizontalDivider()` (Material3)

**`ProjectShellScreen.kt`**
- Removed `private data class NotifItem` (now lives in NotificationDrawerOverlay.kt)
- Removed `@Composable private fun ConnectorRow` block (now lives in ConnectorsHubSheet.kt)
- Fixed call site: `CopilotChatPanelOverlay(colors = colors, ...)` → `CopilotChatPanelOverlay(...)`
- Fixed call site: `ConnectorsHubSheet(colors = colors, ...)` → `ConnectorsHubSheet(...)`

### Result
- ✅ Build succeeded: run `28337666579`
- ✅ APK artifact uploaded: `app-prod-arm64-v8a-debug.apk`
- ✅ Terminal layer (TerminalPane, TerminalService, NativePty, ProotInstaller, BusyboxInstaller, TermuxBootstrapInstaller) — **zero files touched**

### Hard rules added from this session

7. When extracting a composable to its own file: **always check visibility of every type it references**. If the type is `private` or `private-in-file` in the source file, either (a) move it to the new file as `internal`, or (b) create a shared `Models.kt` in the same package.
8. Never pass a `private-in-file` type as a parameter to an `internal` function — Kotlin forbids this across files even in the same package.
9. `IdeColors` lives as a `private data class` inside `ProjectShellScreen.kt`. Do NOT import it from `com.codespace.ide.ui.theme` — it is not there. If overlay files genuinely need theme colors, pass individual `Color` values or read from `MaterialTheme` directly.
10. After every push to the repo (success or failure), update this AGENTS.md with: what changed, what commit SHA, and what the build result was.
11. When a suggestion is made (by the user or agent) about code patterns, rules, or conventions — add it to the Hard Rules section immediately, not later.


---

## FEATURE: Optional Biometric / PIN Lock — June 29, 2026

### What was built (commits: 3 pushes, all ✅ success)

**`SecureTokenStore.kt`**
- Added `biometricLockEnabled: Boolean` — encrypted pref backed by Android Keystore
- Key: `KEY_BIOMETRIC_LOCK = "biometric_lock_enabled"`, default `false` (opt-in, not forced)

**`SettingsScreen.kt`** — new **Security** section (above AI Providers)
- Shows `BiometricManager.canAuthenticate()` check — if device has no biometric/PIN set up, toggle is shown but disabled with an explanatory message
- Toggle saved immediately to `tokenStore.biometricLockEnabled` — no Save button needed
- Icon: `Icons.Default.Fingerprint`, tinted primary when active
- Supporting text changes live: "App requires fingerprint or PIN on every launch" vs "Off — anyone who opens the app gets straight in"

**`CodeSpaceApp.kt`** — `BiometricGate` composable
- Checked once per process launch: if `!tokenStore.biometricLockEnabled` → skips gate entirely (no overhead)
- Full-screen lock UI with Fingerprint icon + "Try Again" button
- Uses `BiometricPrompt` with `BIOMETRIC_WEAK | DEVICE_CREDENTIAL` — supports fingerprint, face, or PIN fallback
- `LaunchedEffect(Unit)` auto-shows the system prompt on entry
- On success → sets `biometricUnlocked = true` → normal NavHost renders
- On cancel/error → stays on gate screen, user can tap "Try Again"
- `androidx.biometric:biometric:1.2.0-alpha05` was already in `build.gradle.kts` — no dep changes needed

### Design decision
Biometric lock is **opt-in via Settings** (not on by default). Reason: don't block developer during active development. User enables it when the app is ready to hand off or leave on a desk.

### Hard rules added
12. Biometric auth must always use `BIOMETRIC_WEAK or DEVICE_CREDENTIAL` — never `BIOMETRIC_STRONG` alone (excludes PIN fallback on devices with weak biometric hardware).
13. Security settings (biometric, account switching) live in the **app-level Settings** (HomeScreen → Settings gear), NOT inside project settings. Project settings = per-project config only.


---

## BUG FIX: Google Sign-In SHA-1 Mismatch — June 29, 2026

### Root cause
`google-services.json` in the repo had the wrong `certificate_hash` for both app clients:
- **Wrong (was in repo):** `09539806fb99759995d35e2b6aa562a2191b83f5`
- **Correct (from Drive / debug.keystore):** `4d893a14f7acd523ffd19f34957d5e4a7bda9223`

Firebase Credential Manager rejects Google Sign-In when the APK's signing SHA-1 doesn't match what's registered. This caused the "Unable to resolve host / Sign-in cancelled" error on AuthScreen — the Google account picker either failed silently or threw a `GetCredentialException` before the network call even happened.

### Fix
Updated `certificate_hash` in both client entries (`com.codespace.ide` and `com.codespace.ide.debug`) to the correct SHA-1 from the debug.keystore committed in the repo.

- Commit: `b4294b0d`
- Build: ✅ success (run 28349433333)

### Source of truth for credentials
All credentials live in Google Drive → "Codespace IDE — Dev Credentials" folder:
- `credentials-and-keys.md` — SHA-1, client IDs, package names, Firebase project info
- `google-services.json` — the authoritative copy to use in the repo

### Hard rule added
14. **Always verify** `google-services.json` `certificate_hash` matches the committed `debug.keystore` SHA-1 (`4d893a14f7acd523ffd19f34957d5e4a7bda9223`) before blaming auth code for sign-in failures. SHA-1 mismatch is silent — no obvious build error, only a runtime sign-in crash.

### Remaining login issue
The app still hits `https://api.codespace-ide.app/api/v1/auth/google` after Firebase sign-in succeeds. That domain has no server. The backend (`/backend` NestJS app) needs to be deployed (Railway / Render / VPS) and the domain DNS pointed at it before full login works end-to-end.

---

## RULE: AI Agent Communication — June 29, 2026

### Rule 15 — Never use code blocks for anything Wisdom needs to copy
Wisdom cannot select or copy text from markdown code blocks (backtick or fenced blocks) in the Base44 chat UI. All terminal commands, URLs, file paths, and any text Wisdom needs to type or paste must be written as plain inline text in the message. This is a hard rule for ALL Base44 agent responses in this project.

---

## CURRENT STATUS — June 29, 2026 (06:04 WAT)

### Where we are right now

**Completed this session:**
- Biometric lock (opt-in) added to app-level Settings — SecureTokenStore.kt, SettingsScreen.kt, CodeSpaceApp.kt — all builds green
- google-services.json SHA-1 corrected (09539806 → 4d893a14) — build green
- AGENTS.md updated after every push ✅

**Blocked — in progress:**
We discovered the SHA-1 registered in Firebase may still be wrong or there were multiple conflicting fingerprints added on previous days. We are doing a clean slate:

Steps remaining before Google Sign-In works:
1. Wisdom runs this command in terminal on TECNO KL4:
   keytool -list -v -keystore ~/.android/debug.keystore -alias androiddebugkey -storepass android -keypass android 2>/dev/null | grep -A2 "SHA"
2. Wisdom adds the SHA-1 and SHA-256 from that output to Firebase console for both package names:
   - Production: https://console.firebase.google.com/project/codespace-ide-2026/settings/general/android:com.codespace.ide
   - Debug: https://console.firebase.google.com/project/codespace-ide-2026/settings/general/android:com.codespace.ide.debug
3. Wisdom downloads fresh google-services.json from Firebase
4. Agent pushes it to repo, updates Google Drive credentials-and-keys.md, updates AGENTS.md

**Next blocker after login:**
Backend NestJS app (/backend folder) is fully written but NOT deployed. The domain api.codespace-ide.app has no DNS or server. After login is fixed this is the next task — deploy to Railway or Render (free tier).

**Remotion (Option C) — planned, not started:**
Decision: Remotion Studio runs as local Node server, accessed via existing PreviewPane browser tab. Implementation not started yet. Waiting for login + backend to be stable first.

---

## SHA-1 Fingerprint Confirmed — June 29, 2026

### Source of truth — debug keystore SHA fingerprints
Extracted directly from GitHub Actions signingReport (build run 28350524042):

SHA-1:   09:53:98:06:FB:99:75:99:95:D3:5E:2B:6A:A5:62:A2:19:1B:83:F5
SHA-256: CC:C5:0B:7F:2C:F9:43:A1:8A:1A:22:92:A4:0C:0A:60:EA:C4:60:BD:2B:EA:FD:ED:7B:42:37:9B:28:45:E8:B7

These must be registered in Firebase for both com.codespace.ide and com.codespace.ide.debug.

### Rule 16 — SHA-1 source of truth is signingReport from CI, not from device
Never assume or derive the SHA-1 from a device keystore. Always extract it from the GitHub Actions signingReport step. The CI keystore (auto-generated debug.keystore) is what signs the APK — that is the only SHA-1 that matters for Firebase registration.

### Rule 17 — Credentials-and-keys.md in Google Drive is the single source of truth
After any credential change (SHA-1, client IDs, API keys, package names), update:
1. google-services.json in the repo
2. credentials-and-keys.md in Google Drive (folder: Codespace IDE — Dev Credentials)
3. AGENTS.md (this file)
All three must match at all times.

### Next actions (in order)
1. Go to Firebase console and update SHA fingerprints for both packages (links in credentials-and-keys.md)
2. Download fresh google-services.json from Firebase, share here for push to repo
3. Deploy NestJS backend (/backend folder) to Railway or Render
4. Implement Remotion Studio button in PreviewPane (Option C)

---

## google-services.json updated — June 29, 2026 (06:39 WAT)

Fresh google-services.json downloaded from Firebase and pushed to android/app/google-services.json.
Also synced to Google Drive (Codespace IDE — Dev Credentials folder).

SHA-1 registered in Firebase (production app com.codespace.ide): 09:53:98:06:FB:99:75:99:95:D3:5E:2B:6A:A5:62:A2:19:1B:83:F5

NOTE: The debug package (com.codespace.ide.debug) does not yet have an Android OAuth client
with the SHA-1 in this json — it only has the web client. This means Google Sign-In will work
on production debug builds but the debug package variant may still fail.
Next action: add SHA-1 fingerprint to com.codespace.ide.debug in Firebase console too.
Link: https://console.firebase.google.com/project/codespace-ide-2026/settings/general/android:com.codespace.ide.debug

### Rule 18 — Switch account option
The app must show a "Switch account / Use different account" button on the login screen.
This is separate from the biometric lock toggle. It clears the cached Google credential
and forces the user to pick a new Google account on next sign-in.

---

## google-services.json FINAL — both packages complete (June 29, 2026 06:45 WAT)

Final google-services.json pushed to repo and synced to Google Drive.
Both Android packages now have the correct SHA-1 fingerprint registered:

- com.codespace.ide       → OAuth client: 872673459882-vess8kh6asgn6g184en67i8hb692pgs1
  SHA-1: 09539806fb99759995d35e2b6aa562a2191b83f5
- com.codespace.ide.debug → OAuth client: 872673459882-u9p1lv1q1hq864kfepma6rm3cme2kfa1
  SHA-1: 09539806fb99759995d35e2b6aa562a2191b83f5

Google Sign-In is now fully configured on the Firebase side.
Remaining blocker: NestJS backend at api.codespace-ide.app is not deployed.
After Firebase auth succeeds, the app will call the backend and fail until it is live.

### Next priorities (in order)
1. Deploy NestJS backend (/backend folder) to Railway or Render — this unblocks full login
2. Add "Switch account" button to login screen (Rule 18)
3. Implement Remotion Studio button in PreviewPane (Option C)

---

## Cloud Project Sync + Auth Screen Overhaul — June 29, 2026

### What changed
- AuthScreen.kt fully rewritten:
  - Sign In / Sign Up tabs — visually separate flows
  - "Use a different Google account" expands a text field where user types their email
  - Google Credential Manager opens pre-filled with that email as a login hint
  - Works for accounts not yet on the device — Google's own sign-in sheet handles adding them
  - "Switch Google account" link in HomeScreen bottom section
- HomeScreen.kt fully rewritten:
  - Accepts `accessToken` and `onSignOut` parameters
  - On launch: auto-fetches projects from backend (GET /api/v1/projects)
  - Falls back to local SharedPreferences cache when offline
  - Creating a project: saved locally + pushed to cloud immediately
  - Deleting a project: removed locally + deleted from cloud
  - Manual sync button (↻) in top bar
  - "Switch Google account" shortcut at bottom of list and on empty state
- Backend: new projects module added
  - backend/src/projects/project.entity.ts — Project TypeORM entity (id, name, kind, pathOrUrl, defaultBranch, ownerId)
  - backend/src/projects/projects.service.ts — list, upsert, remove (scoped to user)
  - backend/src/projects/projects.controller.ts — GET /projects, POST /projects, PUT /projects/:id, DELETE /projects/:id (JWT-guarded)
  - backend/src/projects/projects.module.ts — module definition
  - backend/src/app.module.ts — ProjectsModule registered

### Rule 19 — Projects are user-scoped cloud entities
Projects must always be associated with the authenticated user (ownerId = JWT userId).
Never return another user's projects. All project endpoints require JWT auth guard.

### Rule 20 — HomeScreen requires accessToken + onSignOut props
HomeScreen now requires both props. Any navigation call to HomeScreen must pass the
JWT accessToken from AuthResult and a callback that clears auth state and returns to AuthScreen.
