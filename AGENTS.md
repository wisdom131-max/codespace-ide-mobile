# AI Agent / Copilot — Project Context
> Read this FIRST before touching any code. Updated June 25, 2026 (session 2).

---

## What This Project Is

**CodeSpace IDE** — a VS Code-style Android IDE app (Kotlin + Jetpack Compose).
Built and maintained phone-only by Wisdom using Termux + GitHub Codespaces. No PC exists in this workflow.

- **Package:** `com.codespace.ide.debug`
- **Repo:** `wisdom131-max/codespace-ide-mobile`
- **APK to install:** `app-prod-arm64-v8a-debug.apk` (always arm64-v8a)
- **Build:** GitHub Actions auto-builds on every push → artifact `codespace-ide-apk`
- **Device:** Android 14, arm64-v8a, 3GB RAM

---

## Communication Rules (Wisdom's preferences)
- Short, direct answers. No fluff.
- Always say WHERE to paste code (Codespace terminal vs app bash tab).
- Never repeat a failed approach without explaining what changed.
- One command at a time unless batching is confirmed safe.
- When in doubt — check the file first before patching.
- Always say what a fix will do before applying it.
- find /data/app is PERMISSION DENIED on device — never use it to check .so files.
- Use [ -f path ] and echo EXISTS or echo MISSING not ls in app bash tab.
- Always git pull --rebase and git push — never plain push.

---

## How to Push and Build

git add .
git commit -m "your message"
git pull --rebase && git push

Build triggers automatically. Wait 5-8 min, Actions tab, latest run, Artifacts, codespace-ide-apk.zip.
Always fully uninstall old APK before installing new one when binaries changed.

---

## THE UBUNTU PROOT PROBLEM — Full History

### Goal
Tap Open Ubuntu Linux, proot starts, Ubuntu rootfs mounts, bash prompt, apt works.

### Attempt History

1. copyTo() in tar extraction — all files empty — Fixed with manual ByteArray(8192) read loop
2. /bin/bash not found — Ubuntu uses merged /usr, path is /usr/bin/bash
3. filesDir is noexec on Android 14 — executables must be in nativeLibraryDir
4. Custom proot was DYN not PIE — replaced with real Termux PIE binary
5. libtalloc.so SONAME mismatch — fixed with patchelf --set-soname
6. Gradle stripped unreferenced .so files — added all 4 libs to CMakeLists.txt as IMPORTED plus explicit jniLibs srcDir in build.gradle.kts
7. libandroid-shmem.so missing — extracted from termux deb, added to jniLibs
8. PROOT_LOADER env var empty at runtime — explicitly set to nativeLibraryDir/libproot-loader.so
9. argv[0] missing from args array — argv[0] MUST be proot, execvp requires it
10. Ubuntu tab opened blank, progress messages not showing — fixed onTextChanged wiring, added currentView state ref so background thread writes trigger onScreenUpdated
11. Download had no resume or retry — replaced with resumable HTTP Range download, 5% progress updates, 3 retries
12. find /data/app returned empty, thought .so files missing — permission denied on /data/app, files ARE packaged, confirmed via unzip -l on APK

### Current Architecture

All proot binaries live in jniLibs/arm64-v8a/
Gradle packages them into nativeLibraryDir which is always executable.

jniLibs/arm64-v8a/
  libproot.so          — real Termux PIE proot binary 239KB
  libproot-loader.so   — proot guest ELF loader 18KB
  libtalloc.so         — talloc, SONAME patched to libtalloc.so 34KB
  libandroid-shmem.so  — Android shmem shim 14KB
  libtermux-exec.so    — termux exec helper 7KB

CMakeLists.txt declares all 4 as IMPORTED so Gradle packages them.
build.gradle.kts has explicit sourceSets jniLibs.srcDirs declaration.

launchArgs uses:
- argv[0] = "proot" (REQUIRED)
- --link2symlink (MANDATORY)
- --kill-on-exit
- -S rootfs
- binds for /proc /dev /sys /host-files
- /usr/bin/bash --login
- env: PROOT_LOADER, LD_LIBRARY_PATH, PROOT_TMP_DIR, PROOT_NO_SECCOMP=1

### Ubuntu Rootfs
- URL: https://github.com/termux/proot-distro/releases/download/v4.30.1/ubuntu-questing-aarch64-pd-v4.30.1.tar.xz
- VERSION: ubuntu-questing-v4.30.1
- Extracted to: context.filesDir/ubuntu-rootfs/
- isInstalled checks: .ubuntu_version == VERSION AND usr/bin/bash exists
- Reset extraction: echo "" > /data/data/com.codespace.ide.debug/files/.ubuntu_version
- Download: resumable HTTP Range, 5% progress updates, 3 auto-retries

---

## HARD RULES — Never Break These

1. NEVER gate Open Ubuntu behind deviceCompat.shouldUseOfflineOnly()
2. NEVER add com.github.termux:termux-terminal-view as a Gradle dep — source is vendored
3. NEVER replace TerminalSession.java with ProcessBuilder version
4. NEVER use copyTo() for tar extraction — use manual ByteArray(8192) read loop
5. NEVER use static proot linking — Android 14 TLS alignment fails
6. NEVER put executable binaries in filesDir — noexec on Android 14
7. NEVER remove argv[0] proot from args array — execvp requires it
8. NEVER set onTextChanged to empty lambda — kills screen redraws
9. ALWAYS use git pull --rebase && git push
10. NEVER use heredoc EOF inside YAML workflows
11. NEVER use find /data/app on device — permission denied, use unzip -l on APK instead
12. NEVER use ls in app bash tab to check files — use [ -f path ] builtins

---

## Terminology

filesDir = /data/data/com.codespace.ide.debug/files/ — writable, NOEXEC
nativeLibraryDir = /data/app/.../lib/arm64/ — EXECUTABLE
codeCacheDir = /data/data/com.codespace.ide.debug/code_cache/ — executable on most devices
proot = userspace chroot via ptrace, no root needed
--link2symlink = proot flag to handle symlinks, MANDATORY
rootfs = Ubuntu filesystem on device
VERSION = ubuntu-questing-v4.30.1
jniLibs/ = repo folder Gradle packages as native libs into nativeLibraryDir

---

## Key Files

android/app/src/main/
  java/com/codespace/ide/terminal/
    ProotInstaller.kt       — CRITICAL: rootfs download/extract, proot launchArgs
    BusyboxInstaller.kt     — bootstrap shell, do not touch until Ubuntu works
    TerminalModeManager.kt  — mode persistence
    DeviceCompatibility.kt  — DO NOT use to gate Ubuntu
  java/com/codespace/ide/ui/panes/
    TerminalPane.kt         — terminal UI, tabs, menus, currentView redraws
    SshManagerSheet.kt      — SSH profile manager
    TextExpansionSheet.kt   — text expansion manager
  jniLibs/arm64-v8a/        — proot binaries, always executable
  cpp/
    pty_native.c            — JNI forkpty + execvp, core terminal engine
    CMakeLists.txt          — declares all proot libs as IMPORTED

---

## Remaining Work Priority Order

P1 RED — Verify Ubuntu boots on device
Test Open Ubuntu Linux, should now see progress messages, expect root@localhost prompt.
If blank tab: redraw bug still present, check currentView wiring in TerminalPane.kt.
If Process completed: check diagnostic lines for proot canExec and bash exists.

P2 YELLOW — Terminal tab/session persistence
Sessions destroyed on background/close. Fix = foreground TerminalService stub exists not wired.

P3 YELLOW — Terminal text redraw on keystrokes
onScreenUpdated not wired to keystroke-write path. Affects all tabs.

P4 GREEN — Ollama inside Ubuntu
Once Ubuntu boots: curl -fsSL https://ollama.com/install.sh | sh inside Ubuntu.

P5 GREEN — Git panel not verified end-to-end

P6 GREEN — AI Assistant panel not in nav, code exists, removed during terminal focus

---

## Build Environment

NDK: 26.1.10909125
compileSdk / targetSdk: 34, minSdk: 26
Kotlin + Jetpack Compose + Hilt DI
Codespace: urban-umbrella-774x47p55px394p (shutdown, not deleted)
Access: gh cs ssh from Termux on phone
Accounts: wisdom131-max (owner/admin), wisdomijezie90-art (collaborator, no admin)

---

Last updated: June 25, 2026 — session 2 with Claude Sonnet 4.6
