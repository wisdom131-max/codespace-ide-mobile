# CONSOLIDATED DEVICE ROUND — target artifact 997774b (CI-GREEN 39410307929)

> Status: READY — DO NOT TEST until the owner says so (advisor 2026-10-20).
> Artifact: 997774b — carries BATCH6-BATCH12, C12 Stage 3, U4 PDF attachments
> AND the U4 advisor hardening (LinkageError/crypto classification, 603
> tests green).
> Supersedes: 8580adc, 12f0e40, 416f417, b85b2cc, 922fe49.
> Phone: TECNO KL4, Android 14. Fixtures: `/sdcard/Download/zz_stage3/`
> (generator block in `docs/c12-stage3-u4-test-sheet.md`).

## STOP rules (read before starting)

1. **Any crash** (signal 31 / ANR / visual freeze >5 s): STOP the whole round,
   take an Android bug report, send the ZIP. Do not continue into later phases
   on a crashing build.
2. **3 consecutive failures in one phase**: STOP, note what they were, report.
   Mobile data beats re-trying blind.
3. **A red light the sheet didn't predict**: note the exact wording, continue
   only if harmless (wording), stop if behavioral.
4. Battery saver / thermal throttle on: finish later. A throttled round
   produces fake ANRs and false timing failures.

## Phases (ordered by risk: newest code first, regression sweep last)

### Phase 1 — C12 U4 PDF attachments (NEW CODE, ~10 min)
Sheet: `docs/c12-stage3-u4-test-sheet.md` checks U4-1..U4-10 (fixtures
generated first: text, blank, multipage-10, many-pages-200, encrypted,
corrupted, truncated). High-risk rows: U4-8/U4-9 (encrypted/corrupted — the
new LinkageError hardening; a crash here is a STOP-rule-1 event).

### Phase 2 — C12 Stage 3 documents + archives (~5 min)
Attach a real .docx/.xlsx (text extracts, capability line shows), a legacy
.doc (honest "not supported"), a zip (entry list, tap one entry to preview,
nested archive NOT auto-opened).

### Phase 3 — BATCH12 link work (~10 min)
D1 tap beginning/middle/end for plain-space / prompt / quoted-path rows;
D2 the 3-line `:line:col` file; D3 the 180-char unicode wrap row; Output
copy+save redaction (`sk-` → `[REDACTED]`, paths untouched); rename an OPEN
tab (diagnostics survive, edits continue under the new name).

### Phase 4 — BATCH10/11 link-tap + lifecycle (~10 min)
ONE `[TAPDIAG]` id per physical tap (pairs = DEFECT); Bug-1 repro rows (cd
row with spaces, prompt row, quoted path); ambiguity + directory messages;
`[start]` build stamp; Clear re-print + protected `[LSP-LIFECYCLE]` tail;
Settings-About stamp.

### Phase 5 — BATCH9 LSP lifecycle (~10 min)
Pane-switch: no server restart, no main-thread stall, diagnostics persist;
10-min background idle stop fires (idle_stop notification), foreground never
idle-stopped; crash-cap path only if a real crash occurs (GIVE_UP + manual
retry revives).

### Phase 6 — BATCH6/7 regression (~8 min)
Test L all four tap modes with `[TAP]`/`[CRUMB]` lines; wide-character tap;
P2 Java band + variables (`[BAND-DIAG]`/`[VARS-DIAG]`); P3 jdtls
repair/manifest lines; ANR-recovered JAVA frames; Explorer New File
no-22s-freeze; VARIABLES no-tap/collapse/new-session.

### Phase 7 — 5-min regression sweep (~5 min)
Open project, terminal taps, chat send, one .py file debug, editor
squiggles.

## Optional (parked link items — only if time and data allow)
- P1e language-server status strip spot-check (rides, not gating).
- pwd/D14 fresh-tab behavior observation only (V0-f-c follow-up evidence).
- Python-vs-Java 2d variables differential run (feeds the advisor ruling —
  valuable, ~10 min, owner's call on data).

## Time estimate
~58 min total (sum of phases) + ~10 min fixtures + bug-report overhead on any
stop. Order is also a data-budget order: stopping after Phase 3 still yields
the newest-code verdicts.

## What to send back
One line per check with PASS/FAIL + exact refusal/diagnostic wording on
FAILs; bug report ZIPs for any crash; the round's build stamp line.
