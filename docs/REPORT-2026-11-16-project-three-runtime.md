# Project Three runtime integration — report

16 November 2026. Commit `3d5ffec`, CI run 43782467822, green. Working tree clean, branch `main`.

## What was wired, and where

| Layer | Entry point | Behaviour when live |
|---|---|---|
| Objective plan | `AgentTools.executeTool` -> `RuntimeBridge.beforeDispatch` | A plan that cannot progress refuses further calls and names the stalled step ids. `task_plan` is exempt, because it is the only exit from a dead plan. |
| Reflection recording | same call, after the body | Every real output becomes an observed action; a failure is recorded as failed, with the real text. |
| Self-healing | same call, on a failed output | `RecoveryPlanner` classifies, at most one permitted repair runs through `executeToolBody`, and the result is validated before it may be called recovered. Ask applies nothing; destructive tools are never re-run. |
| Delegation | `task_plan action=delegate` | Builds a `DelegationScheduler` board whose `allowedTools` is what the current chat mode exposes, narrowed by the role. Tools outside the role are refused at enqueue; `task_plan` cannot be delegated. |
| Mode | `RuntimeSession` | Live Ask/Edit/Agent, claimed by the chat screen; an unclaimed session is Ask. |

## Defects the tests found and closed

1. The scheduler enqueued a task naming a tool the role did not hold. Role scope was advisory at the
   enqueue boundary; it is now enforced there.
2. Reflection never listed a started-but-unfinished step as remaining work.
3. A dead plan blocked every tool including the one that revises it, which would have deadlocked the
   model. `task_plan` is now exempt, with a test named for that exit.

## Tests

`RuntimeBridgeTest`: 20 cases. Suites run locally: 3,093 tests, one failure,
`BackupTarTest.failureMessageCarriesRealTotals`, which reproduces with this work stashed (measured
again on a clean tree) and is separately tracked. It does not reproduce in CI.

## Verdict

- **Self-healing: YES.** It runs on the live dispatch path for every tool call, not only when asked.
- **Delegation: YES, model-initiated.** The model delegates through `task_plan action=delegate`; the
  planner does not yet pick roles on its own, so a plan can still be executed without delegation.
- **Reflection: recording YES, verdict on request.** Outcomes are recorded automatically; the verdict
  is produced by `task_plan action=reflect`.

## Limitations

- Delegated tasks execute sequentially in board order. The board marks read-only work as parallel-safe
  and the scheduling rule is unit-tested, but the executor loop itself issues one call at a time, so
  measured parallelism today is one.
- Cancelling the conversation cancels delegated work by coroutine cancellation; a delegated task that
  was mid-write is reported by the ledger as uncertain, not silently retried.
- Nothing is device-verified. No APK was built.
