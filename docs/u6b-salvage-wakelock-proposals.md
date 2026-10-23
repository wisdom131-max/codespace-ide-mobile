# C12-U6-b proposals (advisor items 5 + 6, 2026-10-23)

Status: **PROPOSALS ONLY — no code shipped.** Item 5 is blocked on the advisor
ruling after fix 4's report; item 6 was explicitly requested as proposal-only.

---

## Item 5 — SALVAGE: per-page tolerance for damaged PDFs

### What fix 4 established (the evidence this design rests on)

The REAL PDFBox 2.0.27 parser on our EXACT truncated fixture (first 433 of 722
bytes of the delivered gen.py text.pdf):

- Does NOT throw. The parser is lenient by default (`COSParser.isLenient = true`,
  line 147); our cut removed startxref/xref/trailer, so `retrieveTrailer()`
  falls into `rebuildTrailer()` (line 2225) — a brute-force `bfSearchForObjects()`
  scan that rebuilds the xref from the surviving objects.
- Loads as a 1-page document whose extracted text is BLANK (content stream +
  font were cut) — locked in CI by `PdfTruncatedFixtureUpstreamTest`.
- Contrast: the delivered corrupted fixture (Root points at nonexistent
  object 9) DOES throw `IOException: Page tree root must be a dictionary`
  (locked in the same test).

### The gap (what salvage fixes)

Today the damage unit is the WHOLE FILE: if a real-world PDF is damaged in a
way that makes one page throw (corrupt content stream, missing font object,
broken xref remnant), the engine-level catch converts the entire extraction
to `EngineOutcome.Failed` → the user gets "PDF extraction failed — showing file
info only" even when 9 of 10 pages parse fine. The upstream evidence above
shows the opposite failure mode exists too (silent blank text). Both are
per-page problems, and the fix must handle them per page.

### Design (minimal, rides the existing pipeline)

1. **Engine per-page try/catch** (`PdfBoxTextEngine.extract`): wrap the
   per-page `PDFTextStripper.getText(doc)` call. A page that throws is
   recorded as an UNREADABLE marker (an enum slot `PageResult.Unreadable(why)`
   instead of the current `List<String>`), NOT a silent "" (silent blank is
   what upstream lenient mode produces; we say it out loud). Document-level
   load failure (the truncated-but-rebuilt and corrupted cases fix 4 locked)
   stays on the existing `Corrupted`/`Failed` outcome paths unchanged.
2. **Honest marker in the emitted doc**: the joined text carries one
   parenthetical line per unreadable page group, e.g.
   `(partial: 2 of 10 pages unreadable — pages 3, 7)`. Never presented as
   complete: the marker is inside the extracted text the model receives AND
   in `ExtractedDoc.notes` (the sheet + injector notes line), so both the
   user and the model see it.
3. **Existing guarantees all still apply by construction** (no new code
   needed for these): redacted (attachment tier, same as every extractor),
   untrusted-labeled (the injector's `### Document text extracted from` +
   UNTRUSTED line), capped (MAX_CHARS_PER_PAGE 10,000 / MAX_PAGES 50 /
   snapshot cap), gated (the PdfExtractionGate lane, Busy/Timeout refusals).
4. **Mammoth-style warnings pattern**: `ExtractedDoc.notes` is already the
   warning channel (mammoth collects `messages` the same way) — the marker
   rides it, plus the existing note family ("showing pages 1-X of Y",
   "secrets redacted", truncation notes). One new note kind only: unreadable
   pages. No second warning mechanism.

### Test list (JVM, all fake-seam, no device dependency)

1. `salvage_onePageThrowsOthersParse` — engine returns 3 pages with page 2
   unreadable → doc.text contains pages 1+3 text, contains
   `(partial: 1 of 3 pages unreadable`, notes include the marker.
2. `salvage_allPagesUnreadable_refusesHonestly` — every page unreadable →
   same honest refusal family as today ("no page could be read"), never an
   empty-string success.
3. `salvage_markerIsUntrustedHeaderedAndRedacted` — marker doc through the
   injector → block keeps the UNTRUSTED header; a secret on a surviving page
   is still redacted (notes say "secrets redacted").
4. `salvage_cappedPageStillCountsAsRead` — a page at the 10,000-char cap is
   NOT miscounted as unreadable.
5. `salvage_markerNeverSaysComplete` — sheet prose assertion: when any page
   is unreadable the notes can never render without the partial marker.
6. Existing green tests stay untouched: engine-level Corrupted/Failed/
   Encrypted mappings (truncated-rebuild and corrupted-throw cases from
   fix 4) unchanged — only the per-page loop's granularity changes.

Awaiting advisor ruling before any code.

---

## Item 6 — WAKELOCK / foreground-service delayed start (proposal, no code)

### Current state (READ, cited)

- `CodeSpaceApplication.kt:137-146` — the comment block + "Do NOT acquire
  WakeLocks or start foreground service here" guard. The old crash loop:
  acquisition of 2 WakeLocks + FGS within 1 s of process start; TECNO HiOS
  SIGKILL'd it; restart re-acquired immediately → 16x crash loop in 90 s.
- Today: `TerminalService` (Termux-style FGS) starts from TerminalPane's
  DisposableEffect — never from Application. The app-level
  `PARTIAL_WAKE_LOCK` is a manual gear-menu toggle (`acquireAppWakeLock` /
  `releaseAppWakeLock`, CodeSpaceApplication.kt:175-206), default OFF
  (not held at startup). The service holds its own optional
  PARTIAL_WAKE_LOCK + WifiLock pair via notification ACTION buttons
  (Termux pattern).
- Manifest: `<service android:name=".terminal.TerminalService"
  android:exported="false" />` with NO `foregroundServiceType`;
  targetSdk=28 (Termux parity, same as the TECNO working reference).

### Android 14 rules this design must satisfy (checked)

1. **FGS type requirement**: apps targeting API 34+ MUST declare
   `android:foregroundServiceType` and hold the matching permission (e.g.
   `FOREGROUND_SERVICE_SPECIAL_USE`). At targetSdk 28 the requirement does
   not apply (compat mode on Android 14, exactly how Termux runs on the
   TECNO KL4 today). Constraint on the design: keep targetSdk 28; if the
   target ever bumps, terminal keep-alive has no clean system type —
   `specialUse` is the only honest fit and needs a Play-declared reason.
   `dataSync` is time-capped in 14+ and would be a lie.
2. **Background-FGS-start rule**: starting a foreground service while the
   app is NOT visible throws `ForegroundServiceStartNotAllowedException`
   (API 31+ targets; on Android 14 with targetSdk 28 the system is
   permissive, but the visible-only rule is the design guard that keeps
   OEM power managers from classifying us as a stealth background starter).
   Therefore: the only start path is while an Activity is RESUMED, never
   from `Application.onCreate`, never from a broadcast receiver, never from
   a crash-restart path (a restart has no resume until the user opens the
   app again — which is precisely what breaks the old loop).

### The proposed design

**Setting** (default OFF), in Settings → Terminal:
- Title: "Keep terminal alive in background"
- Subtitle: "Keeps the CPU and Wi-Fi awake while a terminal is running, even
  with the screen off. Uses more battery."
- The existing manual gear-menu wakelock toggle is REPLACED by this setting
  (one keep-alive concept, one switch — two toggles doing one job is a
  trap; noted as a small behavior change for the owner's go-decision).

**Start sequence** (all conditions ANDed):
1. Setting is ON.
2. A terminal session exists (Termux parity — no session, no service, no
   lock; an idle IDE acquires nothing).
3. The app was RESUMED at least once AND at least **5 s** elapsed since the
   first resume of this process (`onResume` stamp + a single one-shot
   postDelayed; timer does not restart on later resumes, so nothing
   re-triggers it).
4. Start is performed while the app is visible (rule 2 above). If the app
   is backgrounded before the timer fires, the pending start is CANCELLED —
   it must never fire from the background.

**Stop**: when the last terminal session closes, or the setting is switched
off → release both locks + stopService, mirroring Termux's pairing.

**Notification text** (existing channel, rebuilt on lock state like today):
- Title: "Codespace terminal is running" (unchanged)
- Body, locks held: "Keep-alive ON — CPU and Wi-Fi stay awake while a
  terminal is running."
- Body, locks not held: "Tap to open. A running terminal keeps this
  notification."
- ACTION buttons unchanged (Acquire/Release WakeLock becomes redundant if
  the setting ships and would be removed — same one-concept rule).

**Why it cannot recreate the old crash loop** (vs CodeSpaceApplication.kt:137-146):
- Old: acquire-at-process-start (restart → instant re-acquire → SIGKILL →
  restart → loop). New: process start acquires NOTHING; the 5 s-after-first-
  RESUME gate means a crash-restarted process with no user in the app never
  reaches the start path — the loop's trigger condition is structurally
  absent, not merely delayed.
- The 16x loop evidence also says the delay must be ≥ the OEM's kill-window
  judgment time; 5 s is the floor the advisor named and matches the
  observed loop period (~5.6 s per cycle) — one full cycle of grace.

### Device test (plain words)

1. Install the build, do NOT touch the setting. Open a terminal, run a
   long task (e.g. a sleep loop), press Home. Expected: normal background
   behavior; after some time Android may pause it — that is today's
   behavior, no new locks.
2. Switch "Keep terminal alive in background" ON. Open a terminal, keep the
   app in the FOREGROUND 5+ seconds. Expected: the terminal notification
   appears and says keep-alive ON (locks held). Turn the screen off 10
   minutes with a `while true; do date; sleep 5; done` loop printing.
   Turn the screen back on: the loop kept printing the whole time (no
   signal 31, no killed session).
3. Kill the app from the recents screen, immediately reopen it, and within
   the first 5 seconds background it again (spam backgrounding). Expected:
   NO notification appears, no locks are held — the pending start was
   cancelled. This is the anti-crash-loop check.
4. Toggle the setting OFF while a terminal runs: notification updates,
   locks release (Termux-style), terminal continues normally.

Awaiting owner go-decision (setting replaces the gear toggle; targetSdk stays
28; 5 s delay) before any code.
