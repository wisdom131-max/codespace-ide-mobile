# B11 — PROBLEMS, OUTPUT & DIAGNOSTICS

> VS Code parity research, batch 11 of 14.
> VS Code: `microsoft/vscode` @ `main`, verified live 2026-09-14 (GitHub API listings).
> Ours: `codespace-ide-mobile` @ `816154e`, grep-verified.

## §0 Scope & sources inspected

VS Code: `workbench/contrib/markers/browser/` (markersView, markersModel, markersTable, markersFilterOptions, markersFileDecorations, **markersChatContext.ts**, markersViewActions), `workbench/contrib/output/browser/` (outputServices, outputView, outputLinkProvider — file links in output), `platform/markers/common/markerService.ts` (marker service), `editor/contrib/gotoError` (F8 navigation — cited B04), codeAction/lightbulb wiring (B04), terminal output channel (B06).

Ours: `diagnostics/` (DiagnosticManager — Diagnostic/Range/RelatedInfo models, Severity ERROR/WARNING/INFO/HINT, SourceHealth READY/UNAVAILABLE/FAILED/STALE; DiagnosticConverter, DiagnosticPublisher, LintChecker, AppOutputLog, PerformanceMonitor, PortsScanner), `lsp/LspDiagnosticsHandler.kt`, `ui/panes/ProblemsPanel.kt` (399 lines) + `AdvancedProblemsPanel.kt` (319), editor squiggles (CodeEditor/DecorationStore), QUICK_FIX action (B04), CrashLog backend flow, PerfProbe (→ Output).

## §1 What VS Code has (citations)

- **Marker service** (`platform/markers/common/markerService`): language/diagnostic providers push markers (severity, range, message, source, code, related info, tags); the service owns the truth, views and the editor subscribe.
- **Problems view** (`markersView.ts`): file-grouped tree + **table mode** (`markersTable.ts`), **filter options** (`markersFilterOptions.ts` — filter by text/severity/file), navigation actions (`markersViewActions.ts`), copy-message, collapse-all; also **`markersFileDecorations.ts`** — problem badges/decorations on EXPLORER files (the B03 gap from the other side!).
- **`markersChatContext.ts`** — problems attachable to chat as structured context (third chat-context edge after SCM history + terminal transcript).
- **Editor wiring**: squiggle decorations w/ severity colors, hover shows the full marker, quick-fix lightbulb linked (B04), F8 next/prev problem (`gotoError`), marker navigation commands.
- **Output panel** (`contrib/output`): channel-per-subsystem, `outputLinkProvider.ts` renders file:line LINKS inside output text, follow-tail, clear, per-channel visibility.
- **Diagnostics power-tools**: diagnostic collections from extensions, "problems as you type" via LSP push/pull, related-info chains (this error ⇐ caused by that error), tags (unnecessary/deprecated — fade + strike).

## §2 Architecture — shared state & connections

- MarkerService is the hub: LSP diagnostics, lint, tasks (build errors → B14), tasks problems matchers all push into one model; problems view/editor decorations/explorer badges/chat context are four renderers of it.
- Output channels are append-only logs with link decoration — our AppOutputLog already says "mirrors VS Code's Output panel" in its own docstring.
- Problems↔chat, problems↔explorer decorations: two edges we have NOT connected (both are in VS Code's tree now).

## §3 What OUR app has (verified)

- **DiagnosticManager**: full-fidelity model — severity levels incl. HINT, ranges, **RelatedInfo** chains, **SourceHealth** states (READY/UNAVAILABLE/FAILED/STALE — a VS Code-absent concept: provider health tracking, useful on-device), DiagnosticConverter + DiagnosticPublisher (LSP → our model).
- **Panels**: ProblemsPanel (399 lines) + AdvancedProblemsPanel (319 — search/filter/severity tiers presumably §6).
- **Editor**: squiggles (CodeEditor/DecorationStore), QUICK_FIX action + lightbulb (B04 HAVE), lint: `LintChecker`/`LintAnalyzer` (own lint layer beyond LSP!).
- **Output**: AppOutputLog — 6 channels (info, build, git, debug, terminal, lsp), timestamped `[ts][channel]` entries; PerfProbe, MC-TRIPWIRE, KEY-FAILOVER, [perf] all land here. No file-links in output text (§6), no follow-tail toggle (§6).
- **Extras beyond VS Code scope**: PortsScanner (running-ports detection — ~ VS Code Ports view analog, unlisted elsewhere), PerformanceMonitor, CrashLog backend entity flow (bug-report → crash analysis).
- **Unknown/missing**: explorer problem badges (markersFileDecorations — B03 gap confirmed from VS Code side), problems-as-chat-context, problems table mode, deprecated/unnecessary tags.

## §4 Verdict table

| Feature (VS Code) | Verdict | Gap |
|---|---|---|
| Marker model (severity, range, related info, tags) | HAVE | tags (unnecessary/deprecated) missing |
| LSP diagnostics → markers | HAVE | push model via handler + publisher |
| Own lint layer beyond LSP | HAVE-UNIQUE | LintChecker/LintAnalyzer |
| SourceHealth tracking | HAVE-UNIQUE | READY/UNAVAILABLE/FAILED/STALE — VS Code has no provider-health surface |
| Problems panel (file-grouped tree) | HAVE | 399-line panel |
| Problems filter/search | PARTIAL | AdvancedProblemsPanel — verify filter scope §6 |
| Problems table mode | MISSING | list-only |
| F8 next/prev problem nav | PARTIAL | gotoError analog via palette/actions §6 |
| Explorer problem badges (markersFileDecorations) | MISSING | B03 gap, confirmed from VS Code side |
| Squiggles + hover + quick-fix link | HAVE | B04 lightbulb + QUICK_FIX |
| Problems as chat context (markersChatContext) | MISSING | future attach-picker row (→ B13) |
| Output channels (per-subsystem) | HAVE | 6 channels, self-documented mirror |
| Output file:line links (outputLinkProvider) | MISSING | plain text (would reuse open-at-line entry — cheap win) |
| Output follow-tail/clear | PARTIAL | clear §6 |
| Build errors → problems (task matchers) | UNKNOWN | build channel exists; matcher path §6 (→ B14) |
| Ports view | PARTIAL-UNIQUE | PortsScanner exists; view surface §6 |

## §5 Cross-subsystem connection edges

- problems ↔ **B03 explorer**: markersFileDecorations is the SAME missing edge from both sides — file badges would close B03's most user-visible gap AND B11's.
- problems ↔ **B13 chat**: markersChatContext = third chat-context edge (after SCM history, terminal transcript) — attach-picker candidate.
- problems ↔ **B08 debug**: stack-trace links + open-at-line share the editor entry (same as OSC 7777 from B06).
- output ↔ **B14 tasks**: build-error matchers → problems panel would unify "run" and "diagnose" (open question, both batches).
- SourceHealth ↔ **R6/multi-key**: provider-health pattern (READY/FAILED/STALE) is the same family as ChatKeyFailover cooldowns — a shared health-state vocabulary exists across our app.

## §6 Open questions / on-device verification

1. AdvancedProblemsPanel: exact filter capabilities (severity tier? text? file scope?) — 319 lines unverified in detail.
2. Output panel: any tap-to-open on `file:line` text today? (grep suggests no — likely cheap win via existing open-at-line entry)
3. Build errors: does build output get parsed into problems, or only into the build channel log?
4. F8/gotError-style "cycle problems" — is there a bound action or palette entry?
5. PortsScanner: surfaced in any panel UI (forwarded-ports list) or background-only?
6. HINT severity: any producer today (LSP hints → inlay-ish?), or enum-only?

## Status

**DONE** — 2026-09-15. Next: B12 UI & workbench chrome.
