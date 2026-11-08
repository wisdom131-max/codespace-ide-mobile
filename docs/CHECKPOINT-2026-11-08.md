# Checkpoint — 2026-11-08 (crash-safety set)

**Install target (already built and green; nothing new was triggered):**

| Field | Value |
|---|---|
| Workflow | `android-build.yml` |
| Run | **3595** (`success`) |
| Commit | **`b08af75`** |
| versionCode | **851** (`git rev-list --count HEAD`) |
| versionName | 1.0.0 |
| Artifact | `codespace-ide-arm64-v8a` — **78,465,250 bytes** (74.8 MB) |
| Also built | `codespace-ide-x86_64` 76,802,753 B, `codespace-ide-armeabi-v7a` 76,746,793 B |
| Artifact expiry | 2027-02-06 |
| Suite | 1678 tests / 203 suites, largest method 65.9% of the bytecode limit |

Confirm the stamp on the device: Settings → About, or the first line of Output. It prints
commit + CI run + build time; a crash log now also carries `Build: 1.0.0 (851) on <model> / Android <release>`.

---

## Device package — crash-safety set (about 30 minutes)

**STOP RULES.** Stop and send me the Output log the moment: the app closes by itself, a screen
freezes longer than 10 seconds, you see `Constraints` or `IllegalArgumentException` in Output, or a
checksum comparison does not print `FILE UNCHANGED`. Do not delete app data to "fix" a crash until
you have sent the log: the restore guard now exists precisely so you never have to.

Work in four sittings; each ends at a clean point you can abandon.

### Sitting 1 (8 min) — big file, no preview

```
pwd                       # must be the project root (the folder containing .git)
python3 - <<'PY'
open('bench/big5000.txt','w').write(''.join('line %d of the big file\n' % i for i in range(1,5001)))
PY
wc -l bench/big5000.txt
```

Open `bench/big5000.txt` from the Explorer. Expected: it opens **editable**, the banner names the
line range, typing works, scrolling is smooth. Type a character, Save, confirm `wc -l` is unchanged.

### Sitting 2 (12 min) — 13,000-line preview and every writer

```
python3 - <<'PY'
with open('bench/big13k.js','w') as f:
    for i in range(13000):
        f.write('const v%05d = %d; // filler line\n' % (i, i))
PY
wc -l bench/big13k.js
sha256sum bench/big13k.js | tee /tmp/before.txt
```

1. Open `bench/big13k.js` from the Explorer. Expected: opens as a **read-only preview**, banner
   says lines 1-5280 of 13000, no editing possible.
2. Page to page 2. Expected banner: lines 5281-10560 of 13000.
3. Try every writer: **Save**, **Save all**, **Format Document**, project **replace-all** for
   `filler` → `FILLER`, **AI apply** of any small edit, and from the terminal
   `sed -i '1s/^/X/' bench/big13k.js`.
4. Then:

```
sha256sum bench/big13k.js | diff /tmp/before.txt - && echo "FILE UNCHANGED (pass)"
```

Expected Output lines: `[guard] write REFUSED for .../big13k.js: preview slice` and
`[perf] guard ... density=... fontScale=...`. The terminal `sed` DOES change the file (that is the
external edit); the editor must then show `File changed on disk` / reload the page and still refuse
to write.

### Sitting 3 (6 min) — line offsets, breakpoints, zoom

- Search for `const v09000` and tap the hit. Expected: the page holding line 9,000 opens (9000 is
  page line 3720 of 5280-10560), not page 1.
- Tap a gutter breakpoint in the preview. Expected: nothing is set, or the note says bookmarks/
  breakpoints are off in a read-only preview.
- Pinch-zoom on a big file until it stops. Expected: `[perf] zoom clamped to Nsp` in Output and the
  text stays readable, never a crash.

### Sitting 4 (8 min) — project root and crash recovery

- Open a project root that contains `.git` and `node_modules`. Expected: the Explorer paints fast,
  `[watch]` lines appear in Output, no freeze. Note the seconds it took.
- Force a crash with a file open: Settings → About → the crash-report action, or `killall -11`.
- Relaunch. Expected: the project and other tabs come back, ONE thin banner says
  "VN Code closed unexpectedly while `<file>` was open; it was not reopened", with a **Reopen**
  button that opens it (as a preview if it is huge).
- Relaunch a second time after a clean exit. Expected: no banner, the file restores normally.

### Failure definitions

A sitting FAILS if: the app closes at launch (send Output + the newest `crash_logs/crash_*.txt`,
whose header now names the build); `FILE UNCHANGED` does not print after the editor-side writers;
the preview edits at all; a search hit at line 9,000 lands anywhere but the page holding it; or the
restore banner appears on a clean exit.

---

## What this checkpoint already closes in code

Restore crash-loop guard (`a54045b`), defect 2 / every text-changing path gated (`253d5a9`),
constraints audit of chat blocks and diff (`79ad2ba`), stale preview (`512b5bf`), navigation into a
preview (`b559928`), preview isolation complete: 13 gated consumers, 4 inert with proof (`b08af75`),
autosave and recovery refusal (`cd64358`, `167516c`), preview writers (`b46d8c8`).
