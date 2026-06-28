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

