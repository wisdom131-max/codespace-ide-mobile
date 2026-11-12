# Project 4 - Advanced Tool Intelligence

Stage 2 of the eight-project programme. Registry unchanged at 118 tools: this adds reasoning around
the tools, not more of them.

## Components and responsibilities

| Area | Component | Responsibility |
| --- | --- | --- |
| 4.1 / 4.2 | `ToolPlanner` | Goal from the request, steps with dependencies, deterministic waves, reuse of completed work |
| 4.3 | `ToolResultCheck` | Six verdicts, so "returned" and "worked" are different facts |
| 4.4 | `ToolFailurePolicy` | Nine failure kinds, four actions, attempt ceiling, no blind retry of unknown side effects |
| 4.5 | `McpToolPicker` | Relevance ranking, ownership, staleness, approval invariant |
| 4.6 / 4.7 | `ToolContextTrim` | Evidence retention, repeat removal, bounded output with an honest marker |

## Safety boundaries

- Planning is text. It executes nothing and widens no approval.
- A write is always in a later wave than the read it depends on, and a write with no preceding read
  is reported as a problem rather than planned.
- `ToolDispatchGuard` remains the only authority on whether a body may run again. `ToolFailurePolicy`
  refuses to retry a side-effecting call whose outcome is unknown precisely so that the decision
  stays with the guard and its reconciler.
- `McpToolPicker.Pick.requiresApproval` is always true and is not configurable, so MCP stays behind
  explicit owner approval even under AUTO_ALL.
- A tool that returned output containing "command not found", "permission denied", "usage:" and
  similar is marked SUSPICIOUS_SUCCESS and told to read back, never reported as done.

## Bounds

Waves are derived from steps, so concurrency is bounded by the plan rather than the device.
`MAX_ATTEMPTS` is 2. Context trimming defaults to 6,000 characters total and 1,500 per result, with
a `[N chars omitted]` marker. MCP ranking is capped at 8 picks. Nothing here loads a whole file: the
planner names tools, and the existing bounded tools do the reading.

## Not wired yet, stated plainly

The components are pure and tested but **not yet called from `AgentTools.executeTool`**. Wiring
planning into the dispatch loop changes when and how tools run, and doing that on the same commit as
the components would make a red CI ambiguous between a reasoning bug and a dispatch regression. The
integration points are known: `ToolPlanner.plan` at request start for multi-step tasks,
`ToolResultCheck.note` appended to a tool result before it enters the transcript, and
`ToolFailurePolicy` consulted where `ToolRetry` is consulted today. This is the remaining P4 work.

## Tests

Planner 12, result check 10, failure policy 10, MCP picker 9, context trim 7 (48 new). Full suite
2440 tests, 1 failure: `BackupTarTest.failureMessageCarriesRealTotals`, root-sandbox only, green in
CI.
