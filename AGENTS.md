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

---

## GOOGLE DRIVE — Credentials & File Map
> Last updated: 2026-06-29
> Any AI agent working on this project MUST read this section first.

### Where to find credentials

| What | Location |
|------|----------|
| All dev credentials (Railway token, Firebase IDs, OAuth clients, SHA-1) | Google Drive → **Codespace IDE — Dev Credentials** folder → `credentials-and-keys.md` |
| Firebase service account JSON (FIREBASE_CLIENT_EMAIL + FIREBASE_PRIVATE_KEY) | Google Drive → **Codespace IDE — Dev Credentials** folder → `codespace-ide-2026-firebase-adminsdk-fbsvc-6716e69019.json` |
| `google-services.json` (Android OAuth + API key) | Google Drive → **Codespace IDE — Dev Credentials** folder → `google-services.json` |
| Firebase service account JSON (private key for Railway) | Google Drive → **Codespace IDE — Dev Credentials** folder → `codespace-ide-2026-firebase-adminsdk.json` (Drive ID: `1k20Ic4erMvzDxX68OVvQ_8v5Ejeemm5_`) |
| Railway API token | Inside `credentials-and-keys.md` above — field "API Token (Superagent)" |

### Google Drive folder → file ID map

| Folder | Drive ID |
|--------|----------|
| **Codespace IDE — Dev Credentials** | `1faD1RO8P7gX3r-LKIu7X_AbaBDfadmRM` |
| **Codespace IDE — Dev Files** | `1myi670kmiTSxEYd3lI0ENqQudHrFDDyE` |
| **YouTube & Finance** | `1uItBB0vv7shtiofjXu-2VWstTYEVA5EI` |
| **Personal** | `18QGkcKm9YAmCtqFEQrFBmdgGUVT_fSYD` |
| **Resources & References** | `1Idx-ibXsMZmhkTgFHoFzBCrHeidN7A7i` |

### Key values (set on Railway production as of 2026-06-29)

| Railway Env Var | Status | Value / Notes |
|----------------|--------|---------------|
| `FIREBASE_PROJECT_ID` | ✅ Set | `codespace-ide-2026` |
| `FIREBASE_CLIENT_EMAIL` | ✅ Set | `firebase-adminsdk-fbsvc@codespace-ide-2026.iam.gserviceaccount.com` |
| `FIREBASE_PRIVATE_KEY` | ✅ Set | From service account JSON (2048-bit RSA) |
| `OWNER_EMAIL` | ✅ Set | `ijeziewisdom5@gmail.com` |
| `JWT_SECRET` | ✅ Set | (random 64-char hex) |
| `DATABASE_URL` | ✅ Set | PostgreSQL via Railway |
| `NODE_ENV` | ✅ Set | `production` |
| `PORT` | ✅ Set | `3000` |

### Railway project details

| Field | Value |
|-------|-------|
| Project name | `stunning-gentleness` |
| Project ID | `5fa8bf0f-4ad9-4dda-bbdf-ef81b1863119` |
| Service name | `codespace-ide-mobile` |
| Service ID | `4aa086c6-3f43-4d0a-95e3-481700990b88` |
| Environment | `production` |
| Environment ID | `4ad3c2b6-7a08-4270-9d68-871371293033` |
| Live URL | `https://codespace-ide-mobile-production.up.railway.app` |
| Health check | `GET /api/v1/health` → `{"status":"ok"}` |

### How to set/update Railway env vars (for future AI)
```bash
# Set or update any env var:
curl -s -X POST "https://backboard.railway.app/graphql/v2" \
  -H "Authorization: Bearer <RAILWAY_API_TOKEN>" \
  -H "Content-Type: application/json" \
  -d @payload.json   # payload: variableCollectionUpsert mutation with projectId/environmentId/serviceId/variables

# Railway API token is in credentials-and-keys.md on Google Drive
```

### How to read Railway env vars (for future AI)
```graphql
# Query:
{ variables(projectId: "5fa8bf0f...", environmentId: "4ad3c2b6...", serviceId: "4aa086c6...") }
```

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

## SAMSUNG KERNEL RESTRICTION — REVISED JUNE 29, 2026

Device kernel 5.15.180-android13 — ORIGINAL ASSUMPTION WAS WRONG.

### What was assumed (incorrect)
`apt install` would NEVER work. Host-side pre-install was thought to be the only mechanism.

### What was confirmed on device (June 29, 2026)
Wisdom ran `apt upgrade` and `apt` in native Termux on the TECNO KL4 — both worked perfectly.
apt 2.8.1 (aarch64) is fully functional in native Termux on this device.

### Revised understanding
The kernel blocks apply INSIDE proot ptrace, not natively. Specifically:
- `chdir` / `getcwd` — ENOSYS (38) inside proot — NOT in native Termux
- `fork` + `execve` for child processes — blocked inside proot ptrace
- `setresuid` — blocked inside proot (workaround: `APT::Sandbox::User "root"`)

### What this means going forward
- Native Termux: apt, dpkg, python, git — all work fine on this device
- Inside our Ubuntu proot tab: apt install is still likely blocked (proot ptrace restriction)
- The Termux bootstrap we ship (bash, curl, etc.) works because it runs natively via nativeLibraryDir
- Next test needed: confirm whether `apt install` works inside OUR Ubuntu proot tab specifically
- If it does work: host-side pre-install workaround is unnecessary — remove it

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
| `5d6f776` | AuthScreen — account picker + always-visible email field (Rule 21) |
| `892d14f` | AGENTS.md — Rule 21 documented |
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

## FEATURE BACKLOG — Structured & Prioritised
> Last updated: June 29, 2026. All duplicates removed. Minimap dropped (not needed on mobile).
> Every AI session must update this list (check off done items, add new ones).
> Grouped by difficulty: Easy → Medium → Hard.

---

### 🟢 EASY (1–2 hrs each, low risk, self-contained)

#### Editor
- [ ] **Find in File** — wire existing `showFindBar`/`findQuery` state so search icon tap sets `showFindBar = true` + CodeEditor highlights all matches; Up/Down to navigate; Aa / whole-word toggles
- [ ] **Command palette keyboard focus fix** — `focusRequester` + `LaunchedEffect` auto-focuses the TextField on open; `DialogProperties(usePlatformDefaultWidth=false)`; `ImeAction.Search`
- [ ] **Command palette resize** — max 60-70% screen height, appears from top; compact rows 32dp; font 12sp results / 14sp input; semi-transparent overlay behind card
- [ ] **Title bar back/forward arrows** — nav history buttons in the title bar
- [ ] **Editor welcome screen** — "Getting Started" tab with keybindings cheatsheet on first open
- [ ] **Snippet support** — user-defined shorthand expansions in the editor

#### Terminal
- [ ] **Clear button** — clears terminal screen (sends `clear\n`)
- [ ] **Export Screen** — save terminal scrollback to file via system file picker
- [ ] **Pkg Update shortcut** — one-tap runs `pkg update -y` in active session
- [ ] **URL detection** — long-press any URL in terminal transcript → open in browser or copy
- [ ] **Startup script** — dot-sourced into every new terminal session (user-configurable path)
- [ ] **Session renaming** — long-press tab chip to rename it
- [ ] **Failsafe mode** — if main shell exits/crashes, offer fallback minimal ash shell
- [ ] **Bell mode preference** — user toggle: vibrate / beep / silent (currently always vibrate)
- [ ] **Back key → ESC** — user preference to send ESC instead of navigating back
- [ ] **Auto-close tab on exit** — close tab automatically when shell exits with code 0 or 130

#### Gear Menu — wire up existing buttons
- [ ] **Setup Shell Profile** → write Zsh + OMZ install script to Ubuntu terminal session
- [ ] **Setup Offline Shell** → call existing ProotInstaller logic
- [ ] **Install Offline Essentials** → write `apt install -y python3 nodejs git curl vim htop` to Ubuntu session
- [ ] **Backup Shell Profile** → copy `~/.zshrc`, `~/.bashrc`, `~/.profile` to `/storage/emulated/0/codespace_backup/`
- [ ] **Restore Shell Profile** → read from backup folder, write back to Ubuntu home
- [ ] **Keyboard Shortcuts** → open modal listing all Ctrl+X shortcuts with actions
- [ ] **Extensions button** → switch active side panel to `SidePanel.EXTENSIONS`

#### UI / Polish
- [ ] **Keyboard shortcut overlay** — Ctrl+Shift+P etc. shown in welcome screen
- [ ] **App icon** — VS Code-style icon with Wisdom's branding (not Microsoft's)
- [ ] **Smooth terminal font** — monospace with ligatures support
- [ ] **Onboarding first-launch wizard** — install bootstrap, pick theme, connect GitHub

---

### 🟡 MEDIUM (half day each, moderate complexity)

#### Editor
- [ ] **IntelliSense autocomplete** — language-aware dropdown in CodeEditor; method/var/class with icons; triggers on `.` and letter input (LazyColumn overlay)
- [ ] **Hover documentation tooltip** — type signature + description + MDN link on long-press in editor
- [ ] **Code folding** — collapse/expand functions and blocks in editor gutter
- [ ] **Multi-cursor editing** — tap + hold to place multiple cursors
- [ ] **Git diff gutters** — inline `+` / `-` line indicators in editor gutter for staged/unstaged changes
- [ ] **Breadcrumb navigation** — tap any segment of the breadcrumb path to jump to that folder/file
- [ ] **Find + Replace** — extend Find bar with Replace field; Aa / word / regex toggles (like VS Code)

#### Terminal
- [ ] **Zsh + Oh My Zsh auto-setup** — background install on first terminal launch; no user interaction needed
- [ ] **Zsh plugins toggle** — autosuggestions + syntax highlighting from bundled zip (no download)
- [ ] **Session PiP view** — mini live last-line previews in inactive tab chips
- [ ] **Make Script** — pick commands from session history → save as `.sh` file
- [ ] **Custom command buttons** — up to 10 user-defined buttons; long-press to edit; `+`/`-` to add/remove
- [ ] **Text expansion** — trigger shortcuts (`;ll` → `ls -la`); TextExpansionSheet already partially done
- [ ] **Autocorrect bar** — shell command suggestion strip above keyboard
- [ ] **Backup/restore Termux data** — `.tar.gz` of full prefix; restore on fresh install
- [ ] **9 accent presets + HSV color wheel** — custom terminal accent colour picker
- [ ] **6 terminal color themes + custom editor** — edit all 18 ANSI colors live
- [ ] **Transcript URL long-press list** — show all URLs in scrollback as a tappable list

#### Source Control Panel
- [ ] **Real changed files list** — run `git status` → show filenames with M / A / D / U badges (like VS Code SCM)
- [ ] **Inline diff view** — tap file → red/green diff lines (old vs new)
- [ ] **Stage individual files** — stage button per file + "Stage All"
- [ ] **Commit + Push + Pull** — write git commands to Ubuntu proot session; parse output
- [ ] **Git auth** — HTTPS via stored token from SecureTokenStore or SSH key in Ubuntu home

#### AI / Copilot
- [ ] **Ollama model auto-detect** — poll `localhost:11434/api/tags` every 30s; populate model picker
- [ ] **Stream responses** — token-by-token output in chat panel (not wait for full response)
- [ ] **System prompt** — configurable per-project system prompt in chat settings
- [ ] **Chat history persistence** — per project/workspace in local JSON (SharedPrefs)
- [ ] **Multi-model** — use different models for different tasks (coding vs planning vs image)
- [ ] **Named chat sessions** — session list in chat panel header; "Debugging login bug" etc.; timestamps + resume
- [ ] **@ Add Context** — mention current file, open tabs, workspace folder, git diff, terminal output

#### Project Persistence
- [ ] **Full session restore** — on project open: restore all editor tabs, active tab, scroll/cursor positions, panel states, font size, unsaved buffers
- [ ] **Auto-save debounce** — save all state 1s after any change; store in `SessionStateStore.ShellState`
- [ ] **Unsaved buffer files** — save unsaved edits to `filesDir/buffers/{projectId}/{filename}.buf`

#### Preview Panel / Run
- [ ] **Hot-reload** — FileObserver on active file; auto-refresh WebView on every save
- [ ] **Run button** — detect `package.json` / `main.py` / `Makefile`; one-tap run in terminal
- [ ] **Port forwarding UI** — real forwarded ports list in PORTS tab; tap → opens in Browser preview

#### GitHub in Gear Menu
- [ ] **Connect GitHub** bottom sheet — PAT text field, "Validate & Save", "Get Token →" link; stores in SecureTokenStore; shows avatar + username in gear header once connected
- [ ] **All git ops use stored token** — push/pull/clone use GitHub PAT from SecureTokenStore

#### Extensions Panel
- [ ] **INSTALLED / RECOMMENDED / MCP SERVERS** — collapsible sections
- [ ] **Star ratings + install count** — like VS Code marketplace
- [ ] **Fix ExtensionsPanel compile error** — add missing `LaunchedEffect` / `withContext` / `Dispatchers` imports in ExplorerPane.kt

---

### 🔴 HARD (full day+, architectural, high risk)

#### AI Agent System
- [ ] **Ask mode** — single-turn Q&A: explain function, decode error, summarise file
- [ ] **Agent mode** — multi-step autonomous: reads open files, writes/edits files, runs terminal commands, creates files/folders
- [ ] **Plan mode** — break task into steps with checkboxes; user approves/rejects each step; tracks progress; resumable after interruption
- [ ] **MCP: Terminal** — run shell commands and stream output back to AI chat
- [ ] **MCP: File system** — AI can read, create, move, delete workspace files
- [ ] **MCP: GitHub** — read repos, create issues, open PRs from within IDE
- [ ] **MCP: Web search** — fetch URLs, search the web, summarise pages
- [ ] **MCP: Custom endpoint** — user can add any MCP server URL
- [ ] **Image editing connector** — open image in chat, describe edits, AI applies them
- [ ] **App builder connector** — scaffold React / Flask / Express project via chat
- [ ] **Camera connector** — capture photo/screenshot, add to chat context

#### Explorer
- [ ] **Phone Files browser** — full `/storage/emulated/0` file manager in Explorer pane; icon + name + size + modified; long-press context menu (Open, Rename, Delete, Copy Path, Move); image long-press 3s → full-size preview; breadcrumb nav; `MANAGE_EXTERNAL_STORAGE` permission

#### Settings
- [ ] **Full VS Code-style settings JSON editor** — searchable settings with live apply

#### Performance & Stability
- [ ] **Lazy-load panels** — only render active panel; cache file tree between switches
- [ ] **Crash reporting + auto-recovery** — catch uncaught exceptions; log to file; offer "Restore last session" on next launch

#### Backend / Railway env vars (Wisdom must set these)
- [ ] Set `OWNER_EMAIL=ijeziewisdom5@gmail.com` on Railway
- [ ] Set `FIREBASE_PROJECT_ID=codespace-ide-2026` on Railway
- [ ] Set `FIREBASE_CLIENT_EMAIL` from Firebase service account JSON on Railway
- [ ] Set `FIREBASE_PRIVATE_KEY` from Firebase service account JSON on Railway (escape `\n`)
- [ ] Set `JWT_SECRET` (run: `openssl rand -hex 64`) on Railway

---
