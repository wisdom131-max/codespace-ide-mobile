# F-TRACK PLAN: Full Testing Surface (TG01–TG07)

**Status:** Approved track (owner ruling 2026-09-25). F1 approved to start at its slot.
**Classification:** FEATURE build — NOT part of FIX-PLAN.md defect tiers. Nothing here closes a
data-loss/security gap; TG01's "UI lie" is the only defect-class item and it goes honest in F2.
**Sequencing (owner-confirmed):** P3b → P3c → P3d → **P4 ∥ F1 → F2 → F3 → F4 → F5** → P5 (both tracks)
→ F6 decision. F-track owns no FIX-PLAN gap IDs; each sub-phase is its own commit, revertable
alone, CI green, P5 checks logged in AGENTS.md, ROADMAP continuity preserved.

---

## VS CODE SOURCE CHECK 1 — Core testing data model (F3/F4 grounding)

Read live from microsoft/vscode main:
`src/vs/workbench/contrib/testing/common/testTypes.ts`, `testResult.ts`, `testResultService.ts`,
`testingStates.ts`, `getComputedState.ts`, `testId.ts`, `testProfileService.ts`,
`browser/testingExplorerView.ts`, `testExplorerActions.ts`.

### Their model (verified from source)

**TestResultState — 7 states, not 3** (`testTypes.ts:14`):
`Unset=0, Queued=1, Running=2, Passed=3, Failed=4, Skipped=5, Errored=6`.
Errored is DISTINCT from Failed (infra/setup error vs assertion failure).

**ITestItem** (`testTypes.ts:374`): `extId, label, tags[], busy, uri, range, description, error,
sortText`. Display + identity in one flat object; children are NOT nested objects.

**TestId** (`testId.ts`): a hierarchical dot-path string built from the parent chain
(`parentId()`, `localId()`, `pathFromParts`). The tree comes from the ID: items are stored FLAT
and the explorer projects them into a tree via prefix-walk (`browser/explorerProjections/
treeProjection.ts` vs `listProjection.ts` — same store, two projections).

**State rollup** (`testingStates.ts`): priority `Running(6) > Errored(5) > Failed(4) > Queued(3) >
Passed(2) > Skipped(1) > Unset(0)`. A suite's `computedState` = max-priority over descendants
(`getComputedState.ts` refresh walks the prefix tree). This is the entire "suite shows failed when
one child fails" mechanism — one comparator, no bespoke logic.

**A run = ITestResult** (`testResult.ts`): `id, name, completedAt, counts (per-state totals),
request, per-test states via getStateById, retained raw output`. `TestResultItem` adds
`ownComputedState, computedState, ownDuration, retired` — `retired` marks stale states after
sources change. Result history is retained across runs (testResultStorage).

**Run-vs-debug capability — the user-facing correction:** it is NOT TestTag.
`ITestRunProfile` (`testTypes.ts:77`) carries `group: TestRunProfileBitset`
(`Run=1<<1, Debug=1<<2, Coverage=1<<3`, `testTypes.ts:47`) — the profile group IS the capability.
`capabilitiesForTest()` folds profile groups into a per-test bitset, which drives the
`hasRunnableTests / hasDebuggableTests` context keys — the SAME data that gates whether a Run or
Debug button renders at all. TestTag does something different: a profile with a `tag` only applies
to items carrying that tag (`canUseProfileWithTest`, `testProfileService.ts`) — a subsetting
mechanism (e.g. "fast tests"), not the run/debug switch.

**Actions** (`testExplorerActions.ts`): one `RunVisibleAction` base class parameterized by bitset;
`runTests({tests: include, exclude, group: bitset})`. Per-tree-row inline Run/Debug buttons live in
`MenuId.TestItem` 'inline' group; visibility is capability-gated. Variants: run-all, debug-all,
run/debug at cursor, current file, failed-from-last-run.

### Our F3/F4 plan vs theirs — matches, divergences, adoptions

| Aspect | Their shape | F-TRACK v1 (before check) | Ruling |
|---|---|---|---|
| States | 7-state enum incl. Queued/Running/Errored/Unset | 3 states (passed/failed/skipped) | **ADOPT 7-state enum** — Running/Queued are needed the moment F4 shows live progress anyway; Errored is free in parsers (pytest E vs F, jest errors vs failures) |
| Test identity | Hierarchical TestId path string; flat store | (undefined) | **ADOPT** — `file:suite:test` path id; lens, results, tree all share the id space; tree = projection, not nested objects |
| Suite rollup | max-priority comparator over prefix tree | (undefined — ad hoc) | **ADOPT verbatim** — one comparator gives suite/file/project rollup for free |
| Run record | ITestResult with counts, completedAt, retained history, retired flag | single TestResult list | **ADOPT simplified**: one TestRun {id, name, completedAt, counts, states-by-id}; keep LAST result only + `retired` staleness marker. DIVERGE (defer): multi-task runs (VS Code runs same tests across several profiles concurrently) — single task on a phone |
| Raw output | retained per-task output stream with ranges | AppOutputLog "test" channel | **DIVERGE (keep ours)** — AppOutputLog already exists; no range-addressable output needed v1 |
| Capability model | Profile group bitsets → capability context keys → action visibility | hardcoded "Run + Debug" everywhere | **ADOPT**: TestRunManager exposes `capabilities(test): Run/Debug bitset`; lens and tree rows render only supported actions. JVM shows Run but not Debug until F6. **Defer TestTag** (no subsetting use case yet) |
| Problems bridge | failure peek in editor (testingOutputPeek, decorations) | publish to Problems panel | **KEEP ours** (CW1 DiagnosticPublisher path) — we already have the Problems panel and jump-to-file; their peek is a richer desktop affordance, defer |

## VS CODE SOURCE CHECK 2 — Core testing view (F4 grounding)

- **TestingExplorerView = ViewPane + ViewModel holding ONE flat store + a projection**
  (`testingExplorerView.ts:92,689`): tree projection walks TestId prefixes; a "list" projection
  flattens to rows. Status nodes can group the tree BY state. Filter acts on the projection.
- **Per-row actions**: inline Run/Debug via `MenuId.TestItem` inline group, capability-gated
  (`testExplorerActions.ts:150-190`); header run-all/debug-all; context variants.
- **Live state**: same store — result service updates flow into the projection; computedState
  refresh re-renders rows; `retired` marks outdated rows after edits.

**F4 ruling:** build the phone version of EXACTLY this shape:
`TestStore` (flat map keyed by TestId) + `TestTreeProjection` (Kotlin composable projection) +
`TestingPane` (bottom-panel tab, mandatory UI rule: 8–12dp corners, 12dp/10dp padding, panel
composable in its own file — 64KB rule). Tree projection only (list projection = desktop QoL,
defer). Group-by-state status nodes: v2. Per-row inline Run (and Debug where capability bitset
says so) + pane-header run-all/run-failed. No nested-object tree model — the flat store + prefix
projection is what makes live rollup cheap on device.

---

## Sub-phases (each revertable alone)

### F1 — Detector v2 (TG05) + command templates (TG06) — SMALL — APPROVED
Rewrite `editor/TestLensDetector.kt` annotation/framework-driven:
- JVM: lens only on `@Test, @ParameterizedTest, @TestFactory, @RepeatedTest`, `@Nested` classes
  (children inherit). DELETE the "class name contains Test/Spec → any `fun …test…`" heuristic
  (the `fun testing()` false-positive). Backtick test names recognized.
- JS/TS: `test`/`it`/`test.each`/`it.each`/`describe`.
- Python: unchanged (already prefix-driven).
- Strip `2>/dev/null` from all templates (TG06); delete the gradle `|| echo` no-op fallback.
- Emit TestId path strings (not bare line numbers) so F3/F4 can consume the same ids.
~250 lines, pure functions, 1 CI cycle expected.

### F2 — Honest Run (TG01 + TG02) — MEDIUM
New `testing/TestRunManager.kt`:
- Per-test command builders (TG02): pytest `path::test_name`, jest `-t "name"`,
  gradle `test --tests "Class.method"`, dart equivalent.
- Execution via `ProotInstaller.execTyped` (TP03 seam): streaming, cancel, typed result.
- **Trust gate:** `TrustState.awaitTrusted(context, projectPath)` first (IG02/P2c pattern);
  headless callers fail closed.
- Capability bitset API `capabilities(test): Run/Debug` (from Check 1) — lens renders honestly.
- EditorPane lens handler routes through it; the log line becomes progress + typed result.
~1 day, 1–2 CI cycles. Revert restores today's log-line behavior exactly.

### F3 — Results (TG03) — MEDIUM-LARGE — now modeled on VS Code's shape
- Parsers beside `GradleErrorParser`: pytest (prefer `--junitxml` to temp — robust), jest `--json`,
  gradle (`build/test-results/*.xml`). Parsers emit the **7-state enum** incl. Errored.
- `testing/TestModels.kt`: TestId (path string), TestResultState, TestResultItem
  {ownState, computedState, ownDuration, retired}, TestRun {id, name, completedAt, counts},
  statePriority comparator (copied semantics from testingStates.ts) + computedState refresh.
- Failures bridged to Problems via `DiagnosticPublisher.publishBuildDiagnostics` (CW1 path).
- Lens/gutter state per test line from the result store.

### F4 — Discovery & Explorer (TG04) — LARGE — now VS Code's view shape
- `testing/TestStore.kt` (flat map by TestId) + `testing/TestDiscoveryService.kt`
  (JVM src/test, Python `test_*.py`/tests/, JS `*.test.*`/`__tests__`; F1 detector as shared core).
- `TestingPane.kt` bottom-panel tab: tree projection over the store, per-row inline Run/Debug
  (capability-gated), header run-all/run-failed, live states + rollup from F3, `retired` dimming.
- Trust gate + TestRunManager reused as the engine.

### F5 — Debug Test: Python + JS (TG07 part 1) — MEDIUM
VS Code finding (verified, kept from prior check): core owns NO runner/debugger — profiles route
to controllers; Python ext = debugpy DAP launch `module:"pytest"`; js-debug = jest under its
runtime. Reusable pattern = our existing DAP stack, adapter launch-arg extensions only:
- PythonDAPAdapter: launch variant `module` + `args` (change at `launchArgs`, PythonDAPAdapter.kt:283).
- NodeDAPAdapter: jest variant — spawn `node --inspect-brk node_modules/.bin/jest -t "name"`, attach.
- Debug lens/tree action routes TestRunManager → UniversalDebugManager; existing Debug Console,
  breakpoints, pause/step work unchanged.

### F6 — Debug Test: JVM (TG07 part 2) — LARGE — separate approval, after P5
No JVM DAP adapter in app. Gradle `test --debug-jvm --tests …` waits on JDWP (5005); needs a
JDWP attach adapter — genuinely new debugger-stack work. Decide after P5 validates F5 on device.

---

## Revert accounting
- F1 = detector file + EditorPane wiring lines → revert restores old detector.
- F2 = new TestRunManager + handler swap → revert restores log-line behavior.
- F3 = new model/parser files + publisher wiring (additive).
- F4 = new store/service/pane files + one bottom-panel tab hook.
- F5 = adapter launch-arg extensions (backwards-compatible; existing debug flows untouched).
No FIX-PLAN gap IDs are consumed; TG01–TG07 close as FEATURES in AGENTS.md, not defect tiers.

## ROADMAP (continuity)
P3b (PLAN A canonical store + CH02) → P3c (polling→flows) → P3d (delete-duplicates) →
P4 (remaining tiers) ∥ F1 → F2 → F3 → F4 → F5 → P5 (full device round, both tracks: TP02 batch,
P2a zip-slip, P2b LAN, P2c trust, P3a checks, F-track checks) → F6 decision.
Backlog owner decisions unchanged: F01 Notebooks, F02 remote-dev, F09 tree-sitter, stdio-vs-TCP.
