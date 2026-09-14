# VS Code ↔ Codespace IDE — Parity Research Index

> Full-source sweep of microsoft/vscode vs our app, batch by batch.
> Started 2026-09-14 after Wisdom approval. Every batch: real VS Code citations
> (verified live), our-source-verified verdicts (grep, not memory), connection edges.
> One file per batch; this index tracks status + the accumulated master connection map.

## Method (fixed for all batches)

- VS Code: live listings/reads of `microsoft/vscode` source (GitHub API/raw).
- Ours: grep of `android/app/src/main/java/com/codespace/ide/` at the green commit named in the batch.
- Verdicts: HAVE / PARTIAL / MISSING + one-line gap.
- §-template: 0 scope/sources · 1 VS Code inventory · 2 VS Code architecture/state · 3 our inventory · 4 verdict table · 5 connection edges · 6 open questions · status.

## Table of contents / progress

| # | Batch | File | Status |
|---|---|---|---|
| B01 | Editor core | [B01-editor-core.md](B01-editor-core.md) | ✅ DONE 2026-09-14 |
| B02 | Command & keybinding system (palette, menus, context keys, dispatch) | [B02-command-keybinding.md](B02-command-keybinding.md) | ✅ DONE 2026-09-14 |
| B03 | File explorer & workspace (explorer, tabs/groups/split, breadcrumbs, autosave/backup, workspace trust) | [B03-files-workspace.md](B03-files-workspace.md) | ✅ DONE 2026-09-14 |
| B04 | IntelliSense (completions, snippets, hover, sig help, smart select, rename, format, code actions) | [B04-intellisense.md](B04-intellisense.md) | ✅ DONE 2026-09-14 |
| B05 | Global search & replace (search editor, replace preview) | [B05-search-replace.md](B05-search-replace.md) | ✅ DONE 2026-09-14 |
| B06 | Terminal (xterm, profiles, shell integration, persistent sessions) | [B06-terminal.md](B06-terminal.md) | ✅ DONE 2026-09-14 |
| B07 | Source control (SCM API, git, diff/merge editor, blame, timeline) | B07-source-control.md | ⬜ |
| B08 | Debugger (DAP, breakpoints, watch/repl, inline values) | B08-debugger.md | ⬜ |
| B09 | Language platform (LSP wiring, notebooks, markdown, language detection) | B09-language-platform.md | ⬜ |
| B10 | Extensions (extension host, marketplace, dependencies) | B10-extensions.md | ⬜ |
| B11 | Tasks, problems & testing | B11-tasks-problems-testing.md | ⬜ |
| B12 | UI shell & window management (panels, status bar, notifications, settings UI, themes, a11y) | B12-ui-shell.md | ⬜ |
| B13 | Chat/AI consolidation (R-rounds + I1–I6 + remaining touchpoints) | B13-chat-ai.md | ⬜ |
| B14 | Remote & lifecycle (tunnels/ports/remote host, telemetry, startup, hot exit, crash) | B14-remote-lifecycle.md | ⬜ |

## Master connection map (accumulates per batch)

Edges recorded so far (B01):
- buffer/undo ↔ chat checkpoints (SnapshotUndoManager.pushForce ⇔ VS Code SingleModelEditStack)
- cursor state ↔ completions/signature/rename overlays (EditorSelectionStore feed)
- LSP ⇄ folding/squiggles/semantic tokens/docHighlight/sticky/inlay (shared providers)
- SCM ⇄ editor decorations (blame gutter, GitDiffAnalyzer, PendingChangesStore apply)
- keybindings ⇄ core editor dispatch (KeyBindingRegistry/KeyInsertDispatcher ⇔ coreCommands.ts)
- tabs/views ⇄ editor view state (SplitViewStore/EditorViewStateEffects ⇔ editorState contrib)

Edges recorded so far (B02):
- command/keybinding dispatch ⇄ editor ops, IntelliSense actions, tab actions (KeyBindingRegistry 34-action enum ⇔ CommandsRegistry)
- palette ⇄ files + settings routing (single fuzzy mode ⇔ multi-provider quick access)
- MISSING across app: when-clause context-key DSL — VS Code's core glue for context-sensitive bindings/menus (largest structural gap found in B02)
- terminal/chat own separate key paths (⇔ VS Code per-context keybinding maps)

Edges recorded so far (B03):
- working-copy pivot MISSING: text-file-only dirty state; R6 PendingChangesStore is a bespoke mini-working-copy (biggest structural note of B03)
- explorer decorations from git MISSING in tree (user-visible gap); TimelinePanel = local-only, git merge pending (→ B07)
- hot-exit content backup MISSING = the missing half of PERSIST-A (view state restored, buffer content not) (→ B14)
- breadcrumbs symbol segment needs LSP doc symbols (→ B09)

Edges recorded so far (B04):
- lsp/ package (19 files, KLS JsonRPC) is our analog of VS Code languageFeatureRegistry pivot — strongest equivalence so far
- HAVEs: completion popup+history+refilter, hover, signature help, code actions w/ lightbulb, rename (dialog), format doc/selection
- cheap parity wins queued: on-type formatting, format-on-save (pairs w/ B03 Q2), goto references/type-def binding
- ghost text (AI) shares the Copilot/AI provider stack (→ B13)

Edges recorded so far (B05):
- BIGGEST FEATURE GAP of the sweep so far: no search view UI and no project-wide replace (engine exists as chat attachment only — SearchResultsAttach)
- UNIQUE PARITY ADVANTAGE: our search overlays R6 PendingChangesStore staged edits — VS Code cannot search unsaved AI-staged state
- AI search (AISearch.ts now in-tree VS Code): ours is inverse — search FEEDS AI; both shapes documented
- editor find/replace PARTIAL: regex+nav+replace-all HAVE, no history/case-preserve

Edges recorded so far (B06):
- terminal = our densest AI-integration surface (run_command gating, transcript attach, paste-record) — mirrors VS Code agentHost/chatTerminalCommandMirror family (→ R6/B13)
- HAVE-UNIQUE: OSC 7777 Acode-style terminal→editor open-at-line bridge
- MISSING w/ cheap-win potential: terminal find-in-buffer, OSC 633 command marks; PARTIAL: session persistence (layout only, processes die), profiles (built-in modes)
- terminal is pane-bound not tab-bound (VS Code terminalEditor) — structural divergence, likely fine on mobile

Edges recorded so far (B07):
- SCM verdict: strongest feature-completeness batch so far — 44-op GitService + 1.9k-line pane; HAVEs: full branch/stash/tag/merge/blame/diff/AI-commit(I4)
- biggest SCM gaps: quickDiff gutter indicators, git-as-timeline merge (B03 edge), explorer git badges (B03), conflict in-editor decorations
- VS Code scmHistoryChatContext.ts = SCM history as chat context — edge queued for chat attach picker (→ B13)

Edges recorded so far (B08):
- debug verdict: HAVE core (DAP Node+Python, state machine, breakpoints incl. logpoints, hover eval, stepping); MISSING cheap-win: conditional breakpoints (DAP wire fields likely ready), data/exception bps, launch.json
- debugChatIntegration.ts (VS Code chat↔debug) — our analog = CrashLog AI path; live-session chat context = future I-round (→ B13)
- Android-native debug extras (logcat/dex/elf/apk/disassembly) = HAVE-UNIQUE, out of VS Code scope

Edges recorded so far (B09):
- BIGGEST POSITIVE SURPRISE: IncrementalTmHighlighter = real TextMate syntactic layer (same tech family as VS Code) + SemanticTokensApplier = two-layer color model roughly matches
- HAVEs beyond expectation: sticky scroll (R10), call AND type hierarchy (CallHierarchyPanel), folding, bracket pairs, toggle comment
- Gaps: no declarative LanguageConfiguration registry (static per-lang data hardcoded), background tokenization behavior unknown on huge files, multi-language server support partial (KLS-first)
- comment threads + languageStatus parked until B10 extension question

Edges recorded so far (B10):
- VERDICT: impossible-parity confirmed (extension host = Node RPC process; out of scope) — batch delivered as analog map
- our ONE true pluggable boundary = MCP (mcp_* tools, permission-gated) — functionally our extension API
- second "installable" surface = R9 modes (.agent.md) + skills — declarative user files extending chat
- candidate roadmap item: "extension-lite" pack format (modes+skills+grammars+snippet packs) — declarative files, no host needed
- BYOK AI stack = HAVE-UNIQUE: 7 providers + custom endpoints + key pool are CORE for us; VS Code ships no providers at all

Edges recorded so far (B11):
- diagnostic model parity is HIGH: DiagnosticManager (4 severities incl HINT, RelatedInfo chains) + own LintChecker layer + squiggles + quick-fix HAVE
- HAVE-UNIQUE: SourceHealth states (READY/UNAVAILABLE/FAILED/STALE) — VS Code has no provider-health surface; PortsScanner ~ VS Code Ports view
- DOUBLE-CONFIRMED GAP: explorer problem badges (markersFileDecorations.ts) — closes B03's most user-visible gap
- 3rd chat-context edge found: markersChatContext.ts (after SCM history + terminal transcript) — attach-picker candidate (→ B13)
- CHEAP WIN: file:line links in Output text (open-at-line entry already exists)

## Notes

- The user's roadmap/testing protocol is unaffected: parity files are docs-only
  commits; every push still runs CI (green required, same as always).
- B01 flagged 5 open questions needing on-device verification (see its §6).
