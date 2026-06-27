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
- Check the file before patching.
- Always say what a fix will do before applying it.
- find /data/app is PERMISSION DENIED on device. Use unzip -l on APK instead.
- Use [ -f path ] not ls in app bash tab.
- Always git pull --rebase && git push.
- NEVER load large files with readBytes() — OOM on 3GB device. Stream instead.

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

proot launch uses complete proot-distro flag set:
--kill-on-exit --link2symlink --sysvipc --kernel-release=\Linux\localhost\6.17.0-PRoot-Distro\... -L --change-id=0:0 -r rootfs plus all required binds.

Ubuntu rootfs: ubuntu-questing-aarch64 (Ubuntu 25.04 Questing)
VERSION string: ubuntu-questing-v4.30.1
Reset rootfs: echo "" > /data/data/com.codespace.ide.debug/files/.ubuntu_version

Static busybox: assets/tools/busybox_arm64 — installed to rootfs/usr/local/bin/ during extraction.

Host-side pre-install: ProotInstaller.kt streams Packages.gz line by line, downloads debs to file, extracts using BoundedInputStream + ZstdCompressorInputStream + TarArchiveInputStream. No proot involved. Dependencies: commons-compress:1.26.0, zstd-jni:1.5.6-4 (NO @aar suffix).

Manual apt setup needed each session (until baked in):
echo "nameserver 8.8.8.8" > /etc/resolv.conf
echo 'APT::Sandbox::User "root";' > /etc/apt/apt.conf.d/00sandbox
echo 'Acquire::AllowInsecureRepositories "true";' >> /etc/apt/apt.conf.d/00sandbox
echo 'APT::Get::AllowUnauthenticated "true";' >> /etc/apt/apt.conf.d/00sandbox

---

## WHAT IS WORKING
- APK builds, app launches, GitHub PAT login
- Ubuntu boots to root@localhost
- apt update works
- Progress messages show in Ubuntu tab (currentView redraws fix)
- Resumable download with 5% progress and 3 retries
- Extra keys bar, SSH Manager, Text Expansions
- Editor multi-tab, auto-save, session restore
- Explorer SAF folder picker

## WHAT IS BROKEN
- apt install — dpkg blocked by Samsung kernel chdir restriction
- Host-side pre-install — last fix was streaming extraction, not yet verified on device
- Bash terminal tab — falls back to /system/bin/sh
- Terminal redraw on keystrokes
- Tab session persistence

---

## NEXT STEPS IN ORDER

1. Test current build — does curl --version work after Ubuntu boots?
2. If yes: bake DNS + apt config into proot launch init script permanently
3. Expand pre-install list: wget, git, python3
4. Install Ollama: curl -fsSL https://ollama.com/install.sh | sh
5. Fix bash terminal tab
6. Fix terminal session persistence (TerminalService.kt stub exists, not wired)
7. Fix terminal redraw on keystrokes
8. UI rebrand — real VS Code functionality (search, git, run/debug, extensions, AI panel)
9. App icon — propagate new V/\Code logo to all mipmap folders (mdpi through xxxhdpi)
10. Play Store release prep

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

---

## KEY FILES

android/app/src/main/
  java/com/codespace/ide/terminal/
    ProotInstaller.kt       — CRITICAL: download, extract, pre-install packages, launch proot
    BusyboxInstaller.kt     — bootstrap shell, do not touch until Ubuntu works
    TerminalModeManager.kt  — mode persistence
    DeviceCompatibility.kt  — DO NOT use to gate Ubuntu
  java/com/codespace/ide/ui/panes/
    TerminalPane.kt         — terminal UI, tabs, currentView redraws fix
    SshManagerSheet.kt      — SSH profile manager
    TextExpansionSheet.kt   — text expansion manager
  assets/tools/
    busybox_arm64           — static busybox binary (2.7MB)
  jniLibs/arm64-v8a/        — proot binaries, always executable
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

Last updated: June 27, 2026 by Claude Sonnet 4.6
