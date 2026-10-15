# Automatic Container Backups — Design Document (PROPOSAL, NO CODE)

Owner request: automatic container backups — daily / weekly / monthly or a custom
time of day, running in the background even when the app is closed.
Status: DESIGN ONLY, awaiting advisor + owner review. Nothing here is built.

Device context that shapes every choice (verified facts): TECNO KL4, Android 14
(API 34), ~2.8 GB RAM, shared storage via FUSE (no symlink syscalls), the
container backup dir is `/storage/emulated/0/CodespaceIDE` and requires the
All-files-access grant. The app already has Hilt + WorkManager wired
(CodeSpaceApplication implements `Configuration.Provider` with HiltWorkerFactory)
— the scheduling infrastructure costs nothing to add.

---

## 1. Scheduling

**Mechanism: WorkManager periodic work** (one unique worker, name
`container-auto-backup`), constraints: `setRequiresCharging(true)` (default),
`setRequiresBatteryNotLow(true)`, `setRequiresStorageNotLow(true)`.

Options exposed in Settings (enum persisted in the unified settings store):
`Off / Daily / Weekly / Monthly / Custom` — "Custom" = a specific time of day
(e.g. 02:30) with the period fixed at daily. Defaults: **weekly, 03:00,
charging only, battery-not-low, storage-not-low.**

How the time of day works: WorkManager has no "run at 03:00" primitive. The
worker computes `nextRunAt` (see §11's next-run calculator — pure, unit-tested)
and returns `Result.retry()` with a computed `BackoffPolicy` delay until that
instant; alternatively (chosen design) the worker is a **one-time chained
worker**: after each run it schedules the next one-time worker with
`initialDelay = nextRunAt - now`. Chained one-time work is chosen because it
gives exact clock targets and survives reboots (WorkManager persists pending
work), while periodic work would drift.

**What is and is not guaranteed on this TECNO:**
- WorkManager persists the request across reboots and app force-stops (the
  request survives; execution is re-attempted when the system wakes the app).
- Doze: between ~22:00 and wake the device enters deep idle; maintenance windows
  fire periodically (roughly hourly-ish, shrinking with inactivity). A 03:00 job
  on an idle phone will typically run in the NEXT maintenance window, not at
  03:00 sharp. While CHARGING overnight — our default — Doze is much shallower
  and the job runs close to target.
- HiOS/Transsion battery manager: aggressively kills background apps and can
  prevent WorkManager from ever firing when the app is "optimized". NOT
  GUARANTEED unless the owner excludes the app: Settings flow guides him
  (in-app screen: check `PowerManager.isIgnoringBatteryOptimizations(pkg)` +
  a plain-words page naming HiOS "Power Manager / Auto-start management" with
  steps to allow background + autostart for Codespace IDE).
- App-crashed or killed: WorkManager re-runs pending work at next process start.
- **Catch-up rule (once per period):** the worker first reads `lastAutoSuccess`
  (persisted timestamp). If a backup already succeeded inside the current
  period, it logs "catch-up skipped (already backed up at X)" and reschedules.
  Missed periods do NOT pile up: one period = one backup, next target computed
  from the last SUCCESS, so a phone off for a week produces one backup, not four.

**Status surface:** last backup (timestamp, size, result), next backup (target
time + which constraints are currently blocking), from the persisted settings —
shown in Settings (§10) and logged to Output.

## 2. Long-running work

- Worker duration: WorkManager gives ~10 min soft limit per attempt; the app
  requests `setForeground(...)` on entry (Android 14 REQUIRES a
  `foregroundServiceType` — we declare **`dataSync`**, the correct type for
  backup/copy work; Android 14 enforces the type at runtime and the
  manifest+notification must match. `dataSync` gets a 6-hour/day cap only on
  Android 15 — device is Android 14, no cap here; a typical backup is 3-8 min,
  far under any limit. VERIFY at build time: `AndroidManifest` gains
  `foregroundServiceType="dataSync"` + the permission
  `FOREGROUND_SERVICE_DATA_SYNC`).
- Foreground notification: plain ("Backing up Ubuntu container… progress MB"),
  same channel as §8, gone when the worker finishes.
- Expedited work is REJECTED as the primary path: `setExpedited` is
  quota-limited and can be silently downgraded — not acceptable for a scheduled
  backup; it is used only for an explicit user-tapped "Back up now" from the
  foreground (where we can fall back to running in-process anyway).
- Partial temp files: the pipeline already writes `.tmp` then renames after
  verification. A crashed/killed worker can leave `container-auto-*.tar.gz.tmp`
  or `container-backup.tar.gz.tmp`; **next worker start (and app start) deletes
  every `*.tmp` in the backup dir older than 1 hour** — never touching finished
  files.

## 3. Consistency — back up only when idle

Checked IN ORDER, all before any write:
1. **Restore/install single-flight lock free** — the same `restoreLock`/install
   flags BackupManager already uses; the worker simply refuses if held.
2. **No live shell session** — `TerminalService.liveSessionCount() == 0`.
3. **No dpkg/apt activity** — the rootfs is plain files on the Android side, so
   the worker checks the guest lock FILES directly: `rootfs/var/lib/dpkg/lock*`,
   `rootfs/var/lib/apt/lists/lock` absent-or-stale (>5 min old mtime = stale
   leftover, ignored).
4. **No shell integration command in flight**: if any session was alive in the
   last 30 s (tracked timestamp), skip — dpkg can finish seconds after the shell
   closes.

**Retry/skip policy:** constraints unsatisfied → skip (notify per §8, retry at
the next period target — no intra-period retry loops that fight HiOS). Shrink
guard or storage guard → skip + notify. Temporary I/O error → one retry after 10
min inside the same run attempt, then fail + notify.

**"If the app process is not running, what is alive in the container?" — READ
from the architecture: NOTHING.** Every proot session is a CHILD of the app
process; when the app dies, all guest processes die with it. The rootfs is inert
files on disk. Therefore any worker running inside our process sees a quiescent
container whenever zero shell sessions are alive — no other process can be
modifying the rootfs. This makes the consistency gate above both necessary and
SUFFICIENT, and it also means a backup taken "while the app is closed" is
identical to one taken while the app is open with no tabs running.

## 4. Writer

Reuses the EXISTING verified pipeline, not a copy:
- source health gate (`ContainerSafety.healthOf`: bash+env) before anything;
- the shared `writeRootfsArchiveVerified` (E17 writer + verify-before-exists);
- scan after writing: `ContainerSafety.verifyBackupArchive(tmp)` — bash+env
  present, sane counts — BEFORE the rename;
- atomic `tmp → target` rename; on any failure the tmp is deleted and every
  existing file is untouched.

**Naming:** `container-auto-YYYYMMDD-HHMM.tar.gz` (collision suffix " (n)",
same generator family as the timestamped pre-restore backups — pure,
unit-tested). NEVER touches: `container-backup.tar.gz`, `.prev`, any
`container-backup-*` timestamped pre-restore file, or any restore source —
enforced by name pattern + the `neverOverwrite` canonical-path guard.

**Retention:** keep the newest N automatic backups (owner setting, 2-7, default
3). Pruning happens ONLY AFTER the new archive is verified and renamed
(a failed backup never deletes anything), oldest-first, and never touches any
non-automatic file. Retention counts each family separately:
- default `container-backup.tar.gz` + its `.prev`: never auto-pruned (the
  verified generation pair);
- timestamped pre-restore (`container-backup-YYYYMMDD-HHMM...`): keep newest 3
  (advisor 4b, pending approval);
- automatic (`container-auto-*`): keep newest N (setting 2-7).

**Storage guard:** before writing, free space on the backup volume must be ≥
2× the current container size + 200 MB margin (the archive is ~1× and the tmp
needs another ~1×). Under the line → skip + notify (§8), never risk filling the
owner's storage.

**Shrink guard (background variant of the existing rule):** if the new archive
is under 60% of the newest previous AUTOMATIC backup, KEEP BOTH (pruning skips
this run), flag the small one in the notification + Output, no prompts —
background work never asks questions.

## 5. Prefs and LSP

The scheduled run FIRST refreshes the prefs backup
(`BackupManager.backupPrefs`) — same order as the manual flow (prefs are tiny;
failure there does not block the container backup, it is reported in the
notification).

**lsp-opt policy (proposed):** `rootfs/opt` holds js-debug/tsserver bundles and
can be large (100-300 MB), but it only changes when a language-server install or
self-heal ran. Policy: include `opt` ONLY when its top-level mtime/size
fingerprint changed since the last successful automatic backup, or on the
forced full backup (§6), or within 24 h of any install/restore event. The
fingerprint is cheap (stat top-level dirs). Honest trade-off: an EXCLUDED opt
means the auto backup alone cannot rebuild the LSP toolchain — but LspManager
self-heals from bundles at first use anyway, and the DEFAULT backup (kept
forever) still contains opt. Stated in the Settings help text.

## 6. Skip when unchanged

**Change fingerprint (all cheap, all from the Android side):**
1. `rootfs/var/lib/dpkg/status` — size + mtime (captures every apt install);
2. sampled mtime/size hash over the top TWO levels of `/root`, `/etc`,
   `/usr/local`, `/opt`, `/home` (bounded walk, ~hundreds of stats on FUSE);
3. an in-app monotonic `containerMutationCounter` — bumped by every event we
   KNOW mutates the container: install, restore, adoption stamp, LSP self-heal
   writes, extension installs;
4. `ShellIntegrationState` command count since the last backup (any command may
   have written files).

Skip decision: all four unchanged since the last SUCCESSFUL automatic backup →
skip (logged + counted, no notification unless 3 skips in a row).

**False negatives, honestly:** an edit deep below the sampled levels (e.g. a
new file in `rootfs/var/www` outside dpkg) with no counter bump and no shell
command would be missed. Mitigations: (a) force a FULL backup every Nth run
(setting, default 4th run = weekly); (b) any restore/install ALWAYS forces the
next run to be full; (c) the manual "Back up now" is never skipped. False
positives (backing up when nothing changed) are preferred over false negatives —
the shell-command trigger (#4) guarantees high false-positive bias on purpose.

## 7. Restore interaction (the multi-backup world)

Today auto-restore, adoption and the restore row assume one default backup.
With several families on disk:
- **Newest VALID wins:** the auto-restore prompter lists candidates = default,
  newest automatic, newest timestamped; picks the NEWEST by timestamp that
  PASSES Check (`BackupTar.inspect` + gate verdict); shows its NAME, SIZE and
  DATE in the notice; NEVER silently picks an older one — if the newest is
  INVALID, the notice says so and names what it fell back to.
- The restore row / picker grows a backup LIST (each family, size, date, Check
  verdict) with per-file Restore and Delete actions.
- **Invariants kept:** the version marker stays the single source of
  "installed-ness"; the single-flight restore lock gates every restore
  including automatic-source ones; restoring an automatic file uses the same
  `explicit=true` confirm as any other source.
- Retention counts per family (§4); restore never prunes.

## 8. Notifications

System channel `codespace_backups` ("Container backups", low importance),
created lazily; `POST_NOTIFICATIONS` requested once on first enable (Android
13+) with a plain pre-permission explainer; if denied, in-app NotificationStore
still shows everything and Settings shows a "notifications off" hint.
- SUCCESS: "Ubuntu backed up — 412 MB, kept 3, next: Sun 03:00".
- FAILURE: reason (one line), "Tap to open Settings" PendingIntent.
- SKIPPED: why (shells busy / low storage / unchanged / constraints unmet).
Every notification also lands in Output + the crumb log (§A).

## 9. Privacy

The archive contains: the Ubuntu rootfs — installed tools and packages, files
under `/root` (terminal history, dotfiles), LSP bundles. It does NOT contain
projects (they live in shared storage, outside the rootfs), app prefs (separate
prefs backup), chats (app-side data), or guest secrets (`GUEST_SECRET_PATHS`
excluded at write time, verified by the writer). Who can read it: the file sits
in PUBLIC shared storage — any app with storage access, and any device user, can
read it. That is the price of owner-accessibility and survives until encryption
ships. **Proposed later phase (design sketch, not committed): optional
passphrase encryption — AES-256-GCM streaming cipher over the tar stream
BEFORE gzip (so Check inspects the plaintext at restore time after decryption),
key derived per-backup via scrypt with a random salt stored in the header,
passphrase prompted at backup enablement and at each restore; container name
`.tar.gz.enc`; wrong passphrase = clean refusal before any rootfs touch.**

## 10. Settings UI (plain words)

Settings > Container Backup > **Automatic backups**:
- "Back up automatically" switch; Frequency (Off/Daily/Weekly/Monthly/Custom);
  time picker (visible for Custom); "Only while charging" switch;
  "Keep last N backups" (2-7 stepper); "Skip when nothing changed" switch.
- Status block, plain: "Last backup: Sun 03:00 — 412 MB, success. Next: Sun
  03:00 (waiting for charge)." Failures name the reason.
- "Back up now" (same worker, expedited, runs with feedback in-app).
- "Backups" list: every family — default, previous, automatic, timestamped —
  each with size, date, Check verdict, Restore, Delete.
- Battery-optimization helper row when HiOS/Doze is likely to block: "Allow
  background backups — open battery settings and remove Codespace IDE from
  optimization" with a direct intent (`ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS`
  where permitted, else the app-info page).

## 11. Tests

Pure JVM (no device): next-run calculator (daily/weekly/monthly/custom — month
ends, leap Feb, year boundary, "now already past target" → next period);
retention pruning (per family, after-verify-only, never cross-family);
auto naming + neverOverwrite interactions with the other families;
skip logic (fingerprint equal/different, forced-full counter); storage-guard
decision boundary; consistency gate order.

Device checks: enable daily + charging, charge overnight → backup exists with
notification; open a shell during the scheduled window → skip notice; fill
storage below the guard → skip notice; two periods missed (phone off) → ONE
catch-up backup; HiOS optimization ON → verify the guidance row appears
(and document whether HiOS still blocks it — the honest unknown).

Unproven until the device round: every guarantee HiOS chooses to break
(§1), foreground dataSync behavior on this OEM, FUSE write throughput for a
400 MB nightly archive (battery/heat), notification permission flow.

## 12. Delivery

- **Phases.** P1: worker + scheduling + catch-up + writer reuse + auto naming +
  retention + storage/shrink guards + notifications (core value). P2: restore
  interaction in a multi-backup world (list, newest-valid-wins prompter). P3:
  skip-if-unchanged + lsp-opt fingerprinting + forced-full cadence. P4: optional
  passphrase encryption (separate decision).
- **Permissions.** `POST_NOTIFICATIONS` (runtime), `FOREGROUND_SERVICE_DATA_SYNC`
  + manifest type, existing storage grant; no new exotic permissions.
- **Effort.** P1 ~1 session (the pipeline, worker, and notification plumbing
  all reuse shipped code); P2 ~1 session (touches the restore UI family);
  P3 ~1 session (fingerprint + tests); P4 its own review, ~1 session.
- **Risks.** (1) HiOS kills/suppresses background work — mitigated by the
  charging constraint + guidance, but honest risk it NEVER runs unattended;
  (2) nightly FUSE write of ~400 MB on a 2.8 GB device — long I/O, heat, and
  low-memory pressure while the app is backgrounded (LMKD may kill the worker
  mid-run: the tmp-cleanup + once-per-period rules make that safe, it just
  retries next period); (3) notification-channel fatigue; (4) multi-backup
  restore UI complexity (P2) — the most error-prone phase, kept out of P1 so
  the core ships independently; (5) battery cost is real — charging-only by
  default is the guardrail.
