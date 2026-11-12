# P4.A - the planner now plans

`ToolPlanner` computed dependency waves and nothing on the request path asked it. `runToolCalls`
executed calls in arrival order, and would run the same `(name, arguments)` twice if the model emitted
it twice.

## What is wired

`ChatSession.runToolCalls`, before the loop:

- `ToolPlanner.plan(batchNames, offeredNames)` - the batch is the intent, so the goal comes from the
  calls themselves and needs no session state.
- `ToolBatchPlan.order(plan, names)` - each planner step claims the first call with its tool name,
  wave by wave. Calls the plan does not mention keep their order at the end. The result is always a
  permutation, so planning cannot lose a call.
- `ToolBatchPlan.duplicates(names, args)` - an exact repeat is answered from the first result with a
  visible note, instead of executing the side effect twice.
- `ToolBatchPlan.note(...)` puts the wave shape in the Thinking Tree when there is more than one wave,
  so a multi-step turn reads as a plan rather than a list of surprises.

## A real planner defect this exposed

`ToolPlanner.add` dropped a step whose tool was not offered - and left dependents pointing at the
dropped id. For `"edit the file and verify"` with only `read_file`/`write_file` offered, the `read`
step depended on a `grep_search` step that did not exist, so `waves()` returned nothing, and the order
fell back to arrival order while the plan looked authoritative. Fixed: a dropped step's id is
recorded, and dependents have it pruned. `aDroppedStepDoesNotBlockWhatDependendedOnIt` locks it.

## Tests

`ToolBatchPlanTest` (10): single call untouched, order is always a permutation, a read is scheduled
before the write it informs, an unmentioned tool keeps its slot at the end, exact repeat points at the
first call, same tool with different arguments is not a repeat, different tools with identical
arguments are not a repeat, three identical calls all point at the first, the note is quiet for one
wave and speaks for several, and a dropped step does not block its dependents.

Full suite 2541 tests, one known `BackupTarTest` root-sandbox failure (green in CI).

## Still open in P4, stated plainly

- Parallel execution of genuinely independent calls (P4.B): the loop is still sequential. Ordering,
  duplicate suppression and planning are in place; concurrency is the next commit, and it needs the
  transcript to keep a deterministic result order while it runs.
- `McpToolPicker` is called from `McpClientManager`, not from the request path. Verifying that it is
  on the real discovery flow is P4.C.
