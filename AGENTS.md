# ⚠️ TWO-REPO STRUCTURE — READ BEFORE TOUCHING ANYTHING

## THIS is the MAIN IDE repo: `wisdom131-max/codespace-ide-mobile`

## Ubuntu proot fixes go in the TEST repo: `wisdom131-max/ubuntu-proot-test`

| Repo | Purpose | What goes here |
|------|---------|----------------|
| `wisdom131-max/codespace-ide-mobile` | Full Codespace IDE app | Auth, editor, terminal UI, Bash tab, all non-Ubuntu features |
| `wisdom131-max/ubuntu-proot-test` | Isolated Ubuntu proot test harness | ProotInstaller, proot launch args, Ubuntu rootfs extraction, symlink fixes, proot crashes |

### Rule: If the fix touches proot, Ubuntu rootfs, or symlinkat() — it goes in `ubuntu-proot-test` ONLY.

### DO NOT push Ubuntu/proot fixes to `codespace-ide-mobile`. They will be reverted.

### Once a fix is verified working in `ubuntu-proot-test`, Wisdom will port it back manually.

---

# AI Agent / Copilot — Project Context
> Read this FIRST before touching any code. Updated July 4, 2026.


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
| `CANNOT LINK EXECUTABLE -bash: libandroid-support.so not found` | `LD_LIBRARY_PATH=$nativeDir` in proot envVars — host JNI .so injected into bash | Remove `LD_LIBRARY_PATH` from proot `envVars` entirely (a86517fa) |
| Samsung `symlinkat()` seccomp block | Samsung kernel 5.15 blocks symlinkat() | Copy multi-call binaries instead of symlinking |
| **Ubuntu black screen after "Resolving N deferred symlinks..."** | `initializeEmulator()` never called — `updateSize()` only fires from `onSizeChanged()`, but view is already laid out when Ubuntu session is swapped in | Call `view.updateSize()` immediately after `view.attachSession()` (commit 955b3f3e5ab0) |
| **App crash after "Resolving 1747 deferred symlinks..."** | `--link2symlink` flag tells proot to queue all hardlinks then resolve via `symlinkat()`. Samsung/TECNO kernel blocks `symlinkat()` inside unprivileged namespaces via seccomp → SIGSYS → crash | **Remove `--link2symlink` entirely** — ubuntu-questing tarball uses real symlinks, no conversion needed (commit 70b415a659e1) |

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

#### STEP 4 — Fix Ubuntu black screen (PTY never initialized)
**Status:** ✅ DONE (2026-06-30) — commit 955b3f3e5ab0
**Root cause:** `initializeEmulator()` is only called from `updateSize()`, which is only called
from `onSizeChanged()`. When the Ubuntu session is swapped into an already-laid-out TerminalView,
`onSizeChanged()` never fires again → emulator stays `null` → proot output has nowhere to go → black screen.
**Fix (TerminalPane.kt — 3 surgical changes):**
1. After `view.attachSession()`: immediately call `view.updateSize()` (post to UI thread, or wait for
   `OnGlobalLayoutListener` if view not yet measured). This is literally what the Termux source comment says to do.
2. `addOnLayoutChangeListener`: now calls `updateSize()` + `onScreenUpdated()` (was only `onScreenUpdated()`).
3. Ubuntu session CWD: changed from `"/"` (host root, SELinux blocked) → `filesDir` (always accessible).
**Expected result on device:** Ubuntu tab shows bash prompt after symlink resolution instead of black screen.

#### STEP 4b — Fix proot crash after symlink resolution
**Status:** ✅ DONE (2026-06-30) — commit 70b415a659e1
**Root cause:** App crashed after showing "Resolving 1747 deferred symlinks..." 
`--link2symlink` flag makes proot queue all hardlinks then call `symlinkat()` to resolve them.
Samsung/TECNO Android 14 kernel blocks `symlinkat()` inside unprivileged containers via seccomp → SIGSYS → crash.
ubuntu-questing tarball already uses real symlinks (not hardlinks) in the rootfs, so `--link2symlink` is unnecessary.
**Fix:** Remove `--link2symlink` from proot args in `ProotInstaller.kt`. VERSION bumped to `r4` to force re-extraction.
**Note:** This fix stacks on top of STEP 4 (black screen fix, commit 955b3f3e5ab0). Both needed for Ubuntu to work.

#### STEP 4c — Device test Ubuntu (PENDING)
**Status:** ⬜ DEVICE TEST (human)
**What to expect:** Ubuntu tab opens, proot starts, NO symlink message, bash prompt appears directly.
**Commands to test:**
```bash
apt update
apt install -y nano vim curl git python3
python3 --version
```

---


---

## AUTH SCREEN — REDESIGN (2026-06-30)

### Problem
- Old design: Sign In / Sign Up tabs + separate Google button + email field + separate picker button = too many elements
- Error "Server auth failed (500)" was a DB crash — TypeORM `synchronize: false` in production meant tables never created

### Fixes applied
| Fix | Commit | File |
|-----|--------|------|
| TypeORM `synchronize: true` always — creates tables on Railway startup | fe7e60b2163e | `database.module.ts` |
| Full AuthScreen redesign — single "Sign in or Sign up" button + email field with inline arrow | b3c6e4b994cd | `AuthScreen.kt` |

### New AuthScreen layout
```
Codespace IDE
Your projects. Any device.

[ Sign in or Sign up ]          ← primary button, full width
[ Enter your Google email  → ]  ← outlined field, arrow icon right side (turns blue when typed)

Tap the field to pick an account or type your email
```
- Tap button OR arrow → opens Google account picker
- Type email manually → press arrow or keyboard Go → picker pre-filtered to that email
- No tabs, no dividers, no redundant buttons

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
- [x] Step 3b — Remove LD_LIBRARY_PATH from proot envVars ✅ a86517fa
- [x] Step 4 — Device test Ubuntu ✅ (confirmed working July 4, build #839)
- [ ] Step 5 — Fix Bash shellArgs() — remove LD_LIBRARY_PATH
- [ ] Step 6 — Fix TerminalPane createTerminalSession args
- [ ] Step 7 — Device test Bash (**HUMAN STEP**)
- [ ] Step 8 — Build ExtensionsPane
- [ ] Step 9 — Terminal menu cleanup
- [ ] Step 10 — Remotion video creator (**NEW — IN PROGRESS**)

---

## 2026-07-04 — CRASH FIX CONFIRMED + BACK BUTTON FIX

### Crash fix (builds #836–#839)
**Root cause:** On 3GB RAM devices (TECNO), reopening the app after minimize triggers
Activity recreation. Immediately forking proot during this window causes OOM/SIGKILL —
the proot/ptrace startup adds ~500MB+ virtual memory on top of the recreating Activity.

**Confirmed fix (build #839):** Delay proot fork by ~8 seconds on reopen after minimize.
- First boot after Ubuntu install: auto-starts immediately (no delay, app is stable)
- Reopen after minimize: shows "Starting terminal..." spinner for ~8s, then auto-starts
- Back button during loading: skips delay and starts terminal immediately
- Reopen with live proot session: reattaches immediately (no delay)

**User confirmed:** Build #837 (manual tap-to-start) worked. Build #839 replaced the
manual tap with automatic 8s delay — same fix, better UX.

**Key files changed:** `TerminalPane.kt` — added `showTapToStart`, `autoStartCountdownDone`
state vars, `LaunchedEffect(showTapToStart)` with 8s delay, loading screen UI, BackHandler.

### Back button fix (build #840)
**Problem:** Back button stopped working after the crash fix. When the app starts directly
on the project screen (via `sessionStateStore.lastProjectId()`), `nav.popBackStack()` returns
false because the project screen IS the start destination — there's nothing to pop back to.

**Fix:**
1. `CodeSpaceApp.kt`: `onBack` now navigates to HOME when `popBackStack()` fails
2. `TerminalPane.kt`: BackHandler during loading screen skips delay and starts terminal

### SharedPreferences flag
`terminal_prefs.ubuntu_first_boot_completed` — distinguishes first boot (auto-start) from
reopen after minimize (delayed start). Set to `true` after first successful proot launch.


---

## BACKLOG — HARD

- [x] ~~**Remotion video creator**~~ — **MOVED TO STEP 10 (IN PROGRESS)**

---

## STEP 10 — REMOTION VIDEO CREATOR (2026-07-04 — IN PROGRESS)

### Goal
Render videos clip-by-clip using Remotion (React-based), then merge clips into final video
using FFmpeg. Avoids memory/timeout issues from rendering full video at once. Ideal for
YouTube content, code walkthroughs, and project demos from within the IDE.

### Architecture
- **New panel:** `RemotionPane.kt` — accessible from the VS Code-style activity bar
- **Remotion runtime:** Runs inside the Ubuntu proot environment (Node.js + npx remotion)
- **Clip-based rendering:** Each clip rendered individually, then merged via FFmpeg
- **Templates:** Pre-built templates for code walkthroughs, project demos, tutorials
- **Output:** MP4 files saved to user's project directory, shareable via Android intents

### Implementation plan
1. Create `RemotionPane.kt` — UI for composing video clips, preview, render button
2. Install Node.js + Remotion in Ubuntu proot (via apt + npm)
3. Create clip templates (React components) stored in project workspace
4. Wire render command → terminal session (npx remotion render <clip> <output>)
5. FFmpeg merge step → combine clips into final video
6. Share intent — "Share video" button using Android FileProvider

### Dependencies (Ubuntu proot)
```bash
apt install -y nodejs npm ffmpeg
npm install -g remotion @remotion/cli
```

### Status: PLANNING — next step after back button fix is confirmed

## 2026-06-30 — ProotInstaller r14: Os.symlink + SYMLINKS.txt file-based pattern

**Commit:** 4d8d9580ab9c
**Problem:** Ubuntu symlinks failing with Bad system call.
**Root causes:**
1. Files.createSymbolicLink() calls symlinkat() — blocked by Samsung kernel 5.15 seccomp.
2. File(rootfs, "/usr/bin/python3") silently ignores rootfs when child starts with slash.

**Fix (exact Termux TermuxInstaller.java pattern):**
- Write symlinks to SYMLINKS.txt during tar streaming (format: target←relpath)
- After extraction + double GC: Os.symlink(target, rootfs.absolutePath + "/" + relPath)
- Os.symlink() bypasses blocked symlinkat() via Android JNI layer
- Path construction: always use string concat, never File(parent, "/absolute")

**Validated:** ubuntu-proot-test r14 (886f7e7d5f) builds green.
**Bash tab:** TermuxBootstrapInstaller v2 uses copy-instead-of-symlink for multi-call binaries.


## 2026-07-02 — r5: restored --link2symlink crash fix + ported dpkg/apt fixes from ubuntu-proot-test

### Part 1 — regression fix (found while doing the port below, unrelated to it)

While reviewing `ProotInstaller.kt` before porting the apt fixes, found that the confirmed
Samsung/TECNO `symlinkat()` seccomp crash fix (r4, commit `70b415a`, removed
`--link2symlink` from the proot args) had been **accidentally reverted**. Commit `9de8534`
("revert: ProotInstaller.kt back to a86517fa — fixes belong in ubuntu-proot-test only")
reverted one commit too far — past `70b415a` — while trying to undo an unrelated bad
experiment, wiping out a real, already-verified fix along with it. All later work (the r14
attempt and the final "revert to r3") never restored it, so `--link2symlink` has been back
in the shipped app this whole time. **Fixed:** removed it again in r5, with a comment
documenting exactly how it was lost so this doesn't happen a third time.

### Part 2 — dpkg/apt fixes ported from ubuntu-proot-test

Per user instruction ("Go"), ported the confirmed, on-device-verified apt/dpkg fixes from
the `ubuntu-proot-test` test-bed repo into this app. Full debugging trail for these fixes
lives in `ubuntu-proot-test`'s own AGENTS.md (multiple sessions); summary of what shipped:

**1. `libdpkg_android_fix.so` — LD_PRELOAD shim (new file: `cpp/dpkg_android_fix.c`)**
Freestanding (`-nostdlib -nodefaultlibs`, raw aarch64 syscalls, zero external deps — must
load into Ubuntu's glibc process via LD_PRELOAD without pulling in Android libm/libdl).
Fixes two real EPERM/EACCES failures dpkg hits on Android:
- `link()` → EACCES (dpkg's status/status-old hardlink backup) → redirected to `rename()`.
- `chown()`/`lchown()`/`fchown()` → EPERM in proot without real root → no-op.
Built as a normal Android JNI shared lib, then copied into the guest rootfs at
`usr/lib/libdpkg_android_fix.so` post-extraction, and LD_PRELOAD'd via
`/etc/profile.d/99-dpkg-fix.sh` (guest-side only — setting LD_PRELOAD as a *host* env var
passed to proot itself breaks `libproot.so`).
CMakeLists.txt POST_BUILD step strips a stray `NEEDED libm.so` entry that the NDK's CMake
toolchain auto-injects regardless of `-nostdlib` flags (confirmed via `readelf -d` during
ubuntu-proot-test's own r17/r18 builds) — requires `patchelf`, now installed in
`android-build.yml` CI.

**2. Did NOT port ubuntu-proot-test's dpkg-split / update-alternatives / service stubbing**
This is the important one. ubuntu-proot-test's *own* `install()` code (still, as of this
writing) creates no-op stubs for `usr/bin/dpkg-split` and `usr/bin/update-alternatives`,
and stubs `usr/sbin/service` alongside `ldconfig`/`invoke-rc.d`/etc. On-device debugging
in that repo proved these specific three stubs (dpkg-split, update-alternatives, service)
were the actual root cause of "apt install silently does nothing" — dpkg-split in
particular made every single install a no-op without ever opening the .deb, going all the
way back to that repo's r10. The real fix was simply to stop overwriting them: the
Questing rootfs tarball already ships correct real binaries for all three before our own
code stomps on them. **This app never had that stubbing bug** (confirmed — grepped this
file before making any changes, no `dpkg-split`/`update-alternatives`/`service` stub logic
existed here), so there was nothing to remove; this note exists so nobody "helpfully" adds
that stubbing pattern here later by copying it from ubuntu-proot-test without checking its
own AGENTS.md first.
Left `ldconfig`/`ldconfig.real`/`update-initramfs`/`systemd-tmpfiles`/`invoke-rc.d`/
`policy-rc.d` untouched too — this app doesn't stub them either, and they weren't part of
the confirmed dpkg-provided-binary audit (that only covered `dpkg-divert`,
`dpkg-statoverride`, `dpkg-trigger`, `dpkg-query`, `dpkg-deb`, `dpkg-split`,
`update-alternatives`, `service`). If a future session finds this app *does* stub any of
those, treat it exactly like dpkg-split/update-alternatives/service: audit before trusting
the stub, since "no-op it, never verified" was the actual root cause of this entire saga.

**3. Five shadow-utils wrapper scripts** (`useradd`, `usermod`, `groupadd`, `userdel`,
`groupdel`) — installed to `usr/sbin/*` post-extraction, original binary preserved as
`*.real`. Root cause: these tools lock `/etc/passwd`/`/etc/group` via `link()` to a
`.lock` file then explicitly check the resulting **nlink count** to confirm it's a real
hardlink. The `dpkg_android_fix.so` `link()→rename()` shim makes the call succeed but
produces nlink=1, not the nlink=2 a genuine hardlink has — so shadow-utils correctly
detects it's not a real lock and refuses. Not fixable at the syscall-shim level; these
wrappers try the real binary first and only fall back to direct file edits on a genuine
lock failure.
**Important caveat:** the actual wrapper scripts were never committed to `ubuntu-proot-test`'s
own repo — they were applied live, interactively, on the user's device only, and are
documented in prose (with example bugs found/fixed) in that repo's status reports. The
versions shipped here were **reconstructed from those prose descriptions**, not copied
from verified source. Shell-syntax-checked (`sh -n`) clean on all 5, but **not yet
independently confirmed on-device in this repo**. Two known bugs from the original
ubuntu-proot-test iteration were deliberately avoided while writing these: (a) `useradd`'s
group resolution must convert a group *name* to its numeric GID before writing
`/etc/passwd` — writing the name directly corrupts the line for glibc's NSS parser; (b)
never use `args="$@"; set -- $args` — that POSIX word-splits and silently truncates any
multi-word argument like `-c "test comment"`. All args here are parsed directly from
`"$@"`/`"$1"`, never re-split through an unquoted variable.

### Status (Updated July 3, 2026 — Build #828)

### CRASH LOOP FIX (builds #823→#826)
**Root cause identified via bugreport analysis:**
- App was acquiring an app-level PARTIAL_WAKE_LOCK + starting foreground service from
  `Application.onCreate()` on EVERY process restart
- TECNO HiOS power management (`Hiber/proxyWakeLock`) kills apps that aggressively
  acquire WakeLocks + FGS immediately on startup — 16 SIGKILLs in 90 seconds
- The app-level WakeLock (held for entire app lifetime) was the unusual thing Termux doesn't do
- **Fix:** Removed `acquireAppWakeLock()` and `startTerminalServiceEarly()` from
  `Application.onCreate()`. Now matches Termux pattern: service starts from
  `TerminalPane`'s `DisposableEffect` (after UI renders), not from Application
- **Status:** Builds #826-#828 green, awaiting on-device test

### MANUAL APP WAKELOCK TOGGLE (builds #827-#828)
- App WakeLock is now user-controlled, not auto-acquired
- Gear menu shows "App WakeLock: ON/OFF" — tap to toggle
- `acquireAppWakeLock()` / `releaseAppWakeLock()` methods in CodeSpaceApplication
  are public, called from UI via `(context.applicationContext as CodeSpaceApplication)`
- `isAppWakeLockHeld` property exposed for UI state
- Default: OFF (no crash loop on startup)
- Matches Termux pattern: user explicitly enables when needed for long-running tasks

### COMMAND PALETTE (build #828)
- Reduced size: 75% width (max 380dp), max 240dp height, 13sp font, tighter padding
- Previous: 90% width (max 560dp), max 320dp height, 14sp font
- Scrollable via LazyColumn, text input focus fixed

### PREVIOUS FIXES (builds #809-#822, July 3)
- ✅ Settings gear icon menu restored (popup menu + state wiring)
- ✅ App crash-on-resume fixed (removed blocking network calls from lifecycle methods)
- ✅ Command palette scrollable via LazyColumn + text input focus fixed
- ✅ Back button uses ArrowBack icon with 44dp touch target
- ✅ Onboarding walkthrough permanently removed
- ✅ CrashLog entity + reportCrash backend function deployed
- ✅ JNI null-guard for cmd/cwd in createSubprocess (defensive, from June tombstones)
- ✅ Session leak guard: kill stale sessions before reattaching
- ✅ Catch Throwable not Exception for OOM handling

### CURRENT ISSUE — CRASH ON MINIMIZE WITH UBUNTU (July 3, build #830)
**User-confirmed diagnosis:**
- App works fine minimize/reopen when Ubuntu is NOT downloaded ✅
- App crashes (SIGKILL) on minimize/reopen AFTER Ubuntu is downloaded ❌
- WakeLock setting (on/off) does not affect the outcome — it's NOT a WakeLock issue

**Root cause:** Memory pressure from proot process
- Ubuntu proot process consumes significant memory on 3GB device
- On minimize: app process + proot process = too much memory → TECNO SIGKILL
- On reopen: tries to reattach to old proot + create new placeholder → OOM → crash loop
- The reattach logic in TerminalPane (lines 692-738) tries to reuse old sessions, but
  the memory pressure kills the app before it can complete

**Fix plan (next build):**
1. Add `killAllSessions()` to TerminalService — finishes all live proot/bash sessions
2. Call it from TerminalPane's lifecycle observer on `ON_STOP` (activity going to background)
3. This kills proot processes immediately when minimized, freeing ~500MB+ of memory
4. On reopen: `getLiveUbuntuSessions()` returns empty → `addUbuntuTab()` starts fresh
5. Tradeoff: terminal sessions don't persist across minimize (acceptable on 3GB — stability > persistence)
6. Also stop the foreground service on minimize (no sessions = no need for FGS)
7. Service restarts from DisposableEffect on reopen

### KNOWN ISSUES
- Terminal bash tab still falls back to busybox ash (not native bash) — pending fix
- Ubuntu proot: dpkg/apt install still blocked by Samsung kernel chdir restriction
- Terminal redraw on keystrokes may still have issues
- Terminal session persistence across minimize: INTENTIONALLY NOT IMPLEMENTED on 3GB devices
  (killing sessions on minimize is the stability fix — see CURRENT ISSUE above)

### DEVICE SPECS (from bugreport July 3)
- TECNO KL4, Android 14, kernel 5.15.180-android13, arm64-v8a, 3GB RAM
- MemTotal: 2,855,472 kB, MemFree: ~46MB, MemAvailable: ~1GB
- SwapTotal: 2,147,160 kB, SwapFree: ~942MB
- Committed_AS: ~98GB (massive overcommit from proot virtual mappings)

### CRASH LOGGING
- CrashLog entity + reportCrash backend function deployed
- App writes crash to local file + uploads to backend on next successful launch
- App needs to stay open ~2-3 seconds on next launch for upload to complete
- Agent can read crash logs via `read_entities("CrashLog")`

## r6 — Closed the gap with ubuntu-proot-test's full fix set (2026-07-02)

The user hit the exact `dpkg-preconfigure` crash live:
```
sh: 0: getcwd() failed: Function not implemented
cannot fetch initial working directory: Function not implemented at /usr/sbin/dpkg-preconfigure line 82.
Error: Sub-process /usr/bin/dpkg returned an error code (100)
```
This confirmed r5 was missing several fixes already found and verified in
`ubuntu-proot-test`. r6 ports the rest of that fix set over, verified against the same
sources used there (real Ubuntu questing package archive, not guessed):

1. **`dpkg-preconfigure` no-op'd** — the exact fix for the crash above. It calls `getcwd()`
   via Perl at two points during every install; binding `/proc/self/cwd` (already in
   `launchArgs`) only covers the top-level shell's cwd, not subprocess forks with a
   different real host cwd context, which is what dpkg-preconfigure's Perl runtime hits.
   Skipping the whole Debconf pre-configuration stage is safe for headless installs.
2. **`/var/lib/dpkg` + apt state dirs made world-writable** — the rootfs is extracted by
   an Android process; proot's guest root otherwise can't write `status-old` etc.
3. **`dpkg.cfg` / `01dpkg-options` expanded** — added `force-confnew`, `force-overwrite`,
   `no-debsig`, `no-triggers`, `DPkg::Lock::Timeout "0"` (flock() may itself be blocked),
   `DPkg::NoDebsig`. r5 only had `force-unsafe-io`.
4. **`ldconfig`/`ldconfig.real`/`systemd-tmpfiles`/`invoke-rc.d` no-op'd, `policy-rc.d`
   returns 101** — these are dpkg post-install triggers that crash on blocked syscalls
   (unshare/mount/pivot_root) with no error text, previously left completely unhandled.
5. **`sources.list` now uses `[trusted=yes]`** — apt was already configured to allow
   unauthenticated repos via `apt.conf.d`, but `[trusted=yes]` per-line skips invoking
   `gpgv` at all, avoiding its "exited unexpectedly" crash outright rather than just
   tolerating the resulting warning.
6. **Real-binary self-heal**: `dpkg-split`/`update-alternatives`/`service` backed up to
   `root/persistent-fixes/*.real` right after extraction (they were already correctly left
   un-stubbed in r5 — this just adds automatic recovery if anything ever reverts one).
7. **Shadow-utils wrapper self-heal**: `installWrapper()` now also backs up each wrapper's
   own script text to `root/persistent-fixes/NAME.wrapper`, and `99-dpkg-fix.sh` restores
   any wrapper found reverted back to a raw binary.
8. **`ssh` init-script fix** — pulled the real `openssh-server_10.0p1-5ubuntu5` init
   script from the Ubuntu questing archive and patched it directly (not guessed): fixes a
   blank `Default-Stop` LSB header (silently skips shutdown symlinks) and a missing
   post-start liveness check (`start-stop-daemon`'s exit code only reflects a successful
   fork, so a later `bind()` failure was reported as a false `[ OK ]`). Shipped as
   `assets/rootfs-fixes/ssh-initd.patched`, applied by `99-dpkg-fix.sh` once
   openssh-server is apt-installed (not present in the base rootfs).
9. **Checked `apache2`'s real init script too** (`2.4.64-1ubuntu3`) — it already has a
   working liveness poll in `do_start()`. No fix needed/applied; left untouched.
10. **`99-dpkg-fix.sh` rewritten** as a full self-heal script (was previously just the
    LD_PRELOAD export) — covers all of the above restore checks, runs on every shell.

### Status
Version bumped to r6 (forces fresh rootfs extraction). Not yet device-tested at this
commit — every individual fix here was verified either against the real shipped Ubuntu
package or in the `ubuntu-proot-test` live sessions, but this is the first time they're
combined in this app's own CI build. Needs a clean install + `apt install nano`/`gcc`
cycle to confirm the dpkg-preconfigure crash is actually gone.

## r7 — Audited against the full debug-status doc; closed the last gap (2026-07-02)

Cross-checked every fix in the uploaded `dpkg-terminal-fix-status-3.md` debug report
(3 live debug sessions) against what's actually shipped in this app. Downloaded the real
Ubuntu questing `.deb`s (`apache2_2.4.64-1ubuntu3.5_arm64`, `openssh-server_10.0p1-5ubuntu5_arm64`)
to verify against ground truth rather than trusting descriptions alone.

**Confirmed already correctly implemented (no changes needed):**
- `useradd` wrapper already resolves group *names* to numeric GIDs before writing
  `/etc/passwd` (the exact critical bug the doc calls out — already avoided).
- No wrapper uses the `args="$@"; set -- $args` anti-pattern (word-splits and truncates
  multi-word args) — all wrappers parse `"$@"` directly.
- `apache2`'s real init script (verified against the actual downloaded package) already
  has a proper `apache_wait_start()` liveness poll (`kill -0` for up to 20s) built in —
  the doc's apache2 "false positive" bug does not apply to this package version. Confirms
  the r6 decision to leave apache2 untouched was correct.
- `ssh`'s real init script (verified against the actual downloaded package): confirmed
  blank `Default-Stop:` and no liveness check in the stock version — both bugs are real,
  and `ssh-initd.patched` already fixes both correctly (diffed byte-for-byte against the
  real package to confirm).

**One gap found and closed:** the doc's "Bug #9" self-heal check was a dedicated,
independent check for an empty `Default-Stop` LSB field — separate from the Bug #6
liveness-check marker check, because a future edit could plausibly restore the liveness
fix but not the LSB header fix (or vice versa) if only one marker is checked. r6 only had
the whole-file restore keyed off the liveness-check marker. r7 adds the standalone check
as defense-in-depth, matching the doc's approach exactly:
```sh
if [ -f /etc/init.d/ssh ] && grep -q "^# Default-Stop:[[:space:]]*$" /etc/init.d/ssh; then
    sed -i "s/^# Default-Stop:.*$/# Default-Stop:\t\t0 1 6/" /etc/init.d/ssh
fi
```

### Status
Version bumped to r7. Verified the generated `99-dpkg-fix.sh` (including the new Bug #9
check with its `\t\t` GNU-sed tab escapes) passes `sh -n`. Not yet device-tested.

## Signal 11 (SIGSEGV) crash reported in Ubuntu terminal — investigation started (2026-07-02)

User reported this exact text in the Ubuntu tab:

```
[Process completed (signal 11) - press Enter]
```

This string comes directly from `TerminalSession.java`'s `MainThreadHandler`
(`exitDescription += " (signal " + (-exitCode) + ")"` when `exitCode < 0`), so this is
confirmed to be a real native SIGSEGV in the child process tree (proot or something it
exec'd — almost certainly `bash`), not a normal shell exit.

**Root cause not yet isolated.** No specific reproduction command was captured. Rather
than guessing at the fix blind, spun up a dedicated isolation harness:

- New repo: **`ubuntu-proot-bash-test`** — a minimal single-activity app (cloned from
  `ubuntu-proot-test`'s proven-working state) that launches straight into `bash --login`
  under proot with none of this app's extra layers (TerminalService binding, cgroup
  migration via `setProcessGroup`, multi-tab session lifecycle, split-panel session
  sharing). See that repo's `AGENTS.md` for the full stress-test checklist and port-back
  criteria.
- Plan: run the stress checklist there first. If it survives, the bug is architectural —
  look at `setTerminalShellPid`'s cgroup migration and the `addUbuntuTab` session-swap
  race first. If it also crashes there, the bug is lower-level (proot / pty JNI / kernel
  seccomp interaction) and needs proot verbose tracing (`-v 9`) to pin down.
- **Nothing has been ported back yet** — this section will be updated once the isolation
  test has a result.

## Terminal environment simplified to Ubuntu-only (2026-07-02)

Per explicit request, removed the entire secondary "bash" terminal system so Ubuntu proot
is the only environment the app ships:

- Deleted the dual-shell fallback in `createTerminalSession()` (busybox/ash +
  Termux-bootstrap/bash). The non-Ubuntu branch is now only used internally as an inert
  placeholder session (`/system/bin/sh`, no bundled binary) to display install-progress
  text before the real Ubuntu proot session takes over — never exposed to the user.
- `TerminalService.createSession()`'s non-Ubuntu branch removed the same way.
- The very first tab on app launch now boots straight into Ubuntu automatically (no more
  defaulting to an ash/bash/ollama tab based on `TerminalModeManager`/`DeviceCompatibility`
  mode guessing).
- `addTab()` ("+" button, hardware-keyboard new-tab shortcut) and all "new terminal" menu
  entries now always open another independent Ubuntu proot session tab — instant if
  already installed, or the full install-with-progress flow if not.
- Removed dead UI: "New Bash Terminal", "Setup Offline Tools", and the "DEFAULT MODE"
  Offline/Bash vs Ubuntu toggle menu section.
- Still pending cleanup: delete the now-unused `TermuxBootstrapInstaller.kt`,
  `BusyboxInstaller.kt`, `TerminalModeManager.kt` files and their remaining references in
  `MainActivity.kt` / `ProjectShellScreen.kt` / `TerminalService.kt`, and reroute the SSH
  Manager feature (currently spawns a non-Ubuntu placeholder shell) to run inside Ubuntu.

## Signal 11 root-caused + crash-reporting pipeline added (2026-07-03)

**Signal 11 (SIGSEGV) — CONFIRMED FIXED.** Root cause found by diffing this app against
the isolated `ubuntu-proot-bash-test` repo (proven stable on the same TECNO KL4 device):

1. This app set `PROOT_NO_SECCOMP=1` in proot's env vars (added way back in `e30db19` for
   an unrelated execve bug that looked like a seccomp issue at the time). The test app had
   already removed this exact flag in an earlier fix round (r24) after confirming Termux's
   own proot-distro never sets it — forcing it routes every syscall through a far-less-tested
   ptrace fallback path in proot. That fix never got ported back here. **Removed.**
2. This app's `libproot.so`/`libtalloc.so` were a different build (375400 bytes) than the
   test app's proven-stable ones (239456 bytes) — leftover from this app's own custom
   static-proot CI experiments. **Replaced with the exact binaries from the test app.**

User confirmed: `apt update` and `apt install nano` now both work cleanly. Consider this
fixed unless it resurfaces.

### New bug found immediately after: app closes instantly when reopened after minimizing

This is a real Android-level crash (not a terminal child-process crash) — happens on
*reopen*, not on minimize itself. Likely process-death-under-memory-pressure (3GB RAM +
proot + Ubuntu rootfs is heavy) followed by a crash somewhere in the cold-start path, but
**not yet confirmed** — we have no ADB/logcat access to this physical device, so the exact
exception was invisible to us until now.

Two things shipped to fix this blind spot for good, plus two defensive hardenings while we
wait for a real trace:

1. **Crash logger + network streaming (the actual fix for "how do I give you the log").**
   `CodeSpaceApplication.installCrashLogger()` installs a
   `Thread.setDefaultUncaughtExceptionHandler` as the very first thing in `onCreate()`.
   On any uncaught exception it:
   - Writes the full stack trace to `filesDir/crash_logs/` (local fallback, read by
     `MainActivity` on next successful launch and shown in a copy-to-clipboard dialog —
     works with zero connectivity).
   - **POSTs it directly to this Superagent's `reportCrash` backend function**
     (`https://superagent-7c842a7e.base44.app/functions/reportCrash`), which stores it in
     a `CrashLog` entity. The agent reads this back with `read_entities` the instant the
     user says "it crashed again" — **no file transfer, no ADB, no app-has-to-open-again
     requirement.** This is the important one: it fires from inside the crash handler
     itself, before the process dies, so it works even in a crash-loop where the app never
     gets far enough to show any UI.
   - Both are best-effort / fully guarded — the crash logger can never itself cause or
     mask the real crash; it always chains to the previous handler afterward.
2. Hardened `TerminalService.onStartCommand()`'s `startForeground()` call with try/catch —
   it was the one unguarded call in the whole cold-start path, and a transient AMS race
   right after the OS kills+relaunches the process is plausible here.
3. Removed the dead `BusyboxInstaller.installIfNeeded()` call from `MainActivity` (unused
   since the Ubuntu-only refactor above — one less thing running on every cold start).

### Status
Both fixes pushed and built green. Waiting on user to reproduce the reopen-crash — next
occurrence will land in the `CrashLog` entity automatically and give us the real
stack trace instead of guesswork.

## proot execve/chmod/chdir "Function not implemented" — root cause found and fixed (2026-07-03)

User hit this reopening the app and launching a new Ubuntu tab:
```
proot error: execve("/usr/bin/env"): Function not implemented
proot error: can't chmod '.../cache/proot-tmp/proot-20505-hfLnxp': Function not implemented
proot error: can't chdir to '/root': Function not implemented
```

**Root cause:** `--bind=/proc/self/cwd:/proc/self/cwd` — the established fix for Samsung/
TECNO kernel 5.15 blocking `SYS_getcwd` via seccomp — was silently dropped from
`ProotInstaller.launchArgs()` in commit `8f0f5ba` (2026-06-30, ironically titled "Samsung
fix") during an unrelated cleanup of `--sysvipc`/`-L`/`--kernel-release`. A stale comment
in the file (`"binding /proc/self/cwd (see launchArgs below) helps..."`) kept pointing at
code that no longer existed — that's what gave it away on re-audit. `-w /root` alone sets
proot's *own* notion of cwd but doesn't fix the underlying blocked syscall that proot's own
bookkeeping (and dpkg/debconf/perl inside the guest) still hits directly.

**Fix:** restored the bind mount in `launchArgs()`. Pushed, built green
(`e31a83c`).

## Native crash handler added — closes the gap where device crashes produced zero logs (2026-07-03)

After the fix above, user reported the app "worked fine but still crashed" tapping open a
new Ubuntu tab. Checked the `CrashLog` backend entity (the network-streaming crash logger
added the day before) — **empty**. That's diagnostic: a `Thread.UncaughtExceptionHandler`
(JVM-level) NEVER sees native signal crashes (SIGSEGV/SIGABRT/SIGBUS/SIGILL/SIGFPE) — those
go straight to the kernel/debuggerd and bypass Java entirely. This is exactly the same
crash *class* as the original "signal 11" bug, just resurfacing in a new spot.

**Fix, two parts:**
1. `pty_native.c`: installs a real `sigaction` handler for SIGSEGV/SIGABRT/SIGBUS/SIGILL/
   SIGFPE via a new JNI method `JNI.installCrashHandler(path)`. On crash it writes signal
   number, code, pid, tid, and fault address to a fixed file using ONLY async-signal-safe
   calls (`open`/`write`/`close` — no malloc, no snprintf/locking), then restores the
   default handler and re-raises so Android's normal tombstone/debuggerd path is completely
   unaffected. Called from `CodeSpaceApplication.installNativeCrashHandler()` right after
   `installCrashLogger()` in `onCreate()`.
2. Can't safely POST from inside a signal handler, so `MainActivity.readLastCrashLog()` now
   reads **every** file in `crash_logs/` (both the JVM logger's timestamped files and the
   native handler's fixed `native_crash_pending.txt`), combines them, uploads to the same
   `reportCrash` backend function on a background thread, and still shows the local
   copy-paste dialog as a fallback for zero-connectivity cases.

Hit one build break during this (unescaped `\n` in a `joinToString` separator, first
attempt used literal newlines instead of the escape sequence) — fixed same session,
build green (`ea56ad9`).

### Status
Both the proot bind-mount fix and native crash handler are live in the current build.
Next crash of ANY kind (Kotlin exception or native signal) should land in `CrashLog`
automatically — no ADB, no reopening-the-app-to-see-a-dialog requirement anymore.

## Real root cause of "app refuses to open after minimizing" found (2026-07-03)

Finally traced this one instead of guessing. `TerminalService.onTaskRemoved()` was
calling `actionReleaseWakeLock()` + `stopSelf()` unconditionally — copied from a naive
reading of the Termux pattern. Problem: TECNO HiOS (and other aggressive Chinese OEM
skins) auto-clears backgrounded apps from Recents on a **plain home-press/minimize**,
not just an explicit swipe-to-close from the task switcher. That means ordinary
minimizing was silently firing `onTaskRemoved`, which killed the foreground service and
released the WakeLock every time — tearing down every live terminal session behind the
user's back. Reopening the app afterward was a full cold start trying to rebind to
sessions that no longer existed, which is what looked like the app "refusing to open."

**Fix:** `onTaskRemoved()` is now a no-op (beyond the mandatory `super` call). The
service only tears down on a genuine `onDestroy()` — explicit force-stop, the system
actually killing the service under real memory pressure, or the user manually stopping
it via the notification action. Pushed as `9ffb9b1`.

### Note on "redownloading rootfs again"
`ProotInstaller.isInstalled()` correctly gates the ~250MB download/extract behind a
version-file + binary-existence check, and `VERSION` hasn't changed in any of today's
commits — so this isn't a regression from the fixes above. A redownload only happens if
`filesDir` was actually wiped (full app data clear, or an uninstall/reinstall cycle) —
expected, one-time behavior after that, not a bug to chase further unless it happens
again on an app that was NOT reinstalled/cleared.

### Status
Three separate bugs now addressed in the same debugging arc: (1) missing proot
`/proc/self/cwd` bind mount, (2) no native-signal crash visibility, (3) service/WakeLock
torn down on OEM-triggered onTaskRemoved. Waiting on user to confirm the minimize/reopen
cycle is stable on the latest build.

## Fixed: rotation/multitasking "interrupts" the Ubuntu download (2026-07-03)

`ProotInstaller.install()` had zero protection against concurrent invocation. Two real
ways to trigger a second overlapping call while the first-run ~250MB download/extract
was still in progress:
1. Tapping "+" for another Ubuntu tab mid-install — `addUbuntuTab()`'s fast path only
   skips to a plain new session if `isInstalled()` is already true, which it isn't
   mid-download, so it falls through and calls `install()` again.
2. Any Compose/Activity state recreation re-firing the bootstrap `LaunchedEffect`
   (defensive — `configChanges` already covers plain rotation at the manifest level,
   but this closes the gap regardless of the actual trigger).

Both would open a second `HttpURLConnection` to the exact same `cacheDir/ubuntu.tar.xz`
and interleave writes into it — corrupting the download and producing exactly the
reported symptom: progress visibly jumping backward / resetting mid-download (it still
eventually finished because of the range-resume retry logic quietly papering over the
corruption on a later attempt).

**Fix:** added `installJob`/`installLock` guard in `ProotInstaller`. Only one thread
ever actually downloads/extracts at a time; any concurrent caller just waits on the
lock and re-checks `isInstalled()` once the first finishes, instead of racing on the
same file. Pushed as `cc2eaa8`.

### Status
Four bugs now fixed in this arc: (1) proot `/proc/self/cwd` bind mount, (2) native
crash handler for signal-level crash visibility, (3) `onTaskRemoved` no longer tears
down the service/WakeLock on OEM-triggered minimize, (4) concurrent-install guard for
seamless downloads across rotation/multitasking. All green on CI. Waiting on user
confirmation across a full test pass.

## Fixed: crash logs were being destroyed before upload could finish (2026-07-03)

Direct response to the repeatable "shows Setting up terminal for a split second then
closes" report on reopen after minimize. Despite the app clearly crashing on-device
every cycle, CrashLog stayed completely empty. Root cause: `readLastCrashLog()` in
MainActivity deleted the local `crash_logs/` files **immediately**, then fired the
upload in a fire-and-forget daemon `Thread` in the background. Since the app crashes
again quickly on the very next launch (same bug, every time), that daemon thread's
network POST never got the ~1-3s it needed to complete before the process died again
— so the on-disk evidence was destroyed before we ever saw it, every single cycle.

**Fix:** upload is now synchronous/blocking (still early in `onCreate()`, before any
Compose renders), and local files are only deleted after a confirmed HTTP 2xx
response. If upload fails, files stay on disk so the next launch gets another shot.
Pushed as `9ceb579`.

**Important:** this fixes visibility, not the crash itself. If the crash is a native
signal, this should finally surface it in `CrashLog` on the next reopen attempt. If
CrashLog is STILL empty after this, that points to an uncatchable OS-level kill
(ANR force-kill or OOM/phantom-process-kill sending SIGKILL) rather than a signal or
JVM exception — a different class of bug requiring a different diagnostic approach
(main-thread blocking-work audit, not crash-handler instrumentation).

### Status
Five fixes now shipped in this arc. Waiting on user to reopen the app after a
minimize cycle — if CrashLog gets an entry this time, we finally have a real stack
trace to work from instead of guessing.


---

## 2026-07-03 (cont'd) — REAL REASON crash logs were never seen: wrong agent endpoint

User reported the previous debugging session's own conclusion ("crash log upload race
fix") didn't actually solve anything — the crash-on-resume-after-minimize was still
happening, and no crash ever surfaced to be analyzed.

### Root cause
`reportCrash` in both `CodeSpaceApplication.kt` (JVM uncaught-exception path) AND
`MainActivity.kt` (native-crash recovery path) was POSTing to
`https://superagent-7c842a7e.base44.app/functions/reportCrash` — **a different Superagent
agent instance entirely**, not the one now debugging this app. That endpoint is real and
does respond (confirmed: returns HTTP 400 without a stack_trace, 200 with one) — it just
belongs to a different agent/app that this session has no read access to. So every crash
report from every test cycle across the last several fixes (bind-mount, native handler,
onTaskRemoved, concurrent-install guard, sync-upload-before-delete) really was being
uploaded successfully — just into a CrashLog entity on a completely different app that
nobody was reading. That's the real explanation for "the app closed too fast to get a
log" — the log was never missing, it was just going to the wrong place the whole time.

### Fix
- Created a `CrashLog` entity + deployed a `reportCrash` function on THIS Superagent
  (`superagent-4bfc55af.base44.app`), which this session can read directly via
  `read_entities` / `aggregate_entities` at any time going forward.
- Updated both hardcoded URLs (`CodeSpaceApplication.kt` and `MainActivity.kt`) to point
  here instead. Swept the whole repo for any other reference to the old endpoint —
  confirmed clean (only this historical AGENTS.md log still mentions it, intentionally).
- Verified the new endpoint end-to-end with a live test POST before wiring the app to it.

### Standing lesson
**Never trust "the crash log is empty" as evidence a crash isn't happening or that a fix
worked** without first confirming the reporting endpoint the app is compiled against
actually matches the agent/app currently being used to read it back. Cross-agent/cross-app
endpoint drift is invisible in code review unless you actually curl the URL and check
which app owns it.

### Status
All 5 previous fixes from earlier today (proot cwd-bind, native crash handler,
onTaskRemoved no-op, concurrent-install guard, sync-upload-before-delete) are still
believed correct in principle and remain in place — none of them were reverted. The gap
was purely in visibility. Waiting on user to reproduce the minimize/reopen crash again;
this time, if it happens, `CrashLog` on THIS agent should actually receive it, and I can
read it directly with no round-trip needed.


---

## 2026-07-03 (cont'd) — Multi-session study from real Termux APK + session-stacking leak fix + install-guard spam fix

### Forensic study: how real Termux handles multiple sessions
Decompiled the reference `termux-app_v0.118.3+github-debug_arm64-v8a` APK from the
user's Google Drive (`Termux/` folder) with jadx to see the actual, battle-tested
pattern for session lifecycle across Activity recreation:

- `TermuxService` holds `final List<TermuxSession> mTermuxSessions` — the Service is
  the SINGLE SOURCE OF TRUTH for what sessions exist, never the Activity.
- `createTermuxSession()` always goes through the Service and appends to that list.
- `setTermuxTerminalSessionClient(client)` — called when the Activity (re)binds — loops
  over **every** session in `mTermuxSessions` and calls
  `session.getTerminalSession().updateTerminalSessionClient(client)` on each one. Never
  just the first/most-recent session.
- `unsetTermuxTerminalSessionClient()` — called on unbind — swaps every session back to
  a no-op `mTermuxTerminalSessionClientBase` so callbacks don't NPE with no UI attached,
  but the underlying process keeps running untouched.
- The Activity's job on launch/reconnect is to ask the Service for its existing list
  and rebuild the tab UI from THAT — never to blindly create new sessions.

### Bug found + fixed: session-stacking leak on Activity recreation
Our `TerminalService.createSession()` had **zero** tracking of what it created, and
nothing but a manual "close tab" tap ever called `finishIfRunning()`. Since Compose
`remember` state resets completely whenever the Activity is destroyed/recreated (which
TECNO HiOS does on a plain minimize while the Service/process survive), every reopen
spawned ANOTHER real Ubuntu proot+bash+rootfs-mount session on top of whatever was
already running, silently orphaned. Stacking multiple live proot session trees is a
very plausible OOM trigger on a 3GB device, firing exactly at the moment of reopening —
matching the reported "opens then instantly closes" symptom.

Fix (mirrors the real Termux pattern above):
- `TerminalService` now tracks `liveSessions` and exposes `getLiveUbuntuSessions()`
  (all still-running sessions, pruned of finished ones) and cleans them up for real in
  `onDestroy()`.
- `TerminalPane`'s bootstrap effect now waits for the service bind, asks for
  `getLiveUbuntuSessions()`, and if any exist, rebuilds the ENTIRE tab list from them
  (via `TerminalSession.updateTerminalSessionClient()` — same real Termux API) instead
  of ever creating a duplicate. Only falls through to the install/fork path if nothing
  is already running.

### Bug found + fixed: install-guard spam ("fills the screen", "progress bar doesn't show")
A different session earlier the same day added a concurrent-install guard in
`ProotInstaller.install()` (commit cc2eaa8f) to stop two threads racing on the same
download file. Correct in principle, but its "waiting" loop called
`onProgress("Another setup is already in progress, waiting for it to finish...")` on
**every single 1-second iteration**, and `addUbuntuTab()` always created a **new**,
separate placeholder tab for every trigger — so tapping "+" (or any other duplicate
bootstrap trigger) mid-install produced a tab that only ever repeated that one line
forever, while the REAL "Downloading... X%" progress was quietly running in a
different, original tab the user wasn't necessarily looking at. That's the "fills the
screen" / "the download progress bar doesn't show" complaint.

Fix:
- The wait loop now announces once, not every second.
- `ProotInstaller.installingTabId` tracks which tab owns the real install.
- `addUbuntuTab()` checks `ProotInstaller.isInstallRunning()` first and, if set, just
  switches `activeId` to the existing installing tab instead of spawning a duplicate —
  so the user always lands on the tab with real progress, never a spam clone.

### Confirmed for the user: no, this does NOT redownload Ubuntu per tab
`addUbuntuTab()`'s fast path checks `ProotInstaller.isInstalled(ctx)` before ever
touching the network — once installed, every subsequent tab (new or reattached) just
forks a new proot session against the already-extracted rootfs. Download only ever
happens once per device install.

### Status
All fixes pushed to `codespace-ide-mobile` (confirmed to be the MAIN app, not a test
repo — `ubuntu-proot-test` and `ubuntu-proot-bash-test` are the isolated test repos).
Build green. Waiting on user to test: (1) rotate during an active Ubuntu download —
should no longer interrupt/spam, (2) minimize and reopen — should reattach to the
live session instead of stacking a new one, (3) tap "+" mid-install — should jump to
the real progress tab instead of cloning a spam tab.


---

## 2026-07-03 (cont'd 2) — Termux install-UX study (decompiled TermuxInstaller.java) + ghost-tap-on-rotate fix + dialog revert

### Forensic study: real Termux's actual bootstrap-install UI
Decompiled `TermuxInstaller.java` from the reference APK (jadx) specifically to check
install-time UI behavior on rotation:
- Bootstrap install uses a plain `ProgressDialog.show(activity, null, message, true, false)`
  — a small, centered, indeterminate spinner dialog. Not full-screen.
- `TermuxActivity` manifest `configChanges` (decoded the binary AndroidManifest.xml via
  jadx on a repackaged APK): `density|smallestScreenSize|screenSize|uiMode|screenLayout|
  orientation|navigation|keyboardHidden|keyboard` — functionally identical to our own
  manifest. Confirms rotation should never destroy either app's Activity.
- Tried mirroring the ProgressDialog with a Compose `Dialog` — user feedback: it dimmed/
  covered the whole screen and, because it only shows the latest message (replacing, not
  appending), it hid the real "% downloaded" progress that used to be visible as scrolling
  terminal text. **Reverted.** What the user actually wants (and what this app already did
  before this session started poking at it) is Termux's OTHER, more visible pattern:
  plain, append-only scrolling status text — general status lines AND the numeric
  "Downloading... X%" lines both written straight to the terminal display, never
  overwriting each other, so both stay visible together (important so the user can see
  exactly how far a download got if mobile data drops mid-transfer). Restored that.
- Binary pre-flight diagnostics (nativeLibraryDir/proot/loader/talloc/shmem/rootfs/bash
  existence checks) moved to `Log.d()` only — no longer written to the terminal. This
  was pure adb-debugging output, not something Termux itself shows, and was contributing
  needless clutter without helping the user.

### Bug found + fixed: ghost-tap on the tab-strip "+" icon during rotation
Root cause of "rotating added a new tab I never tapped": the tab-strip "+" `IconButton`
sits exactly where a finger resting on the glass during a physical rotate gesture ends up
once the layout reflows for the new orientation. Android can and does dispatch that as a
genuine tap on whatever is now under the finger post-relayout — this is a known class of
Android UI bug, not a state-management bug. `addTab()` now tracks the timestamp of the
last orientation flip (`LaunchedEffect(configuration.orientation)`) and ignores any call
landing within 600ms of it, silently swallowing the ghost-tap instead of spawning a
duplicate Ubuntu tab (which, since Ubuntu was already installed, wouldn't re-download but
WOULD fork a second live proot+bash session — still wasteful and confusing).

### Also hardened (defense in depth, no user-visible change)
Confirmed `TerminalService.createSession(isUbuntu = true)` correctly registers every
Ubuntu session into `liveSessions` (the list `getLiveUbuntuSessions()` reads from for
reattach). The `boundService?.createSession(...) ?: createTerminalSession(...)` fallback
pattern used at 3 call sites in `TerminalPane.kt` is a latent leak: if it ever hits the
`createTerminalSession()` fallback (boundService null at that exact instant), that session
is created UNTRACKED and invisible to future reattach checks. In practice `boundService`
is bound synchronously at the very start of composition so this window is tiny, but it's
flagged in AGENTS.md as a known residual risk worth closing properly if session-stacking
is ever seen again after the ghost-tap fix above.

### Status
All fixes pushed to `codespace-ide-mobile`, confirmed the correct MAIN app repo. CI green
on every commit this round (`74391c25` ghost-tap fix, `5fe1fada` dialog revert). Waiting
on the user to test: (1) rotate mid-download — should no longer add a phantom tab, and
should show BOTH status text and live "% downloaded" lines scrolling together in the
terminal, (2) tap "+" normally (not during a rotation) — should still add a tab instantly
since Ubuntu is already installed on this device.

### Next level
User said "after you fix that... we'll move to the next level" — awaiting their next
instruction once they've confirmed the rotate/tab/progress-text fixes above actually hold
on-device.


---

## 2026-07-03 (cont'd 3) — Fixed "terminal fills screen until rotate" + missing back button

### Bug 1: terminal/editor renders oversized on first launch, self-corrects after rotating
Root cause: `enableEdgeToEdge()` + the SplashScreen API (`installSplashScreen()`) can leave
the FIRST `WindowInsets` dispatch to the freshly-created Compose hierarchy stale/incomplete
— a known interaction between the two APIs. Compose lays out its first frame as if there
are zero system-bar insets to subtract, so content renders oversized/full-bleed until
something forces Android to redeliver a fresh insets pass — which a rotation always does,
explaining why it "fixes itself" on rotate. Fix: in `MainActivity.onCreate()`, right after
`setContent { ... }`, force `ViewCompat.requestApplyInsets(window.decorView)` ourselves so
the correct layout is applied on the very first frame instead of waiting on the user to
accidentally trigger it via rotation.

### Bug 2: system back button/gesture did nothing
`ProjectShellScreen.kt` imported `BackHandler` but never actually called it — only specific
in-app UI elements (the "Exit" dropdown item, one toolbar icon) called `onBack()` directly.
The hardware/gesture back action was completely unwired. Added a real `BackHandler` at the
top of the screen that closes whichever overlay/menu/dialog is currently open first (command
palette, connectors sheet, notif drawer, terminal theme picker, panel/explorer/more/person/
gear/run menus, color theme picker, chat panel, find/replace bar, open menu-bar dropdown) —
matching natural back-stack feel so back doesn't jump straight home while something's open —
and falls through to `onBack()` (return to home screen) only when nothing else is open.

### Status
Both fixes pushed to `codespace-ide-mobile` (confirmed correct main app repo). CI green on
both commits (`a5d713f4` insets fix, `b513a4b7` BackHandler fix). Awaiting user confirmation
on-device: (1) app should render at correct size immediately on cold open, no rotation
needed, (2) hardware/gesture back button should close menus one at a time then return to
the home screen.


---

## 2026-07-03 (cont'd 4) — Backend Railway deploy prep + Terminal shell env fix + Back button + Onboarding removal

### Backend Railway Deployment (code pushed, manual deploy needed)
- **`railway.json`** added at repo root — tells Railway to build using `backend/Dockerfile`
- **`backend/src/main.ts`** — added `/api/v1/health` endpoint for Railway healthcheck
- **`backend/src/database/database.module.ts`** — added `ssl: { rejectUnauthorized: false }` for Railway PostgreSQL in production
- **`backend/Dockerfile`** — cleaned up: slim runtime image, removed unnecessary `node-pty rebuild`
- **`backend/.env.example`** — full reference of all env vars needed (DATABASE_URL, JWT_SECRET, FIREBASE_*)

**Manual steps still needed (on railway.app):**
1. New Project -> Deploy from GitHub repo -> `wisdom131-max/codespace-ide-mobile`
2. Add PostgreSQL plugin
3. Set env vars: DATABASE_URL (auto from plugin), JWT_SECRET, JWT_REFRESH_SECRET, FIREBASE_PROJECT_ID, FIREBASE_CLIENT_EMAIL, FIREBASE_PRIVATE_KEY, OWNER_EMAIL, NODE_ENV=production
4. Railway gives URL -> paste into Android app's BASE_URL config

### Terminal Shell Environment Fix (matching Termux exactly)
Studied decompiled Termux smali (TermuxService.smali, TermuxTerminalSessionClient.smali) from user's phone.
The bash terminal tab was falling back to `/system/bin/sh` because the environment was incomplete.

**Root cause:** `TerminalService.createSession()` was missing critical env vars that Termux sets:
- `LD_LIBRARY_PATH` -> `nativeLibraryDir` (required for native .so resolution)
- `LD_PRELOAD` -> `libtermux-exec.so` (intercepts exec() for Samsung OEM compat)
- `ENV` -> `~/.ashrc` (ash reads this for interactive sessions)
- `PATH` order was wrong — `nativeDir` was missing

**Fix:** Rewrote `createSession()` env setup to mirror Termux's `TermuxShellEnvironmentClient`:
- `PATH=$bin:$nativeDir:/system/bin:/system/xbin`
- `LD_LIBRARY_PATH=$nativeDir:$existing`
- `LD_PRELOAD=$nativeDir/libtermux-exec.so`
- `ENV=$home/.ashrc`
- All Android system vars (ANDROID_DATA, ANDROID_ROOT, BOOTCLASSPATH, etc.) inherited

Also fixed **PS1 prompt** in `BusyboxInstaller.kt`:
- Was using bash-only escapes that ash doesn't support
- Changed to `${USER}` and `${PWD##*/}` (ash-compatible variable expansion)

### Back Button Fix (top-left corner)
**Problem:** The back button at the top-left of ProjectShellScreen used `Icons.Default.KeyboardArrowUp` (an UP arrow, not a back arrow) with a tiny 20dp click area. User reported it didn't work to go back to the home/menu screen.

**Fix:**
- Changed icon to `Icons.AutoMirrored.Filled.ArrowBack` (proper back arrow)
- Wrapped in a 44dp Box for proper touch target (Material Design minimum)
- `onBack()` calls `nav.popBackStack()` which returns to HomeScreen

### Onboarding Walkthrough Removal (blank screen fix)
**Problem:** After logging in and creating a project, the app showed a blank screen on first launch. Root cause: `OnboardingWalkthrough` — a full-screen Dialog overlay that rendered on first launch (controlled by `onboarding_seen` SharedPrefs flag). The dialog was either rendering blank or intercepting all touches so the user couldn't interact with the app behind it.

**Fix:**
- Removed `showOnboarding` state variable from `ProjectShellScreen.kt`
- Removed the `OnboardingWalkthrough(onDone=...)` overlay block
- Deleted `OnboardingWalkthrough.kt` file entirely
- Will build a better onboarding later

### Build chain fixes (runs #640-#676, all resolved)
All build errors from the feature addition round were fixed:
1. `AuthScreen.kt` — `setLoginHint` unresolved -> reflection
2. `CodeSpaceApp.kt` — `HomeScreen` missing `accessToken` + `onSignOut` params
3. `CodeEditor.kt` — real 0x0A byte inside string literals -> byte-replaced
4. `TermuxBootstrapInstaller.kt` — same embedded newline bug
5. `TerminalPane.kt` — missing `LazyRow`, `items`, `detectTapGestures` imports
6. `TerminalPane.kt` — `combinedClickable` experimental -> `pointerInput + detectTapGestures`
7. Hidden SDK constants `THREAD_GROUP_TOP_APP/FOREGROUND` -> raw integers 5 and 1
8. `Process.setProcessGroup()` hidden API -> reflection
9. `boundService` scope -> fixed lifecycle

### Current status
- CI: Build #676 green (all compilation errors resolved)
- Backend: Code ready for Railway deploy (manual step needed)
- Terminal: Shell env now matches Termux exactly
- Back button: Fixed with proper ArrowBack icon + 44dp touch target
- Onboarding: Removed entirely (was causing blank screen)
- Next: Test on device — back button, no blank screen, terminal shell works


---

## 2026-07-04 — Ollama + Claude Code Setup (Termux-style, local only)

### Reference
Based on [AbuZar-Ansarii/Claude-Ollama-VScode](https://github.com/AbuZar-Ansarii/Claude-Ollama-VScode).
Only the Termux/Ollama part was used — no VS Code (code-server) component.

### Architecture
```
App (proot Ubuntu)
├── Ollama binary (arm64, installed in /usr/local/bin)
│   ├── ollama serve → localhost:11434
│   └── Model: nemotron-3-super:cloud
│       └── :cloud tag = inference offloaded to NVIDIA cloud (minimal local RAM)
│       └── Requires FREE ollama.com account (ollama signin)
├── Claude Code (npm install -g @anthropic-ai/claude-code)
│   ├── ANTHROPIC_BASE_URL=http://localhost:11434
│   ├── ANTHROPIC_AUTH_TOKEN=ollama
│   └── ANTHROPIC_MODEL=nemotron-3-super:cloud
│   └── Full agent access: read/write files, run commands, edit code, search FS
└── AI Chat panel (in-app)
    ├── Connects to localhost:11434 (NOT Codespace)
    └── Default model: nemotron-3-super:cloud
```

### What changed (builds #848–#856)
1. **TerminalPane.kt** — "Setup Ollama + Claude Code" button (6-step automated setup):
   - Step 1: Download + install ollama-linux-arm64 binary from GitHub releases
   - Step 2: `ollama serve` in background on port 11434
   - Step 3: `ollama signin` (interactive — user enters ollama.com credentials)
   - Step 4: `ollama pull nemotron-3-super:cloud`
   - Step 5: `npm install -g @anthropic-ai/claude-code`
   - Step 6: Write ANTHROPIC_BASE_URL/AUTH_TOKEN/MODEL to ~/.bashrc

2. **CopilotChatPanelOverlay.kt** — Removed Codespace URL entirely:
   - Deleted `OLLAMA_CS` constant (was `https://turbo-system-xrw4697pr99x3rjj-11434.app.github.dev`)
   - Only `OLLAMA_LOCAL = "http://localhost:11434"` remains
   - Auto-detect now checks local only (no Codespace fallback)
   - Default model: `nemotron-3-super:cloud`

3. **AiAssistantPane.kt** — Same: local Ollama only, no Codespace
   - Renamed `callCodespaceModel` → `callOllama`
   - Replaced `CODESPACE_URL` → `OLLAMA_URL = "http://localhost:11434"`

### IMPORTANT: Ollama cloud models require an account
- Regular models (`llama3.2`, `qwen2.5-coder`) — no account needed
- Cloud models (anything `:cloud`) — **requires free ollama.com account**
- Run `ollama signin` to authenticate
- Free tier: ~50 cloud requests/month
- Paid tier available for more usage

### Build fixes applied
- Build #854: Kotlin string escaping broken (`\"` in echo commands for .bashrc exports)
- Build #855: Same escaping issue persisted
- Build #856: Fixed — simplified the export lines to use `\"` properly + added signin step
- **Build #856: GREEN ✅**

### Current build status
- Latest green: **#856** (commit `1e52611d69`)
- All Codespace URLs removed from the codebase
- Ollama setup is fully local (Termux-style)

### Next steps
1. User installs #856, taps "Setup Ollama + Claude Code"
2. Creates account at ollama.com (if not already)
3. Completes `ollama signin` in terminal when prompted
4. Tests `claude --model nemotron-3-super:cloud` — should have full file/command access
5. Tests AI Chat panel — should connect to localhost:11434
6. If cloud model is too slow or hits free tier limits, consider local model alternative


---

## 2026-07-04 — Crash Loop Fix Summary (builds #823–#845)

### Root Cause (CORRECTED)
NOT JNI null pointer (that was June's issue). July's crash loop was SIGKILL from TECNO HiOS
power management (`Hiber/proxyWakeLock` module). The app acquired PARTIAL_WAKE_LOCK + started
foreground service from `Application.onCreate()` on EVERY process restart. TECNO kills apps
that aggressively acquire WakeLocks + FGS immediately on startup. 16 SIGKILLs in 90 seconds.

### Fix (builds #823–#839)
1. Removed `acquireAppWakeLock()` and `startTerminalServiceEarly()` from `Application.onCreate()`
2. Service initialization moved to `TerminalPane`'s `DisposableEffect` (matches Termux pattern)
3. Build #834: Kill proot sessions on minimize — did NOT fix (crash is on STARTUP not leftover)
4. Build #836-#837: "Tap to start" button — user confirmed this worked
5. Build #839: Replaced manual tap with automatic **8-second delay** on reopen
   - First boot after install: auto-starts immediately (no delay)
   - Reopen after minimize: 8s stabilization delay before auto-forking proot
6. Build #843-#844: Fixed back button + enabled cleartext traffic for WebView

### Key lesson
On 3GB RAM devices, NEVER fork proot immediately on activity reattachment.
The app process needs time to stabilize. 8-second delay is the minimum.

### Build #845 status
- Back button: routes to Home screen, skips 8s spinner
- WebView: `usesCleartextTraffic="true"` for local dev servers
- Remotion: new tab connected to localhost:3000
- Ollama: automated setup button (download binary, install, pull model)
- Crash loop: RESOLVED by 8s delay on reopen

### Confirmed working
- App survives minimize → reopen without crash (8s delay)
- Back button navigates to Home screen
- Preview pane loads internet content (cleartext traffic enabled)
- Terminal menu has "Setup Ollama + Claude Code" button


---

## 2026-07-04 — FULL AGENT CAPABILITY PLAN (Superagent Parity)

### Goal
Make any AI launched in the app (via terminal Claude Code OR in-app chat panel) a full agent
with capabilities matching Base44 Superagent:

### Capability Matrix
| Superagent Feature | App Implementation | Status |
|---|---|---|
| File read/write | Terminal + chat panel tools | Terminal: ✅ Chat: TODO |
| Command execution (bash) | Terminal sessions | ✅ (via Claude Code) |
| Git push/pull/commit | git config in proot + token injection | TODO |
| Secret detection & storage | Encrypted SharedPreferences + pattern scan | TODO |
| Web search | curl to search APIs in proot | TODO |
| Remotion video creation | npx remotion render commands | TODO (Remotion tab exists) |
| Memory/identity persistence | Files in proot ~/.agent/ | TODO |
| OAuth connectors (Gmail, etc.) | Token storage + API calls | TODO |
| Entity/database management | Backend API calls | TODO |
| Automations (scheduled tasks) | cron in proot | TODO |
| Skills (reusable scripts) | Scripts in ~/.agent/skills/ | TODO |
| Image generation | External API calls | TODO |

### Architecture
```
User
├── Terminal (Claude Code)
│   ├── Full file system access (read/write/edit/search)
│   ├── Run any command (bash, npm, git, remotion)
│   ├── Git: push/pull/commit with stored credentials
│   ├── Remotion: npx remotion render <composition>
│   ├── Secret detection: scan files for API keys, tokens
│   ├── Memory: ~/.agent/memory.md, ~/.agent/identity.md
│   └── Skills: ~/.agent/skills/*.sh (reusable scripts)
│
├── In-App Chat Panel (Ollama with tool calling)
│   ├── Tool: run_command → writes to terminal, captures output
│   ├── Tool: read_file → reads from app file system
│   ├── Tool: write_file → writes to app file system
│   ├── Tool: list_files → lists directory contents
│   ├── Tool: git_operation → runs git commands
│   ├── Tool: render_remotion → runs Remotion render
│   ├── Tool: search_files → grep through files
│   ├── Tool: save_secret → stores in encrypted prefs
│   ├── Tool: get_secret → retrieves from encrypted prefs
│   └── Tool: web_search → curl to search API
│
└── Shared Resources
    ├── ~/.agent/CLAUDE.md (system prompt for Claude Code)
    ├── ~/.agent/memory.md (persistent memory)
    ├── ~/.agent/skills/ (reusable scripts)
    ├── ~/.git-credentials (GitHub token for push/pull)
    └── Encrypted SharedPreferences (API keys, tokens)
```

### Implementation Plan
1. **Enhanced setup script** — git config, Remotion install, CLAUDE.md, agent directory
2. **AgentTools.kt** — tool definitions + execution logic for chat panel
3. **CopilotChatPanelOverlay.kt** — integrate Ollama tool calling API
4. **SecretManager.kt** — encrypted storage + pattern detection
5. **ConnectorManager.kt** — OAuth token storage + API calls (Gmail, Calendar)

### Key Design Decisions
- Git credentials injected from app's GitHub PAT login (already stored)
- Ollama tool calling API (v0.3.0+) used for chat panel agent mode
- Secrets stored in Android EncryptedSharedPreferences (API 23+, minSdk 26 ✓)
- Agent system prompt (CLAUDE.md) tells AI about all available tools
- Memory persists in proot file system (~/.agent/ directory)

---

## 2026-07-04 — FULL AGENT CAPABILITY SYSTEM IMPLEMENTED ✅

### What was built
Complete agent capability system giving ANY AI launched in the app (via API, terminal,
or in-app chat) the same powers as a Base44 Superagent. 32 tools available.

### Files created/updated

**Kotlin (app-side, for API/chat-panel AI):**
- `agent/AgentTools.kt` — 32-tool system with text-based tool calling protocol
  - Shell: run_command, read_file, write_file, list_files, search_files
  - Git: git_commit_push, git_pull_rebase, git_branch (create/switch/list/merge/delete), git_status, git_diff
  - Video: render_remotion (clip-by-clip + FFmpeg merge for 3GB devices)
  - Secrets: save_secret, get_secret, detect_secrets (12 pattern types)
  - Web: web_fetch, web_search (DuckDuckGo)
  - Memory: save_memory, read_memory, delete_memory
  - Connectors: list_connectors, connect_service, use_connector (Gmail/Calendar/Drive/Slack/GitHub)
  - Data: create_entity, read_entities, update_entity, delete_entity (file-backed JSON)
  - Scheduling: schedule_task, list_tasks, cancel_task (ScheduledExecutorService + persistence)
  - Media: generate_image, upload_file
  - Packages: install_package (npm/pip/apt)

- `agent/AgentMemory.kt` — Persistent JSON key-value memory (survives sessions)
- `agent/AgentConnectorManager.kt` — OAuth connector registry (5 services, token storage, API calls)
- `agent/AgentEntityManager.kt` — File-backed CRUD data store (lightweight, no SQLite on 3GB)
- `agent/AgentScheduler.kt` — Task scheduling with cron support + persistence

**Shell (proot terminal, for Claude Code / any terminal AI):**
- `assets/agent-tools/agent-tools.sh` — 24 terminal commands mirroring the Kotlin tools
  - All git operations, remotion rendering, secret detection, web search/fetch
  - Memory persistence, connector management, scheduling (crontab), data entities
  - Sourceable in .bashrc for automatic availability

### Tool calling protocol
Text-based `<tool>{"name":"...","arguments":{...}}</tool>` tags.
Works with ANY Ollama model (no native tool-calling support required).
AgentTools.parseToolCalls() extracts calls, executeTool() runs them.

### Secret detection patterns (12 types)
AWS keys, GitHub tokens, Google API keys, Google OAuth tokens, OpenAI keys,
Anthropic keys, Slack tokens, Stripe keys, generic API keys, private keys,
JWT tokens, database connection strings.

### Connector architecture
OAuth flow: connect_service returns auth URL → user opens in WebView →
callback captures code → save_secret stores token → use_connector calls API.
Supported: Gmail, Google Calendar, Google Drive, Slack, GitHub.

### GitHub 2FA Status
- wisdomijezie90-art: 2FA ENABLED (TOTP)
  - TOTP secret stored securely
  - 16 recovery codes saved to private storage
- wisdom131-max: 2FA setup pending

---

## 2026-07-04 — BUILD FAILURES #861, #862 — FIX IN PROGRESS

### Build #861 — FAILED
Error: AgentTools.kt compilation errors (initial version had unresolved references)

### Build #862 — FAILED
Error: AgentScheduler.kt line 40 — `*/` in Kotlin block comment interpreted as comment end
Root cause: Comment contained `"*/N * * * *"` (cron notation), Kotlin parsed `*/` as end of `/** ... */`
Fix: Replaced block comment with single-line `//` comments (no `*/` possible)
All other agent files compile clean — only AgentScheduler.kt had the issue.

### Fix being pushed: Build #863
- AgentScheduler.kt: replaced `/** ... */` comment containing `*/` with `//` line comments
- No other files changed

---

## 2026-07-04 — GITHUB CODESPACE CONNECTION PLAN

### User Request
Connect Superagent to the GitHub Codespace on the phone so it can:
- See what each button in the app does
- Verify functions are working
- Understand the UI structure
- Help debug build failures faster

### Codespace Info
- Name: urban-umbrella-774x47p55px394p
- Owner: wisdom131-max
- Used for: development on phone

### Connection Options
1. **GitHub API (current)** — Clone repo, read source files, understand UI from code
2. **Browserbase** — Open Codespace web interface (codespaces.githubusercontent.com) in browser
3. **GitHub Codespaces API** — Get codespace status, machine type, running state
4. **SSH tunnel** — Not practical from sandbox

### What was done
- Cloned repo to sandbox for code analysis
- Can read any source file to understand button behaviors
- Can trace UI → ViewModel → Service connections from code
- Browserbase can open the Codespace web UI for visual inspection
- Build failures can be diagnosed from GitHub Actions logs via API

### AGENTS.md update protocol
Always update this file BEFORE answering user questions about the codebase.
This ensures all context is persisted for future sessions.

## Fixed: Build #863/#864 — AgentConnectorManager.kt:97 compile error (2026-07-04)

Picked this up from another AI's session that ran out of tokens mid-fix (they'd
already correctly fixed AgentScheduler.kt's `*/` block-comment issue, but that fix
alone wasn't enough — a second, unrelated compile error was still failing the build).

Error: `scopes.joinToString(",") { it.toString() }` where `scopes` is an
`org.json.JSONArray` parameter. Android's `org.json.JSONArray` does NOT implement
`Iterable`, so Kotlin's `joinToString` extension never resolves on it — compiler
correctly rejected it as "Unresolved reference."

Fix: iterate by index instead — `(0 until scopes.length()).joinToString(",") { idx ->
scopes.optString(idx) }`. Scanned the rest of the new `agent/` package (AgentTools,
AgentEntityManager, AgentMemory, AgentScheduler) for the same JSONArray-as-Iterable
mistake — this was the only occurrence.

## 2026-07-04 — VS Code FEATURE PARITY PUSH (full implementation)

### Directive
User wants everything the in-app agent capability system + VS Code UI parity work
was scoped to actually be IMPLEMENTED, not just planned. Explicitly asked to:
1. Wire AiAssistantPane into the UI (was defined but never rendered)
2. Make bottom panel tabs (Problems, Output, Debug Console, Ports) real, not stubs
3. Study the real VS Code UI (via the GitHub Codespace web UI) for exact button
   positions/behavior and mirror that UX in the Android app
4. Keep this file + Drive backup docs current as the plan progresses

### Plan (this session)
1. [in progress] Inspect github.dev Codespace UI via Browserbase for reference —
   Activity Bar icon order, panel tab layout, Command Palette behavior, status bar.
2. Wire `AiAssistantPane` to the Activity Bar (new icon, right-side panel,
   draggable width, matches VS Code's Copilot Chat panel UX).
3. Implement real Problems panel — parse compiler/lint diagnostics, list with
   file:line jump-to-source.
4. Implement real Output panel — channel dropdown (Build, Git, Extension Host
   equivalent), scrollable log view.
5. Implement real Debug Console — input line + output log, wired to running
   debug session if one exists.
6. Implement real Ports panel — list forwarded ports (e.g. Remotion's :3000),
   open-in-browser action.

Status of each step tracked below as they land.

### Status update — 2026-07-04
- Confirmed via code read: the AI chat panel is ALREADY wired in production
  (`CopilotChatPanelOverlay.kt`, real Ollama integration, persisted history,
  Ask/Agent/Plan modes) — reachable via the Activity Bar Chat icon and the
  gear menu's "Toggle AI Chat". `AiAssistantPane.kt` is confirmed dead code
  (not referenced anywhere) — candidate for deletion in a later cleanup pass.
- Live inspection of the real GitHub Codespace VS Code UI was attempted
  (Browserbase) but blocked by an interactive GitHub login wall (no stored
  credentials). Implementation proceeded from established, accurate VS Code
  UX knowledge instead of live click-through.
- Landed real implementations (previously static stubs) for:
  - Problems panel — actual static analysis on the open file (unmatched
    brackets, trailing whitespace, mixed indentation, TODO/FIXME markers)
  - Output panel — shared `AppOutputLog` channel, any part of the app can
    log real events to it (currently: debug-run dispatches)
  - Ports panel — real TCP probe of localhost dev-server ports (proot
    shares the host network namespace), tap to open live in Preview
  - Debug Console Run (▷) — builds the right interpreter command per file
    type and dispatches it into the real Ubuntu terminal session
- Build pushed (commit 8ca3c9a) — CI run in progress.

---

## 2026-07-05 — VS CODE UI PARITY: COLOR THEMES, INLINE AI PANEL, SEARCH & DEBUG

### Commits
- `1dc142b` — Color theme picker, inline draggable AI panel, menu bar, gear fixes
- `68c1cd9` — Functional Search panel + VS Code-style Run & Debug panel

### Build Status
- Build #28731080985 (commit 1dc142b): ✅ SUCCESS (6m9s)
- Build #28731276085 (commit 68c1cd9): IN PROGRESS

### Changes

#### AI Chat Panel (CopilotChatPanelOverlay.kt + ProjectShellScreen.kt)
- **Removed SmartToy icon** from activity bar (user feedback: "childish")
- **Replaced floating overlay with inline right-side panel** — renders inside the layout Row, not as a full-screen overlay
- **Draggable from left edge** — drag left to widen, drag right to narrow, drag past 60dp closes the panel. Mirror of how the explorer panel's right-edge drag handle works
- **Changed icon to Psychology** (Icons.Default.Psychology) — professional, matches VS Code Copilot aesthetic. Used in both the top-bar toggle and the panel header
- **All colors now flow from active theme** via `ChatPanelColors` — no more hardcoded Catppuccin colors
- **Ask/Agent/Plan mode tabs** with distinct icons (QuestionAnswer, AutoMode, ListAlt)
- **Model picker dropdown** — shows available Ollama models, auto-detects local models on launch
- **CopilotChatPanelInline** added as a non-overlay composable (no scrim, no full-screen Box)
- `aiPanelWidth` state variable (float, default 300f) controls the draggable width
- Top-right toggle: `showChatPanel` state, Psychology icon with blue highlight when active

#### Color Theme Picker (ProjectShellScreen.kt)
- Full dialog with **16 themes**: Dark (Default), Dark Modern, Dracula, AMOLED Black, Monokai, One Dark Pro, GitHub Dark, Tokyo Night, Nord, Catppuccin Mocha, Light (Default), Light Modern, GitHub Light, Quiet Light, Solarized Light, Eye Care
- Each theme shows **live color preview swatches** (background, accent, indicator colors)
- Dark and Light sections with checkmark on the active theme
- Clicking a theme calls `onSelectTheme()` to switch the active `ThemePreset` instantly
- Dialog triggered from gear menu → "Color Theme" and command palette → "Preferences: Color Theme"

#### Menu Bar (ProjectShellScreen.kt)
- **Restored VS Code menu bar**: File, Edit, Selection, View, Go, Run, Terminal, Help
- Each menu has a dropdown with actions and keyboard shortcuts displayed
- All actions wired to `handleMenuAction` → `handleCommandAction`
- Current theme name displayed on the right side of the menu bar
- `MenuBarItem` and `MenuAction` data classes define the menu structure
- `openMenuBar` state tracks which dropdown is open

#### Gear Menu Fixes (ProjectShellScreen.kt)
- **Toggle Word Wrap** now shows ON/OFF state and actually toggles `wordWrap` state
- **Go to Line** shows a dialog with line number input (not just a notification)
- **Color Theme** opens the new theme picker dialog (not a notification)

#### Search Panel (ExplorerPane.kt)
- **Actual file content search** across the workspace — scans .kt, .java, .xml, .gradle, .kts, .py, .js, .ts, .json, .md, .txt, .yml, .yaml, .sh, .html, .css files
- **Case sensitive toggle** (Aa button) — highlights when active
- **Whole word toggle** (\b button) — uses regex word boundary matching
- **Regex toggle** (.* button) — enables regex pattern matching
- **Results grouped by file** with expand/collapse — file name, result count badge
- Expanded files show **line numbers and matched line text** in monospace
- Results count summary: "N results in M files"
- File scan limit of 500 files to prevent OOM on 3GB device
- Skips hidden directories, `build/`, and `node_modules/`

#### Run & Debug Panel (ExplorerPane.kt)
- **Launch configuration dropdown**: Kotlin Application, Android App (Debug/Release), Gradle Build, JUnit Tests, Terminal Script
- **Run/Stop buttons** with state management — Run (green play), Stop (red stop)
- **Collapsible Variables section** — shows variable name = value pairs when running
- **Collapsible Watch section** — placeholder with "Click + to add a watch expression"
- **Collapsible Call Stack section** — shows function frames with file:line references
- **Collapsible Breakpoints section** — shows file:line entries with red dot icons
- `SectionHeader` composable for expand/collapse UI (chevron + title)
- More menu (...) in header connects to `onMoreMenu` callback

### File Summary
| File | Changes |
|------|---------|
| `ProjectShellScreen.kt` | Menu bar, color theme dialog, inline chat panel, gear menu fixes, Psychology icon, handleMenuAction |
| `CopilotChatPanelOverlay.kt` | New `CopilotChatPanelInline` composable, Psychology icon, Ask/Agent/Plan tabs, model picker |
| `ExplorerPane.kt` | Functional SearchPanel (file content search), VS Code-style RunDebugPanel with collapsible sections |
| `AiAssistantPane.kt` | Replaced hardcoded light colors with MaterialTheme.colorScheme |

### Current Architecture
```
Root Box
├── Column
│   ├── Top Bar (workspace name, action icons, Psychology chat toggle, notification bell)
│   ├── Menu Bar (File | Edit | Selection | View | Go | Run | Terminal | Help)
│   └── Row (Main Body)
│       ├── Activity Bar (48dp: Explorer, Search, Git, Run, Extensions + bottom: Account, Settings)
│       ├── Side Panel (draggable right edge, 80-500dp)
│       ├── Editor Column (weight 1f)
│       │   ├── Tab Bar (horizontal scroll, close buttons)
│       │   ├── Breadcrumb (file path)
│       │   ├── Editor Area (EditorPane or watermark)
│       │   ├── Coding Toolbar (symbol keyboard)
│       │   ├── Find/Replace Bar
│       │   └── Status Bar (branch, errors, warnings, Ln/Col, encoding, language)
│       └── AI Chat Panel (draggable left edge, 60-600dp, inline not overlay)
├── Color Theme Dialog
├── Go to Line Dialog
├── Command Palette
├── Notification Drawer
└── Other overlays
```

### Next Steps
1. Monitor build #28731276085 — verify SearchPanel + RunDebugPanel compile cleanly
2. Test on device: drag the AI panel from right to left, verify it collapses to 0dp
3. Test color theme switching — verify all 16 themes render correctly
4. Test search panel — verify file content search works on workspace files
5. Wire Run & Debug "Run" button to actually launch the selected config in the terminal
6. Wire Search panel "Replace" functionality to actually replace text in files
7. Wire Breakpoints to toggle from editor gutter

---

## Update — July 5, 2026 (Build ~#866+)

### Editor Area Enhancements

#### MCP Status Indicator
- **Moved from terminal to blue status bar** (bottom right corner of app UI)
- Green dot = AgentApiServer running, Red = not running
- Polls every 3 seconds via LaunchedEffect
- Removed from both TerminalPane (main) and SplitTerminalPanel

#### Top Toolbar Cleanup
- **Removed Psychology (copilot) icon** from top toolbar — chat is accessible via command palette and gear menu
- Chat panel toggle still works via: command palette "Toggle Copilot Chat", gear menu, or keyboard

#### AI Chat Panel Drag
- Chat panel drag handle now **collapses to 0.dp** (closes at < 20f, down from 60f)
- Drag left-to-right to close — matches VS Code behavior
- Previous minimum was 60f, now 0f for full collapse

#### Editor Toolbar (NEW — below breadcrumb)
- **Quick action icons**: Find, Replace, Zoom −/+, Word Wrap toggle, Go to Line
- Shows current font size between zoom buttons
- **Live match count** when Find bar is open and query is non-empty
- Horizontal divider separates it from breadcrumb above and find bar below

#### Functional Find & Replace
- **Case sensitive toggle** (Aa) — highlights blue when active
- **Whole word toggle** (\b) — highlights blue when active
- **Regex toggle** (.*) — highlights blue when active
- **Replace (single)**: replaces first occurrence in file on disk + notification
- **Replace All**: replaces all occurrences in file on disk + count notification
- Match count shown in editor toolbar

#### Tab Context Menu (NEW — long-press on editor tab)
- **Close**: closes the tapped tab
- **Close Others**: closes all tabs except the tapped one
- **Close All**: clears all tabs
- **Close Saved**: closes all tabs (auto-save model means all are "saved")
- **Copy Path**: copies full file path to clipboard + toast
- Uses `combinedClickable` for long-press support

#### CodeEditor.kt Enhancements
- **Word wrap**: when enabled, removes horizontal scroll — text wraps to next line
- **Scroll-to-line**: `scrollToLine` parameter scrolls editor to target line (used by Go to Line dialog)
- **Bracket matching**: detects matching `() [] {}` when cursor is adjacent to a bracket
  - Searches forward for opening brackets, backward for closing brackets
  - Tracks depth for nested brackets
  - Stores `Pair(bracketPos, matchPos)` for highlight rendering
- **Indentation guides**: faint vertical lines at each 2-space indent level
  - Calculates max indent depth from file content
  - Renders up to 10 indent guide lines
  - Uses `colors.gutter.copy(alpha = 0.15f)` for subtle appearance

#### Go to Line — Now Functional
- Dialog accepts line number input
- Sets `scrollTargetLine` state → passed to EditorPane → CodeEditor
- `LaunchedEffect(scrollToLine)` scrolls `vScroll` to `(line - 1) * fontSize * 1.25`
- Auto-resets after 500ms so the same line can be re-triggered

### File Summary (This Update)
| File | Changes |
|------|---------|
| `ProjectShellScreen.kt` | Editor toolbar, functional find/replace, tab context menu, MCP in status bar, removed Psychology icon, chat draggable to 0dp, Go to Line scroll wiring |
| `EditorPane.kt` | Passes `wordWrap` and `scrollToLine` to CodeEditor (all 3 call sites) |
| `CodeEditor.kt` | Word wrap, scroll-to-line, bracket matching, indentation guides |
| `TerminalPane.kt` | Removed MCP indicators (both main + split), removed AgentApiServer import |

### Updated Architecture
```
Root Box
├── Column
│   ├── Top Bar (workspace name, action icons, notification bell)
│   ├── Menu Bar (File | Edit | Selection | View | Go | Run | Terminal | Help)
│   └── Row (Main Body)
│       ├── Activity Bar (48dp: Explorer, Search, Git, Run, Extensions + bottom: Account, Settings)
│       ├── Side Panel (draggable right edge, 80-500dp)
│       ├── Editor Column (weight 1f)
│       │   ├── Tab Bar (horizontal scroll, close buttons, LONG-PRESS context menu)
│       │   ├── Breadcrumb (file path)
│       │   ├── Editor Toolbar (Find, Replace, Zoom ±, Word Wrap, Go to Line, match count)
│       │   ├── Find/Replace Bar (functional case/word/regex toggles, replace, replace all)
│       │   ├── Editor Area (EditorPane with word wrap, bracket matching, indent guides)
│       │   ├── Coding Toolbar (symbol keyboard)
│       │   └── Status Bar (branch, Ln/Col, UTF-8, MCP indicator)
│       └── AI Chat Panel (draggable to 0dp, inline not overlay)
├── Color Theme Dialog
├── Go to Line Dialog (functional — scrolls editor)
├── Command Palette
├── Notification Drawer
└── Other overlays
```

### Next Steps
1. Verify build compiles with all editor enhancements
2. Test on device: editor toolbar icons, find/replace, tab long-press menu
3. Test word wrap toggle — verify long lines wrap correctly
4. Test Go to Line — verify editor scrolls to correct position
5. Test bracket matching — verify it detects nested brackets correctly
6. Add bracket match visual highlight (currently computes positions but doesn't render highlight)
7. Add code folding (collapse/expand functions and blocks)


---

## 2026-07-05 — FULL VS CODE ACTIVITY BAR PANELS + BUILD FIXES

### Source Control Panel — Complete Rewrite (SourceControlPane.kt)
- **Per-file stage/unstage**: click file row to stage, click staged file to unstage
- **+/- buttons**: explicit stage/unstage icons per file
- **Discard changes**: red X button per unstaged file (`git checkout --`)
- **Staged Changes + Changes sections**: VS Code pattern with collapsible headers + counts
- **Branch selector dropdown**: shows current branch, tap to switch (`git checkout`)
- **Ahead/behind indicator**: shows up/down arrows with commit counts
- **Stage all / Unstage all**: bulk actions in section headers
- **File status colors**: M=yellow, A=green, U=green(untracked), D=red
- **File-type icons**: code/markdown/image/archive/shell icons per extension
- **Commit button**: disabled until message + staged changes exist
- **Push button**: standalone push to remote
- **Pull (sync)**: header sync icon runs `git pull`
- **Refresh**: header refresh icon re-reads git status

### Search Panel — Enhanced (ExplorerPane.kt)
- **Include/Exclude file filters**: glob pattern inputs (e.g. `*.kt`, `!*.json`)
- **Filter toggle button**: show/hide include/exclude inputs
- **Replace All across files**: replaces in all matching files, shows count
- **Click result to open**: clicking a search result opens the file in editor
- **Replace result indicator**: shows total occurrences replaced

### Command Palette — Expanded
- Git commands: Commit, Push, Pull, Stage All
- Editor commands: Close All Editors, Close Editor, Format Document, Toggle Word Wrap, Go to Line
- Panel switching: Explorer, Search, Source Control, Run & Debug, Extensions
- Explorer commands: Open Folder, Refresh Explorer, Collapse All

### Build Fixes (July 5)
- **Hilt 2.51.1 -> 2.52**: Maven Central stopped resolving 2.51.1 plugin artifact
- **Padding positional args**: `padding(start=, end=)` -> `padding(16.dp, 4.dp, 8.dp, 4.dp)` (Compose BOM 2024.06.00 compatibility)
- **Regex escape fixes**: `\s` and `\.` in Kotlin string literals (Illegal escape errors)
- **Toast import**: Added `import android.widget.Toast` to ProjectShellScreen.kt
- **combinedClickable**: Added `@OptIn(ExperimentalFoundationApi::class)` + import

### Current Build Status
- Last successful build: commit `b4c77256` (before editor panel changes)
- Fixes pushed: commit `a10b572` — all compilation errors addressed
- Pending: green build verification, then device test

### What's Next (User Request — July 5)
1. **Multi-root workspace explorer**: VS Code-style — add multiple top-level folders to explorer
2. **Device file access**: Browse device files (pictures, downloads, etc.) from explorer
3. **Image preview on long-press**: Hold an image file -> popup shows the actual image, fades on release
4. **Green build**: All compilation errors fixed, waiting for CI confirmation

---

## 2026-07-05 — UNIVERSAL PREVIEW + REACT/JSX SUPPORT + DASHBOARD INTERACTIVE MODE

### PreviewPane — Now a Universal Live Preview (PreviewPane.kt)
The preview tab is no longer just for dashboards — it's a universal live preview for ANY code the user or AI writes.

**Supported preview types:**
1. **HTML/CSS/JS** — Any `.html` file renders live with full CSS + JS support
2. **React/JSX** — Auto-detected (import React, useState, ReactDOM, jsx keywords). Renders via:
   - React 18 UMD (development build)
   - Babel Standalone for JSX transpilation in-browser
   - Import/export statements stripped for browser compatibility
   - useState/useEffect/useRef injected from React
   - Works with any `.jsx`, `.tsx`, or `.js` file containing React code
3. **Markdown** — Any `.md` file rendered to HTML with GitHub-flavored styling
4. **SVG** — Any `.svg` file rendered inline
5. **Browser/Local Server** — Connects to local dev servers (Remotion Studio, localhost:3000, etc.)
   - `usesCleartextTraffic="true"` in AndroidManifest allows HTTP + local servers
6. **Dashboard (Interactive)** — Drag-and-drop dashboard builder with Chart.js
   - Add widgets: Stat Card, Chart (bar/line/pie), Progress Bar, Table, Activity Feed, Icon Grid
   - Drag widgets to reposition, X to remove
   - Export dashboard as standalone HTML file
   - AI can generate a JSON spec file → auto-renders as interactive dashboard

**How AI-generated content flows into preview:**
- AI writes an HTML file → user opens it → renders live
- AI writes a React component → user opens it → auto-detected and rendered with Babel
- AI writes a dashboard JSON spec → user opens it → rendered as interactive dashboard
- AI generates any UI → it appears in the preview tab for interaction

### Dashboard JSON Spec Format (for AI-generated dashboards)
```json
{
  "title": "My Dashboard",
  "widgets": [
    {"type": "stat", "title": "Revenue", "value": "$45,231", "label": "This month", "trend": "12.5%", "trendDirection": "up"},
    {"type": "chart", "title": "Sales", "chartType": "bar", "color": "#e94560", "labels": ["Mon","Tue","Wed"], "data": [30,50,45]},
    {"type": "progress", "title": "Build", "percent": 75, "label": "3 tasks left", "color": "#4ecca3"},
    {"type": "table", "title": "Files", "headers": ["Name","Size"], "rows": [["main.kt","12KB"]]},
    {"type": "activity", "title": "Activity", "items": [{"color":"#4ecca3","text":"Build succeeded","time":"2m"}]},
    {"type": "icons", "title": "Actions", "icons": [{"icon":"📁","label":"Files","color":"#3a86ff"}]}
  ]
}
```

### Build Fixes (July 5)
- **Kotlin string interpolation**: Rewrote `generateDefaultDashboard()` and `generateDashboardFromJson()` using string concatenation instead of triple-quoted strings with JS `$` signs (Kotlin interpreted `${...}` in JS as template expressions)
- **Nested double quotes**: Escaped `\"` in React detection strings (`from "react"` → `from \"react\"`)
- **JSONObject/JSONArray imports**: Fixed from bare `JSONObject` to `org.json.JSONObject`

### Current Build Status
- ✅ Build passing (commit `98a0a28`)
- APK available from GitHub Actions run #28738434547

### What's Next
1. **Multi-root workspace explorer**: Add multiple top-level folders (VS Code multi-root workspace pattern)
2. **Auto-refresh preview on file save**: Live reload when editor saves
3. **Device file access**: Browse device files (pictures, downloads) from explorer
4. **Image preview on long-press**: Hold image file → popup shows actual image
5. **AI → Preview pipeline**: AI writes file → auto-opens in preview tab
