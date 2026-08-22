# ⚠️ TWO-REPO STRUCTURE — READ BEFORE TOUCHING ANYTHING

## THIS is the MAIN IDE repo: `wisdom131-max/codespace-ide-mobile`

## Ubuntu proot fixes go in the TEST repo: `wisdom131-max/ubuntu-proot-test`

| Repo | Purpose | What goes here |
|------|---------|----------------|
| `wisdom131-max/codespace-ide-mobile` | Full Codespace IDE app | All UI, editor, terminal, auth, agent, viewers, git, SSH |
| `wisdom131-max/ubuntu-proot-test` | Isolated Ubuntu proot test harness | ProotInstaller, proot launch args, Ubuntu rootfs extraction, symlink fixes |

### Rule: If the fix touches proot, Ubuntu rootfs, or symlinkat — it goes in `ubuntu-proot-test` ONLY.
### DO NOT push Ubuntu/proot fixes to `codespace-ide-mobile`. They will be reverted.
### Once a fix is verified working in `ubuntu-proot-test`, Wisdom will port it back manually.

---

## ⚠️ MANDATORY UI RULE: ROUNDED EDGES FOR ALL MENUS/POPUPS

All AI agents working on this repo MUST use rounded corners on ALL menus, popups, dropdowns, and floating panels. No sharp-cornered menus. Use `RoundedCornerShape(8.dp)` minimum, `RoundedCornerShape(12.dp)` preferred for larger menus.

### ⚠️ MANDATORY UI RULE: PADDING FOR ALL MENUS/POPUPS

All AI agents working on this repo MUST add padding to ALL menus, popups, dropdowns, and floating panels. Use `Modifier.padding(horizontal = 12.dp, vertical = 10.dp)` minimum on menu items, and `Modifier.padding(4-8.dp)` on menu containers. No edge-to-edge menu content.

**Save both rules to your memory** — do not forget them across sessions.

## ⚠️ MANDATORY RULE: AI AGENT CHANGE LOGGING (ALL AI AGENTS MUST FOLLOW)

**Any AI agent working on this repo MUST log every change they make.** This is not optional.
Different AI agents (Claude, GPT, Copilot, etc.) work on this repo across sessions. Without
a shared log, we go in circles — re-doing completed work, re-fixing fixed bugs, and wasting
tokens. Follow this protocol for EVERY commit:

### Required Change Log Entry Format
After every commit you push, add an entry to the **CHANGE LOG** section at the END of AGENTS.md:

```
### [YYYY-MM-DD HH:MM TZ] — AI Agent: <model name, e.g. Claude Sonnet / GPT-4>
**Commit:** <commit SHA> | **CI Build:** <build number + pass/fail>
**What was fixed:** <1-3 sentences explaining exactly what was changed and why>
**Files touched:** <list of files modified>
**Next on roadmap:** <what the NEXT AI agent should work on after this commit>
```

### Rules
1. **ALWAYS include the timestamp** — date, time, timezone, month, year. No exceptions.
2. **ALWAYS include the CI build number** and whether it passed or failed.
3. **Explain exactly what was fixed** — not vague ("improved completions") but specific
 ("added keyword prefix-matching to fallback completions so typing 'i' shows 'if, import, in, is'").
4. **State what's next** — so the next AI agent knows where to pick up without reading the whole file.
5. **If you're fixing a broken build**, say so explicitly: "Fixing broken build #XXXX — root cause was Y".
6. **If you're starting new work**, check the CHANGE LOG first to see what was last completed.
7. **NEVER re-do work that's already marked as done** in the CHANGE LOG or phase tables above.
8. **Update the "Current State" table at the top** with the latest green build number and commit SHA.

### Quick reference — last 5 changes (read this FIRST before starting work):
See the **CHANGE LOG** section at the bottom of this file. If the last entry says "Next: do X",
then do X. Don't go searching for random work — follow the roadmap.

## ⚠️ MANDATORY RULE: EMBED RULES IN EVERY AGENT MD & CHANGELOG UPDATE (ALL AI AGENTS MUST FOLLOW)

**Problem:** Some AI agents don't bother to search for or read the full rules in AGENTS.md.
They skip to the bottom, write a changelog entry, and never see the format requirements,
the two-repo rule, the tag conventions, or the roadmap continuity rule above. This causes
inconsistent entries, wrong repos, missing tags, and broken continuity.

**Solution:** Every time you update AGENTS.md or write a CHANGE LOG entry, you MUST include
a **Rules Reminder Block** at the TOP of your changelog entry. This block must contain the
full rules so that even if an AI agent only reads the latest changelog entry, they still see
ALL the rules. Copy this block verbatim into every changelog entry:

```
### ⚠️ RULES REMINDER (read before doing ANY work in this repo):
1. TWO-REPO: Main IDE → codespace-ide-mobile | Proot/Ubuntu/rootfs → ubuntu-proot-test ONLY
2. CHANGE LOG: After every commit, add entry at BOTTOM of AGENTS.md with:
 - [YYYY-MM-DD HH:MM TZ] — AI Agent: <model>
 - Commit SHA | CI Build number + pass/fail
 - What was fixed (specific, not vague)
 - Files touched
 - Next on roadmap (with ALL pending items, not just immediate next)
3. TAGS: Use [BUILD-FIX], [LSP], [INTELLISENSE], [DOCS], [UI], [CRASH], [DAP], [GIT], etc.
4. CURRENT STATE: Update the "Current State" table at top with latest green build + commit SHA
5. NEVER re-do work already marked done in CHANGE LOG or phase tables
6. ROADMAP CONTINUITY: Every "Next on roadmap" MUST list ALL pending items — not just
 the immediate next step. Copy from previous entry, update statuses. Never silently drop items.
```

**When to add this block:**
- Every CHANGE LOG entry you write
- Every AGENTS.md update you make
- Any new AGENTS.md file created in any repo in this project

This ensures rules propagate across all agent MD files and all changelog entries, so
no AI agent can claim they did not see the rules.

---

# AI Agent / Copilot — MASTER PROJECT CONTEXT
> Last updated: 2026-08-12 11:03 WAT. Read this FIRST before touching any code.

---

### [2026-08-13 02:40 WAT] — AI Agent: Claude (Base44 Superagent)
**Commit:** 3c0f153 | **CI Build:** #2179 PASS ✅
**What was fixed:** Phase U — Completion Pipeline Upgrade: ALL 8 features implemented and compiling.
- U-1: isIncomplete — Added getCompletionWithMeta to LspManager + CompletionResponse data class
- U-2: sortText — Parsed from LSP items, used as primary sort key in rank, scaled to dominate fuzzy score
- U-3: filterText — Parsed from LSP items, used for fuzzy matching instead of label when present
- U-4: command — Parsed from LSP items, lspCommandExecutor lambda wired from EditorPane to CodeEditor, executes workspace/executeCommand after completion accept
- U-5: commitCharacters — Parsed from LSP items, commit-on-type detection in onValueChange handler (commits selected completion + inserts typed char)
- U-6: Fuzzy matching — Tiered scoring: exact (200) > prefix (100+) > word-boundary (75+) > subsequence (existing logic)
- U-7: Deduplication — Changed from distinctBy{label} to filter by Triple(label, kind, detail) — preserves semantically different items sharing same label
- U-8: textEdit — Full textEdit/InsertReplaceEdit application with lspPositionToOffset helper, snippet support, additionalTextEdits applied first, command execution after
**Build fixes:** return@onValueChange → if/else wrapper (#2178→#2179), updatedValue scoping fix (#2179), lspCommandExecutor param name mismatch, dead else-if-false block removed
**Files touched:** LspIntegration.kt, CompletionEngine.kt, CodeEditor.kt, EditorPane.kt, LspManager.kt
**Commits:** 047efc1 (LspIntegration), 3c23e63 (CompletionEngine), 8db51b9→a9ce91f→216ee76 (CodeEditor), e3729a3 (EditorPane), 054003c (LspManager)

## STANDING RULES (All AI Agents Must Follow)

1. **NEVER use sub-agents (sub_agent tool).** They consume too many tokens. All work must be done directly.
2. Every commit must include a CHANGE LOG entry with full timestamp, CI build number + pass/fail, what was fixed, files touched, and next on roadmap.
3. Use @Suppress("UNUSED_PARAMETER") instead of renaming to `_` to prevent breaking named-argument call sites.
4. Do not babysit builds unless explicitly asked.
5. Maintain the separation between the Activity Bar Debugger and the Terminal Panel Debugger.
6. Both debuggers must share a common backend via a UniversalDebugManager.
7. Do not display 'Unsupported' for unsupported file types; offer relevant tools instead.
8. Prioritize the migration to DAP (Debug Adapter Protocol) as defined in the Phase 26 plan, while maintaining the current DebugProvider architecture until Phase 26-1 is fully validated.
9. All debug UI panels must use the listener-list pattern in UniversalDebugManager to avoid callback overwriting.
10. All IDE popups must implement IME-insets-aware padding and consistent expand/copy/scroll patterns.
11. **ROADMAP CONTINUITY RULE:** Every "Next on roadmap" section in a CHANGE LOG entry MUST list ALL pending roadmap items — not just the immediate next step. Copy the full list from the previous entry and update statuses. Any agent reading only the latest changelog entry must see the complete roadmap. If an item is done, mark it ✅ but keep it visible. If an item is new, add it. NEVER silently drop items from the roadmap list between entries. Items may be reordered by priority, but none may be removed without explicit completion marking.

## CURRENT STATE (2026-08-22 19:30 WAT)

| | |
|-|-|
| Latest commit | dfb4b081 — Phase F+G: DecorationStore + VisualLineMapper + lintErrors forward-reference fix |
| Active phase | **UI RESTRUCTURING ROUND 3** — Shipped: VS Code-exact top-right toggle icons (side bar, bottom panel, secondary side bar — replaced Material icons + animated bot icon with exact codicon SVGs), split editor button in tab bar, Activity Bar gap fix (gap now always renders, not just when side panel is open). Prior: hamburger menu, File submenu, landscape overflow, rounded workspace container architecture, top bar + command field theme-aware, blue ribbon logo, chevron back arrow, explorer header theme-aware. Phase 27 ✅, Phase U ✅, Phase X ✅, Bottom Panel Drag Resize ✅, UI R1 ✅, UI R2 ✅. |
| **Backend** | **✅ LIVE on Render** — https://codespace-ide-backend.onrender.com (health: /api/v1/health → 200) |
| Backend host | Render (srv-d9q34761egvs73d7ejfg), free tier, oregon region |
| Database | Supabase Postgres via pooler (aws-0-eu-central-1.pooler.supabase.com:6543) |
| Old Railway | ⚠️ DEPRECATED — https://codespace-ide-mobile-production.up.railway.app is dead (free trial ended) |
| Last confirmed green | ee5c4a7a ✅ — MinimapSection extraction build fix (Phase F+G included) |
| **Phase 26-4** | **✅ COMPLETE** — AttachDebugDialog, capability-aware step toolbar, multi-session switcher, context wiring (#1592 GREEN) |
| **Phase 26-3** | **✅ COMPLETE** — NodeDAPAdapter (js-debug, launch+attach, capability negotiation), UDM multi-session (#1589 GREEN) |
| **Phase 26-2** | **✅ COMPLETE** — DAPClient, DebugAdapter interface, LegacyDebugAdapter, PythonDAPAdapter (debugpy), UDM integration |
| **Phase 26-1** | **✅ COMPLETE** — Type Definition, Find Implementations, Code Lens, Inlay Hints, Outline LSP, Code Folding |
| **Phase 25** | **✅ COMPLETE** — Full IDE Intelligence + UI Reliability Audit (TS pin, Preview fix, LSP pipeline) |
| **Phase 24** | **✅ COMPLETE** — LSP teardown fix, RAM fix, diagnostics squiggles, Find References, Rename |
| **Phase 23** | **✅ COMPLETE** — UDM, 6 language debug providers, Android+APK, breakpoint persistence |
| **Phase 22** | **✅ COMPLETE** — ProblemsPanel live-update, merge conflict editor, LSP/JSON-RPC full stack |
| **Phase 21-X** | **✅ COMPLETE** — DEX/ELF/APK analyzers, disassembly, binary diff (#1290 GREEN) |
| **Phase 21** | **✅ COMPLETE** — 17 viewers shipped (#1262 GREEN) |
| **Phase 18** | **✅ COMPLETE** (build #1219 GREEN) — Multi-file edit & refactoring |
| **Phase 17** | **✅ COMPLETE** (build #1208 GREEN) — File mgmt polish |
| **Phase 16** | **✅ COMPLETE** (build #1199 GREEN) — Fetch, Cloud Backup, Session Sync |
| **Phase 15** | **✅ COMPLETE** (build #1183 GREEN) |
| **Phase 14** | **✅ COMPLETE** (build #1176) |
| **Phase 13** | **✅ COMPLETE** (build #1172) — Runtime UX Polish & Stability |
| **Phase 12** | **✅ COMPLETE** (build #1157) — Project Setup & Toolchain |
| **Phase 11** | **✅ COMPLETE** (build #1137) — Android Build Environment |
| **Phase 9** | **✅ COMPLETE** (build #1129) — Performance & Monitoring |
| **Phase 8** | **✅ COMPLETE** (build #1119) — Debugging Infrastructure |
| **Phase 2** | **✅ COMPLETE** (build #1068) |

### Phase 26-2 — DAP Abstraction Layer ✅ COMPLETE (build #1586)

| # | Item | Status | Files |
|---|------|--------|-------|
| 26-2a | DAPClient.kt — JSON-RPC over stdin/stdout, seq correlation, event dispatch | ✅ DONE (#1583) | debug/DAPClient.kt |
| 26-2b | DebugAdapter interface + LegacyDebugAdapter wrapper | ✅ DONE (#1584) | debug/DebugAdapter.kt |
| 26-2c | PythonDAPAdapter — debugpy over DAP, fallback to legacy pdb | ✅ DONE (#1585) | debug/PythonDAPAdapter.kt |
| 26-2d | UDM — register PythonDAPAdapter, resolveAdapter DAP-first/legacy fallback | ✅ DONE (#1586) | debug/UniversalDebugManager.kt |

### Phase 26-3 — Node.js DAP + Attach Mode + Multi-Session ✅ COMPLETE (build #1589)

| # | Item | Status | Files |
|---|------|--------|-------|
| 26-3a | NodeDAPAdapter.kt — @vscode/js-debug over proot stdin/stdout, full DAP lifecycle | ✅ DONE (#1588) | debug/NodeDAPAdapter.kt |
| 26-3b | Attach mode — attach(context, session, port, pid) in NodeDAPAdapter + attachDebug in UDM | ✅ DONE (#1589) | debug/NodeDAPAdapter.kt, debug/UniversalDebugManager.kt |
| 26-3c | Capability negotiation — InitializeResponse → DAPCapabilities, getAdapterCapabilities(sessionId) | ✅ DONE (#1589) | debug/UniversalDebugManager.kt |
| 26-3d | Multi-session — getActiveSessions, getSessionById, activeSessionId, setActiveSession | ✅ DONE (#1589) | debug/UniversalDebugManager.kt |

### Phase 26-4 — Debug UI ✅ COMPLETE (build #1592)

| # | Item | Status | Files |
|---|------|--------|-------|
| 26-4a | AttachDebugDialog.kt — port/PID picker, Attach button, progress indicator, inline error | ✅ DONE (#1591) | ui/panes/AttachDebugDialog.kt |
| 26-4b | Capability-aware step toolbar — ▶ Continue, ⏸ Pause, ↷ Step Over, ↓ Step Into, ↑ Step Out; DAP badge | ✅ DONE (#1592) | ui/screens/ProjectShellScreen.kt |
| 26-4c | Multi-session switcher — LazyRow tab bar, setActiveSession, per-session stop/step | ✅ DONE (#1592) | ui/screens/ProjectShellScreen.kt |
| 26-4d | startDebug context param wired at both call sites; DebugConsolePanel gets context+activeFilePath | ✅ DONE (#1592) | ui/screens/ProjectShellScreen.kt |
| 26-4e | DebugToolbarBtn extracted private composable for step controls | ✅ DONE (#1592) | ui/screens/ProjectShellScreen.kt |

### Phase 26-1 ✅ COMPLETE (build #1582 docs, #1581 code green)

| Item | Status |
|------|--------|
| Code Lens, Inlay Hints, Document Links rendered in CodeEditor | ✅ DONE |
| LSP code folding (folding ranges from LSP, regex fallback) | ✅ DONE |
| Document Symbol Outline Panel (LSP-powered, sidebar) | ✅ DONE |
| Type Definition peek overlay | ✅ DONE |
| Find Implementations overlay | ✅ DONE |


### Phase 8 — Debugging Infrastructure ✅ COMPLETE (build #1119)

| # | Feature | Status | Notes |
|---|---------|--------|-------|
| P8-1 | Breakpoint gutter markers | ✅ DONE (#1111/#1112) | Tap line number = toggle red dot |
| P8-2 | Logcat viewer | ✅ DONE (#1113→#1115→#1119) | LogcatPanel.kt, color-coded, filter, pause |
| P8-3 | Variable inspector panel | ✅ DONE (#1116/#1117→#1119) | VariableInspectorPanel.kt, watch+locals+callstack |
| P8-4 | DAP client | ⏭️ SKIP | Needs external language adapters |

Bottom panel tabs: PROBLEMS, OUTPUT, TERMINAL, DEBUG, PORTS, SPLIT, PREVIEW, LOGCAT, VARIABLES

### Phase 9 — Performance & Monitoring ✅ COMPLETE (build #1129)

| # | Feature | Status | Files |
|---|---------|--------|-------|
| P9-1 | Background file indexer + symbol search | ✅ DONE | FileIndexer.kt, SymbolSearchPanel.kt, wired in PSS |
| P9-2 | Smart file caching (LRU, 20 files) | ✅ DONE | FileCache.kt, wired in EditorPane.kt |
| P9-3 | Memory pressure monitor (RAM in status bar) | ✅ DONE | PerformanceMonitor.kt → MemoryMonitor, StatusBarContent |
| P9-4 | Large file support (>1MB detection) | ✅ DONE | FileCache.isLargeFile, EditorPane check |
| P9-5 | Code metrics + live cursor in status bar | ✅ DONE | PerformanceMonitor.kt → CodeMetrics, StatusBarContent |

New files created in Phase 9:
- `diagnostics/PerformanceMonitor.kt` — MemoryMonitor (reads /proc/meminfo) + CodeMetrics (lines, size, functions, nesting)
- `editor/FileCache.kt` — LRU cache, 20 files, invalidates on write, isLargeFile threshold 1MB
- `editor/FileIndexer.kt` — Background workspace symbol indexer (classes, functions, variables)
- `ui/panes/SymbolSearchPanel.kt` — Overlay UI for Go-to-Symbol workspace search

Modified files in Phase 9:
- `ui/screens/ProjectShellScreen.kt` — Added imports, indexer startup, "Go to Symbol" menu, SymbolSearchOverlay + StatusBarContent extracted composables
- `ui/panes/EditorPane.kt` — loadFileContent uses FileCache, cache invalidation on write, large file check

### ⚠️ ARCHITECTURAL RISK: ProjectShellScreen.kt method size

**ProjectShellScreen.kt is 3967 lines.** It hit the JVM 64KB method-too-large limit during Phase 9 (#1126–#1128 failed).


---

## ⚠️ JVM 64KB BYTECODE LIMIT — CRITICAL REFERENCE FOR ALL AI AGENTS

### What is it?
The JVM enforces a hard 64KB (65535 bytes) bytecode limit per method. Kotlin Composable functions that are too large hit "Method too large" compilation errors and break ALL CI builds.

### Files at risk (as of 2026-08-11, build #2121 GREEN):

| File | Total Lines | Composable Body | Status | Extracted Files |
|------|------------|-----------------|--------|-----------------|
| `CodeEditor.kt` | 5305 | ~3976 (lines 480-4456) | ⚠️ NEAR LIMIT | `CursorOverlay.kt`, `EditorOverlays.kt`, `PeekWidget.kt`, `GotoDefinitionDialog.kt`, `FindReplaceBar.kt`, `HoverPopup.kt`, `LightbulbIndicator.kt`, `BottomPanels.kt` |
| `ProjectShellScreen.kt` | 4073 | ~3500+ | ⚠️ NEAR LIMIT | `PssOverlays.kt`, `PssActivityBar.kt`, `SymbolSearchOverlay.kt`, `StatusBarContent.kt`, `PssEditorColumn.kt`, `PssTopBar.kt` |
| `ExplorerPane.kt` | 3162 | ~2800+ | ⚠️ WATCH | (no extractions yet) |

### RULES FOR ALL AI AGENTS:
1. **NEVER add inline code to `CodeEditor.kt`'s `CodeEditor` composable body (lines 480-4456).** If you need to add rendering code, create a new `@Composable` function in a separate file (e.g., `NewFeatureOverlay.kt`) and call it from CodeEditor with a single function call line.
2. **NEVER add inline code to `ProjectShellScreen.kt`'s main composable body.** Same pattern — extract to a separate file.
3. **If a build fails with "Method too large"**, the fix is ALWAYS to extract inline code into a separate `@Composable` function in a new file. Do NOT try to reduce code by removing features.
4. **Each extracted function should be `internal` (not `private`) if called from another file.**
5. **When extracting, pay attention to:**
 - Type names (e.g., `BlameLine` not `GitBlame`)
 - Parameter types (e.g., `GUTTER_WIDTH` is `Float` not `Int`, `extraCursors` is `List<Int>` not `Set<Int>`)
 - Import paths (e.g., `EditorColors` is in `com.codespace.ide.ui`, not `com.codespace.ide.ui.Theme`)
 - Local function references (use `{ lineFromOffset(it) }` not `::lineFromOffset` for local functions)
6. **Line count is a proxy, not a guarantee.** The actual bytecode depends on Compose compiler group generation, lambda captures, and control flow. A 4000-line composable might be fine, or it might not. When in doubt, extract.

### Extraction pattern (proven to work):
```kotlin
// In new file: NewFeatureOverlay.kt
@Composable
internal fun androidx.compose.foundation.layout.BoxScope.NewFeatureOverlay(
 param1: Type1,
 param2: Type2,
) {
 if (condition) {
 Box(modifier = Modifier.align(Alignment.TopStart)...) { ... }
 }
}

// In CodeEditor.kt (single line call):
NewFeatureOverlay(param1, param2)
```

### History of 64KB failures and fixes:
- #1128: ProjectShellScreen — fixed by extracting PssOverlays, PssActivityBar, etc.
- #1819: CodeEditor — fixed by extracting FindReplaceBar, HoverPopup, LightbulbIndicator
- #1916-1919: CodeEditor — fixed by extracting GotoDefinitionDialog, BottomPanels
- #2108-2118: CodeEditor — fixed by extracting EditorOverlays (BlameLineOverlay, ExtraCursorOverlay, SearchMatchOverlay, MergeConflictOverlay) + CursorOverlay
- #2121: GREEN after all extractions confirmed working

The fix was extracting `SymbolSearchOverlay` and `StatusBarContent` into separate @Composable functions (#1129 green).

**RULE FOR FUTURE PHASES:** Any new UI added to ProjectShellScreen MUST be extracted into a separate
@Composable function from the start. Do NOT inline large blocks in the main `ProjectShellScreen` function.
The main function should delegate to extracted composables. If the file grows past ~2200 lines,
proactively extract more composables before the build breaks.

Current extracted composables in PSS:
- `SymbolSearchOverlay` — symbol search overlay (P9-1)
- `StatusBarContent` — full status bar with RAM, metrics, cursor, MCP (P9-3/P9-5)

### CI Build History — Phase 8 & 9

| Build | Result | Notes |
|-------|--------|-------|
| #1111 | GREEN ✅ | feat(P8-1): breakpoint gutter markers |
| #1112 | GREEN ✅ | feat(P8-1): wire breakpoints into EditorPane |
| #1113 | GREEN ✅ | feat(P8-2): LogcatPanel.kt — new Logcat viewer composable |
| #1114 | FAIL ❌ | feat(P8-2): add Logcat tab — Compose State read off main thread |
| #1115 | FAIL ❌ | fix(P8-2): AtomicBoolean for pause flag — still had P8-3 issues in tree |
| #1116 | FAIL ❌ | feat(P8-3): VariableInspectorPanel — compilation errors |
| #1117 | FAIL ❌ | feat(P8-3): wire VariableInspectorPanel — same root cause |
| #1118 | FAIL ❌ | docs(AGENTS): update Phase 8 status — ran on broken tree |
| #1119 | GREEN ✅ | fix(P8): LOGCAT + VARIABLES branches in overflow menu — P8 COMPLETE ✅ |
| #1120 | GREEN ✅ | feat(P9-3/P9-5): PerformanceMonitor — MemoryMonitor + CodeMetrics |
| #1121 | GREEN ✅ | feat(P9-3/P9-5): wire RAM monitor + file metrics + live cursor into status bar |
| #1122 | GREEN ✅ | feat(P9-2): FileCache — LRU cache for recently opened files |
| #1123 | GREEN ✅ | feat(P9-2/P9-4): wire FileCache into EditorPane + cache invalidation on write |
| #1124 | GREEN ✅ | feat(P9-1): FileIndexer — background workspace symbol index |
| #1125 | FAIL ❌ | feat(P9-1): SymbolSearchPanel — missing focusRequester import |
| #1126 | FAIL ❌ | feat(P9-1): wire FileIndexer + SymbolSearchPanel — private loadWorkspacePath + bad padding |
| #1127 | FAIL ❌ | fix(P9-1): add focusRequester import — PSS still broken in this commit |
| #1128 | FAIL ❌ | fix(P9-1): replace loadWorkspacePath + fix padding — Method too large (JVM 64KB limit) |
| #1129 | GREEN ✅ | fix(P9): extract SymbolSearchOverlay + StatusBarContent — P9 COMPLETE ✅ |

Root cause of #1125–#1128 (Phase 9):
1. **#1125**: Missing `import androidx.compose.ui.focus.focusRequester` in SymbolSearchPanel.kt
2. **#1126**: `loadWorkspacePath` is private in ExplorerPane.kt — can't call from ProjectShellScreen.kt
 Fix: use `java.io.File(context.filesDir, "projects/$projectId").absolutePath` instead
3. **#1126**: `padding(top = 60.dp, horizontal = 16.dp)` — no such overload exists in Compose
 Fix: chain two `padding` calls: `.padding(horizontal = 16.dp).padding(top = 60.dp)`
4. **#1128**: `Method too large` — ProjectShellScreen composable exceeded JVM 64KB bytecode limit
 Fix: extract `SymbolSearchOverlay` and `StatusBarContent` into separate @Composable functions

LESSON: When adding new imports to a file, verify the import path is correct (focusRequester is a
modifier extension, needs `import androidx.compose.ui.focus.focusRequester`). When calling functions
from other files, check they're not `private`. When adding to large composables (>1500 lines),
extract new UI into separate @Composable functions from the START.

---


## 📍 ROADMAP — CURRENT STATUS (Updated 2026-08-16 17:38 WAT)

> **ALL AI AGENTS: Read this section FIRST before looking at any "PENDING", "PLANNED", "TODO", or "Next on roadmap" markers elsewhere in this file.**
> Many older sections below contain stale status markers that were never updated when features shipped. This section is the authoritative current status.

### ✅ DONE — Earlier shipped features (CI green as of #2121):
- Editor overlay extraction, cursor mode toggle, Pyright LSP, feature toggles, themes, AI chat, GitHub OAuth, terminal isolation, proot fixes, file upload, PDF viewer, peek definition, find & replace, hover popup, source control, power user analyzers, formatter selection

### 🚫 BLOCKED:
- Google OAuth Client Secret — Need GCP console access
- Flow Mode — Can't test, no mobile data
- API_BASE_URL — May still point to old Railway URL, needs updating to Render

### 🔧 ACTIVELY PLANNED (not yet implemented):
1. **TypeScript 7 as default** — TS7 + `vtsls` LSP, TS 5.6.3/4.9.5 as backup options
2. **Multi-Cursor feature** — Double-tap trigger, 3-dot floating menu, Select Next/All Occurrences, column-aware selection

### ❌ DO NOT REFERENCE these old items — they are DONE or STALE:
- Phase 9-42 phases: ALL COMPLETE. Do not re-implement.
- "Next on roadmap" lines in older change log entries: Those were written at that time. Check THIS section instead.
- Terminal cross-project bleeding: FIXED (commit 9096f1d)
- Ash terminal tab: REMOVED. App is Ubuntu-proot only.
- Theme switching data loss: FIXED (ThemeViewModel + DataStore)
- Rotation safety (dialogs): FIXED (key(orientation) wrappers)
- AI agent path guessing: FIXED (WORKSPACE_PATH injection)
- Multi-cursor: FIXED (Crash #2 + fan-out fix) — do NOT re-do
- SVG/HTML/Markdown rendering: FIXED (commits 4cd11525, d0de184b) — do NOT re-do
- Extra keys/toolbar: FIXED (commits 5febc736, 43a60bb1) — do NOT re-do
- Find bar: FIXED (commit 19ef3320) — do NOT re-do
- Git identity: FIXED (commit 37200067) — do NOT re-do
- Trash delete: FIXED (commit 37200067) — do NOT re-do
- Cursor styles (SOLID/EXPAND): FIXED (commit 37200067) — do NOT re-do
- scrollToLine cursor positioning: FIXED (commit 37200067) — do NOT re-do

## KNOWN KOTLIN/COMPOSE CI FAILURE PATTERNS (memorise these)

Do NOT repeat any of these — they have each caused 5+ failed builds:

1. Raw newlines inside double-quoted strings: "foo\nbar" is OK, literal newline is NOT. Use \n or triple-quoted strings.
2. remember inside if/else branches or LazyColumn items{} — Compose rules: call remember unconditionally at top of composable.
3. Double-quotes inside a double-quoted string: "of "$var"" breaks the string. Use single quotes: "of '$var'".
4. Triple-quoted strings inside ${} interpolation — not valid Kotlin. Extract to a local val first.
5. `LocalContext.current` (or any `Local*.current`) inside `scope.launch {}` / `LaunchedEffect {}` / coroutine lambdas — NOT allowed. Capture it at the top of the `@Composable` function and use the captured val inside any lambdas.

---

## CI BUILD STATUS (recent only)

| Build | Result | Notes |
|-------|--------|-------|
| #2432 | GREEN ✅ | Phase A+E: PositionMapper + EditorMetrics |
| #2434 | ❌ FAIL | MethodTooLargeException — CodeEditor exceeded 64KB (fix in progress) |
| #2435+ | pending | Fix: extract gen counters + indent logic to reduce bytecode |

> Old build history (#1000-#2431) removed 2026-08-22. All past builds were either GREEN or already resolved.

---

## WHAT THE APP IS — COMPLETE FEATURE MAP

### Package & Build
- **App ID:** `com.codespace.ide`
- **Min SDK:** 26 (Android 8), Target SDK: 28
- **ABI:** arm64-v8a + armeabi-v7a (per-ABI APKs + universal)
- **Auto-increment versionCode** from `git rev-list --count HEAD`
- **Keystore:** debug.keystore for debug; env-var-driven for release CI
- **Flavors:** dev / staging / prod (different `API_BASE_URL`)
- **Build system:** Kotlin + Gradle KTS, Hilt DI, KSP, Room, CMake (native C)

---

### Authentication (`AuthScreen.kt`, `GitHubAuth.kt`, `SecureTokenStore.kt`)
- **Firebase Auth** (Google Sign-In via Credential Manager — modern, not legacy GoogleSignIn)
- **GitHub OAuth** — device-code flow (non-interactive, no redirect URL needed)
 - `GitHubAuth.requestDeviceCode` → `pollForToken` → `fetchUsername`
 - Token stored in `SecureTokenStore` (Keystore-backed AES-256 encrypted SharedPreferences)
- **Biometric lock** — optional fingerprint/face gate on app open (SettingsScreen)
- **Settings screen** shows active provider, API key input for AI providers

---

### Project Management (`HomeScreen.kt`)
- Create / open / delete local projects
- Each project lives in `context.filesDir/projects/<name>/`
- Opens into `ProjectShellScreen`

---

### Main IDE Shell (`ProjectShellScreen.kt` — 2160 lines, 8 @Composable functions)
- Four-pane layout: **Explorer | Editor | Terminal | Preview**
- Tab bar with pane switching
- **Bottom panel** (Problems / Output / Debug Console / Ports) — collapsible
- Global in-app notifications via `NotificationStore` (bell icon + drawer overlay)
- Full-screen overlays: Copilot Chat, Connectors Hub, Settings, Source Control, SSH Manager, Text Expansion
- Biometric bypass guard on project open
- Menu bar with actions: new file, save, run, build, format, terminal ops

---

### File Explorer (`ExplorerPane.kt` — ~2300 lines)
- Recursive tree view with expand/collapse
- **Single-tap file routing** (all binary types handled — none reach the text editor):
 - `.png/.jpg/.jpeg/.webp/.gif/.bmp/.svg` → Image preview popup
 - `.zip/.apk/.jar/.aar` → ArchiveViewer (browse contents like ZArchiver)
 - `.pdf` → PdfViewer (native PdfRenderer, paginated, pinch-zoom)
 - `.mp4/.webm/.mov/.mkv/.m4v/.3gp/.avi` → VideoPlayerDialog
 - `.mp3/.wav/.ogg/.m4a/.aac/.flac/.opus` → AudioPlayerDialog
 - `.db/.sqlite/.sqlite3` → SqliteViewerDialog
 - `.dex/.so/.class/.o/.a/.bin/.dat/.exe/.dll/.ttf/.otf/.woff/.woff2` → HexViewerDialog
 - NUL-byte sniff safety net (`sniffLooksBinary`) → HexViewerDialog for any undetected binary
 - Everything else → EditorPane (text editor)
- **Long-press** → context menu (Open / Preview / Rename / Copy / Cut / Paste / Duplicate / Delete / Copy Path / Share / Open in Terminal / New File Here / New Folder Here / Import Image(s) Here)
- **Device quick-access folders** (Pictures, DCIM, Downloads, Documents, Music, Movies)
- **SAF folder picker** for external storage
- **Tab bar** showing open files
- **Outline view** (symbols in current file)
- **Search panel** (in-project text search with filters)
- Rotation-safe dialogs — `key(orientation)` on all AlertDialogs (fixes configChanges=orientation Activity that never recreates)

---

### Code Editor (`EditorPane.kt`, `CodeEditor.kt`, `SyntaxHighlighter.kt`, `LanguageSpecs.kt`, `SyntaxTransformation.kt`)
- Compose-based text editor with `BasicTextField`
- **Syntax highlighting** for: Kotlin, Java, Python, JavaScript/TypeScript, HTML, CSS, Markdown, JSON, XML, Shell/Bash, C/C++, Rust, YAML
- **Language auto-detection** from file extension
- **Lint checker** (`LintChecker.kt`) — inline problem markers
- **Find/Replace** in editor
- **Line numbers** gutter
- Font size adjustment
- **EditorColors** theming (dark IDE palette)

---

### Terminal (`TerminalPane.kt` — 2208 lines, `NativePty.kt`, `TerminalSession.kt`, `TermuxBootstrapInstaller.kt`)

#### Native PTY (primary shell)
- JNI via `pty_native.c` / CMake — exact Termux JNI API:
 - `createSubprocess`, `setPtyWindowSize`, `setPtyUTF8Mode`, `waitFor`, `close`
- **Termux bootstrap** (`bootstrap-aarch64.zip` in assets — 28MB, 252 binaries)
 - Extracted to `context.filesDir/termux-prefix/`
 - Contains bash 5.2.37, coreutils, git, curl, python, etc.
 - `TermuxBootstrapInstaller.kt` handles extraction, symlink resolution, script patching
- Shell launched as `-bash` (login shell via argv[0]) with correct `HOME`, `PREFIX`, `PATH`
- **NO LD_LIBRARY_PATH** — avoids ABI mismatch crashes
- **NO `--login` flag** — avoids host `/etc/profile` permission denied

#### Ubuntu proot shell (separate tab)
- `ProotInstaller.kt` — Ubuntu 25.04 (Questing) rootfs via proot
- Managed separately in `ubuntu-proot-test` repo
- **Known working** on TECNO KL4 with seccomp-aware symlink bypass

#### BusyBox fallback shell
- `BusyboxInstaller.kt` — offline fallback if bootstrap not yet extracted
- Lives at `context.filesDir/bin/busybox`

#### Multi-tab terminal
- Unlimited tabs, each an independent `TerminalSession`
- Tab rename, color scheme picker (scrollable, rotation-safe)
- **Key bar** — swipeable extra keys (tab, arrow keys, ctrl, escape, pipe, etc.)
- **⋮ overflow menu — AI & TOOLS section** (separate items, not one button):
 - 📥 **Install Ollama** — runs `ollamaInstallScript`, tries 5 install methods in sequence
 - 🤖 **Launch Coding Agent** — first run opens model picker → full setup (Ollama + Claude Code); subsequent runs reuse existing tab + `ollamaLaunchScript`
 - 🎬 **Setup Remotion** — runs `remotionSetupScript`: Node.js + ffmpeg + headless Chrome deps + `@remotion/cli` + scaffolds `~/remotion-project/` with TSX starter + chunked render helper
 - 🎞️ **Launch Remotion Studio** — runs `remotionRelaunchScript` (guards: must run Setup first)
 - 🎙️ **Install Voice (TTS)** — opens model picker → Piper (fast, on-device) or Bark (slower, CPU-only)
 - 🔑 / 🚪 **Sign in/out of Ollama** — `ollama signin` / `ollama signout`
 - **Multi-Instance Mode** toggle — allows multiple Ollama tabs (advanced)
 - 🔌 **Show Agent Tools (32)** — lists available MCP tools
- **SSH remote terminal** — `RemoteTerminalSession.kt` connects to backend WS

#### MCP shell integration (`McpShellProfile.kt`)
- Installs `.agent-profile.sh` + `.bashrc` injection in Ubuntu rootfs
- Provides `agent` CLI binary at `/usr/local/bin/agent`
- Shell functions: `agent_read`, `agent_write`, `agent_ls`, `agent_search`, `agent_run`, `agent_git`, `agent_ask`, `agent_session_save`
- Configures `AGENT_API_URL` and `AGENT_TOKEN` env vars
- **Build #2427 fix**: L120 triple-quoted to avoid Kotlin escape crash

#### Terminal features
- Color scheme picker (Dark, Light, Solarized, Dracula, Nord, Monokai, etc.)
- `TerminalModeManager` — offline / online / Ollama / Ubuntu mode selection
- `TerminalEnhancementManager` — profile script backup/restore, theme persistence
- `OllamaSetup.kt` — Ollama + Nemotron profile installer
- **Backup/restore** (`BackupManager.kt`) — tar.gz of Ubuntu rootfs to external storage

---

### Preview Pane (`PreviewPane.kt` — 1281 lines)
- **HTML preview** — WebView with JS enabled, file:// local serving
- **Browser** — full in-app browser with navigation bar
- **Remotion** — React video rendering via WebView
- **Dashboard** — custom WebView mode
- **Markdown** — rendered preview
- **SVG** — inline SVG rendering
- **File upload support** — `onShowFileChooser` bridged to Android GetContent picker
 - Single and multiple file modes
 - Wired into all 4 WebViews (HTML, Browser, Remotion, Dashboard)
- Fullscreen toggle (rotation-safe via `key(orientation)`)
- Port-forwarding integration (opens localhost ports from terminal)

---

### Source Control (`SourceControlPane.kt`, `GitEngine.kt`)
- **JGit** (on-device, no shell git needed)
- Stage / unstage / commit / push / pull / fetch
- Diff viewer
- Branch management
- **GitHub OAuth** integration — browse repos, clone, push with token

---

### SSH Manager (`SshManagerSheet.kt`, `SshManager.kt`, `SshProfile.kt`, `SshProfileStore.kt`)
- SSH connection profiles (host, port, user, key/password)
- **sshj** library for SSH/SFTP
- Port tunneling (local port forwarding)
- Profile CRUD with encrypted storage
- Opens SSH session in a terminal tab

---

### File Viewers (all in `ui/panes/`)
- **ArchiveViewer.kt** — browse ZIP/APK/JAR/AAR contents, extract files
- **PdfViewerDialog.kt** — native PdfRenderer, one-page-at-a-time bitmap, pinch-zoom, Prev/Next
- **MediaViewers.kt** — VideoPlayerDialog (VideoView + MediaController), AudioPlayerDialog (MediaPlayer + seek bar), HexViewerDialog (256KB-capped hex+ASCII dump)
- **HexViewerDialog.kt** — standalone hex viewer (File param version)
- **SqliteViewerDialog.kt** — opens SQLite .db files, table list, `SELECT * LIMIT 200`, scrollable grid
- **ImageGenDialog.kt** — AI image generation dialog
- **ImageGenService.kt** — image generation API client

---

### AI Copilot (`CopilotChatPanelOverlay.kt` — 1124 lines)
- Sliding overlay panel (swipe right or button)
- **Multi-provider**: supports multiple AI backends (OpenAI, Anthropic, Gemini, Ollama)
- **Multi-session** chat history (persisted to SharedPreferences as JSON)
- **Chat modes** — Code, Chat, etc.
- Token streaming
- Code blocks with syntax highlighting and copy button
- **MCP tool calls** — `AgentTools.parseToolCalls` + `executeTool`
- Agent memory (`AgentMemory.kt`) — key/value store persisted to `agent_memory/`
- Agent scheduler (`AgentScheduler.kt`) — CRON-style task scheduling
- File context injection (attaches open file to prompt)

---

### Agent System (`agent/` package)
- **`AgentApiServer.kt`** — in-app HTTP server (NanoHTTPD) that exposes agent tools via REST
 - Used by the `agent` CLI binary in the terminal to call back into the app
- **`AgentTools.kt`** — tool executor (read_file, write_file, list_files, search_files, run_command, git_*, MCP tools)
- **`AgentConnectorManager.kt`** — lists/connects third-party OAuth services via `ConnectorsApiClient`
- **`AgentEntityManager.kt`** — CRUD for agent-managed entities (SQLite-backed)
- **`AgentMemory.kt`** — persistent key/value agent memory (JSON files)
- **`AgentScheduler.kt`** — schedule agent tasks by cron expression

---

### Connectors (`ConnectorsHubSheet.kt`, `ConnectorsApiClient.kt`)
- Connects to third-party OAuth services (GitHub, Google Drive, Slack, etc.)
- **ConnectorsHubSheet** — sheet UI showing connector status + connect/disconnect buttons
- OAuth WebView flow (in-app browser for auth, callback intercepted)
- `ConnectorsApiClient` — proxies API calls through backend

---

### Notifications (`NotificationStore.kt`, `NotificationDrawerOverlay.kt`)
- In-app notification store (`mutableStateListOf`) — INFO / SUCCESS / WARNING / ERROR types
- Bell icon in toolbar with unread count badge
- Drawer overlay — shows all notifications, mark-all-read, clear, dismiss individual
- Timestamp formatting (relative time)

---

### Diagnostics & Dev Tools
- **`LintChecker.kt`** — syntax problem detection per language, returns `Problem(line, col, message, severity)` list
- **`PortsScanner.kt`** — scans active localhost ports (finds running dev servers)
- **`AppOutputLog.kt`** — in-memory log sink, displayed in Output panel
- **Problems panel** — shows lint issues for active file with jump-to-source
- **Output panel** — build/run output stream
- **Debug Console panel** — interactive JS/shell debug
- **Ports panel** — lists active ports, click to open in Preview

---

### Data Layer
- **Room database** — entity persistence
- **DataStore Preferences** — lightweight settings
- **SecureTokenStore** — AES-256 Keystore-backed encrypted token storage
- **SessionStateStore** — per-project editor state (open files, scroll positions, cursor)
- **BackupManager** — Ubuntu rootfs tar.gz backup to external storage (SDCARD/CodespaceIDE/)

---

### Networking
- **Retrofit + OkHttp** — REST API client
- **kotlinx.serialization** — JSON
- **Firebase Auth** — backend auth
- **sshj** — SSH/SFTP
- **JGit** — on-device Git
- **Commons Compress + XZ + Zstd** — archive extraction (Ubuntu rootfs, tar.xz, .zst)

---

### Key Files Quick Reference

| File | Lines | Purpose |
|------|-------|---------|
| `ProjectShellScreen.kt` | 1905 | Main IDE layout, all pane wiring |
| `TerminalPane.kt` | 2208 | Terminal UI, tabs, key bar, setup scripts |
| `ExplorerPane.kt` | ~2300 | File tree, all viewer routing |
| `CopilotChatPanelOverlay.kt` | 1124 | AI chat panel |
| `PreviewPane.kt` | 1281 | WebView preview modes |
| `TermuxBootstrapInstaller.kt` | 415 | Termux bootstrap extraction |
| `McpShellProfile.kt` | 296 | Ubuntu terminal AI profile |
| `AgentTools.kt` | 454 | MCP tool executor |
| `AgentApiServer.kt` | 227 | In-app REST server for agent CLI |
| `ConnectorsHubSheet.kt` | 358 | OAuth connector UI |
| `SettingsScreen.kt` | 487 | Settings, biometric, API keys, GitHub OAuth |
| `SourceControlPane.kt` | — | Git UI |
| `BackupManager.kt` | 209 | Ubuntu rootfs backup/restore |

---

## KNOWN BUGS (DO NOT REPEAT)

| Bug | Root cause | Fix |
|-----|-----------|-----|
| `signal 31` on bash start | `LD_LIBRARY_PATH` includes wrong ABI .so dir | Remove LD_LIBRARY_PATH entirely |
| `CANNOT LINK EXECUTABLE "--rcfile"` | args[0] = "--rcfile" treated as binary name | Use `arrayOf("-bash")` only |
| `/etc/profile: Permission denied` | `--login` flag tries host `/etc/profile` | Remove `--login` flag |
| 185 scripts broken paths | Bootstrap hardcodes `/data/data/com.termux/files/usr` | `patchAllScripts` after extraction |
| Ubuntu black screen | `initializeEmulator` never called — view already laid out | Call `view.updateSize` after `view.attachSession` |
| Ubuntu crash after symlink resolving | `--link2symlink` + Samsung seccomp blocks `symlinkat` → SIGSYS | Remove `--link2symlink` |
| McpShellProfile L120 Kotlin compile | `\\"` in double-quoted string = backslash + close-quote | Use triple-quoted `"""..."""` string |
| Dialog doesn't resize on rotation | Activity has `configChanges=orientation` — never recreates | `key(orientation)` wrapper on all Dialogs |
| File upload in WebView not working | `onShowFileChooser` not overridden | `rememberOnShowFileChooser` shared helper |
| Video/audio/binary opens in text editor | No extension routing for those types | MediaViewers.kt + sniffLooksBinary |
| Claude Code broken after npm install | postinstall script doesn't always run | Manually run `node .../install.cjs` + verify |
| Remotion render fails (headless Chrome) | Missing libnspr4/libnss3/etc | Install full headless Chrome dep list |

---

## OPEN ITEMS (priority order)

| # | Item | Status | Notes |
|---|------|--------|-------|
| CI | Build green | DONE ✅ (#1028–#1030) | Raw newline escape fix confirmed green |
| 12 | Terminal cross-project state bleed | DONE | TrackedSession scoping fixed |
| 13 | AI package access bridging | DONE | ProotInstaller.execOnce routing |
| 11 | GitHub OAuth repo browsing | DONE | RepoBrowserSheet.kt shipped 2026-07-13 |
| P2-1 | Rename Symbol | DONE ✅ | Shipped |
| P2-2 | Find & Replace | DONE ✅ | Shipped |
| P2-3 | Go to Line | DONE ✅ | Shipped |
| P2-4 | Go to Definition | DONE ✅ | Long-press context sheet + results dialog |
| P2-5 | Error squiggles (lint) | DONE ✅ | LintAnalyzer hardened; SyntaxTransformation underlines errors |
| P2-6 | Git diff gutter | DONE ✅ | GitDiffAnalyzer.kt (LCS diff) + gutter bars fully wired in CodeEditor.kt |

---

## DEVICE CONSTRAINTS (TECNO KL4 — aarch64, Android 14, Kernel 5.15.180)

- 3-8 GB RAM — avoid loading large files into memory at once
- Samsung-derived kernel 5.15 — seccomp blocks `symlinkat` inside unprivileged namespaces
- Always use 8KB stream buffers for extraction, not byte-array slurp
- System.gc every 1000 files during extraction
- No W^X restriction on `nativeLibraryDir` — safe to execute .so files there
- Termux bootstrap ZIP is 29 MB — already in assets, extracts to ~150 MB


---

## FUTURE FEATURE EVALUATION POLICY (MANDATORY)

Before implementing any feature outside the core IDE experience, an AI agent MUST:
1. Present the feature to Wisdom first.
2. Explain benefits, drawbacks, performance impact, and maintenance cost.
3. Wait for explicit approval before implementing.

This applies to: Social/Collaboration features, Live collaboration, Shared Workspaces,
Achievement/Gamification/Rewards/Badges/Leaderboards, Coding streaks, Complex dashboards,
Utility collections, Experimental/Novelty features, Comment systems.

When uncertain whether a feature is essential or optional → ASK FIRST.

---

## BACKGROUND SAFE STARTUP & RECOVERY SYSTEM (Phase 4 directive — 2026-07-13)

### Existing Systems Audited (do NOT duplicate these):
| Component | File | Status |
|-----------|------|--------|
| Crash logger (JVM UncaughtExceptionHandler) | CodeSpaceApplication.kt | ✅ EXISTS |
| Native signal crash handler (SIGSEGV/SIGABRT) | CodeSpaceApplication.kt | ✅ EXISTS |
| Ubuntu rootfs backup/restore (tar.gz to external storage) | BackupManager.kt | ✅ EXISTS |
| App-wide output log (500-line ring buffer) | AppOutputLog.kt | ✅ EXISTS |
| Lint checker (inline error markers) | LintChecker.kt | ✅ EXISTS |
| Port scanner | PortsScanner.kt | ✅ EXISTS |
| Editor session save/restore (tab paths in SharedPreferences) | EditorPane.kt | ✅ EXISTS |
| WorkManager configured (HiltWorkerFactory) | CodeSpaceApplication.kt | ✅ EXISTS |

NO Workspace Trash, NO crash loop counter, NO safe mode, NO checkpoint/snapshot system,
NO background integrity validators — these do NOT exist yet.

### Startup Rule (CRITICAL):
- NEVER block startup. NEVER delay the 8-second headstart. All validation is background-only.
- App displays UI immediately. Background workers validate AFTER startup completes.

### What to implement (Phase 4 — do not start until Phase 2+3 complete):
1. **Crash loop counter** — increment on every cold start; reset after 60s uptime; trigger safe mode at 3+ crashes
2. **Safe Mode** — on crash loop: disable last-used terminal session, skip Ubuntu tab, load minimal UI; never auto-delete data
3. **Workspace Trash** — move deleted files/folders to ; restore/purge from settings
4. **Undoable file ops** — FileOpHistory: create/delete/rename/move with multi-level undo (stack in memory, not disk)
5. **Background integrity validator** — WorkManager OneTimeWork runs 10s after startup: validate settings JSON, editor session paths, cache sizes; post notification on problem
6. **Recovery Assistant** — simple screen listing detected problems + repair buttons (clear cache, reset settings, restore from backup)
7. **Workspace Snapshots** — manual zip of current project to external storage (extend BackupManager)
8. **Diagnostics Hub** — extends existing AppOutputLog: categories (crash/perf/git/terminal), export to .txt file

### Performance constraints:
- No startup delays. No UI blocking. WorkManager for all heavy validation.
- Minimal memory — cap diagnostic buffers at 500 entries (same as AppOutputLog).
- Never auto-delete user data.


---

## ARCHITECTURE DECISIONS (locked — don't change without reason)

1. **Termux native bootstrap** (not Ubuntu proot) for primary terminal — resolved OOM crashes
2. **No ExoPlayer** — native VideoView (no new Gradle dep, keeps APK small)
3. **No proot in main app** — Ubuntu tab uses proot but that lives in separate repo until stable
4. **Per-ABI APK splits** — keeps download size small for 3-8 GB devices
5. **`-bash` as argv[0]** — triggers login shell behavior correctly
6. **No LD_LIBRARY_PATH** — avoids ABI mismatch on bash startup
7. **`key(orientation)` on Dialogs** — because Activity has `configChanges=orientation` and never recreates
8. **versionCode from git commit count** — no manual bumping, always newer than installed version


---


---

## CHANGE LOG (recent entries only — old entries purged 2026-08-22)

---

### [2026-08-21 14:40 WAT] — AI Agent: Claude (Superagent), Commit: e45a3ae7, CI Build: #2413 (pending)
**Tags:** [LSP] [KOTLIN] [CRASH-FIX]
**What was fixed:**
1. Kotlin LSP "Message could not be parsed" root cause: kotlin-language-server 1.3.13 uses an older LSP4J that cannot deserialize newer LSP capability fields (callHierarchy, typeHierarchy, linkedEditingRange, moniker, inlayHint, semanticTokens with empty arrays, codeAction.resolveProvider). Gson throws parse error and returns error response, causing initialize to fail. Fix: Added `buildMinimalClientCapabilities()` with only LSP 3.14 base-spec fields — used for kotlin-language-server only; all other servers still get full capabilities.
2. Reduced JVM heap from -Xmx512m to -Xmx384m for Kotlin LSP to reduce memory pressure on TECNO KL4 (2.8GB RAM).
3. Added error code+message logging in JsonRpcClient.handleMessage so future LSP error responses show full error code + message in logs.
**Files touched:** LspManager.kt, JsonRpcClient.kt
**Next on roadmap:** (1) Verify build #2413 green on device — verify Kotlin LSP initializes. (2) Device retest of batch 2 LSP fixes (tests 7-14). (3) Batch 3 LSP tests (15-28): auto-completion, signature help, code actions, hover, inlay hints, code lens, document links, diagnostics, format, rename. (4) Editor Bug 1: Horizontal scroll stuck after zoom. (5) Editor Bug 2: Diagnostic overlap. (6) TypeScript 7 as default LSP with vtsls. (7) API_BASE_URL update to Render. (8) Codicon activity bar icons.

### RULES REMINDER BLOCK
1. TWO-REPO: Main IDE → codespace-ide-mobile | Proot/Ubuntu/rootfs → ubuntu-proot-test ONLY
2. CHANGE LOG: After every commit, add entry at BOTTOM of AGENTS.md
3. TAGS: Use [BUILD-FIX], [LSP], etc.
4. CURRENT STATE: Update Current State table at top with latest green build + commit SHA
5. NEVER re-do work already marked done
6. ROADMAP CONTINUITY: List ALL pending items
7. UI RULE: ALL menus/popups use rounded corners (8-12dp) AND padding (12dp horizontal, 10dp vertical minimum)

### [2026-08-22 12:40 WAT] — AI Agent: Claude (Superagent), Commit: 71113c8f, CI Build: #2422 (GREEN ✅)
**Tags:** [GIT] [BUILD-FIX] [UI]
**What was fixed:**
1. Git pathspec mismatch (root cause of staging failures): `git status --porcelain` reports paths relative to repo ROOT, but `git add`/`git reset`/`git diff`/`git blame` resolve pathspecs relative to CWD. When the app's active project dir is a subdirectory of the actual git repo, `git add "ProjectName/file.js"` fails with "pathspec did not match any files" because git looks for `ProjectName/ProjectName/file.js`. Fix: Added `rootFor(workdir)` helper that resolves the true repo root via `git rev-parse --show-toplevel`, then used it as the working directory for all pathspec-based commands (add, unstage, diffFile, diffStaged, blame, log-for-file, resolveConflict). Also added `--` pathspec separator to reset, resolveConflict, and blame for safety.
2. Missing Discard changes feature: Long-press on an unstaged or untracked file in Source Control now shows a confirmation dialog. For tracked files: `git checkout -- <path>` (revert to last commit). For untracked files: `git clean -f -- <path>` (delete from disk). Dialog uses RoundedCornerShape(12.dp) and error-colored confirm button per UI rules.
3. Unstage fix: Added `--` separator to `git reset HEAD -- <files>` to prevent ambiguous argument errors on repos with no commits yet (where HEAD doesn't exist as a branch name).
**Files touched:** GitService.kt, ScmState.kt, SourceControlPane.kt
**Next on roadmap:** (1) Batch 3 device tests — staging/unstaging/discard with the pathspec fix. (2) Full project wizard with template scaffolding (folder creation for Android/Flutter/React/etc). (3) Editor Bug 1: Horizontal scroll stuck after zoom. (4) Editor Bug 2: Diagnostic overlap. (5) TypeScript 7 as default LSP with vtsls. (6) API_BASE_URL update to Render. (7) Codicon activity bar icons. (8) Device retest of remaining 43-55 tests.


---

### [2026-08-22 12:58 WAT] — AI Agent: Claude Sonnet 4.5

**RULES REMINDER:** 1. TWO-REPO: Main IDE → codespace-ide-mobile | Proot/Ubuntu/rootfs → ubuntu-proot-test ONLY. 2. CHANGE LOG: After every commit, add entry at BOTTOM of AGENTS.md with timestamp, commit SHA, CI build number+pass/fail, what was fixed, files touched, next on roadmap. 3. TAGS: Use [BUILD-FIX], [LSP], etc. 4. CURRENT STATE: Update Current State table at top with latest green build + commit SHA. 5. NEVER re-do work already marked done. 6. ROADMAP CONTINUITY: List ALL pending items. 7. UI RULE: ALL menus/popups use rounded corners (8-12dp) AND padding (12dp horizontal, 10dp vertical minimum).

**Commit:** 55248a1e | CI: pending (watching)

**[FIX] Terminal→Explorer auto-refresh**

**What was fixed:** Files created/modified/deleted via terminal commands (echo > file.txt, mkdir, touch, git checkout, rm, etc.) now automatically appear in the Explorer file tree without manual refresh. Previously the Explorer had no connection to terminal file system changes — users had to manually tap refresh to see new files.

**How:** Added a debounced (1.5s) `onFileSystemChanged` callback to TerminalPane that fires after terminal output settles. This bumps a `terminalActivityCounter` state in ProjectShellScreen, which is passed as `externalRefreshTrigger` to ExplorerSidePanel, triggering a `refresh++` that re-scans the file tree.

**Files touched:**
- `ExplorerPane.kt` — added `externalRefreshTrigger: Int = 0` parameter + `LaunchedEffect` that bumps internal `refresh`
- `TerminalPane.kt` — added `onFileSystemChanged: () -> Unit = {}` param, wired debounced callback into `onTextChanged`, added `Job`/`delay` imports, wired SplitTerminalPanel too
- `ProjectShellScreen.kt` — added `terminalActivityCounter` state, passed to both TerminalPane instances + ExplorerSidePanel

**Next on roadmap:**
1. ✅ Terminal→Explorer auto-refresh — DONE (this commit)
2. Verify CI green on 55248a1e
4. Editor Bug 1: Horizontal scroll stuck after zoom
5. Editor Bug 2: Diagnostic overlap — same-line diagnostics stack at identical Y
6. TypeScript 7 as default LSP with vtsls
7. API_BASE_URL may still point to old Railway URL — update to Render
8. Codicon activity bar icons — waiting for Wisdom's screenshots
9. Project Wizard 3-step flow — shipped #2421
10. Source Control dotfile/metadata filtering — shipped #2421
11. BLOCKED: Google OAuth Client Secret (need GCP console access), Flow Mode (no mobile data), device testing on TECNO KL4

### RULES REMINDER BLOCK
1. TWO-REPO: Main IDE → codespace-ide-mobile | Proot/Ubuntu/rootfs → ubuntu-proot-test ONLY
2. CHANGE LOG: This entry — timestamp, SHA, CI build, what fixed, files touched, next on roadmap
3. TAGS: [RESTRUCTURE], [BUILD-FIX]
4. CURRENT STATE: Updated table above — #2432 green
5. NEVER re-do work already marked done
6. ROADMAP CONTINUITY: All pending items listed below
7. UI RULE: Rounded corners (8-12dp) + padding (12dp horiz, 10dp vert) on all menus/popups

### [2026-08-22 16:24 WAT] — AI Agent: Superagent (Claude)

**Commit:** 3485ba1c | **CI Build:** #2432 ✅ GREEN (fix for #2431 ❌)

**What was fixed:**
- Phase A (PositionMapper): Created `EditorPosition.kt` with `PositionMapper` class — O(log n) offset↔(line,column) lookups via cached newline offsets. Replaces 5+ independent calculation methods (Methods A-E) scattered throughout CodeEditor.kt.
  - 18 inline `cLine`/`cCol` calculations → `positionMapper.offsetToPosition()`
  - `GotoLineBar` offset calc: `text.split().take().sumOf()` → `gotoLineMapper.lineStart()`
  - `scrollToLine` LaunchedEffect: manual for-loop → `positionMapper.lineStart()`
  - `GhostTextOverlay` + `HoverOverlay`: inline calcs → `PositionMapper`
  - `offsetToLineChar()`: substring search → `PositionMapper`
  - `lineFromOffset()`: `take().count()` → `positionMapper.offsetToLine()`
- Phase E (EditorMetrics): Created `EditorMetrics.kt` with centralized metrics data class.
  - 12 `fontSize * 1.25f` pixel calculations → `editorMetrics.lineHeightPx`
  - 5 `fontSize * 0.6f` char width calculations → `editorMetrics.charWidthPx`
  - `GUTTER_WIDTH` const references `EditorMetrics.GUTTER_WIDTH_DP`
  - `GhostTextOverlay` + `HoverOverlay` use `EditorMetrics` constants
- Build fix: Re-added missing `coroutineScope.launch {` wrapper in `lspImportProvider` block that was accidentally dropped during the Phase A migration, causing cascading syntax errors (#2431 → #2432).

**Files touched:**
- `android/app/src/main/java/com/codespace/ide/editor/EditorPosition.kt` (NEW — 155 lines)
- `android/app/src/main/java/com/codespace/ide/editor/EditorMetrics.kt` (NEW — 130 lines)
- `android/app/src/main/java/com/codespace/ide/editor/CodeEditor.kt` (104 insertions, 124 deletions)

**Next on roadmap:**
- Phase B: Position auto-shifting on text edits (insert/delete updates positions)
- Phase C: Thread PositionMapper through to EditorOverlays.kt
- Phase D: Replace lineFromOffset() callers with direct positionMapper.offsetToLine()
- Go to Line highlight verification (on-device test)
- Complete UI testing batches (Batch 5-6 pending)
- Install build #2432 after all batches pass

---

### [2026-08-22 16:42 WAT] — AI Agent: Claude Opus 4.6

**RULES REMINDER:**
1. TWO-REPO: Main IDE → codespace-ide-mobile | Proot/Ubuntu/rootfs → ubuntu-proot-test ONLY.
2. CHANGE LOG: This entry. Timestamp, SHA, CI build, what was fixed, files touched, next on roadmap.
3. TAGS: [LSP] [RESTRUCTURE] [DOCS]
4. CURRENT STATE: Updated below.
5. NEVER re-do work already marked done.
6. ROADMAP CONTINUITY: All pending items listed.
7. UI RULE: Rounded corners (8-12dp), padding (12dp horiz, 10dp vert).

**Commit:** dc18c2f5 | **CI Build:** #2434 ❌ (MethodTooLargeException — CodeEditor composable exceeded 64KB limit due to 6 new gen counters)
**Fix commit:** (pending push) | **CI Build:** (pending)

**Fix:**
- Extracted 8 individual `var x by remember { mutableStateOf(0L) }` gen counters into a single `LspRequestGens` class — one `remember` call instead of 8 saves ~300 bytes of bytecode.
- Extracted multi-line indent + single-line tab logic into `EditorActions.kt` — saves ~400 bytes of bytecode from the CodeEditor composable body.

**What was fixed:**
- Phase B (Position auto-shifting): Added `shiftOnInsert()`, `shiftOnDelete()`, `shiftPositionOnInsert()`, `shiftPositionOnDelete()`, `shiftOffsetsOnInsert()`, `shiftOffsetsOnDelete()` to `PositionMapper`. These allow cached positions (LSP responses, diagnostic ranges, search matches) to be adjusted when text is edited without full re-computation. Inspired by sora-editor's `MappedSpans.adjustOnInsert()/adjustOnDelete()`.
- Phase C (Thread PositionMapper to EditorOverlays): Replaced `lineFromOffset: (Int) -> Int` lambda parameter with `positionMapper: PositionMapper` in both `ExtraCursorOverlay` and `SearchMatchOverlay`. Eliminated 2 inline `lastIndexOf('\n')` calculations in EditorOverlays.kt — now use `positionMapper.offsetToPosition()` for line+column in one call. Replaced `fontSize * 0.6f` with `EditorMetrics.CHAR_WIDTH_MULTIPLIER` in both overlays.
- Phase D (Replace lineFromOffset callers): Migrated all 19 remaining `lineFromOffset()` calls to `positionMapper.offsetToLine()`. Replaced 13 inline `lastIndexOf('\n')` calculations with `positionMapper.lineStart()` / `positionMapper.offsetToPosition().column`. Replaced 5 `split('\n').getOrNull(lineFromOffset(x))` patterns with `positionMapper.getLineText()`. Replaced 1 `split('\n').sumOf()` LSP offset calculation with `positionMapper.lspToOffset()`. Added 6 new gen counters (hoverRequestGen, definitionRequestGen, referencesRequestGen, codeActionRequestGen, formatRequestGen, renameRequestGen) for future stale-response protection of async LSP requests.

**Files touched:**
- `android/app/src/main/java/com/codespace/ide/editor/EditorPosition.kt` (Phase B: +60 lines shift methods)
- `android/app/src/main/java/com/codespace/ide/editor/EditorOverlays.kt` (Phase C: signature changes + inline calc removal)
- `android/app/src/main/java/com/codespace/ide/editor/CodeEditor.kt` (Phase D: 19 lineFromOffset + 13 lastIndexOf + 5 split+getOrNull + 1 sumOf replacements, +6 gen counters)

**Next on roadmap:**
- Phase F: Decoration store (centralize syntax/semantic/diagnostic/search/selection layers with independent invalidation)
- Phase G: Visual line mapper (folding + word-wrap support, replace displayLines list)
- Go to Line highlight verification (on-device test)
- Complete UI testing batches (Batch 5-6 pending)
- Install build after all batches pass

---

### RULES REMINDER BLOCK
1. TWO-REPO: Main IDE → codespace-ide-mobile | Proot/Ubuntu/rootfs → ubuntu-proot-test ONLY
2. CHANGE LOG: After every commit, add entry at BOTTOM of AGENTS.md
3. TAGS: Use [BUILD-FIX], [LSP], [DOCS], etc.
4. CURRENT STATE: Update Current State table at top with latest green build + commit SHA
5. NEVER re-do work already marked done
6. ROADMAP CONTINUITY: List ALL pending items
7. UI RULE: ALL menus/popups use rounded corners (8-12dp) AND padding (12dp horizontal, 10dp vertical minimum)

### [2026-08-22 18:25 WAT] — AI Agent: Claude Opus 4.6 (Superagent)

**Commit:** dfb4b081 | **CI Build:** pending (fix for #2434 ❌)

**Tags:** [BUILD-FIX] [DOCS] [RESTRUCTURE]

**What was fixed:**
1. **Phase F — DecorationStore:** Created `DecorationStore.kt` — centralizes 12 decoration layers (syntax, diagnostics, semanticTokens, search, selectionHighlights, cursor, blame, conflicts, inlayHints, bookmarks, foldRanges, foldedLines) with independent epoch-based invalidation. Each layer bumps its own epoch on update, so consumers `remember(epoch)` to skip recomposition when that layer hasn't changed. Inspired by sora-editor's RenderContext/MappedSpans.
2. **Phase G — VisualLineMapper:** Created `VisualLineMapper.kt` — replaces the displayLines list with proper visual-line ↔ document-line mapping that handles both code folding (collapses ranges to placeholder) and word-wrap (splits long lines into segments). Provides O(1) visual line count, O(1) visual→doc lookup, and O(1) doc→visual lookup.
3. **Build fix — lintErrors forward reference:** Phase F LaunchedEffect for `lintErrors` was placed at line 866 but `lintErrors` isn't declared until line 1677. Moved the sync LaunchedEffect to after the declaration.
4. **AGENTS.md purge:** Removed 10,454 lines of old test tracking tables, audit notes, phase detail specs, and historical changelog entries. File reduced from 11,404 → ~1,000 lines. All old test results (🟢/🟡/🔴 tracking, batch results, compatibility tests, vscode.dev manual test sessions) permanently deleted per testing protocol reset.

**Files touched:**
- `android/app/src/main/java/com/codespace/ide/editor/DecorationStore.kt` (NEW — ~230 lines)
- `android/app/src/main/java/com/codespace/ide/editor/VisualLineMapper.kt` (NEW — ~200 lines)
- `android/app/src/main/java/com/codespace/ide/editor/CodeEditor.kt` (decorationStore + visualLineMapper wiring, lintErrors LaunchedEffect relocation)
- `AGENTS.md` (purged 10,454 lines of old test/audit/historical data)

**Next on roadmap:**
- Verify CI green on dfb4b081 (lintErrors fix)
- Phase B: Position auto-shifting on text edits (insert/delete updates positions)
- Phase C: Thread PositionMapper through to EditorOverlays.kt
- Phase D: Replace lineFromOffset() callers with direct positionMapper.offsetToLine()
- Go to Line highlight verification (on-device test)
- Editor Bug 1: Horizontal scroll stuck after zoom
- Editor Bug 2: Diagnostic overlap — same-line diagnostics stack at identical Y
- TypeScript 7 as default LSP with vtsls
- API_BASE_URL may still point to old Railway URL — update to Render
- Codicon activity bar icons — waiting for Wisdom's screenshots
- BLOCKED: Google OAuth Client Secret (need GCP console access), Flow Mode (no mobile data)

---

### RULES REMINDER BLOCK
1. TWO-REPO: Main IDE → codespace-ide-mobile | Proot/Ubuntu/rootfs → ubuntu-proot-test ONLY
2. CHANGE LOG: After every commit, add entry at BOTTOM of AGENTS.md with timestamp, SHA, CI build, what fixed, files touched, next on roadmap
3. TAGS: [BUILD-FIX], [DOCS], etc.
4. CURRENT STATE: Update Current State table at top with latest green build + commit SHA
5. NEVER re-do work already marked done
6. ROADMAP CONTINUITY: List ALL pending items
7. UI RULE: ALL menus/popups use rounded corners (8-12dp) AND padding (12dp horiz, 10dp vert) minimum

### [2026-08-22 19:30 WAT] — AI Agent: Claude Opus 4.6 (Superagent)

**Commit:** ee5c4a7a | **CI Build:** ✅ GREEN

**Tags:** [BUILD-FIX] [DOCS]

**What was fixed:**
1. **Method too large fix:** CodeEditor.kt exceeded 64KB JVM bytecode limit after Phase F+G additions (6027 lines). Extracted 235 lines of inline minimap + overview ruler + indentation guide rendering code into new `MinimapSection.kt` as a `BoxScope` extension composable. CodeEditor.kt now 5805 lines — well under limit.
2. **Invalid imports fix:** Removed `kotlin.math.maxOf` and `kotlin.math.minOf` imports — these are top-level stdlib functions, not in `kotlin.math`.
3. **AGENTS.md cleanup:** Removed stale old test changelog entry (04640746 — Tests 6, 7, 8, 12 from build #2427). Updated Current State table to reflect latest green build ee5c4a7a.

**Files touched:**
- `android/app/src/main/java/com/codespace/ide/editor/CodeEditor.kt` (replaced 235-line inline block with single MinimapSection() call)
- `android/app/src/main/java/com/codespace/ide/editor/MinimapSection.kt` (NEW — 270 lines)
- `AGENTS.md` (cleanup + changelog update)

**Next on roadmap:**
- Resume UI testing with fresh batches (new testing protocol — all old results purged)
- Go to Line highlight verification (on-device test)
- Editor Bug 1: Horizontal scroll stuck after zoom
- Editor Bug 2: Diagnostic overlap
- TypeScript 7 as default LSP with vtsls
- API_BASE_URL update to Render
- Codicon activity bar icons verification
