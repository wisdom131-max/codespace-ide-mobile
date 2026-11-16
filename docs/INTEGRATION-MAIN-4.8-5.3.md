# Integrating Project Four Stage 4.8 and Projects Five–Six into `main` (2026-11-16)

## Starting point, verified rather than assumed

| Ref | SHA |
|---|---|
| `main` | `9754f666` |
| `stage-4.8` (Project Four) | `4a96def` |
| `stage-5.3` (Projects Five + Six) | `a67a238` |

Both source branches fork from `9754f666` itself (`git merge-base main stage-4.8` = `git merge-base main
stage-5.3` = `9754f666`). Neither contains the other: `main..stage-4.8` is 6 commits, `main..stage-5.3`
is 26, and `main` is ahead of neither. So this is a two-way integration, not a fast-forward.

## Merge order and why

**Stage 4.8 first, then stage-5.3.** The dependency runs that way: Project Six's `ChatSession.buildEngine`
wiring and `HandoffGuard` sit on top of the delivery path Project Four's `DeliveryLedger` rewrote, and
Project Five's `ToolExecutionGateway` delegates into the same single gate Project Four hardened. Merging
the smaller, older lineage first keeps the conflicted file (`ChatSession.kt`) a three-way merge between
two known parents rather than a replay across 26 commits.

Both merges use `--no-ff`, so each project keeps its own ancestry and its own merge commit. Nothing is
squashed and no source branch is reset or rewritten.

## Overlap and the one real conflict

| File | Situation |
|---|---|
| `docs/IMPLEMENTED.md` | **conflict**: both sides append rows to the same trailing table. Resolved by keeping BOTH sets of rows, Project Four's first. Losing either side would have deleted a project's history from the ledger, which is the one thing this file exists to prevent. |
| `android/.../chat/panel/ChatSession.kt` | auto-merged, verified by hand: `DeliveryLedger` (4.8), the `DeliveryStore` attach, `summaryStore` (6.3), `lastContextDecision` (6.1) and the handoff wiring all present. |
| `.github/workflows/*`, `AGENTS.md`, engine sources | auto-merged without overlap. |

Because a machine merge can silently keep one side of a file, each project's markers were grepped on the
merged tree before the merge was committed.

## Security invariant

`AgentTools.executeTool` is still the only execution gate. Neither merge adds an executor: retention and
retrieval are text, `HandoffGuard` is a decision, `ToolExecutionGateway` validates and then delegates.
No approval, role, MCP, prerequisite or side-effect check was relaxed to make the merge apply.

## The long-standing local failure, answered rather than waved through

`BackupTarTest.failureMessageCarriesRealTotals` fails on this sandbox and passes in CI. Cause, measured:
the case makes its destination unwritable and expects one failure per entry, and **this sandbox runs as
root** (`id -u` = 0), so directory permissions are not enforced, nothing fails, and the assertion reads 0
instead of 2. That is the environment, not the extractor.

It is no longer labelled harmless and left at that: the case now probes the filesystem
(`permissionsEnforced()`) and skips with a printed reason where permissions cannot bite. On CI's
unprivileged runner it runs exactly as before, with no assertion weakened.
