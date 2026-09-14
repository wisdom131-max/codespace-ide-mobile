# VS CODE PARITY — FINAL CROSS-BATCH REVIEW

> Parity research, final review. 2026-09-15.
> Covers B01-B14 (all committed in `vscode-parity/`). Every verdict below traces to a batch file; every gap cites its source batch.

## §1 Executive summary

The app is NOT a thin editor — it is a **structurally faithful VS Code workbench with a different center of gravity**. Where VS Code is an extension host first, we are a **self-contained on-device workbench**: the things VS Code delegates to extensions (git, debug, languages, AI providers, preview) are our core code, and we have production surfaces VS Code has no analog for (BYOK AI stack, touch chrome, Android binary tooling, cloud backup).

Parity is **strongest where users spend time** (editor, chat, SCM, language features) and **thinnest where VS Code's architecture is host-shaped** (extension system, declarative registries, unified services). The single biggest structural gap is **B02's ContextKeyExpr/action-registry DSL**; the highest-value cheap wins cluster around **wiring existing subsystems to each other** (problems↔chat, build↔problems, SCM↔timeline).

## §2 Scoreboard (verdict rows per batch, from batch files)

| Batch | HAVE | UNIQUE | PARTIAL | MISSING | Headline |
|---|---|---|---|---|---|
| B01 Editor core | 2 | 0 | 20 | 9 | view-state persistence is a WIN; guides family thin |
| B02 Commands/keybinding | 1 | 0 | 5 | 5 | **biggest structural gap: ContextKeyExpr DSL** |
| B03 Files/workspace | 2 | 0 | 8 | 8 | tab-split rewrite solid; git badges + timeline missing |
| B04 IntelliSense | 4 | 0 | 11 | 1 | **strongest registry-parity area (lsp/ package)** |
| B05 Search/replace | 3 | 0 | 2 | 7 | multi-file search solid; replace-across-files gap |
| B06 Terminal | 3 | 1 | 5 | 7 | densest AI-integration surface; OSC 633 missing |
| B07 SCM/git | 7 | 0 | 4 | 6 | **most feature-complete subsystem** |
| B08 Debug/DAP | 6 | 1 | 8 | 6 | real DAP; conditional bps = cheapest win |
| B09 Language/LSP | 8 | 0 | 7 | 1 | **biggest positive surprise: real TextMate + two-layer model** |
| B10 Extensions | 7 | 1 | 7 | 4 | impossible-parity confirmed; MCP = our plugin boundary |
| B11 Problems/output | 5 | 2 | 4 | 4 | diagnostic model high; explorer badges double-gap |
| B12 UI/chrome | 3 | 2 | 6 | 3 | notification trio + dialogs spec HAVE; touch chrome UNIQUE |
| B13 Chat/AI | 9 | 2 | 7 | 9 | strongest parity surface; BYOK UNIQUE |
| B14 Tasks/lifecycle | 0 | 3 | 7 | 3 | task stack rich; matchers/autoSave/hub missing |

**Totals: 60 HAVE + 12 HAVE-UNIQUE vs 101 PARTIAL + 73 MISSING (+6 UNKNOWN, 6 N/A).** The PARTIAL column is the roadmap: most PARTIALs are "exists but unwired/unfinished," not "absent."

## §3 Cheap wins (consolidated — do AFTER re-tests, in this order)

1. **Problem matchers → Problems panel** (B11+B14): regex-match build-channel lines into DiagnosticManager. Closes the task→terminal→problems pipeline.
2. **Problems + SCM-history attach rows** (B07/B11/B13): 2 rows in the existing attach picker; 2 of 3 chat-context edges. Reuses existing models.
3. **Conditional breakpoints** (B08): DAP wire fields ready; UI absent. Grep-verified zero-cost-of-protocol.
4. **Output file:line links** (B11): tap-detection over open-at-line entry (already built for OSC 7777/debug/chat).
5. **Explorer problem badges** (B03/B07/B11): double-confirmed gap; most user-visible single fix.
6. **Git log → TimelinePanel merge** (B03/B07): both batches point at it; GitService.log(file) already exists.
7. **Snippet packs + declarative comment/bracket configs** (B09+B10): loadable files — first step toward B10's "extension-lite" pack.
8. **Retry button + session export** (B13): small chat-panel render parts.

## §4 Real projects (priority order, each needs a pre-plan like R6's)

1. **Action registry + ContextKeyExpr-lite** (B02): unify palette/keybindings/menus/btn enablement. Unblocks: declarative menus (B12), extension-lite contributions (B10). Biggest structural payoff.
2. **WorkingCopy hub** (B14): one dirty-state model for PendingChangesStore + editor buffers + backups + history. Architectural cleanup that R6/R7 already strained against.
3. **Inline editor-zone chat** (B13): PssEditorColumn host; mirrors inlineChat/. Needs B12 placement decision.
4. **Checkpoint timeline UI** (B13): checkpoints exist (R6); timeline rendering = chatEditing's most visible artifact.
5. **LanguageConfiguration registry** (B09): declarative per-language data; makes FileDetector generic + B03 file-type UX data-driven.
6. **Voice STT pipeline** (B13): Android SpeechRecognizer as speechToText analog.
7. **Sessions-as-tabs + todos/plan-review render parts** (B13): ride B03 tab system.
8. **Background tokenization audit** (B09): perf verify before promising huge-file parity.

## §5 Mobile-accepted divergences (do NOT build)

Extension host + marketplace (B10), multi-window/aux windows (B14), editor-group grids + movable views + auxiliarybar (B12), multi-session debug (B08), PR pill (B13), background/composite task configs (B14). In-editor review overlay → transcript card (R6 shape, B13) stays.

## §6 HAVE-UNIQUE inventory (our moat vs VS Code)

BYOK provider stack + key pool + failover (B10/B13) · touch-first chrome: MC double-tap, long-press, IME insets, portrait-adaptive (B12) · Android tooling: logcat, dex/elf/apk/disassembly viewers (B08) · SourceHealth provider states (B11) · OSC 7777 terminal→editor bridge (B06) · TerminalAiBridge transcript context (B06/B13) · Cloud backup + DownloadCenter + ProjectWizard + LivePreviewServer (B14) · 30s local history + per-apply checkpoints (B07/B14).

## §7 Cross-subsystem connection map (the "what talks to what" net)

**Shared infra hubs** (each is one entry-point that multiple subsystems already ride):
- **open-at-line entry**: chat stack links (B08) · OSC 7777 (B06) · output links when built (B11) · problems nav (B11).
- **GitDiffAnalyzer**: chat review cards (R6) · SCM diffs (B07) · undo restore.
- **lsp/ registry**: completions (B04) · symbols (B04/B13) · diagnostics (B11) · folding/hierarchy (B09).
- **SnapshotUndoManager**: editor toolbar undo (B01) · R6 apply batches (B07/B14).
- **AppOutputLog channels**: perf probe (B01/B12) · key failover (B13) · MC tripwire (B01) · build (B14).
- **FlowGate + ChatPermissionStore**: run_command (B06/B14) · MCP tools (B10) · file writes staging (R6).
- **TerminalPane**: task runner surface (B14) · agent execution (B06) · OSC bridge (B06) · transcript context (B13).
- **PERSIST-A/B view state**: editor + folds + blame + tabs (B01/B03/B12).

**Edges still unconnected** (the §3 list in graph terms): build→problems, problems→chat, SCM-history→chat, SCM→timeline, problems→explorer-decorations, output-text→open-at-line.

## §8 Open verifications (§6 backlog, grouped)

- **On-device (add to test batches)**: hot-exit unsaved-buffer restore (B14 — PERSIST-B item), background tokenization on huge files (B09), AdvancedProblemsPanel filter scope (B11), StatusBar segment inventory (B12), chat panel perf budget (B13), blame latency (B07), R10 sticky re-tests (B09).
- **Code-side (grep/inspect when coding the area)**: Zen-mode naming (B12), LspManager multi-server (B09), StdlibCompletions scope (B09), TaskRunner per-project config (B14), BuildHistoryStore persistence (B14), screenshot-attach feasibility (B13), symbols attach (B13), conflict-flow shape (B07), discard granularity (B07).

## §9 Recommended sequence after re-tests

1. Wisdom runs pending re-test batches (F1-F6 + PS + R10 + prior R-rounds) → fix fallout.
2. Cheap wins 1-8 (§3) in order — each is shippable + testable in one round.
3. Real project 1 (action registry) with an R6-style locked pre-plan.
4. Re-run affected batch verdicts; INDEX.md stays the living scoreboard.

## Status

**DONE** — 2026-09-15. Sweep complete: 14/14 batches + final review. Research phase ENDS; next phase = re-tests → cheap wins → pre-planned projects.
