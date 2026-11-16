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
