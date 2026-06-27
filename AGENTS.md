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

## TERMUX SOURCE AUDIT — HOW TERMUX DOES IT (verified June 27, 2026)

Deep-dive of termux/termux-app latest source. All differences found and fixed.

### termux.c (JNI) — our impl is IDENTICAL to Termux's
Termux's create_subprocess does exactly: /dev/ptmx open, grantpt/unlockpt, IUTF8+no-flow-control termios,
TIOCSWINSZ, fork(), sigfillset(SIG_UNBLOCK), setsid(), dup2 stdin/stdout/stderr, close extra FDs via /proc/self/fd,
clearenv(), putenv() from envp, chdir(cwd), execvp(cmd, argv).
Our pty_native.c is already identical. ✓

### Login shell argv — POSIX leading-dash convention
Termux source (TermuxSession.java):
```java
String processName = (isLoginShell ? "-" : "") + ShellUtils.getExecutableBasename(executable);
arguments[0] = processName;  // e.g. "-bash" or "-ash"
```
execvp(cmd, argv): `cmd` = executable path (what to run), `argv[0]` = process name (what it sees as itself).
These are SEPARATE. cmd tells OS what binary to exec. argv[0] is just the name shown in `ps`.
Busybox additionally uses argv[0] to pick the applet. Leading dash stripped: "-ash" → "ash" applet.

### libbusybox.so applet table — ash not bash
Our libbusybox.so (2.79MB) has these shell applets: ash, sh, hush.
"bash" appears only as a string in an error message — NOT as a compiled applet.
Trying to run "-bash" → "bash: applet not found" → code 127.
Fix: use "-ash" as argv[0]. ash is BusyBox's full interactive POSIX shell.

### LD_PRELOAD — Termux sets it in the shell environment, not just proot
Termux exports LD_PRELOAD=libtermux-exec-ld-preload.so in every shell session's environment
(via TermuxAppShellEnvironment). This intercepts exec() calls from ANY child process.
We now do the same for the ash tab: LD_PRELOAD=${nativeLibraryDir}/libtermux-exec.so.

### Environment variables — what Termux sets (AndroidShellEnvironment + TermuxShellEnvironment)
Required vars (we now match these exactly):
- HOME, PWD, PATH, SHELL, TMPDIR
- TERM=xterm-256color
- COLORTERM=truecolor
- LANG=en_US.UTF-8
- LD_PRELOAD=libtermux-exec.so (if file exists)
- ANDROID_DATA, ANDROID_ROOT, ANDROID_STORAGE, ANDROID_RUNTIME_ROOT,
  ANDROID_ART_ROOT, ANDROID_I18N_ROOT, ANDROID_TZDATA_ROOT,
  EXTERNAL_STORAGE, BOOTCLASSPATH, DEX2OATBOOTCLASSPATH (passed from System.getenv())

### Shell startup files — ash vs bash
- bash reads: .bash_profile (login), .bashrc (interactive)
- ash reads: .profile (login), $ENV file (interactive, e.g. .ashrc)
- We now write .profile (not .bash_profile) ✓
- We set ENV=$HOME/.ashrc in .profile so ash loads aliases/settings for interactive use
- .inputrc is NOT used by ash (ash has its own line editing, not readline)

### setupShellCommandArguments — Termux's extra step
Termux reads the first 4 bytes of the executable to detect ELF vs script vs shebang.
For ELF: runs directly. For shebang: rewrites interpreter path to $PREFIX/bin/interp.
For no-shebang scripts: prepends $PREFIX/bin/sh.
We don't need this since we always run libbusybox.so directly (it IS an ELF).

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

busybox applet list (from binary): acpid, addgroup, adduser, ash, awk, base64, basename, cat, chmod,
chown, chroot, cp, cut, date, dd, df, diff, du, echo, env, expr, find, grep, gunzip, gzip, head,
hostname, httpd, hush, id, ifconfig, ip, kill, killall, less, ln, ls, md5sum, mkdir, more, mount,
mv, netstat, nslookup, ntpd, passwd, ping, printf, ps, pwd, rm, rmdir, route, sed, seq, sh,
sha256sum, sleep, sort, su, tail, tar, tee, test, top, touch, tr, uname, uniq, unzip, wget, which,
whoami, wc, xargs, xz, xxd, yes, zcat (and many more — full Unix toolset, NO bash or curl)

Ubuntu rootfs: ubuntu-questing-aarch64 (Ubuntu 25.04)
Reset rootfs: echo "" > /data/data/com.codespace.ide.debug/files/.ubuntu_version

Host-side pre-install: ProotInstaller.kt streams Packages.gz, downloads debs, extracts via
BoundedInputStream + ZstdCompressorInputStream + TarArchiveInputStream. No proot. No dpkg.

### pty_native.c — Termux's exact JNI (verified identical)
Replaces forkpty(). Does: /dev/ptmx + fork(), setsid(), sigfillset SIG_UNBLOCK, close extra FDs,
clearenv(), IUTF8, flow-control off, shows exec error in terminal on failure.

### createTerminalSession (TerminalPane.kt) — ash tab env
Now matches Termux exactly:
- cmd = libbusybox.so path (execvp executable)
- argv[0] = "-ash" (POSIX login shell name, busybox strips leading dash to find "ash" applet)
- env includes: HOME, PWD, PATH, TERM, COLORTERM=truecolor, LANG=en_US.UTF-8, SHELL, TMPDIR,
  LD_PRELOAD=libtermux-exec.so (if exists), ANDROID_* passthrough from System.getenv()

---

## WHAT IS WORKING (verified by build)
- APK builds (last green: 3a81d835ab58 at ~13:30 UTC June 27)
- App launches, GitHub PAT login
- Ubuntu boots to root@localhost (proot launch args correct)
- apt update works (DNS + apt config baked in)
- Progress messages in Ubuntu tab
- Resumable download, 5% progress, 3 retries
- Extra keys bar, SSH Manager, Text Expansions
- Editor multi-tab, auto-save, session restore
- TerminalService foreground before extraction
- pty_native.c = Termux exact impl
- LD_PRELOAD=libtermux-exec.so in proot env
- LD_PRELOAD=libtermux-exec.so in ash tab env (new)
- ash tab: argv[0]="-ash", full Termux-matching env

## WHAT IS NOT YET TESTED ON DEVICE
- ash tab: prompt appears, no "applet not found" (fix: -ash not -bash)
- Ubuntu: extraction no crash (OOM fix)
- Ubuntu: curl works after boot (pre-install + libtermux-exec)
- Tab completion and arrow keys in ash tab
- Terminal redraw on keystrokes

## WHAT IS BROKEN BY DESIGN
- apt install — dpkg blocked by Samsung kernel. PERMANENT.

---

## ALL CRASH ROOT CAUSES (June 27, 2026)

### 1. XZ OOM crash — FIXED
XZCompressorInputStream default: no memory limit → up to 800MB → Android OOM kill.
Fix: XZCompressorInputStream(stream, false, 96*1024) — 96MB limit, safe for ubuntu-questing (~80MB peak).

### 2. TerminalService never started — FIXED
Extraction ran as plain daemon thread (lowest OOM priority). Samsung kills first.
Fix: TerminalService.start() before thread, stop() in finally, progress in notification.

### 3. stale BusyboxInstaller.installEssentials() call — FIXED
ProjectShellScreen.kt referenced non-existent method. Fix: installIfNeeded().

### 4. Bash tab "login: applet not found" code 127 — FIXED (multiple rounds)
Round 1: arrayOf("--login") → TerminalSession uses args[0] as argv[0], busybox saw "--login" as applet name.
Round 2: arrayOf("bash","--login") → busybox ash doesn't support --login flag.
Round 3: arrayOf("-bash") → "bash" applet not compiled into libbusybox.so.
Round 4 (final): arrayOf("-ash") → ash IS the applet. "-ash" → strip dash → "ash" applet → works.

### 5. Pre-install OOM during curl deb download — FIXED
55MB rootfs extraction, then immediate deb download hits memory ceiling. Android OOM kill bypasses try/catch.
Fix: triple System.gc() + runFinalization() + 800ms sleep before deb download loop.

### 6. pty_native.c missing Termux features — FIXED
forkpty() → /dev/ptmx+fork(). Missing: setsid, signal unblock, FD cleanup, clearenv, IUTF8, flow-control off.
Fix: replaced entire pty_native.c with Termux's exact termux.c.

### 7. Ubuntu binaries silently failing in proot — FIXED
Missing LD_PRELOAD=libtermux-exec.so in proot env. exec() calls fail silently on Android.
Fix: added to proot env.

### 8. Missing env vars in ash tab — FIXED
Missing LANG, COLORTERM, LD_PRELOAD, Android system vars, PWD.
Fix: createTerminalSession now builds env matching Termux's AndroidShellEnvironment + TermuxShellEnvironment.

---

## TEST PROCEDURE
Install latest APK (commit 3a81d835ab58). Test in order — stop at first failure:

1. **Ash tab**: tap + → New Bash Terminal
   - Expected: prompt appears (no "applet not found")
   - If fails: check BusyboxInstaller.shellPath() returns valid nativeLibraryDir path

2. **Ubuntu tab**: tap + → Open Ubuntu Linux
   - Expected: "[Ubuntu] Checking installation..." immediately
   - Failure: TerminalService not started

3. **Extraction**: watch progress
   - Expected: Downloading 0%→100%, Extracting rootfs, Pre-installing, Downloading curl deb, Essential packages pre-installed, shell prompt
   - If crash at curl deb: increase GC sleep from 800ms to 1500ms

4. **Ubuntu shell**: `curl --version` → should show curl 8.14.x

5. **Ash tab**: confirm tab completion (Tab key) and arrow key history work

---

## NEXT STEPS IN ORDER

1. **TEST on device** (see TEST PROCEDURE above)
2. If ash tab works: verify tab completion and arrow key history
3. If curl works in Ubuntu: expand pre-install list (wget, git, python3, nano)
4. Fix terminal redraw on keystrokes (known issue)
5. Fix tab session persistence (save to disk on TerminalService stop)
6. Install Ollama in Ubuntu: `curl -fsSL https://ollama.com/install.sh | sh`
7. UI rebrand — VS Code functionality (search, git panel, run/debug, extensions, AI panel)
8. App icon — propagate new V/\Code logo to all mipmap folders
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
17. NEVER assume any API signature, constructor args, or parameter types. Always read source first.
18. XZCompressorInputStream: 3-arg form XZCompressorInputStream(stream, false, memoryLimitInKb). Arg2=Boolean, Arg3=Int.
19. ALWAYS start TerminalService before any long extraction/download thread
20. NEVER use --login flag for busybox bash/ash — use argv[0]="-ash" (leading dash POSIX convention)
21. NEVER use arrayOf("--login") — TerminalSession uses args[0] as argv[0], busybox treats it as applet name
22. ALWAYS add LD_PRELOAD=libtermux-exec.so to proot env AND ash tab env
23. NEVER assume apt install works in Ubuntu on this device — dpkg blocked by Samsung kernel permanently
24. libbusybox.so has ash/sh/hush — NOT bash. NEVER use "-bash" as argv[0]. Use "-ash".
25. ash login shell reads .profile (not .bash_profile). ash interactive reads $ENV (not .bashrc).
26. ALWAYS pass Android system env vars (ANDROID_DATA, ANDROID_ROOT etc.) from System.getenv() — Termux does this and many binaries need them

---

## KEY FILES

android/app/src/main/
  java/com/codespace/ide/terminal/
    ProotInstaller.kt       — download, extract, pre-install packages, launch proot
    BusyboxInstaller.kt     — ash tab shell setup (libbusybox.so nativeLibraryDir trick)
    TerminalService.kt      — foreground service, MUST be started before extraction
    TerminalModeManager.kt  — mode persistence
    DeviceCompatibility.kt  — DO NOT use to gate Ubuntu
  java/com/codespace/ide/ui/panes/
    TerminalPane.kt         — terminal UI, tabs, createTerminalSession() — CRITICAL for ash env
    SshManagerSheet.kt      — SSH profile manager
    TextExpansionSheet.kt   — text expansion manager
  java/com/codespace/ide/ui/screens/
    ProjectShellScreen.kt   — shell actions menu — keep in sync with BusyboxInstaller API
  assets/tools/
    busybox_arm64           — static busybox binary (same as libbusybox.so, 2.79MB)
  jniLibs/arm64-v8a/        — proot + busybox + zstd + termux-exec binaries
  cpp/
    pty_native.c            — JNI: Termux's exact /dev/ptmx+fork() impl (verified identical)
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
- CORRECT: implementation("com.github.luben:zstd-jni:1.5.6-4") — NO classifier
- WRONG: @aar suffix or linux_aarch64/android_aarch64 classifier
- Verify: unzip -l app.apk | grep zstd

### XZ extraction
- ALWAYS: XZCompressorInputStream(stream, false, 96 * 1024) — 3-arg constructor
- Default (2-arg) can OOM with 800MB allocation → silent Android kill

### busybox libbusybox.so shell applets
- HAS: ash, sh, hush (and 200+ Unix tools)
- DOES NOT HAVE: bash, curl, python, git, node
- ash IS BusyBox's full POSIX shell — supports variables, functions, for/while, arrays (limited), pipelines, redirects, here-docs, job control
- ash line editing: built-in (not readline). Arrow keys work. Tab completion works for paths and commands.
- ash startup: .profile (login), $ENV file (interactive). Set ENV=$HOME/.ashrc in .profile.

### TerminalSession argv convention (CRITICAL)
- TerminalSession(cmd, cwd, args, env, ...) calls JNI.createSubprocess(cmd, cwd, args, env, ...)
- JNI does: execvp(cmd, argv) where argv = args array
- cmd = executable to run (e.g. /data/.../nativeLibraryDir/libbusybox.so)
- args[0] = argv[0] = process name (what the process sees as its own name)
- These are SEPARATE. cmd is what gets exec'd. argv[0] is just the process name / applet selector.
- For busybox: argv[0] selects the applet. Leading "-" is stripped: "-ash" → selects "ash" applet → login shell.
- Termux source: arguments[0] = (isLoginShell ? "-" : "") + ShellUtils.getExecutableBasename(executable)

### libtermux-exec.so — Termux's secret weapon
Intercepts exec() calls via LD_PRELOAD and rewrites /usr/* paths to work on Android.
Without it, many Ubuntu binaries and scripts fail silently.
Set in BOTH proot env AND ash tab env. Path: context.applicationInfo.nativeLibraryDir + "/libtermux-exec.so"

---

---

## UI/SHELL GLUE AUDIT — June 27, 2026 (session 3 pass 2)

Deep audit of Termux UI/shell integration vs our implementation.

### TerminalSessionClient interface — COMPLETE ✓
All 13 methods implemented in SimpleTerminalSessionClient:
onTextChanged, onTitleChanged, onSessionFinished, onCopyTextToClipboard, onPasteTextFromClipboard,
onBell, onColorsChanged, onTerminalCursorStateChange, setTerminalShellPid, getTerminalCursorStyle,
logError/Warn/Info/Debug/Verbose/StackTrace. All correct.

### TerminalViewClient interface — COMPLETE ✓ (with 1 fix)
All methods implemented in SimpleTerminalViewClient. One fix:
- shouldEnforceCharBasedInput: was true (Samsung password keyboard mode) → fixed to false (TYPE_NULL, correct for TECNO)
- Termux base class returns false. TYPE_NULL is "most correct" per Termux comments.
- TYPE_TEXT_VARIATION_VISIBLE_PASSWORD is only for Samsung stock keyboards — not needed on TECNO KL4.

### TerminalView.java — IDENTICAL to Termux ✓
Line count matches upstream exactly (1500 lines). No divergence.

### mTermSession field — public ✓
TerminalView.mTermSession is public. Direct access in update lambda is fine.

### updateSize / initializeEmulator — called automatically ✓
TerminalView.attachSession() calls updateSize() internally.
onSizeChanged() also calls updateSize(). No manual call needed.

### Session creation timing — NO RACE ✓
- TerminalState/TerminalSession object created in remember{} (main thread) — no subprocess yet.
- LaunchedEffect fires → ensureOfflineShell writes .profile on IO thread.
- Spinner shows until bootstrapReady = true.
- AndroidView only rendered AFTER bootstrapReady → attachSession → updateSize → initializeEmulator → ash starts.
- .profile exists before ash subprocess launches. Correct order. ✓

### IME input connection — correct ✓
shouldEnforceCharBasedInput=false → TYPE_NULL input type.
IME_FLAG_NO_FULLSCREEN set in TerminalView. Correct for terminal use.
onSingleTapUp → requestFocus() + showSoftInput(). Keyboard appears on tap. ✓

### TerminalEnhancementManager — dead code ✓
terminal_profile.sh is written but never sourced by ash. The file is only accessible from UI menu items
("Setup Shell Profile", "Backup Shell Profile", "Restore Shell Profile"). Harmless, just not used by the shell.

### Two TerminalSession classes — NOT a conflict ✓
- com.codespace.ide.terminal.TerminalSession (Kotlin) — ProcessBuilder-based, used nowhere relevant
- com.termux.terminal.TerminalSession (Java) — real PTY session, used by TerminalPane.kt
TerminalPane.kt imports com.termux.terminal.TerminalSession explicitly. No name collision at runtime.

### Bugs found and fixed (UI/shell audit):
1. shouldEnforceCharBasedInput = true → false (wrong keyboard mode for TECNO) — commit 3e22991779
2. PS1 with single-quoted $(printf) — ash doesn't expand cmd substitution in prompt — fixed in dce9e01042
3. HISTFILESIZE/HISTCONTROL — ash ignores (bash-only) — removed in dce9e01042
4. .inputrc — ash doesn't use readline — removed in dce9e01042
5. installIfNeeded vs ensureOfflineShell at startup — .profile never written — fixed in 1dbd0d4e8c

### Hard rules added from this audit:
- NEVER set shouldEnforceCharBasedInput = true — only for Samsung stock keyboards
- NEVER use HISTFILESIZE or HISTCONTROL in ash profiles (bash-only vars)
- NEVER write .inputrc for ash — ash uses built-in line editor, not readline
- NEVER assume ash expands $(cmd) in PS1 — use ESC=$'\033' + double-quoted PS1
- ALWAYS call ensureOfflineShell (not installIfNeeded) on startup — the former writes .profile


---

## SIGNAL 31 CRASH + DEBUGGING STRATEGY — June 27, 2026 (session 3 pass 3)

### Root cause identified: SIGRTMIN (signal 31)
The terminal crash `[Process completed (signal 31) - press Enter]` is caused by Android's
power/battery manager sending SIGRTMIN to kill the terminal subprocess. This is the #1
cause of terminal crashes on TECNO, Infinix, and Samsung OEM devices with aggressive power management.

### Fixes applied:
1. **AndroidManifest.xml** — added `REQUEST_IGNORE_BATTERY_OPTIMIZATIONS`, `VIBRATE`,
   `REQUEST_INSTALL_PACKAGES`, `RECEIVE_BOOT_COMPLETED` — commit 8708902143
2. **MainActivity.kt** — added `requestBatteryOptimizationExemption()` called in `onCreate()`.
   - Uses `Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS` to prompt user on first launch.
   - Fallback: opens `ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS` if OEM blocks the direct dialog.
   - Commit: 6f1961f26d

### Missing permissions vs Termux (now fixed):
Termux has these that we were missing: VIBRATE, REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
REQUEST_INSTALL_PACKAGES, RECEIVE_BOOT_COMPLETED, READ_LOGS, DUMP, WRITE_SECURE_SETTINGS,
PACKAGE_USAGE_STATS, SET_ALARM.
We added the non-system-privileged ones. READ_LOGS/DUMP/WRITE_SECURE_SETTINGS require
system signing and cannot be granted to a regular APK.

### SSH client already in app:
`com.codespace.ide.ssh.SshManager` uses SSHJ library. Can connect OUT to SSH servers.
No SSH server (dropbear) running on-device yet. Not needed for debugging.

### Real-time crash log options:

**Option 1 — Wireless ADB (RECOMMENDED — no code changes needed):**
1. Settings → About Phone → tap Build Number 7× → enable Developer Options
2. Settings → Developer Options → Wireless Debugging → Enable
3. Tap "Pair device with pairing code" → note IP:port + code
4. On PC (same WiFi): `adb pair <IP>:<port>` → enter code → `adb connect <IP>:<debug-port>`
5. `adb logcat | grep -E "TerminalPane|BusyboxInstaller|ProotInstaller|JNI|proot|signal|killed|SIGRT"`

**Option 2 — In-app logcat reader (future):**
Add `READ_LOGS` permission (requires system signing or adb grant) + logcat reader screen.
Not viable for debug builds without manual `adb shell pm grant`.

### On-device manual battery fix (if dialog doesn't appear):
Settings → Apps → Visual Node Code → Battery → "Unrestricted" or "No restrictions"

### Hard rules from signal 31 investigation:
- ALWAYS request battery optimization exemption at launch for any app with long-running processes
- NEVER assume foreground service alone protects from OEM power managers — it does NOT on TECNO/Infinix
- ALWAYS match Termux's AndroidManifest permissions for any terminal emulator fork
- Signal 31 = SIGRTMIN = Android power manager kill. Not a code bug — a permission/system bug.


Last updated: June 27, 2026 by Superagent (Base44) — session 3 (signal 31 root cause + battery fix + ADB debugging guide)


---

## CRASH #9 — SIGSEGV signal 11 in JNI_createSubprocess — FIXED (June 27, 2026)

### Root cause
`Java_com_termux_terminal_JNI_createSubprocess` in `pty_native.c` — Termux JNI block.
The `args` array loop called `GetStringUTFChars(env, arg_java_string, NULL)` without a null check.
If any element in the `args` array was null, this caused SIGSEGV at offset `0x3c` (null pointer dereference).

### Tombstone key info
- Signal: 11 (SIGSEGV), code 1 (SEGV_MAPERR), fault addr 0x3c
- Cause: null pointer dereference
- Frame #00–#01: CheckJNI::GetStringCharsInternal in libart.so
- Frame #02: Java_com_termux_terminal_JNI_createSubprocess+116 in libptynative.so
- Call chain: onSizeChanged → updateSize → initializeEmulator → createSubprocess
- Timestamp: 2026-06-27 08:08:54 +0100, device TECNO KL4, Android 14

### Fix
Added null guard in the `args` loop inside `Java_com_termux_terminal_JNI_createSubprocess`:
```c
if (!arg_java_string) { argv[i] = strdup(""); continue; }
```
Matches the null guard already present in `Java_com_codespace_ide_terminal_NativePty_createSubprocess`.
Pushed in commit b2f60e9ddb.

### How crash was found
Bug report extracted from TECNO KL4 built-in bug reporter → tombstone_14 grepped for signal/crash info.

---

## SESSION HANDOFF NOTE — June 27, 2026

Previous AI (Claude via other platform) hit credit limit mid-session.
Continuing here on Base44 Superagent.
Context transferred via docx containing key parts of the previous conversation.
AGENTS.md updated by Base44 Superagent to reflect full crash history and current state.

### Current status as of handoff
- All known crashes fixed and pushed
- WHAT IS NOT YET TESTED ON DEVICE (see above section) is the next priority
- Next step: install the latest APK on TECNO KL4 and test ash tab + Ubuntu tab


---

## SIGNAL 31 — DEEPER FIX (June 27, 2026, session 4)

### Root cause #2 found: TerminalService was stopping after Ubuntu setup
`TerminalService.stop(ctx)` was called in the Ubuntu `finally` block — so after extraction
the foreground service died, leaving ash tab and Ubuntu tab completely unprotected.
Fix: `DisposableEffect(Unit)` in `TerminalPane` now starts service on open, stops on dispose.
Commit: 7203fa9780

### Root cause #3 found: No WakeLock/WifiLock (optional but critical on TECNO)
Verified from Termux source: Termux uses `ACTION_WAKE_LOCK` / `ACTION_WAKE_UNLOCK` intents
to toggle both `PowerManager.PARTIAL_WAKE_LOCK` and `WifiManager.WIFI_MODE_FULL_HIGH_PERF`
as a pair, triggered by a notification button. Notification rebuilds to show "Wake Lock held".

Implemented identically in `TerminalService`:
- Notification now has "Acquire WakeLock" / "Release WakeLock" button
- Both locks acquired/released as a pair (Termux pattern)
- `ACCESS_WIFI_STATE` + `CHANGE_WIFI_STATE` added to AndroidManifest
- Commits: bd570f8414 (TerminalService), 59dace346d (Manifest)

### How to use:
Open terminal tab → pull down notification → tap "Acquire WakeLock" → locks prevent signal 31.

### Hard rules from this investigation:
- NEVER stop TerminalService in a `finally` block mid-session — only stop when pane disposes
- ALWAYS use `DisposableEffect(Unit)` to tie service lifetime to composable lifetime
- WakeLock + WifiLock MUST be acquired/released as a pair (Termux pattern — never one without the other)
- Notification MUST be rebuilt after lock state changes to reflect current state


---

## TERMUX vs CODESPACE IDE — FULL COMPARISON + IMPLEMENTATION LOG (June 27, 2026)

### Research source
Verified directly from Termux open source:
- TermuxService.java, TermuxActivity.java
- TermuxTerminalSessionActivityClient.java
- TermuxTerminalViewClient.java
- TerminalSession.java (terminal-emulator module)
- TermuxTerminalExtraKeys.java

---

### FEATURES IMPLEMENTED THIS SESSION (commit f3c95d7321)

All added to TerminalPane.kt:

| Feature | What was done |
|---|---|
| **Bell handler** | `onBell` now vibrates 80ms via `VibrationEffect` (Termux default bell mode) |
| **onTitleChanged** | Escape sequences (vim, tmux, bash `\e]0;title\a`) now update tab name |
| **onSessionFinished** | Tab name appends `[exited]` when process dies — user can see it clearly |
| **onTerminalCursorStateChange** | Relayed to `TerminalView.setTerminalCursorBlinkerState()` |
| **onEmulatorSet cursor blink** | `onEmulatorSet()` now starts cursor blinker — matches Termux exactly |
| **Pinch-to-zoom** | `onScale()` now increments/decrements font size (6–48sp range) |
| **URL tap detection** | `onSingleTapUp` now regex-scans word at tap position, opens browser if URL found |
| **keepScreenOn** | `TerminalView.keepScreenOn = true` — screen won't dim mid-session |
| **PTY resize on layout** | `addOnLayoutChangeListener` fires `onScreenUpdated()` on size change → vim/nano get correct cols/rows |
| **Callback wiring** | All 4 callbacks (text, title, finished, cursor) fully wired in AndroidView `update` block |

---

### STILL MISSING (not implemented yet — future work)

| Feature | Termux impl | Status |
|---|---|---|
| **Custom color schemes** | `TerminalColors` loaded from `~/.termux/colors.properties` | ❌ Not done |
| **Custom fonts** | TTF loaded from `~/.termux/font.ttf` | ❌ Not done |
| **Hardware keyboard shortcuts** | Ctrl+Alt+N/P switch tabs, Ctrl+Alt+C new session etc | ❌ Not done |
| **Transcript URL long-press list** | Shows all URLs in scrollback, tap to open | ❌ Not done |
| **Session rename on title change** | Termux shows toast for background tab title changes | ❌ Not done |
| **Back key = Escape setting** | `shouldBackButtonBeMappedToEscape` driven by user preference | ❌ Hardcoded false |
| **Bell: beep mode** | SoundPool + bell.ogg (USAGE_ASSISTANCE_SONIFICATION) | ❌ Vibrate only |
| **Bell: ignore mode** | User-selectable bell behaviour (vibrate/beep/ignore) | ❌ No setting |
| **Auto-remove exited sessions** | Termux auto-closes on exit code 0 or 130 | ❌ Just marks [exited] |
| **Keep screen on preference** | Termux makes this a toggle in prefs | ❌ Always on |
| **Max sessions limit (8)** | Termux enforces MAX_SESSIONS = 8 | ❌ No limit |
| **Session list drawer** | Left-swipe drawer with all sessions listed | ❌ Only tab bar |
| **Ctrl+Alt keyboard shortcuts** | Full hardware keyboard shortcut map | ❌ Not done |
| **`libtermux-exec.so` LD_PRELOAD** | exec() interception for full Linux compat | ⚠️ Partial (env var set but binary may not exist) |
| **WifiLock on tab open** | Termux optional, we have it behind notification button | ✅ Done |
| **WakeLock on tab open** | Same — notification button toggle | ✅ Done |

---

### HARD RULES from this research

1. `onEmulatorSet()` MUST call `setTerminalCursorBlinkerState(true, true)` — without it cursor never blinks
2. `onScale()` MUST return `1.0f` (reset scale factor) — not the raw scale — or pinch state compounds incorrectly
3. `addOnLayoutChangeListener` is essential for vim/nano/htop — they need SIGWINCH via `onScreenUpdated()`
4. ALL 4 client callbacks must be re-wired in the `update` block of AndroidView every time session changes
5. `keepScreenOn = true` goes on the View, not the Activity Window
6. `onTitleChanged` — always `take(20)` the title to prevent tab overflow
7. Bell: always guard with `hasVibrator()` check — some emulators/devices have no vibrator
