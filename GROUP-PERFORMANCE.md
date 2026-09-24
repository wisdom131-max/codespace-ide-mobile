# Performance: fresh source-only group audit

**State:** app source read; no device test run. `READ A/path:N` is 1-based in `android/app/src/main/java/com/codespace/ide/`, `READ T/path:N` in `android/app/src/main/java/com/termux/`, `READ V/path:N` in `vscode-src/` (repo root `src/vs/`). This group owns performance MECHANISMS: the PerfProbe measurement instrumentation (`editor/PerfProbe.kt`), the device-level monitors (`diagnostics/PerformanceMonitor.kt` — MemoryMonitor/CodeMetrics/SyncStatusMonitor), LspManager's per-server RSS watchdog (`lsp/LspManager.kt:2189-2210`), the status-bar RAM poll, every recurring poller/debounce/cap in the UI layer, and the startup path's known main-thread history (`MainActivity.kt:205-230`). Cost findings filed by OTHER groups (TP08 transcript rescan, CH13 session blob, XG14 search list, SG07 proot spawns, DG05 DEBUG-ANR, G01 write path) are boundaries this group RANKS, not re-audits. Nothing here is a device outcome or a code-fix authorization.

## Feature inventory (performance-relevant mechanisms as implemented)

| ID | Source-traced feature |
|---|---|
| PF01 | PerfProbe measure-first instrumentation (approved 2026-09-06): keystroke→render latency (onEdit at onValueChange → onTextLaidOut at text layout, logged per-edit > 8 ms), frame health via frame-clock observer (32 ms gap = jank, dropped≈ calculation), 5 s summary lines to the Output channel, >5 s STALL logged IMMEDIATELY with wall-clock time for correlation, PERF-IDLE quiet windows (2026-09-14: no narration during idle). READ A/editor/PerfProbe.kt:25-110; A/editor/CodeEditor.kt:1673-1676,2319,2595. |
| PF02 | Frame-clock observer: `while (true) { withFrameNanos { PerfProbe.onFrame } }` inside CodeEditor — one per editor instance, runs for the editor's whole lifetime. READ A/editor/CodeEditor.kt:1673-1676. |
| PF03 | MemoryMonitor: /proc/meminfo parse (MemTotal/MemAvailable) with Runtime fallback; usedMb/usagePercent/isLowRam (<100 MB available). READ A/diagnostics/PerformanceMonitor.kt:16-38. |
| PF04 | Status-bar RAM poll: MemoryMonitor.getMemInfo() every 5 s in a while(true) LaunchedEffect; isLowRam turns the indicator red. READ A/ui/screens/ProjectShellScreen.kt:4350-4360. |
| PF05 | LSP server RSS watchdog: single-thread scheduled executor, first check 5 s then every 10 s, reads per-PID memory snapshots for every live server, MEMORY lifecycle log lines when state ≠ NORMAL (VmRSS/VmPeak), executor recreated after teardown (CRASH-FIX comment). READ A/lsp/LspManager.kt:2189-2210. |
| PF06 | Startup crash-log path fixed off the main thread: crash-log upload used to run as a blocking network call in onCreate (NetworkOnMainThreadException + 6 s timeout ANR = a "app opens then closes" contributor); now local read for the dialog + background Thread upload, files kept on failure. READ A/MainActivity.kt:205-230. |
| PF07 | MCP discovery is process-once: discoveryStarted volatile guard, started at first need, reset only on explicit teardown — no repeated discovery passes. READ A/agent/McpClientManager.kt:77,125,259-260. |
| PF08 | Gutter virtualization: gutter previously rendered ALL lines as composables — OOM and jank on 1000+ line files; now virtualized to visible lines. READ A/editor/CodeEditor.kt:2071-2076. |
| PF09 | Syntax precompute offload: files ≥ 200 lines get highlight computed on Dispatchers.Default after a 100 ms delay, cached in precomputedForText (AnnotatedString reused until text changes); < 200-line files highlight inline. READ A/editor/CodeEditor.kt:2290-2300. |
| PF10 | O(log n) line index: newline offsets precomputed per text change (C-5 FIX) replacing per-keystroke O(n) scans for completion/signature-help/hover/diagnostics. READ A/editor/CodeEditor.kt:1124-1130. |
| PF11 | Editor file search on a background thread with 300 ms debounce (R1-2, prevents jank on large files). READ A/editor/CodeEditor.kt:1954-1956. |
| PF12 | Completion pipeline debounces: 150 ms completion debounce + 70 ms show delay (documented headroom vs other effects); hover, document-highlight, document-symbol all debounced. READ A/ui/panes/EditorPane.kt:1467-1483,1580,1655,1741,1815. |
| PF13 | Ghost-text 800 ms debounce on completion context change. READ A/editor/CodeEditor.kt:1530-1545. |
| PF14 | Highlight timers: blink tick every 150 ms for up to 6 s (blinkTick++ recomposition driver), gold-band auto-dismiss at 5 s, scroll-to-line retry loop 50 ms × 20 attempts, jump cleanup at 2.5 s. READ A/editor/CodeEditor.kt:757-773,1105-1120,4524-4527. |
| PF15 | LSP recovery watcher: while(true) 2 s poll of LspManager.lspRecoveryCounter per editor instance to reset completion-fallback flags when a server recovers. READ A/editor/CodeEditor.kt:1322-1335. |
| PF16 | Snapshot autosave loop: every 20 s, walkTopDown over ALL workspace roots, filter to files modified < 5 min, < 1 MB, capped take(20) copies into .versionhistory + grouped trim. READ A/ui/panes/ExplorerPane.kt:1502-1532. |
| PF17 | execOnce output discipline: MAX_LINES 2000 cap, concurrent 64 KB drain thread (documented pipe-buffer deadlock fix), process-exposed cancel. READ A/terminal/ProotInstaller.kt:1356-1420,1399-1406. |
| PF18 | NotificationStore burst protection (global limit across sources per 10 s window) + 50-item serialized history cap. READ A/data/NotificationStore.kt:552-575,275-283. |
| PF19 | Chat session persistence: whole-sessions blob serialized per persist, last-50 sessions (CH13's write-amplification input, ranked here). READ A/ui/screens/CopilotChatPanelOverlay.kt:172-193,976-983. |
| PF20 | Image attach streaming: 64 KB buffer stream copy with 5 MB hard cap (no full-bitmap decode of oversized picks at copy time). READ A/chat/ChatImageAttachments.kt:105-125. |

## Cross-group connection ledger

| Edge | Producer -> consumer and seam |
|---|---|
| C-PG01 | Terminal TP08 (ranked here): URL chip loop re-reads the ENTIRE transcript every 2 s for the active tab and runs the regex per pass — the terminal's own finding is this group's #1 recurring-allocation cost. READ A/ui/panes/TerminalPane.kt:1162-1179. |
| C-PG02 | Chat CH13 (ranked here): saveSessions serializes every session on every persist — UI-thread prefs apply per send/rating/command. READ A/ui/screens/CopilotChatPanelOverlay.kt:172-193,976-983. |
| C-PG03 | Extensions XG11/XG14 (ranked here): unbounded apt-cache results into one LazyColumn; panel-lifecycle op-state loss forces re-polls. READ A/ui/panes/PackageManagerPane.kt:193-229. |
| C-PG04 | SCM SG07 (ranked here): one loadStatus = 7 fresh proot spawns; every SCM refresh pays ~1-2 s each (TT11 measure). READ A/scm/ScmState.kt. |
| C-PG05 | Debugger DG05 (ranked here): restart + sidebar Debug button still spawn proot on the MAIN thread — the ANR class this group owns; DG05's fix list covers 2 callers, these 2 remain. |
| C-PG06 | LSP: the RSS watchdog (PF05) and the 2 s recovery poller (PF15) are the same subsystem's two halves — the watchdog detects bloat, the poller un-flags completions; restart backoff (RestartBackoff, LspManager:102-114) caps the restart storm. READ A/lsp/LspManager.kt:102-114. |
| C-PG07 | Problems/Build: PR03's MAX_LINES=2000 swallow is execOnce's memory cap (PF17) seen from the correctness side — the cap is a real perf feature that costs build-marker fidelity; the typed ProotResult design must carry a `truncated` flag so both sides get truth. READ A/terminal/ProotInstaller.kt:1399-1406. |
| C-PG08 | Editor G01 boundary: the write path's correctness gaps (silent failure per edit, dirty marking) are owned by the Editor group; this group owns ONLY the write path's cost profile (autosave cadence vs snapshot loop overlap — both copy full file content). |
| C-PG09 | Tabs TB02 (ranked here): rapid tab actions racing ShellState's dual writers produce recomposition churn on top of the state-corruption bug — stress tests for TB02 double as PG01 evidence. |
| C-PG10 | PerfProbe is the shared measurement substrate for every other group's device checks: TT11 (execOnce timing), TT30 (200-line burst), DT-class jank, XC-class list scrolls — all record via [perf] lines in the Output tab. READ A/editor/PerfProbe.kt:1-45. |

## VS Code clone comparison

| Area | Verified VS Code owner | Android contrast |
|---|---|---|
| Perf marks / startup telemetry | READ V/src/vs/base/common/performance.ts:14,75-76. Global mark()/measure() timeline wired into startup (marks per phase), perf_hooks reused in browser context; workbench startup renders a breakdown view. | READ A/editor/PerfProbe.kt:25-110. Measurement exists but ONLY inside the editor surface (keystroke + frames); no startup marks, no phase timeline — the app cannot answer "what does open cost?" today. PG13. |
| Text buffer efficiency | READ V/src/vs/editor/common/model/pieceTreeTextBuffer/pieceTreeBase.ts:268. PieceTreeBase: O(log n) edits on a red-black tree of text pieces — no full-string rebuild on keystroke. | READ A/editor/CodeEditor.kt:1124-1130. Value.text is one immutable String; every keystroke rebuilds the whole text + recomputes newline offsets (remember(value.text)) and precomputed highlight — O(file size) per edit on large files; the C-5 offsets fix removed the per-keystroke SCAN but not the per-keystroke REBUILD. |
| Render layer | READ V/src/vs/editor/browser/view/viewLayer.ts:6,267,382. ViewLayerRenderer draws ONLY visible lines through a fixed pool of FastDomNodes — DOM node count is O(viewport), not O(file). | READ A/editor/CodeEditor.kt:2071-2076. Gutter matches this (virtualized after the OOM lesson); the text body is a single BasicTextField whose layout cost rides Compose text layout — verified frame-level by PerfProbe, not architecturally viewport-bounded. |
| Idle scheduling | READ V/src/vs/base/common/async.ts:1533. runWhenIdle defers discretionary work to browser idle windows with timeout fallback. | No idle-class scheduler exists; the app's equivalents are fixed-delay polls (2 s/5 s/10 s/20 s) that run regardless of UI state. |
| Search out-of-process | READ V/src/vs/workbench/services/search/node/ripgrepFileSearch.ts (with rawSearchService.ts): search runs ripgrep in a SEPARATE process pool — the UI thread never parses results and a runaway search can't freeze it. | READ A/editor/CodeEditor.kt:1954-1956. In-app file search is background-thread + debounced (right instinct) but in-process — a pathological directory still competes for GC; project-wide search (SR group) reuses the same pattern with caps. |
| Extension host profiling | READ V/src/vs/workbench/services/extensions/electron-browser/extensionHostProfiler.ts:14. ExtensionHostProfiler samples extension code with the inspector — attributable, per-extension CPU blame. | READ A/editor/PerfProbe.kt:25-110. Whole-surface frame/keystroke counters only — no attribution to feature, coroutine, or subsystem; a jank source can only be found by correlation. PG13. |
| Terminal renderer | READ V/src/vs/workbench/contrib/terminal/browser/xterm/xtermTerminal.ts:117. xterm.js with canvas/webgl renderers — GPU-accelerated glyph blitting. | READ T/terminal/TerminalEmulator.java:1-100. Vendored termux Canvas view — CPU-rendered but proven on-device; the 2 s chip rescan (TP08) is the actual terminal perf cost, not the renderer. |
| List virtualization | READ V/src/vs/base/browser/ui/list/listView.ts:298. ListView virtualizes with anchored range rendering — million-row lists scroll at O(viewport). | The app's LazyColumn usage is virtual and keyed (Browse/Installed/History, chat list) — the RIGHT pattern; the gap is unbounded SOURCE lists (XG14 apt-cache, search results before caps), not the list control itself. |

## Fresh scratch-only device checks, NONE RUN

Record PASS/FAIL/NOT RUN with reason; most record via Output-tab `[perf]` lines (PF01 substrate, C-PG10). Use a disposable project and a large real file (1000+ lines).

| Test | Action -> pass criterion and diagnostic |
|---|---|
| PM01 PF01 | Open a 1000+ line file, type ~30 s, scroll top-to-bottom, open completion popup. Record every [perf] line — keystroke maxLatency, 5 s frame summaries, any STALL. This is the baseline this group ranks against. |
| PM02 PF02 | During PM01, watch for 5 s summaries during IDLE windows — probe must go quiet (PERF-IDLE), not narrate zeros forever. |
| PM03 PF14 | Trigger a gold-band jump (ide open file:42) and observe [perf] lines: the 6 s blink window should NOT show jank storms; record worst frame gap during blink vs after. |
| PM04 PG01 | Same file, PM03 comparison: does worstFrame climb during blink (150 ms tick recompositions) on the 3 GB device? Record. |
| PM05 PF09 | Toggle between a < 200-line file and a ≥ 200-line file, edit both. Record keystroke latencies for both classes (precompute path vs inline path). |
| PM06 PG02 | Open SPLIT editors on the same file, then two different files. Record [perf] summaries + logcat: do two LSP recovery pollers + two frame observers coexist without additive jank? |
| PM07 PF05 | Start 2 language servers, then in the guest `top` — every ~10 s each server's /proc/<pid> read happens; verify MEMORY log lines appear when a server is forced to bloat (open a huge project) and no extra churn when NORMAL. |
| PM08 PG03 | With 100+ modified files in a project, watch the snapshot loop: time one walkTopDown cycle (add a temporary marker or watch IO via logcat); record whether the 20 s cadence ever overlaps itself. |
| PM09 PG03 | Multiple panel mounts: open/close Explorer quickly 3 times; confirm only ONE snapshot loop is walking (dispose cancels the old LaunchedEffect). |
| PM10 PG04 | Terminal: fill scrollback with 3000 lines, wait 10 s with the tab open. Record [perf] or logcat evidence of the 2 s transcript rescan cost climbing with transcript size (TP08 evidence at the performance layer). |
| PM11 PG04 | Same tab, minimize 10 s, return: does the chip loop resume/rescan the FULL transcript immediately? Record. |
| PM12 PG05 | Extensions tab open 60 s with Agent API DOWN: record poll behavior (1 s timeouts × every 5 s) — any battery/CPU hint in logcat; then API up: dot flips green ≤ 5 s. |
| PM13 PF04 | Status bar RAM poll: open a big file + terminal + chat; record usedMb/totalMb across 60 s and whether the red low-RAM state ever triggers on this 3 GB device. |
| PM14 PG06 | Cold start timing (NOT measured by the app — stopwatch): record app-icon→editor-visible on (a) fresh install, (b) warm reopen, (c) reopen after a crash with crash-logs present (PF06 path — dialog must appear instantly, upload in background). |
| PM15 PG13 | After PM14: check Output tab — is there ANY startup-phase timing logged? Expected NO (measurement gap evidence). |
| PM16 CH13 ref | Chat: send 10 quick messages; watch for frame jank at each send on a long session list (write amplification). Record worst frame gap. |
| PM17 XG14 ref | apt-cache search a broad term ("lib"), record list length + scroll smoothness with [perf] running. |
| PM18 SG07 ref | Source Control refresh with 20 dirty files; stopwatch the status load; [perf] STALL line expected during the 7 proot spawns if main thread blocks. |
| PM19 DG05 ref | Sidebar Debug button on a Kotlin project: stopwatch; expect ANR-class stall (main-thread proot spawn) — record exact stall from [perf] STALL line. |
| PM20 PF16 | Snapshot loop correctness-perf: edit 3 files rapidly, wait 20 s; verify exactly ≤ 20 .bak copies made and .versionhistory trims to newest-20 per group (no unbounded disk growth). |
| PM21 PF16 | Edit a 5 MB file (> 1 MB cap) — verify it is SKIPPED by the snapshot loop (cap working) and record that G01's write path is its only recovery. |
| PM22 PF18 | Burst: trigger 30 notifications in 10 s from one source (git spam) — record how many are dropped by burst protection vs shown. |
| PM23 PF18 | Fill notification history to 50, verify the store re-serializes without growing (cap) — check prefs size before/after. |
| PM24 PG07 | Notification settings screen: flip every toggle rapidly; record any frame jank during the burst of serializeHistory+apply calls. |
| PM25 PG09 | ProjectShellScreen recomposition probe: with [perf] running, rapid-toggle bottom panel tabs 10×; record worst frame gap (wide-scope recomposition evidence). |
| PM26 TB02 ref | Rapid tab open/close ×20 (the TB02 stress): record [perf] worst frame + whether ShellState corruption and jank co-occur. |
| PM27 PF12 | Completion debounce behavior: type fast in a .ts file; record that requests fire ~220 ms after last keystroke (not per keystroke) via Output lines. |
| PM28 PF13 | Ghost text: type a completing prefix, wait 800 ms; ghost appears once, no flicker loop. |
| PM29 PF11 | In-editor search on the 1000+ line file: type a query, verify results land after 300 ms debounce on a background thread with no frame gaps during search. |
| PM30 PF17 | `seq 1 200000` in the terminal: verify the 2000-line cap holds (memory flat), then run again via chat run_command and compare. |
| PM31 PG12 | PM03 + go-to-line: record total recomposition churn during overlapping blink/auto-dismiss/jump-cleanup timers — any visible flicker on low-end? |
| PM32 PG08 | Explorer on a 2000-file project: initial tree render + first expand of a 500-file dir; record worst frame gap and whether expansion is lazy. |
| PM33 PG10 | Open the app, immediately open chat, run one command: verify MCP discovery runs ONCE (Output/logcat) — no repeated discovery passes on panel opens. |
| PM34 PG11 | During a long apt install (op strip appending), observe the output strip: autoscroll keeps up (scroll-to-max per append) with no frame-gap growth; record. |
| PM35 PF20 | Attach a 4.9 MB image and a 5.1 MB image: first streams fine, second rejected at the 5 MB cap without an OOM attempt; record memory during the 4.9 MB copy. |
| PM36 PF06 | With crash-logs present, cold start: dialog shows instantly (no 6 s block), background upload completes (logs deleted); then crash-logs ABSENT: identical start timing (no empty-path cost). |

## Source-confirmed gaps for master fix-plan input

| Gap | Priority | Bounded source finding |
|---|---|---|
| PG01 | HIGH in group | Recomposition-surface discipline: blinkTick++ (PF14) recomposes the whole 5270-line CodeEditor scope every 150 ms for up to 6 s per jump (CodeEditor:757-764); EditorPane carries ~70 LaunchedEffects (grep count) and CodeEditor ~56 in two files that the 64 KB rule already strains. No derivedStateOf/read-state-narrowing pattern for the blink/scroll indicators is in evidence. PM03/PM04 measure it. |
| PG02 | HIGH in group | The LSP recovery watcher (PF15) is a per-editor-instance while(true) 2 s poll that never pauses — with split editors it multiplies (PM06); it could be a StateFlow subscription on LspManager instead of polling. READ A/editor/CodeEditor.kt:1322-1335. |
| PG03 | MEDIUM-HIGH | The 20 s snapshot loop (PF16) walks EVERY file of EVERY workspace root (stat per entry via walkTopDown) before the filter discards most — O(project files) disk I/O every 20 s on a 3 GB device, per mounted ExplorerPane; no cheap change-detection gate (e.g. root lastModified) before the full walk. READ A/ui/panes/ExplorerPane.kt:1502-1532. PM08/PM09. |
| PG04 | MEDIUM | TP08 confirmed at this layer (C-PG01): full-transcript String re-read + regex every 2 s, cost grows linearly with scrollback (4000-row transcript); gate on transcript length change first. READ A/ui/panes/TerminalPane.kt:1162-1179. PM10/PM11. |
| PG05 | MEDIUM | McpPanel 5 s poll does a network connect + full `.agent.json` readText + full `.bashrc` readText every pass while the panel is visible (PF16 of Extensions; PackageManagerPane:459-489) — three I/O ops per 5 s for a status dot; also TP02's surface churner. PM12. |
| PG06 | MEDIUM | CH13 (ranked here): whole-sessions blob serialize + prefs apply per chat send/rating/command on the UI path. PM16. |
| PG07 | LOW-MEDIUM | NotificationStore settings writes serialize a 50-item history JSON inline with each apply burst (PF18's other side); burst-protected but not batched. PM24. |
| PG08 | LOW-MEDIUM | Explorer tree perf unaudited at depth: 3866-line ExplorerPane renders trees with per-entry composables; expansion laziness + first-render cost on big projects unmeasured (PM32), feeds the fix plan only as a measurement item. |
| PG09 | LOW-MEDIUM | ProjectShellScreen is a 5254-line single screen with ~24 LaunchedEffects and nested helper scopes (PssBottomPanelContent/PssEditorColumn) — wide recomposition scopes are structurally likely (PM25 evidence item, not a code-change authorization). |
| PG10 | LOW | Startup cost profile is invisible: no marks, no phases (PG13's other half); MCP discovery is correctly process-once (PF07) but nothing reports how long the remaining init chain takes. PM14/PM15/PM33. |
| PG11 | LOW | Op-strip autoscroll sets rememberScrollState(Int.MAX_VALUE) and appends render last-60 lines each pass — minor per-append cost during long installs (PM34). |
| PG12 | LOW | Three overlapping highlight timers (blink 6 s, auto-dismiss 5 s, jump cleanup 2.5 s) all write the same highlight state — churn, not correctness. PM31. |
| PG13 | MEDIUM, measurement | PerfProbe covers only editor keystroke/frames; no startup marks (VS Code performance.ts:14 analog), no subsystem attribution (VS Code extensionHostProfiler.ts:14 analog) — the fix plan cannot rank costs it cannot see. Deliberate measure-first scope (2026-09-06) — this gap is the extension of that pass, not a violation of it. PM14/PM15. |
| PG14 | LOW | MemoryMonitor.isLowRam has no degradation consumer (status bar color only, PF04) — no mode that sheds the precompute/poll costs when RAM is actually low. PM13. |

## Boundary

Editor owns the write path's correctness (G01); Terminal owns execOnce semantics (TP03) — C-PG07 only adds the `truncated` requirement; Tabs owns TB02's corruption, this group owns its jank twin; Chat owns CH13's fix design, this group ranks its cost; Extensions owns XG11/XG14, Debugger owns DG05's caller list; LSP owns the watchdog's restart policy (LS group) while this group owns its cadence cost. PerfProbe was an approved measure-first pass (2026-09-06) — PM01-PM36 use it, none authorizes an optimization; on a 3 GB device every "HIGH in group" here is bounded by measurement first (PM rows), exactly as the PERF-PROBE comment prescribes.

## Note on strengths worth keeping in the plan

The measurement instinct is the app's biggest perf asset: PerfProbe (with STALL correlation, idle quieting, keystroke→render latency), the LSP RSS watchdog with lifecycle logs, MemoryMonitor with fallback, the gutter-virtualization OOM lesson, the C-5 O(log n) offsets fix, and the startup main-thread fix with its documented ANR history are all VS-Code-shaped instincts (measure → attribute → fix) already proven on-device. The recurring weakness is fixed-cadence polling where a signal (StateFlow/event) would do: PG02/PG04/PG05 are three instances of the same pattern.
