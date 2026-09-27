# P5 TEST GUIDE — the full device verification round, expanded

Every test below is complete on its own: what to paste, where to tap, what you should see, PASS/FAIL, edge cases, and what to send back. Test numbers match P5-DEVICE-CHECKLIST.md (1-203), which stays the quick-reference version; this file is the walk-through.

**How to report results back (use this exact format, one line per test):**

```
T#1 PASS — 401 printed
T#2 PASS — tool list JSON, 32 tools
T#5 FAIL — token empty
T#4 NOT RUN — no second device today
```

For visual checks add a screenshot. For log checks paste the exact log line. `PARTIAL` is allowed when only part of the expectation held. Note ANY label or button whose wording differs from what I wrote — a renamed label is itself a finding.

**Before you start:** install the 9235b43 debug APK, open the app, sign in, and open (or create) any project. Most tests need a project open. Tests that need a SECOND device or app-internal storage are marked NOT RUN-able — skip and mark them, never fake a result.

---

# PHASE 1 — TP02 SECURITY ROUND (Tests 1-6)

**Get there:** open your project → bottom bar → the TERMINAL tab. If the session on screen started before you installed the new APK, close it (tab X) and start a NEW one — the fresh session sources the updated shell profile that exports `$AGENT_API_TOKEN`. You'll know the session is fresh if `echo $AGENT_API_TOKEN` prints something (test 5).

**T1. [TP02] No token = refused.**
- Paste exactly: `curl -s -o /dev/null -w "%{http_code}\n" http://localhost:8765/tools`
- Expect: a line printing exactly `401`.
- PASS: prints `401`. FAIL: prints `200` (server serving without a token — security hole), or a tool list. Edge: `000` + "Failed to connect" means the Agent API server isn't listening at all — that's a different finding; report it as FAIL(server-not-listening) and continue to T2 anyway.
- Report: the printed line.

**T2. [TP02] Token = allowed.**
- Paste: `curl -s -H "Authorization: Bearer $AGENT_API_TOKEN" http://localhost:8765/tools`
- Expect: JSON — a tool list (starts with `[` or contains `"tools"`). No "unauthorized" text.
- PASS: JSON tool list. FAIL: 401/empty (token not exported → your session is old; make a NEW terminal session and retry) .
- Report: first ~3 lines of output.

**T3. [TP02] Legacy wrappers still work.**
- Paste (two commands, one at a time):
  - `agent_tools`
  - `agent run_command '{"command":"echo ok"}'`
- Expect: `agent_tools` prints the tool list; `agent run_command` returns output containing `ok`.
- PASS: both work. FAIL: either errors out.
- Report: one line each.

**T4. [TP02] LAN closed.** (Needs a second device on the same Wi-Fi. If you don't have one today: mark NOT RUN.)
- On the phone, in the terminal: `ip addr | grep "inet "` → note the Wi-Fi IP (e.g. `192.168.x.x`).
- On the second device's browser or terminal: `curl -s -m 5 http://<PHONE-IP>:8765/tools`
- Expect: connection refused or timeout (NOT a tool list, NOT 401 — nothing should answer at all).
- PASS: refused/timeout. FAIL: any HTTP response comes back.
- Report: the exact error the second device shows.

**T5. [TP02] Token exported.**
- Paste: `echo $AGENT_API_TOKEN`
- Expect: a long hex string (64 chars, no spaces). PASS: non-empty hex. FAIL: empty line.
- Report: first 4 chars only (don't paste the full token to me).

**T6. [P2c] Untrusted project gets 403, then works after Trust.**
- Go HOME (back out of your project) → create a NEW project via the wizard (name it `p5-untrust`) → open it (do NOT tap Trust anywhere yet).
- In its terminal: `agent run_command '{"command":"echo hi"}'`
- Expect: a 403 refusal with a message like "project not trusted yet" in the JSON.
- Then: open the AI chat panel, send any request that needs a tool (e.g. "list the files in this project") → the TRUST prompt appears → tap **Trust**.
- Re-run the same terminal command → it now executes.
- PASS: 403 before trust, success after. FAIL: command ran before trust (that's the security hole), OR no trust prompt ever appeared.
- Report: the 403 message text + confirmation it worked after trusting.

---

# PHASE 2 — SETUP A: fresh install + rootfs + terminal (Tests 7-17)

**Note:** your device already has a working rootfs from the crash-fix testing, so T7 (fresh install) does not apply today — mark it NOT RUN and only run it if you ever wipe. T8's report-file read is app-internal storage; on a non-rooted phone you can't open it — the honest substitute is capturing the on-screen install progress lines, which show the same checksum-verification facts.

**T8 (substitute). [TP04] Checksum-verified install visible.**
- Only if you ever reinstall the rootfs from scratch (Settings → search "Ubuntu"/"Proot" → Reinstall rootfs, or first-run flow): watch the terminal/output stream.
- Expect: the stream prints a "Verifying rootfs checksum..." line and the install completes without an unresolved-symlink error.
- PASS: the verify line appears and completes. FAIL: install finishes without a verify line, or fails with unresolved links.
- Report: the progress lines (screenshot of the terminal stream is fine).
- Edge (optional, CI-level, skip if inconvenient): kill the app mid-download, corrupt the partial file, reopen → the install must SAY the download restarts, not extract garbage.

**T9. [TP04] Upgrade path.**
- Just open the Ubuntu terminal normally and run `ls /` → a normal rootfs listing (bin, etc, usr...).
- PASS: boots normally, commands run. FAIL: any unresolved-link or boot failure.
- Report: one line.

**T10. [TP06] Close-confirm dialog.**
- Part 1: in a NEW terminal tab run `sleep 300 && echo done` then within ~10 seconds tap that tab's X (close).
- Expect: a "Close terminal?"-style dialog (because the tab produced output recently). Tap Cancel → the tab survives.
- Part 2: run `sleep 300` (no echo) in another tab, wait 20 seconds doing nothing, then tap its X.
- Expect: closes with NO dialog.
- PASS: dialog in part 1, silent close in part 2. FAIL: no dialog after recent output (kill risk), or a dialog on a long-quiet tab.
- Report: both behaviors.

**T11. [TP07, TP14] Sessions survive minimize.**
- In a terminal tab run `ps | head -5` (works, shows processes). Press HOME (leave the app), wait 60 seconds, return to the app.
- Expect: the same session is alive and `ps` still runs in it.
- PASS: session alive, output intact. FAIL: tab killed on return ("Session ended").
- Report: one line.

**T12. [TP08, PG04] URL chip appears once, no churn.**
- Run `echo "see https://example.com/x"` → a clickable URL chip appears in/near the output within ~2 seconds.
- Then leave that terminal idle for 60+ seconds, watching the chip area.
- Expect: chip stays; no flicker, no rebuild, no duplicate chips. Switch away to another tab and back → chip restored, no burst of activity.
- PASS: chip once, stable, restored on return. FAIL: flicker/duplicates/chip disappears.
- Report: one line (screenshot if anything flickers).

**T13. [TP11] Quoted path (space + command-substitution bait).**
- HOME → New Project → name it exactly: `p5 spacey $(echo hi)`
  - If the wizard REFUSES this name, note that (it may be validation) and instead create `p5 spacey test` (space only) — the space alone exercises the quoting.
- Open the project, open its terminal, run `pwd`.
- Expect: the literal directory name printed (`p5 spacey $(echo hi)` — the `$(echo hi)` NOT executed), no shell error about the cd, and the session's startup transcript (if visible in Output) shows the cd quoted.
- PASS: pwd shows the literal name. FAIL: `hi` substituted somewhere, or the session fails to cd.
- Report: the pwd line.

**T14. [TP12] Backup excludes guest secrets.**
- First make sure the live rootfs has something secret-shaped: `ls ~/.ssh` (even empty is fine, but note it).
- Settings (App-wide) → search "Backup" → the Backup & Restore section → tap **Back up now** (container backup — this can take several minutes; watch the progress).
- When done, in the terminal paste:
  `tar -tzf /storage/emulated/0/CodespaceIDE/container-backup.tar.gz | grep -E "root/.ssh|root/.gitconfig"`
- Expect: NO output (grep finds nothing — exit code 1). Then `ls -la ~/.ssh` → still exists in the live rootfs.
- PASS: grep empty + live .ssh intact. FAIL: any tar entry listed.
- Report: whether grep printed anything + the tar file size (`ls -l /storage/emulated/0/CodespaceIDE/container-backup.tar.gz`).

**T15. [TP14] Per-project session reattach, no bleed.**
- Open project A, start a terminal session, run `echo A` in it. HOME → open project B, open its terminal, run `echo B`.
- HOME → open A again → its terminal shows YOUR earlier session (`echo A` output history) and B's session is NOT visible in A. Minimize 60s → return → both still right when you switch.
- Then force-stop the app (Recents → swipe away, or App Info → Force stop) → relaunch → open a terminal → run `ps aux | grep -i proot | head -3` → no duplicated/orphaned old trees beyond the fresh session.
- PASS: right session per project, no orphans. FAIL: A shows B's session or duplicate forks.
- Report: one line per sub-check.

**T16. [LS06] Same-name files don't cross squiggles.** (Needs the Kotlin LSP running — if you haven't installed it, Extensions tab → Packages → install the Kotlin LSP first, then open a .kt file and wait ~30s for it to start.)
- In a project create two folders and two files:
  - `folderA/MainActivity.kt` — paste: `fun brokenA() { val x: Int = "not an int" }`
  - `folderB/MainActivity.kt` — paste: `fun okB() { val y = 1 }`
- Open BOTH files as tabs. Wait for diagnostics (squiggly underlines) to arrive.
- Expect: `folderA/MainActivity.kt` gets a red squiggle under the bad line; `folderB/MainActivity.kt` stays clean (no squiggles copied from its same-named sibling).
- PASS: only folderA squiggled. FAIL: folderB also shows squiggles.
- Report: screenshot of both tabs.

**T17. [TP03, XG05] Package install streams honestly.**
- Extensions tab (side panel) → Packages section → install a small package (e.g. `cowsay` or `sl`).
- Expect: the op strip shows live apt output while it runs, then a done marker; the package appears in Installed without manual refresh. Now remove it → it disappears from Installed without refresh.
- PASS: streamed, honest result, list updates both ways. FAIL: silent hang, false success, or stale list.
- Report: one line.

---

# PHASE 3 — SETUP B: hostile fixtures (Tests 18-38)

**Prepare the fixtures ONCE (all in the app terminal, project open):**

**Fix-B1 (zip-slip bomb)** — paste exactly:
`python3 -c "import zipfile; z=zipfile.ZipFile('/storage/emulated/0/Download/slippz.zip','w'); z.writestr('../pwned.txt','x'); z.writestr('/data/local/tmp/abs.txt','x'); z.writestr('legit.txt','ok'); z.close()"`

**Fix-B2 (tar escape bomb)** — paste exactly:
`python3 -c "import tarfile,io; t=tarfile.open('/storage/emulated/0/Download/esc.tar.gz','w:gz'); t.addfile(tarfile.TarInfo('../escaped.txt'), io.BytesIO(b'x')); t.addfile(tarfile.TarInfo('safe.txt'), io.BytesIO(b'ok')); t.close()"`

**Fix-B3 (truncated APK)** — copy any real APK you have in Download (the codespace APK you installed from works), then:
`head -c 20000 /storage/emulated/0/Download/<your-apk-name>.apk > /storage/emulated/0/Download/trunc.apk`

**Fix-B4 (over-cap file)** — paste exactly (writes a 129MB file, takes a few seconds):
`python3 -c "open('/storage/emulated/0/Download/big.pcap','wb').write(b'\x00'*(129*1024*1024))"`
and a small control file:
`python3 -c "open('/storage/emulated/0/Download/small.pcap','wb').write(b'\x00'*1000)"`

**Fix-B5 (quoting-attack DB)** — paste exactly (the backslash-backtick is intentional):
`python3 -c "import sqlite3; c=sqlite3.connect('/storage/emulated/0/Download/evil.db'); c.execute('CREATE TABLE \"we\`ird\"(x)'); c.execute('CREATE TABLE normal(x)'); c.commit()"`
(One table named `we`ird` containing a backtick, one normal table.)

**Fix-B6 (>100-char deep path)** — run at your project root:
```
mkdir -p "a/deeply/nested/directory/structure/that/keeps/going/on/and/on/for/quite/a/while/indeed/to/exceed/one/hundred/characters/of/relative/path/length"
echo keepme > "a/deeply/nested/directory/structure/that/keeps/going/on/and/on/for/quite/a/while/indeed/to/exceed/one/hundred/characters/of/relative/path/length/important-file.txt"
```

**Fix-B7 (corrupt settings.json)** — this file is app-internal (`/data/data/com.codespace.ide.debug/files/settings.json`). Try registering it: Explorer → Add/Register root → paste `/data/data/com.codespace.ide.debug` → if it opens, go to `files/settings.json` and replace its content with `{{{{ not json`. If the app refuses to register that path, mark T34 NOT RUN (device-restricted).

---

**T18. [EX05] Zip-slip refused.**
- Explorer → open `/storage/emulated/0/Download` (registered root or built-in shortcut) → long-press `slippz.zip` → **Extract Here** (into Download or a subfolder — note which).
- Expect: extraction succeeds; the output dir contains ONLY `legit.txt`. Verify in terminal:
  - `ls /storage/emulated/0/Download/pwned.txt` → No such file or directory
  - `ls /data/local/tmp/abs.txt` → No such file or directory
- PASS: only legit.txt; both hostile targets absent; no crash. FAIL: pwned.txt exists anywhere, or crash.
- Report: both `ls` results.

**T19. [EX04] Path traversal refused.**
- Explorer → in the project tap + → **New File** → type exactly `../esc.txt` → confirm → expect an "Invalid file name"-style error. Verify in terminal: `ls ../esc.txt` → No such file.
- New File → `sub/ok.txt` → created nested inside the project (`ls sub/` shows it).
- Repeat both shapes with **New Folder**, and with **Rename** (rename any file to `../moved.txt` → refused, file not moved).
- PASS: every `../` form refused; the nested form works. FAIL: any escape created a file outside.
- Report: one line per attempt.

**T20. [EX07] Root removal honesty.**
- `mkdir -p /storage/emulated/0/Download/x && echo hi > /storage/emulated/0/Download/x/f.txt` → register `x` as an Explorer root → remove that root choosing the "Remove & delete files" option → expect: deleted, and it says so.
- Then register `/storage/emulated/0` itself → remove with delete → expect the refusal ("directory NOT deleted (device-critical/outside zone)") and NOTHING deleted — spot-check `ls /storage/emulated/0/` still lists your folders.
- PASS: deletes registered subfolder, refuses device-critical root. FAIL: storage wiped (catastrophic) or silent no-op with no message.
- Report: both messages (screenshot ideal).

**T21. [RG03, RG07] Restore rejects tar-escape entries.**
- Do this only AFTER T14 gave you a real container backup. In terminal:
  1. `cp /storage/emulated/0/CodespaceIDE/container-backup.tar.gz /storage/emulated/0/CodespaceIDE/container-backup.real.tar.gz` (protects the real one)
  2. `cp /storage/emulated/0/Download/esc.tar.gz /storage/emulated/0/CodespaceIDE/container-backup.tar.gz` (plants the evil one)
- Settings (App-wide) → Backup & Restore → **Restore** → confirm.
- Expect: restore completes (writes safe.txt into the container; the existing rootfs is NOT wiped). Verify: `ls /storage/emulated/0/CodespaceIDE/escaped.txt` → No such file or directory. Open a terminal → `ls /` → container still boots.
- Clean up: `cp /storage/emulated/0/CodespaceIDE/container-backup.real.tar.gz /storage/emulated/0/CodespaceIDE/container-backup.tar.gz`.
- PASS: restore completed, container alive, escaped.txt absent. FAIL: escaped.txt created, or restore crashed the app.
- Report: the escaped.txt check + container alive (yes/no).

**T22. [RG04] Long filename survives backup → restore.**
- With Fix-B6 in place: Settings → Backup & Restore → **Back up now** (wait) → **Restore** → confirm.
- Verify the deep file: `cat "<the full deep path>/important-file.txt"` → prints `keepme`.
- PASS: path + content intact end-to-end. FAIL: truncated/corrupted filename or missing file.
- Report: the cat output.

**T23. [IG01] Entity path traversal refused.**
- AI chat panel → ask: `Create an entity named ../projects for me`
- Expect: typed refusal ("Invalid entity name"-style). Then: `Create an entity named testentity123` → works.
- PASS: hostile refused, normal works. FAIL: hostile accepted.
- Report: the refusal message.

**T24. [VG02] Preview refuses symlink escape.**
- At the project root:
  - `mkdir -p ../sibling && echo '<h1>sib</h1>' > ../sibling/index.html`
  - `ln -s ../sibling siblink`
  - `echo '<h1>real</h1>' > page.html`
- Open `page.html` in the editor → run Live Preview → the real page serves and hot-reloads.
- Try the escape: open `siblink/index.html` (via the explorer if visible) → preview → expect the sibling content is NOT served through the link (refused / not found); the preview never forges an outside path.
- PASS: project page serves; symlink path refused. FAIL: sibling content served.
- Report: what the preview showed for the siblink path.
- Cleanup: `rm siblink` (the link only), then delete the `sibling` folder through the app's file explorer (it routes to trash — fine).

**T25. [OG04] Wizard refuses `..` names.**
- HOME → New Project → name it exactly `..` → attempt creation (try both create paths if offered).
- Expect: "not valid project names"-style refusal at BOTH paths; nothing created outside the chosen parent.
- PASS: refused everywhere. FAIL: anything created.
- Report: the refusal message.

**T26. [VG03, VG04] Corrupt APK doesn't crash.**
- Open `trunc.apk` from the Download root → it routes to the APK analyzer/manifest viewer.
- Expect: NO crash — empty or partial manifest data, app alive (open another file after).
- PASS: no crash, partial data, app alive. FAIL: any crash/ANR.
- Report: what the viewer showed (screenshot).

**T27. [VG05, VG10] Over-cap file = honest message.**
- Open `big.pcap` (129MB) → routes to the Network viewer → expect a clear "Too large to open in this viewer … cap 128MB"-style message. NO hang, NO crash, NO silent blank.
- Open `small.pcap` → the viewer engages (even if it reports empty/invalid content).
- PASS: cap message + small file engages. FAIL: hang/crash/blank.
- Report: the cap message text.

**T28. [VG06] Unsafe table name refused.**
- Open `evil.db` → the DB viewer. Expect: the `we`ird` table refused with an "unsafe name"-style error (or listed-but-refused on tap); `normal` lists its rows fine.
- PASS: hostile refused honestly, normal works. FAIL: crash or broken UI on the hostile name.
- Report: the refusal message.

**T29. [VG07] Extract-over-existing asks first.**
- Extract `slippz.zip` somewhere once (creates legit.txt), then extract it AGAIN into the same place.
- Expect: an **Overwrite / Cancel** dialog BEFORE anything is written. Cancel → nothing changed. Redo → Overwrite → file replaced.
- PASS: dialog + cancel honored. FAIL: silent overwrite.
- Report: dialog yes/no + cancel behavior.

**T30. [VG08] Quota honesty (OPTIONAL — skip if you don't want the disk churn).**
- `python3 -c "open('/storage/emulated/0/Download/huge.bin','wb').write(b'\x00'*(1100*1024*1024))"`, zip it, extract → extraction ABORTS with a quota message and leaves NO partial file. Delete huge.bin (and its zip) from Downloads afterwards.
- PASS: abort + no partial file. FAIL: partial file left or crash. Report: only if run.

**T31. [VG09] Logcat honesty.**
- Open the Logcat panel (search for it in Settings/panels/Output tools).
- Expect: an explicit "adb not available. Run 'adb logcat' in terminal…"-style line — NOT a silently empty feed.
- PASS: honest line present. FAIL: blank with no explanation.
- Report: the line.

**T32. [VG11] APK signature claim is honest.**
- Open the REAL codespace APK in the APK Analyzer → find the signing section (META-INF .RSA entry).
- Expect: wording like "signature entry present — META-INF/…RSA (presence only; not verified)" — must NOT claim "signed/verified" from mere presence.
- PASS: honest wording. FAIL: any "verified/signed" claim.
- Report: the exact line.

**T33. [VG12] Format routing regression.**
- From Download, tap a `.pcap` (and a `.har`, `.oat`/`.vdex` if available).
- Expect: each opens its correct viewer (Network / AndroidRuntime) — same dispatch as before the registry move.
- PASS: correct dispatch. FAIL: wrong viewer / no handler.
- Report: which opened.

**T34. [SK01, SK02] Corrupt settings quarantine.** (Needs Fix-B7 to have worked; else NOT RUN.)
- With settings.json corrupted: fully close the app → relaunch → expect a clean boot with defaults, NO crash. If you can re-register the internal root, confirm `files/settings.json.corrupt` exists (the quarantine copy).
- Toggle several settings + edit one keybinding → force-close → relaunch → all persist.
- PASS: clean boot + quarantine + persistence. FAIL: crash or settings lost.
- Report: boot + persistence results.

**T35. [RG02, RG08] Corrupt container backup → typed failure, container survives.**
- With your REAL backup in place (after T21's cleanup), truncate it:
`python3 -c "p='/storage/emulated/0/CodespaceIDE/container-backup.tar.gz'; d=open(p,'rb').read(); open(p,'wb').write(d[:len(d)//2])"`
- Settings → Backup & Restore → **Restore** → confirm.
- Expect: a TYPED failure (corrupt archive) in the status/terminal, fall back to fresh-install path, and the EXISTING container still boots (open terminal → `ls /`).
- PASS: honest failure + container intact. FAIL: crash, silent success, or destroyed container.
- Report: the failure message + container alive (yes/no).
- Afterwards: take a fresh **Back up now** so you have a valid tarball again.

**T36. [EX02] Nested trash restore.**
- `mkdir -p src/sub && echo x > src/sub/Util.txt` → delete `Util.txt` in the Explorer (soft delete → trash).
- Open the Trash view → restore it → expect it returns to `src/sub/` (verify `ls src/sub/`).
- PASS: restored in place. FAIL: restored at trash root or project root.
- Report: the restored path.

**T37. [EX01] Delete failure honesty (OPTIONAL — only if you can force a storage failure cleanly; otherwise NOT RUN).**
- Force a disk-full or read-only state, delete a file in the Explorer → expect honest failure text, file still present; a BULK delete reports the real moved count vs failures.
- PASS: honest per-item failures, no false "moved N" claim. Report: what happened.

**T38. [EX06] New Folder normal path.**
- Explorer → + → New Folder → `made-folder` → visible in the tree immediately.
- PASS: appears. Report: one line.

---

# PHASE 4 — SETUP C: the polyglot test project (Tests 39-96)

**Create the project and fixtures ONCE:**

1. HOME → New Project → name `p5-poly` → create + open it. Trust it when asked.
2. In the project terminal, install the runners (takes a few minutes):
   - `apt-get install -y python3-pytest` (pytest)
   - `npm init -y` then `npm install --save-dev jest typescript` (Node is already in the container)
3. Create these files (Explorer → + → New File → paste → save):

**File `test_math.py`:**
```python
def add(a, b):
    return a + b

def test_add():
    assert add(1, 2) == 3

def test_sub_fails():
    assert add(2, 1) == 1

def test_mult_fails():
    assert add(2, 2) == 5
```
(one passing test, two deliberately failing)

**File `sum.test.js`:**
```js
const sum = (a, b) => a + b;
test('adds ok', () => { expect(sum(1, 2)).toBe(3); });
test('adds wrong', () => { expect(sum(1, 1)).toBe(3); });
describe('group', () => {
  test('nested pass', () => { expect(sum(0, 0)).toBe(0); });
});
```

**File `util.ts`:**
```typescript
export function unusedName(x: number): number {
  return x + 1;
}
```

**File `Main.kt`:**
```kotlin
// a comment line to check highlighting
fun broken(): Int {
    val s: String = "a string value"
    return 42
}
```

**Part C1 — Test lenses and honest runs (T39-T52)**

**T39. [TG05/F1] Lens detection is annotation-driven.**
- In the project create `KtLensTest.kt`:
```kotlin
class SomethingTest {
    fun testing() { }            // NO annotation — must NOT get a lens
    @org.junit.Test fun annotated() { }  // annotated — MUST get a lens
}
```
- Open it and wait for the Kotlin LSP (Extensions → Packages → install Kotlin LSP first if you haven't).
- Expect: a "Run Test"-style lens ONLY on `annotated()`. NOTHING on `testing()`.
- Then open `test_math.py` → lenses on all three test functions; `sum.test.js` → lenses on `adds ok`, `adds wrong`, and the nested `nested pass` (a "Run Tests" suite lens on `group`/`describe` too; `.skip` variants you can add — a skipped test gets no runnable lens or an honest skipped state).
- PASS: annotated-only JVM lens; Py/JS lenses present. FAIL: a lens on `testing()` (the old class-name heuristic — the exact bug this row fixed).
- Report: which functions showed lenses.

**T40. [TG06] Honest command templates.**
- Tap Run Test on `annotated()` → watch the Output tab → test channel.
- Expect: the logged command is plain (e.g. `./gradlew test ...` for JVM, `python3 -m pytest ...` for Python) with NO `2>/dev/null` and NO `|| echo` fragments in the echoed command.
- PASS: clean command shown. FAIL: any suppression/fallback fragment in the command line.
- Report: the exact command line from the output.

**T41. [TG02/F2] One tap = one test.**
- Tap Run Test on `test_add` ONLY → watch the Output test channel.
- Expect: pytest runs ONLY `test_math.py::test_add` (the node id appears in the output; no other test executes).
- Then tap the `group` describe suite lens on the JS file → the container test runs (both tests inside `group` if you added more), NOT the whole file.
- PASS: single-test node id; suite tap targets the container. FAIL: whole file/project ran.
- Report: the pytest node-id line.

**T42. [TG01/F2] Typed results.**
- Run `test_add` → expect `PASSED — test_add (exit 0, Ns)`-style line in the test channel.
- Run `test_sub_fails` → expect `FAILED — ... (exit N)` WITH pytest's real assertion traceback visible above it (nothing suppressed).
- PASS: both typed lines + visible stderr. FAIL: silent or missing traceback.
- Report: both result lines.

**T43. [F2] Trust gate.**
- Create one more project `p5-untrust2`, open it, DON'T trust yet. Create a tiny `t.py` with a passing test, tap Run Test.
- Expect: the Trust prompt appears once; refuse → the channel shows the honest "not trusted — run refused" line; nothing executed.
- Trust it → re-run → executes. Tap Run Test again → NO second prompt.
- PASS: prompt once, refusal honest, no re-prompt after trust. FAIL: ran while untrusted, or prompt loops.
- Report: refusal line text.

**T44. [F2] BUSY on double-tap.**
- On `test_math.py` tap Run Test on `test_add` and IMMEDIATELY tap Run Test on `test_sub_fails`.
- Expect: the second tap is refused with an "already active"-style BUSY line; the first run finishes cleanly; the two runs do NOT interleave in the channel.
- PASS: honest BUSY. FAIL: two interleaved runners.
- Report: the BUSY line.

**T45. [TG07/F5] Debug Test for Python + JS (and honest absence for JVM).**
- Python: add a breakpoint on the `assert` line of `test_add` (gutter tap on that line in the editor). Tap its Debug Test lens (or the row's Debug chip in the Tests pane).
- Expect: the debug console attaches (debugpy), tap **Continue** → execution STOPS at your breakpoint; variables/stack visible; Continue again → the test finishes → the row/gutter flips to PASSED (only that test ran).
- JS (only if `npm install` finished): breakpoint inside `adds ok` → Debug Test → console shows jest starting under `node --inspect-brk` → attach → Continue → stops at the breakpoint → Continue → finishes.
- JVM: `annotated()` shows NO Debug lens/chip (supportsDebug=false until the future JVM decision).
- Also: a test that FAILS under the debugger (breakpoint then let `adds wrong` fail) ends CRASHED → FAILED row. A passing debug run that you stop early ends STOPPED→PASSED only if it really completed.
- PASS: Python attach + stop; JVM honest absence; failed-debug → FAILED. FAIL: fake session, or no stop at the breakpoint.
- Report: which languages attached + whether each stopped.

**T46. [TG03/F3] Failing test lands in Problems.**
- Run `test_sub_fails`. After it completes, open the **Problems** panel.
- Expect: a TEST-source row with the real failure message (assertion text). Tap the row → the editor jumps to the failing line in the right file. Fix the test (make it `assert add(2,1) == 1`), run again → the stale row disappears.
- PASS: row appears, jump lands, stale clears. FAIL: wrong file/line or stale row lingers.
- Report: screenshot of the Problems panel after the failed run.

**T47. [TG03/F3] Gutter states.**
- While `test_sub_fails` runs, watch its line: blue RUNNING dot → red cross on failure. `test_add`: blue → green check. If you run the `group` suite: the suite line shows the merged state, and tests the run did NOT cover go dim (no glyph).
- PASS: live glyph transitions. FAIL: glyphs stuck or wrong.
- Report: one line.

**T48. [TG03/F3] Report hygiene.**
- After a pytest and a jest run, check the project root: `ls` in the terminal → NO `.codespace-test-result.xml/json` leftovers (they're parsed then deleted).
- PASS: clean root. FAIL: leftover reports.
- Report: ls output (or "clean").

**T49. [TG04/F4] Testing pane tree.**
- Open the **Tests** bottom tab → **Refresh**.
- Expect: a tree — `test_math.py` with 3 leaves, `sum.test.js` with its leaves (suite indented under `group`), correct counts per file; the same leaf names the editor lenses show.
- Tap a leaf's Run → only that test runs; row goes blue then check/cross; the gutter glyph agrees.
- **Run All** → every leaf runs sequentially. Tap **Stop** mid-batch → current runner dies, the batch ends honestly (the interrupted test CANCELLED, others untouched).
- After a mixed run, **Run Failed** → ONLY failed rows re-run (watch the channel — no passing test re-executes).
- Switch to another project → the tree clears (no foreign tests).
- PASS: tree correct, run/stop/run-failed honest, clear on switch. FAIL: any foreign or missing rows, or Run Failed re-running passes.
- Report: the tree shape (screenshot) + run-failed behavior.

**T50. [TG08] Run Unit Tests routes honestly for non-gradle projects.**
- In `p5-poly` (no gradle wrapper): find the build/tasks panel → **Run Unit Tests**.
- Expect: it routes through the real test pipeline (discovery → TestStore targets → runner) with an honest passed/failed summary — NOT a cryptic gradle-wrapper failure.
- PASS: real run or an honest "no tests found". FAIL: gradle error for a non-gradle project.
- Report: the summary line.

**T51. [TG09] Lens dedupe.**
- Open `test_math.py` with the Python LSP active AND the built-in lens detector both alive.
- Expect: each test line shows ONE chip, not a doubled pair.
- PASS: single chip per line. FAIL: duplicates.
- Report: one line.

**T52. [TG10] Detection is debounced.**
- Type rapidly in `test_math.py` (add spaces/new lines over ~10 seconds of typing).
- Expect: no per-keystroke rescans — the UI stays smooth; lens positions update ~500ms after you STOP typing, not during.
- PASS: smooth typing, update after pause. FAIL: jank per keystroke.
- Report: one line (smooth/stuttered).

**Part C2 — Problems panel (T53-T64)**

**T53. [PR01] Cancel kills the process, status sticky.**
- Start a long build in the terminal channel: in the Tests/Output-triggered run or a build task — easiest: run the full jest suite via Run All, then tap Stop/Cancel.
- Expect: the runner's output STOPS dead in the channel (no continued lines), and the status stays CANCELLED — a later run does not overwrite it to "PASSED".
- PASS: dead process + sticky CANCELLED. FAIL: trailing output after cancel, or status overwritten.
- Report: the status tile text after cancel.

**T54. [PR03, PR01] Failed build is FAILED; long output truncated with a header.**
- Force a failing build: easiest is a TS build — edit `util.ts` to contain `const x: number = "wrong";` then run the TypeScript build/check task (or `npx tsc --noEmit` via task runner).
- Expect: the Problems/build status shows FAILED — even if some warning line contains "BUILD SUCCESSFUL" text somewhere in the output. The failure rows are real file rows (tap → jump).
- Then run something with >10000 output lines (e.g. `npm install` shows many lines) → expect the output kept the FIRST 10000 lines with a visible truncation header.
- PASS: honest FAILED + truncation header. FAIL: success-ish status on a failed build.
- Report: the status line + whether the truncation header appeared.

**T55. [PR06] Build-row matchers are per-language.**
- With the Python LSP not involved, run in the terminal: `python3 -c "print('bad.py:42: error: fake error line')"` (a plain script printing a compile-shaped line).
- Expect: NO false BUILD row appears in Problems (python is TRACEBACK-only in the gate).
- Then make a REAL error: `npx tsc --noEmit` on the broken `util.ts` → a TSC row appears. And an `npm install` produces NO rows.
- PASS: no fake rows, real rows appear. FAIL: the fake printed error became a row.
- Report: which rows appeared.

**T56. [PR08] Pathless build rows are honest.**
- After the failing tsc/build: the Problems panel shows "(build output)"-style header rows for errors with no file path.
- Expect: tapping them does NOTHING (no crash, no jump) — they're display-only. Real file rows still jump.
- PASS: inert header rows + working file rows. FAIL: crash or a jump to nowhere on a pathless row.
- Report: one line.

**T57. [PR09] Explorer badge is file-scoped.**
- With the broken `util.ts` in the tree: the Explorer shows a badge on `util.ts`.
- Expect: the badge count = only THAT file's problems; the panel opened from it is pre-filtered with a "File: util.ts" chip. Tap the chip → clears to all files.
- PASS: file-scoped badge + filter chip works. FAIL: badge shows global counts, no chip.
- Report: the badge number + chip behavior.

**T58. [PR14] Panel and squiggles agree live.**
- Open `util.ts` (broken). Expect: red squiggle under the bad line AND a matching Problems row — same message.
- Insert 5 blank lines ABOVE the error (don't save) → both the squiggle and the panel row shift DOWN 5 together (unsaved edits count).
- Fix the error (edit it out) → the row and squiggle both clear; a NEW error while the panel is open appears without reopening it.
- PASS: agreement through edit + live updates. FAIL: drift between gutter and panel.
- Report: one line.

**T59. [PR02] Count agreement, no 3s lag.**
- With a few problems present: the RUN badge (task/build surface), the Explorer file badge, and the Problems panel header count all show the SAME number, updating together — within a second, not a 3-second poll delay.
- PASS: same number, near-immediate. FAIL: stale disagreement.
- Report: the three numbers.

**T60. [PR07] Panel menu filter is real.**
- Problems panel → ⋯ (overflow) menu → "Show Errors Only" → only errors remain; "Show All Problems" → everything returns. "Focus Search" → the search box gets focus.
- PASS: both filters actually filter; focus works. FAIL: no-op menu items.
- Report: one line.

**T61. [PR05] Related-information lines render.**
- In a Kotlin file add: `import java.util.List` at the top with no usage. Wait for the LSP diagnostic.
- Expect: a "unused import"-style row; IF the server sends related info, the row shows "↳ first usage"-style sub-lines; tapping the row jumps to the import.
- If your LSP doesn't emit related info for this case, mark PARTIAL (row+jump verified, related-lines not exhibited by this server) — don't fail the row on the server's choice.
- Report: full or partial + what you saw.

**T62. [PR11] Lint dedupe, no jank.**
- Open the broken `util.ts`, wait for diagnostics; close/reopen quickly several times; switch tabs fast.
- Expect: no duplicate identical rows accumulate; no visible jank; no repeated lint passes in quick succession (no double work while switching).
- PASS: clean single rows, smooth. FAIL: duplicates or jank.
- Report: one line.

**T63. [PR12] Build dropdown = full catalogue.**
- Open the build/tasks panel → its task dropdown.
- Expect: the full task catalogue (the ~8 defined tasks) listed; picking one runs EXACTLY that task (not a default).
- PASS: full list, correct dispatch. FAIL: missing or wrong-task dispatch.
- Report: the tasks listed.

**T64. [PR13] Interrupted runs marked honestly.**
- Start a build, then CLOSE the task panel mid-run → the tile/row ends FAILED with "interrupted"-style text (not silently vanished, not stuck RUNNING).
- Kill the app mid-build (force-stop), relaunch → the task shows FAILED "interrupted by app restart"; you can re-run it.
- PASS: honest interrupted states both ways. FAIL: stuck RUNNING forever.
- Report: both outcomes.

---

**Part C3 — IntelliSense (T65-T76)**

*(These need the Python LSP and/or TS LSP running — install via Extensions → Packages if a test complains, then wait ~30s after opening the file.)*

**T65. [IC01] Accept contract.**
- In `test_math.py` type on a new line: `    add` then wait for completions; highlight the `add(` member if listed and tap the keyboard's **Tab** (the accessory key).
- Expect: ONLY the typed fragment is replaced (the `.` before it, the `obj.` prefix — intact); no whole-word over-replacement.
- Snippet route: in the same file type `for` → accept the for-loop snippet (if present) with Tab → expect tab stops engage (first default text highlighted), no raw `$1` left in the text.
- Commit-char route: with an LSP item highlighted, type `(` → the item commits, then `(` is appended after it.
- PASS: all three routes clean. FAIL: over-replacement or raw `$1`.
- Report: which routes worked.

**T66. [IC02] Auto-import span.**
- In `util.ts` (or a new TS file), delete everything, then type `import { unusedName } from "./util";` — remove it again, and instead type `unusedNa` and accept the completion that offers to ADD the import (tsserver's auto-import).
- Expect: the replaced span is exactly what the server intended; the import line lands above; no leftover fragments of the typed prefix.
- PASS: clean import + exact span. FAIL: leftover characters.
- Report: the resulting lines (paste them).

**T67. [IC05] Popup keyboard navigation.**
- Trigger completions (type `ad` in `test_math.py`). Press the keyboard's **Down, Down, Up** (arrow keys; use the accessory bar if your keyboard lacks arrows).
- Expect: the highlight moves through the list; **Tab** accepts the HIGHLIGHTED row — not row 1.
- PASS: navigation + highlighted accept. FAIL: Tab always takes the first row.
- Report: one line.

**T68. [IC06] Enter behavior.**
- Popup VISIBLE (trigger completions) → press **Enter** → expect: the selected item is accepted AND no newline is inserted.
- Popup CLOSED → press Enter → a normal newline.
- PASS: both behaviors. FAIL: popup-Enter inserting a newline line, or closed-Enter eating the newline.
- Report: one line.

**T69. [IC07] Server ordering respected.**
- In `util.ts` type `x.` on a new line after creating `const x = { alpha: 1, zeta: 2, mid: 3 };` — accept nothing; watch the member list order.
- Expect: the server's own order (alphabetical for tsserver members) is respected — no fuzzy-score reshuffle.
- PASS: server order visible. FAIL: visibly reshuffled.
- Report: the order you saw.

**T70. [IC09] Signature help + popup coexist.**
- In `test_math.py` type `add(` → the parameter hint appears. Keep typing `1` → the completion popup may open → BOTH visible simultaneously: hint ABOVE the cursor line, popup BELOW.
- PASS: both coexist without one hiding the other. FAIL: one erases the other.
- Report: one line (screenshot ideal).

**T71. [IC10] Curated hover fallback.**
- Kill the TS/Python LSP (or open a language with no server, e.g. a `.sh` file or plain `.txt` named `notes.md`). Hover (long-press) over a common keyword (`val`, `fun`, or `def`).
- Expect: a curated doc popup appears (the built-in hover content) — not silence.
- PASS: popup appears. FAIL: nothing.
- Report: which popup showed.

**T72. [IC11] Detail panel follows the highlight.**
- Trigger completions where a LOCAL suggestion shares a label with an LSP one (type `ad` — the local word `add` and LSP items). Arrow-highlight each in turn and look at the detail/doc panel.
- Expect: the doc shown belongs to the SELECTED item, even when labels collide.
- PASS: correct doc per selection. FAIL: same doc for both / wrong item's doc.
- Report: one line.

**T73. [IC12] No cross-language recency.**
- In `test_math.py` accept the completion for `add`. Then open `Main.kt` and type `ad`.
- Expect: the Kotlin list is ordered fresh — no Python-recent-`add` boost pushing items up.
- PASS: no cross-language carryover. FAIL: suspicious reordering.
- Report: one line.

**T74. [IC13] Document-word completions.**
- In `test_math.py` add a function using an unusual identifier: type `def myCustomThing():
    pass` at the end. In a DIFFERENT function (or the test file top), type `myCust`.
- Expect: a "word from this document" suggestion for `myCustomThing` appears.
- PASS: doc-word suggestion present. FAIL: absent.
- Report: one line.

**T75. [IC14] Call-context signature boost.**
- In `test_math.py` type `foo("` then later an identifier inside that string context, then elsewhere `bar(` outside strings.
- Expect: the call-argument boost (signature-shaped items ranked up) ONLY when genuinely inside call parens — not when a bare `(` sits inside a string literal before the cursor.
- PASS: boost gated. FAIL: boost inside string contexts.
- Report: one line.

**T76. [IC04] Auto-import offset + honest failure.**
- Do T66's accept again (auto-import inserts a line ABOVE the cursor) → the remaining inserted text lands at the RIGHT offset (cursor ends after your accepted word, not a column early/late).
- Break the import path (rename `util.ts` to `utilX.ts` temporarily, trigger the auto-import) → the Output/lsp channel shows a `[AutoImport]` failure line — never silence.
- PASS: right offset + logged failure. FAIL: shifted text or silent failure.
- Report: the offset behavior + the failure line.

**Part C4 — Editor store, jumps, search (T77-T96)**

**T77. [PLAN A, G03] Per-file state survives tab switches.**
- Open `test_math.py`, wait for squiggles. Switch to `Main.kt`, then BACK to `test_math.py`.
- Expect: the squiggle ranges reappear IMMEDIATELY (restored from the store — no visible re-lint delay).
- Add a gutter bookmark (tap the gutter/bookmark on a line) in `test_math.py` → leave the editor entirely (go to the Terminal or another side panel) → return → bookmark still there.
- PASS: instant restore + bookmark survival. FAIL: blank-then-lint flash, lost bookmarks.
- Report: one line.

**T78. [G03] Same file, different spelling = same buffer.**
- With `test_math.py` open in the editor, in the terminal run: `echo "# touched via other spelling" >> ./test_math.py` (relative path spelling).
- Expect: the editor/terminal agree it's the same file — no dirty-state confusion, no duplicate-tab ghost, the editor picks up the external change cleanly (refresh/reload prompt or auto).
- PASS: single buffer identity. FAIL: the relative write created a split identity or stale cache.
- Report: what the editor did.

**T79. [PG01] Gold band jump behavior (unchanged visuals).**
- Go to Line (the editor's goto-line bar): enter line 5 → the gold band appears ON line 5, blinks ~6s, auto-clears at ~5s — exactly like before the extraction. Tap a Problems row and a test result row: same band behavior, correct lines.
- While the band blinks, type and scroll: no perceptible stutter (the whole-editor churn is gone).
- Jump to a line under a pinned/sticky header (if your file has folding) → band still lands on the correct line.
- PASS: right line, right timing, smooth. FAIL: wrong line, jank, band never clears.
- Report: one line (screenshot of the band welcome).

**T80. [PG12] Second jump's band wins.**
- Trigger jump 1 (Go to Line 5), then within ~2 seconds trigger jump 2 (Go to Line 10, or tap a test row).
- Expect: the SECOND jump's band blinks the FULL 6 seconds — the earlier timer no longer kills the new band.
- PASS: full blink on jump 2. FAIL: band cut short (~1s) because of the earlier jump.
- Report: one line.

**T81. [SR09] Palette/Save menu honesty.**
- Open the command palette (⌘/Ctrl+P-style — the palette entry point) → "Go to File" → pick a file → the PALETTE STAYS OPEN (per the row's expectation — verify it does or note the behavior you see).
- Edit-menu → **Save File** on a dirty file → it ACTUALLY saves (dirty dot clears; content on disk changes — verify with `cat` in terminal).
- PASS: save real; palette behavior as described (note any difference). FAIL: fake save.
- Report: the disk content check.

**T82. [SR03, SR04] Search & replace honesty.**
- In the editor find/replace (the search panel): search `x` with **Aa (match case) OFF** in a file containing both `x` and `X`. **Replace All** → `y`.
- Expect: lowercase hits ARE replaced (the old bug left them untouched) — case-insensitive replace really replaces.
- Restore the file (undo). Then make one project file unreadable (chmod 444 a scratch file — if awkward, use a file in a read-only dir), run a folder-wide Replace All → a RED failure line appears in the search panel for the unreadable file; other files still replaced; an OPEN tab of a replaced file shows the new content WITHOUT reopening.
- PASS: both. FAIL: silent misses, no red line.
- Report: the red failure line.

**T83. [SR01] Hit-line jump lands on the match.**
- Folder search: search `return` in the project → tap a hit line.
- Expect: the jump lands on the exact hit line in the right file (regex/wildcard-aware — search `test_.*` too if the panel supports it, and tap a hit: right line).
- PASS: precise landing. FAIL: opens file at top / wrong line.
- Report: one line.

**T84. [SR05, SR11, SR07] Symbol search.**
- Open the symbol search panel (Ctrl/Cmd+T-style). Search `add`.
- Expect: workspace symbols listed; tap one → file opens AND jumps to the symbol's line. Try a symbol whose path contains `+` or a space (e.g. `p5 spacey` project's `brokenA`) → still resolves the right file.
- Indexer warm-up: immediately after opening the app, run a symbol search while the indexer is still warming → results may be empty at first; when indexing completes the results POPULATE WITHOUT retyping your query.
- PASS: jumps land; warm search populates. FAIL: dead results / stuck empty.
- Report: one line per sub-check.

**T85. [SR06] Symbol panel freshness.**
- Close and reopen the symbol search panel quickly after opening a new file.
- Expect: the panel is ready/fresh on open (signal-driven) — no stale remembered list from a previous file, no waiting for a poll.
- PASS: fresh. FAIL: stale list. Report: one line.

**T86. [G08] Per-file find state.**
- In file A (`Main.kt`) run a find for `return`, close the find bar. Switch to file B (`util.ts`), reopen find.
- Expect: B shows ITS OWN last query (or empty) — NOT A's `return`.
- PASS: isolated state. FAIL: query bled across files.
- Report: what B showed.

**T87. [G06] Split views share history.**
- Split the editor on `test_math.py` (two views of one file). Type a character in view A (adds an undo step). Tap UNDO in view B.
- Expect: A's edit steps back — undo behaves identically from either view (one shared history, no desync).
- PASS: cross-view undo works. FAIL: undo does nothing from the other view.
- Report: one line.

**T88. [G07] Preview freshness after chat apply.**
- Ask the AI chat to make a small change to `util.ts` (e.g. "rename the function in util.ts to renamedThing"), approve the apply.
- View `util.ts` through the Explorer's PREVIEW (single-click) → content is POST-apply (the new name), never the stale pre-apply buffer.
- PASS: fresh content everywhere. FAIL: stale preview.
- Report: one line.

**T89. [G02] Undo chat apply + snapshot restore revert visibly.**
- Chat: **Undo last Apply** → the `util.ts` TAB visibly reverts (old function name back) — no stale buffer, no reload needed.
- Then: edit a file, wait ~30s for a local snapshot, open the Timeline (or Explorer Local History) → restore the earlier snapshot → the tab visibly reverts to that content too (both restore surfaces).
- PASS: both reverts visible in the open tab. FAIL: tab shows old content until manual reopen.
- Report: one line per surface.

**T90. [G01, G10] Read-only save = honest error.**
- `chmod 444 util.ts` in terminal → edit it in the editor → File → Save (or ⋯ → Save).
- Expect: an ERROR notification ("Disk write failed"-style), the dirty marker STAYS — never a fake "Saved ✓". Fix with `chmod 644 util.ts` → save → dirty clears.
- PASS: honest error + recovery. FAIL: false success.
- Report: the notification text.

**T91. [G01] Kill mid-edit, disk never lies.**
- Edit a file (dirty), then force-stop the app WITHOUT saving → relaunch → accept the restore prompt if offered, else reopen the file.
- Expect: the DISK content is unchanged (never claimed saved); the restore dialog offers the recovered edits; declining keeps disk untouched.
- PASS: honest disk + restore offer. FAIL: dirty content silently on disk.
- Report: what disk had after relaunch (`cat` line).

**T92. [G05, DG09] Frame/TODO jumps land on the line.**
- Debug a Python test with a breakpoint (T45 flow), pause, open the VARIABLES/stack panel → tap a DIFFERENT stack frame (e.g. the pytest runner frame).
- Expect: the jump lands ON that frame's line — not line 1, not the wrong file. Tap a breakpoint row → same precision. Then tap a TODO row (a `// TODO: check jump` comment in Main.kt listed in a TODO panel if present) → file + exact line.
- PASS: all three land precisely. FAIL: line-1 or wrong-file jumps.
- Report: one line per jump.

**T93. [TM03, TM05] TextMate colors + fallback.**
- Open `Main.kt` with TextMate highlighting ON (In-Project Settings → the TextMate toggle).
- Expect: token colors match the bundled Dark+ theme — comments in greenish `#6A9955`, strings in reddish `#CE9178`, keywords in bluish `#569CD6` — NOT the flat generic palette. Screenshot for me; I'll compare against the exact palette.
- Then open `util.ts` (a language WITHOUT a bundled grammar) → highlighting falls back to the built-in highlighter CLEANLY (still colored, no blank file).
- PASS: themed Kotlin, clean TS fallback. FAIL: blank highlight or wrong palette.
- Report: a screenshot of Main.kt + a screenshot of util.ts.

**T94. [TM06] TextMate toggle works.**
- In-Project Settings → toggle "TextMate Highlighting" OFF → edit the file → highlighting switches to built-in. Toggle ON → back on the next edit. No restart needed.
- PASS: toggle effective. FAIL: dead toggle.
- Report: one line.

**T95. [TM01/TM04 regression] Highlighting stability.**
- Scroll a ~50+ line file up and down for ~20 seconds, editing occasionally.
- Expect: stable highlighting, no drift (colors bleeding across lines), no flicker.
- PASS: stable. FAIL: any drift. Report: one line.

**T96. [XG07] LSP install lands in Extensions history.**
- Extensions tab → History (the package-manager history section): your earlier Kotlin/Python LSP installs appear with real completion status.
- Break one: airplane mode ON, install a small LSP/package that fails → the History entry carries the failure detail (the real E:/error line, not just "failed").
- PASS: entries + honest failure detail. FAIL: empty history or bare "failed".
- Report: the failure line.

---

# PHASE 5 — SETUP D: perf reads (Tests 97-102)

Do this in the SAME session as Phase 4 (same project open), and SEND ME THE LOG LINES — they feed the recomposition ranking backlog.

**T97. [PG08, PG09, PG10, PG13] Startup perf marks.**
- Fully close the app. Cold-start it, open the same project.
- Open the **Output** tab and look for `[perf]` lines: a startup chain (app-create → prefs-backup-restore → stores-init → textmate-init → onCreate-end → shell-first-frame) with elapsed times and per-phase deltas; a shell-first-frame total; an MCP discovery duration line on your FIRST chat of the session.
- Then expand a large folder in the Explorer → a tree-build `[perf]` line appears (duration + node count).
- PASS: the chain completes end-to-end (every phase logged, not just some). FAIL: gaps in the chain or no [perf] lines at all.
- Report: PASTE ALL THE [perf] LINES verbatim (this is the one test where the paste is the deliverable).

**T98. [PG03] Snapshot cadence.**
- Edit a project file, then leave the editor idle 2+ minutes. Then in the terminal:
`ls .versionhistory/ | tail -5`
- Expect: roughly ONE new snapshot per recent edit — NOT a new entry every 20 seconds of idleness. Also confirm snapshots capture files in nested source dirs, but NOTHING from hidden/dependency dirs (.git, node_modules, build).
- PASS: sane cadence, correct scope. FAIL: snapshot spam or missing edits.
- Report: the ls output + a note on the time gaps.

**T99. [PG14] Low-RAM gate.**
- Open a heavy project + a running terminal + the browser/preview; get the status-bar RAM readout to turn red (low) — if you can't get it red today, mark NOT RUN.
- When red: watch `.versionhistory` — NO new snapshot files appear while RAM is low. Close some load → RAM recovers → snapshots resume.
- PASS: pause + resume. FAIL: snapshot loop kept churning at low RAM.
- Report: red/not-red + what the snapshot dir did.

**T100. [PG06] Session save is active-session only.**
- In a multi-session project (create 2-3 chat sessions): send messages in one, rate a reply.
- Expect: switching/rename/restore all work correctly, no jank while persisting (persist re-serializes only the active session), and the OTHER sessions' transcripts untouched when you reopen them.
- PASS: correct behavior, no jank. FAIL: cross-session contamination.
- Report: one line.

**T101. [PG07] Notification history debounced.**
- Trigger a build (notification burst) → within a few seconds force-stop → relaunch → the build notifications that fired persist in history (the debounce didn't lose them).
- Dismiss a notification → relaunch → it reappears (dismissal persisted). Toggling notification settings → applies immediately.
- PASS: no lost bursts, dismissal sticky. FAIL: lost or resurrected-when-dismissed rows.
- Report: one line.

**T102. [PG11] Op strip scrolls per line.**
- Start a long install (`apt-get install -y ...something small-but-chatty` or update lists in the Package Manager).
- Expect: the op strip auto-scrolls per output line and STAYS scrollable (you can grab it and scroll back mid-run); after completion, close and reopen the strip → same content, scrollable.
- PASS: per-append scroll + manual scroll works. FAIL: stuck at top, or overflow-hack behavior.
- Report: one line.

---

# PHASE 6 — SETUP E: SCM torture (Tests 103-120)

**Get there:** side panel → GIT/Source Control panel on project `p5-poly` (run `git init .` in its terminal first if the pane doesn't offer init; the Publish flow can also init). Create the test files:
- `a.txt` — `echo alpha > a.txt`
- `b.txt` — `echo beta > b.txt`
- `c.txt` — `echo gamma > c.txt`

**T103. [SG01] Scoped commit + smart-commit ask + nothing pending.**
- Edit all three files, stage ONLY `a.txt` via its file row's **+** button → type a message → **Commit** → verify in terminal: `git log -1 --stat` → ONLY a.txt in that commit.
- Unstage everything (keep b/c dirty), Commit → the "Stage all and commit?" dialog appears showing the RIGHT count (2) → **Cancel** → nothing committed, nothing staged → run `git log -1 --stat` again (unchanged). Redo → **confirm** → commits all (verify).
- Clean tree (commit everything) → Commit → honest "Nothing to commit — no staged changes"-style snackbar, no dialog, no git call.
- PASS: all three flows honest. FAIL: commit swept unstaged files, or silent no-op.
- Report: the stat of each commit you made.

**T104. [SG01] Publish states its staging scope.**
- Open the Publish flow (on a fresh un-pushed repo) → the dialog shows the "Publish stages ALL pending file(s)"-style line → publish works end-to-end (repo created, pushed).
- PASS: line present + publish succeeds. FAIL: hidden scope or failed publish.
- Report: the dialog text.

**T105. [SG03, SG02] Restore is confirmed AND reversible, on both surfaces.**
- Edit `a.txt` (`echo changed > a.txt`), wait ~30s for a snapshot. Open the Timeline → tap **Restore** on the earlier snapshot → a CONFIRM dialog appears naming the file → confirm → content restored AND a NEW snapshot entry appears (the pre-restore capture) → tap Restore on THAT → you get `changed` back (fully reversible).
- Same flow in Explorer → Local History dialog (right side): confirm + reversible again. On a read-only target (`chmod 555` won't work on a file; instead restore into a dir you made read-only, or just verify the ERROR case: make the file read-only with chmod 444 before restoring) → expect ERROR "file unchanged"-style, file untouched.
- PASS: both surfaces confirm + reversible; honest error. FAIL: instant overwrite with no capture.
- Report: reversibility on both surfaces (yes/no each).

**T106. [SG06] Auto-refresh without flicker.**
- Keep the GIT panel open and visible. Then: edit + save a file in the editor; delete a file in the Explorer; run `git commit -am "x"` in the terminal.
- Expect: the pane's change count/branch/ahead-behind update WITHOUT manual refresh (immediately for .git-touching ops via the observer; within ~30s for pure worktree edits via the ticker). NO Loading-spinner flicker during these silent refreshes.
- Close the panel → make more changes → no polling continues (nothing drains battery — you can't see this directly; just note the pane doesn't stale on reopen).
- PASS: fresh without flicker. FAIL: stale counts or spinner strobing.
- Report: which triggers updated and how fast.

**T107. [SG07] Two-spawn status, no lock fights.**
- Open the GIT panel → status loads in ~1-2 seconds (not multi-second). While the panel is open, run `git status` in the terminal → works instantly, no `index.lock` error.
- PASS: fast + concurrent. FAIL: multi-second status or lock errors.
- Report: roughly how long the status load took.

**T108. [SG08] Subdirectory-of-outer-repo timelines.**
- If you have a project that sits inside an outer git repo (or make one: `git init` in `/storage/emulated/0/p5-outer`, then create project `p5-poly` inside it): open the Timeline for a committed file in `p5-poly`.
- Expect: GIT history rows DO appear (the old code said "No timeline available" for subdirectory repos). Tap a commit row → a diff dialog shows THAT commit's changes to THIS file. A file OUTSIDE the repo shows no foreign history (honest empty).
- PASS: rows + tap + honest empty. FAIL: dead rows or foreign history.
- Report: one line.

**T109. [SG09] Local vs remote branch labels.**
- Create a local branch `feature/test` (terminal: `git checkout -b feature/test`). Open the Branches dialog.
- Expect: `feature/test` shows LOCAL; `origin/main` (after a push) shows remote. A local branch named like a remote (e.g. `origin-main` if you create one) must still show LOCAL.
- PASS: correct labels. FAIL: mislabeled.
- Report: the labels you saw.

**T110. [SG10] Publish origin pre-check.**
- On your pushed repo (it HAS origin now) → open Publish again.
- Expect: an honest abort BEFORE anything happens ("already has an origin remote — Publish would orphan a new GitHub repo; use Push"-style). Then check GitHub (or ask me to) → NO new repo created.
- PASS: abort, nothing created. FAIL: a second GitHub repo appeared.
- Report: the abort message.

**T111. [SG11] Rename diff paths.**
- `git mv a.txt a-moved.txt`, stage it, view the diff in the pane.
- Expect: the OLD path line shows WITHOUT the `a/` prefix (reads `a.txt → a-moved.txt` or `--- a.txt` without `a/`).
- PASS: prefix stripped. FAIL: `a/a.txt` visible.
- Report: the header lines of the diff.

**T112. [SG13] HEAD badge + ff-only pull.**
- Commit once more → open the History dialog → the LATEST commit shows a HEAD badge (file-filtered logs stay honestly unbadged — note if you check one).
- Diverge a branch (terminal: `git checkout -b diver; echo z > z.txt; git add .; git commit -m z; git checkout main; echo y > y.txt; git add .; git commit -m y`) → Pull → expect an HONEST failure (no surprise merge commit) with explicit Merge/Rebase buttons offered → tap **Merge** → merges cleanly.
- PASS: badge + honest failure + working merge. FAIL: auto-merge during pull.
- Report: the failure message + merge result.

**T113. [SG14] Commit identity honesty.**
- Sign OUT of the app (GitHub sign-out in Settings) → in the terminal set no identity (`git config --unset user.name; git config --unset user.email` if set) → stage a file → Commit.
- Expect: an HONEST typed failure with fix instructions (NOT a silently fabricated "VN Code User" identity, NOT a crash). Check `git log -1` → nothing committed.
- Sign back in → if an old fabricated identity was configured, Commit upgrades it in place to your signed-in account (check `git config user.name` after); if you had a REAL configured identity, it stays UNTOUCHED.
- PASS: honest failure, in-place upgrade, real configs preserved. FAIL: fabricated commit or clobbered real identity.
- Report: the failure text + the config values before/after.

**T114. [SG15] Operation gating.**
- Start a slow git op (e.g. a big commit or a pull), and WHILE it runs tap + on a file row / Stage All.
- Expect: a "Wait for current operation"-style snackbar; the row icons disabled while busy; no `.git/index.lock` crash.
- PASS: gated + honest. FAIL: concurrent gits racing (look for index.lock errors in the terminal).
- Report: the snackbar text.

**T115. [SG04] No credential leak in push cmdline.**
- In the terminal run: `while true; do cat /proc/*/cmdline 2>/dev/null | tr '\0' ' ' | grep -a git && echo ---; sleep 0.2; done` (starts a watcher — Ctrl+C stops it).
- In the SCM pane, Push (with your GitHub token-based auth).
- Watch the terminal for the git push process args: expect a `/tmp/.gitcred-<hex>` helper path and NO base64 `Authorization` header, NO token text. After the push: `ls /tmp/.gitcred-*` → empty (helpers cleaned up).
- Stop the watcher (Ctrl+C).
- PASS: push succeeds, no token in cmdline, helpers gone. FAIL: any credential text visible.
- Report: a sample of the watcher's git lines (redact nothing needed if clean; if you see token-like text, DO NOT paste it — say "token visible in cmdline").

**T116. [SG16] Private clone via credentials.**
- SCM → Browse My Repos → clone one PRIVATE repo of yours.
- Expect: clone succeeds with the stored credentials (no raw auth error). Same T115 cmdline check applies if you run the watcher again.
- PASS: clean private clone. FAIL: auth error.
- Report: cloned yes/no.

**T117. [SG02] Restore error path (read-only target).**
- `chmod 444 b.txt` → Timeline/Local History → Restore an earlier b.txt snapshot → expect ERROR "file unchanged"-style notification, the file on disk untouched, the dialog stays usable. `chmod 644 b.txt` → Restore again → SUCCESS + the editor refreshes.
- PASS: honest failure then success. FAIL: fake success while file unchanged.
- Report: both notifications.

**T118. [IG02] Untrusted project's scheduled task blocked.**
- In `p5-untrust2` (the untrusted one — create a new one if you trusted it), schedule a task via the app's scheduler (ask the chat: "schedule a task that runs `echo hi` in one minute").
- Expect: at fire time the task does NOT run; a "Scheduled task blocked"-style notification appears. Trust the project → the next occurrence (re-schedule it) runs.
- Also: tasks you created BEFORE this update in a trusted project still run (grandfathered).
- PASS: blocked → runs after trust; grandfathered intact. FAIL: ran while untrusted.
- Report: the notification text.

**T119. [TB01] Dismiss keeps backups.**
- Make a file dirty, force-stop the app, relaunch → the autosave-restore dialog appears → tap OUTSIDE the dialog (dismiss) → the dialog closes.
- Force-stop → relaunch → the dialog appears AGAIN (the backup files were KEPT, not deleted by the stray dismiss). Choose **Restore** → content + dirty state restored into the tab.
- PASS: dismiss keeps, restore works. FAIL: one stray tap destroyed the backup.
- Report: dialog reappeared (yes/no) + restore result.

**T120. [TB01] Autosave for a not-open file is kept, not claimed.**
- With some file X not open in any tab: simulate leftover autosave by force-stopping while two files are dirty, then relaunch, and close the dialog; open only ONE of the files afterward, force-stop, relaunch.
- Expect: the restore dialog reports honestly per-file ("restored / kept (file not open this session)"-style WARNING counts) — it never claims "Edits restored ✓" for files that couldn't be restored.
- PASS: honest per-file counts. FAIL: blanket success claim.
- Report: the exact count message.

---

# PHASE 7 — SETUP F: chat / AI surface (Tests 121-139)

**Get there:** open the AI chat panel on project `p5-poly`.

**T121. [CH04] Attachments survive restart.**
- Attach a file via the chat chip (📎/attach) AND send a second message containing a `#util.ts` path token. Let both replies finish.
- Force-close the app → reopen the same chat session → the thread (with both messages) is restored.
- Send a follow-up in the restored thread: "what did I attach earlier?" → the model's answer reflects the attached file's CONTENT (it didn't lose the attachment context on restore).
- PASS: restored thread + attachment-aware follow-up. FAIL: messages gone or attachment-blind model.
- Report: the follow-up answer (short).

**T122. [CH06] Truncation markers.**
- Ask the chat to run: `seq 1 60000` (long output).
- Expect: the tool result carries a `[... TRUNCATED — showing N of M chars]`-style marker. Then ask it to read `big.pcap`-sized text... if too heavy, ask it to read a file you make >8000 chars: `python3 -c "open('big.txt','w').write('x'*9000)"` then "read big.txt" → same truncation marker. A folder-wide search across >500 files (copy junk: run `for i in $(seq 1 520); do echo x > f$i.txt; done` first, ask to search `x` in the project, then clean up with `ls f*.txt | xargs rm`) shows the scan-cap note.
- PASS: all markers present. FAIL: silent cut-offs.
- Report: the marker lines.

**T123. [CH07] Queue doesn't drop messages.**
- While a reply is STREAMING, send message A, then message B.
- Expect: a queue chip appears ("Queued: A (+1 more queued)"-style). When the streaming reply ends: A sends FIRST, then B — both delivered, both answered, none replaced.
- Tap the × (cancel) while A+B are queued → the chip clears and NEITHER sends.
- PASS: FIFO delivery + cancel honored. FAIL: a message vanished.
- Report: the chip text + delivery order.

**T124. [CH08] Export failures are honest.**
- With NO project open (HOME screen): try Export (the chat export action) → expect "Export failed — no project open"-style (never a fake success file).
- Open the project; make the exports dir read-only (`chmod 555 .codespace/exports` — create it first: `mkdir -p .codespace/exports`), export → the error names the WRITE failure specifically (disk/permissions), not the project.
- PASS: both honest. FAIL: silent failure or success-shaped nothing.
- Report: both messages. Restore perms after (`chmod 755 .codespace/exports`).

**T125. [CH09] Approval auto-timeout honesty.**
- Set tool approval mode to MANUAL (if the app has a flow gate setting) → trigger a tool approval → IGNORE the card for 10 minutes (screen on — you can run other phases meanwhile and come back).
- Expect: the turn ends with an honest auto-timeout message — NOT "rejected by user" — and the chat is usable after (not stuck in a loading state).
- Trigger two gated calls back-to-back → the second approval card appears ONLY after the first resolves.
- PASS: honest timeout + serialized cards. FAIL: mislabeled rejection or stuck chat.
- Report: the timeout message.

**T126. [CH10] MCP discovery honesty.**
- Add an MCP server with a bad command (e.g. command `nonexistent_binary_xyz`) → Refresh → its row shows "Discovery failed: ..."-style inline detail.
- Delete a server whose tools WERE discovered → ask the chat what tools it sees → its mcp_* tools no longer appear to the model; re-add the server → tools return after the next chat/Refresh.
- PASS: both flows honest. FAIL: zombie tools.
- Report: the discovery-failed line.

**T127. [CH11] MCP hangs bounded at 90s.**
- Add an MCP server whose command hangs (e.g. `sleep 9999`) → trigger a tool call on it → tap **Stop** → the turn ends before the 90s bound (mid-call cancel works), chat usable.
- Re-trigger and let it hang → past ~90s the honest timeout message appears (chat stays usable, no infinite spinner).
- PASS: cancel + bound. FAIL: unkillable turn.
- Report: the timeout message. Delete the hanging server after.

**T128. [CH12] Key labels persist, not in plain prefs.**
- Set a custom label on a key slot (chat settings → the AI keys section) → force-close → relaunch → the label persists in the UI.
- Check the storage: if you can register the internal root (like Fix-B7), look in shared_prefs for the chat_key_pool file → NO "labels" entry in PLAIN prefs (labels live in the secure store now).
- If you can't reach internal storage: the persistence half alone is the on-device check; mark the storage half N/A.
- PASS: label persists; no plain labels entry. FAIL: label lost.
- Report: persistence yes/no + storage finding if reachable.

**T129. [CH13] Multi-session persist + delete.**
- With 3+ saved chat sessions: send a message in one → watch for UI jank (persist is O(active session) — should be none).
- Delete a session → force-close → relaunch → it STAYS deleted. Open an old session from before the app update → it loads (legacy migration).
- PASS: no jank, delete sticky, legacy loads. FAIL: resurrected session or legacy loss.
- Report: one line.

**T130. [CH14] MCP delete with live session warns.**
- With a CONNECTED MCP server (the good one from T126): tap its delete.
- Expect: a confirm dialog naming BOTH the session stop AND the secret wipe. Cancel keeps everything. Delete removes the server AND ends its session.
- PASS: dialog + both outcomes. FAIL: silent session kill.
- Report: the dialog text.

**T131. [CH03] Long-command approval card.**
- In MANUAL flow: trigger a tool call with a command >160 chars (ask the chat: "run this exact command: <paste a 200-char echo chain>").
- Expect: the card shows ~4 lines + a "Show all (N chars)"-style toggle → expanded = the FULL text, scrollable. A "Trust this project" button appears ONLY if the project is untrusted; tapping it both trusts AND approves.
- PASS: truncated view + full toggle + conditional trust button. FAIL: wall-of-text or missing toggle.
- Report: the toggle label.

**T132. [IG15] Per-call consent for connectors.**
- In AUTO flow (auto-approve settings on): ask the chat to use a connector (use_connector-style tool) → the approval card appears EVERY time (not just once), showing full service/method/endpoint.
- PASS: repeated honest cards. FAIL: approved once then silent forever.
- Report: how many cards for 2 calls.

**T133. [CH02] Guest-path translation.**
- In the guest terminal, copy a guest-style path (e.g. `/root/project/p5-poly/Main.kt` — print it: `echo /root/project/p5-poly/Main.kt`).
- In the chat, ask: `read the file /root/project/p5-poly/Main.kt` → expect: the read SUCCEEDS, and the Output tab shows a `[CH02] file-tool path translated guest->host`-style line.
- Then ask it to write a NEW file at a guest path whose parent exists (`/root/project/p5-poly/newfile.txt`) → the file lands INSIDE the project (check Explorer), NOT on the Android storage root; no stray `/root` directory created on shared storage.
- PASS: translation + no stray dirs. FAIL: "Could not read file" or files landing outside.
- Report: the [CH02] line + where newfile.txt landed.

**T134. [IG13] Bogus cron refused.**
- Ask the chat: "schedule a task with cron expression 'hourly'" (or "0 9 *").
- Expect: an honest "Unsupported cron form ... NOT scheduled"-style refusal and the task does NOT appear when you list tasks ("list my scheduled tasks").
- PASS: refused + absent from list. FAIL: task created anyway.
- Report: the refusal line.

**T135. [IG11] Entity id collision honest.**
- Ask the chat to create an entity record twice in quick succession (same command twice, fast).
- Expect: BOTH records exist afterward (id bumped-until-unique — no silent overwrite). Verify: ask it to list those records → two entries.
- If you can reach the entity files (internal storage via Fix-B7 root): corrupt one record file by hand → ask for an update-all-style operation → expect "Skipped 1 unreadable record(s)"-style honest reporting.
- PASS: both created; skip reported. FAIL: one record silently eaten.
- Report: the list result.

**T136. [CH05] Staging refusal, not silent disk write.**
- Block the staging dir: `mkdir -p .codespace/staging && chmod 555 .codespace/staging` (adjust path if the app uses a different staging location — check the refusal text).
- In AGENT mode, ask the chat to write a file ("create newfile2.txt with content hello").
- Expect: the model receives a typed REFUSAL ("write_file REFUSED — staging failed"-style), the file on disk is UNCHANGED/absent, and NO pending-changes card appeared.
- PASS: refusal, no direct write. FAIL: file written directly anyway.
- Report: the refusal the model echoed. Restore perms (`chmod 755 .codespace/staging`).

**T137. [CH01] Apply-undo with a real failure, retried.**
- Ask for a multi-file change ("rename the function in util.ts and Main.kt to betterName") → approve apply → **Undo last Apply** → "Restored N file(s)".
- Force a checkpoint failure: `chmod 555 .codespace` (or make the checkpoint dir read-only) → Undo again → per-file "NOT restored: ... (reason)" lines + "Failed entries kept — Undo can be retried"-style note.
- Fix perms → Undo again → restores.
- PASS: per-file honesty + retry works. FAIL: blanket "No checkpoints found" while checkpoints existed (the original bug).
- Report: the per-file lines (short).

**T138. [IM01, IM02, IM03] Gemini image gen: header auth, cap, model override.**
- Set your Gemini API key in settings (AI keys section) if not already.
- Ask the chat to generate a small image ("generate a 100x100 image of a red square").
- Expect: it works via header auth (no visible `?key=` in any echoed request/URL — check the Output tab if the request is rendered).
- In-Project Settings → AI Agent → **Gemini Image Model**: change it to a newer valid model id → next generation uses it. Put GARBAGE in the field → the failure comes back as an honest API error naming the model, not a generic crash.
- PASS: three behaviors. FAIL: key in URL, or dead override.
- Report: worked/failed per sub-check.

**T139. [IG07] Hub OAuth via external browser.**
- Open the Connectors/Hub surface → connect a REAL connector (Google or Slack).
- Expect: an EXTERNAL browser app opens (NOT an in-app WebView) → finish the sign-in there → WITHOUT reopening the sheet, return → the connector row flips to Connected within ~5 seconds (the poll) + a success toast.
- Disconnect → reconnect → same flow works again.
- PASS: external browser + poll flips. FAIL: in-app webview (the old bug), or manual refresh needed.
- Report: browser external (yes/no) + how the row updated.

---

# PHASE 8 — SETUP G: settings, backup, scheduler (Tests 140-156)

**Get there:** the gear menu → **Settings (App-wide)**.

**T140. [SK07, SK10] One settings surface, one search box.**
- The gear menu says "Settings (App-wide)" (or equivalent — note the exact label). All three settings surfaces (app-wide, in-project, whatever third surface your build shows) share ONE search box: type `keybinding` → results show section + surface per hit; type `theme`, `format` → same.
- PASS: label correct everywhere; unified search with surface tags. FAIL: stale "In-Project Settings" label or fragmented search.
- Report: the label + a screenshot of one search.

**T141. [SK09, SK08] Rebind + conflict handling.**
- Settings → Keybindings: pick any action → tap its **Record** chip → the capture dialog opens → press a hardware key (e.g. volume-down won't reach the app — use a keyboard key if you have one; otherwise note NOT RUN for the capture half and test conflicts only).
- Conflict: type/record a combo that's already bound to another action (e.g. set the same key on two actions) → a reassign DIALOG appears asking before stealing the binding. Cancel → original binding untouched.
- PASS: dialog before steal; Esc cancels the recorder. FAIL: silent steal.
- Report: the dialog text.

**T142. [SK11] Theme persistence.**
- Pick a specific dark theme (not just "dark mode" — a named dark theme if the app offers one) → toggle dark mode OFF → ON again → your LAST dark theme is restored (not flattened to a default dark).
- PASS: remembered. FAIL: reset to default.
- Report: theme name before/after.

**T143. [SK12] Settings backup export/import.**
- Settings → Settings Backup section → **Export to clipboard** → paste it in your notes app (it's JSON — confirm it's non-empty and looks like settings).
- Change a setting, then **Import** (paste the JSON back via the SAF file picker flow — save the clipboard to a .json file in Download first if the import wants a file) → the exported settings are restored.
- PASS: round-trip works. FAIL: empty export or import no-op.
- Report: worked yes/no.

**T144. [SK05] Clear All Data states its exact scope.**
- Find **Clear All Data** → read the dialog BEFORE confirming.
- Expect: it lists the exact scope (keybindings, settings.json, notifications, workspace memory + the original three prefs) AND states what is NOT touched (disk files, container, backups).
- Confirm → those are cleared; the container, projects, and backups are INTACT (open a project after).
- PASS: scoped honestly, container untouched. FAIL: overclaiming dialog (says "All Data") or collateral damage.
- Report: the dialog text + what survived.

**T145. [SK06] Destructive settings verify their write.**
- Disable the app lock (if PIN is set) and/or sign out of GitHub → each action reads back the persisted store before claiming success (i.e., after the action, relaunch → the state really changed).
- PASS: verified-persist semantics (state holds after relaunch). FAIL: action claims success but state reverts.
- Report: one line.

**T146. [SK14] Formatter cross-links.**
- Settings → Formatter Selection → it cross-links/references the format_on_save and TS-related rows (a pointer or inline mention, not a dead reference).
- PASS: the cross-links navigate somewhere real. FAIL: dead mention.
- Report: where the links went.

**T147. [IG03] Scheduler survives kill; corrupted file = clean start.**
- Create a scheduled task (via chat or the Task Scheduler UI) → force-stop the app → relaunch → the task is in the list and fires at its next due time (schedule it 2 minutes out for a quick signal).
- Corrupt tasks: only if you can reach internal storage (Fix-B7 root) — replace the scheduler JSON with garbage → relaunch → app starts CLEAN, task list empty, no crash.
- PASS: persistence + clean corruption recovery. FAIL: task lost or crash loop.
- Report: fired (yes/no) + corruption half if reachable.

**T148. [IG04] No downloads manager.**
- Look at the bottom tab bar: NO DOWNLOADS tab exists, and no reference to a download manager anywhere in settings. The remaining tabs (BACKUP, ARTIFACTS, etc.) all still open their right screens.
- PASS: absent + survivors route. FAIL: dead tab or 404 screen.
- Report: the tab list you see.

**T149. [IG10, IG16] Atomic stores survive mid-write kills.**
- Get a busy chat-save going (send a burst of messages that trigger agent memory writes) → force-stop the app MID-BURST → relaunch → agent memory and scheduled tasks are INTACT, not wiped to empty (the atomic write + fallback worked).
- PASS: intact stores. FAIL: empty memory/tasks after the kill.
- Report: intact (yes/no).

**T150. [RG05] Auto prefs-backup every start.**
- Toggle a setting, relaunch the app. If you can reach the backup dir: `ls /storage/emulated/0/CodespaceIDE/prefs-backup/` → contains *.xml files + settings.json etc.
- Expect: the backup refreshed on THIS start (file timestamps today — check `ls -l`).
- Also verify the RG05 hotfix in the field: relaunch a few times → the app opens normally every time (this is the crash-loop regression check).
- PASS: refreshed + no launch crashes. FAIL: stale backup.
- Report: the ls -l output (a few lines).

**T151. [RG06] Restore applies through live prefs.**
- Change a toggle's value, then run Settings → Backup & Restore → **Restore** (prefs restore) → expect the toggle to LAND at the backed-up value immediately through the prefs API (no restart needed for the value to show — note if a restart was needed, that's a finding).
- PASS: applied live. FAIL: restored only after restart (old bug) or silently lost.
- Report: the toggle before/after.

**T152. [RG09] Cloud backup panel honest when signed out.**
- Sign out → open the Cloud Backup panel → expect a plain "sign in"-style statement (missing sign-in), buttons wired to sign-in (not fake progress).
- PASS: honest. FAIL: spinner forever or fake backup claim.
- Report: the message.

**T153. [RG10, RG11] Session import honesty.**
- Export a session blob if the app offers session export; corrupt its schema by editing one field to a HIGHER version number (edit in any text editor) → import → expect an honest "newer schema"-style refusal (not a crash, not silent import).
- Import an OLD (pre-update) session blob → it loads (legacy migration).
- PASS: both. FAIL: crash on corrupt or legacy loss.
- Report: both outcomes.

**T154. [RG12] Snapshot export states its recovery path.**
- Run the snapshot export (settings/backup section) → the success message honestly states there's NO in-app restore yet + tells you the manual recovery path.
- PASS: honest. FAIL: implies restore exists.
- Report: the message.

**T155. [IG06] Known-hosts checker.**
- Terminal → SSH Manager → Known Hosts → **Check** (with rootfs installed): each profile shows trusted/not-recorded.
- Connect to a NEW host via a profile (any SSH target you can reach — if none today, mark the new-host half NOT RUN) → re-open → Check → trusted now. **Forget** the host → reconnect → openssh re-accepts (TOFU) → trusted again. **Clear all** → Check shows all not-recorded.
- No-rootfs case is only testable on a fresh device — skip unless wiping.
- PASS: statuses track reality. FAIL: always-not-recorded or crash.
- Report: the statuses you saw.

**T156. [OG05] Backend-unreachable fallback is honest.**
- Airplane mode ON (or wrong API base — don't change config; airplane is enough) → open the app → it works offline; open the Connectors Hub → a READABLE "offline / sign in again"-style message (NOT a bare 401 stack).
- Reconnect → sign in again → the Hub works (the fallback token is honestly flagged as needing a real re-login).
- PASS: readable degraded mode + recovery. FAIL: raw 401 or dead UI.
- Report: the offline message.

---

# PHASE 9 — SETUP H: Package Manager / Extensions (Tests 157-168)

**Get there:** side panel → the EXTENSIONS tab (packages + MCP + agent tools sections).

**T157. [XG06] Refresh op strip.**
- Tap **⟳ Refresh** → the op strip shows an UPDATE-LISTS operation with live apt output streaming, ending in a done/failed dot; the run also appears in the History list.
- PASS: streamed + recorded. FAIL: silent spinner.
- Report: the op's title + outcome.

**T158. [XG14] Search results capped.**
- Search a broad term (`lib`) → expect at most ~40 rows, and the list scrolls smoothly (no jank with hundreds of unrendered rows).
- PASS: capped + smooth. FAIL: hundreds of rows rendering choppy.
- Report: roughly how many rows appeared.

**T159. [XG16] Category chips filter featured.**
- On an empty search: the featured list shows category chips; tap a category → filtered list; tap **All** → resets; typing anything → searches everything (not just featured).
- PASS: three behaviors. FAIL: chips are no-ops.
- Report: one line.

**T160. [XG10] Real dpkg versions + upgradable Update.**
- **Installed** tab: each row shows its REAL installed version (cross-check one: `dpkg -l | grep <name>` in terminal).
- For any upgradable package (run `apt-get update` first): an **Update** button appears on its row → tapping it runs the update through the op strip and the version changes after.
- PASS: real versions + working per-pkg update. FAIL: version shows "unknown"/blank or Update does nothing.
- Report: one package's before/after version.

**T161. [XG11] Op strip survives tab switches.**
- Start an install → mid-run, switch to the TERMINAL tab → do something → switch back to Extensions.
- Expect: the SAME op strip, still live, still streaming (process-level state — it didn't reset).
- PASS: same strip. FAIL: strip restarted/blanked.
- Report: one line.

**T162. [XG12] Lock contention classified honestly.**
- Start an apt install in the PACKAGE MANAGER, and WHILE it runs, trigger a second one (e.g. an LSP bootstrap install — T96's flow, or Refresh).
- Expect: the losing op reports the lock-busy state with a readable hint ("another apt operation is running"-style), NOT a raw `dpkg lock` error and NOT a hang.
- PASS: classified + readable. FAIL: raw error or frozen.
- Report: the hint text.

**T163. [XG13, XG15] Remove needs confirm + simulation count.**
- On the `git` package row (or any big meta-package), tap **Remove** → a confirm dialog shows a PACKAGE COUNT derived from the removal simulation (`apt-get -s` — e.g. "this will remove 40 packages").
- **Cancel** does nothing. Confirm on a SAFE package only if you're willing to reinstall it after — otherwise Cancel and just verify the count.
- PASS: dialog + simulation count + cancel honored. FAIL: instant removal or no count.
- Report: the count text.

**T164. [XG08] Failure detail lands in History.**
- Airplane mode ON → try installing a package → the op fails → open **History**: the entry for the failed run carries the real E:/Err: line detail, not just "failed".
- PASS: real error detail. FAIL: bare failure.
- Report: the E: line. Airplane OFF after.

**T165. [XG09] Agent tools card live.**
- The MCP/AGENT TOOLS card shows a tool count (~32) matching the live server (cross-check: `agent_tools | tail -1` or count the entries — roughly).
- PASS: count present and plausible. FAIL: zero/blank count.
- Report: the number on the card.

**T166. [IG08] UNKNOWN is honest under contention.**
- Start a heavy apt install. While it runs, open the Build Environment / toolchain panel → **Refresh toolchain**.
- Expect: rows show the gray **? UNKNOWN** state with a "Probe failed"-style note (the proot is busy — honest unknown), and real values return after the install finishes.
- PASS: UNKNOWN under load + recovery after. FAIL: fake green statuses or permanent unknowns.
- Report: the UNKNOWN note text.

**T167. [IG14] Port discovery in ~5s, no refresh needed.**
- In the guest terminal: `python3 -m http.server 7777 &` → open the Ports panel → the port appears within ~5 seconds WITHOUT tapping refresh.
- Kill it (`kill %1`) → on a later tick it disappears.
- PASS: appears + disappears. FAIL: manual refresh needed.
- Report: seconds until it appeared.

**T168. [XG05] Installed list tracks reality.**
- `apt-get remove -y cowsay` in the TERMINAL (the package from T17) → back in the package manager WITHOUT refreshing → Installed list reflects the removal (or on next refresh at worst — note which).
- PASS: tracks. FAIL: ghost package.
- Report: which behavior.

---

# PHASE 10 — SETUP I: debugging (Tests 169-179)

**Get there:** a Python file with a test (your `test_math.py` + `p5-poly`), the Debug surfaces (Explorer debug button, PSS Debug tab, the Debug bottom tab).

**T169. [DG01] Breakpoints survive process death.**
- Set a breakpoint INSIDE `test_add` (gutter tap the assert line). In the BREAKPOINTS list add a condition or hitCondition (the breakpoint row → condition field: set `hitCondition` to 1 if the UI offers it).
- Fully kill the app (Recents swipe + relaunch) → reopen the project.
- Expect: the breakpoint is STILL in the BREAKPOINTS list with its condition intact.
- PASS: persisted. FAIL: gone or condition dropped.
- Report: present (yes/no) + condition intact.

**T170. [DG03] Disabled breakpoint doesn't stop.**
- Start a debug run (Debug Test on `test_add`), pause at the breakpoint. Now TOGGLE the breakpoint off (its row toggle, or the gutter) while paused → **Continue**.
- Expect: on the next pass (run again to a second breakpoint or re-run the test) the disabled breakpoint does NOT stop.
- PASS: disabled = no stop. FAIL: keeps stopping.
- Report: one line.

**T171. [DG05] Restart stays responsive.**
- With a live debug session: Overflow menu → **Restart** (and also the Explorer debug-panel Restart icon).
- Expect: the UI stays RESPONSIVE throughout (spinner/notification, no ANR dialog), the session restarts, no app close. Same from a COLD project (no proot booted yet) — restart survives the boot wait without freezing.
- PASS: responsive both paths. FAIL: frozen UI / ANR.
- Report: ANR (yes/no).

**T172. [DG06] Failed launch leaves no phantom.**
- Force a failed debug launch: debug a file with a bad interpreter line (`#!/nonexistent` on a .py run config, or debug a file you deleted the guest copy of).
- Expect: honest launch failure; the session PICKER shows NO phantom entry for the failed id.
- PASS: honest failure + clean list. FAIL: zombie session row.
- Report: one line.

**T173. [DG07] Reverse requests answered.**
- Debug a JS test (js-debug attach path from T45) — this emits a DAP reverse request (`runInTerminal`).
- Expect: the session does NOT hang; the log (Output/lsp or debug channel) shows the notSupported response instead of silence.
- PASS: answered + no hang. FAIL: hang at launch.
- Report: the notSupported line if visible.

**T174. [DG09] Guest-path frames jump to host file, exact line.**
- Paused in the Python debug: open VARIABLES/stack → tap a frame whose file path is a GUEST path (e.g. `/root/...` style).
- Expect: the jump opens the CORRECT host file AT the frame's exact line (not project-root-joined guest path, not line 1).
- PASS: right file + line. FAIL: wrong file or line 1.
- Report: the file+line you landed on.

**T175. [DG10] One watch store, both surfaces.**
- In the Explorer debug panel, ADD a watch expression (`add(1,2)`) → it appears in the VARIABLES-panel watch list immediately. Add a different one in the VARIABLES panel → appears in the Explorer panel. REMOVE it in either → gone from both.
- REPL: evaluate `1+2` → the echo shows in BOTH the Explorer console and the PSS Debug tab.
- PASS: synced both ways. FAIL: one-sided store.
- Report: one line.

**T176. [DG12] Two sessions isolated.**
- Start TWO Python debug sessions (debug `test_add` and `test_sub_fails` in separate session starts).
- In session A, tap **Stop** → expect: session B is UNAFFECTED (pause/step/evaluate still work on B; B's debuggee alive — `ps` shows B's process).
- PASS: isolation. FAIL: killing A killed B.
- Report: B alive (yes/no).

**T177. [DG13] Debuggee terminated inside rootfs.**
- Stop a session mid-debug → in the terminal: `ps aux | grep -E "debugpy|python3.*test"` → NO orphaned debuggee left.
- A failed launch (T172) also leaves no tracked process.
- PASS: clean teardown. FAIL: orphaned processes.
- Report: the ps output (or "clean").

**T178. [DG14] Breakpoint broadcast + Remove All.**
- With TWO live sessions: add/remove/toggle a breakpoint in ONE → BOTH sessions rebind (edit file A while session B is on another file → B keeps ITS breakpoints too, only A's changes propagate).
- With ≥1 breakpoint: the BREAKPOINTS header shows **Remove All** → tap → clears the store + live sessions + PERSISTED state (kill app, reopen: still empty).
- While running, an UNverified bp (one the server rejected) renders DIM with the server's message next to it.
- PASS: all four. FAIL: one-sided broadcast or resurrection after Remove All.
- Report: one line per sub-check.

**T179. [DG04] Honest refusal for unsupported languages.**
- Open `Main.kt`, set a breakpoint, tap Debug (Explorer debug button or the Run/Debug menu).
- Expect: NO session starts; the console shows "[debug] No debugger available for Kotlin — use Run instead."-style (ExplorerPane) or a "No debugger available"-style toast (PSS); NO fake RUNNING state with pause/step buttons. The session switcher lists nothing.
- Python file → real session starts as before. JS/TS → Node session. A shell file → real bash -x session. HTML/JSON → the non-debuggable message.
- PASS: honest everywhere. FAIL: any fabricated session (the original bug).
- Report: the exact refusal line.

---

# PHASE 11 — SETUP J: tabs, splits, TB02 race (Tests 180-186)

**T180. [TB04] Split ids don't collide.**
- Split `test_math.py` into 4 views (the split action, 3 times). Close view #2 (its X). Add a split again.
- Expect: the new view gets a FRESH number — NO two strip entries share one id. Toggle between them: no state bleed (scroll/cursor independent); closing one never closes another.
- PASS: fresh id + isolation. FAIL: duplicate id or cascade close.
- Report: the strip's ids before/after.

**T181. [TB05] Splits are per-project.**
- In `p5-poly` with splits open, switch to project B (no saved splits) → its strip shows NO p5-poly splits. Switch back → p5-poly's splits return.
- PASS: isolation + return. FAIL: bleed.
- Report: one line.

**T182. [TB06] Rename keeps the split + all per-file state.**
- With `util.ts` open AND split: rename it in the Explorer (Rename → `util2.ts`).
- Expect: the TAB title updates; the split SURVIVES with the new name; bookmarks/undo history/folds/scroll position carry over; breakpoint dots stay on the same LINES; NO second tab for the old path reappears.
- PASS: everything carries. FAIL: lost state or ghost tab.
- Report: which states carried (name them).

**T183. [TB07] OPEN EDITORS close works.**
- Open the OPEN EDITORS panel → tap **X** on a tab entry.
- Expect: the tab actually closes in the strip (and STAYS closed — no resurrection), its split views cascade away, and the shell breadcrumb follows the newly active tab.
- PASS: close + cascade + breadcrumb. FAIL: resurrected tab.
- Report: one line.

**T184. [TB08] Path-spelling dedupe.**
- Open `test_math.py` via the Explorer. Then open the SAME file via a differently-spelled path: ask the LSP for go-to-definition into it, or use a Go-to-File palette entry that returns a relative/URL-decoded spelling.
- Expect: NO duplicate tab — the EXISTING tab activates (canonical identity wins).
- PASS: single tab. FAIL: twin tabs for one file.
- Report: one line.

**T185. [TB03] Dirty-close gate on every close path.**
- Make `util2.ts` dirty. Close it via: strip X → the Save & Close / Discard & Close / Cancel dialog appears. Repeat via context-menu **Close Others** and **Close All** → same dialog.
- A CLEAN tab's X closes directly, no dialog.
- Root-removal close (delete the file's folder so the tab closes): with a dirty tab → it saves + closes; make the write FAIL (chmod the dir read-only first) → the tab STAYS OPEN with an ERROR.
- PASS: all paths gated, failures keep tabs. FAIL: ungated loss of dirty content.
- Report: dialog on each path (yes/no).

**T186. [TB02] ShellState race protocol — RUN TWICE.**
- Round 1: in one session, RAPIDLY alternate: tab selections (tap 3-4 different files back and forth), panel switches (Explorer → Terminal → GIT → back), and font-size changes (In-Project Settings → editor font size, change it 2-3 times) — do the alternation for ~30 seconds, fast.
- Then: force-close the app → relaunch → reopen the project.
- Expect: ALL fields restore: the last ACTIVE FILE tab, pinned tabs, the open panel, the bottom tab, and the FONT SIZE — all together, none erased by the race.
- Round 2: repeat the whole protocol once more.
- Legacy check: open a project you last saved with the PREVIOUS app version → its session state still restores ONCE (the migration path).
- PASS: all fields intact in both rounds + legacy restores once. FAIL: ANY field reverted to a default (that's the split-writer race surviving — report WHICH field).
- Report: per round: active file / pins / panel / bottom tab / font size (yes/no each), + the legacy result.

---

# PHASE 12 — SETUP K: telemetry, forced crash (Tests 187-189) — debug build only

**T187. [RG01] Force a real crash.**
- On-device in `com.codespace.ide.debug`, deliberately crash it: easiest reproducible path — in the chat ask for something known-broken, or use any in-app action that reliably force-closes; a plain uncaught exception anywhere proves the pipeline.
- Immediately after the crash: if you can reach internal storage (Fix-B7 root), check `files/crash_logs/crash_<timestamp>.txt` EXISTS with the stack trace.
- PASS: local crash file persisted. FAIL: no local file (the pipeline didn't even reach disk).
- Report: the file's name + first line.

**T188. [RG01] The record persists on the server.**
- Relaunch ONLINE, wait ~1 minute. Then TELL ME: "I crashed it" — I will read the CrashLog entity on this Superagent and verify: `app_package=com.codespace.ide.debug`, stack_trace present, app_version + git_hash populated, thread_name from either path (`recovered_on_next_launch` = the retry path won the race).
- PASS: a real record with all fields. FAIL: no record after 2 minutes.
- Report: just tell me when you've crashed + relaunched; I'll pull the record and confirm. **This closes RG01's standing note.**

**T189. [RG01] Retry-path honesty.**
- Crash the app AGAIN with AIRPLANE MODE ON → local file persists, NO record created.
- Reconnect, relaunch → the retry uploads the queued crash (I'll verify a second record), and the local crash_logs file is consumed.
- PASS: queued-then-uploaded. FAIL: lost in airplane mode.
- Report: tell me when you've done it; I'll verify both records.

---

# PHASE 13 — SETUP L: home / projects / cloud (Tests 190-197)

**T190. [OG01] Local project survives sync.**
- Airplane mode ON → create a project locally (`p5-offline`) → it appears in the list. Reconnect → let the cloud sync run.
- Expect: the project SURVIVES the sync and the status shows "+N local-only"-style.
- PASS: survivor + honest count. FAIL: wiped on sync (the original bug).
- Report: the status text.

**T191. [OG01] Empty cloud never wipes the list.**
- (Only if you're willing to empty your cloud project list — otherwise NOT RUN.) Empty the cloud account's projects → sync → the visible list is NOT wiped.
- PASS: no wipe. Report: if run.

**T192. [OG01] Register Existing Folder.**
- Create a project folder via the wizard (`p5-adopt` with a file in it), then delete the project from the LIST only (soft). New Project → navigate to the parent → type the SAME name.
- Expect: the "Register Existing Folder"-style adoption button appears → registering opens the EXISTING folder with its files intact (no scaffold, no overwrite).
- PASS: adoption works. FAIL: "Folder already exists and is not empty" dead end (the original bug).
- Report: the button label + files intact.

**T193. [OG01] Restore-into-same-project.**
- Open any project, force-close the app, relaunch.
- Expect: the session restores into the SAME project (the NAME fallback in the resolver).
- PASS: right project. FAIL: home screen or wrong project.
- Report: one line.

**T194. [OG03] Same-name project reuses the name safely.**
- Delete a project (soft delete) → create a NEW project with the SAME name.
- Expect: creation succeeds, and the soft-deleted predecessor's trash survived — check the `.deleted-`-style sibling directory exists in the parent (holding the old `.ide-trash`).
- PASS: both. FAIL: creation blocked or trash destroyed.
- Report: the sibling dir name.

**T195. [OG02] Offline delete warns.**
- Airplane mode ON → delete a project → expect a warning notification about the backend being unreachable; on next ONLINE sync the cloud copy may REAPPEAR (that's the documented expected behavior, not a bug).
- PASS: warning + documented reappearance. FAIL: silent or surprise.
- Report: the warning text.

**T196. [EX03] Cut/paste: folder copy, collision honesty, clipboard survives failure.**
- Copy a FOLDER in the Explorer → paste → all children present (recursive copy works).
- Cut a file (`cut.txt`, create first) and paste it where a same-name file exists → an error toast appears AND the clipboard still holds the cut (paste it somewhere valid → works — not lost on failure).
- Cut a file that has an OPEN TAB → paste (move) elsewhere → the TAB follows the new path (title updates, content + dirty state intact; no dead tab on the old path).
- PASS: all three. FAIL: partial folder copy, lost clipboard, or dead tab.
- Report: one line per sub-check.

**T197. [EX08] Select-All walks the whole tree, dedupes.**
- Collapse all top-level folders in the Explorer → multi-select → **All** → expect files from INSIDE those collapsed folders are listed (it walks the whole non-hidden tree, not just what's displayed).
- Then manually select some files → tap All → the manually-selected ones appear ONCE (deduped), no duplicates.
- PASS: whole-tree walk + dedupe + ONE state write (no UI jank on a big tree). FAIL: only-visible files or duplicates.
- Report: counts (how many files All found vs. how many visible).

---

# PHASE 14 — SETUP M: live preview LAN (Tests 198-200) — needs a second device

**T198. [VG01] Preview serves locally.**
- In a project with `page.html`: run Live Preview → it serves and hot-reloads (edit the file → the preview updates) on the phone. If you can see logcat: a "port 5500 (LOOPBACK-ONLY bind)"-style line.
- PASS: works locally. FAIL: no serve.
- Report: hot-reload worked (yes/no).

**T199. [VG01] LAN closed.**
- Second device, same Wi-Fi: `curl -m 5 http://<PHONE-IP>:5500/` → connection REFUSED/timeout — the port accepts nothing external.
- PASS: refused. FAIL: page content served to the other device.
- Report: the second device's result.

**T200. [VG01] Bind shape.**
- In the proot terminal: `ss -tln` (or `netstat -tln`) → the preview port shows `127.0.0.1:5500` — NOT `0.0.0.0:5500` or `[::]:5500`.
- PASS: loopback bind. FAIL: wildcard bind.
- Report: the ss line.

---

# FINAL SWEEP (Tests 201-203) — regression only, no fixtures

**T201. [TP05, TP09, TP10, TP15, TP16] Terminal regression.**
- Tabs restore after a force-stop (session reattach works); the app boots with ZERO new terminal-related logcat errors; the extraction/install path is covered by T21/T35 already.
- PASS: no new errors. Report: anything odd you saw in logs/Output.

**T202. [P2c] Migration grandfathering.**
- Update-installs preserved your pre-existing projects → open one → NO trust prompt appears on its first gated action (grandfathered). A NEW wizard project prompts once on ITS first gated action. Delete + re-register the SAME folder → NO re-prompt (trust remembered by canonical path).
- PASS: all three. FAIL: prompts on old projects (or lost trust).
- Report: one line per case.

**T203. [OG06, LS12, LS13] Comment/doc-accuracy rows.**
- No user-visible surface (these were comment corrections + restart settings) — covered by normal use during the round. No action needed; mark as COVERED-BY-ROUND.

---

# WHEN YOU'RE DONE

Paste your results list back to me in the T#-format from the top (grouped by phase is fine, partial batches welcome — send Phase 1 whenever it's done, don't wait for all 14). I'll analyze every line against the expected behavior, tell you PASS/FAIL/PARTIAL per test, flag anything that needs a code fix or a re-test, and update the ledger accordingly. Screenshots welcome for: T16 (both tabs), T46 (Problems panel), T93 (Main.kt colors), T97 ([perf] lines pasted as text is better), T186 (the field table).

Remember: `NOT RUN` with a reason is always an acceptable answer. Never spend longer than ~5 minutes forcing one test — flag it and move on; the point is coverage breadth, not perfection on any single check.
