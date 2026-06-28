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

