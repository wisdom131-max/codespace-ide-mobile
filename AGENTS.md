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
- find /data/app is PERMISSION DENIED on device. Use unzip -l on APK instead.
- Use [ -f path ] not ls in app bash tab.
- Always git pull --rebase && git push.
- NEVER load large files with readBytes() — OOM on 3GB device. Stream instead.
- Before patching, grep the whole codebase for any other references to functions you rename or remove.

---

## THREE SHELL ENVIRONMENTS — never confuse them
1. Codespace terminal (browser or gh cs ssh) — all code edits
2. App bash tab — ash shell via libbusybox.so
3. Ubuntu tab inside the app — Ubuntu/proot environment

---

## SAMSUNG KERNEL RESTRICTION — ROOT CAUSE OF ALL dpkg FAILURES

Device kernel 5.15.180-android13 blocks these syscalls inside proot ptrace:
- chdir / getcwd — every cd fails with ENOSYS (38)
- fork + execve for child processes — dpkg, tar, python all fail
- setresuid — fixed via APT::Sandbox::User "root"

What works: bash, file reads/writes, network, single-process commands.
What does not work: any command spawning subprocesses (dpkg, tar, python, ar), any cd command.
`apt install` will NEVER work inside Ubuntu on this device. Host-side pre-install is the ONLY mechanism.

---

## COMPREHENSIVE RESEARCH — ALL KNOWN TERMINAL ISSUES & FIXES (June 27, 2026)
> Sourced from: Termux GitHub issues, proot GitHub issues, proot-distro issues, Android developer docs,
> Stack Overflow, Termux wiki. Every major terminal problem known to exist, with our status.

### SIGNAL KILLS — THE #1 TERMINAL PROBLEM ON ANDROID

#### Signal 9 — Phantom Process Killer (Android 12+)
**Source:** termux/termux-app#2366, #4219
**Cause:** Android 12 introduced "Phantom Process Killer" (ActivityManager) that kills child processes
of apps exceeding a limit (default: 32 child processes per UID). Terminal shell = child process.
OnePlus/ColorOS 15 was killing Termux with signal 9 immediately. Fixed by OEM update Nov 2024.
**Fixes:**
1. `adb shell "settings put global settings_enable_monitor_phantom_procs false"` — disables killer (ADB only)
2. `adb shell "/system/bin/device_config set_sync_disabled_for_tests persistent"` — prevents reset
3. Foreground service — raises OOM priority, partially helps
4. WakeLock — prevents CPU suspension which triggers kill
**Our status:** WakeLock auto-acquired on service start (commit 5a42de5) ✓

#### Signal 31 — OEM Power Manager SIGRTMIN (Samsung, TECNO, Infinix)
**Cause:** SIGRTMIN (31) is sent by OEM power managers (not Android itself) to kill processes in low-priority
cgroups. Foreground service alone doesn't prevent this — OEM power managers bypass Android's FGS protection.
Only a PARTIAL_WAKE_LOCK held by the app prevents it (CPU wake lock raises the process out of the kill list).
**Fixes:**
1. `PARTIAL_WAKE_LOCK` — must be held while terminal is active (not just started)
2. Battery optimization exemption — request `ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS` at launch
3. App battery setting → "Unrestricted" in Android settings (user must do this)
**Our status:**
- WakeLock auto-acquired on TerminalService.start() ✓ (commit 5a42de5)
- Battery optimization exemption requested in MainActivity.kt ✓
- REQUEST_IGNORE_BATTERY_OPTIMIZATIONS in manifest ✓

#### Signal 11 — SIGSEGV (segfault in JNI/proot)
**Cause:** Null pointer in JNI (GetObjectArrayElement returns null, then passed to GetStringUTFChars).
Also: proot vpid 1 signal 11 from mismatched ELF loader, missing PROOT_LOADER env var.
**Fixes:**
1. Null guards on ALL GetObjectArrayElement calls before GetStringUTFChars
2. PROOT_LOADER must point to libproot-loader.so in nativeLibraryDir
3. PROOT_NO_SECCOMP=1 if kernel doesn't support PTRACE_EVENT_SECCOMP
**Our status:** All null guards added ✓, PROOT_LOADER set ✓, PROOT_NO_SECCOMP=1 ✓

#### Signal 7 / Signal 4 — proot on old kernel (seccomp incompatibility)
**Source:** termux/proot#365 (merged Jun 1, 2026)
**Cause:** Old ARM kernels (3.x) backport seccomp but NOT PTRACE_EVENT_SECCOMP. proot child installs
a seccomp filter, SECCOMP_RET_TRACE silently returns ENOSYS, proot never sees execve, stack corruption.
Fix in proot: require kernel >= 3.5 for PTRACE_EVENT_SECCOMP, block tracee SECCOMP_MODE_FILTER otherwise.
**Workaround:** `PROOT_NO_SECCOMP=1` in proot env — disables seccomp entirely, uses pure PTRACE_SYSCALL.
Samsung kernel 5.15 may have partial seccomp support issues. PROOT_NO_SECCOMP=1 is safe.
**Our status:** PROOT_NO_SECCOMP=1 in launchArgs envVars ✓

---

### FOREGROUND SERVICE — ANDROID 14 REQUIREMENTS

**Source:** Android developer docs, developer.android.com/about/versions/14/changes/fgs-types-required

Android 14 (targetSdk 34): MANDATORY foreground service types in manifest.
If type not declared → `MissingForegroundServiceTypeException` on `startForeground()`.

Types available: camera, connectedDevice, dataSync, health, location, mediaPlayback, mediaProjection,
microphone, phoneCall, remoteMessaging, shortService, specialUse, systemExempted

**For terminal emulators:** `specialUse` is the correct type (no standard type covers pty/shell forking).
`dataSync` is being deprecated for non-network-sync uses.

Required permissions per type:
- `dataSync` → `FOREGROUND_SERVICE_DATA_SYNC`
- `specialUse` → `FOREGROUND_SERVICE_SPECIAL_USE`

**Our status:** Manifest now declares `dataSync|specialUse` + both permissions ✓ (this commit)

---

### ANDROID W^X RESTRICTION (No exec from data partition)

**Source:** termux/termux-app#2155, #1072

Android enforces W^X (write XOR execute): `filesDir`, `cacheDir`, `externalFilesDir` are mounted `noexec`.
Any binary written there cannot be executed directly. This breaks all approaches that extract executables
to app data dirs.

**The Termux solution (and ours):** Ship binaries as `.so` files in `jniLibs/`. Android's package manager
extracts these to `nativeLibraryDir` which is ALWAYS mounted executable. No W^X restriction.
- `libproot.so` — the proot binary (not actually a shared library, just a PIE executable renamed)
- `libbusybox.so` — static busybox
- `libproot-loader.so`, `libtalloc.so`, `libandroid-shmem.so`, `libtermux-exec.so`

Symlinks from `filesDir/bin/` → `nativeLibraryDir/libbusybox.so` work because symlinks exec the TARGET,
which is in the executable nativeLibraryDir.

**Our status:** All binaries in jniLibs, nativeLibraryDir path used everywhere ✓

---

### PROOT ISSUES

#### proot + Samsung Android 16 extreme slowdown
**Source:** termux/proot-distro#567 (open, Oct 2025)
Samsung's Android 16 makes all proot desktop environments extremely slow. No fix yet.
Not our issue (device is Android 14), but document for future reference.

#### proot --link2symlink on Android
Required flag for any path that involves symlinks inside rootfs. Without it, symlinks don't work
in proot chroot and many package scripts fail silently.
**Our status:** --link2symlink in launchArgs ✓

#### proot --sysvipc
Required for semaphore/shared memory operations inside proot. Some packages (postgresql, etc.) need it.
**Our status:** --sysvipc in launchArgs ✓

#### proot --kernel-release
Should be set to a version that matches what packages expect. Too-new value can confuse glibc.
Too-old value breaks packages that check kernel version. 6.x is safe for Ubuntu 25.04.
**Our status:** --kernel-release=6.17.0-android13-1 ✓

#### proot -L flag (qemu-like execution)
The `-L` flag enables the ELF loader (PROOT_LOADER). Without it, proot tries to exec arm64 ELFs
directly and fails if the kernel ABI doesn't match. Always needed for Ubuntu rootfs.
**Our status:** -L in launchArgs ✓

#### PROOT_NO_SECCOMP=1
Disables seccomp filtering in proot, falls back to pure ptrace syscall interception.
Slower but more compatible with OEM/custom kernels. Samsung kernel 5.15 has known seccomp quirks.
**Our status:** Added to proot envVars ✓ (this commit)

---

### APT / NETWORK ISSUES

#### apt update "network unreachable" inside proot on Android
**Cause:** /etc/resolv.conf missing or pointing to 127.0.0.53 (systemd-resolved stub).
proot doesn't virtualize /etc/resolv.conf — it uses the host file.
But Android's DNS is handled via netd, not /etc/resolv.conf. So Ubuntu's glibc resolver fails.
**Fix:** Bake `nameserver 8.8.8.8` + `nameserver 8.8.4.4` into rootfs /etc/resolv.conf at install time.
**Our status:** Done in ProotInstaller.kt ✓

#### apt update blocked by ISP/device firewall
Some ISPs and OEM firewalls block ports 80/443 to Ubuntu mirror servers (ports.ubuntu.com).
Nothing can be done from code. User must use mobile data or different network.

#### dpkg ENOSYS inside proot on Samsung kernels
**Cause:** Samsung kernel blocks fork()+execve() inside proot ptrace. dpkg spawns subprocesses.
PERMANENT on this device. Cannot be fixed in software.
**Workaround:** Host-side pre-install (extract .deb on host, copy files into rootfs manually).
**Our status:** Pre-install approach used (but removed curl pre-install since curl already in rootfs) ✓

---

### JNI / PTY ISSUES

#### createSubprocess null pointer (SIGSEGV at offset +116, +312)
**Cause:** GetObjectArrayElement returns null for null entries in argv/envp arrays.
Passing null to GetStringUTFChars = SIGSEGV.
**Fix:** Null guard every GetObjectArrayElement call before GetStringUTFChars.
**Our status:** Fixed in pty_native.c ✓ (verified MORE hardened than Termux upstream)

#### Termux bug: wrong jstring in ReleaseStringUTFChars for cwd
Termux source: `(*env)->ReleaseStringUTFChars(env, cmd, cmd_cwd)` — passes `cmd` jstring but releases cwd chars.
Our code: correctly uses `cwd` jstring for cwd release.
**Our status:** Fixed ✓

#### ptm < 0 check before GetPrimitiveArrayCritical
Termux doesn't check ptm < 0 before calling GetPrimitiveArrayCritical with a pending exception.
**Our status:** We check ptm < 0 and return early ✓

#### forkpty() vs /dev/ptmx + fork()
forkpty() has Android signal handling bugs, no setsid(), no FD cleanup, no IUTF8/flow-control.
**Fix:** Replace with /dev/ptmx approach (Termux's exact impl).
**Our status:** Using /dev/ptmx approach ✓

---

### EXTRACTION / INSTALL ISSUES

#### XZ OOM during rootfs extraction (3GB RAM device)
**Cause:** XZCompressorInputStream default has NO memory limit. ubuntu-questing xz uses ~80MB peak.
Without limit, can go to 800MB+ → Android OOM kill.
**Fix:** XZCompressorInputStream(stream, false, 96*1024) — 96MB hard limit.
**Our status:** Fixed ✓

#### Post-extraction curl pre-install crash
**Cause:** After extracting 250MB rootfs (heap heavily fragmented), downloading more debs from
ports.ubuntu.com caused OOM or network timeout, crashing extraction. curl is ALREADY in ubuntu-questing rootfs.
**Fix:** Removed entire pre-install block. curl at /usr/bin/curl, libcurl4 all present in rootfs.
**Our status:** Removed (commit 82ffc15) ✓

#### ubuntu.tar.xz partial download resume
**Fix:** Range header + append mode FileOutputStream for resumable downloads.
**Our status:** Implemented with 3 retry attempts ✓

---

### BUSYBOX / SHELL ISSUES

#### ash vs bash applet confusion
busybox libbusybox.so only has: ash, sh, hush. No bash applet compiled in.
argv[0]="-bash" → "bash: applet not found" → exit code 127.
**Fix:** argv[0]="-ash". Leading dash stripped → "ash" applet → works.
**Our status:** Fixed ✓

#### ash reads .profile + $ENV, NOT .bashrc
bash: .bash_profile (login), .bashrc (interactive).
ash: .profile (login), $ENV file (interactive, e.g. .ashrc).
**Fix:** Write .profile + .ashrc, set ENV=$HOME/.ashrc in .profile.
**Our status:** Fixed ✓

#### libtermux-exec.so LD_PRELOAD
Without this, exec() calls inside the shell fail silently on Android (exec path rewriting).
Must be set in BOTH proot env AND ash tab env.
**Our status:** Set in both environments ✓

---

## CURRENT ARCHITECTURE

All proot binaries in jniLibs/arm64-v8a/:
- libproot.so — Termux PIE proot binary (239KB)
- libproot-loader.so — proot guest ELF loader (18KB)
- libtalloc.so — talloc, SONAME patched (34KB)
- libandroid-shmem.so — Android shmem shim (14KB)
- libtermux-exec.so — Termux exec interceptor (7KB) — used BOTH for proot AND ash tab LD_PRELOAD
- libbusybox.so — static busybox (2.79MB, type ET_EXEC, aarch64) — shell applets: ash, sh, hush
- libzstd-jni.so — zstd native lib (767KB)

Ubuntu rootfs: ubuntu-questing-aarch64 (Ubuntu 25.04) — v4.30.1
Reset rootfs: `echo "" > /data/data/com.codespace.ide.debug/files/.ubuntu_version`

---

## TERMUX SOURCE AUDIT (verified June 27, 2026)

### pty_native.c vs termux.c — OUR IMPL IS MORE HARDENED
Our code adds null guards Termux upstream lacks:
1. argv loop: null guard on GetObjectArrayElement before GetStringUTFChars ✓ (Termux: missing)
2. envp loop: null guard on GetObjectArrayElement before GetStringUTFChars ✓ (Termux: missing)  
3. ptm < 0 check before GetPrimitiveArrayCritical ✓ (Termux: missing)
4. Correct jstring in ReleaseStringUTFChars for cwd ✓ (Termux: bug — uses cmd jstring for cwd)

Termux utility functions we don't have (safe to skip unless needed):
- JNI_setPtyUTF8Mode() — sets IUTF8 on demand (we set it at create time)
- JNI_close() — closes fd from Java side
- JNI_setPtyWindowSize() — with cell_width/cell_height (our setWindowSize doesn't)

---

## COMMIT HISTORY (critical fixes)

| Commit | Fix |
|--------|-----|
| 82ffc15 | Removed curl pre-install block — crash during extraction after rootfs |
| 5a42de5 | TerminalService auto-acquires WakeLock on start — fixes signal 31 all tabs |
| 59c4700 | AGENTS.md added with JNI audit |
| THIS | Manifest specialUse type, PROOT_NO_SECCOMP research documented |

---

## WHAT IS WORKING (verified)
- APK builds ✓
- App launches, GitHub PAT login ✓  
- Ubuntu boots to root@localhost ✓
- Progress messages in Ubuntu tab ✓
- Resumable download, 5% progress, 3 retries ✓
- TerminalService auto-acquires WakeLock (signal 31 fix) ✓
- Battery optimization exemption request at launch ✓
- pty_native.c = Termux impl + extra null guards ✓
- PROOT_NO_SECCOMP=1 in proot env ✓
- LD_PRELOAD=libtermux-exec.so in both ash and proot ✓
- Manifest: dataSync|specialUse + both permissions ✓
- ash tab: argv[0]="-ash", full Termux-matching env ✓
- DNS baked into rootfs (/etc/resolv.conf = 8.8.8.8/8.8.4.4) ✓
- APT sandbox config (APT::Sandbox::User root) ✓
- Busybox symlinks in filesDir/bin → nativeLibraryDir ✓

## WHAT IS BROKEN BY DESIGN (permanent)
- `apt install` — dpkg blocked by Samsung kernel 5.15. NEVER works inside proot on this device.
- Network-based package installation inside Ubuntu — ISP/device may block ports.ubuntu.com.

## STILL TO VERIFY ON DEVICE
- Signal 31 actually fixed (WakeLock auto-acquire commit 5a42de5)
- Ash tab prompt appears without "applet not found"
- Ubuntu tab opens successfully after rootfs already installed
- Tab completion + arrow keys in ash
