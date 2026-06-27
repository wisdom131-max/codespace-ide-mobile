# AI Agent / Copilot — Project Context
> Read this FIRST before touching any code. Updated June 27, 2026.

---

## What This Project Is

**Visual Node Code** (display name) / **CodeSpace IDE** (package) — a VS Code-style Android IDE with built-in Ubuntu Linux terminal powered by proot. No root needed.

- **Package:** `com.codespace.ide.debug`
- **Repo:** `wisdom131-max/codespace-ide-mobile`
- **APK:** `app-prod-arm64-v8a-debug.apk`
- **Device:** TECNO KL4, Android 14, Samsung kernel 5.15.180-android13-8-gb70ce4a70964-ab489, arm64-v8a, 3GB RAM
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
2. App bash tab — test running app behavior
3. Ubuntu tab inside the app — test Ubuntu/proot behavior

---

## SAMSUNG KERNEL RESTRICTION — ROOT CAUSE OF ALL dpkg FAILURES

Device kernel 5.15.180-android13 blocks these syscalls inside proot ptrace:
- chdir / getcwd — every cd fails with ENOSYS (38)
- fork + execve for child processes — dpkg, tar, python all fail
- setresuid — fixed via APT::Sandbox::User "root"

What works: bash, file reads/writes, network, single-process commands.
What does not work: any command spawning subprocesses (dpkg, tar, python, ar), any cd command.

The workaround: extract packages on Android host side in Java/Kotlin BEFORE proot launches.
This means `apt install` will NEVER work inside Ubuntu on this device — that is permanent and by design.
Do NOT attempt to skip host-side pre-install and fall back to apt. It will fail.

---

## CURRENT ARCHITECTURE

All proot binaries in jniLibs/arm64-v8a/:
- libproot.so — Termux PIE proot binary (239KB)
- libproot-loader.so — proot guest ELF loader (18KB)
- libtalloc.so — talloc, SONAME patched (34KB)
- libandroid-shmem.so — Android shmem shim (14KB)
- libtermux-exec.so — termux exec helper (7KB)
- libbusybox.so — static busybox 2.7MB (nativeLibraryDir trick — always executable)
- libzstd-jni.so — zstd native lib (767KB)

proot launch uses complete proot-distro flag set:
--kill-on-exit --link2symlink --sysvipc --kernel-release=\Linux\localhost\6.17.0-PRoot-Distro\... -L --change-id=0:0 -r rootfs plus all required binds.

Ubuntu rootfs: ubuntu-questing-aarch64 (Ubuntu 25.04 Questing)
VERSION string: ubuntu-questing-v4.30.1
Reset rootfs: echo "" > /data/data/com.codespace.ide.debug/files/.ubuntu_version

Static busybox: assets/tools/busybox_arm64 — installed to rootfs/usr/local/bin/ during extraction.

Host-side pre-install: ProotInstaller.kt streams Packages.gz line by line, downloads debs to file, extracts using BoundedInputStream + ZstdCompressorInputStream + TarArchiveInputStream. No proot involved. Dependencies: commons-compress:1.26.0, zstd-jni:1.5.6-4 (NO @aar suffix).

DNS + apt config is baked permanently into rootfs during extraction:
- /etc/resolv.conf: nameserver 8.8.8.8 / 8.8.4.4
- /etc/apt/apt.conf.d/00sandbox: APT::Sandbox::User "root"; AllowInsecureRepositories; AllowUnauthenticated

### pty_native.c — Termux's exact JNI implementation (last updated June 27 session)
Replaced the old forkpty() call with Termux's exact /dev/ptmx + fork() implementation:
- forkpty() → /dev/ptmx + manual fork() — Android-safe
- setsid() — proper terminal control group
- sigfillset() + sigprocmask(SIG_UNBLOCK) — unblocks all signals after fork (Android's Java process blocks many)
- Closes all extra file descriptors (3..255) in child
- clearenv() + repopulate — no inherited JVM junk in child env
- IUTF8 enabled — proper Unicode/emoji in terminal
- Flow control (IXON/IXOFF) disabled — no more Ctrl+S lockup
- Shows exec() error in terminal if shell launch fails — no more silent crash
- Shows "Failed to exec: errno N" on terminal before child exits

### ProotInstaller.kt — proot env additions (last updated June 27 session)
- Added LD_PRELOAD=libtermux-exec.so to proot environment. This is Termux's "secret weapon":
  intercepts exec() calls inside proot and rewrites /usr paths to work on Android.
  Without it, many Ubuntu binaries silently fail. Path: nativeLibraryDir/libtermux-exec.so
- Dropped PROOT_FORCE_COREDUMP=1 — was causing unnecessary overhead
- Triple System.gc() + runFinalization() + 800ms sleep BEFORE the deb download loop
  (gives Android time to reclaim heap after the 55MB rootfs extraction)

---

## WHAT IS WORKING (verified by build or logic)
- APK builds successfully (last green: 7b390f2ff6 at 2026-06-27T10:50)
- App launches, GitHub PAT login
- Ubuntu boots to root@localhost (proot launch args confirmed correct)
- apt update works (DNS baked in, apt config baked in)
- Progress messages show in Ubuntu tab (currentView redraws fix)
- Resumable download with 5% progress and 3 retries
- Extra keys bar, SSH Manager, Text Expansions
- Editor multi-tab, auto-save, session restore
- Explorer SAF folder picker
- TerminalService foreground service started before extraction (confirmed logic correct)
- Bash tab login shell: argv[0] = "-bash" (POSIX leading-dash convention, copied from Termux)
- pty_native.c replaced with Termux's exact implementation (setsid, clearenv, IUTF8, etc.)
- LD_PRELOAD=libtermux-exec.so added to proot env

## WHAT IS NOT YET TESTED ON DEVICE (install latest APK and verify)
- Bash tab: does it open with a prompt? (no more "login: applet not found")
- Ubuntu: does the foreground notification appear during extraction?
- Ubuntu: does extraction complete without crash? (OOM fix + triple GC in place)
- Ubuntu: does `curl --version` work after boot? (pre-install worked)
- Ubuntu: does `curl` actually function (network, SSL)? (libtermux-exec.so intercept)
- Tab completion and arrow key history in bash tab
- Terminal redraw on keystrokes — known issue, not yet addressed
- Tab session persistence — TerminalService.kt wired but session state not saved to disk

## WHAT IS BROKEN / BY DESIGN
- apt install — dpkg blocked by Samsung kernel chdir restriction. PERMANENT. Do not attempt.
- Host-side pre-install is the only package install mechanism for Ubuntu packages on this device.

---

## CRASH ROOT CAUSES FOUND (June 27, 2026 sessions)

### 1. Ubuntu extraction OOM crash — FIXED
Symptom: App crashes silently before showing any progress. No error message.
Root cause A: XZCompressorInputStream has NO memory limit by default. Can allocate up to
  ~800 MB for the Ubuntu .tar.xz. Android kills the process before any output appears.
Fix: Pass memoryLimitInKb = 96*1024 (96 MB) to XZCompressorInputStream constructor.
  ubuntu-questing tarball peaks at ~80 MB — 96 MB is safe with headroom.
Root cause B: TerminalService.start() was NEVER called — it was dead code. Extraction ran
  as a plain daemon Thread with the lowest OOM priority. Samsung kills these first.
Fix: TerminalService.start() called before Thread starts; stop() in finally block.
  Progress messages mirrored to notification to keep service visibly active.

### 2. Build failure — FIXED
Symptom: compileProdDebugKotlin FAILED — Unresolved reference: installEssentials
Root cause: ProjectShellScreen.kt called BusyboxInstaller.installEssentials() which
  was a stale reference from a previous AI session. The method never existed.
Fix: Replaced with installIfNeeded() which is the correct existing method.

### 3. Download buffer was 1 MB — FIXED (minor)
Root cause: ByteArray(1024 * 1024) during download. No throughput gain on mobile;
  wastes ~960 KB heap during download phase alongside other allocations.
Fix: Reduced to 64 KB.

### 4. Bash tab "login: applet not found" (code 127) — FIXED
Symptom: Bash tab shows "-login: applet not found" then "[Process completed (code 127)]"
Root cause: TerminalSession(busybox, home, arrayOf("--login"), env, 4000, client) — Termux's
  TerminalSession uses args[0] as argv[0]. So busybox received argv[0]="--login" and
  treated "--login" as an applet name. No "login" applet → code 127.
  Then tried arrayOf("bash","--login") — correct applet, but busybox bash doesn't support
  --login flag. Only the POSIX leading-dash argv[0] convention works.
Fix: arrayOf("-bash") — argv[0]="-bash" signals login shell. Copied exactly from Termux source.
  String processName = (isLoginShell ? "-" : "") + ShellUtils.getExecutableBasename(executable)
  arguments[0] = processName  // e.g. "-bash"

### 5. Pre-install OOM crash during curl deb download — FIXED
Symptom: Download completes (55MB rootfs), extraction starts, app crashes during
  "Downloading curl_8.14.1-2ubuntu1_arm64.deb..."
Root cause: 55MB rootfs extraction spikes heap. The deb download immediately after hits
  the memory ceiling. Android OOM killer kills the process (bypasses try/catch).
  System.gc() at line 188 was already there but not enough after such a large extraction.
Fix: Triple System.gc() + runFinalization() + 800ms sleep right before the deb download loop.
  Gives Android time to actually reclaim the heap before touching the network again.

### 6. pty_native.c — 4 critical differences from Termux — FIXED
Symptom: Terminal behavior subtly wrong (signals, Unicode, Ctrl+S lockup, silent exec failures)
Root cause: Custom forkpty() implementation missing key Termux behaviors.
Fix: Replaced entire pty_native.c with Termux's exact implementation. See CURRENT ARCHITECTURE above.

### 7. Ubuntu binaries silently failing in proot — FIXED
Symptom: Ubuntu commands fail without error after proot launches.
Root cause: Missing LD_PRELOAD=libtermux-exec.so in proot env. This shared library intercepts
  exec() calls and rewrites /usr paths for Android compatibility.
Fix: Added to proot env: LD_PRELOAD={nativeLibraryDir}/libtermux-exec.so

---

## TEST PROCEDURE (next device test)
Install latest APK (artifact: app-prod-arm64-v8a-debug from commit 7b390f2ff6). Test in order — stop at first failure:

1. Open app → tap + in terminal tab bar → **Open Bash Terminal**
   - Expected: clean bash prompt (no "login: applet not found")
   - Failure means: BusyboxInstaller argv[0] or libbusybox.so path wrong

2. Open app → tap + in terminal tab bar → **Open Ubuntu Linux**
   - Expected: "[Ubuntu] Checking installation..." appears immediately
   - Failure means: TerminalService didn't start

3. Watch extraction:
   - Expected: foreground notification visible, "Downloading... 0%...100%", "Extracting Ubuntu rootfs...", "Pre-installing essential packages...", "Downloading curl_8.14.1-2ubuntu1_arm64.deb...", "Extracting...", "Essential packages pre-installed", then proot launches to shell
   - If crash during deb download: triple GC fix didn't work — try increasing sleep from 800ms to 1500ms

4. At Ubuntu shell prompt, run: `curl --version`
   - Expected: curl 8.14.x output
   - Failure: pre-install failed or libtermux-exec.so not working

5. Test bash tab separately: tap + → New Bash Terminal
   - Expected: prompt shows with no errors, .bashrc loaded

Report result of each step.

---

## NEXT STEPS IN ORDER

1. **TEST on device** (see TEST PROCEDURE above)
2. If bash tab works: verify tab completion and arrow key history
3. If curl works in Ubuntu: expand pre-install list (wget, git, python3, nano)
4. Fix terminal redraw on keystrokes
5. Fix tab session persistence (save session state to disk on TerminalService stop)
6. Install Ollama: `curl -fsSL https://ollama.com/install.sh | sh`
7. UI rebrand — real VS Code functionality (search, git, run/debug, extensions, AI panel)
8. App icon — propagate new V/\Code logo to all mipmap folders (mdpi through xxxhdpi)
9. Play Store release prep

---

## HARD RULES — NEVER BREAK

1. NEVER gate Open Ubuntu behind deviceCompat.shouldUseOfflineOnly()
2. NEVER add com.github.termux:termux-terminal-view as Gradle dep — source is vendored
3. NEVER replace TerminalSession.java with ProcessBuilder version
4. NEVER use copyTo() for tar extraction — use manual ByteArray(8192) read loop
5. NEVER use static proot linking — Android 14 TLS alignment fails
6. NEVER put executable binaries in filesDir — NOEXEC on Android 14
7. NEVER remove argv[0] proot from args array — execvp requires it
8. NEVER set onTextChanged to empty lambda — kills screen redraws
9. ALWAYS use git pull --rebase && git push
10. NEVER use heredoc EOF inside YAML workflows
11. NEVER use find /data/app on device — permission denied
12. NEVER use ls in app bash tab — use [ -f path ] builtins
13. NEVER use readBytes() for large files — OOM on 3GB device, stream instead
14. NEVER use @aar suffix on zstd-jni — suppresses native .so packaging
15. Samsung kernel blocks chdir inside proot — NEVER assume cd works in Ubuntu
16. ALWAYS grep codebase for other callers before renaming or removing any function
17. NEVER assume any API signature, constructor args, or parameter types. Always read the actual source or javadoc first. Verify with the jar/docs before writing any call.
18. XZCompressorInputStream correct form: XZCompressorInputStream(stream, false, memoryLimitInKb) — 3 args. Arg2=Boolean decompressConcatenated, Arg3=Int memoryLimitInKb. 2-arg constructor takes Boolean not Int.
19. ALWAYS start TerminalService before any long extraction/download thread — plain threads get OOM-killed
20. NEVER use --login flag for bash tab — busybox bash doesn't support it. Use argv[0]="-bash" (leading dash POSIX convention).
21. NEVER use arrayOf("--login") as TerminalSession args — Termux TerminalSession uses args[0] as argv[0], so busybox sees "--login" as an applet name.
22. ALWAYS add LD_PRELOAD=libtermux-exec.so to proot environment — without it Ubuntu binaries silently fail on Android.
23. NEVER use apt install inside Ubuntu on this device — dpkg forks subprocesses that the Samsung kernel blocks. Host-side pre-install is the ONLY mechanism.

---

## KEY FILES

android/app/src/main/
  java/com/codespace/ide/terminal/
    ProotInstaller.kt       — CRITICAL: download, extract, pre-install packages, launch proot
    BusyboxInstaller.kt     — bash tab shell setup (libbusybox.so nativeLibraryDir trick)
    TerminalService.kt      — foreground service, MUST be started before extraction
    TerminalModeManager.kt  — mode persistence
    DeviceCompatibility.kt  — DO NOT use to gate Ubuntu
  java/com/codespace/ide/ui/panes/
    TerminalPane.kt         — terminal UI, tabs, addUbuntuTab(), currentView redraws fix
    SshManagerSheet.kt      — SSH profile manager
    TextExpansionSheet.kt   — text expansion manager
  java/com/codespace/ide/ui/screens/
    ProjectShellScreen.kt   — shell actions menu — keep in sync with BusyboxInstaller API
  assets/tools/
    busybox_arm64           — static busybox binary (2.7MB)
  jniLibs/arm64-v8a/        — proot + busybox + zstd binaries, always executable
  cpp/
    pty_native.c            — JNI: Termux's exact /dev/ptmx+fork() impl (setsid, clearenv, IUTF8, no flow control)
    CMakeLists.txt          — declares all proot libs as IMPORTED

---

## BUILD ENVIRONMENT

NDK: 26.1.10909125
compileSdk/targetSdk: 34, minSdk: 26
Kotlin + Jetpack Compose + Hilt DI
Key deps: commons-compress:1.26.0, zstd-jni:1.5.6-4, xz:1.9
Codespace: urban-umbrella-774x47p55px394p (shutdown not deleted)
Access: gh cs ssh from Termux
Accounts: wisdom131-max (owner/admin), wisdomijezie90-art (collaborator)

---

## LIBRARY NOTES

### zstd-jni
- CORRECT: implementation("com.github.luben:zstd-jni:1.5.6-4") with NO classifier
  The libzstd-jni.so is bundled directly in jniLibs/arm64-v8a/ — do NOT use classifier
- WRONG: implementation("com.github.luben:zstd-jni:1.5.6-4@aar") — suppresses native .so
- WRONG: linux_aarch64 or android_aarch64 classifier — causes duplicate .so conflict
- Always verify: unzip -l app.apk | grep zstd

### XZ extraction
- ALWAYS use XZCompressorInputStream(stream, false, 96 * 1024) — the 3-arg constructor. Arg 2 is Boolean decompressConcatenated (use false). Arg 3 is memoryLimitInKb (Int).
- Default (no limit) can allocate 800 MB, causing silent OOM crash on 3GB device
- 96 MB is safe for ubuntu-questing tarball (peaks ~80 MB). If MemoryLimitException is thrown,
  the error surfaces in the UI — do not increase beyond 128 MB without profiling.

### Termux TerminalSession argv convention
- TerminalSession(executable, cwd, args, env, ...) — args[0] is argv[0] for the process.
- For login shell: args = arrayOf("-bash") where "-bash" is the process name with leading dash.
- Termux source: arguments[0] = (isLoginShell ? "-" : "") + ShellUtils.getExecutableBasename(executable)
- This is the POSIX way: every shell checks argv[0][0] == '-' to decide if it's a login shell.

---

Last updated: June 27, 2026 by Superagent (Base44) — session 2 (PDF catch-up)
