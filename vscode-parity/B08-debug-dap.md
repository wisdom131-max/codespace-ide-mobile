# B08 — DEBUG & DAP

> VS Code parity research, batch 8 of 14.
> VS Code: `microsoft/vscode` @ `main`, verified live 2026-09-14 (GitHub API listings).
> Ours: `codespace-ide-mobile` @ `727faae`, grep-verified.

## §0 Scope & sources inspected

VS Code: `workbench/contrib/debug/browser/` (debugService, debugSession, debugAdapterManager, debugConfigurationManager, breakpointsView, breakpointWidget + breakpointEditorContribution, callStackView + callStackWidget, debugHover, debugToolBar, debugEditorActions/Contribution, exceptionWidget, disassemblyView, debugMemory, debugChatIntegration, debugExpressionRenderer, debugConsoleQuickAccess, loadedScriptsView, debugTaskRunner, debugStatus, debugANSIHandling, linkDetector), `contrib/debugCommon`, platform DAP (`platform/debugCommon` + `platform/debug` DAP protocol layer).

Ours: `debug/` package (DAPClient, DebugAdapter, DebugConfiguration, DebuggerDependencies, NodeDAPAdapter, PythonDAPAdapter, UniversalDebugManager) + UI: `ui/panes/DebugConsoleSection.kt`, `DebugHoverEvaluate.kt`, `AttachDebugDialog.kt`, `DebugEditDialogs.kt`, EditorPane P54 breakpoints, `LogcatPanel.kt`, `AndroidRuntimeViewerDialog.kt`, `ApkAnalyzerDialog.kt`, `DisassemblyViewerDialog.kt`, `DexViewerDialog.kt`, `ElfViewerDialog.kt`.

## §1 What VS Code has (citations)

- **Session core** (`debugService.ts`, `debugSession.ts`, `debugAdapterManager.ts`): pluggable debug-adapter contributions per language/type, lifecycle state machine, restart/terminate, session picker for multiple concurrent sessions (`debugSessionPicker.ts`), pre-launch task runner (`debugTaskRunner.ts`), compound configs + `launch.json` (`debugConfigurationManager.ts`), status-bar indicator (`debugStatus.ts`).
- **Breakpoints** (`breakpointsView.ts`, `breakpointWidget.ts`): source/function/data/exception breakpoints; INLINE editing of conditions/hit-count via `breakpointWidget` (click gutter → popover); logpoints; per-viewpoint decorations; expression renderer for rich values (`debugExpressionRenderer.ts`).
- **Call stack + variables + watch**: threads/frames view, frame-switch focus, variables view w/ nested inspect + copy value + "break on value change" (data breakpoints), watch view, hover evaluate (`debugHover.ts`), inline/peek debugging.
- **Console/REPL**: debug console with ANSI (`debugANSIHandling.ts`), links (`linkDetector.ts`), quick-access console history (`debugConsoleQuickAccess.ts`), filter, evaluate.
- **Advanced**: `disassemblyView.ts` (instruction-level stepping), `debugMemory.ts` (memory viewer), `exceptionWidget.ts` (exception breakpoint filters), `loadedScriptsView.ts`, `debugChatIntegration.ts` (**chat↔debug edge** — AI debugging context/actions).
- Mobile-hostile features exist too (multi-session, compound) — flagged as skip candidates.

## §2 Architecture — shared state & connections

- `IDebugService` owns sessions; views (breakpoints/callstack/variables/watch) are thin renderers over the session model — the DAP layer (`platform/debugCommon`) is the protocol boundary, contributions supply adapters.
- Editor is instrumented: gutter decorations, hover evaluate, inline values all ride editor services (B01/B04 registries).
- debugChatIntegration — debugger state is exposed to the AI layer (another chat touchpoint for B13).

## §3 What OUR app has (verified)

- **Core models + state machine** (`UniversalDebugManager.kt`): DebugSession, DebugState w/ `isValidTransition`, DebugThread/StackFrame/Variable, DebugBreakpoint, DebugFunctionBreakpoint, DebugWatch; adapter interface (`canDebug`, `launch(breakpoints, onOutput, onPaused)`, `stop`).
- **DAP adapters**: `DAPClient` + `NodeDAPAdapter` (JS/TS) + `PythonDAPAdapter` (Python) — real DAP speakers; `DebugConfiguration` for run setups; `DebuggerDependencies` (auto-install of node debug adapters?); logpoints in DAPClient.
- **Editor integration**: P54 breakpoints in EditorPane (main-instance bug fixed), `DebugEditDialogs` (breakpoint/config editing), `DebugHoverEvaluate` (hover eval), stepInto/Over/Out in adapters, paused-at-frame UI.
- **Debug console**: `DebugConsoleSection` (thin, 96 lines — output + basic interaction).
- **Android-native debug extras** (VS Code parity-IRRELEVANT but product-differentiating): LogcatPanel, AndroidRuntimeViewerDialog, ApkAnalyzerDialog, DisassemblyViewerDialog (static), DexViewerDialog, ElfViewerDialog, HexViewer.
- **No** conditional breakpoints, no hit-count, no data breakpoints, no exception breakpoints UI, no launch.json, no instruction-level stepping (disassembly viewer is static, not debug-linked), no memory viewer, no loaded-scripts view, no multi-session, no inline-values.

## §4 Verdict table

| Feature (VS Code) | Verdict | Gap |
|---|---|---|
| DAP protocol layer + adapters | HAVE | Node + Python; pluggable via DebugAdapter interface |
| Session state machine | HAVE | DebugState w/ transition validation |
| Source breakpoints (gutter) | HAVE | P54 (fixed main-instance bug #2061 area) |
| Logpoints | HAVE | in DAPClient |
| Conditional breakpoints | MISSING | no condition/hit-count anywhere (grep-verified) |
| Function breakpoints | PARTIAL | model exists (DebugFunctionBreakpoint); UI path §6 |
| Data/exception breakpoints | MISSING | — |
| Inline breakpoint editing (breakpointWidget) | PARTIAL | DebugEditDialogs exists; popover-inline vs dialog §6 |
| Step in/out/over, continue, pause, stop | HAVE | adapter-level |
| Variables + nested inspect | PARTIAL | DebugVariable model + onPaused delivery; tree/expand UI §6 |
| Watch expressions | PARTIAL | DebugWatch model; persistence §6 |
| Hover evaluate | HAVE | DebugHoverEvaluate |
| Debug console w/ ANSI + links | PARTIAL | DebugConsoleSection is thin (96 lines); ANSI/links unverified §6 |
| REPL evaluate in console | PARTIAL | evaluate wired via adapters (Node/Python) — console UX thin |
| Call stack view + frame switch | PARTIAL | frames delivered onPaused; dedicated view §6 |
| launch.json / compound configs | MISSING | DebugConfiguration is app-internal |
| Multi-session + session picker | MISSING | fine to skip on mobile |
| Instruction stepping + memory viewer | MISSING | disassembly exists but static |
| Loaded scripts view | MISSING | — |
| Chat↔debug integration (debugChatIntegration.ts) | PARTIAL | crash-log AI path exists (CrashLog entity flow); live-session chat context missing |
| Android-native extras (logcat, dex/elf/apk) | HAVE-UNIQUE | out of VS Code scope entirely |

## §5 Cross-subsystem connection edges

- debug ↔ **B11 diagnostics**: stack-trace links → open-at-line (same entry as OSC 7777 from B06); problems panel could host breakpoint errors.
- debug ↔ **B13 chat**: crash-log entity + AI analysis is our existing debug-AI edge; VS Code's debugChatIntegration adds live-session context — future I-round candidate.
- debug ↔ **B10 extensions**: adapter contributions are VS Code's extension point; ours is compile-time (two adapters) — a "Debug Adapter" registry would mirror the pivot.
- disassembly/dex/elf ↔ **B12 UI**: static viewers need the dialog pattern (scroll + rounded + padded) — same rule family.
- logcat ↔ **B06 terminal**: LogcatPanel parallels a dedicated output channel (→ B14 output channels).

## §6 Open questions / on-device verification

1. Conditional breakpoints: DAP supports `condition`/`hitCondition` — our DAPClient likely just never sends them; confirm the wire fields exist (cheap win if so).
2. Variables: is there an expandable tree in DebugEditDialogs or flat list only?
3. Watch persistence across sessions (DebugWatch stored in prefs?).
4. Debug console: ANSI rendering + clickable file:line links present?
5. DebugConfiguration: is it editable per-project (persisted) or per-launch dialog only?
6. PythonDAPAdapter: debugpy path — inside proot or host?

## Status

**DONE** — 2026-09-14. Next: B09 Language features & LSP services.
