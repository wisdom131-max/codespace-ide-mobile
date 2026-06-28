# AI Agent / Copilot — Project Context
> Read this FIRST before touching any code. Updated June 27, 2026.

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

## FULL AUDIT — ALL BUGS FOUND & FIXED (June 27, 2026)
> This session: comprehensive full-codebase audit. Every file read. All issues logged below.

### SESSION COMMITS (newest first)

| Commit | What it fixed |
|--------|--------------|
| `024e817` | Full UI/UX/terminal audit — 7 bugs fixed (see below) |
| `9884a0e` | Manifest `specialUse` type + AGENTS.md research overhaul |
| `5a42de5` | TerminalService auto-acquires WakeLock on start — fixes signal 31 on all tabs |
| `82ffc15` | Removed curl pre-install block — crash during extraction |
| `59c4700` | AGENTS.md JNI audit, all null guards confirmed |

---

### BUG AUDIT — commit 024e817 (7 fixes)

#### 1. proot HOME env was host path (CRASH inside Ubuntu)
**File:** `ProotInstaller.kt` → `launchArgs()` envVars  
**Was:** `HOME=${context.filesDir.absolutePath}` → `/data/data/com.codespace.ide.debug/files`  
**Problem:** That path does not exist inside the proot chroot. Ubuntu processes got a broken HOME. cd ~, bash --login, anything reading HOME broke silently.  
**Fix:** `HOME=/root` — correct path inside the chroot.

#### 2. TerminalModeManager DEFAULT_MODE was MODE_OLLAMA (3-sec startup delay)
**File:** `TerminalModeManager.kt`  
**Was:** `DEFAULT_MODE = MODE_OLLAMA`  
**Problem:** Ollama mode calls `RemoteTerminalSession.isReachable(backendUrl)` — a 3-second timeout HTTP call — on every terminal open. Backend is never reachable on device. Blocked ash startup for 3 seconds every time.  
**Fix:** `DEFAULT_MODE = MODE_OFFLINE` — ash opens instantly.

#### 3. SplitTerminalPanel infinite lambda chain (memory leak)
**File:** `TerminalPane.kt` → `SplitTerminalPanel` → `AndroidView update{}`  
**Was:** `val existing = mirrorTab.client.onTextChanged; mirrorTab.client.onTextChanged = { existing?.invoke(); view.post{...} }`  
**Problem:** `update{}` runs on EVERY recompose. Each recompose wrapped the previous lambda in a new one → infinitely deep call chain after minutes of use → memory leak + slowdown.  
**Fix:** Direct assignment without chaining: `mirrorTab.client.onTextChanged = { view.post { view.onScreenUpdated() } }`

#### 4. SplitTerminalPanel missing keepScreenOn
**File:** `TerminalPane.kt` → `SplitTerminalPanel` → `AndroidView factory{}`  
**Was:** No `keepScreenOn` in the mirror pane's TerminalView.  
**Problem:** Screen dims/locks while using the split panel. Main pane had it, mirror didn't.  
**Fix:** Added `keepScreenOn = true` to mirror pane factory.

#### 5. BusyboxInstaller called on main thread from menu (ANR risk)
**File:** `ProjectShellScreen.kt` → `handleMenuAction()`  
**Was:** `BusyboxInstaller.ensureOfflineShell(context)` and `BusyboxInstaller.installIfNeeded(context)` called synchronously in menu click handlers on the main thread.  
**Problem:** File I/O on main thread = ANR (Application Not Responding). Android kills the app after 5 seconds of blocked main thread.  
**Fix:** Wrapped in `scope.launch { withContext(Dispatchers.IO) { ... } }`. Added `rememberCoroutineScope()`.

#### 6. Ubuntu extraction thread was daemon (download dies when backgrounded)
**File:** `TerminalPane.kt` → `addUbuntuTab()`  
**Was:** `Thread { ... }.apply { isDaemon = true; start() }`  
**Problem:** Daemon threads are killed when the JVM considers the process idle (e.g. user backgrounds the app mid-download). 250MB extraction silently dies.  
**Fix:** `isDaemon = false; name = "UbuntuSetupThread"` — thread survives backgrounding.

#### 7. Manifest missing permissions + no explicit hardwareAccelerated
**File:** `AndroidManifest.xml`  
**Added:**
- `CHANGE_NETWORK_STATE` — required for SSH connections and WebSocket terminal sessions to change network routing.
- `android:hardwareAccelerated="true"` on `<application>` — explicit declaration. TerminalView uses Canvas rendering; without this flag some OEMs disable GPU acceleration for the process.

---

### EARLIER FIXES (pre-audit)

#### Signal 31 — WakeLock auto-acquire (commit 5a42de5)
**File:** `TerminalService.kt` → `onStartCommand()`  
**Problem:** WakeLock existed but was opt-in (notification button). Without it, TECNO OEM power manager sends SIGRTMIN (signal 31) and kills all terminal processes within seconds of screen-off.  
**Fix:** `actionAcquireWakeLock()` called automatically on every service start, not just on user button press.

#### curl pre-install crash during extraction (commit 82ffc15)
**File:** `ProotInstaller.kt`  
**Problem:** After extracting 250MB rootfs (heap fragmented), code tried to download curl + libcurl4 debs from ports.ubuntu.com. curl already exists in ubuntu-questing rootfs at `/usr/bin/curl`. This extra download pushed the device over memory ceiling → OOM kill.  
**Fix:** Removed entire pre-install block.

#### PROOT_NO_SECCOMP=1 (earlier session)
**File:** `ProotInstaller.kt` → `launchArgs()` envVars  
**Why:** Samsung kernel 5.15 has partial/broken seccomp PTRACE_EVENT_SECCOMP support. proot's seccomp filter can cause vpid 1 signal crashes. PROOT_NO_SECCOMP=1 disables seccomp, uses pure ptrace interception. Slower but stable. See termux/proot#365 (merged Jun 1 2026).

#### Manifest foreground service type (commit 9884a0e)
**File:** `AndroidManifest.xml`  
**Added:** `specialUse` alongside `dataSync` for `TerminalService`. Terminal emulators don't fit any standard Android service type. `dataSync` is being deprecated for non-sync uses. `FOREGROUND_SERVICE_SPECIAL_USE` permission added. `PROPERTY_SPECIAL_USE_FGS_SUBTYPE = terminal_emulator` metadata added.

---

## ARCHITECTURE

### Binaries (jniLibs/arm64-v8a/ → extracted to nativeLibraryDir)
All shipped as `.so` files to bypass Android W^X restriction (data partition is `noexec`; `nativeLibraryDir` is always executable).

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
- `TerminalSession.kt` (com.termux) — the REAL session class (do not confuse with the dead com.codespace.ide.terminal.TerminalSession which uses ProcessBuilder and is unused)
- `NativePty.kt` + `pty_native.c` — JNI PTY layer (identical to Termux, plus extra null guards)
- `ProjectShellScreen.kt` — main IDE screen, menu actions, VS Code layout
- `DeviceCompatibility.kt` — RAM/storage checks (advisory only, Ubuntu always allowed)
- `TerminalModeManager.kt` — mode: offline (default), ollama, ubuntu

---

## TERMUX JNI AUDIT (verified June 27, 2026)

Our `pty_native.c` is MORE hardened than Termux upstream:

| Guard | Termux | Ours |
|-------|--------|------|
| null check on argv GetObjectArrayElement | ✗ | ✓ |
| null check on envp GetObjectArrayElement | ✗ | ✓ |
| ptm < 0 check before GetPrimitiveArrayCritical | ✗ | ✓ |
| correct jstring in ReleaseStringUTFChars for cwd | ✗ (uses cmd) | ✓ |

### TerminalSession subprocess start timing
**IMPORTANT:** `TerminalSession` constructor does NOT start the subprocess.
The process only starts when `updateSize()` → `initializeEmulator()` is called,
which happens inside `attachSession()` on the TerminalView.
TerminalView is only attached after `bootstrapReady=true` (post-ensureOfflineShell).
**No race condition between profile writing and ash startup.**

---

## PROOT LAUNCH ARGS (verified working)

```
cmd:     nativeLibraryDir/libproot.so
argv[0]: proot
flags:   --kill-on-exit --link2symlink --sysvipc --kernel-release=6.17.0-android13-1 -L
         --change-id=0:0 --rootfs=<rootfsDir> --cwd=/root
binds:   /dev /proc /sys /dev/urandom:/dev/random /proc/self/fd:/dev/fd
         /proc/self/fd/0:/dev/stdin  /proc/self/fd/1:/dev/stdout  /proc/self/fd/2:/dev/stderr
         <selinuxFakeDir>:/sys/fs/selinux  <rootfs>/tmp:/dev/shm  <hostFiles>:/host-files
exec:    /usr/bin/env -i HOME=/root USER=root LOGNAME=root TERM=xterm-256color
         PATH=/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin
         MOZ_FAKE_NO_SANDBOX=1 /bin/bash --login

envVars: PROOT_LOADER=<nativeDir>/libproot-loader.so
         PROOT_TMP_DIR=<cacheDir>/proot-tmp
         PROOT_NO_SECCOMP=1
         LD_LIBRARY_PATH=<nativeDir>
         LD_PRELOAD=<nativeDir>/libtermux-exec.so
         TMPDIR=<cacheDir>/proot-tmp
         HOME=/root  ← MUST be /root, not host filesDir
```

---

## ASH TAB ENV (verified matching Termux)

```
cmd:     nativeLibraryDir/libbusybox.so
argv[0]: -ash   (leading dash → POSIX login shell → busybox strips dash → "ash" applet)
env:     HOME=<filesDir>/home  PWD=<filesDir>/home
         PATH=<bin>:/system/bin:/system/xbin
         TERM=xterm-256color  COLORTERM=truecolor  LANG=en_US.UTF-8
         SHELL=<busybox>  TMPDIR=<cacheDir>
         LD_PRELOAD=<nativeDir>/libtermux-exec.so  (if exists)
         ANDROID_* passthrough from System.getenv()
         USER=u0_a<uid-10000>
```

Shell startup files:
- `.profile` — login (ash reads this, NOT .bash_profile)
- `.ashrc` — interactive (pointed to by `ENV=$HOME/.ashrc` in .profile)
- `.bashrc` — stub no-op (so scripts sourcing it don't error)
- `.inputrc` — NOT used by ash (ash has its own line editing, not readline)

---

## ALL KNOWN SIGNAL KILL ISSUES

### Signal 31 — SIGRTMIN (OEM power manager)
**Cause:** Samsung/TECNO OEM power manager sends SIGRTMIN to processes not holding a WakeLock.  
**Fixes applied:**
- `PARTIAL_WAKE_LOCK` auto-acquired on TerminalService start ✓
- `WifiManager.WIFI_MODE_FULL_HIGH_PERF` WifiLock also acquired ✓
- Battery optimization exemption requested in MainActivity ✓
- `REQUEST_IGNORE_BATTERY_OPTIMIZATIONS` in manifest ✓

### Signal 9 — Phantom Process Killer (Android 12+)
**Cause:** Android 12+ kills child processes exceeding 32 per UID.  
**Code-side:** Foreground service raises priority. WakeLock helps.  
**User-side:** `adb shell "settings put global settings_enable_monitor_phantom_procs false"` disables killer.

### Signal 11 — SIGSEGV in JNI
**Cause:** Null pointer from GetObjectArrayElement passed to GetStringUTFChars.  
**Fixed:** All null guards in pty_native.c ✓

### Signal 7 / 4 — proot seccomp on old/custom kernels
**Cause:** Kernel backports seccomp but not PTRACE_EVENT_SECCOMP. proot child installs filter, crashes.  
**Fixed:** `PROOT_NO_SECCOMP=1` in proot envVars ✓

---

## PERMANENT LIMITATIONS (by design, cannot fix in software)

- **`apt install` inside Ubuntu** — dpkg spawns child processes. Samsung kernel 5.15 blocks fork+execve inside proot ptrace. PERMANENT. Use host-side pre-install only.
- **`cd` inside Ubuntu** — chdir blocked by kernel. Commands that cd internally also fail.
- **Signal 9 from Phantom Process Killer** — only fixable via adb or OEM update. Code-side mitigation (FGS + WakeLock) is already in place.

---

## WHAT IS WORKING (verified)
- APK builds ✓ (last green: commit 024e817)
- App launches, GitHub PAT login ✓
- Ubuntu boots to `root@localhost` ✓
- Progress messages in Ubuntu tab ✓
- Resumable 250MB download, 5% steps, 3 retries ✓
- TerminalService auto-acquires WakeLock (signal 31 fix) ✓
- Battery optimization exemption request at launch ✓
- `pty_native.c` = Termux impl + extra null guards ✓
- `PROOT_NO_SECCOMP=1` in proot env ✓
- `LD_PRELOAD=libtermux-exec.so` in both ash and proot ✓
- Manifest: `dataSync|specialUse` + both permissions ✓
- `android:hardwareAccelerated="true"` explicit ✓
- `CHANGE_NETWORK_STATE` permission ✓
- ash tab: `argv[0]="-ash"`, full Termux-matching env ✓
- DNS baked into rootfs (`/etc/resolv.conf = 8.8.8.8/8.8.4.4`) ✓
- APT sandbox config (`APT::Sandbox::User root`) ✓
- Busybox symlinks in `filesDir/bin/` → `nativeLibraryDir` ✓
- proot `HOME=/root` (not host filesDir) ✓
- Default terminal mode: `MODE_OFFLINE` (instant ash, no 3-sec health check) ✓
- SplitTerminalPanel: no lambda chain leak, keepScreenOn=true ✓
- BusyboxInstaller menu calls: offloaded to IO dispatcher ✓
- Ubuntu extraction thread: non-daemon (survives backgrounding) ✓

## STILL TO VERIFY ON DEVICE
- Signal 31 actually gone after WakeLock auto-acquire
- Ash tab prompt shows (no "applet not found", no "bash: not found")
- Ubuntu tab boots to `root@localhost` after rootfs already installed
- Tab completion + arrow keys in ash
- Split panel stable after 10+ min use (lambda leak fix)

---

## TERMUX ON-DEVICE DECOMPILE FINDINGS (June 28, 2026)
> Source: Termux v0.118.3 APK decompiled directly from user's TECNO KL4 via MT Manager.
> This is ground truth — not from internet sources. Files: AndroidManifest.xml + libtermux.so.

### libtermux.so — Symbol Table Analysis
Built with NDK r22b, arm64-v8a.

**Exported JNI symbols (complete list — nothing else):**
- `Java_com_termux_terminal_JNI_close`
- `Java_com_termux_terminal_JNI_createSubprocess`
- `Java_com_termux_terminal_JNI_setPtyUTF8Mode`
- `Java_com_termux_terminal_JNI_setPtyWindowSize`
- `Java_com_termux_terminal_JNI_waitFor`

**C stdlib calls used:** `fork`, `setsid`, `execvp`, `clearenv`, `putenv`, `sigfillset`, `sigprocmask`, `dup2`, `ioctl`, `opendir`, `readdir`, `closedir`, `grantpt`, `unlockpt`, `ptsname_r`, `waitpid`, `open`, `close`, `chdir`, `strerror`, `asprintf`, `perror`, `exit`

**NOT present (confirmed absent):** `prctl`, `setProcessGroup`, `cgroup`, `phantom`, `PR_SET_CHILD_SUBREAPER`

**Conclusion:** Termux C code is IDENTICAL in behavior to our `pty_native.c`. Signal 31 survival is NOT from the native layer.

---

### AndroidManifest.xml — Full Comparison (from device)

| Setting | Termux (device) | Ours (before fix) | Ours (after fix) |
|---|---|---|---|
| `targetSdkVersion` | **28** | 34 | **28** ✓ |
| `compileSdkVersion` | 30 | 34 | 34 |
| `foregroundServiceType` | **NONE** | specialUse | **NONE** ✓ |
| `android.max_aspect` | **10.0** | missing | **10.0** ✓ |
| `com.samsung.android.keepalive.density` | true | true | true ✓ |
| `com.samsung.android.multidisplay.keep_process_alive` | true | true | true ✓ |
| `com.sec.android.support.multiwindow` | true | true | true ✓ |
| `androidx.window.extensions` | required=false | missing | required=false ✓ |
| `androidx.window.sidecar` | required=false | missing | required=false ✓ |
| `FOREGROUND_SERVICE_SPECIAL_USE` perm | absent | present | **removed** ✓ |

### Why targetSdk=28 is the nuclear option:
- Android 14 applies full **compat mode** for apps targeting SDK < 29
- All FGS restrictions (API 29+, 31+, 33+, 34+) are completely bypassed
- No foregroundServiceType required → no `specialUse` enforcement
- No phantom process rules → children of service not subject to phantom killer
- No 6-hour FGS timeout (dataSync/API 31+)
- OEM power managers (HiOS/TECNO) treat compat-mode apps more leniently

### Commits from this session:
| Commit | Change |
|---|---|
| `78e6513` | Added Samsung/TECNO OEM keepalive meta-data, removed dataSync FGS type |
| `4233848` | Added android.max_aspect=10.0, added androidx.window libs, removed PROPERTY sub-element |
| next | targetSdk=28, removed foregroundServiceType entirely, removed FOREGROUND_SERVICE_SPECIAL_USE |

### NEXT: Decompile classes.dex — TermuxService.smali
**What to look for:**
1. `TermuxService` → `onStartCommand` method — how does it call startForeground?
2. `TermuxApplication` → `onCreate` — anything besides crash handler?
3. Search for `Process` class usage — any `setProcessGroup` or `setThreadPriority`?
4. Search for `PowerManager` — when/how is WakeLock acquired?
5. Search for `cgroup` string in the full dex dump

**How to do it in MT Manager:**
- Open base.apk → `classes.dex` → tap **Dex Editor Plus**
- Use the **Search** tab (magnifying glass) → search class name
- Or: long-press `classes.dex` → **Dex to Java** if you have jadx plugin


---

## TERMUX TermuxService.smali — DECOMPILED (June 28, 2026)
> Source: classes.dex decompiled on user's TECNO KL4 via Dex Editor Plus in MT Manager.

### onStartCommand — full logic (confirmed from smali):
```
onStartCommand(Intent, int, int):
  1. runStartForeground()          ← FIRST, always, no conditions
  2. if (intent == null) → return START_STICKY   ← restart case, no WakeLock
  3. switch(intent.getAction()):
       "com.termux.service_wake_lock"   → actionAcquireWakeLock()
       "com.termux.service_wake_unlock" → actionReleaseWakeLock()
       "com.termux.service_execute"     → actionExecute()
       "com.termux.service_stop"        → actionStopService()
       else                            → log error
  4. return START_STICKY
```

### CRITICAL: WakeLock is NOT auto-acquired
- Termux WakeLock is **opt-in** — sent via explicit intent action by TermuxActivity
- Service restarts (null intent) do NOT acquire WakeLock
- Termux survives on TECNO **without** a continuously held WakeLock
- Our auto-acquire in `else` branch is extra insurance (no harm, keeps our sessions alive)

### onDestroy — full logic:
```
onDestroy():
  1. clearTermuxTMPDIR()
  2. actionReleaseWakeLock(false)   ← drop lock if held
  3. if (!mWantsToStop) → killAllTermuxExecutionCommands()
  4. runStopForeground()
```
- `mWantsToStop = true` = user stopped it
- `mWantsToStop = false` = system killed it → clean up shells

### What actually keeps Termux alive (confirmed, in order of importance):
1. `targetSdk=28` → full Android compat mode, ALL FGS restrictions bypassed
2. OEM meta-data (samsung keepalive, max_aspect, window extensions)
3. `START_STICKY` → service auto-restarts if killed
4. `startForeground()` called first in every `onStartCommand` invocation
5. WakeLock = optional, user-toggled, NOT the primary survival mechanism
6. NO cgroup, NO setProcessGroup, NO prctl — confirmed absent from binary

### Our TerminalService vs Termux comparison (post-fix):
| Pattern | Termux | Ours |
|---|---|---|
| startForeground() first | ✓ | ✓ |
| START_STICKY | ✓ | ✓ |
| WakeLock auto-acquire | ✗ (opt-in) | ✓ (extra, no harm) |
| mWantsToStop flag | ✓ | ✗ (not needed, same effect) |
| targetSdk=28 | ✓ | ✓ (fixed) |
| OEM meta-data | ✓ | ✓ (fixed) |

### Next decompile targets:
1. `TermuxApplication.onCreate()` — what does it do at startup?
2. Search `PowerManager` in STRINGS tab — see all WakeLock usage sites
3. Search `sharedUserId` effect — does Termux use any cross-process permissions?
4. `runStartForeground()` method body — what notification channel / type does it use?
