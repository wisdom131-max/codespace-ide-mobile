# Copilot runtime architecture

How a Copilot turn reaches a tool, and which layer may say no.

## Project Three runtime integration (2026-11-16)

Before this stage every Project Three layer was a tested policy that nothing called. This stage
connects them, and only through one door: `AgentTools.executeTool` -> `executeGated` -> `executeToolBody`.

- **Before dispatch** `RuntimeBridge.beforeDispatch(plan, toolName)` asks the live plan whether this
  call can run. A plan that cannot progress refuses further work with the stalled step ids, so the
  agent reports a blocker instead of flailing. `task_plan` itself is never refused, because it is
  the only exit from a dead plan; a gate that blocked it would deadlock the model.
- **After dispatch** `RuntimeBridge.afterDispatch` records the outcome as an observed action in
  `ReflectionState`: a failure is a failed action, and the evidence string is the real output.
- **On failure** `RuntimeBridge.recover` asks `RecoveryPlanner` what may be applied, applies at most
  one repair, runs the tool body once more, and validates the repair before it may claim recovery.
  Ask never applies anything, destructive calls are never re-run, and the original error text is
  never rewritten.
- **Delegation** `task_plan action=delegate` builds a `DelegationScheduler` board whose
  `allowedTools` is what the current chat mode exposes, narrowed again by the role. A task naming a
  tool its role does not hold is refused at enqueue, and `task_plan` cannot be delegated, so a
  child agent cannot start its own plan and recurse.
- **Mode** `RuntimeSession` holds the live Ask/Edit/Agent mode. An unclaimed session is Ask, so a
  dispatch before any claim gets the most restrictive answer.
