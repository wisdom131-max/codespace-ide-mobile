# AI Agent / Copilot — Project Context
> Read this FIRST before touching any code. Updated June 25, 2026.

---

## What This Project Is

**CodeSpace IDE** — a VS Code-style Android IDE app (Kotlin + Jetpack Compose).
Built and maintained phone-only by Wisdom using Termux + GitHub Codespaces. No PC exists in this workflow.

- **Package:** `com.codespace.ide.debug`
- **Repo:** `wisdom131-max/codespace-ide-mobile`
- **APK to install:** `app-prod-arm64-v8a-debug.apk` (always arm64-v8a)
- **Build:** GitHub Actions auto-builds on every push → artifact `codespace-ide-apk`
- **Device:** Android 14, arm64-v8a

---

## Communication Rules (Wisdom's preferences)
- Short, direct answers. No fluff.
- Always say WHERE to paste code (Codespace terminal vs app bash tab).
- Never repeat a failed approach without explaining what changed.
- One command at a time unless batching is confirmed safe.
- When in doubt — check the file first before patching.

---

## How to Push & Build

```bash
git add .
git commit -m "your message"
git pull --rebase && git push   # always rebase, never plain push
```
Build triggers automatically. Wait ~5-8 min → Actions tab → latest run → Artifacts → codespace-ide-apk.zip.

---

## THE UBUNTU PROOT PROBLEM — Full History

This is the hardest part of the project. Read carefully.

### Goal
Tap "🐧 Open Ubuntu Linux" → proot starts → Ubuntu rootfs mounts → bash prompt → apt works.

### Root Cause Chain (resolved as of build #350)

| # | Problem | Fix |
|---|---|---|
| 1 | `copyTo()` in tar extraction → all files empty | Replaced with manual `ByteArray(8192)` read loop |
| 2 | `/bin/bash` not found | Ubuntu uses merged /usr — path is `/usr/bin/bash` |
| 3 | filesDir is noexec on Android 14 | Executables must be in `nativeLibraryDir` or `codeCacheDir` |
| 4 | Custom-compiled proot was DYN (shared lib), not PIE | Replaced with real Termux PIE binary from packages.termux.dev |
| 5 | `libtalloc.so` SONAME mismatch (proot needed `libtalloc.so.2`) | `patchelf --set-soname` to fix SONAME |
| 6 | Gradle stripped `libtalloc.so` (no JNI reference) | Added `System.loadLibrary()` stubs to force packaging |
| 7 | `libandroid-shmem.so` missing | Extracted from termux deb, added to jniLibs |
| 8 | `PROOT_LOADER` env var was empty string at runtime | Explicitly set to `$nativeLibraryDir/libproot-loader.so` with logging |
| 9 | **`argv[0]` missing from args array** | JNI does `execvp(cmd, argv)` — argv[0] MUST be `"proot"` (program name). Without it args shift by 1 and proot fails → silently falls back to `/system/bin/sh` |

### Current Working Architecture (build #350+)

**All proot binaries live in `jniLibs/arm64-v8a/`** — Gradle packages them into `nativeLibraryDir` which is always executable on Android:

```
jniLibs/arm64-v8a/
  libproot.so          ← real Termux PIE proot binary (239KB)
  libproot-loader.so   ← proot guest ELF loader (18KB)
  libtalloc.so         ← talloc, SONAME patched to "libtalloc.so" (34KB)
  libandroid-shmem.so  ← Android shmem shim (14KB)
  libtermux-exec.so    ← termux exec helper (7KB)
```

**`ProotInstaller.kt` launchArgs() — exactly how it must work:**
```kotlin
val nativeDir = context.applicationInfo.nativeLibraryDir
val proot     = "$nativeDir/libproot.so"
val loader    = "$nativeDir/libproot-loader.so"

val args = arrayOf(
    "proot",            // argv[0] = program name — REQUIRED, JNI does execvp(cmd, argv)
    "--link2symlink",   // MANDATORY — handles symlinks on filesDir filesystem
    "--kill-on-exit",
    "-S", rootfs,
    "-b", "/proc:/proc",
    "-b", "/dev:/dev",
    "-b", "/sys:/sys",
    "-b", "$hostFiles:/host-files",
    "-w", "/root",
    "/usr/bin/env", "-i",
    "HOME=/root",
    "TERM=xterm-256color",
    "PATH=/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin",
    "LANG=en_US.UTF-8",
    "/usr/bin/bash", "--login"
)

val envVars = arrayOf(
    "PROOT_LOADER=$loader",       // proot exec()s this — must point to real file
    "LD_LIBRARY_PATH=$nativeDir", // linker finds libtalloc.so + libandroid-shmem.so
    "PROOT_TMP_DIR=$tmpDir",
    "TMPDIR=$tmpDir",
    "PROOT_NO_SECCOMP=1",         // required on Android kernels
    "HOME=${context.filesDir.absolutePath}"
)
```

### Ubuntu Rootfs
- URL: `https://github.com/termux/proot-distro/releases/download/v4.30.1/ubuntu-questing-aarch64-pd-v4.30.1.tar.xz`
- VERSION string: `ubuntu-questing-v4.30.1`
- Extracted to: `context.filesDir/ubuntu-rootfs/`
- `isInstalled()` checks: `.ubuntu_version` file content == VERSION AND `usr/bin/bash` exists
- bash confirmed present at: `ubuntu-rootfs/usr/bin/bash` (1.6MB)
- Reset extraction: `echo "" > /data/data/com.codespace.ide.debug/files/.ubuntu_version`

### JNI Layer (pty_native.c)
The native code does:
```c
execvp(cmdStr, argv);              // cmdStr = proot path, argv = args array
// on failure → falls back to /system/bin/sh silently
```
This is why argv[0] = "proot" is critical. If args[0] is "--link2symlink", proot sees wrong args and exits → sh fallback with no visible error.

---

## HARD RULES — Never Break These

1. **NEVER gate "Open Ubuntu" behind `deviceCompat.shouldUseOfflineOnly()`** — Ubuntu must always be accessible
2. **NEVER add `com.github.termux:termux-terminal-view` as a Gradle dep** — Termux source is vendored in `com/termux/terminal/`
3. **NEVER replace `TerminalSession.java` with a ProcessBuilder version** — must use the PTY/forkpty JNI version
4. **NEVER use `copyTo()` for tar extraction** — use manual `ByteArray(8192)` read loop
5. **NEVER use static proot linking** — Android 14 TLS alignment (needs 64-byte, NDK gives 8-byte) always fails
6. **NEVER put executable binaries in `filesDir`** — it is noexec on Android 14
7. **NEVER remove argv[0] ("proot") from the args array** — execvp requires it
8. **ALWAYS use `git pull --rebase && git push`** — plain push gets rejected
9. **NEVER use heredoc (`<<EOF`) inside YAML workflows** — YAML parser breaks it

---

## Terminology

| Term | Meaning |
|---|---|
| `filesDir` | `/data/data/com.codespace.ide.debug/files/` — writable, **NOEXEC** |
| `nativeLibraryDir` | `/data/app/~~.../com.codespace.ide.debug-.../lib/arm64/` — **EXECUTABLE** ✅ |
| `codeCacheDir` | `/data/data/com.codespace.ide.debug/code_cache/` — executable on most devices |
| `proot` | Userspace chroot via ptrace — no root needed |
| `--link2symlink` | proot flag to handle symlinks on noexec filesystems — **MANDATORY** |
| `rootfs` | Ubuntu filesystem on device |
| `VERSION` | `ubuntu-questing-v4.30.1` |
| `jniLibs/` | Repo folder Gradle packages as native libs → `nativeLibraryDir` |

---

## Key Files

```
android/app/src/main/
├── java/com/codespace/ide/terminal/
│   ├── ProotInstaller.kt          ← CRITICAL: rootfs download/extract, proot launchArgs
│   ├── BusyboxInstaller.kt        ← bootstrap shell (noexec issue — don't touch until Ubuntu works)
│   ├── TerminalModeManager.kt     ← mode persistence (Ubuntu vs Offline)
│   └── DeviceCompatibility.kt     ← DO NOT use to gate Ubuntu
├── java/com/codespace/ide/ui/panes/
│   ├── TerminalPane.kt            ← terminal UI, tabs, menus, extra keys bar
│   ├── SshManagerSheet.kt         ← SSH profile manager
│   └── TextExpansionSheet.kt      ← text expansion manager
├── jniLibs/arm64-v8a/             ← proot binaries (always executable)
├── assets/proot/arm64-v8a/        ← duplicate binaries (codeCacheDir approach, not used)
└── cpp/pty_native.c               ← JNI: forkpty + execvp — the core terminal engine
```

---

## Remaining Work (Priority Order)

### 🔴 P1 — Verify Ubuntu boots on device (build #350)
Test "🐧 Open Ubuntu Linux" → expect `root@localhost:~#` prompt.
If still failing, check logcat for `ProotInstaller` tag — logs now print all paths.

### 🟡 P2 — App bash tab: Permission Denied
BusyboxInstaller extracts to `filesDir` → noexec. Fix = route through proot (same as Ubuntu tab).
DO NOT fix until Ubuntu tab is confirmed working.

### 🟡 P3 — Terminal tab/session persistence
Sessions live in Compose state, destroyed on background/close.
Fix = foreground `TerminalService` (stub exists at `TerminalService.kt`, not wired up).

### 🟡 P4 — Terminal text doesn't redraw until tap/scroll
`TerminalView.onScreenUpdated()` likely not wired to keystroke-write path.

### 🟢 P5 — Ollama inside Ubuntu
Once Ubuntu boots: `curl -fsSL https://ollama.com/install.sh | sh` inside Ubuntu.

### 🟢 P6 — Git panel not verified end-to-end
JGit wired in SourceControlPane.kt but never confirmed working.

---

## Build Environment

- **NDK:** 26.1.10909125
- **compileSdk / targetSdk:** 34, **minSdk:** 26
- **Kotlin + Jetpack Compose + Hilt DI**
- **Codespace:** `urban-umbrella-774x47p55px394p` (shutdown, not deleted)
- **Access:** `gh cs ssh` from Termux on phone
- **Accounts:** `wisdom131-max` (owner/admin), `wisdomijezie90-art` (collaborator, no admin)

---

*Last updated: June 25, 2026 — build #350*
