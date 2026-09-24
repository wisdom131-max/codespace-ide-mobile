# Testing: fresh source-only group audit

**State:** app source read; no device test run. `READ A/path:N` is 1-based in `android/app/src/main/java/com/codespace/ide/`, `READ V/path:N` is 1-based in `vscode-src/` (repo root `src/vs/`). Per COVERAGE-CHECK.md this is the smallest group of the four: the app's entire testing surface is (a) the synthetic test-lens detector, (b) its render-merge pipeline and click interception in EditorPane, (c) one gradle task-catalogue entry, and (d) scattered test-adjacent mentions (jest-seeding project templates, a JUnit file classifier, a Chat skill that writes tests). There is NO test discovery service, NO test results surface, NO test explorer, NO coverage, NO per-test outcome — this group's job is to prove those absences precisely and audit what exists. **Boundaries owned elsewhere:** the TaskRunner catalogue row for `test` (Problems PB11), BuildRunner output truncation (Problems PR03 — test failures late in output are swallowed), the lens RENDERING row in Editor (E18), and the debugger's DAP config surface (Debugger DG). Nothing here is a device outcome or a code-fix authorization.

## Feature inventory

| ID | Source-traced feature |
|---|---|
| TS01 | TestLensDetector: line-scan detection of test entry points for 5 language families — Kotlin/Java (@Test annotation forms + `fun test*`/`void test*` inside any class containing "Test"/"Spec"), Python (`def test_*`/`async def test_*`/`class Test*:`), JS/TS (it/test/it.skip/it.only/describe), Dart (test/testWidgets/group) — with seen-lines dedupe. READ A/editor/TestLensDetector.kt:26-45,47-63,65-77,79-99,101-116. |
| TS02 | Synthetic lens pair per detected line: "▶ Run Test" (command codespace.runTest) and "Debug Test" (command codespace.debugTest), zero-width whole-line ranges, arguments=[lineIndex], data.type runTest/debugTest. READ A/editor/TestLensDetector.kt:118-160. |
| TS03 | Lens pipeline in EditorPane: regenerate on every path/content change (IO), 1200ms debounce + two-level stale checks (server generation, document version) for the LSP branch, merge LSP + synthetic lenses, no-LSP fallback shows synthetic only. READ A/ui/panes/EditorPane.kt:1809-1858. |
| TS04 | CodeLens click routing: synthetic test commands intercepted first; other LSP commands executed via executeCommand; unresolved lenses resolved then executed. READ A/ui/panes/EditorPane.kt:2671-2710. |
| TS05 | Test-lens "execution": builds a per-language command string, writes ONE line to the Output log (channel "test") — see TG01 for what it does NOT do. READ A/ui/panes/EditorPane.kt:2676-2694. |
| TS06 | Per-language command templates: Python `python3 -m pytest "<file>" 2>/dev/null \|\| python3 "<file>"`; JS/TS `npx jest "<file>" 2>/dev/null \|\| node "<file>"`; Kotlin/Java `./gradlew test 2>/dev/null \|\| echo 'Run via IDE build task'`. READ A/ui/panes/EditorPane.kt:2680-2683. |
| TS07 | Task catalogue entry: TaskId.TEST "Run Unit Tests" → gradle `test` via BuildRunner with problems published per run (Problems-owned). READ A/project/TaskRunner.kt:47. |
| TS08 | Test-adjacent surfaces: JS/TS project templates seed jest + a "test": "jest" script; PowerUser file classifier labels `*test*.kt/.java` JUnit; Chat SkillsCatalog has a test-writing skill. READ A/project/ProjectTemplates.kt:243,251-252,324; A/ui/panes/PowerUserPanels.kt:197-198; A/chat/SkillsCatalog.kt:58. |
| TS09 | Editor-side lens rendering (E18 boundary): merged lenses rendered as code-lens chips in the editor gutter area. READ A/editor/CodeEditor.kt:2288-2301; A/ui/panes/EditorPane.kt:1811-1858. |
| TS10 | No test settings exist anywhere: no testing category in SettingsSchema (VS Code has a whole testing configuration tree) — confirmed absent. READ A/editor/settings/SettingsSchema.kt:17-38. |

## Cross-group connection ledger

| Edge | Producer -> consumer and seam |
|---|---|
| C-TS01 | Editor E18 owns lens rendering + the "test CodeLens" row; this group owns detection semantics and the click path's execution (or lack of it). READ A/ui/panes/EditorPane.kt:1811-1858. |
| C-TS02 | Problems PB11 owns the gradle `test` task row; PR03's 2000-line output cap means test failures past the cap NEVER reach the Problems panel — a test-results gap that lives in Problems but only matters for Testing. READ A/project/TaskRunner.kt:47; A/build/BuildRunner.kt (PR03). |
| C-TS03 | Debugger: no debug-test wiring exists — UniversalDebugManager has no test-launch configs, and the "Debug Test" lens never reaches it. A future debug-test flow must come through this seam. READ A/ui/panes/EditorPane.kt:2676-2690; A/debug/DebugConfiguration.kt. |
| C-TS04 | Terminal: the natural executor for the dead lens command is the terminal/execOnce stack — wiring it inherits TP03 (String result, no typed success) and the MAX_LINES 2000 cap. READ A/ui/panes/EditorPane.kt:2680-2683; A/terminal/IdeTerminalBridge.kt. |
| C-TS05 | LSP: real servers (pyright, typescript) also emit Run/Test codelenses; the merge has NO dedupe — same test can show two lens chips (TG09). READ A/ui/panes/EditorPane.kt:1838-1852. |
| C-TS06 | Chat: SkillsCatalog can WRITE tests; the app can only run gradle `test` wholesale — the agent produces artifacts the IDE cannot exercise per-test. READ A/chat/SkillsCatalog.kt:58. |
| C-TS07 | 18b: ProjectWizard/ProjectTemplates seed jest into new JS/TS projects whose tests the lens can detect but only jest-file-run (never per-test). READ A/project/ProjectTemplates.kt:243,251-252. |
| C-TS08 | Performance: detectTestLenses rescans the FULL file on every content change (per keystroke) — unbounded IO scan; PG-family measure-first (per-file PM row before any fix). READ A/ui/panes/EditorPane.kt:1809-1814. |
| C-TS09 | Settings: no testing category exists; any future testing settings land in the SK01/SK02-hardened store per the owner's priority ruling. READ A/editor/settings/SettingsSchema.kt:17-38. |
| C-TS10 | PowerUser/Viewers (18a): JUnit file classification hints at a power-user intent to recognize test files — no functional link today. READ A/ui/panes/PowerUserPanels.kt:197-198. |

## VS Code clone comparison

| Area | Verified VS Code owner | Android contrast |
|---|---|---|
| Testing view | READ V/src/vs/workbench/contrib/testing/browser/testingViewPaneContainer.ts:21; V/src/vs/workbench/contrib/testing/browser/testingExplorerView.ts (TestingExplorerFilter at testingExplorerFilter.ts:35). A whole Testing view container: explorer tree, filter, actions. | No testing view of any kind; the only project-wide test entry is the gradle task in the bottom panel. |
| Test item model + discovery | READ V/src/vs/workbench/contrib/testing/common/mainThreadTestCollection.ts. Extension-provided TestItem tree: discovery, hierarchy, tags; persistent between runs. | READ A/editor/TestLensDetector.kt:26-45. Content-scan of the open file only: no project discovery, no hierarchy, no tags; each open file is an island. |
| Run profiles (run/debug) | READ V/src/vs/workbench/contrib/testing/common/testProfileService.ts:116 (+ runTests/debug refs :55+). Profiles carry a debug flag; per-test execution by item id. | READ A/ui/panes/EditorPane.kt:2676-2690. Two lens titles, zero execution: no profiles, no runner, debug identical to run (TG01/TG07). |
| Test results | READ V/src/vs/workbench/contrib/testing/common/testResultService.ts:75. TestResultService retains per-test outcomes, computed states, messages. | READ A/ui/panes/EditorPane.kt:2686-2690. One log line; no result is ever captured, parsed, or displayed (TG03). |
| Editor test decorations | READ V/src/vs/workbench/contrib/testing/browser/testingDecorations.ts:359. TestingDecorations renders run/debug gutter markers from the TestItem tree + result states in-editor. | READ A/editor/CodeEditor.kt:2288-2301. Lens chips exist (from E18) but carry no state — a passed and a failed test look identical. |
| Failure peek inline | READ V/src/vs/workbench/contrib/testing/browser/testingOutputPeek.ts:437. TestingOutputPeekController shows failure output inline at the test site. | Nothing: a failed test's message is only in raw Build/Output text, subject to PR03 truncation (C-TS02). |
| Coverage | READ V/src/vs/workbench/contrib/testing/common/testCoverageService.ts:55; browser/codeCoverageDecorations.ts, testCoverageView.ts. Coverage service + editor decorations + coverage view. | No coverage surface exists in any form. |
| Testing configuration | READ V/src/vs/workbench/contrib/testing/common/configuration.ts. First-class testing settings (auto-run, openTesting, gutter options). | READ A/editor/settings/SettingsSchema.kt:17-38. No testing category; nothing to configure because nothing runs (TS10). |

## Fresh scratch-only device checks, NONE RUN

Record PASS/FAIL/NOT RUN with reason. Use a disposable scratch project; back up before running gradle tasks.

| Test | Action -> pass criterion and diagnostic |
|---|---|
| TC01 TS01 | Scratch Kotlin file with @Test fun, a @ParameterizedTest fun, a backtick-named test, and a `fun latestThing()` inside a class named `MyTestHelper` — record WHICH lines get lenses (predicted: @Test only; backtick/parameterized missed; latestThing false-positive via the Test-substring class heuristic). |
| TC02 TS01 | Python file: def test_ok, async def test_async, class TestFoo:, unittest method inside class not named Test* — record detection. |
| TC03 TS01 | JS file: it('x'), test('y'), it.each, describe, it.skip, nested it inside describe — record detection (predicted: it.each missed). |
| TC04 TS01 | Dart file: test('x'), testWidgets('y'), group — record detection. |
| TC05 TS03 | Type in the file continuously: lenses appear ~1.2s after typing stops (debounce) and content changes restart them; no lens flicker while typing. |
| TC06 TS03 | Switch files quickly: lenses from the previous file never bleed into the new file (stale checks). |
| TC07 TG09 | With pyright (or TS LSP) running on a Python/TS test file that ALSO has LSP-provided Run/Test codelenses: look for DUPLICATE chips on one test line. Record. |
| TC08 TG01 **core** | Tap "▶ Run Test" on a passing pytest/jest test. Expected: NOTHING runs — no terminal, no process; only a `[TestLens] Running test…` line in Output (channel test). Record exactly what the user sees. |
| TC09 TG01 | Tap "Debug Test" on the same test: identical behavior to Run (no debugger, no DAP attach, no breakpoint). Record. |
| TC10 TG02 | The logged command for Python runs the WHOLE FILE (pytest "<file>"), not the tapped test — even if execution were wired, sibling tests run too. Confirm from the logged string. |
| TC11 TS07 | Run "Run Unit Tests" from the bottom panel on a scratch Android project: gradle `test` runs, problems published, results only as raw output. |
| TC12 C-TS02 | Make the scratch project emit >2000 lines of test output with failing tests named past line 2000 — confirm those failures NEVER appear in Problems (PR03 cap eats them). |
| TC13 TG03 | After a failing gradle `test` run: search every UI surface for the failing test's name — Problems? No. Editor? No. Output only. Record the result-void. |
| TC14 TS08 | Create a JS project from templates: package.json has jest + "test": "jest" script; the seeded sample test gets a lens. |
| TC15 TS06 | Read the logged Kotlin/Java command: `./gradlew test 2>/dev/null || echo 'Run via IDE build task'` — note it targets the PROJECT, not the file or test, and stderr is discarded (TG06). |
| TC16 TG05 | Open a non-test Kotlin file that merely contains a class with "Spec" in the name and a `fun testing()` — record false-positive lenses. |
| TC17 TS09 | Long test file: lens chips align with the correct lines while scrolling fast (E18 alignment check applies). |
| TC18 TS03 | Open a test file with NO LSP server running: lenses still render (synthetic-only fallback). |
| TC19 TG07 | From the Debugger's config surface, search for any test-launch config type — record absence (debug-test flow does not exist). |
| TC20 TG10 | Type a full character repeatedly in a 5000-line test file: detectTestLenses rescans per keystroke on IO — record any typing jank vs the PG measure-first rule (add a PM row, no fix). |
| TC21 TS10 | Search In-Project Settings for any testing category — record absence. |
| TC22 TG04 | Project-wide: is there ANY way to see all tests in the project without opening each file? Record the absence (no explorer). |
| TC23 TG08 | In a pure-JS or Python scratch project, run "Run Unit Tests" (gradle task): record the failure mode (no gradlew / wrong project type). |
| TC24 C-TS06 | Ask the Chat skill to write a test, then try to run exactly that test through any UI path — only whole-file (if wired) or whole-project (gradle) paths exist. |

## Source-confirmed gaps for master fix-plan input

| Gap | Priority | Bounded source finding |
|---|---|---|
| TG01 | TOP of group | The Run/Debug Test lenses are DECORATIVE: the click handler builds the command string and writes one AppOutputLog line, then returns — no terminal execution, no BuildRunner call, no process. "Debug Test" is byte-identical to Run Test (no debugger involvement). A visible affordance with dead behavior is a UI lie: users tap "▶ Run Test" and nothing happens anywhere except a log most never open. READ A/ui/panes/EditorPane.kt:2671-2690. TC08/TC09. |
| TG02 | HIGH in group | No per-test targeting even in the dead template: pytest/jest run the whole FILE, gradle runs the whole PROJECT — the tapped line only feeds the log message; VS Code executes the single TestItem by id with filters. READ A/ui/panes/EditorPane.kt:2680-2683. TC10. |
| TG03 | HIGH in group | No test-results surface at all: nothing parses test output (no JUnit XML, no pytest/jest result parsing), no pass/fail state, no failure-to-Problems bridging, no inline failure peek — outcome is invisible unless the user reads raw (PR03-truncated) text. READ A/ui/panes/EditorPane.kt:2686-2690; A/build/BuildRunner.kt (PR03). TC13. |
| TG04 | HIGH in group | No test discovery/explorer: lenses scan only the OPEN file; no project-wide test tree, no run-all-tests entry beyond the Android-only gradle task. READ A/editor/TestLensDetector.kt:26-45; A/project/TaskRunner.kt:47. TC22. |
| TG05 | MEDIUM | Detection blind spots + false positives source-confirmed: @ParameterizedTest/@TestFactory/@Nested/backtick-name tests missed; `it.each`/`test.each` missed; the JVM heuristic "class name contains Test/Spec" marks the whole file as test context and any `fun …test…` (e.g. latestThing) gets a lens. READ A/editor/TestLensDetector.kt:49-63,79-93. TC01/TC03/TC16. |
| TG06 | LOW-MEDIUM | `2>/dev/null` in every template swallows real runner errors (S01-echo: an unverifiable command that hides its own failure channel); gradle fallback reduces to a no-op echo. READ A/ui/panes/EditorPane.kt:2680-2683. TC15. |
| TG07 | HIGH in group | Debug-Test does not exist end-to-end: no DAP test-launch configs, no adapter wiring, the debug lens shares the (dead) run path. Any fix needs a Debugger-seam design, not a lens tweak. READ A/ui/panes/EditorPane.kt:2676-2690; A/debug/DebugConfiguration.kt. TC09/TC19. |
| TG08 | MEDIUM | The only runnable entry (gradle `test`) assumes an Android/gradle project: JS/Python/Dart projects get a task that fails on the missing wrapper — testing is Android-first in a multi-language IDE. READ A/project/TaskRunner.kt:47. TC23. |
| TG09 | MEDIUM | Lens merge has no dedupe: LSP-provided Run/Test codelenses and synthetic lenses stack — duplicate chips on one line when a server is active. READ A/ui/panes/EditorPane.kt:1838-1852. TC07. |
| TG10 | LOW | detectTestLenses rescans the full file on EVERY content change (per keystroke, on IO) with no memo of last-scan result for unchanged non-test files — PG-family; measure first (PM row), no fix claim without numbers. READ A/ui/panes/EditorPane.kt:1809-1814. TC20. |

## Boundary

Editor owns lens rendering (E18); Problems owns the task row (PB11) and the output cap (PR03 — C-TS02 makes it a testing-relevant cap); Debugger owns the DAP surface a future debug-test flow must grow into (C-TS03); Terminal owns the execution seam (TP03 typed-result) a fix must route through; Performance owns the measure-first gate on TG10; Settings owns where testing settings would live (post SK01/SK02 hardening, per the owner's 2026-09-24 priority ruling). **Priority note:** TG01 is the group's headline (a visible affordance whose entire execution is a log line) and belongs in the FIRST wave of feature-dead checks on device (TC08/TC09 confirm it in one tap); TG02-TG04 and TG07 describe what a real testing surface would require — they are additive-feature gaps, not defects in shipped behavior, and rank below the data-loss/security tiers by the audit's standing rules. All TC01-TC24 are predictions; none authorizes code changes.
