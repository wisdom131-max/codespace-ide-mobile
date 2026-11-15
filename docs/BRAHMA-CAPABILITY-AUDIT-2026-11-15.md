# Brahma capability audit, gap analysis and implementation plan

**Date:** 2026-11-15
**Reference build:** `com.brahma.assistant` v1.0.1, `b.apk`, SHA-256
`6f4af99d578ec3aed35228bcbb0c44e01172cfc62655ac9e4beec3e91ac63ac0` (owner's own copy, previously approved for
read-only inspection).
**Method:** static inspection of the four `classes*.dex` files with a purpose-written DEX reader
(`/tmp/brahma/dexparse.py`): class list, superclasses, method names and signatures, enum constants read from the
static field tables, and string constants. No decompilation, no execution, no installation, no code or asset
copied.

## Evidence labels

| Label | Means |
|---|---|
| CONFIRMED | A class, method signature, enum constant or string constant in the APK supports it directly. |
| OBSERVED | Seen happening in the app; not used in this document, since nothing was run. |
| INFERRED | A reasonable architectural reading of the confirmed symbols. Not a claim about behaviour. |
| UNKNOWN | Not established by static evidence. |

Static inspection proves **structure**, not **behaviour**. A method named `executeWithHealing` proves a healing code
path exists; it does not prove it succeeds, how often it retries, or whether a user sees it. Every entry below is
labelled on that basis, and nothing here is marked DEVICE-VERIFIED.

---

## A. Brahma capability inventory

### A1. Planning and task decomposition

| Item | Evidence | Confidence | Limitation |
|---|---|---|---|
| `planner/OmniPlanner` singleton holding an Android `Context` | class + `<init>(Context)` | CONFIRMED | Planner is tied to a Context, so it cannot be unit-tested off-device |
| `decomposeGoal(String): List<PlanStep>` | method signature | CONFIRMED | Decomposition strategy UNKNOWN |
| `decomposeGoalWithLlm(String): suspend` | method signature; prompt string "Decompose this composite user goal into ordered subagent steps:" | CONFIRMED for the call and the prompt, INFERRED for the parse step | The LLM reply format is a JSON array in the prompt ("JSON array of steps e.g. [{\"action\": \"open_app\" …}]"); how malformed replies are handled is UNKNOWN |
| `executePlan(goal, steps, callback)` with a `$WhenMappings` inner class | signatures | CONFIRMED | Ordering, retries and cancellation semantics UNKNOWN |
| `PlanStep(stepId:Int, role:SubagentRole, description, action:String, args:Map<String,Any?>, isParallel:Boolean)` | constructor descriptor | CONFIRMED | A step names one action and a boolean `isParallel`; there is no dependency field, so ordering is the list order |
| `StepExecutionResult(stepId, role, description, success:Boolean, output:String, durationMs:Long)` | constructor descriptor | CONFIRMED | Success is a boolean produced by the executor; no evidence field |
| `OmniPlanReport(goal, totalSteps, completedSteps, results:List, durationMs, summary)` | constructor + getters | CONFIRMED | Reporting exists as a data class; whether it reaches the UI is UNKNOWN |

**Reading (INFERRED):** the plan is a flat list with a flag for parallel-safe steps. There is no dependency graph, no
milestone grouping, no revision, and no evidence requirement: a step is done when the executor says `success = true`.

### A2. Subagents and delegation

| Item | Evidence | Confidence | Limitation |
|---|---|---|---|
| `planner/SubagentRole` enum: `GENERAL`, `RESEARCHER`, `BUILDER`, `TESTER` | enum static fields | CONFIRMED | Four roles, not the nine the roadmap imagines |
| Role carried on every step and result | `PlanStep.role`, `StepExecutionResult.role` | CONFIRMED | What a role actually restricts is UNKNOWN; no per-role tool list is visible |
| Parallel execution | `PlanStep.isParallel` | CONFIRMED as a field, INFERRED as concurrency | No visible concurrency bound, no join/timeout handling found |
| Per-step failure string | "Subagent failed: " string constant | CONFIRMED | Failure propagation into the remaining steps UNKNOWN |
| `forge/SkillForgeManager` with `forgeSkillFromPrompt` and `executeSkill`, plus `ForgedSkill` and `SkillActionStep` | class + method signatures | CONFIRMED | Brahma can synthesise a new reusable skill from a prompt and then run it. How a forged skill is validated or approved is UNKNOWN |

### A3. Recovery

| Item | Evidence | Confidence | Limitation |
|---|---|---|---|
| `heal/AutoHealManager` | class, singleton, `getHealLog` | CONFIRMED | |
| `executeWithHealing(toolName, args, context, maxAttempts): suspend` | signature | CONFIRMED | Default `maxAttempts` value is a bytecode constant, UNKNOWN from this pass; no loop breaker visible |
| `DiagnosisResult(category:FailureCategory, rootCause, remedyDescription, remediatedArgs:Map, launchIntent:Intent)` | constructor + getters | CONFIRMED | Recovery is a **re-mediated action**: it rewrites arguments and can launch an `Intent` |
| `FailureCategory` enum: `APP_NOT_FOUND`, `ARGUMENT_ERROR`, `FILE_IO_ERROR`, `NETWORK_TIMEOUT`, `PERMISSION_REQUIRED`, `UNKNOWN` | enum static fields | CONFIRMED | Six categories; no destructive-vs-safe distinction |
| `recordHealing(String)` and the `[Auto-Heal Recovered]: ` string prefix | signature + string | CONFIRMED | The recovery is reported back into the conversation text |
| `findAlternativePackage(packageName, context)` | method in `ExecutionReflectionEngine` | CONFIRMED | |

**Reading (INFERRED):** recovery is optimistic and device-action oriented: diagnose into one of six categories,
rewrite the arguments (a package name, a launch intent), retry up to N times, and prepend a marker to the output. It
is not approval-aware in any visible way, which is exactly the part this project must not copy.

### A4. Execution reflection

| Item | Evidence | Confidence | Limitation |
|---|---|---|---|
| `heal/ExecutionReflectionEngine` | class | CONFIRMED | |
| `reflectAndVerify(goal, args, verified:Boolean, output, context): Pair` | signature | CONFIRMED | Verification is an input boolean plus a text audit, not an independent check |
| `diagnose(tool, args, error, context): DiagnosisResult` | signature | CONFIRMED | |
| `[Verified: ` string prefix | string constant | CONFIRMED | The verdict is written into the reply text |

### A5. Tool and action layer

| Item | Evidence | Confidence |
|---|---|---|
| `actions/BrahmaActionRegistry` plus 27 action classes (`AppLauncherAction`, `BrowserAction`, `FileControllerAction`, `DeviceSettingsAction`, `MessagingAction`, `SystemDiagnosticsAction`, `YouTubeAction`, `MacroProtocolsAction`, `DailyRoutineAction`, `DirectIntentRouter` …) | class list | CONFIRMED |
| Action dispatch by string name with a JSON args map (the planner prompt names `open_app`, `tap_element`, `type_text`) | prompt strings + `PlanStep.action` | CONFIRMED |
| Two AI providers: `ai/GeminiMobileClient`, `ai/OpenRouterMobileClient`; `ai/GeminiChunk$Text` and `GeminiChunk$ToolCall` | class list | CONFIRMED |
| Permission or approval gate around actions | not found as a distinct gate class in the Brahma package | UNKNOWN |

### A6. Memory and context

| Item | Evidence | Confidence | Limitation |
|---|---|---|---|
| `memory/BrahmaMemoryCortex`: SQLite helper, `remember(content, category, importance)`, `recall(query, limit, minScore)`, `forget(id)`, `harvestFactsFromTurn(text)`, `buildContextPrompt(query, limit)`, `extractKeywords` with a `STOPWORDS` set | full method list | CONFIRMED | Real scoring and keyword extraction, inferred BM25-like from `score`/`importance`/`accessCount` fields |
| `MemoryItem(id, content, category, score, importance, createdAt, accessCount)` | constructor + getters | CONFIRMED | |
| `memory/BrahmaMemoryStore`: JSON-file store with `storeMemory`, `recallMemory`, `deleteMemory`, `listAll` | method list | CONFIRMED | Two parallel memory systems exist in the same APK |
| `memory/BrahmaChatHistoryStore`: save/load/clear history as JSON | method list | CONFIRMED | No compaction or summarisation visible |

### A7. Voice and conversation

Covered in the 14 November voice report: Edge websocket read-aloud, `GeminiCharonVoiceClient`, Android TTS
fallback, `AIState` (Idle/Listening/Thinking/Speaking/Error), once-a-day greeting flag in `BrahmaProactiveService`.
Greetings are **out of scope** for VN Code by owner decision.

### A8. Other notable subsystems

`sentinel/*` (device-admin lock screen with live face verification), `office/*` (document handling),
`integrations/*` (28 classes), `automation/*`, `wallpaper/*`, `shopping/*`. Only `office` and `integrations`
overlap with this project's scope; `sentinel` is a different product.

---

## B. VN Code capability inventory

| Capability | Implementation | Tests | Status |
|---|---|---|---|
| Objective plan with milestones, dependencies, revisions | `agent/goal/TaskPlanEngine.kt`, `agent/goal/PlanState.kt`, tool `task_plan` | `TaskPlanEngineTest` (21 cases) | IMPLEMENTED, CI-GREEN (`f7e2371`, run 43756359759) |
| Tool-call order and dedupe | `ToolPlanner`, `ToolBatchPlan`, `ToolCallLedger` | planner/batch/ledger suites | IMPLEMENTED, CI-GREEN |
| Dispatch safety and truncation | `ToolDispatchGuard`, `ToolResultCheck`, `ArgFingerprint` | guard suites | IMPLEMENTED, CI-GREEN |
| Failure policy and reconciliation | `ToolFailurePolicy`, `ToolReconciler`, `ToolEffectIndex` | policy suites | IMPLEMENTED, CI-GREEN |
| Write verification | `WriteVerifier`, `WriteBoundary`, `ToolCallTruncation` | writer suites | IMPLEMENTED, CI-GREEN |
| Memory and facts | `MemoryScope`, `MemoryScopeState`, `agent/facts/*`, `FactGuard`, `FactRetrieval` | facts suites | IMPLEMENTED |
| Context management | `ToolContextTrim`, `agent/context/ContextDigest` | context suites | IMPLEMENTED |
| Research pipeline | `agent/research/*` (planner, sources, quality, contradiction, history, synthesis) | research suites | IMPLEMENTED, CI-GREEN (`9bbc018`) |
| Documents and media | `docs/*` (Docx, Xlsx, Pptx, OCR), `media_info`, `image_convert`, `video_frames` | docs suites | IMPLEMENTED, CI-GREEN |
| Voice | `agent/voice/*` | `voice` suites | IMPLEMENTED, CI-GREEN (`27523ef`, run 43756051162); barge-in and neural male TTS outstanding |
| Connectors and MCP | `AgentConnectorManager`, `McpClientManager`, `McpLifecycle`, `SecureTokenStore` | connector suites | IMPLEMENTED |
| Permission model | `ChatPermissionStore`, Ask/Edit/Agent modes, Auto All / Auto Safe / Always Ask | permission suites | IMPLEMENTED |
| Subagents | none | none | **MISSING** |
| Self-healing | `ToolFailurePolicy` classifies and stops; no diagnosis of *why*, no safe repair | policy suites | **PARTIAL** |
| Execution reflection | `ToolResultCheck` validates a single result; no objective-level review | check suites | **PARTIAL** |
| Skill forging from a prompt | none (skills are user-authored files) | — | MISSING, and deliberately so: see D4 |

---

## C. Comparison matrix

| Capability | Classification | Why |
|---|---|---|
| Objective-level plan with dependencies, milestones, revisions | **VN CODE STRONGER** | Brahma's `PlanStep` is a flat list with an `isParallel` flag and no dependency field; ours refuses cycles, skips dependents on failure, caps revisions and requires evidence |
| Plan evidence requirement | **VN CODE STRONGER** | Brahma records `success: Boolean` from the executor; ours refuses to complete a step without a stated observation |
| Specialised subagent roles | **BRAHMA STRONGER** | Brahma has a `SubagentRole` enum on the step itself; VN Code has no role concept at all |
| Delegation mechanics (parallel flag, per-step duration, result aggregation) | **BRAHMA STRONGER** | `StepExecutionResult` with duration and `isParallel` exists; we have nothing |
| Recovery from a failed action | **BOTH NEED IMPROVEMENT** | Brahma retries with rewritten arguments but shows no approval gate; ours stops but does not diagnose. Neither is both safe and capable |
| Failure classification | **BOTH HAVE IT** | Brahma: six categories. Ours: `ToolFailurePolicy`. Neither is complete on its own |
| Objective-level reflection | **BRAHMA STRONGER**, but shallow | Brahma has `ExecutionReflectionEngine.reflectAndVerify` with a textual audit; ours has nothing at plan level. Theirs trusts a boolean input |
| Memory recall with scoring | **BRAHMA STRONGER** on scoring, VN CODE STRONGER on governance | `BrahmaMemoryCortex` scores and decays; our `FactGuard`/`MemoryScope` gives provenance, correction and scope, which theirs lacks |
| Tool-layer guards (duplicates, truncation, effect index) | **VN CODE STRONGER** | No equivalent to `ToolCallLedger`, `ToolDispatchGuard` or `ToolEffectIndex` is visible in the Brahma package |
| Permission model | **VN CODE STRONGER** | Ask/Edit/Agent plus Auto All/Safe/Ask, with a write gate; Brahma shows no distinct approval gate class |
| Skill forging | **BRAHMA ONLY** | `SkillForgeManager` is a real capability we lack entirely |
| Face-lock security | **BRAHMA ONLY, out of scope** | `sentinel/*`; not a Copilot capability |

---

## D. Gap and opportunity analysis

**D1. Roles are the biggest architectural gap.** Brahma's useful idea is not the plan, it is that a step carries a
*role*, and a role is what lets a plan become a delegation. VN Code's plan has no such notion, so every step is
"the agent does this". The improvement is a role registry that binds each role to a **tool subset** derived from the
existing `ToolMeta` categories and the existing mode gates. That gives specialisation without a second permission
system: a role can only ever narrow what the user has permitted.

**D2. Delegation must not become a way around approvals.** Brahma's `isParallel` flag with no visible bound is the
shape of a side-effect storm. Our version needs a hard concurrency bound, a rule that only read-only steps may run
concurrently, a rule that anything writing must run alone and in plan order, and cancellation that leaves no partial
write.

**D3. Recovery should be a proposal, not an action.** Brahma rewrites arguments and relaunches an `Intent` inside
`executeWithHealing`. Ours must diagnose into a category, and then either perform a *provably safe* repair (a retry of
a read, a path correction inside the project, a re-resolve of a stale path) or hand the user a specific proposal.
Destructive or non-idempotent steps never auto-recover.

**D4. Skill forging: not now.** `forgeSkillFromPrompt` generates executable steps from prose. Without an approval and
validation story that is a supply-chain hole dressed as a feature. We record it as BRAHMA ONLY, and revisit only with
a designed review gate.

**D5. Reflection is the piece that makes the other two honest.** A reflection pass compares objective, plan, actions
and evidence, and refuses to call a task done because nothing threw. This is the cheapest large reliability win
available, and it composes with the plan we already ship.

**D6. Where neither is adequate.** Long-running work across a process death: Brahma stores history JSON; our resume
work covers interrupted turns but not a plan resumed days later. Parked as a Project Six item, not patched here.

---

## E. Implementation plan

Ordered, with dependencies. Every stage is one commit, tests before push, CI verdict read before the next push.

| # | Stage | Objective | Reuses | Depends on | Acceptance | Tests |
|---|---|---|---|---|---|---|
| 3.3 | Subagent roles | A role registry binding each role to an allowed tool subset and a write capability, derived from `ToolMeta` + mode gates | `ToolRegistry`, `ToolMeta`, `ChatMode`, `TaskPlanEngine` | 3.2 | A role can never expose a tool outside the caller's permitted set; unknown roles refused | role × tool matrix, narrowing proofs |
| 3.4 | Delegation policy | Bounded, cancellation-safe delegation: parallel only for read-only independent steps, writes serialised in plan order, concurrency cap, conflict detection on the same path, result aggregation with per-step evidence | `TaskPlanEngine` waves, `ToolBatchPlan` | 3.3 | A write never runs concurrently with anything; a cancelled delegation leaves no partial write; two roles writing one path is reported as a conflict | policy cases incl. cancellation and conflict |
| 3.5 | Self-healing | Diagnosis of a failure into a category, a safe-repair whitelist, refusal of the rest, one repair per step | `ToolFailurePolicy`, `ToolResultCheck`, plan revisions | 3.4 | Only whitelisted repairs auto-apply; a destructive step is never repaired blindly; every repair is recorded with its evidence | classifier + repair-safety cases |
| 3.6 | Execution reflection | Objective-level review: objective vs plan vs actions vs evidence, incomplete-work detection, unsupported-success refusal | `TaskPlanEngine`, delegation records, `ToolResultCheck` | 3.5 | A plan with a failed or skipped step can never be reported complete; a success claim without evidence is downgraded with the reason named | reflection cases |
| 4.x | Tool intelligence | Semantic discovery, suitability scoring, structured errors, safe retries, health | existing dynamic loading | 3.6 | No regression in the 129-tool registry or the doc budget | registry suites |
| 5.x | Cross-provider | Capability catalogue, routing, fallback without duplicate execution | provider layer | 4.x | A fallback replays no completed tool call | fallback suites |
| 6.x | Long context | Budgeting, summarisation, continuation, provenance | `ToolContextTrim`, facts store | 5.x | An interrupted session never resubmits a completed request | context suites |
| 7.x | Experience | Streaming, presentation, accessibility | chat panel | 6.x | No greeting path exists anywhere | UI-state suites |
| 8.x | Reliability and security | Connector health, timeouts, refresh, revocation, audit | connector layer | 7.x | No secret can reach a prompt, log or commit | security suites |

Deliberately excluded: skill forging (D4), face lock (A8), copying any Brahma code, assets, endpoints or tokens.

---

## Status legend for every later stage report

RESEARCHED → IMPLEMENTED → TESTED → CI-GREEN → DEVICE-VERIFIED → COMPLETE. Device verification stays parked until the
eight-project programme finishes; nothing in this document is device-verified.
