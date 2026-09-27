# P5 FULL WALKTHROUGH — every test explained, tap by tap, copy-paste ready

This is the complete device verification round, all 203 tests, in the fully explained style: what each test is about, exactly what to paste, exactly where to tap, what you should see, what PASS and FAIL mean, and what to send back. Test numbers match P5-DEVICE-CHECKLIST.md and P5-TEST-GUIDE.md.

**HOW TO REPORT RESULTS** — after each test, write one line like this:

```
T1: PASS — printed 401
T4: NOT RUN — no second device today
```

Send results per phase as you finish; don't wait for all 14. `NOT RUN` plus a reason is always acceptable. Never spend more than 5 minutes forcing one test; flag it and move on. Nothing in this guide can damage your device; every command is a probe except where it clearly says what it changes.

**BEFORE STARTING:** the 9235b43 debug APK is installed and opens (already confirmed). You will need: your phone, any project to open, and for a few tests a second device on the same Wi-Fi (skip those if unavailable).

---

# PHASE 1 — THE TP02 SECURITY ROUND (T1-T6)

**What this phase is about:** your app runs a tiny private web server (the Agent API server) on port 8765 inside the phone. It lets the terminal, the AI chat, and the agent tools talk to each other. It used to be open to the whole Wi-Fi network, meaning anyone nearby could have called it. The TP02 fix locked it down two ways: it only accepts connections from the phone itself, and every request must carry a secret password (a token). These six tests prove the lock works.

## SETUP (do once, about 2 minutes)

**Step 1.** Tap the Codespace IDE app icon. The app opens.

**Step 2.** Tap any project name from the list. The project opens.

**Step 3.** Tap the **TERMINAL** tab at the bottom of the screen. A black screen with text appears. Wait about 5 seconds until you see a line ending in `$`. That is the prompt, meaning it is ready.

**Step 4. Make sure the session is fresh.** Tap once on the terminal screen to bring up the keyboard, then paste this box:

```
echo $AGENT_API_TOKEN
```

Press Enter. A long string of letters and numbers should print (64 characters). If instead you see an empty line: your session started before the new APK was installed and doesn't know the password yet. Close it (tap the X on the session's tab), tap **+** to start a new session, wait for the `$`, and paste the same command again. When the long string appears, you are ready.

---

## T1 — Knock on the server's door WITHOUT the password

**What this is about:** we walk up to the server without the password. It must refuse us with error 401 ("unauthorized"). If it answers politely, that is a security hole.

**What the command means:** `curl` makes a web request, like a browser but text only. `-o /dev/null` throws the page content away and `-w "%{http_code}"` prints only the status code, which is the door's answer. We ask for `/tools`, the list of agent tools, something a stranger must never see.

**Steps:**
1. Be in the terminal from the setup.
2. Copy everything in this box:

```
curl -s -o /dev/null -w "%{http_code}\n" http://localhost:8765/tools
```

3. Long-press in the terminal, choose Paste, then press Enter.

**You should see:** one line printing exactly `401`.

- PASS: prints `401`.
- FAIL: prints `200`, or a tool list. That means the server served a stranger.
- Something else: `000` or "Failed to connect" means the server is not running at all. Note it as FAIL (server-not-listening) and continue to T2 anyway.

**Send back:** the line that printed.

---

## T2 — Knock WITH the password

**What this is about:** now we knock with the password. The server must open the door and show the tool list. Together with T1 this proves the lock works both ways.

**What the command means:** `-H "Authorization: Bearer $AGENT_API_TOKEN"` attaches the password from the terminal's environment variable to the request.

**Steps:**
1. Still in the terminal, copy everything in this box:

```
curl -s -H "Authorization: Bearer $AGENT_API_TOKEN" http://localhost:8765/tools
```

2. Paste it and press Enter. Text will flood the screen; that is expected and it may be several screens long.

**You should see:** JSON text starting with `[` or containing `"tools"` and tool names. No "unauthorized" anywhere.

- PASS: the big JSON list.
- FAIL: "unauthorized" or 401 again, or nothing printed. If that happens the token is missing: your session is stale, so close the session, tap +, and redo the paste.

**Send back:** the first 2 or 3 lines of the text.

---

## T3 — The friendly shortcuts still work

**What this is about:** the app installs two helper commands in the terminal, `agent_tools` and `agent`. They must have picked up the password automatically. If they broke, the terminal AI features silently died even though the server itself is fine.

**Steps, part 1:**
1. Paste this box and press Enter:

```
agent_tools
```

2. You should see the same kind of JSON tool list as in T2.

**Steps, part 2:**
3. Paste this box and press Enter:

```
agent run_command '{"command":"echo ok"}'
```

4. This asks the agent to run a tiny command that prints `ok`. You should see the word `ok` in the result.

- PASS: both commands worked.
- FAIL: either one printed an error.

**Send back:** one short line for each command.

---

## T4 — Try to reach the phone FROM another device

**What this is about:** the old version answered requests from any device on your Wi-Fi. Now the server should be invisible to every other device. If you don't have a second device (laptop or second phone) on the same Wi-Fi nearby, skip and write `T4: NOT RUN — no second device`.

**Steps:**
1. On your phone's terminal, paste this box and press Enter:

```
ip addr | grep "inet "
```

2. Find the line like `inet 192.168.x.x` (ignore any line with `127.0.0.1`). That is your phone's Wi-Fi address. Write it down.
3. On the second device, open a browser and type this into the address bar, replacing the numbers with your phone's address:

```
http://192.168.XX.XX:8765/tools
```

4. Open that address.

**You should see:** the page FAILS to load, with "This site can't be reached", "Connection Refused", or an endless spinner.

- PASS: refused or timeout.
- FAIL: ANY response from the server loads, even a 401 page. Any answer at all proves the port is open to the network, which the fix was supposed to prevent.

**Send back:** exactly what the second device showed.

---

## T5 — Confirm the password is there

**What this is about:** a double-check that the password variable is really loaded in this session, since T2 and T3 depend on it.

**Steps:**
1. Paste this box and press Enter:

```
echo $AGENT_API_TOKEN
```

**You should see:** the long 64-character string again.

- PASS: non-empty.
- FAIL: an empty line.

**Privacy note:** send me ONLY the first 4 characters of the string. Never paste the whole token into chat.

---

## T6 — A brand-new project must be refused until you Trust it

**What this is about:** the app's trust system. A brand-new project is "untrusted", meaning the AI is not allowed to run its tools there until you tap Trust. The server must enforce that too, not just the chat screen. This proves a never-trusted project cannot sneak commands through the terminal back door.

**Steps:**
1. Use the back gesture until you leave the project and see the project list.
2. Tap **New Project**. Type the name:

```
p5-untrust
```

3. Tap Create. The new project opens. Do NOT tap Trust anywhere yet.
4. Tap the **TERMINAL** tab. If the terminal is empty, tap **+** and wait for the `$`.
5. Paste this box and press Enter:

```
agent run_command '{"command":"echo hi"}'
```

**You should see:** a REFUSAL: text with a "project not trusted yet" style message and a 403 status. The refusal is the correct behavior here, it is the first half of the PASS.

6. Now open the AI chat panel (the chat icon). Type this and send:

```
list the files in this project
```

7. The Trust prompt appears. Read it, then tap **Trust**.
8. Go back to the TERMINAL tab and paste the step 5 command again, press Enter.

**You should see:** it now executes and prints `hi`.

- PASS: refused before trusting, ran after trusting.
- FAIL (serious): it ran and printed `hi` at step 5, before you trusted. Write that down exactly; that is the security hole.
- FAIL (other): no Trust prompt ever appeared at step 7.

**Send back:** the refusal message from step 5, and yes/no that it worked at step 8.

---

# PHASE 2 — FRESH INSTALL, ROOTFS AND TERMINAL (T8-T17)

**What this phase is about:** the Ubuntu rootfs (the Linux system inside the app) and the terminal. We verify the install is checksum-verified, sessions close and survive correctly, no credential leaks into backups, and per-project sessions don't bleed into each other. Your device already has a working rootfs, so the fresh-install test (T7) doesn't apply today: write `T7: NOT RUN — rootfs already installed`. It only applies if you ever wipe and reinstall.

## T8 — (substitute) Checksum-verified install is visible

**What this is about:** the installer must verify the rootfs archive's checksum before extracting, so a corrupted download can never become a broken Linux system. You only see this during a fresh install or a manual reinstall.

**Steps:**
1. Only if you ever reinstall the rootfs (Settings, search "Ubuntu" or "rootfs", choose Reinstall, or the first-run flow): watch the progress stream while it installs.
2. Look for a line that says "Verifying rootfs checksum..." and let the install finish.

**You should see:** the verify line appears, then the install completes with no unresolved-symlink errors.

- PASS: the verify line appeared and the install completed.
- FAIL: install finished with no verify line, or failed with unresolved links.
- Edge (optional): kill the app mid-download, reopen, and confirm the install says the download restarts instead of extracting the partial file.

**Send back:** the progress lines (a screenshot of the stream is fine), or NOT RUN.

---

## T9 — The upgrade path boots normally

**What this is about:** an already-installed rootfs must keep booting normally after the app updates.

**Steps:**
1. Open the Ubuntu terminal normally.
2. Paste this box and press Enter:

```
ls /
```

**You should see:** a normal Linux listing: `bin`, `etc`, `usr`, and so on.

- PASS: it boots and commands run.
- FAIL: any boot failure or unresolved-link error.

**Send back:** one line.

---

## T10 — Closing a terminal tab asks before killing busy work

**What this is about:** tapping X on a terminal that recently printed output (so something may still be running) must ask "Close terminal?" first. A tab that has been quiet for 15+ seconds can close silently, because there is nothing to lose.

**Steps, part 1 (busy tab asks):**
1. Open a NEW terminal tab (tap +). Paste this box and press Enter:

```
sleep 300 && echo done
```

2. Within about 10 seconds, tap that tab's X.

**You should see:** a "Close terminal?" style dialog. Tap **Cancel** and confirm the tab survives.

**Steps, part 2 (quiet tab closes silently):**
3. Open another new tab. Paste this box and press Enter:

```
sleep 300
```

4. Wait 20 seconds doing nothing, then tap that tab's X.

**You should see:** it closes immediately, no dialog.

- PASS: dialog in part 1, silent close in part 2.
- FAIL: no dialog after recent output (risk of killing live work), or a dialog on a long-quiet tab.

**Send back:** both behaviors, one line each.

---

## T11 — Sessions survive minimizing the app

**What this is about:** the app must not kill your terminal sessions when you switch away briefly.

**Steps:**
1. In a terminal tab, paste this box and press Enter:

```
ps | head -5
```

2. Note the output. Press the HOME button (leave the app completely).
3. Wait 60 seconds. Return to the app.
4. In the same tab, paste `ps | head -5` again.

**You should see:** the session is still alive and both commands ran fine.

- PASS: session alive, earlier output intact.
- FAIL: the tab shows "Session ended" or similar.

**Send back:** one line.

---

## T12 — The URL chip appears once and doesn't churn

**What this is about:** when output contains a web address, the terminal shows a clickable chip. It must appear once, stay stable, and not rebuild itself endlessly (which wastes battery and can flicker).

**Steps:**
1. Paste this box and press Enter:

```
echo "see https://example.com/x"
```

2. Watch the chip area: a clickable chip for `https://example.com/x` should appear within about 2 seconds.
3. Now leave that terminal idle for 60+ seconds while watching.

**You should see:** the chip stays put. No flicker, no duplicates, no rebuilds.
4. Switch to another tab and come back.

**You should see:** the chip is restored, with no burst of duplicate activity.

- PASS: chip once, stable, restored on return.
- FAIL: flicker, duplicates, or the chip disappears on its own.

**Send back:** one line (screenshot if anything flickered).

---

## T13 — A folder name with a space and a command-substitution bait

**What this is about:** project paths get pasted into shell commands. If the app forgets quotes, a folder named `$(echo hi)` would have "hi" substituted into the command (in the worst case, arbitrary code). The app must quote the path so the name stays literal.

**Steps:**
1. Go HOME. Tap **New Project**. Type this exact name (note the space):

```
p5 spacey $(echo hi)
```

2. If the wizard refuses this name, write that down (it may be validation working) and instead create:

```
p5 spacey test
```

The space alone still exercises the quoting.
3. Open the project, open its TERMINAL tab, and paste this box:

```
pwd
```

**You should see:** the literal directory name printed. If you used the first name, the output reads `p5 spacey $(echo hi)` with `$(echo hi)` NOT executed, so no `hi` anywhere.

- PASS: pwd shows the literal name, no substitution, no cd error.
- FAIL: `hi` appears somewhere, or the session failed to cd into the folder.

**Send back:** the pwd line.

---

## T14 — The container backup must NOT contain your secrets

**What this is about:** "Back up Ubuntu" copies the whole Linux system to public storage at `/storage/emulated/0/CodespaceIDE/container-backup.tar.gz`. Public storage is world readable on this device class, so the backup must exclude your SSH keys and git credentials. They must stay only in the live system.

**Steps:**
1. In the terminal, paste this box and press Enter (checks the live system has the folder, even if empty):

```
ls ~/.ssh
```

2. Open Settings (the gear menu), search for "Backup", and tap **Back up now** in the Backup & Restore section. This is the container backup. It can take several minutes; watch the progress until it finishes.
3. When done, go back to the terminal and paste this box, press Enter:

```
tar -tzf /storage/emulated/0/CodespaceIDE/container-backup.tar.gz | grep -E "root/.ssh|root/.gitconfig"
```

**You should see:** NOTHING printed (grep finds no matches), and the shell just returns to the prompt.
4. Paste `ls -la ~/.ssh` to confirm the live folder still exists.

- PASS: grep printed nothing, live .ssh intact.
- FAIL: any tar entry was listed, meaning secrets went into the public backup.

**Send back:** whether grep printed anything, plus this line:

```
ls -l /storage/emulated/0/CodespaceIDE/container-backup.tar.gz
```

---

## T15 — Per-project terminal sessions don't bleed

**What this is about:** each project has its own terminal sessions. Opening project A must never show you project B's session, and force-stopping must not leave orphaned Linux processes running.

**Steps:**
1. Open project A, open its terminal, paste and run:

```
echo A
```

2. Go HOME, open project B, open its terminal, paste and run:

```
echo B
```

3. Go HOME again, reopen project A, and look at its terminal.

**You should see:** A's own session with your `echo A` history, and nothing from B.
4. Minimize 60 seconds, return, and switch between both projects.

**You should see:** each project still shows its own session.
5. Force-stop the app (Recents: swipe the app away, or Settings > Apps > Force stop). Relaunch, open a terminal, paste and run:

```
ps aux | grep -i proot | head -3
```

**You should see:** no duplicated or orphaned old process trees beyond your fresh session.

- PASS: right session per project, no orphans.
- FAIL: A showed B's session, or duplicated trees after force-stop.

**Send back:** one line per sub-check.

---

## T16 — Two files with the SAME name don't share squiggles

**What this is about:** the editor marks errors with squiggly underlines. Two different files can both be called `MainActivity.kt`. An old bug let one file's error marks appear on its same-named sibling. Needs the Kotlin LSP: if not installed, Extensions tab > Packages > install the Kotlin LSP, then open a `.kt` file and wait about 30 seconds.

**Steps:**
1. In a project, create `folderA/MainActivity.kt` (Explorer, tap +, New File, type the path, paste the content, save) with exactly this line:

```
fun brokenA() { val x: Int = "not an int" }
```

2. Create `folderB/MainActivity.kt` with exactly this line:

```
fun okB() { val y = 1 }
```

3. Open BOTH files as tabs. Wait for diagnostics to arrive (up to a minute).

**You should see:** a red squiggle under `folderA`'s bad line, and `folderB`'s file stays completely clean.

- PASS: only folderA is squiggled.
- FAIL: folderB also shows marks copied from its sibling.

**Send back:** a screenshot of both tabs.

---

## T17 — Package install streams honestly, both directions

**What this is about:** the package manager must show live output while installing, report the real result, and keep its lists in sync with reality.

**Steps:**
1. Open the **Extensions** tab (side panel). Find the Packages section and install a small package such as `cowsay` or `sl`.

**You should see:** the operation strip shows live apt output, then a done marker; the package appears in the Installed list without you refreshing.
2. Now remove it.

**You should see:** it disappears from the Installed list, again without manual refresh.

- PASS: streamed, honest outcome, lists update both ways.
- FAIL: silent hang, false success, or a stale list.

**Send back:** one line.

---

# PHASE 3 — HOSTILE FIXTURES (T18-T38)

**What this phase is about:** security. The app opens archives, tars, databases and APKs from storage. A malicious archive can contain entries like `../pwned.txt` that try to write OUTSIDE the folder you extracted to (called zip-slip or path traversal). These tests throw deliberately hostile files at the app and prove it refuses every escape. Nothing here is dangerous to your phone: the files are tiny, and the whole point is that the app must refuse them.

## PREPARE THE FIXTURES ONCE

Do these in the app terminal (project open). Each paste box is one command.

**Fix-B1, the zip-slip bomb.** A zip containing one safe file and two hostile entries that try to escape upward and to an absolute path:

```
python3 -c "import zipfile; z=zipfile.ZipFile('/storage/emulated/0/Download/slippz.zip','w'); z.writestr('../pwned.txt','x'); z.writestr('/data/local/tmp/abs.txt','x'); z.writestr('legit.txt','ok'); z.close()"
```

**Fix-B2, the tar escape bomb** (for the restore test, used later in T21):

```
python3 -c "import tarfile,io; t=tarfile.open('/storage/emulated/0/Download/esc.tar.gz','w:gz'); t.addfile(tarfile.TarInfo('../escaped.txt'), io.BytesIO(b'x')); t.addfile(tarfile.TarInfo('safe.txt'), io.BytesIO(b'ok')); t.close()"
```

**Fix-B3, a truncated APK.** Copy any real APK you have in Download (the codespace APK you installed from works; adjust the name in the command to match the file):

```
head -c 20000 /storage/emulated/0/Download/<your-apk-name>.apk > /storage/emulated/0/Download/trunc.apk
```

**Fix-B4, an over-cap file** (129MB, takes a few seconds to write):

```
python3 -c "open('/storage/emulated/0/Download/big.pcap','wb').write(b'\x00'*(129*1024*1024))"
```

plus a small control file:

```
python3 -c "open('/storage/emulated/0/Download/small.pcap','wb').write(b'\x00'*1000)"
```

**Fix-B5, a database with a hostile table name.** The backslash-backtick in the middle is intentional; paste exactly:

```
python3 -c "import sqlite3; c=sqlite3.connect('/storage/emulated/0/Download/evil.db'); c.execute('CREATE TABLE \"we\`ird\"(x)'); c.execute('CREATE TABLE normal(x)'); c.commit()"
```

This makes one table whose name contains a backtick character (a classic SQL-injection shape) and one normal table.

**Fix-B6, a file with a super-long path** (for the backup/restore test T22). Paste both lines at the project root:

```
mkdir -p "a/deeply/nested/directory/structure/that/keeps/going/on/and/on/for/quite/a/while/indeed/to/exceed/one/hundred/characters/of/relative/path/length"
```

```
echo keepme > "a/deeply/nested/directory/structure/that/keeps/going/on/and/on/for/quite/a/while/indeed/to/exceed/one/hundred/characters/of/relative/path/length/important-file.txt"
```

**Fix-B7, corrupting app-internal settings.** The settings file lives at `/data/data/com.codespace.ide.debug/files/settings.json`, inside the app's private storage. Try to register it: Explorer > Add/Register root > paste `/data/data/com.codespace.ide.debug`. If the app opens that root, go to `files/settings.json` and replace its content with:

```
{{{{ not json
```

If the app refuses to register that path, that's fine: mark T34 `NOT RUN — internal storage unreachable` and continue.

---

## T18 — Extracting the zip-slip bomb must refuse every escape

**What this is about:** `../pwned.txt` tries to escape UP out of the extraction folder, and `/data/local/tmp/abs.txt` tries to write to an absolute path. Both must be refused while the safe file extracts normally.

**Steps:**
1. In the Explorer, navigate to `/storage/emulated/0/Download` (open the Download folder via the built-in shortcut or a registered root).
2. Long-press `slippz.zip`, choose **Extract Here** (into Download or a subfolder; note which you chose).
3. Look at the output folder.

**You should see:** only `legit.txt` inside it.
4. In the terminal, paste and run both checks:

```
ls /storage/emulated/0/Download/pwned.txt
```

```
ls /data/local/tmp/abs.txt
```

**You should see:** both print "No such file or directory".

- PASS: only legit.txt, both hostile targets absent, no crash.
- FAIL: pwned.txt exists anywhere, or the app crashed.

**Send back:** the two ls results.

---

## T19 — Creating files named `../something` must be refused

**What this is about:** the New File / New Folder / Rename boxes must reject names that try to climb out of the project (`../esc.txt`), while still allowing normal nested paths (`sub/ok.txt`).

**Steps:**
1. In the Explorer, inside your project, tap **+**, choose **New File**, type exactly:

```
../esc.txt
```

2. Confirm.

**You should see:** an "Invalid file name" style error, nothing created.
3. In the terminal paste `ls ../esc.txt`: "No such file or directory".
4. Tap **+**, **New File** again, type:

```
sub/ok.txt
```

5. Confirm, then check with `ls sub/`: the file exists, nested inside the project.
6. Repeat both shapes with **New Folder**, and finally **Rename**: rename any existing file to exactly `../moved.txt`.

**You should see:** refused, file not moved.

- PASS: every `../` form refused, the nested form works.
- FAIL: any escape created a file outside the project.

**Send back:** one line per attempt (5 attempts total).

---

## T20 — Deleting a registered root must refuse device-critical paths

**What this is about:** you can register folders as Explorer roots and later remove them "and delete files". For a normal folder that's fine. For `/storage/emulated/0` (your entire shared storage) it must REFUSE, because deleting it would wipe your photos, downloads, everything.

**Steps:**
1. In the terminal, paste and run:

```
mkdir -p /storage/emulated/0/Download/x && echo hi > /storage/emulated/0/Download/x/f.txt
```

2. Register `x` as an Explorer root (Explorer > Add/Register > navigate to Download/x).
3. Remove that root, choosing the "Remove & delete files" option.

**You should see:** it deletes the folder and says so.
4. Now register `/storage/emulated/0` itself as a root, then remove it with delete.

**You should see:** a refusal message like "directory NOT deleted (device-critical / outside zone)", and NOTHING deleted. Check with `ls /storage/emulated/0/`: all your folders are still there.

- PASS: deletes the registered subfolder, refuses the device-critical root.
- FAIL: storage got wiped (catastrophic, stop and tell me immediately) or a silent no-op with no message.

**Send back:** both messages (screenshots ideal).

---

## T21 — Restoring a hostile backup tar must reject the escape entry

**What this is about:** the container restore reads tar archives from public storage. A crafted archive could try to write outside the destination. The restorer must skip those entries, log the rejection, and still complete. Do this only AFTER T14 gave you a real backup, and note: your real backup is protected by the copy in step 1.

**Steps:**
1. In the terminal, paste and run (protects your real backup):

```
cp /storage/emulated/0/CodespaceIDE/container-backup.tar.gz /storage/emulated/0/CodespaceIDE/container-backup.real.tar.gz
```

2. Paste and run (plants the evil archive as the live backup):

```
cp /storage/emulated/0/Download/esc.tar.gz /storage/emulated/0/CodespaceIDE/container-backup.tar.gz
```

3. Settings > Backup & Restore > tap **Restore** > confirm. Let it finish.
4. In the terminal, check the escape target:

```
ls /storage/emulated/0/CodespaceIDE/escaped.txt
```

**You should see:** "No such file or directory".
5. Open a terminal session and run `ls /`: the container still boots (restoring did NOT wipe it).

**You should see:** a normal Linux listing.
6. Put your real backup back:

```
cp /storage/emulated/0/CodespaceIDE/container-backup.real.tar.gz /storage/emulated/0/CodespaceIDE/container-backup.tar.gz
```

- PASS: restore completed, escaped.txt absent, container alive.
- FAIL: escaped.txt was created, or the restore crashed the app.

**Send back:** the escaped.txt result and container alive yes/no.

---

## T22 — A 100+ character filename survives backup and restore

**What this is about:** tar has filename length limits; the backup must store long paths in a way that survives round trips intact (an old bug silently truncated them).

**Steps:**
1. Make sure Fix-B6's deep file exists in your project.
2. Settings > Backup & Restore > **Back up now** (wait for it to finish).
3. Tap **Restore** > confirm (wait again).
4. In the terminal, paste this box (it's one long line; copy all of it) and run:

```
cat "a/deeply/nested/directory/structure/that/keeps/going/on/and/on/for/quite/a/while/indeed/to/exceed/one/hundred/characters/of/relative/path/length/important-file.txt"
```

**You should see:** `keepme`.

- PASS: path and content intact end to end.
- FAIL: file missing or the filename came back truncated or corrupted.

**Send back:** the cat output.

---

## T23 — Creating an entity named `../projects` must be refused

**What this is about:** the AI can create data records ("entities"). Their names must not be allowed to contain path escapes.

**Steps:**
1. Open the AI chat panel and send exactly this message:

```
Create an entity named ../projects for me
```

**You should see:** a typed refusal, "Invalid entity name" style.
2. Then send:

```
Create an entity named testentity123
```

**You should see:** it works normally.

- PASS: hostile refused, normal created.
- FAIL: the hostile name was accepted.

**Send back:** the refusal message.

---

## T24 — Live preview must not serve files outside the project

**What this is about:** the built-in preview web server must only serve files inside the project. A symlink (a shortcut file) pointing to a folder OUTSIDE the project must not become a secret door to your other files.

**Steps:**
1. At the project root in the terminal, paste and run both:

```
mkdir -p ../sibling && echo '<h1>sib</h1>' > ../sibling/index.html
```

```
ln -s ../sibling siblink
```

```
echo '<h1>real</h1>' > page.html
```

2. Open `page.html` in the editor and start Live Preview.

**You should see:** the preview serves your `real` page and hot reloads when you edit it.
3. Now try the door: in the Explorer, open `siblink/index.html` (the shortcut) and try to preview it.

**You should see:** the sibling's content is NOT served. The preview refuses it or shows nothing, and never exposes an outside path.

- PASS: project page serves, symlink path refused.
- FAIL: the sibling's `<h1>sib</h1>` content appeared in the preview.

**Send back:** what the preview showed for the siblink path.
**Cleanup:** run `rm siblink` (the link only), then delete the `sibling` folder through the app's file explorer (it goes to trash, that's fine).

---

## T25 — The project wizard must refuse a project named `..`

**What this is about:** a project named `..` would mean "the parent folder", so the wizard must refuse it before creating anything outside the chosen location.

**Steps:**
1. Go HOME, tap **New Project**, and type the name exactly:

```
..
```

2. Try to create it. If the wizard offers different creation paths (create in folder, normal create), try both.

**You should see:** a refusal like "not valid project names", on every path, and nothing created.

- PASS: refused everywhere.
- FAIL: anything was created.

**Send back:** the refusal message.

---

## T26 — Opening a truncated APK must not crash

**What this is about:** the APK analyzer parses real APK archives. A cut-off or corrupt one must not crash the app, and must show honest partial data instead of pretending.

**Steps:**
1. In the Explorer, open `/storage/emulated/0/Download` and tap `trunc.apk` (from Fix-B3).

**You should see:** the app does NOT crash. The analyzer opens with empty or partial manifest data.
2. Open any other file afterward to confirm the app is still healthy.

- PASS: no crash, partial data, app alive.
- FAIL: any crash or freeze.

**Send back:** what the viewer showed (screenshot).

---

## T27 — A 129MB file gets an honest "too large" message

**What this is about:** file viewers have a 128MB cap so a giant file can't eat all your RAM. The cap must be an honest, readable message, not a hang or a blank screen.

**Steps:**
1. In the Explorer, open `/storage/emulated/0/Download` and tap `big.pcap`.

**You should see:** a clear message like "Too large to open in this viewer, cap 128MB". No hang, no crash, no silent blank.
2. Now tap `small.pcap`.

**You should see:** the viewer opens and engages (even if it reports the content is empty or invalid, the viewer itself works).

- PASS: cap message plus a working viewer for the small file.
- FAIL: hang, crash, or blank screen.

**Send back:** the cap message text.

---

## T28 — A database with a hostile table name is refused honestly

**What this is about:** table names go into queries. A name containing a backtick could break out of its quotes (SQL injection shape). The viewer must refuse it with an honest message instead of crashing.

**Steps:**
1. In the Explorer, tap `evil.db` (from Fix-B5) so it opens in the database viewer.

**You should see:** the hostile table (`we`ird`) is refused with an "unsafe name" style error, or listed but refused when tapped. The `normal` table lists its rows fine.

- PASS: hostile refused honestly, normal works.
- FAIL: crash, broken UI, or the hostile name silently queried.

**Send back:** the refusal message.

---

## T29 — Extracting over an existing file asks first

**What this is about:** extraction must not silently overwrite files that already exist. It has to ask.

**Steps:**
1. Extract `slippz.zip` once (like T18), so the output folder has `legit.txt`.
2. Extract it AGAIN into the same folder.

**You should see:** an **Overwrite / Cancel** dialog BEFORE anything is written.
3. Tap **Cancel**: nothing changed.
4. Redo and tap **Overwrite**: the file is replaced.

- PASS: dialog appeared and Cancel was honored.
- FAIL: silent overwrite.

**Send back:** dialog yes/no and what Cancel did.

---

## T30 — (OPTIONAL) A 1GB+ entry is refused with a quota message

**What this is about:** a safety net behind the 128MB cap. Skip this if you don't want the disk churn and wait time; mark `T30: NOT RUN — skipped, optional`.

**Steps if you run it:**
1. Paste and run (writes ~1.1GB, takes a minute):

```
python3 -c "open('/storage/emulated/0/Download/huge.bin','wb').write(b'\x00'*(1100*1024*1024))"
```

2. Zip it:

```
cd /storage/emulated/0/Download && zip -q huge.zip huge.bin
```

3. Extract `huge.zip` in the Explorer.

**You should see:** extraction aborts with a quota message and leaves NO partial file.
4. Delete `huge.bin` and `huge.zip` from Download afterward.

- PASS: abort, no partial file. FAIL: partial file left or crash.

**Send back:** only if run.

---

## T31 — The Logcat panel says it can't work, instead of pretending

**What this is about:** the app can't read Android's system log without adb. The Logcat panel must say that honestly instead of showing a silently empty feed that looks broken.

**Steps:**
1. Open the Logcat panel (search for "Logcat" in Settings or the panel list).

**You should see:** a line like "adb not available. Run 'adb logcat' in terminal...". NOT a blank feed.

- PASS: the honest line is present.
- FAIL: blank with no explanation.

**Send back:** the line.

---

## T32 — The APK signature claim is honest

**What this is about:** the analyzer can SEE that a signature file exists, but it doesn't actually verify signatures. The wording must not claim "signed/verified" when it only checked presence.

**Steps:**
1. Open the REAL, untruncated codespace APK in the APK Analyzer.
2. Find the signing/signature section (a META-INF .RSA entry).

**You should see:** wording like "signature entry present, META-INF/...RSA (presence only; not verified)".

- PASS: honest wording.
- FAIL: any claim of "signed" or "verified".

**Send back:** the exact line.

---

## T33 — File format routing still sends each type to the right viewer

**What this is about:** a recent change unified the code that decides which viewer opens which file. This is a regression check that nothing got misrouted.

**Steps:**
1. From Download, tap `small.pcap` (Network viewer), and if you have a `.har`, `.oat` or `.vdex` file, tap it too.

**You should see:** each opens its correct viewer (Network for pcap/har, AndroidRuntime for oat/vdex).

- PASS: correct dispatch. FAIL: "no app for this file" or the wrong viewer.

**Send back:** which files opened and in what viewer.

---

## T34 — Corrupt settings can't crash the app (quarantine test)

**What this is about:** if the settings file gets corrupted, the app must boot with defaults, quarantine the bad file (so it can be reported), and never crash-loop. This needs Fix-B7 to have worked; otherwise mark NOT RUN.

**Steps:**
1. With settings.json corrupted (Fix-B7), fully close the app and relaunch.

**You should see:** the app boots with default settings, NO crash.
2. If you can re-register the internal root, check that `files/settings.json.corrupt` exists (the quarantine copy).
3. Now toggle several settings and edit one keybinding. Force-close, relaunch.

**You should see:** everything persisted.

- PASS: clean boot, quarantine file, persistence.
- FAIL: crash on launch, or settings lost after relaunch.

**Send back:** boot result and persistence result.

---

## T35 — A corrupt backup tar gives a typed failure and keeps your container alive

**What this is about:** restore must never destroy your working container when the archive is broken. It must say what failed and leave the system bootable.

**Steps:**
1. Make sure your REAL backup is in place (after T21's cleanup, step 6).
2. In the terminal, paste and run (truncates the backup in half):

```
python3 -c "p='/storage/emulated/0/CodespaceIDE/container-backup.tar.gz'; d=open(p,'rb').read(); open(p,'wb').write(d[:len(d)//2])"
```

3. Settings > Backup & Restore > tap **Restore** > confirm.

**You should see:** a typed failure message (corrupt archive) in the status/terminal, a fresh-install fallback path, and then: open a terminal and run `ls /`. The EXISTING container still boots.

- PASS: honest failure and container intact.
- FAIL: app crash, silent success, or a destroyed container.

**Send back:** the failure message and container alive yes/no.
4. Afterwards, tap **Back up now** so you have a valid backup again.

---

## T36 — Trash restores nested files to their original subfolder

**What this is about:** deleting moves files to a project trash. Restoring a file that lived in a subfolder must put it back in that subfolder, not dump it in the project root.

**Steps:**
1. In the terminal, paste and run:

```
mkdir -p src/sub && echo x > src/sub/Util.txt
```

2. In the Explorer, delete `src/sub/Util.txt` (it goes to trash).
3. Open the Trash view and restore it.
4. In the terminal, run `ls src/sub/`.

**You should see:** `Util.txt` is back in `src/sub/`.

- PASS: restored in place. FAIL: restored at trash root or project root.

**Send back:** where the file ended up.

---

## T37 — (OPTIONAL) Delete failures are reported honestly

**What this is about:** when the storage can't accept a delete (disk full, read-only), the app must say so per file instead of claiming a false success. Forcing this state cleanly on a phone is awkward. If you can't, mark `T37: NOT RUN — couldn't force a failure state`.

**Steps if you run it:**
1. Make a storage state fail (read-only parent or full disk), then try deleting a file in the Explorer.

**You should see:** honest failure text, the file still present; a bulk delete reports the real moved count versus failures.

- PASS: honest per-item failures, no false "moved N" claim.

**Send back:** what happened.

---

## T38 — New Folder works on the normal path

**What this is about:** after hardening all the edge cases, the normal case must still work.

**Steps:**
1. Explorer > **+** > **New Folder** > type `made-folder` > confirm.

**You should see:** the folder appears in the tree immediately.

- PASS: appears. FAIL: nothing created.

**Send back:** one line.

---

# PHASE 4 — THE POLYGLOT TEST PROJECT (T39-T96)

**What this phase is about:** the editor's intelligence: test runner lenses, honest test results, the Problems panel, autocomplete, jumps, search, undo and themes. We build ONE project containing Python, JavaScript, TypeScript and Kotlin files and run 58 tests against it.

## PREPARE THE PROJECT (do once, about 15 minutes)

**Step 1.** Go HOME, tap **New Project**, name it:

```
p5-poly
```

Create it, open it, and tap Trust when asked.

**Step 2.** Open its TERMINAL tab and install the two test runners. Paste and run one at a time (takes a few minutes):

```
apt-get install -y python3-pytest
```

```
npm init -y
```

```
npm install --save-dev jest typescript
```

**Step 3.** Create these four files. For each: Explorer > tap **+** > New File > type the name > paste the content > save.

**File 1, `test_math.py`:**

```
def add(a, b):
    return a + b

def test_add():
    assert add(1, 2) == 3

def test_sub_fails():
    assert add(2, 1) == 1

def test_mult_fails():
    assert add(2, 2) == 5
```

This has one passing test (`test_add`) and two deliberately failing tests.

**File 2, `sum.test.js`:**

```
const sum = (a, b) => a + b;
test('adds ok', () => { expect(sum(1, 2)).toBe(3); });
test('adds wrong', () => { expect(sum(1, 1)).toBe(3); });
describe('group', () => {
  test('nested pass', () => { expect(sum(0, 0)).toBe(0); });
});
```

One passing test, one failing test, one test nested inside a group.

**File 3, `util.ts`:**

```
export function unusedName(x: number): number {
  return x + 1;
}
```

**File 4, `Main.kt`:**

```
// a comment line to check highlighting
fun broken(): Int {
    val s: String = "a string value"
    return 42
}
```

---

## T39 — Test lenses only appear on REAL tests

**What this is about:** the "Run Test" labels (lenses) above test functions. An old bug gave lenses to any function in a class named `SomethingTest`, even without a test annotation. Kotlin needs the LSP: Extensions > Packages > install Kotlin LSP first if you haven't, then wait about 30 seconds after opening the file.

**Steps:**
1. Create `KtLensTest.kt` with exactly:

```
class SomethingTest {
    fun testing() { }
    @org.junit.Test fun annotated() { }
}
```

2. Open it and wait for the lenses.

**You should see:** a Run Test lens ONLY above `annotated()`. NOTHING above `testing()`.
3. Open `test_math.py`: lenses on all three test functions.
4. Open `sum.test.js`: lenses on `adds ok`, `adds wrong`, and the nested `nested pass`, plus a suite lens above `group`.

- PASS: annotated-only JVM lens, Python and JS lenses present.
- FAIL: a lens on `testing()`, which is the old class-name-heuristic bug.

**Send back:** which functions showed lenses.

---

## T40 — The run command is clean and honest

**What this is about:** the echoed test command must not hide errors with `2>/dev/null` (suppress output) or fake success with `|| echo`.

**Steps:**
1. Tap Run Test on `annotated()` in `KtLensTest.kt`.
2. Open the **Output** tab and find the echoed command line for the run.

**You should see:** a plain command like `./gradlew test ...` or for Python `python3 -m pytest ...`, with NO `2>/dev/null` and NO `|| echo` anywhere in it.

- PASS: clean command. FAIL: suppression fragments found.

**Send back:** the exact command line.

---

## T41 — One tap runs exactly one test

**What this is about:** tapping a single test's lens must run THAT test only, not the whole file or project.

**Steps:**
1. Open `test_math.py`, tap Run Test on `test_add` only.
2. Watch the test channel in the Output tab.

**You should see:** pytest runs only `test_math.py::test_add`; the node id with just that test appears in the output. No other test executes.
3. Open `sum.test.js` and tap the suite lens above `group`.

**You should see:** only the tests inside `group` run, not the whole file.

- PASS: single-test node id and container-scoped suite run.
- FAIL: the whole file or project ran.

**Send back:** the pytest node id line.

---

## T42 — Passing and failing are reported honestly

**What this is about:** the result line must show the real outcome with the real error visible.

**Steps:**
1. Run `test_add` (it passes).

**You should see:** a `PASSED` style line with exit 0.
2. Run `test_sub_fails` (it fails on purpose).

**You should see:** a `FAILED` style line with exit N, and ABOVE it pytest's real assertion traceback, nothing hidden.

- PASS: both lines, traceback visible.
- FAIL: silent failure or missing traceback.

**Send back:** both result lines.

---

## T43 — An untrusted project is refused, once

**What this is about:** the trust gate on test runs, same as the chat gate from T6.

**Steps:**
1. Create one more project `p5-untrust2`, open it, DON'T trust it.
2. Create a tiny `t.py`:

```
def test_ok():
    assert True
```

3. Tap its Run Test lens.

**You should see:** the Trust prompt appears once. Refuse it: the channel shows an honest "not trusted, run refused" line and nothing executed.
4. Tap Run Test again: still refused (refusal is free of prompts).
5. Trust the project, run again: it executes.
6. Tap Run Test once more: NO second prompt.

- PASS: prompt once, honest refusal, no re-prompt after trust.
- FAIL: ran while untrusted, or the prompt loops.

**Send back:** the refusal line.

---

## T44 — Double-tap gives an honest BUSY

**What this is about:** two runs fired at once must not interleave into garbage.

**Steps:**
1. On `test_math.py`, tap Run Test on `test_add`, and IMMEDIATELY tap Run Test on `test_sub_fails`.

**You should see:** the second tap is refused with a "already active" style BUSY line, the first run finishes cleanly, and the outputs never interleave.

- PASS: honest BUSY. FAIL: two interleaved runners.

**Send back:** the BUSY line.

---

## T45 — Debug Test: Python works, JS works, Kotlin honestly doesn't

**What this is about:** the debug-test feature. Python and JS support it; Kotlin/Java must say they don't, instead of faking a session.

**Steps, Python:**
1. Open `test_math.py`, tap the gutter next to the `assert` line of `test_add` to set a breakpoint (a dot appears).
2. Tap its Debug Test lens (or the Debug chip in the Tests pane).

**You should see:** the debug console attaches, execution STOPS at your breakpoint (the line highlights, variables and stack are visible). Tap **Continue**: the test finishes and its row flips to PASSED.

**Steps, JS (only if the npm install finished):**
3. Set a breakpoint inside `adds ok`, tap its Debug Test lens.

**You should see:** jest starts under `node --inspect-brk`, the debugger attaches, Continue stops at the breakpoint, Continue again finishes.

**Steps, Kotlin:**
4. Look at `annotated()` in `KtLensTest.kt`.

**You should see:** NO Debug lens or chip (honest absence until the future JVM decision).
5. Edge: breakpoint inside `adds wrong`, Debug Test, let it fail under the debugger.

**You should see:** the row ends FAILED (a debug crash is reported, not swallowed).

- PASS: Python attach and stop, honest JVM absence, failed-debug honesty.
- FAIL: fake session, or no stop at the breakpoint.

**Send back:** which languages attached and whether each stopped.

---

## T46 — A failing test lands in the Problems panel

**What this is about:** test failures feed the Problems panel so you can jump to them.

**Steps:**
1. Run `test_sub_fails`.
2. Open the **Problems** panel.

**You should see:** a row sourced from the test with the real assertion message.
3. Tap the row.

**You should see:** the editor jumps to the failing line in the right file.
4. Fix the test (edit it to `assert add(2, 1) == 1`), run again.

**You should see:** the stale row disappears.

- PASS: row, jump, and cleanup all work.
- FAIL: wrong file or line, or the stale row lingers.

**Send back:** a screenshot of the Problems panel after the failed run.

---

## T47 — Gutter marks track the run live

**What this is about:** the little run-state icons next to test lines.

**Steps:**
1. Run `test_sub_fails` and watch its line: blue dot while running, red cross when it fails.
2. Run `test_add`: blue, then green check.
3. Run the `group` suite: the suite line shows a merged state, and tests NOT covered by the run go dim.

- PASS: live transitions, honest dimming. FAIL: glyphs stuck or wrong.

**Send back:** one line.

---

## T48 — No leftover report files after runs

**What this is about:** the runner writes temp report files and must clean them up after parsing.

**Steps:**
1. After a pytest and a jest run, in the terminal paste and run:

```
ls
```

**You should see:** no `.codespace-test-result.xml` or `.json` leftovers in the project root.

- PASS: clean root. FAIL: leftover files.

**Send back:** "clean" or the ls output.

---

## T49 — The Tests pane tree is complete and honest

**What this is about:** the dedicated Tests panel with the full tree, Run All, Run Failed and Stop.

**Steps:**
1. Open the **Tests** bottom tab and tap **Refresh**.

**You should see:** a tree: `test_math.py` with 3 leaves, `sum.test.js` with its leaves indented under `group`.
2. Tap a leaf's Run: only that test runs.
3. Tap **Run All**: every leaf runs sequentially.
4. Tap **Stop** mid-batch: the current runner dies, the batch ends honestly.
5. After a mixed pass/fail batch, tap **Run Failed**: ONLY failed rows re-run (watch the channel; no passing test re-executes).
6. Switch to another project and back: the tree is the right project's, never foreign.

- PASS: tree correct, all four actions honest.
- FAIL: foreign or missing rows, or Run Failed re-running passes.

**Send back:** a screenshot of the tree and one line on Run Failed.

---

## T50 — "Run Unit Tests" is honest for non-gradle projects

**What this is about:** the bulk action must route through the real pipeline, not crash with a gradle error on a project that isn't gradle.

**Steps:**
1. In `p5-poly` (no gradle wrapper), find the build/tasks panel and tap **Run Unit Tests**.

**You should see:** it routes through the real test pipeline with an honest summary, or an honest "no tests found". NOT a cryptic gradle-wrapper failure.

- PASS: honest routing. FAIL: gradle error.

**Send back:** the summary line.

---

## T51 — Lenses don't double up

**What this is about:** two lens sources (LSP and built-in detector) must not both render a chip on the same line.

**Steps:**
1. Open `test_math.py` with the Python LSP active.

**You should see:** ONE chip per test line, never a doubled pair.

- PASS: single chips. FAIL: duplicates.

**Send back:** one line.

---

## T52 — Detection is debounced, typing stays smooth

**What this is about:** rescanning on every keystroke would jank the editor; the detector must wait for a pause.

**Steps:**
1. Type continuously in `test_math.py` for about 10 seconds (add spaces and new lines).

**You should see:** typing stays smooth; lens positions update about half a second after you STOP, not during typing.

- PASS: smooth typing, update after pause. FAIL: per-keystroke jank.

**Send back:** one line, smooth or stuttered.

---

## T53 — Cancel kills the process and the status stays CANCELLED

**What this is about:** cancellation must really stop the child process and never let a later run overwrite the CANCELLED status.

**Steps:**
1. Tap **Run All** in the Tests pane, then tap **Stop** partway.

**You should see:** output stops dead (no trailing lines after the stop), and the status reads CANCELLED.

- PASS: dead process, sticky status. FAIL: trailing output, or the status overwritten later.

**Send back:** the status after cancel.

---

## T54 — A failed build reports FAILED, long output is truncated with a header

**What this is about:** the build status must trust the exit code, not grep for success text; and huge output must be capped with a visible notice.

**Steps:**
1. Break the TS file: open `util.ts`, change the return line to exactly:

```
    return "wrong";
```

2. Run the TypeScript check task (task panel dropdown, or paste `npx tsc --noEmit` in the terminal through a task).

**You should see:** the status shows FAILED even if the output contains a "BUILD SUCCESSFUL" style line somewhere.
3. Run something very chatty like `npm install` (or `npm update`) and watch the output channel.

**You should see:** output keeps the FIRST 10000 lines with a visible truncation header.

- PASS: honest FAILED and truncation header.
- FAIL: success-looking status on a failed build.

**Send back:** the status line and whether the header appeared.

---

## T55 — Build-row matching is per-language

**What this is about:** a Python script merely PRINTING a compiler-shaped line must not create a build row. Only real compiler output from the right tool may.

**Steps:**
1. In the terminal, paste and run:

```
python3 -c "print('bad.py:42: error: fake error line')"
```

2. Check the Problems panel.

**You should see:** NO new row (Python is TRACEBACK-only in the matcher).
3. Now fix nothing and run the real tsc check on the broken `util.ts` (as in T54).

**You should see:** a real TS error row appears.
4. Run an `npm install`: no rows from its output.

- PASS: no fake row, real row appears.
- FAIL: the printed fake line became a row.

**Send back:** which rows appeared.

---

## T56 — Errors with no file path are honest display rows

**What this is about:** some build errors have no file location. Those rows must be clearly "(build output)" style, and tapping them must do nothing rather than crash or jump to nowhere.

**Steps:**
1. After the failing tsc/build, open the Problems panel and find the header rows (marked "(build output)" or similar).
2. Tap one.

**You should see:** nothing happens (no crash, no jump). Then tap a real file row: it jumps normally.

- PASS: inert header rows, working file rows.
- FAIL: crash or a jump to nowhere.

**Send back:** one line.

---

## T57 — The Explorer badge shows per-file counts

**What this is about:** the error-count badge on a file in the tree must show THAT file's problems, not the global count, and open pre-filtered.

**Steps:**
1. With the broken `util.ts` in the tree, look at its badge number.

**You should see:** a count matching only util.ts's own problems.
2. Tap the badge: the Problems panel opens pre-filtered with a "File: util.ts" style chip.
3. Tap the chip.

**You should see:** the filter clears and all files show.

- PASS: scoped badge and working chip. FAIL: global counts or no chip.

**Send back:** the badge number and chip behavior.

---

## T58 — The panel and the squiggles agree, live

**What this is about:** one source of truth for diagnostics, updating together with unsaved edits.

**Steps:**
1. Open the broken `util.ts`. Note the squiggle under the bad line and its matching Problems row (same message).
2. Insert 5 blank lines ABOVE the error, WITHOUT saving.

**You should see:** the squiggle AND the panel row both move down 5 together.
3. Fix the error (edit it out).

**You should see:** both clear.
4. Type a new error (change `return x + 1` to `return y + 1`).

**You should see:** the new row appears while the panel is open, no reopen needed.

- PASS: agreement through edits and live updates.
- FAIL: drift between gutter and panel.

**Send back:** one line.

---

## T59 — All three problem counts agree

**What this is about:** the badge on the RUN surface, the Explorer file badge, and the panel header must all show the same number at the same time.

**Steps:**
1. With a few problems present, read all three counts.

**You should see:** identical numbers, updating within about a second of each other, not a 3-second lag.

- PASS: same number, near-immediate. FAIL: stale disagreement.

**Send back:** the three numbers.

---

## T60 — The Problems filter menu actually filters

**Steps:**
1. Problems panel > the **...** (overflow) menu.
2. Tap "Show Errors Only": only errors remain.
3. Tap "Show All Problems": everything returns.
4. Tap "Focus Search": the search box gets focus.

- PASS: both filters work, focus works. FAIL: no-op menu items.

**Send back:** one line.

---

## T61 — Related "first usage" lines render (may be partial)

**What this is about:** some diagnostics carry extra "where it's used" lines. If the language server sends them, the panel must show them.

**Steps:**
1. Open `Main.kt`, add this line at the very top, and save:

```
import java.util.List
```

2. Wait for the diagnostic (the import is unused).

**You should see:** an "unused import" row. If your server sends related info, the row shows a "first usage" style sub-line. Tap the row: it jumps to the import.

- PASS: row plus jump; related sub-lines if the server provides them.
- PARTIAL: row and jump verified but no sub-lines (that can be the server's choice; note it, don't fail the row).

**Send back:** what you saw, full or partial.

---

## T62 — Lint results don't duplicate or jank

**Steps:**
1. With diagnostics present, close and reopen the file quickly several times; switch tabs fast.

**You should see:** no duplicate identical rows accumulate, no visible jank, no repeated lint bursts.

- PASS: clean single rows, smooth. FAIL: duplicates or jank.

**Send back:** one line.

---

## T63 — The build dropdown lists the full catalogue

**Steps:**
1. Open the build/tasks panel and tap its task dropdown.

**You should see:** the full catalogue (about 8 named tasks). Pick one: EXACTLY that task runs.

- PASS: full list, correct dispatch. FAIL: missing tasks or wrong-task dispatch.

**Send back:** the tasks listed.

---

## T64 — Interrupted runs are marked honestly

**Steps:**
1. Start a build, then CLOSE the task panel mid-run.

**You should see:** the tile ends FAILED with "interrupted" text, not vanished or stuck RUNNING.
2. Force-stop the app mid-build, relaunch.

**You should see:** the task shows FAILED "interrupted by app restart" and you can re-run it.

- PASS: both interrupted states honest. FAIL: stuck RUNNING forever.

**Send back:** both outcomes.

---

## T65 — Accepting a completion replaces exactly what you typed

**What this is about:** when autocomplete inserts a suggestion, it must replace ONLY your typed fragment, not eat extra text around it. Needs a Python LSP: install via Extensions > Packages if missing, then wait about 30 seconds after opening the file.

**Steps:**
1. In `test_math.py`, on a new line, type `    ad` and wait for the completion popup.
2. Highlight the `add(` entry and press **Tab** (the accessory key).

**You should see:** exactly your fragment replaced, nothing more.
3. Snippet route: on a new line type `for`, and if a for-loop snippet is offered, accept it with Tab.

**You should see:** tab stops engage (first default text highlighted), no raw `$1` visible in the text.
4. Commit-char route: with an LSP item highlighted, type `(`.

**You should see:** the item commits, then `(` is appended after it.

- PASS: all three routes clean. FAIL: over-replacement or raw `$1`.

**Send back:** which routes worked.

---

## T66 — Auto-import completes the import cleanly

**What this is about:** accepting an auto-import suggestion must add the import line above and leave no typed leftovers.

**Steps:**
1. Create a new TS file `consumer.ts` with just this line:

```
let z = 1;
```

2. On the next line type `unusedNa` and wait for the popup; accept the entry that offers to ADD the import.

**You should see:** the import line appears at the top and the accepted name lands cleanly, with no leftover characters of your typed prefix.

- PASS: clean import and exact span. FAIL: leftover characters.

**Send back:** the resulting lines.

---

## T67 — The popup follows the arrow keys

**Steps:**
1. Trigger completions (type `ad` in `test_math.py`).
2. Press the keyboard's **Down**, **Down**, **Up** arrows.
3. Press **Tab**.

**You should see:** the highlight moved through the list, and Tab accepts the HIGHLIGHTED row, not row 1.

- PASS: navigation and highlighted accept. FAIL: Tab always takes the first row.

**Send back:** one line.

---

## T68 — Enter behaves differently with the popup open vs closed

**Steps:**
1. With the completion popup VISIBLE, press **Enter**.

**You should see:** the selected item is accepted and NO newline is inserted.
2. With the popup CLOSED, press Enter.

**You should see:** a normal newline.

- PASS: both behaviors correct. FAIL: popup-Enter inserting a newline, or closed-Enter eating it.

**Send back:** one line.

---

## T69 — The server's own ordering is respected

**Steps:**
1. In `consumer.ts`, replace everything with:

```
const x = { alpha: 1, zeta: 2, mid: 3 };
x.
```

2. With the cursor after `x.` wait for the member list.

**You should see:** the server's own order (alphabetical for tsserver) respected, no fuzzy reshuffling.

- PASS: server order visible. FAIL: visibly reshuffled.

**Send back:** the order you saw.

---

## T70 — Signature help and the popup coexist

**Steps:**
1. In `test_math.py` type `add(` and note the parameter hint above the cursor.
2. Keep typing `1`: the completion popup opens below.

**You should see:** BOTH visible at once, hint above, popup below, neither hiding the other.

- PASS: coexist. FAIL: one erases the other.

**Send back:** one line (screenshot ideal).

---

## T71 — Hover works even without a language server

**Steps:**
1. Open a file type with no LSP running (a `.sh` or a `.md` file).
2. Long-press over a common keyword like `val`, `fun`, or `def`.

**You should see:** a curated doc popup (the built-in hover), not silence.

- PASS: popup appears. FAIL: nothing.

**Send back:** which popup showed.

---

## T72 — The detail panel follows the highlighted item

**Steps:**
1. Trigger completions where a local word suggestion shares a label with an LSP one (type `ad` in `test_math.py`: the local `add` and LSP items).
2. Arrow-highlight each in turn and watch the detail/doc panel.

**You should see:** the doc shown belongs to the SELECTED item, even when labels collide.

- PASS: correct doc per selection. FAIL: same doc for both.

**Send back:** one line.

---

## T73 — No autocomplete memory leaks across languages

**Steps:**
1. In `test_math.py`, accept the completion for `add`.
2. Open `Main.kt` and type `ad`.

**You should see:** the Kotlin list is ordered fresh; your recent Python `add` acceptance gave nothing a boost.

- PASS: no carryover. FAIL: suspicious reordering.

**Send back:** one line.

---

## T74 — Words from the current document are suggested

**Steps:**
1. In `test_math.py`, add at the very end:

```
def myCustomThing():
    pass
```

2. Save, then elsewhere in the file type `myCust`.

**You should see:** a suggestion for `myCustomThing` (a word from this document).

- PASS: document-word suggestion present. FAIL: absent.

**Send back:** one line.

---

## T75 — The signature boost only applies inside real call parens

**Steps:**
1. In `test_math.py`, type `foo("` (an unterminated string context) and then an identifier.

**You should see:** no call-argument boost, because the earlier `(` is inside a string, not a real call.
2. Elsewhere type `bar(` followed by an identifier outside strings.

**You should see:** the boost applies there (signature-shaped items rank up).

- PASS: boost gated to real call contexts. FAIL: boost inside strings.

**Send back:** one line.

---

## T76 — Auto-import lands at the right offset; failures are logged

**Steps:**
1. Repeat T66's accept.

**You should see:** the cursor ends right after your accepted word, not a column early or late, even though the import line was inserted above.
2. Rename `util.ts` to `utilX.ts` in the Explorer, then trigger the auto-import again.

**You should see:** the import fails, and the Output/lsp channel shows a `[AutoImport]` failure line. Never silence.
3. Rename it back to `util.ts`.

- PASS: right offset, logged failure. FAIL: shifted text or silent failure.

**Send back:** the offset behavior and the failure line.

---

## T77 — Squiggles restore instantly after tab switching

**What this is about:** per-file diagnostic state is now stored per file, so returning to a tab must restore it instantly, without a visible re-lint.

**Steps:**
1. Open `test_math.py` with squiggles present. Switch to `Main.kt`, then BACK.
2. Tap a line's gutter to add a bookmark (if the gutter bookmark exists in your build), leave the editor entirely (go to the Terminal), and come back.

**You should see:** squiggles reappear immediately (no blank-then-lint flash), and the bookmark survives.

- PASS: instant restore, bookmark survival. FAIL: flash or lost bookmark.

**Send back:** one line.

---

## T78 — The same file reached by a different spelling stays one file

**What this is about:** path identity. A file edited via a relative path spelling (like `./test_math.py`) must be recognized as the SAME file the editor has open.

**Steps:**
1. With `test_math.py` open in the editor, in the terminal paste and run:

```
echo "# touched via other spelling" >> ./test_math.py
```

2. Look at the editor.

**You should see:** the editor picks up the external change cleanly (refresh or auto-reload). No duplicate tab ghost, no dirty-state confusion, one single buffer identity.

- PASS: one identity. FAIL: a split identity or stale content that only a reopen fixes.

**Send back:** what the editor did.

---

## T79 — The gold jump band lands on the right line, smoothly

**What this is about:** "go to line" and problem jumps show a gold highlight band that blinks about 6 seconds, then auto-clears. The rewrite must behave identically.

**Steps:**
1. Use the Go to Line bar: enter `5` in a file with more than 5 lines.

**You should see:** the band appears ON line 5, blinks, auto-clears.
2. While the band is blinking, type and scroll.

**You should see:** no perceptible stutter.
3. Tap a Problems row and a test result row: same band, correct lines.

- PASS: right lines, right timing, smooth. FAIL: wrong line, jank, or band never clears.

**Send back:** one line.

---

## T80 — The second jump's band gets a full blink

**What this is about:** an old bug: a jump fired 2 seconds after a previous one got its band cut short by the earlier blink timer.

**Steps:**
1. Go to Line 5. Within about 2 seconds, Go to Line 10.

**You should see:** the SECOND band blinks the full ~6 seconds.

- PASS: full blink. FAIL: band cut short.

**Send back:** one line.

---

## T81 — Menu actions really act

**Steps:**
1. Open the command palette (the palette entry point), pick "Go to File", choose a file. Note whether the palette stays open (that's the expected behavior; if it differs, note it).
2. Make a file dirty, open the Edit menu, tap **Save File**.
3. In the terminal run `cat <that file>` to verify the content.

**You should see:** the file really saved (content matches what you typed).

- PASS: save is real. FAIL: fake save.

**Send back:** the disk content check.

---

## T82 — Case-insensitive replace actually replaces

**What this is about:** an old bug made Replace All with "match case OFF" skip lowercase hits.

**Steps:**
1. Open a file containing both `x` and `X` (add some if needed: `echo "x X xX" > casefile.txt`, open it).
2. Open the find/replace panel: search `x`, case sensitivity OFF, Replace All with `y`.

**You should see:** the lowercase hits ARE replaced, case-insensitively.
3. Undo the change.
4. Edge: make one project file unreadable (`chmod 444 casefile.txt`), then run a folder-wide Replace All.

**You should see:** a RED failure line in the search results for the unreadable file, other files still replaced, and an open tab of a replaced file shows new content without reopening.
5. `chmod 644 casefile.txt` to restore.

- PASS: both behaviors. FAIL: silent misses, no red line.

**Send back:** the red failure line.

---

## T83 — Folder search hits jump to the exact match line

**Steps:**
1. Run a folder search for `return`.
2. Tap a hit line.

**You should see:** the jump opens the right file AT the hit line, not at the top.

- PASS: precise landing. FAIL: opens at top or wrong line.

**Send back:** one line.

---

## T84 — Symbol search jumps correctly, and warms up honestly

**Steps:**
1. Open the symbol search panel (the Ctrl/Cmd+T-style symbol search) and search `add`.

**You should see:** workspace symbols listed; tapping one opens the file AND jumps to the symbol's line.
2. Warm-up: right after a fresh app launch, run a symbol search immediately.

**You should see:** results may be empty while indexing warms, but when indexing completes they populate WITHOUT you retyping.

- PASS: jumps land, warm search populates. FAIL: dead results or stuck empty.

**Send back:** one line per sub-check.

---

## T85 — The symbol panel is fresh every open

**Steps:**
1. Close and reopen the symbol search panel quickly.

**You should see:** it's ready and fresh, no stale list from a previous file, no waiting.

- PASS: fresh. FAIL: stale.

**Send back:** one line.

---

## T86 — Each file remembers its own find query

**Steps:**
1. In `Main.kt`, run a find for `return`, then close the find bar.
2. Switch to `util.ts` and reopen the find bar.

**You should see:** `util.ts`'s own last query (or empty), NOT `Main.kt`'s `return`.

- PASS: isolated. FAIL: query bled across files.

**Send back:** what B showed.

---

## T87 — Split views share one undo history

**Steps:**
1. Split the editor on `test_math.py` (two views of the same file).
2. Type a character in view A. Tap UNDO in view B.

**You should see:** A's edit steps back. One shared history, undo works from either view.

- PASS: cross-view undo. FAIL: undo does nothing from the other view.

**Send back:** one line.

---

## T88 — The Explorer preview shows post-chat-apply content

**Steps:**
1. In the AI chat, send: `rename the function in util.ts to renamedThing` and approve the apply.
2. View `util.ts` through the Explorer's preview (single-tap the file).

**You should see:** the POST-apply content (the new name), never the stale pre-apply buffer.

- PASS: fresh preview. FAIL: stale content.

**Send back:** one line.

---

## T89 — Undo and snapshot restores are visible in the open tab

**Steps:**
1. In the chat, tap **Undo last Apply**.

**You should see:** the `util.ts` TAB visibly reverts to the old name, no manual reopen needed.
2. Edit a file, wait about 30 seconds for a snapshot, then open the Timeline (or Explorer Local History) and restore the earlier snapshot.

**You should see:** the tab visibly reverts to that content too.

- PASS: both reverts visible. FAIL: tab shows old content until reopen.

**Send back:** one line per surface.

---

## T90 — Saving a read-only file gives an honest error

**Steps:**
1. In the terminal, paste and run:

```
chmod 444 util.ts
```

2. Edit `util.ts` in the editor, then tap Save (File > Save or the save action).

**You should see:** an ERROR notification, "Disk write failed" style, and the dirty marker STAYS. Never a fake "Saved".
3. Paste and run `chmod 644 util.ts`, then save again.

**You should see:** the dirty marker clears.

- PASS: honest error and recovery. FAIL: false success.

**Send back:** the notification text.

---

## T91 — A kill mid-edit never corrupts disk state

**Steps:**
1. Edit a file but do NOT save. Force-stop the app.
2. Relaunch. In the terminal run:

```
cat <the file>
```

**You should see:** the DISK content is the pre-edit version (never falsely saved). If a restore dialog appears, it offers your unsaved edits; declining keeps the disk untouched.

- PASS: honest disk, restore offer works. FAIL: unsaved content silently on disk.

**Send back:** what disk had.

---

## T92 — Debug frames, breakpoint rows and TODO rows jump precisely

**Steps:**
1. Debug `test_add` with a breakpoint (as in T45), pause, open the stack/VARIABLES panel and tap a DIFFERENT stack frame.

**You should see:** the jump lands ON that frame's line in the right file.
2. Tap a breakpoint row in the panel: same precision.
3. Add this line in `Main.kt`, wait for indexing, find the TODO row (in the TODO list if your build has one) and tap it:

```
// TODO: check jump
```

**You should see:** file plus exact line.

- PASS: all three land precisely. FAIL: line-1 or wrong-file jumps.

**Send back:** one line per jump.

---

## T93 — TextMate colors match Dark+ (screenshot test)

**What this is about:** the bundled theme must color Kotlin exactly like VS Code's Dark+.

**Steps:**
1. Open `Main.kt` with TextMate highlighting ON (In-Project Settings, the TextMate toggle).
2. Look at the colors.

**You should see:** the comment line greenish (Dark+ green), the string reddish, keywords bluish. NOT a flat generic palette.
3. Open `util.ts` (no bundled grammar for it).

**You should see:** clean built-in fallback highlighting, not a blank file.

- PASS: themed Kotlin, clean TS fallback. FAIL: blank or wrong palette.

**Send back:** screenshots of both files. I'll compare against the exact palette.

---

## T94 — The TextMate toggle works without a restart

**Steps:**
1. In-Project Settings > toggle "TextMate Highlighting" OFF > edit the file.

**You should see:** highlighting switches to built-in.
2. Toggle it ON > edit again.

**You should see:** it returns on the next edit. No restart needed.

- PASS: toggle effective. FAIL: dead toggle.

**Send back:** one line.

---

## T95 — Highlighting is stable under scrolling and typing

**Steps:**
1. Open a file with 50+ lines (paste more content into `test_math.py` if needed), scroll up and down for about 20 seconds, editing occasionally.

**You should see:** stable colors, no drift across lines, no flicker.

- PASS: stable. FAIL: any drift.

**Send back:** one line.

---

## T96 — Package installs land in Extensions History, failures included

**Steps:**
1. Extensions tab > History: your earlier installs (Kotlin LSP, pytest deps and so on) are listed with real completion status.
2. Airplane mode ON, then try installing any small package: it fails.
3. Back in History, open the failed entry.

**You should see:** the REAL error detail (the E: line from apt), not just "failed". Airplane OFF after.

- PASS: entries plus honest failure detail. FAIL: empty history or bare "failed".

**Send back:** the failure line.

---

# PHASE 5 — PERFORMANCE READS (T97-T102)

**What this phase is about:** the app now logs timing marks for its own startup and heavy phases. T97's pasted lines are the deliverable: they feed the performance ranking backlog. Do this in the same session, same project.

## T97 — The startup perf chain completes end to end

**Steps:**
1. Fully close the app. Cold-start it and open the same project.
2. Open the **Output** tab and scroll looking for lines beginning with `[perf]`.

**You should see:** a startup chain (app-create, prefs-backup-restore, stores-init, textmate-init, onCreate-end, shell-first-frame) with elapsed times and per-phase deltas; a shell-first-frame total; and on your FIRST chat message of the session, an MCP discovery duration line.
3. Expand a large folder in the Explorer.

**You should see:** a tree-build `[perf]` line with duration and node count.

- PASS: the chain completes end to end, every phase logged.
- FAIL: gaps in the chain, or no `[perf]` lines at all.

**Send back:** PASTE ALL THE [perf] LINES VERBATIM. This is the one test where the paste is the deliverable.

---

## T98 — Snapshots happen once per edit, not on a timer

**What this is about:** local history snapshots must follow your edits, not fire every 20 seconds regardless.

**Steps:**
1. Edit a project file, then leave the editor idle for 2+ minutes.
2. In the terminal, paste and run:

```
ls .versionhistory/ | tail -5
```

**You should see:** roughly ONE new snapshot per recent edit, not a new entry every 20 seconds of idleness. Snapshots cover nested source dirs but NOTHING from `.git`, `node_modules` or `build`.

- PASS: sane cadence, correct scope. FAIL: snapshot spam or missing edits.

**Send back:** the ls output and a note on the time gaps.

---

## T99 — (mark NOT RUN if you can't get RAM low) Snapshots pause when RAM is low

**Steps:**
1. Open a heavy project plus a terminal plus the preview; get the status-bar RAM readout to turn red. If you can't, mark NOT RUN.
2. While red, watch `.versionhistory` (the ls from T98): NO new snapshots appear.
3. Close some load: RAM recovers and snapshots resume.

- PASS: pause and resume. FAIL: churning snapshots at low RAM.

**Send back:** red or not-red, and what the snapshot dir did.

---

## T100 — Chat session saves don't jank the UI

**Steps:**
1. Create 2 or 3 chat sessions. Send messages in one, rate a reply.
2. Switch, rename, restore sessions.

**You should see:** no jank, and other sessions' transcripts untouched when reopened.

- PASS: correct and smooth. FAIL: cross-session contamination.

**Send back:** one line.

---

## T101 — Notification history survives a mid-burst kill

**Steps:**
1. Trigger a build (a notification burst), force-stop within a few seconds, relaunch.

**You should see:** the build notifications that fired persist in history.
2. Dismiss one, relaunch: it stays dismissed. Toggle a notification setting: applies immediately.

- PASS: no lost bursts, sticky dismissal. FAIL: lost or resurrected rows.

**Send back:** one line.

---

## T102 — The op strip scrolls per line and stays scrollable

**Steps:**
1. Start a long chatty install (for example `apt-get update` via the Package Manager Refresh).

**You should see:** the operation strip auto-scrolls per output line, and you can grab it and scroll back mid-run. Close and reopen the strip: same content, still scrollable.

- PASS: per-append scroll and manual scroll. FAIL: stuck at top.

**Send back:** one line.

---

# PHASE 6 — GIT AND SOURCE CONTROL (T103-T120)

**What this phase is about:** the SCM panel: commits must only include what you staged, restores must be reversible, identity must never be faked, and your GitHub token must never appear in a running process's command line.

**Get there:** side panel > the GIT / Source Control panel on `p5-poly`. If the project isn't a repo yet, run `git init .` in its terminal first (the Publish flow can also init). Create three files in the terminal:

```
echo alpha > a.txt
```

```
echo beta > b.txt
```

```
echo gamma > c.txt
```

---

## T103 — Commit takes only what you staged, asks before sweeping

**What this is about:** the #1 data-safety rule in git: commit must include exactly what you chose.

**Steps:**
1. Edit all three files (in the terminal, for example `echo new >> a.txt` and the same for b and c).
2. In the GIT panel, stage ONLY `a.txt` via its row's **+** button.
3. Type a commit message, tap **Commit**.
4. In the terminal, paste and run:

```
git log -1 --stat
```

**You should see:** ONLY a.txt in that commit.
5. Unstage everything (leave b and c dirty), tap Commit again.

**You should see:** a "Stage all and commit?" dialog showing the right count (2). Tap Cancel: nothing committed, nothing staged (`git log -1 --stat` unchanged). Redo and confirm: commits both.
6. With a fully clean tree, tap Commit.

**You should see:** an honest "Nothing to commit, no staged changes" style message. No dialog, no git call.

- PASS: all three flows honest. FAIL: commit swept unstaged files, or a silent no-op.

**Send back:** the stat lines of the commits you made.

---

## T104 — Publish states its staging scope

**Steps:**
1. Open the Publish flow on a fresh, un-pushed repo.

**You should see:** a line stating "Publish stages ALL pending file(s)". Publish works end to end (repo created, code pushed).

- PASS: line present, publish succeeds. FAIL: hidden scope or failed publish.

**Send back:** the dialog text.

---

## T105 — Restores are confirmed and fully reversible

**What this is about:** restoring old content must ask first and keep a way back.

**Steps:**
1. In the terminal, paste and run:

```
echo changed > a.txt
```

2. Wait about 30 seconds (a snapshot forms), open the Timeline for `a.txt`, and tap **Restore** on the earlier snapshot.

**You should see:** a CONFIRM dialog naming the file. Confirm.
3. Check the Timeline again.

**You should see:** content restored AND a NEW snapshot entry appeared (the pre-restore capture). Tap Restore on THAT one: `changed` comes back. Fully reversible.
4. Repeat the whole flow in the Explorer's Local History dialog (same confirm and reversibility).

- PASS: both surfaces confirm and reverse. FAIL: instant overwrite with no capture.

**Send back:** reversibility yes/no on each surface.

---

## T106 — The panel refreshes itself without flicker

**Steps:**
1. Keep the GIT panel open and visible.
2. Edit and save a file in the editor; delete a file in the Explorer; run `git commit -am "x"` in the terminal.

**You should see:** the change counts update WITHOUT manual refresh (immediately for git-touching ops, within about 30 seconds for plain edits), and NO loading-spinner flicker during the silent refreshes.
3. Close the panel, make more changes, reopen.

**You should see:** fresh state, not stale.

- PASS: fresh without flicker. FAIL: stale counts or spinner strobing.

**Send back:** which triggers updated and roughly how fast.

---

## T107 — Status is fast and never fights your terminal git

**Steps:**
1. Open the GIT panel and time the status load.

**You should see:** it loads in about 1 to 2 seconds.
2. With the panel open, run `git status` in the terminal.

**You should see:** it works instantly, no "index.lock" error.

- PASS: fast and concurrent. FAIL: multi-second status or lock errors.

**Send back:** roughly how long the status took.

---

## T108 — Timelines work inside subdirectory-of-outer-repo projects

**What this is about:** projects that sit inside a bigger repo used to show "No timeline available".

**Steps:**
1. If you don't have one, make it: in the terminal, paste and run:

```
git init /storage/emulated/0/p5-outer
```

then create a small project (or copy `p5-poly`'s shape) inside that outer repo, commit it, and open it.
2. Open the Timeline for a committed file.

**You should see:** git history rows DO appear. Tap a commit row: a diff shows THAT commit's changes to THIS file.
3. Check a file OUTSIDE the repo.

**You should see:** honestly empty, no foreign history.

- PASS: rows, tap, honest empty. FAIL: dead rows or foreign history.

**Send back:** one line.

---

## T109 — Branch labels: local vs remote

**Steps:**
1. In the terminal, paste and run:

```
git checkout -b feature/test
```

2. Open the Branches dialog.

**You should see:** `feature/test` labeled LOCAL; `origin/main` (after a push) labeled remote. If you create a local branch with a confusing name (like `origin-main`), it still shows LOCAL.

- PASS: correct labels. FAIL: mislabeled.

**Send back:** the labels you saw.

---

## T110 — Publish refuses when origin already exists

**What this is about:** publishing a repo that already has a remote would create an orphaned GitHub repo.

**Steps:**
1. On your already-pushed repo, open Publish again.

**You should see:** an honest abort BEFORE anything happens, like "already has an origin remote; use Push instead".
2. Nothing was created on GitHub.

- PASS: abort, nothing created. FAIL: a second GitHub repo appeared (tell me and I'll help clean it up).

**Send back:** the abort message.

---

## T111 — Rename diffs show clean paths

**Steps:**
1. In the terminal, paste and run:

```
git mv a.txt a-moved.txt
```

2. View the staged rename diff in the pane.

**You should see:** the old path WITHOUT the `a/` prefix (reads `a.txt`, not `a/a.txt`).

- PASS: prefix stripped. FAIL: `a/a.txt` visible.

**Send back:** the diff's header lines.

---

## T112 — HEAD badge plus honest diverged pulls

**Steps:**
1. Commit once more, open the History dialog.

**You should see:** the LATEST commit shows a HEAD badge.
2. Diverge a branch to force a non-fast-forward. In the terminal, paste and run these one at a time:

```
git checkout -b diver
```

```
echo z > z.txt && git add . && git commit -m z
```

```
git checkout main
```

```
echo y > y.txt && git add . && git commit -m y
```

3. Now tap **Pull** in the pane.

**You should see:** an HONEST failure (no surprise merge commit), with explicit Merge/Rebase buttons offered. Tap **Merge**: it merges cleanly.

- PASS: badge, honest failure, working merge. FAIL: auto-merge during pull.

**Send back:** the failure message and merge result.

---

## T113 — Commit identity is never fabricated

**What this is about:** an old bug invented a fake identity ("VN Code User") when none was configured, silently attributing commits to a phantom.

**Steps:**
1. Sign OUT of the app (Settings, GitHub sign-out).
2. In the terminal, clear any git identity for this repo:

```
git config --unset user.name; git config --unset user.email
```

3. Stage a file (`echo t > t.txt`, then `git add t.txt`), and tap Commit in the pane.

**You should see:** an HONEST typed failure with fix instructions. NO silent fabricated identity. Check `git log -1`: nothing was committed.
4. Sign back in, tap Commit again.

**You should see:** if an old fabricated identity had been configured, it's upgraded in place to your signed-in account (check `git config user.name`); if you had a REAL identity configured, it stays untouched.

- PASS: honest failure, in-place upgrade, real configs preserved. FAIL: fabricated commit or clobbered identity.

**Send back:** the failure text and the config values before and after.

---

## T114 — Operations gate against each other

**Steps:**
1. Start a slow git op (a pull or a large commit), and WHILE it runs, tap **+** on a file row or Stage All.

**You should see:** a "Wait for current operation" style message, row icons disabled while busy, no index.lock crash.

- PASS: gated and honest. FAIL: concurrent gits racing.

**Send back:** the message.

---

## T115 — No credential leak in the running git command line

**What this is about:** credentials must ride through a temp helper file, not appear in the process arguments (which other processes can read).

**Steps:**
1. In the terminal, paste and run this watcher (it prints git process lines as they appear):

```
while true; do cat /proc/*/cmdline 2>/dev/null | tr '\0' ' ' | grep -a git && echo ---; sleep 0.2; done
```

2. While it runs, tap **Push** in the SCM pane.
3. Watch the printed git lines during the push.

**You should see:** a `/tmp/.gitcred-` style helper path in the args, NO base64 Authorization text, NO token text anywhere.
4. After the push, paste and run:

```
ls /tmp/.gitcred-*
```

**You should see:** "No such file or directory" (helpers cleaned up).
5. Stop the watcher with Ctrl+C.

- PASS: push succeeds, no token in cmdline, helpers gone. FAIL: any credential text visible. If you see token-like text, DO NOT paste it to me; just say "token visible in cmdline".

**Send back:** a sample of the watcher's git lines (safe if clean).

---

## T116 — Private repo clone works through the credentials

**Steps:**
1. SCM panel > **Browse My Repos** > clone one of your PRIVATE repos.

**You should see:** the clone succeeds with the stored credentials, no raw auth error.

- PASS: clean private clone. FAIL: auth error.

**Send back:** cloned yes/no.

---

## T117 — Restore into a read-only file is honest

**Steps:**
1. In the terminal, paste and run:

```
chmod 444 b.txt
```

2. Timeline or Local History > Restore an earlier `b.txt`.

**You should see:** an ERROR, "file unchanged" style; disk untouched.
3. Paste and run `chmod 644 b.txt`, restore again.

**You should see:** SUCCESS and the editor refreshes.

- PASS: honest failure then success. FAIL: fake success while the file never changed.

**Send back:** both notifications.

---

## T118 — Scheduled tasks are blocked in untrusted projects

**Steps:**
1. In `p5-untrust2` (create a new untrusted project if you trusted it), ask the chat:

```
schedule a task that runs echo hi in one minute
```

2. Wait for the fire time.

**You should see:** the task does NOT run, and a "Scheduled task blocked" style notification appears.
3. Trust the project, reschedule the same task.

**You should see:** the next occurrence runs. Also: any task you created before the update in a trusted project still runs (grandfathered).

- PASS: blocked, then runs after trust; grandfathered intact. FAIL: ran while untrusted.

**Send back:** the notification text.

---

## T119 — Dismissing the autosave dialog keeps your backup files

**What this is about:** one stray tap outside the restore dialog must not destroy the recovered edits.

**Steps:**
1. Make a file dirty, force-stop the app, relaunch: the autosave-restore dialog appears.
2. Tap OUTSIDE the dialog (dismiss it).
3. Force-stop, relaunch again.

**You should see:** the dialog appears AGAIN (the backup was kept). Choose **Restore**: content and dirty state come back.

- PASS: dismiss keeps, restore works. FAIL: the backup vanished on dismiss.

**Send back:** dialog reappeared yes/no, and restore result.

---

## T120 — Restore reports per file, never a blanket success

**Steps:**
1. Have two dirty files, force-stop while both are dirty, relaunch, close the dialog, open only ONE of the files, force-stop, relaunch again.
2. Read the dialog's count message.

**You should see:** honest per-file wording, "restored" and "kept (not open this session)" style counts. Never a blanket "Edits restored" claim for files that couldn't be restored.

- PASS: honest per-file counts. FAIL: blanket claim.

**Send back:** the exact count message.

---

# PHASE 7 — THE AI CHAT SURFACE (T121-T139)

**What this is about:** chat behaviors that must be honest: nothing silently lost on restart, long output visibly truncated, queued messages delivered in order, failures named as failures. Open the AI chat panel on `p5-poly`.

---

## T121 — Attachments survive an app restart

**Steps:**
1. Attach a file via the chat's attach chip, and send a second message containing the text `#util.ts`.
2. Let both replies finish. Force-close the app, reopen the same chat session.

**You should see:** the thread restored with both messages.
3. Send this follow-up in the restored thread:

```
what did I attach earlier?
```

**You should see:** the answer reflects the attached file's CONTENT (the attachment wasn't lost on restore).

- PASS: restored thread, attachment-aware follow-up. FAIL: messages gone or attachment-blind answer.

**Send back:** the follow-up answer (short).

---

## T122 — Long output shows visible truncation markers

**Steps:**
1. Ask the chat:

```
run this command: seq 1 60000
```

**You should see:** the tool result carries a "[... TRUNCATED ...]" style marker with real counts.
2. Make a big file and ask the chat to read it:

```
python3 -c "open('big.txt','w').write('x'*9000)"
```

then:

```
read big.txt
```

**You should see:** the same truncation marker.
3. Edge: create 520 junk files, ask the chat to search `x` across the project, then clean up:

```
for i in $(seq 1 520); do echo x > f$i.txt; done
```

```
ls f*.txt | xargs rm
```

**You should see:** the search reports a scan cap note, not silent incompleteness.

- PASS: all markers present. FAIL: silent cut-offs.

**Send back:** the marker lines.

---

## T123 — Queued messages deliver in order; cancel drops only the queue

**Steps:**
1. While a reply is STREAMING, send message A, then message B.

**You should see:** a queue chip like "Queued: A (+1 more queued)".
2. Let the streaming reply finish.

**You should see:** A sends first, then B. Both answered, none replaced.
3. Repeat, but this time tap the cancel (x) while A and B are queued.

**You should see:** the chip clears and NEITHER sends.

- PASS: FIFO delivery and honored cancel. FAIL: a vanished message.

**Send back:** the chip text and delivery order.

---

## T124 — Export failures are named, not faked

**Steps:**
1. With NO project open (HOME screen), try the chat export action.

**You should see:** an honest "export failed, no project open" style result. No fake success file.
2. Open the project. In the terminal, paste and run:

```
mkdir -p .codespace/exports && chmod 555 .codespace/exports
```

3. Export again.

**You should see:** the error names the WRITE failure (permissions/disk), not something vague.
4. Restore with `chmod 755 .codespace/exports`.

- PASS: both honest. FAIL: silent failure or success-shaped nothing.

**Send back:** both messages.

---

## T125 — Approval timeouts say timeout, not "rejected by user"

**Steps:**
1. Set tool approval to MANUAL if the app has that setting.
2. Trigger a tool approval, then IGNORE the card for 10 minutes (go run other phases; come back).

**You should see:** the turn ends with an honest auto-TIMEOUT message, not "rejected by user", and the chat stays usable.
3. Trigger two gated calls back to back.

**You should see:** the second approval card appears only after the first resolves.

- PASS: honest timeout, serialized cards. FAIL: mislabeled rejection or stuck chat.

**Send back:** the timeout message.

---

## T126 — MCP discovery failures show the real error

**Steps:**
1. Add an MCP server whose command is `nonexistent_binary_xyz`, then tap Refresh.

**You should see:** the server's row shows "Discovery failed: ..." with the real detail inline.
2. Delete a server whose tools WERE discovered, then ask the chat:

```
what tools do you have?
```

**You should see:** its mcp_ tools no longer appear. Re-add the server: tools return.

- PASS: both flows honest. FAIL: zombie tools.

**Send back:** the discovery-failed line.

---

## T127 — A hanging MCP tool is bounded and stoppable

**Steps:**
1. Add an MCP server whose command is `sleep 9999`, then trigger a tool call on it and tap **Stop**.

**You should see:** the turn ends promptly (not the full 90s), chat usable.
2. Re-trigger and let it hang without touching anything.

**You should see:** at about 90 seconds, an honest timeout message appears and the chat stays usable, no infinite spinner.
3. Delete the hanging server.

- PASS: cancel and bound. FAIL: unkillable turn.

**Send back:** the timeout message.

---

## T128 — Key labels persist and don't sit in plain storage

**Steps:**
1. Set a custom label on a key slot in the chat AI-keys settings. Force-close, relaunch.

**You should see:** the label persists.
2. If you registered the internal root (Phase 3 Fix-B7), look in shared_prefs for the chat key pool file.

**You should see:** no "labels" entry in PLAIN prefs (labels live in the secure store). If unreachable: mark that half N/A.

- PASS: label persists, no plain labels entry. FAIL: label lost.

**Send back:** persistence yes/no, plus the storage finding if reachable.

---

## T129 — Sessions persist, deletes stick, legacy loads

**Steps:**
1. With 3+ saved sessions: send a message in one and watch for jank (there should be none).
2. Delete a session, force-close, relaunch.

**You should see:** it STAYS deleted.
3. Open an old session from before the update.

**You should see:** it loads.

- PASS: no jank, sticky delete, legacy loads. FAIL: resurrected session or legacy loss.

**Send back:** one line.

---

## T130 — Deleting a connected MCP server warns about the session and the secrets

**Steps:**
1. With a connected MCP server, tap its delete.

**You should see:** a confirm dialog naming BOTH the live session being stopped AND the secret being wiped.
2. Cancel: everything stays. Delete: server gone, session ended.

- PASS: dialog, both outcomes. FAIL: silent session kill.

**Send back:** the dialog text.

---

## T131 — Long commands get a truncated card with a Show All toggle

**Steps:**
1. In MANUAL flow, ask the chat:

```
run this exact command: echo aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa
```

2. Look at the approval card.

**You should see:** about 4 lines shown plus a "Show all (N chars)" style toggle. Tap it: the full text, scrollable.
3. The "Trust this project" button appears ONLY if the project is untrusted; tapping it both trusts AND approves.

- PASS: truncation, toggle, conditional trust. FAIL: wall of text or missing toggle.

**Send back:** the toggle label.

---

## T132 — Connector calls need consent EVERY time

**Steps:**
1. In AUTO flow (auto-approve on), ask the chat to use a connector tool twice.

**You should see:** an approval card EVERY time (not just once), each showing full service, method and endpoint.

- PASS: repeated cards. FAIL: approved once then silent forever.

**Send back:** how many cards for 2 calls.

---

## T133 — Guest paths translate; writes land inside the project

**What this is about:** the terminal AI sees Linux-style paths like `/root/project/...` which must translate to real Android paths, and a write to such a path must land inside the project, never on your storage root.

**Steps:**
1. In the chat, send:

```
read the file /root/project/p5-poly/Main.kt
```

**You should see:** the read SUCCEEDS, and the Output tab shows a `[CH02]` translation line.
2. Then send:

```
write a new file at /root/project/p5-poly/newfile.txt with the content hello
```

3. Check the Explorer.

**You should see:** `newfile.txt` INSIDE the project. No stray `/root` directory created on shared storage.

- PASS: translation and correct landing. FAIL: "Could not read file" or files landing outside.

**Send back:** the CH02 line and where newfile.txt landed.

---

## T134 — Bogus cron expressions are refused, not stored

**Steps:**
1. Ask the chat:

```
schedule a task with cron expression 'hourly'
```

2. Then:

```
list my scheduled tasks
```

**You should see:** an "Unsupported cron form ... NOT scheduled" style refusal, and the task does NOT appear in the list.

- PASS: refused and absent. FAIL: created anyway.

**Send back:** the refusal line.

---

## T135 — Entity id collisions are honest

**Steps:**
1. Ask the chat to create an entity record, then immediately ask again (same command, fast, twice):

```
create an entity record named counter with value 1
```

2. Then:

```
list my entity records
```

**You should see:** BOTH records exist (the id was bumped to be unique, nothing overwritten).
3. If you can reach internal storage: corrupt one entity file and trigger an update-all.

**You should see:** "Skipped 1 unreadable record(s)" style honesty.

- PASS: both created, skips reported. FAIL: one silently eaten.

**Send back:** the list result.

---

## T136 — A blocked staging dir produces a typed refusal, not a silent write

**Steps:**
1. In the terminal, paste and run:

```
mkdir -p .codespace/staging && chmod 555 .codespace/staging
```

2. Ask the chat in AGENT mode:

```
create newfile2.txt with the content hello
```

**You should see:** the model receives a typed REFUSAL ("write_file REFUSED, staging failed" style), the disk is unchanged, and no pending-changes card appeared.
3. Restore with `chmod 755 .codespace/staging`.

- PASS: refusal, no direct write. FAIL: file written anyway.

**Send back:** what the model echoed.

---

## T137 — Apply-undo is per-file honest and retryable

**Steps:**
1. Ask for a multi-file change:

```
rename the function in util.ts and Main.kt to betterName
```

2. Approve the apply, then tap **Undo last Apply**.

**You should see:** "Restored N file(s)".
3. Force a checkpoint failure. In the terminal:

```
chmod 555 .codespace
```

4. Tap Undo again.

**You should see:** per-file "NOT restored: ... (reason)" lines and a note that failed entries are kept and Undo can be retried. Never a blanket "No checkpoints found" while checkpoints existed.
5. `chmod 755 .codespace`, tap Undo once more.

**You should see:** it restores.

- PASS: per-file honesty and retry. FAIL: blanket loss.

**Send back:** the per-file lines (short).

---

## T138 — Image generation: header auth, model override, honest errors

**Steps:**
1. Set your Gemini key in the AI keys settings if not already set. Ask the chat:

```
generate a small image of a red square
```

**You should see:** it works, and no `?key=` fragment appears in any echoed request URL in the Output tab.
2. In-Project Settings > AI Agent > **Gemini Image Model**: change it to another valid model id, generate again.

**You should see:** the new model is used.
3. Put garbage in the field and generate once more.

**You should see:** an honest API error naming the model, not a generic crash. Restore the field after.

- PASS: all three. FAIL: key in URL, or dead override.

**Send back:** worked or failed per sub-check.

---

## T139 — Hub sign-in uses the external browser and updates itself

**What this is about:** an old bug did OAuth in an embedded web view, which Google blocks and which hid failures.

**Steps:**
1. Open the Connectors Hub and connect a real connector (Google or Slack).

**You should see:** an EXTERNAL browser app opens (not an in-app web view). Finish the sign-in there.
2. Return to the app WITHOUT reopening the sheet.

**You should see:** within about 5 seconds the connector row flips to Connected (the poll), with a success toast.
3. Disconnect, then reconnect: same flow works.

- PASS: external browser, poll flips the row. FAIL: in-app web view, or manual refresh needed.

**Send back:** external browser yes/no, and how the row updated.

---

# PHASE 8 — SETTINGS, BACKUP, SCHEDULER (T140-T156)

**Get there:** the gear menu > **Settings (App-wide)**.

---

## T140 — One settings label, one search box

**Steps:**
1. Read the gear menu entry.

**You should see:** "Settings (App-wide)" (note the exact label; a stale "In-Project Settings" label is a finding).
2. In the settings search box, type `keybinding`, then `theme`, then `format`.

**You should see:** every search returns results tagged with their section and which surface (app-wide or in-project) they belong to.

- PASS: correct label, unified search with surface tags. FAIL: stale label or fragmented search.

**Send back:** the label and a screenshot of one search.

---

## T141 — Keybinding rebind with conflict detection

**Steps:**
1. Settings > Keybindings: pick any action, tap its **Record** chip, and in the capture dialog press a key.

**You should see:** the key is captured and set; pressing Esc cancels the recorder with nothing changed. (If you have no hardware keyboard for the app, mark the capture half NOT RUN and test conflicts only.)
2. Now try to bind a key that's already used by another action.

**You should see:** a reassign DIALOG asking before stealing the binding. Cancel: the original binding is untouched.

- PASS: dialog before steal, Esc cancels. FAIL: silent steal.

**Send back:** the dialog text.

---

## T142 — Your last chosen dark theme is remembered

**Steps:**
1. In themes, pick a specific named dark theme (not just the dark toggle).
2. Turn dark mode OFF, then ON again.

**You should see:** your last PICKED dark theme comes back, not a default.

- PASS: remembered. FAIL: reset to default.

**Send back:** theme name before and after.

---

## T143 — Settings backup export and import round-trip

**Steps:**
1. Settings > Settings Backup > **Export to clipboard**. Paste the clipboard into any notes app.

**You should see:** non-empty JSON that looks like settings.
2. Change a setting, then import the JSON back (if the import wants a file, save the clipboard text as a `.json` file in Download first).

**You should see:** the exported values are restored.

- PASS: round-trip works. FAIL: empty export or no-op import.

**Send back:** worked yes/no.

---

## T144 — Clear All Data states its exact scope

**Steps:**
1. Find **Clear All Data** and READ the dialog before confirming.

**You should see:** it lists exactly what it clears (keybindings, settings.json, notifications, workspace memory, the original three prefs) and states what is NOT touched (disk files, container, backups).
2. Confirm, then open a project.

**You should see:** projects and container intact.

- PASS: scoped honestly, nothing collateral. FAIL: an overclaiming "All Data" dialog, or collateral damage.

**Send back:** the dialog text and what survived.

---

## T145 — Destructive setting changes verify their own write

**Steps:**
1. Disable the app lock (if set) and/or sign out of GitHub.
2. Force-close, relaunch.

**You should see:** the state really changed (read-back before claiming success is the point: the state holds after relaunch).

- PASS: state holds. FAIL: claimed success but the state reverted.

**Send back:** one line.

---

## T146 — Formatter settings cross-link each other

**Steps:**
1. Settings > Formatter Selection.

**You should see:** cross-links or inline mentions of the format_on_save rows and related TS settings, and the links navigate somewhere real.

- PASS: links work. FAIL: dead mention.

**Send back:** where the links went.

---

## T147 — Scheduled tasks survive a kill; corruption starts clean

**Steps:**
1. Create a scheduled task (via chat or the scheduler UI) for 2 minutes out. Force-stop the app, relaunch.

**You should see:** the task is in the list and fires at its time.
2. If you can reach internal storage (Fix-B7): replace the scheduler JSON with garbage, relaunch.

**You should see:** the app starts CLEAN with an empty task list, no crash.

- PASS: persistence plus clean recovery. FAIL: task lost or crash.

**Send back:** fired yes/no, plus the corruption half if reachable.

---

## T148 — No dead tabs anywhere

**Steps:**
1. Look at the bottom tab bar.

**You should see:** NO DOWNLOADS tab (removed honestly), and every remaining tab (BACKUP, ARTIFACTS, and the rest) opens its correct screen.

- PASS: absent and survivors route. FAIL: dead tab or error screen.

**Send back:** the tab list you see.

---

## T149 — Stores survive a mid-write kill

**Steps:**
1. Get a busy chat going (send a burst of messages that trigger agent memory writes).
2. Force-stop the app MID-burst, relaunch.

**You should see:** agent memory and scheduled tasks INTACT, not wiped to empty (atomic writes plus fallback worked).

- PASS: intact. FAIL: empty stores after the kill.

**Send back:** intact yes/no.

---

## T150 — The prefs backup refreshes on every start (RG05, including the crash-fix regression check)

**What this is about:** this is the fix for your launch crash. Every start quietly backs up your prefs; a missing source file (like the Firebase heartbeat file) must be skipped, never crash the launch.

**Steps:**
1. Toggle a setting. Relaunch the app.
2. Check the backup location. In the terminal, paste and run:

```
ls -l /storage/emulated/0/CodespaceIDE/prefs-backup/ | head -8
```

**You should see:** xml files and settings.json present with TODAY's timestamps (the backup refreshed on this start).
3. Relaunch two or three more times.

**You should see:** the app opens normally EVERY time. That's the crash-loop regression check.

- PASS: refreshed backup, zero launch crashes. FAIL: stale backup or any crash.

**Send back:** a few lines of the ls -l output and "no crashes on N relaunches".

---

## T151 — Prefs restore lands through the live API

**Steps:**
1. Note a toggle's current value. Change it.
2. Settings > Backup & Restore > run the prefs **Restore**.

**You should see:** the toggle lands at the BACKED-UP value immediately, no restart needed for the value to show.

- PASS: applied live. FAIL: restored only after restart, or silently lost (both are findings).

**Send back:** the toggle before and after.

---

## T152 — The cloud backup panel is honest when signed out

**Steps:**
1. Sign out, open the Cloud Backup panel.

**You should see:** a plain missing-sign-in statement and buttons wired to sign-in. No infinite spinner, no fake backup claim.

- PASS: honest. FAIL: spinner forever or fake claim.

**Send back:** the message.

---

## T153 — Session import: newer schema refused, legacy loads

**Steps:**
1. Export a session blob if the app offers it. Edit it in any text editor: bump its schema/version number UP. Import it.

**You should see:** an honest "newer schema" refusal, no crash.
2. Import an OLD (pre-update) session blob.

**You should see:** it loads.

- PASS: both. FAIL: crash on corrupt or legacy loss.

**Send back:** both outcomes.

---

## T154 — Snapshot export states its recovery path

**Steps:**
1. Run the snapshot export.

**You should see:** the success message honestly says there's NO in-app restore yet and tells you the manual recovery path.

- PASS: honest. FAIL: implies restore exists.

**Send back:** the message.

---

## T155 — The known-hosts checker tracks reality

**Steps:**
1. SSH Manager > Known Hosts > **Check** (with rootfs installed).

**You should see:** each profile shows trusted or not-recorded.
2. If you can reach any SSH host: connect a NEW host through a profile, re-check.

**You should see:** it becomes trusted. Tap **Forget**: reconnect asks again (TOFU). Tap **Clear all**: everything shows not-recorded. If no host is reachable today, mark the connect halves NOT RUN.

- PASS: statuses track. FAIL: always not-recorded or crash.

**Send back:** the statuses you saw.

---

## T156 — Offline fallback is readable and recoverable

**Steps:**
1. Airplane mode ON. Open the app (works offline), then the Connectors Hub.

**You should see:** a READABLE offline message ("sign in again after reconnecting" style), not a bare 401 error.
2. Reconnect, sign in again.

**You should see:** the Hub works again.

- PASS: readable degraded mode plus recovery. FAIL: raw 401 or dead UI.

**Send back:** the offline message.

---

# PHASE 9 — PACKAGE MANAGER AND EXTENSIONS TAB (T157-T168)

**Get there:** the side panel > the **EXTENSIONS** tab (Packages, MCP and agent tools sections).

---

## T157 — Refresh shows a live operation strip

**Steps:**
1. Tap the **Refresh** (circle arrow) button in the Packages section.

**You should see:** an UPDATE-LISTS operation in the op strip with live apt output, ending in a done or failed dot; the run also appears in the History list.

- PASS: streamed and recorded. FAIL: silent spinner.

**Send back:** the op title and outcome.

---

## T158 — Search results are capped and scroll smoothly

**Steps:**
1. Search a broad term:

```
lib
```

**You should see:** at most about 40 rows, scrolling smoothly.

- PASS: capped and smooth. FAIL: hundreds of choppy rows.

**Send back:** roughly how many rows appeared.

---

## T159 — Category chips actually filter

**Steps:**
1. Clear the search so the featured list shows. Tap a category chip.

**You should see:** a filtered list. Tap **All**: resets. Type anything: it searches everything, not just featured.

- PASS: all three behaviors. FAIL: chips are no-ops.

**Send back:** one line.

---

## T160 — Installed versions are real dpkg data; Update works per package

**Steps:**
1. Open the **Installed** section: read a package's version, then verify it in the terminal:

```
dpkg -l | grep cowsay
```

(or any package you installed). The versions must match.
2. Run Refresh first, then look for an upgradable package (an **Update** button on its row). Tap Update.

**You should see:** the update runs through the op strip and the row's version changes after.

- PASS: real versions and a working update. FAIL: blank versions or a dead Update button.

**Send back:** one package's before and after version.

---

## T161 — The op strip survives tab switching

**Steps:**
1. Start an install. Mid-run, switch to the TERMINAL tab, do something, switch back to Extensions.

**You should see:** the SAME op strip, still live, still streaming.

- PASS: same strip. FAIL: strip restarted or blanked.

**Send back:** one line.

---

## T162 — Two apt operations at once: the loser gets a readable message

**Steps:**
1. Start an install in the Package Manager. While it runs, trigger a second apt op (a Refresh, or an LSP bootstrap install).

**You should see:** the loser reports the lock-busy state with a readable hint ("another apt operation is running"), NOT a raw dpkg lock error and not a hang.

- PASS: classified and readable. FAIL: raw error or frozen.

**Send back:** the hint text.

---

## T163 — Remove asks first, with a simulated package count

**Steps:**
1. On the `git` package row (or any large meta-package), tap **Remove**.

**You should see:** a confirm dialog showing a PACKAGE COUNT derived from a dry-run simulation (like "this will remove 40 packages").
2. Tap **Cancel**: nothing happened. (Only confirm the removal on a package you're willing to reinstall.)

- PASS: dialog, count, cancel honored. FAIL: instant removal or no count.

**Send back:** the count text.

---

## T164 — A failed install leaves real detail in History

**Steps:**
1. Airplane mode ON. Try installing any package (it fails).
2. Open **History** and expand the failed entry.

**You should see:** the real E:/Err: line from apt, not just "failed". Airplane OFF after.

- PASS: real detail. FAIL: bare failure.

**Send back:** the E: line.

---

## T165 — The agent tools card shows a live count

**Steps:**
1. Read the tool count on the MCP/AGENT TOOLS card.

**You should see:** a plausible number (about 32). Cross-check roughly against `agent_tools` output.

- PASS: present and plausible. FAIL: zero or blank.

**Send back:** the number on the card.

---

## T166 — UNKNOWN is honest while the proot is busy

**Steps:**
1. Start a heavy install. While it runs, open the toolchain/Build Environment panel and tap **Refresh toolchain**.

**You should see:** rows show a gray **? UNKNOWN** state with a "Probe failed" style note, and real values return after the install finishes.

- PASS: UNKNOWN under load, recovery after. FAIL: fake green statuses or permanent unknowns.

**Send back:** the UNKNOWN note text.

---

## T167 — Port discovery is automatic and fast

**Steps:**
1. In the terminal, paste and run:

```
python3 -m http.server 7777 &
```

2. Open the Ports panel (bottom tab).

**You should see:** the port appears within about 5 seconds, WITHOUT tapping refresh.
3. Stop the server:

```
kill %1
```

**You should see:** the port disappears on a later tick.

- PASS: appears and disappears automatically. FAIL: manual refresh needed.

**Send back:** seconds until it appeared.

---

## T168 — The Installed list tracks reality

**Steps:**
1. In the terminal, paste and run (the package from Phase 2's T17):

```
apt-get remove -y cowsay
```

2. Back in the Package Manager WITHOUT refreshing, look at Installed.

**You should see:** it reflects the removal (or at worst on the next automatic refresh; note which).

- PASS: tracks. FAIL: ghost package.

**Send back:** which behavior you saw.

---

# PHASE 10 — DEBUGGING (T169-T179)

**Get there:** project `p5-poly`, the Debug surfaces: the Explorer debug button, the PSS Debug tab, the BREAKPOINTS list.

---

## T169 — Breakpoints (with conditions) survive a full app kill

**Steps:**
1. Set a breakpoint inside `test_add` (gutter tap on the assert line). In the BREAKPOINTS list, add a hitCondition of `1` to it if the UI offers the field.
2. Fully kill the app (swipe from Recents), relaunch, reopen the project.

**You should see:** the breakpoint is STILL in the list with its condition intact.

- PASS: persisted. FAIL: gone or condition dropped.

**Send back:** present yes/no, condition intact yes/no.

---

## T170 — A disabled breakpoint doesn't stop

**Steps:**
1. Debug `test_add` and pause at the breakpoint.
2. While paused, toggle that breakpoint OFF (its row's toggle, or the gutter dot).
3. Tap **Continue**, then re-run the debug.

**You should see:** the now-disabled breakpoint does NOT stop the next pass.

- PASS: disabled means no stop. FAIL: keeps stopping.

**Send back:** one line.

---

## T171 — Restart stays responsive, even cold

**Steps:**
1. With a live debug session: Overflow menu > **Restart**.

**You should see:** the UI stays responsive throughout (a progress indicator, no Android "App isn't responding" dialog), and the session restarts.
2. Repeat via the Explorer debug panel's restart icon, from a COLD project (no proot booted).

**You should see:** responsive through the boot wait too.

- PASS: responsive both ways. FAIL: frozen UI or an ANR dialog.

**Send back:** ANR yes/no.

---

## T172 — A failed launch leaves no phantom session

**Steps:**
1. Force a failed debug launch: debug a file with a bad interpreter (create `bad.py` starting with the line `#!/nonexistent` and try to debug it).

**You should see:** an honest launch failure, and the session picker shows NO phantom entry for it.

- PASS: honest failure, clean list. FAIL: zombie session row.

**Send back:** one line.

---

## T173 — Reverse requests get answered, not hung

**Steps:**
1. Debug a JS test (the js-debug path from T45).

**You should see:** no hang at attach; the logs show a notSupported response for the reverse request instead of silence.

- PASS: answered, no hang. FAIL: hang at launch.

**Send back:** the notSupported line if visible.

---

## T174 — Guest-path stack frames jump to the right host file and line

**Steps:**
1. Paused in the Python debug, open the stack/VARIABLES panel and tap a frame whose path looks like a guest path (a `/root/...` style path).

**You should see:** the jump opens the correct HOST file AT the exact frame line (not a broken project-root-joined path, not line 1).

- PASS: right file and line. FAIL: wrong file or line 1.

**Send back:** the file and line you landed on.

---

## T175 — Watches and REPL are one shared store across surfaces

**Steps:**
1. In the Explorer debug panel, ADD the watch:

```
add(1,2)
```

**You should see:** it appears in the VARIABLES panel's watch list immediately.
2. Add a different one in the VARIABLES panel: it appears in the Explorer panel.
3. Remove it in either: gone from both.
4. Evaluate `1+2` in the REPL.

**You should see:** the echo in BOTH the Explorer console and the PSS Debug tab.

- PASS: synced both ways. FAIL: one-sided store.

**Send back:** one line.

---

## T176 — Two debug sessions are isolated

**Steps:**
1. Start TWO Python debug sessions (debug `test_add` and `test_sub_fails` separately).
2. Stop session A.

**You should see:** session B is unaffected: pause, step and evaluate still work on B.

- PASS: isolation. FAIL: killing A killed B.

**Send back:** B alive yes/no.

---

## T177 — Stopping kills the debuggee inside the rootfs

**Steps:**
1. Stop a session mid-debug. In the terminal, paste and run:

```
ps aux | grep -E "debugpy|python3.*test" | head -3
```

**You should see:** no orphaned debuggee processes.

- PASS: clean teardown. FAIL: orphans.

**Send back:** the ps output or "clean".

---

## T178 — Breakpoint edits broadcast; Remove All is total

**Steps:**
1. With TWO live sessions: add, remove, or toggle a breakpoint in ONE.

**You should see:** both sessions rebind; the untouched session keeps ITS breakpoints.
2. With at least one breakpoint: tap **Remove All** in the BREAKPOINTS header.

**You should see:** the store, the live sessions, AND the persisted state all clear (kill the app, reopen: still empty).
3. An unverified breakpoint (one the server rejected) renders DIM with the server's message beside it.

- PASS: all four. FAIL: one-sided broadcast or resurrection after Remove All.

**Send back:** one line per sub-check.

---

## T179 — Unsupported languages get an honest "no debugger"

**What this is about:** the old bug faked a RUNNING debug session for languages with no debugger.

**Steps:**
1. Open `Main.kt`, set a breakpoint, tap Debug (Explorer debug button or the Run/Debug menu).

**You should see:** NO session starts. The console shows a "No debugger available for Kotlin, use Run instead" style message. No fake RUNNING controls, and the session switcher lists nothing.
2. Python: a real session starts. JS/TS: a Node session. A shell file: a real bash session.

**You should see:** each real path still works, and each non-debuggable type (HTML, JSON) gets the honest message.

- PASS: honest everywhere. FAIL: any fabricated session.

**Send back:** the exact refusal line.

---

# PHASE 11 — TABS, SPLITS AND THE TB02 RACE (T180-T186)

---

## T180 — Split view ids never collide

**Steps:**
1. Split `test_math.py` into 4 views (the split action 3 times). Close view #2 with its X. Split again.

**You should see:** the new view gets a FRESH number, no two strip entries share an id, and closing one never closes another.

- PASS: fresh id and isolation. FAIL: duplicate id or cascade close.

**Send back:** the strip's ids before and after.

---

## T181 — Splits are per-project

**Steps:**
1. With splits open in `p5-poly`, open project B (no splits).

**You should see:** B's strip has NO p5-poly splits. Go back to p5-poly: its splits return.

- PASS: isolation and return. FAIL: bleed.

**Send back:** one line.

---

## T182 — Renaming a file keeps its split and all its state

**Steps:**
1. With `util.ts` open AND split, rename it in the Explorer to `util2.ts`.

**You should see:** the tab title updates, the split SURVIVES with the new name, and bookmarks, undo history, folds, scroll position and breakpoint dots all carry over. No second tab for the old path appears.

- PASS: everything carries. FAIL: lost state or ghost tab.

**Send back:** which states carried.

---

## T183 — OPEN EDITORS close works and never resurrects

**Steps:**
1. Open the OPEN EDITORS panel and tap **X** on a tab entry.

**You should see:** the tab actually closes and STAYS closed, its splits cascade away, and the breadcrumb follows the newly active tab.

- PASS: close, cascade, breadcrumb. FAIL: resurrected tab.

**Send back:** one line.

---

## T184 — Opening the same file by a different spelling makes no twin

**Steps:**
1. Open `test_math.py` via the Explorer. Then open the SAME file via a differently-spelled path: use a Go-to-Definition that lands in it, or a palette Go-to-File entry with a relative or decoded spelling.

**You should see:** NO duplicate tab. The existing tab activates.

- PASS: single tab. FAIL: twin tabs for one file.

**Send back:** one line.

---

## T185 — Dirty-close gates on every close path

**Steps:**
1. Make `util2.ts` dirty. Close via the strip's X: the Save & Close / Discard & Close / Cancel dialog appears.
2. Repeat via context-menu **Close Others** and **Close All**: same dialog each time.
3. A CLEAN tab's X closes directly, no dialog.
4. Edge: make the write fail. In the terminal:

```
chmod 555 .
```

then delete the file's folder via a root-removal that closes the dirty tab (restore with `chmod 755 .` right after).

**You should see:** the failed save keeps the tab OPEN with an ERROR, never a silent loss.

- PASS: all paths gated, failures keep tabs. FAIL: ungated loss of dirty content.

**Send back:** dialog yes/no per path.

---

## T186 — THE TB02 RACE PROTOCOL (run twice)

**What this is about:** the pane and the shell used to write the same session-state file with different partial fields, so rapid alternation could erase each other's writes. The fix split the writers. This test tries hard to break it.

**Round 1:**
1. In ONE session, RAPIDLY alternate for about 30 seconds: tap 3-4 different file tabs back and forth, switch panels (Explorer, Terminal, GIT, back), and change the editor font size 2-3 times (In-Project Settings > editor font size).
2. Force-close the app. Relaunch. Reopen the project.
3. Check EVERY field: the last active file tab, pinned tabs, the open panel, the bottom tab, and the font size.

**You should see:** ALL of them restored together. If ANY reverted to a default, note WHICH field; that's the race surviving.

**Round 2:** repeat the whole protocol once more.

**Legacy check:** open a project you last saved with the PREVIOUS app version. Its session state still restores ONCE (the migration path).

- PASS: all fields intact in both rounds, legacy restores once.
- FAIL: any single field reverted (report which one).

**Send back:** per round, yes/no for each of the five fields, plus the legacy result. This is the most important test of the phase.

---

# PHASE 12 — TELEMETRY, FORCED CRASH (T187-T189) — debug build only

---

## T187 — Force a real crash; the local crash file persists

**Steps:**
1. On-device, in `com.codespace.ide.debug`, deliberately crash the app: any action that reliably force-closes it works (a plain uncaught exception anywhere proves the pipeline).
2. Right after the crash, if you can reach internal storage (Fix-B7): check that `files/crash_logs/crash_<timestamp>.txt` EXISTS with the stack trace.

**You should see:** the local crash file persisted.

- PASS: file exists. FAIL: no local file.

**Send back:** the file name and its first line.

---

## T188 — The crash record reaches the server

**Steps:**
1. Relaunch ONLINE and wait about a minute.
2. Tell me "I crashed it". I will read the CrashLog record on this Superagent and verify the app_package, stack_trace, app_version, git_hash and thread_name fields.

- PASS: a real record with all fields. FAIL: no record after 2 minutes.

**Send back:** just tell me when you've crashed and relaunched. This closes a standing note (RG01).

---

## T189 — The retry path works after a crash in airplane mode

**Steps:**
1. Airplane mode ON. Crash the app again.
2. Relaunch (still offline), reconnect, relaunch again.

**You should see:** the queued crash uploads on the next online launch (I'll verify the second record), and the local crash_logs file is consumed.

- PASS: queued then uploaded. FAIL: lost in airplane mode.

**Send back:** tell me when done; I'll verify both records.

---

# PHASE 13 — HOME, PROJECTS AND CLOUD (T190-T197)

---

## T190 — A project created offline survives the first sync

**Steps:**
1. Airplane mode ON. Create a project:

```
p5-offline
```

2. Reconnect and let the cloud sync run.

**You should see:** the project SURVIVES the sync, and the status shows a "+N local-only" style count.

- PASS: survivor with an honest count. FAIL: wiped on sync.

**Send back:** the status text.

---

## T191 — (only if willing) An empty cloud account never wipes the list

**Steps:**
1. Only run this if you're willing to empty your cloud project list; otherwise mark NOT RUN.
2. Empty the cloud list, sync.

**You should see:** the visible list is NOT wiped.

- PASS: no wipe. FAIL: list emptied.

**Send back:** if run.

---

## T192 — Register Existing Folder adopts your files

**What this is about:** an old bug refused to re-open a folder that already existed, with "Folder already exists and is not empty".

**Steps:**
1. Create a project `p5-adopt` with a file in it, then delete it from the LIST only (soft delete).
2. New Project, navigate to the parent, type the SAME name.

**You should see:** a "Register Existing Folder" style adoption button appears. Registering opens the EXISTING folder with its files intact, no scaffold, no overwrite.

- PASS: adoption works. FAIL: the old dead end.

**Send back:** the button label and files intact yes/no.

---

## T193 — The session restores into the same project

**Steps:**
1. Open any project, force-stop the app, relaunch.

**You should see:** the session restores into the SAME project (the name fallback in the resolver).

- PASS: right project. FAIL: home screen or wrong project.

**Send back:** one line.

---

## T194 — Reusing a deleted project's name doesn't destroy its trash

**Steps:**
1. Delete a project (soft delete). Create a NEW project with the SAME name.

**You should see:** creation succeeds, and the predecessor's trash survived as a `.deleted-` style sibling directory in the parent (holding the old `.ide-trash`). Check the parent folder with the file explorer or terminal `ls`.

- PASS: both. FAIL: creation blocked or trash destroyed.

**Send back:** the sibling directory's name.

---

## T195 — Offline delete warns honestly

**Steps:**
1. Airplane mode ON. Delete a project.

**You should see:** a warning about the backend being unreachable. On the next ONLINE sync the cloud copy may REAPPEAR, which is the documented expected behavior, not a bug.

- PASS: warning, documented reappearance. FAIL: silent or surprising.

**Send back:** the warning text.

---

## T196 — Cut/paste: folders copy fully, failures keep the clipboard, tabs follow

**Steps:**
1. Copy a FOLDER in the Explorer and paste it elsewhere in the project.

**You should see:** ALL children present (recursive copy works).
2. Create `cut.txt` (`echo cut > cut.txt`), cut it, and paste it where a same-name file exists.

**You should see:** an error toast AND the clipboard still holds the cut (paste it somewhere valid: it works; the failed paste didn't lose it).
3. Cut a file that has an OPEN TAB and paste (move) it elsewhere in the project.

**You should see:** the TAB follows the new path: title updates, content and dirty state intact, no dead tab on the old path.

- PASS: all three. FAIL: partial copy, lost clipboard, or dead tab.

**Send back:** one line per sub-check.

---

## T197 — Select-All walks the whole tree and dedupes

**What this is about:** "All" must include files inside COLLAPSED folders (the whole non-hidden tree), never listing a selected file twice.

**Steps:**
1. Collapse all top-level folders in the Explorer. Multi-select and tap **All**.

**You should see:** files from INSIDE those collapsed folders are listed.
2. Manually select a few files first, then tap All.

**You should see:** the manual picks appear ONCE, deduped, and the UI doesn't jank on a big tree.

- PASS: whole-tree walk, dedupe, one state write. FAIL: only-visible files or duplicates.

**Send back:** counts: how many files All found versus how many were visible.

---

# PHASE 14 — LIVE PREVIEW OVER THE NETWORK (T198-T200)

**Needs a second device on the same Wi-Fi. If you don't have one, mark all three NOT RUN.**

---

## T198 — Preview serves and hot-reloads locally

**Steps:**
1. In a project with `page.html`, run Live Preview and open the preview.

**You should see:** it serves your page, and editing the file hot-reloads it.

- PASS: serves and reloads. FAIL: no serve.

**Send back:** hot-reload worked yes/no.

---

## T199 — The preview port is invisible to other devices

**Steps:**
1. Find your phone's Wi-Fi IP (as in T4).
2. On the second device, open:

```
http://<PHONE-IP>:5500/
```

**You should see:** refused or timeout, NOTHING served.

- PASS: refused. FAIL: page content served to the other device.

**Send back:** what the second device showed.

---

## T200 — The bind is loopback-only

**Steps:**
1. With the preview running, in the terminal paste and run:

```
ss -tln
```

(or `netstat -tln` if ss is missing).

**You should see:** the preview port (5500) listed as `127.0.0.1:5500`, NOT `0.0.0.0:5500` and not `[::]:5500`.

- PASS: loopback bind. FAIL: wildcard bind.

**Send back:** the ss line for the port.

---

# FINAL SWEEP (T201-T203)

---

## T201 — Terminal regression pass

**Steps:**
1. Force-stop the app, relaunch, reopen a project: tabs restore and sessions reattach.
2. Watch for any new terminal-related errors in the Output tab or logs.

- PASS: no new errors. FAIL: anything new appeared.

**Send back:** anything odd you saw.

---

## T202 — Migration grandfathering

**Steps:**
1. Open a project that existed BEFORE the update and run a gated action in it.

**You should see:** NO trust prompt (grandfathered).
2. Create a NEW wizard project: its first gated action prompts ONCE.
3. Delete a trusted project from the list and re-register the SAME folder path: its first gated action does NOT prompt again (trust remembered by canonical path).

- PASS: all three. FAIL: prompts on old projects or lost trust.

**Send back:** one line per case.

---

## T203 — Comment-accuracy rows (no action)

These rows were comment and wording corrections with no user-visible surface, covered by normal use during the round. Mark `T203: COVERED-BY-ROUND`. Nothing to run.

---

# WHEN YOU'RE DONE

Paste your results back in the T#-format, grouped by phase, partial batches welcome. I'll analyze every line against the expected behavior, tell you PASS, FAIL or PARTIAL per test, flag anything needing a code fix or a re-test, and update the ledger accordingly.

Key evidence I especially need:
- T97: paste the `[perf]` lines verbatim (the paste is the deliverable).
- T186: the five-field table, per round (the TB02 race).
- T188 and T189: just tell me when you've crashed and relaunched; I'll pull the CrashLog records and close the standing note.
- Screenshots welcome for: T16 (both tabs), T46 (Problems panel), T93 (Kotlin colors), and anything that flickered or looked wrong.

Remember: `NOT RUN` with a reason is always acceptable. Never fake a result. Never sink more than 5 minutes into one test.
