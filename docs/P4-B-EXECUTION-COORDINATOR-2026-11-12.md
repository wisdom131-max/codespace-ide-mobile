# P4.B - execution is coordinated

The batch is planned (P4.A) and now executed under a rule rather than a `for` loop.

## The rule

`ToolExecutionCoordinator`:

- **Only proven-independent tools run together.** `PARALLEL_SAFE` is ten read tools:
  `read_file`, `grep_search`, `list_dir`, `file_exists`, `find_files`, `read_project_structure`,
  `zip_list`, `checksum`, `get_diagnostics`, `package_tree`. Every write, every command, every git
  operation, every connector call, every MCP call and every research call (they own the source ledger)
  stays strictly sequential. Adding a name is a claim that concurrent calls cannot interfere, and the
  list is asserted by a test so it cannot drift quietly.
- **Bounded**: `MAX_PARALLEL_READS = 2`. This ships on a phone with little RAM and one connection.
- **Deterministic**: answers are collected into a map keyed by plan index, and the session renders in
  plan order. Completion order never reaches the transcript.
- **Fail-fast within a wave**: `coroutineScope` cancels siblings when one call throws, so a failed
  wave cannot leave reads running behind a turn that already moved on.
- **A write is never sandwiched inside a read wave**: a non-safe call flushes the queued reads first.

## Integration

`ChatSession.runToolCalls` now:

1. plans the batch (P4.A) and orders it,
2. walks the plan order doing row, reuse, staging and **approval** strictly sequentially,
3. queues consecutive parallel-safe calls and flushes at the ceiling,
4. runs everything else alone.

Approval therefore still happens before any execution, and a declined call records `NOT RUN` exactly
as before. Parallel execution happens on `Dispatchers.IO`, so read-heavy turns no longer block the main
thread. `ToolDispatchGuard` and its ledger are untouched and still wrap every call.

## Tests

`ToolExecutionCoordinatorTest` (9): writes and commands are never parallel-safe, the safe set is
exactly the ten reads, runs preserve order and split around a write, a single read is its own run,
results come back by index not completion order, concurrency stays under the ceiling while genuinely
overlapping, every call runs exactly once, a failure cancels its wave's siblings, and a write between
reads is never overlapped with them.

Full suite 2550 tests, one known `BackupTarTest` root-sandbox failure (green in CI).

## Limitation, stated

Parallelism is per contiguous run of safe reads, not a global scheduler over the whole plan. That is
deliberate: a wider scheduler would need per-tool locks around `AgentTools`' shared state (write
journal, source ledger, loadout version), and the phone gains little from three concurrent reads over
two.
