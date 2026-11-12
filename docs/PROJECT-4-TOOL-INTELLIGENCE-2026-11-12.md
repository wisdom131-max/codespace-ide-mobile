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

## What is wired, and what is not

Wired into the production dispatch path (`AgentTools.executeTool`):

- **Result validation.** Every tool result passes through `ToolResultCheck.check` and gains its
  note when there is something to say. A truncated read now carries "do not describe the operation
  as complete"; an "error:" or "command not found" inside a normal return is marked for read-back.
  A legitimately empty grep adds nothing.
- **Failure interpretation.** The exception path classifies the failure and appends the one action
  that can follow it, so the model is told whether this was a permission refusal, a missing
  prerequisite, an argument problem or a timeout.
- **MCP selection.** `McpClientManager.suggestFor` ranks the discovered tools against this turn's
  request and, when one fits but is not loaded, adds a single line naming it and stating that
  loading it needs the owner's approval. Nothing is loaded automatically and the approval gate is
  untouched, so the hint cannot widen what the model may call.

**Not wired: planning, dependency-aware execution and context trimming.** `ToolPlanner` and `ToolContextTrim` are implemented
and tested, but the planner changes when and how tools run and the trimmer changes what history the
model sees, so each needs its own commit against a known-green base rather than riding along with
validation. The integration point is the request loop in `ChatSession`, where the
plan would land as Thinking Tree steps. Until then the model still chooses its own order; nothing in
this commit pretends otherwise.

## Tests

Planner 12, result check 11, failure policy 10, MCP picker 9, context trim 7 (48 new). Full suite
2440 tests, 1 failure: `BackupTarTest.failureMessageCarriesRealTotals`, root-sandbox only, green in
CI.
