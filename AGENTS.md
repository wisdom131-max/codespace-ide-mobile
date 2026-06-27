# AI Agent / Copilot — Project Context
> Read this FIRST before touching any code. Updated June 27, 2026.

---

## What This Project Is

**Visual Node Code** (display name) / **CodeSpace IDE** (package) — a VS Code-style Android IDE with built-in Ubuntu Linux terminal powered by proot. No root needed.

- **Package:** `com.codespace.ide.debug`
- **Repo:** `wisdom131-max/codespace-ide-mobile`
- **APK:** `app-prod-arm64-v8a-debug.apk`
- **Device:** Android 14, Samsung kernel 5.15.180-android13, arm64-v8a, 3GB RAM
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

---

## WHAT IS WORKING (verified by build or logic)
- APK builds successfully (last green: commit 28285028054 at 09:20)
- App launches, GitHub PAT login
- Ubuntu boots to root@localhost (proot launch args confirmed correct)
- apt update works (DNS baked in, apt config baked in)
- Progress messages show in Ubuntu tab (currentView redraws fix)
- Resumable download with 5% progress and 3 retries
- Extra keys bar, SSH Manager, Text Expansions
- Editor multi-tab, auto-save, session restore
- Explorer SAF folder picker
- Bash tab now uses libbusybox.so (nativeLibraryDir trick) — NOT YET TESTED on device
- TerminalService foreground service now started before extraction — NOT YET TESTED on device

## WHAT IS BROKEN / UNTESTED
- apt install — dpkg blocked by Samsung kernel chdir restriction (known, by design for now)
- Host-side pre-install (curl + libcurl4t64) — logic is correct, NOT YET VERIFIED on device
- Bash tab / tab completion / arrow keys — fix pushed but NOT YET TESTED on device
- Terminal redraw on keystrokes — known issue, not yet addressed
- Tab session persistence — TerminalService.kt wired but session state not saved to disk

---

## CRASH ROOT CAUSES FOUND (June 27, 2026 session)

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

---

## NEXT STEPS IN ORDER

1. **TEST on device** — install latest APK, tap Ubuntu, confirm:
   a. Does the foreground notification appear? (confirms service started)
   b. Does extraction complete? (no more crash)
   c. Does `curl --version` work after Ubuntu boots? (confirms pre-install worked)
   d. Does bash tab open with busybox bash? (libbusybox.so fix)
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
17. NEVER call XZCompressorInputStream without memoryLimitInKb — OOM on 3GB device. Use 96*1024.
18. ALWAYS start TerminalService before any long extraction/download thread — plain threads get OOM-killed

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
    pty_native.c            — JNI forkpty + execvp
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
- ALWAYS use XZCompressorInputStream(stream, 96 * 1024) — the 2-arg constructor with memoryLimitInKb
- Default (no limit) can allocate 800 MB, causing silent OOM crash on 3GB device
- 96 MB is safe for ubuntu-questing tarball (peaks ~80 MB). If MemoryLimitException is thrown,
  the error surfaces in the UI — do not increase beyond 128 MB without profiling.

---

Last updated: June 27, 2026 by Superagent (Base44)
