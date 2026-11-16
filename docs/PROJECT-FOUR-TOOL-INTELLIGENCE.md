# Project Four — Advanced Tool Intelligence: Brahma comparison and gap analysis

16 November 2026. Evidence base: static inspection of the owner's own reference APK (SHA-256
`6f4af99d…ac63ac0`) with the purpose-written DEX reader, plus the VN Code tree at `deb7865`.

Confidence labels: CONFIRMED (read from code), INFERRED (architectural reading), UNKNOWN (not
established). Nothing here comes from running Brahma.

## Brahma's tool architecture, as found

| Capability | Evidence | Confidence |
|---|---|---|
| One flat action list, all sent every turn | `BrahmaActionRegistry.buildGeminiToolsJson()` returns a `JSONArray` of every tool; no filter, no ranking, no budget | CONFIRMED |
| Name dispatch | `BrahmaActionRegistry.execute(name, args)` is a `when` over names returning `Pair` | CONFIRMED |
| No semantic discovery | No method in the Brahma package ranks, scores, embeds or shortlists tools; the only selection-shaped symbol is `buildGeminiToolsJson` | CONFIRMED |
| No tool grouping or catalogue | No group/category concept in the action package | CONFIRMED |
| Device-automation action set | ~40 names: `launch_app`, `tap_element`, `type_text`, `get_clipboard`, `get_notifications`, `run_terminal`, `search_files`, `play_youtube`, `create_word/excel/ppt`, `list_skills`, `create_skill` | CONFIRMED |
| Provider-specific tool format | Tools are built straight into Gemini's wire shape, which couples the tool layer to one provider | CONFIRMED |
| No structured failure taxonomy at the tool layer | Failures surface as strings; classification lives in `heal/FailureCategory`, applied after the fact | INFERRED |

Brahma does have one thing VN Code does not: `forge/SkillForgeManager`, which generates a new skill
from a prompt and runs it as `SkillActionStep`s. That is dynamic registration in the strongest sense,
and it is ALSO the riskiest capability in the app, since generated code runs with the assistant's own
device authority. It is recorded as **BRAHMA ONLY — not adopted**: there is no approval story for
generated actions, and this project does not import one.

## Where VN Code already stands

| Capability | Implementation | Evidence |
|---|---|---|
| Tool metadata (category, keywords, cost, destructive) | `ToolMeta`, `ToolCategory`, `ToolCost`, `ToolMetaIndex.metaFor` | CONFIRMED |
| Relevance scoring with synonyms and task kind | `ToolRelevance.score` / `rank` / `kindOf` | CONFIRMED |
| Lean Tool Mode and on-demand loading | `ToolOffer.plan`, `ToolLoadout` | CONFIRMED |
| Grouped catalogue and search | `ToolCatalogue.entries/groups/search` | CONFIRMED |
| Multi-call planning into waves | `ToolPlanner`, `ToolBatchPlan` | CONFIRMED |
| Duplicate-call and duplicate-side-effect prevention | `ToolCallLedger`, `ToolRetry`, `ToolEffectIndex` | CONFIRMED |
| Result validation | `ToolResultCheck` | CONFIRMED |
| Structured failure classes and retry policy | `ToolFailurePolicy`, `ToolDispatchGuard` | CONFIRMED |
| MCP discovery with a hard approval boundary | `ToolDiscovery`, MCP guard (AUTO_ALL included) | CONFIRMED |

## Comparison matrix

| Capability | Brahma | VN Code | Class |
|---|---|---|---|
| Tool catalogue breadth | ~40 device actions | 129 tools across 15 categories | VN CODE STRONGER |
| Discovery by intent | none | keyword + synonym + task-kind scoring | VN CODE STRONGER |
| Token discipline | every tool every turn | Lean mode, loadout, ranked offer | VN CODE STRONGER |
| Grouping for humans | none | `/tools` catalogue with search | VN CODE STRONGER |
| Dispatch safety | name lookup | guard, ledger, effect index, approval gates | VN CODE STRONGER |
| Device control | deep (intents, taps, notifications) | deliberately out of scope | BRAHMA STRONGER |
| Self-generated skills | `SkillForgeManager` | absent by design | BRAHMA ONLY |
| Provider-independent tool format | no (Gemini-shaped) | yes (one spec, any provider) | VN CODE STRONGER |
| Prerequisite awareness | none | none | BOTH NEED IMPROVEMENT |
| Coverage of a multi-capability request | none | ranking only, no coverage check | BOTH NEED IMPROVEMENT |
| Tool health / demotion of a broken tool | none | none | BOTH NEED IMPROVEMENT |
| Cost-aware routing | none | cost enters the score | VN CODE STRONGER |

## Gap analysis and what Project Four will add

1. **Coverage, not just ranking.** Ranking answers "which tool is best for this request". It cannot
   answer "does this request need two capabilities, and is one of them impossible right now". A
   rename across a project needs find AND replace; in Ask mode the replace half is unavailable, and
   today the model discovers that by being refused. Stage 4.1 adds a capability assessment that
   names the gap, and distinguishes "loadable" from "not in this mode" from "not implemented".
2. **Tool health.** A tool that fails twice in a row stays as attractive as before. Stage 4.1 adds a
   bounded health record fed by real dispatch outcomes, and demotes a tool that keeps failing
   instead of letting the model rediscover the same failure.
3. Later stages, in dependency order: capability-and-dependency awareness in the planner (4.2),
   orchestration quality across waves (4.3), tool result validation tightening (4.4), MCP lifecycle
   health and reconnection (4.5).

Not adopted, with reasons: generated-skill execution (no approval story), device-automation actions
(out of the IDE's scope and outside its permission model).

## Stage 4.2 — capability and dependency awareness (implemented)

Stage 4.1 could say whether a capability exists. It could not say what must happen first, nor
whether a request that needs four capabilities is doable at all. Both are now represented.

**Prerequisite metadata** lives in `ToolPrerequisites`: a capability graph (replace needs find;
unzip needs the archive inspected; test and edit carry OPTIONAL hints only) plus a tool-to-capability
table so a real call can satisfy a prerequisite. Prerequisites are metadata about work, not
permission: nothing there can approve, expose or widen a tool.

**Planning** lives in `DependencyPlanner`: it computes the closure of a request, orders it into
waves, refuses a cycle before anything runs (naming the loop), marks a dependent IMPOSSIBLE when a
required prerequisite is unreachable in this mode, and BLOCKED when one already failed. Optional
edges never become waves, so an optional step can never turn into a mandatory call.

**Whole-request coverage** lives in `ToolTaskCoverage`: it reuses `ToolCapabilityPlan.assess`, adds
the dependency plan, and reports what can be done, what cannot, why, and whether the complete
request is executable. Partial support is never reported as complete.

**Enforcement** is in the existing gate: `RuntimeBridge.prerequisiteRefusal` refuses a call whose
capability has an unsatisfied REQUIRED prerequisite, quoting the reason and the failed evidence.
`PrereqLedger` records what actually succeeded (with output as evidence) or failed, and
`afterDispatch` feeds it from real tool outcomes. The refusal happens BEFORE the mode, approval, MCP
and role checks, so a satisfied prerequisite still has to pass every one of them: a dependency can
never grant permission, and MCP's hard boundary including AUTO_ALL is untouched.

**Health** is used only to break ties between candidates that already serve the same capability
(`ToolTaskCoverage.choose`). It never adds a tool a mode does not expose, never disables one, and
never overrides suitability.

Tests: 26 cases in `ToolPrerequisiteTest` covering the metadata table, waves, cycles, impossible and
blocked dependents, satisfaction, the gate in both directions, failed-prerequisite messaging, and the
three health rules. Full suite: 3,127 tests, one failure, `BackupTarTest.failureMessageCarriesRealTotals`
(expected 2, got 0), which reproduces on a clean tree and passes in CI.

Known limitations: the wave computation is not yet wired to execute waves itself (the model still
drives calls one at a time, in order); `PrereqLedger` is session memory, so a process restart forgets
what was satisfied; and the prerequisite table is deliberately small.

## Stage 4.3 — orchestration that runs, evidence that survives (implemented)

Stage 4.2 could compute waves and refuse a call whose prerequisite was missing. The waves were a
diagram: execution was still one call at a time, and the ledger forgot everything when the activity
died. Two changes close that.

**Waves execute.** `Orchestrator` takes the waves and runs them. Read-only steps in a wave run
concurrently behind a semaphore (default 2 on a device with four or fewer cores, never more than 3);
a step that writes or is destructive runs alone, after the readers. A failure records the failed
capability, stops later waves, and marks them skipped by name instead of leaving them looking
unstarted. A step that throws is a failure, not a pass. Cancellation is checked before every start
and reported as CANCELLED, never as success. `RuntimeBridge.delegate` now hands its wave to the
orchestrator, so delegated work is the first real user of it, and every task still goes through the
same `execute` lambda and therefore the same gate, approval, MCP and role checks.

**Evidence survives.** `PrereqStore` writes the ledger to `filesDir/prereq-ledger.json` using the
`ToolCallLedger` convention: a temp file and a rename, bounded to 200 entries, 240 characters of
evidence per entry, structure only and no transcripts. `PrereqLedger.attach` binds it per
conversation and plan revision; a corrupt file is reported as untrusted and yields nothing rather
than being guessed at. `OrchestrationResume` classifies what came back: a completed write is DONE and
is not repeated, a failed write that may have landed NEEDS_APPROVAL, a cheap read is RETRY_SAFE, and
anything proved under another conversation or an older plan revision is BLOCKED.

Still not done, and deliberately: the orchestrator's parallelism is used by delegation only. The
main chat loop still issues model tool calls one at a time, because parallelising arbitrary model
calls needs per-call identity in the transcript, which is Stage 4.4 work. The Thinking Tree shows the
existing per-step activity lines; it does not yet render "waiting for prerequisite" as its own state.

### 4.3 second pass — resume, duplicates and the reason on screen

The first pass ran the waves and wrote the ledger. This pass closes the gaps that only show up when
a run is interrupted.

- **Resume is enforced, not described.** `Orchestrator.run(prior = ...)` reads what the durable ledger
  proved. A capability that is settled is skipped, so a completed write is never repeated; a
  write whose outcome is uncertain is *held* and named, never guessed at; a cheap read may be tried
  again. Evidence from another conversation, or from an older plan revision, blocks instead of
  counting.
- **Duplicates lose.** A step id is claimed once per run; the second dispatch of the same id is
  refused and recorded, which covers a model retry and two workers naming the same task.
- **A failure stops the chain, not the turn.** Steps that depend on the failed capability are
  skipped by name; steps that never depended on it still run, once. One replan is the limit, and a
  second failure is reported rather than retried.
- **The reason is on screen.** `OrchestrationTrace` keeps the last 16 lines of orchestration and the
  delegation path feeds it, so "held: an earlier edit may already have landed" is visible in the
  same block as the plan, separate from the grouped `/tools` catalogue.

Twelve further cases (durable roundtrip, corrupt file, bounded tail, settled write, uncertain write,
retryable read, foreign conversation, stale revision, duplicate id, held step, failure isolation,
cancellation, trace). Full suite 3,157 tests, one failure: `BackupTarTest`, unchanged and
pre-existing.

## Stage 4.4 — an interrupted turn says what it left behind (implemented)

Stage 4.3 made delegated waves resumable and left the chat turn without an answer: a process death
mid-plan kept steps marked RUNNING and nobody could tell a finished write from one that never ran.

`TurnRecovery` reads the plan and the durable ledger and answers four ways, reusing
`OrchestrationResume` instead of a second classifier: PROVEN (evidence exists), UNKNOWN (a write that
may or may not have landed, which must be confirmed before it repeats), OPEN (safe to run again), and
BLOCKED (proved in another conversation, or under a plan revision this one is not). One sentence
reaches the transcript; it never says "resumed" when nothing was.

Two rules got sharper on the way. A successful read is proved *and* retry-safe, which the resume
classifier had expressed as one label; and a record that does not say which plan revision it belongs
to is now BLOCKED rather than trusted, because "I do not know which plan this was" is not evidence.

`task_plan action=show` now appends the proof: which capabilities the ledger actually records, which
ones are blocked, and the interruption sentence when there is one. That is the same plan block the
Chevron tree already renders, so the dependency state is visible without a new surface and without
touching the grouped /tools catalogue.

## Stage 4.5 — a result is not evidence until it is checked (implemented)

Stages 4.1–4.4 assumed that a tool which returned had done the work. This stage separates the two
questions and makes the second one decide.

`ToolOutputContract` holds a deliberately small set of output contracts (write acknowledgement words,
test-result markers, which tools may legitimately return nothing). `ResultValidator` composes those
with the existing `ToolResultCheck` into one verdict: valid, legitimately empty, unexpected empty,
malformed, partial, unacknowledged, failed, or validator-failed. A validator bug is never evidence
about the tool, and a truncated result is real but cannot satisfy a prerequisite that needs the whole
answer.

`SideEffectVerifier` reads the filesystem after a write, move or directory creation: the file is
there, or it is not; for a whole-file write, its contents match what was asked for. It reads only, so
proving a write cannot duplicate it, and an unresolvable relative path says nothing rather than
refusing a change that may have landed.

The live gate now: validates the result, appends one plain-language caveat for the model, and records
the verdict in `PrereqLedger`. Only `valid` and `empty valid` satisfy a capability, so an
unacknowledged write blocks its dependent call instead of looking like proof. Tool health receives
only genuine outcome problems; truncation, cancellation and validator failures no longer demote a
tool.
