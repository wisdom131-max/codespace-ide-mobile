# V1 PLAN: Per-Project Workspace-Binding Registry (Option A, owner-approved 2026-10-04)

**Status:** PLAN — awaiting owner approval. No code until the V0-f-c confirming round lands.
**First consumer:** node_modules (the jest/FUSE symlink class).
**Approval context:** Option A (rootfs-side private dirs bind-mounted into the /sdcard project) approved 2026-10-04 with gates: (1) the device re-test must first name the EXACT failing syscall (widened capture shipped in cd974d4) — if it is outside node_modules, this plan is revised before build; (2) F-track-depth plan BEFORE code — this document is that plan; (3) build as a GENERAL reusable mechanism, not a node_modules hack.

---

## 0. Hard dependency statement (advisor ruling 2026-10-07)

**V1's first cut DOES touch ProotInstaller.** The entire mechanism lives in
`ProotInstaller.launchArgs()` (ProotInstaller.kt:1610) — the single choke point that
assembles the proot binary, the `--bind=` list, and the guest env for EVERY session:
the interactive terminal (:1811), MCP shell spawns (McpClientManager.kt:570), and the
IDE environment builders (IdeEnvironment.kt:54/97). Therefore, per the advisor ruling:

> The E17 full-uninstall round-trip must COMPLETE before any V1 phase that touches
> ProotInstaller or the restore flow.

Sequencing that satisfies it:
1. Consolidated round (8 checkpoints, unchanged) — confirms V0-f and unblocks V1 code.
2. NEXT round (WiFi): **E17 round-trip runs FIRST**, then V1-a's inert sanity checks.
3. V1-b (first live bind) gets a **dedicated device round** — see the high-risk flag below.

V1-a CODE may be written and shipped inert (zero bindings exist, zero behavior change)
so it can ride round 2; nothing ACTIVATES until V1-b.

---

## 1. Current state (READ-verified in code)

- `ProotInstaller.launchArgs(context)` returns `Triple(proot, args, envVars)`; the args
  list hardcodes all binds (`--bind=/dev /proc /sys ... /sdcard /host-files` plus the
  restored `/proc/self/cwd` Samsung fix). It takes NO project identity — every caller
  today gets the IDENTICAL bind set.
- Callers: interactive terminal (:1811), McpClientManager (:570), IdeEnvironment (:54,
  :97). One-shot tool exec (`runCommand`-family) also derives from launchArgs (:1811
  block) with fd-bind filtering.
- Project paths reach proot in two forms: host-side (`/storage/emulated/0/...`,
  `/host-files/projects/<id>`) and guest-side (`/sdcard/...` — `--bind=/sdcard` maps
  host shared storage). The path-form mismatch class (Problems-panel jump bug) is the
  standing lesson: the registry must store BOTH forms explicitly, never derive one
  from the other by string surgery.
- Private dirs must live INSIDE the rootfs tree (app-internal
  `filesDir/ubuntu-rootfs/...`) — that is the whole point: the rootfs side is ext4
  (real fs, real syscalls), while `/sdcard` is FUSE (blocked symlinkat/linkat class).

---

## 2. The mechanism

### 2a. Binding registry (V1-a)

New `terminal/WorkspaceBindingStore.kt` — pure Kotlin core + thin prefs persistence
(same pattern as CustomEndpointStore):

```
ProjectBindings {
  projectKey          // stable hash of the canonical HOST project path (the identity)
  hostProjectPath     // canonical host path (management/display)
  guestProjectPath    // in-proot path form (e.g. /sdcard/My codespace app 3/proj)
  bindings: [ { guestPath, privatePath, kind: NODE_MODULES|CUSTOM } ]
}
```

- One registry entry per project; `kind` makes node_modules the first CONSUMER of a
  general mechanism, not a special case.
- Guest and host path forms stored separately (form-mismatch lesson above).
- CRUD: register/remove/lookup-by-projectKey; persisted as one JSON blob in prefs;
  version counter for reactive UI (phase C+ surfaces it in Settings).
- Lifecycle keyed by the stable project-path hash (owner requirement): private dir
  path = `<rootfs>/opt/bindings/<hash>/<name>` — survives rootfs backup/restore
  (BackupManager archives the whole rootfs, so bindings survive reinstall too).

### 2b. Injection (V1-a)

`launchArgs(context, projectId/hostPath? = null)` overload:

- `null` (default) → byte-identical args to today; EVERY existing caller keeps working
  unchanged. This is what makes V1-a inert and trivially revertable.
- Non-null → consult the registry for that project's entry; for each binding whose
  private dir EXISTS on the host, append `--bind=<hostPrivatePath>:<guestPath>`.
  Missing private dir → SKIP that bind + honest log line (a missing bind source can
  hard-fail proot launch — a session must never die because a binding rotted).
- No registry code reads the "active root" — the session binds ITS OWN project's
  bindings only (the V0-f CWD-stamping lesson generalized).

### 2c. Caller threading (V1-b)

Every PROJECT-SCOPED launchArgs/spawn site passes its project identity:
TerminalService interactive creation + `forSubprocess` family (D14 already threaded
workDir/projectId through most of this family — V1-b completes it), McpClientManager
spawn, IdeEnvironment builders, and the DAP adapter debuggee launches
(JvmDAPAdapter/NodeDAPAdapter spawn paths). Enumerated and verified at build time.

### 2d. node_modules consumer (V1-b)

- **Ships OFF by default behind an opt-in switch** (advisor 3a): a global
  "workspace bindings" toggle in Settings, default OFF. A bind injects ONLY when the
  switch is ON AND the project has a registered binding AND the crash-loop guard has
  not tripped. The switch is the kill-switch; the guard is the seatbelt.
- Register-on-need: an explicit action (AI tooling npm install for a project, or a
  one-tap "Move node_modules into rootfs storage" in Explorer/Settings) creates the
  private dir, MOVES the project's node_modules into it (rename within... cross-device:
  FUSE→ext4 copy, then delete source), and writes the registry entry.
- Guest sees `<project>/node_modules` as a normal ext4 directory → npm bin symlinks
  live on ext4, jest never touches FUSE for them.
- Removal: delete registry entry → next session unbinds; the private dir stays until
  explicit cleanup (owner can reclaim via Settings later).

---

## 2e. Crash-loop guard (advisor 3a) — one restart recovers, never a reinstall

**Requirement:** if the previous start with bindings did not complete, bindings
auto-disable; the next start always boots clean.

**How an incomplete start is detected:** a two-phase mark file in APP-INTERNAL
filesDir (NOT the rootfs, NOT /sdcard — it must be readable even when proot is
hanging):

1. Immediately BEFORE a session launches with bindings active, the app writes
   `bindings-start.pending` (contains: timestamp, project key, binding list).
2. The bound session's FIRST successful output callback (first onData after exec —
   proot survived startup and bash is emitting) CLEARS the mark.
3. On the NEXT app start, a still-present mark means the previous bound start never
   completed → set `bindingsDisabledByGuard=true` (persisted), DELETE the mark,
   notify the user ("Workspace bindings auto-disabled after an incomplete start —
   tap to re-enable"), and launch everything with zero binds.

With the guard tripped, launches are byte-identical to today — so ONE restart is
always a full recovery. Re-enabling requires an explicit user tap (never auto).

**False-positive cost, stated honestly:** an app crash UNRELATED to bindings during a
bound start also trips the guard once — cost is one re-enable tap. Guard scope is
STARTUP completion: a mid-session freeze (e.g. during jest) happens after the mark was
cleared and does not trip it — that class stays with the revert-style isolation
playbook (S1-c precedent).

---

## 2f. Expected device rounds between now and V1-b, with combining proposal (advisor 3b)

| # | Round | Carries |
|---|---|---|
| 1 | **Consolidated round** (unchanged, 8 checkpoints) | V0-f-c confirmation + the 7 other pending checkpoints |
| 2 | **Combined WiFi round** | E17 round-trip FIRST (full uninstall/reinstall + restore), then E17-b choice-dialog check (dialog shows backup MB + ~58 MB, both buttons, no-choice timeout aborts), then V1-a inert checks (launch args byte-identical, all four consumers). Proposal: if MK phases C–E and F are SHIPPED by then, their device checks ride this same round instead of getting their own. |
| 3 | **V1-b dedicated round** (kept per advisor 3c) | node_modules live bind + jest run; first gate: the re-test names the exact failing syscall from the widened capture. Sole focus, nothing else bundled. |

That is 3 rounds between now and V1-b. The only NEW device time V1 asks for is the
V1-a inert checks in round 2 (~minutes) plus the dedicated V1-b round.

---

## 3. Phases, risk, testing

| Phase | Content | Device round |
|---|---|---|
| V1-a | Registry store + launchArgs overload + JVM tests (bind-arg assembly, missing-dir skip, path-form integrity, hash stability) | RIDES round 2 (after E17 round-trip, same round, INERT checks only) |
| V1-b | Caller threading + node_modules register/move + live bind + opt-in switch (default OFF) + crash-loop guard | DEDICATED round (see flag) |
| V1-c | Lifecycle: project-delete cleanup, repair (rotted binding auto-clear + notify), Settings surface | rides the next natural round |

**High-risk flag (owner decision required):** V1-b is the first LIVE proot bind of a
large ext4 directory over a FUSE path on the TECNO 5.15 kernel — freeze/crash class
territory (same kernel family that blocks symlinkat/linkat). Per the device-round
batching rule this is flagged for a DEDICATED round rather than riding a batch.
Recommendation: dedicated round for V1-b alone, with the jest syscall capture as its
first gate.

**Standing gate (unchanged from Option A approval):** the device re-test must NAME the
exact failing syscall from the widened capture (cd974d4). If it is NOT inside
node_modules, this plan is revised before V1-b build.

**Test matrix:** JVM unit tests for the store (register/lookup/remove, hash stability,
path-form round-trip); bind-arg assembly (present dir → appended, missing dir →
skipped + logged, null project → byte-identical args); proot no-regression (all four
launchArgs consumers unchanged with no bindings registered).

**Rollback:** V1-a = revert launchArgs + delete store file (no data written — nothing
registered). V1-b = revert caller threading; registry entries are plain data (delete
them; the moved node_modules keeps working as a normal guest dir at its new path until
moved back). No storage migrations anywhere.

---

## 4. Explicitly out of scope

Option C (full project relocation) stays parked as its own future decision (alongside
F6 JDT-LS Layer 1 + Kotlin file-debug). Non-node_modules consumers (custom binds)
come only after V1-b proves the mechanism on device.
