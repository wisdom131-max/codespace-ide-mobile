# CONSOLIDATED DEVICE ROUND — target artifact: latest GREEN after G-E-a (see header)

> Status: READY — DO NOT TEST until the owner says so (advisor 2026-10-20).
> Artifact: 35b9c8d (CI-GREEN 39527504041, newest green at doc time; the
> baseline build-stamp check confirms the installed APK). 997774b carried
> BATCH6-BATCH12 + C12 Stage 3 + U4; since then 23fdf2c adds the
> StackOverflowError hardening, 24b3b30..0271b7b add G-E-a, and 401d65d +
> 9e12950 add the PDF stuck-parse gate (PdfExtractionGate: one dedicated
> lane thread, prompt caller-side wait, Busy refusal while a zombie parse
> burns) — G-E-a INERT (no call sites, switch OFF), the gate only changes
> PDF extraction behavior (PDF rows below exercise it). Supersedes:
> 8580adc, 12f0e40, 416f417, b85b2cc, 922fe49, 0271b7b.
> Phone: TECNO KL4, Android 14. Fixtures: `/sdcard/Download/zz_stage3/`
> (generator block in `docs/c12-stage3-u4-test-sheet.md`).

## ROUND SHAPE (advisor 2026-10-20)

A 5-minute **BASELINE** first, then two sittings of about 30 minutes each,
then an optional parked section. **If the baseline fails, STOP the whole
round** — nothing after it is meaningful on a broken build.

**NO UNINSTALL in this round.** Install is always FRESH-INSTALL-OVER
(the existing app keeps its data). Before ANY destructive step, first
check the backup copy in Documents is present and readable.

## STOP rules (read before starting)

1. **Any crash** (signal 31 / ANR / visual freeze >5 s): STOP the whole
   round, take an Android bug report, send the ZIP. Do not continue into
   later phases on a crashing build.
2. **3 consecutive failures in one phase**: STOP, note what they were,
   report. Mobile data beats re-trying blind.
3. **A red light the sheet didn't predict**: note the exact wording, continue
   only if harmless (wording), stop if behavioral.
4. Battery saver / thermal throttle on: finish later. A throttled round
   produces fake ANRs and false timing failures.

## BASELINE (~5 min) — must pass before anything else

Fresh-install-OVER the existing app (do NOT uninstall). Then:

- **B1 Build stamp**: Settings-About / `[start]` line shows the expected
  build stamp for the round artifact. Wrong stamp = wrong APK = STOP.
- **B2 Terminal prompt**: open a terminal; prompt appears, CWD is sane
  (project root or last-saved dir — no `/root` surprise on a fresh tab).
- **B3 One chat message**: send one plain chat prompt; a real answer comes
  back (no 402/bubble — that would be an account-side false signal, note it).
- **B4 Pane switching with a Java file open**: open a `.java` file, then
  switch Explorer / Terminal / Problems / Git panes a few times — no crash,
  no stall, editor content intact on return.
- **B5 One debug run**: run the known-good Python file-debug once
  (auto-install path already proven) — breakpoint hits, Result line prints.

**Any B1..B5 FAIL = STOP the whole round.** Report which row and the wording.

## SITTING A (~30 min, newest code first)

### Phase A1 — C12 U4 PDF attachments (NEW CODE, ~10 min)
Sheet: `docs/c12-stage3-u4-test-sheet.md` checks U4-1..U4-10 (fixtures
generated first: text, blank, multipage-10, many-pages-200, encrypted,
corrupted, truncated). High-risk rows: U4-8/U4-9 (encrypted/corrupted — the
LinkageError + StackOverflowError hardening; a crash here is a
STOP-rule-1 event).

### Phase A2 — C12 Stage 3 documents + archives (~5 min)
Attach a real .docx/.xlsx (text extracts, capability line shows), a legacy
.doc (honest "not supported"), a zip (entry list, tap one entry to preview,
nested archive NOT auto-opened).

### Phase A3 — BATCH12 link work (~10 min)
D1 tap beginning/middle/end for plain-space / prompt / quoted-path rows;
D2 the 3-line `:line:col` file; D3 the 180-char unicode wrap row; Output
copy+save redaction (`sk-` → `[REDACTED]`, paths untouched); rename an OPEN
tab (diagnostics survive, edits continue under the new name).

### Phase A4 — BATCH10/11 link-tap + lifecycle (~10 min)
ONE `[TAPDIAG]` id per physical tap (pairs = DEFECT); Bug-1 repro rows (cd
row with spaces, prompt row, quoted path); ambiguity + directory messages;
`[start]` build stamp; Clear re-print + protected `[LSP-LIFECYCLE]` tail;
Settings-About stamp.

## SITTING B (~30 min, older batches + sweep)

### Phase B1 — BATCH9 LSP lifecycle (~10 min)
Pane-switch: no server restart, no main-thread stall, diagnostics persist;
10-min background idle stop fires (idle_stop notification), foreground never
idle-stopped; crash-cap path only if a real crash occurs (GIVE_UP + manual
retry revives).

### Phase B2 — BATCH6/7 regression (~8 min)
Test L all four tap modes with `[TAP]`/`[CRUMB]` lines; wide-character tap;
P2 Java band + variables (`[BAND-DIAG]`/`[VARS-DIAG]`); P3 jdtls
repair/manifest lines; ANR-recovered JAVA frames; Explorer New File
no-22s-freeze; VARIABLES no-tap/collapse/new-session.

### Phase B3 — 5-min regression sweep (~5 min)
Open project, terminal taps, chat send, one .py file debug, editor
squiggles.

## SITTING C (OPTIONAL, parked link items — only if time and data allow)
- P1e language-server status strip spot-check (rides, not gating).
- pwd/D14 fresh-tab behavior observation only (V0-f-c follow-up evidence).
- Python-vs-Java 2d variables differential run (feeds the advisor ruling —
  valuable, ~10 min, owner's call on data).

## Time estimate
Baseline ~5 + Sitting A ~35 (incl. ~10 min fixtures) + Sitting B ~23;
~63 min of checks total across two sittings, stop-anywhere after A3 and the
newest-code verdicts are still in hand.

## What to send back
One line per check with PASS/FAIL + exact refusal/diagnostic wording on
FAILs; bug report ZIPs for any crash; the round's build stamp line.
