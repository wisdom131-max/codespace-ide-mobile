# Project Three — Advanced Agent Capabilities: completion report

Date: 16 November 2026. Scope: Stages 3.3 to 3.7, executed in order without stopping between them.
Evidence rule for this report: a stage is CI-GREEN only against the run id quoted beside it, and nothing
here is DEVICE-VERIFIED. Nothing was pushed to `main` as a separate decision: each stage went out over the
already-approved stage pipeline, and nothing was built or installed as an APK.

## Status board

| Stage | What it is | Commit | CI run | Verdict | Label |
|---|---|---|---|---|---|
| 3.1 | Objective-level task plan with evidence and dependency skipping | `f7e2371` | 43756359759 | success | CI-GREEN |
| 3.3 | Specialised subagent roles that narrow, never widen | `15575ea` | 43758102836 | success | CI-GREEN |
| 3.4 | Bounded delegation | `175a30a` | 43759583764 | success | CI-GREEN |
| 3.5 | Self-healing and safe recovery | `9e899ea` | 43759688281 | success | CI-GREEN |
| 3.6 | Execution reflection | `2c5004c` | 43761631809 | success | CI-GREEN |
| 3.7 | Integration of the five capabilities | `36a412a` | 43761640713 | success | CI-GREEN |

Nothing in Project Three is DEVICE-VERIFIED or COMPLETE by the project's own labels. Completing those
requires the parked device round, which has not run.

Correction to an earlier statement: run 43758102836 was still in progress when Stage 3.3 was first
called green, and I then said it did not cover `15575ea`. It does: the run finished with success against
`15575ea`. Stage 3.3 is CI-GREEN, and the earlier correction was wrong.

## Stage 3.4 — Bounded delegation

Reused: `SubagentRoles` (3.3) for scopes, `ToolMeta`/`ToolRegistry` for tool metadata, the plan's own
dependency readiness for ordering.

Architecture: `agent/subagents/DelegationScheduler.kt`. A `Board` holds tasks with
QUEUED/RUNNING/COMPLETED/FAILED/CANCELLED/BLOCKED, a nested `Budget` (depth, agents, tasks, concurrency),
the project `epoch`, and the tool set the board may use. The scheduler decides what may run; it executes
nothing.

Rules and the failure each prevents:
- Parallel is a privilege: only a task with no writes, run by a read-only role, shares a lane capped at 3.
  A task with a write waits until nothing is running and then runs alone, in declaration order.
- Two writers on one path: the later task is BLOCKED, naming the path and the holding task. It is a
  decision for the user, not a race.
- A read does not start beside a queued write to the same path, so a read cannot report a version of a
  file that is about to change.
- A duplicate request (same role, title, reads, writes) is answered from the first task; it never becomes
  a second task, so a duplicate cannot apply a side effect twice.
- Cancellation marks everything open CANCELLED and refuses new work.
- `childBoard` is strictly smaller on every axis: depth + 1 (max 2), agents - 1, the parent's remaining
  task budget, and tools intersected with the parent task's own scope. Asking for a tool the parent never
  held is refused, so delegation cannot raise privilege.
- Completion needs evidence; blank evidence is recorded as a FAILURE. A read result whose epoch predates a
  project change is COMPLETED but unverified and marked stale.

Tests: 23 in `DelegationSchedulerTest`, including one against the real registry and real metadata
(`ToolRegistry.spec` + `ToolMetaIndex.metaFor`) asserting a read-only role receives no destructive tool.

Limitations: the scheduler is a policy layer. Its callers still have to honour `wave()`; the tool
dispatch path does not yet consult it. Time budgets are expressed as task/agent counts plus the epoch, not
wall-clock deadlines, because a phone that sleeps must not invalidate a run on a timer.

## Stage 3.5 — Self-healing and safe recovery

Reused: `ToolFailurePolicy` for the reading of an error message, so recovery and retry agree about what a
failure is.

Architecture: `agent/heal/RecoveryPlanner.kt`. Nine categories (invalid arguments, missing resource,
permission, tool execution, provider/network, timeout, dependency, state conflict, resource exhaustion,
plus unknown). A closed set of eight repairs. `plan()` returns a `Plan` that keeps the original error
verbatim and states, in `withheld`, why nothing was applied when nothing was.

Automatic only if all four hold: no side effect and known outcome, repair budget unspent (max 1, and
attempts < 2), Ask mode not in force, and the repair itself is read-only by construction (re-read, resolve
a stale path inside the project, narrow a range, one retry). Everything else is a proposal: a permission
refusal, a dependency gap, an unrecognised failure, any write, any unknown outcome.

`verdict()` refuses to call a repair recovered without post-repair evidence, and treats "still fails" text
as failure. `Ledger` keeps tool, category, repair, original error and outcome, bounded at 40.

Tests: 22 in `RecoveryPlannerTest`, two of them exhaustive over all categories: no category offers an
automatic repair for a write, and none offers one in Ask mode.

Limitations: applying an approved proposal is the caller's job; the planner never executes. Category
detection is text-based, inherited from `ToolFailurePolicy`, so a provider with unusual wording falls to
UNKNOWN and is reported rather than retried.

## Stage 3.6 — Execution reflection

Reused: `TaskPlanEngine` (the plan is the source of planned work; no competing planner was created),
`DelegationScheduler` board, `RecoveryPlanner.Ledger` lines.

Architecture: `agent/reflect/ReflectionEngine.kt`. Compares the objective as stated, the plan, the actions
observed, graded evidence (IMPLEMENTED < TESTED < CI_GREEN < DEVICE_VERIFIED), acceptance criteria,
delegated results and recovery outcomes.

Rejections it makes, each tested: a completion with no evidence; evidence that is "done", "ok" or "no
exception thrown"; a subagent vouching for its own work; a step done before a dependency; a step done on
top of a failed call; a read result that went stale; disagreeing subagents on one step; an unverified
delegated result; a blocked delegation (raised as something the user must decide); a recovery that did not
hold; a criterion met at a weaker level than it asked for. Failed, skipped and unreachable work stays in
the report.

Tests: 19 in `ReflectionEngineTest`.

Limitations: evidence has to be supplied by whoever did the work, or recorded at the tool layer; the
engine cannot read a diff or a CI run by itself. Reflection is a judgement over a record, so a record that
was never written produces "no evidence", not a pass.

## Stage 3.7 — Integration

Architecture: `agent/reflect/ReflectionState.kt` plus one hook in `AgentTools.executeToolBody` and
`task_plan action=reflect`.

- Every tool result is recorded once, at the single point where tools return, into a session-scoped
  bounded record (200 actions, 40 criteria). Reflection therefore judges what the session observed, not
  what the model says happened.
- Runtime failures are recorded as failed actions; "Refused:", "Blocked:" and "Denied:" results likewise.
- The plan, the delegation board and the recovery ledger all feed the same reflection.
- `task_plan action=reflect` takes optional `criteria` and `observations` and returns a verdict, leaving
  the plan untouched. The single-step `evidence` string argument is unchanged; the array is
  `observations`, because a duplicate JSON key in one schema is a bug waiting for a strict parser.

Tests: 9 in `ProjectThreeIntegrationTest`, walking plan -> role selection -> bounded delegation ->
permitted recovery -> reflection, plus the lying variants.

## Regression suite

Full local run: 3073 tests, 3 skipped, 1 failure — `BackupTarTest.failureMessageCarriesRealTotals`. That
failure is pre-existing and environmental, not caused by this work: it fails identically with all Project
Three changes stashed, and the same test passes in CI on the same commit. It is recorded here rather than
hidden.

The combined regression list from the brief maps to tests as follows: duplicate replies and duplicate tool
calls (existing suites plus the delegation dedupe cases), duplicate side effects (dedupe + recovery
`MAX_AUTO_REPAIRS`), transcript races (existing), session recovery and process death (existing), partial
tool calls (existing guard suite), permission bypass (role and child-board cases against the real
registry), credential leakage and path traversal (existing security suites), provider fallback (existing),
concurrent file modifications (write conflict cases), delegation cancellation, nested delegation limits,
recovery loops, false success claims and evidence validation (new).

## Remaining work in Project Three

1. Nothing wires `DelegationScheduler.wave()` into the live tool dispatch yet: the policy exists and is
   tested, but the chat loop does not yet ask it what may run. Until then delegation cannot actually
   happen in a session, only be planned.
2. `RecoveryPlanner` is not yet called from dispatch either; the existing `ToolFailurePolicy` retry path is
   what runs today. Connecting them is a behaviour change and needs its own stage and CI run.
3. The device round (parked by instruction) has to confirm what CI cannot: that a real conversation with
   the plan, delegation and reflection surfaces behaves sanely on the TECNO device.

Nothing above is marked COMPLETE, and none of it is claimed to be device-verified.

## Next, per the established order

Project Four: Advanced Tool Intelligence. Then Cross Provider Intelligence, Long Context Management,
Copilot Experience Improvements, and Integration Reliability & Security. The Brahma audit at
`docs/BRAHMA-CAPABILITY-AUDIT-2026-11-15.md` remains the comparison evidence, and the gaps identified
there that neither application handles well (no dependency graph, no evidence grading, no approval gate on
healing) are now addressed in VN Code.
