# AI Agent / Copilot — Project Context
> Read this FIRST before touching any code. Updated June 28, 2026.


---

# MASTER PLAN — 2026-06-30 (AUTHORITATIVE — SUPERSEDES ALL PREVIOUS PLANS)

## What Wisdom wants
1. Ubuntu tab working first (was close before it broke)
2. Replicate the real Termux APK terminal stack exactly — study the 7z, copy everything
3. No rewrites — surgical, verified, step-by-step with green builds
4. Update this MD after every completed step

---

## WHAT WE KNOW FROM THE TERMUX APK (v0.118.3 — studied 2026-06-30)

File: `termux-app_v0.118.3+github-debug_arm64-v8a today to base 44.7z` (42 MB)
Google Drive ID: `1pD7CVZBknpYF8tLmgr9lw95_XP4jqReO`

### APK Structure (exact)
```
termux-app_v0.118.3+github-debug_arm64-v8a/
├── AndroidManifest.xml          (13 KB — permissions, services, receivers)
├── classes.dex                  (9.3 MB — main dex, all app logic)
├── classes2.dex ... classes29.dex  (support libs, 3 KB – 1.3 MB each)
├── lib/
│   └── arm64-v8a/
│       ├── libtermux-bootstrap.so  (28.7 MB — NOT a regular .so)
│       │     Magic: 7f454c46 = ELF binary
│       │     This IS the bootstrap zip embedded as an ELF .so
│       │     Android extracts it to nativeLibraryDir (always executable)
│       │     This is how Termux ships the 3490-file bootstrap on Android
│       └── libtermux.so            (8 KB — JNI native library)
│             Exported JNI functions (exact names):
│             - Java_com_termux_terminal_JNI_close
│             - Java_com_termux_terminal_JNI_createSubprocess
│             - Java_com_termux_terminal_JNI_setPtyUTF8Mode
│             - Java_com_termux_terminal_JNI_setPtyWindowSize
│             - Java_com_termux_terminal_JNI_waitFor
│             Native calls used: clearenv, execvp, grantpt, setsid,
│             sigfillset, sigprocmask, ptsname_r, unlockpt, waitpid
│             Linker: Android clang 11.0.5 (r399163b1)
├── res/                         (460 XML files — layouts, drawables, menus)
├── resources.arsc               (772 KB — compiled resources)
└── META-INF/                    (signatures, kotlin module metadata)
```

### Key classes found in dex (from string scan)
```
com.termux.app.TermuxActivity
com.termux.app.TermuxApplication
com.termux.app.TermuxInstaller          ← bootstrap extraction logic
com.termux.app.TermuxInstaller$1
com.termux.app.TermuxInstaller$2
com.termux.app.RunCommandService
com.termux.app.TermuxOpenReceiver
com.termux.app.utils.CrashUtils
com.termux.app.utils.PluginUtils
com.termux.app.models.UserAction
com.termux.terminal.JNI                 ← JNI bridge (libtermux.so)
com.termux.terminal.KeyHandler
com.termux.terminal.TerminalRow
com.termux.terminal.TerminalBuffer
com.termux.terminal.TerminalColors
com.termux.terminal.TerminalOutput
com.termux.terminal.BuildConfig
com.termux.view.TerminalView            ← the terminal UI widget
com.termux.view.TerminalView$1
com.termux.view.TerminalView$2
com.termux.view.TerminalView$3
com.termux.view.TerminalRenderer
com.termux.view.TerminalViewClient
com.termux.shared.logger.Logger
com.termux.shared.data.DataUtils
com.termux.shared.data.IntentUtils
com.termux.shared.data.UrlUtils
com.termux.shared.file.FileUtils
com.termux.shared.shell.ShellUtils
com.termux.shared.shell.TermuxTask
com.termux.shared.view.ViewUtils
com.termux.shared.R$interpolator
```

### Exact JNI function signatures (from libtermux.so ELF)
```c
// These are the 5 JNI functions — same as what we have in pty_native.c
// Our implementation matches. The difference is in HOW we call them.
Java_com_termux_terminal_JNI_createSubprocess(cmd, cwd, args[], env[], pidArray[], rows, cols)
Java_com_termux_terminal_JNI_setPtyWindowSize(fd, rows, cols)
Java_com_termux_terminal_JNI_setPtyUTF8Mode(fd, utf8Mode)
Java_com_termux_terminal_JNI_waitFor(pid)
Java_com_termux_terminal_JNI_close(fd)
```

### How real Termux ships the bootstrap (CRITICAL DISCOVERY)
- `libtermux-bootstrap.so` (28.7 MB ELF) lives in `lib/arm64-v8a/`
- Android PackageManagerService extracts it to `nativeLibraryDir` on install
- `nativeLibraryDir` is ALWAYS marked executable — no W^X restriction
- Inside the ELF, the bootstrap zip is embedded as a data section
- Termux reads it from `nativeLibraryDir/libtermux-bootstrap.so` at runtime
- We already do this correctly — our `bootstrap-aarch64.zip` is in `assets/`
  but the execution pattern is the same (extract from a known path)

### Root paths Termux uses (hardcoded in dex strings)
```
/data/data/com.termux/files/usr    ← PREFIX
/data/data/com.termux/files/home   ← HOME
/data/user/0/com.termux/files/usr  ← alternate PREFIX (Android 7+)
```
We use:  `context.filesDir/termux-prefix` — correct, just different path

---

## KNOWN BUGS (DO NOT REPEAT THESE — DOCUMENTED PERMANENTLY)

| Bug | Root cause | Fix |
|-----|-----------|-----|
| `signal 31` on bash start | `LD_LIBRARY_PATH` includes any `.so` dir — wrong ABI injected into bash | **Remove LD_LIBRARY_PATH entirely** |
| `CANNOT LINK EXECUTABLE "--rcfile"` | `args[0]` = `"--rcfile"` — JNI execve treats args[0] as the binary name | Use `arrayOf("-bash")` only |
| `libandroid-support.so e_version: 65725` | `$prefix/lib` in `LD_LIBRARY_PATH` | Same fix — remove LD_LIBRARY_PATH |
| `/etc/profile: Permission denied` | `--login` flag tries host `/etc/profile` | Remove `--login` flag entirely |
| 185 scripts broken paths | Bootstrap zip hardcodes `/data/data/com.termux/files/usr` | `patchAllScripts()` after extraction |
| `apt install` — no packages found | `sources.list` is empty in bootstrap zip | Write sources.list at extraction time |
| Samsung `symlinkat()` seccomp block | Samsung kernel 5.15 blocks symlinkat() | Copy multi-call binaries instead of symlinking |

---

## STEP-BY-STEP EXECUTION PLAN

### PRIORITY 1: Ubuntu tab (close to working — fix first)

#### STEP 1 — Diagnose Ubuntu apt failure
**Status:** ✅ DONE (2026-06-30)
**What:** Read ProotInstaller.kt fresh, read launchArgs(), check proot command args
**Test on device:** Open Ubuntu tab → run `apt update`
**Expected error:** Either network, sources.list, or proot exec failure
**Rule:** Read the file, understand exactly what proot command is executed, then fix

#### STEP 2 — Fix Ubuntu apt sources.list
**Status:** ✅ DONE (2026-06-30) — sources.list already correct in commit 2b93e4ef
**File:** `ProotInstaller.kt`
**What:** Confirm `sources.list` is written with correct Ubuntu 25.04 (questing) URLs:
```
deb http://ports.ubuntu.com/ubuntu-ports questing main restricted universe multiverse
deb http://ports.ubuntu.com/ubuntu-ports questing-updates main restricted universe multiverse
deb http://ports.ubuntu.com/ubuntu-ports questing-security main restricted universe multiverse
```
**Rule:** Only touch `writeEnvironment()` or the sources.list injection block

#### STEP 3 — Fix proot launch args (if needed)
**Status:** ✅ DONE (2026-06-30) — commit 8f0f5ba6
**File:** `ProotInstaller.kt` → `launchArgs()`
**What:** Verify proot is called with `-0` (fake root), correct `--rootfs=`, `-w /root`,
correct `DEBIAN_FRONTEND=noninteractive`
**Rule:** Only change what's broken, build, test


### STEP 3 FINDINGS (2026-06-30 — commit 8f0f5ba6)
Removed from proot args:
- `--sysvipc` — Samsung 5.15 kernel blocks SysV IPC syscalls inside unprivileged namespaces
- `-L` (LDSO interception) — conflicts with our nativeLibraryDir .so layout
- `--bind=/proc/self/cwd:/proc/self/cwd` — self-referential bind causes confusion
Changed:
- `--kernel-release` from `6.17.0-android13-1` → `5.15.0-android13-4` (matches actual Samsung kernel)
- VERSION bumped to `ubuntu-questing-v4.30.1-r2` (forces re-extraction on device)
Build: ✅ green (8f0f5ba6)

#### STEP 4 — Device test Ubuntu
**Status:** ⬜ DEVICE TEST (human)
**Commands:**
```bash
apt update
apt install -y nano vim curl git python3
python3 --version
```

---

### PRIORITY 2: Bash tab (Termux exact parity)

#### STEP 5 — Fix shellArgs() in TermuxBootstrapInstaller.kt
**Status:** ⬜ NOT STARTED
**File:** `TermuxBootstrapInstaller.kt` → `shellArgs()` only
**Exact changes:**
```kotlin
// REMOVE any LD_LIBRARY_PATH line
// argv must be: arrayOf("-bash")
// env must NOT contain LD_LIBRARY_PATH at all
val env = arrayOf(
    "TERM=xterm-256color",
    "COLORTERM=truecolor",
    "HOME=$home",
    "PREFIX=$prefix",
    "PATH=$prefix/bin:$prefix/sbin:/system/bin",
    "TMPDIR=$prefix/tmp",
    "SHELL=$prefix/bin/bash",
    "LANG=en_US.UTF-8",
    "LC_ALL=en_US.UTF-8",
    "TERMUX_VERSION=0.118.1",
    "TERMUX_APP_PACKAGE_MANAGER=apt",
    "DPKG_FORCE=unsafe-io",
    "PERL_BADLANG=0"
    // NO LD_LIBRARY_PATH — causes signal 31
)
return Pair("$prefix/bin/bash", env)
```
**Rule:** Touch ONLY shellArgs() — nothing else in this file

#### STEP 6 — Fix TerminalPane createTerminalSession
**Status:** ⬜ NOT STARTED
**File:** `TerminalPane.kt` → `createTerminalSession()` only
**Change:** Use `arrayOf("-bash")` as the args array
**Rule:** Touch ONLY that one line

#### STEP 7 — Device test Bash tab
**Status:** ⬜ DEVICE TEST (human)
**Commands:**
```bash
echo $PREFIX
ls $PREFIX/bin | head -20
apt update
apt install -y nano git python3
```

---

### PRIORITY 3: Extensions panel (real package market)

#### STEP 8 — Build ExtensionsPane.kt
**Status:** ⬜ NOT STARTED
**File:** New `ExtensionsPane.kt`
**Features:**
- Reads `/var/lib/dpkg/status` from Termux prefix → installed packages list
- Reads `/var/lib/dpkg/status` from Ubuntu proot → Ubuntu installed packages
- Search bar → runs `apt-cache search <term>` via background shell
- One-tap install → sends `apt install -y <pkg>\n` to active terminal tab
- Categories: Dev Tools, Languages, Networking, Utilities, AI/ML, Editors
- Badge on Extensions icon showing installed count
- No fake data — reads real dpkg/status file

---

### PRIORITY 4: Menu cleanup

#### STEP 9 — Terminal menu dropdown cleanup
**Status:** ⬜ NOT STARTED
**File:** `TerminalPane.kt` → menu composable only
**Changes:**
- All scattered buttons go inside the categorized dropdown
- Categories have expand/collapse chevron
- No floating standalone buttons
**Keep:** All existing categories (TERMINALS, AI & TOOLS, DEFAULT MODE, MANAGE)

---

## EXECUTION RULES (PERMANENT — APPLY TO EVERY SESSION)
1. Read the target file fresh from GitHub before every edit
2. Make the SMALLEST possible change — never rewrite whole files
3. Push and wait for GREEN build before next step
4. If build fails: read the error log, fix ONLY the error, push again
5. Never touch files not listed in the current step
6. Update step status (⬜ → ✅) in this MD after every green build
7. No LLM-directed rewrites — only surgical edits based on actual errors
8. The Termux APK 7z is in Google Drive — re-download and study it if needed

---

## STEP STATUS SUMMARY
- [x] Step 1 — Diagnose Ubuntu apt ✅
- [x] Step 2 — Fix Ubuntu sources.list ✅ (already correct)
- [x] Step 3 — Fix proot launch args ✅ 8f0f5ba6
- [ ] Step 4 — Device test Ubuntu (**HUMAN STEP**)
- [ ] Step 5 — Fix Bash shellArgs() — remove LD_LIBRARY_PATH
- [ ] Step 6 — Fix TerminalPane createTerminalSession args
- [ ] Step 7 — Device test Bash (**HUMAN STEP**)
- [ ] Step 8 — Build ExtensionsPane
- [ ] Step 9 — Terminal menu cleanup
