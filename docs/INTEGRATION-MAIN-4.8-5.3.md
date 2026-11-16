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

## Result

| Step | SHA |
|---|---|
| starting `main` | `9754f666` |
| merge Project Four Stage 4.8 | `9d94455` |
| merge Projects Five + Six | `58f6baa` |
| integration candidate (tested by CI) | `55d36fd` |
| `main` after fast-forward | `55d36fd` |

Ancestry on `main` after integration:

```
9754f66 ──┬── 9811e02 … 50505e8 4a96def ──┐   Project Four Stage 4.8
          │                              ├── 9d94455 ──┐
          └── 27a08b6 … 46c6c75 a67a238 ──────────────┴── 58f6baa ── 55d36fd
              Projects Five + Six                (merged)     (main)
```

## Verification on the integrated tree

- app unit suite **0 tests, 0 failures** (the `BackupTarTest` case now skips here with a printed
  reason and still asserts on an unprivileged runner); engine suite **278 tests, 0 failures**.
- `:app:compileDevDebugKotlin` and `:app:dexBuilderDevDebug` green (D8 is the check a green compile
  does not give: a non-local return out of an inline lambda compiles and then fails dexing).
- Cross-project checks by suite, not by hope: duplicate replies and delivery ownership
  (`DeliveryLedger`/`ReplyOwnership` cases), duplicate tool execution and exchange loss
  (`Stage54bProducerTest`, `ToolExchangeLog`), provider history serialisation
  (`Stage54ContinuityTest`, payload assertions), session and retention leakage
  (`Stage63RetentionTest` cross-session case), handoff behaviour (`Stage63HandoffGuardTest`), and
  context budgeting (`LearnedLimitTest`, `Stage62SummaryTest`).
- CI on the exact candidate `55d36fd`: run **43910173612**, job `build`, success.

## What this is not

Integration-tested and CI-green is not device-verified. No APK was built or installed, nothing was run
on the phone, no live provider was contacted, and tools remain component-tested only where they need an
Android `Context`. Every limitation recorded per project in `IMPLEMENTED.md` still applies: no summary
compression, continuation untested below the UI, vocabulary-based retrieval and superseding, retained
files outliving a deleted session until they age out, `runTools` not exercised on the JVM.
