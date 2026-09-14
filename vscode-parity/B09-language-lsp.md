# B09 — LANGUAGE FEATURES & LSP SERVICES

> VS Code parity research, batch 9 of 14.
> VS Code: `microsoft/vscode` @ `main`, verified live 2026-09-14 (GitHub API listings).
> Ours: `codespace-ide-mobile` @ `035b51b`, grep-verified.

## §0 Scope & sources inspected

VS Code: `editor/common/languages/` (languageConfiguration.ts + languageConfigurationRegistry.ts, modesRegistry.ts, supports/, autoIndent.ts, enterAction.ts, linkComputer.ts, injections/, highlights/, nullTokenize.ts), `workbench/services/textMate/browser/` (textMateTokenizationFeature*, backgroundTokenization/ + tokenizationSupport/ — ASYNC background tokenization), `editor/contrib/` confirmed: stickyScroll, folding, bracketMatching, indentation, lineSelection, unicodeHighlighter, wordHighlighter, guides; `workbench/contrib/` confirmed: callHierarchy, typeHierarchy, comments, languageStatus.

Ours: `lsp/` (JsonRpcClient, LspManager, LspServerLifecycle, LspDocumentSync, SemanticTokensApplier, DocumentSymbolCache, LspWorkspaceHandler…), `editor/IncrementalTmHighlighter.kt` + `IncrementalHighlighter.kt` (TextMate!), `SyntaxTransformation.kt`, `BracketPairConfig.kt`, `CallHierarchyPanel.kt`, sticky-scroll code (CodeEditor, DecorationStore, EditorLinePositioning — R10), folding (CodeEditor/EditorDecorations), toggle-comment (CodeEditor), `StdlibCompletions` (fallback language data).

## §1 What VS Code has (citations)

- **LanguageConfiguration** (`languages/languageConfiguration.ts` + registry): per-language declarations — comment tokens (line/block), brackets, auto-closing pairs, indentation rules, on-enter rules (`enterAction.ts`), folding markers, word patterns. Drives toggle-comment, bracket auto-close, reindent WITHOUT a server.
- **Tokenization**: TextMate grammars via extension contributions (`textMateTokenizationFeature.ts`), **background tokenization** (`backgroundTokenization/`) — the token store syncs lazily, syntactic highlight stays correct while typing on huge files; `injections/` (embedded grammars), semantic tokens overlay on top of syntactic (two-layer model).
- **Sticky scroll** (`editor/contrib/stickyScroll`): nested sticky scopes, option-state, click-to-jump.
- **Folding** (`contrib/folding`): provider-based (LSP foldingRange) + indentation + marker fallbacks; fold-all/level commands.
- **Bracket matching** (`contrib/bracketMatching` + guides): pair match, colorization, guides (bracketPair config).
- **Indentation** (`contrib/indentation`): detect + reindent, tab/space conversion.
- **Call/type hierarchy** (`contrib/callHierarchy`, `contrib/typeHierarchy`): tree + peek interaction via LSP calls.
- **Comments** (`contrib/comments`): comment THREADS (ranges + replies — GitHub-style), comment API.
- **Language status** (`contrib/languageStatus`): per-language status-bar item (server state, encodings, mode).

## §2 Architecture — shared state & connections

- TWO-LAYER color model: TextMate (syntactic, instant, background-computable) UNDERLAYS semantic tokens (LSP) — VS Code merges. This is the central tokenization fact.
- LanguageConfiguration is static data — no LSP needed for comment/bracket/indent behaviors (fast path); LSP adds folding/hover/etc.
- Hierarchy features are thin views over LSP callHierarchy/typeHierarchy requests — same JsonRPC boundary we already speak.
- comments + languageStatus are extension-surface features (→ B10 boundary).

## §3 What OUR app has (verified)

- **TextMate highlighting: HAVE** — `IncrementalTmHighlighter` (+ plain `IncrementalHighlighter` fallback). The single biggest positive surprise of the sweep: syntactic layer is the same technology VS Code uses.
- **Semantic tokens: HAVE** — `SemanticTokensApplier` over LSP (two-layer model roughly matches VS Code's shape).
- **Sticky scroll: HAVE** — R10 (CodeEditor + DecorationStore + EditorLinePositioning; re-test batch R10-1..R10-12 pending on-device).
- **Call hierarchy + type hierarchy: HAVE** — `CallHierarchyPanel` covers BOTH (grep hits both in JsonRpcClient + CodeEditor).
- **Folding: HAVE** (CodeEditor/EditorDecorations; folds persisted — PERSIST-A).
- **Bracket pairs: HAVE** — `BracketPairConfig.kt` (B01 errata: guides gap noted there — config exists, guides incomplete).
- **Toggle-comment: HAVE** (CodeEditor; B01 errata confirmed behavior).
- **Language configuration data: PARTIAL** — SyntaxTransformation + BracketPairConfig cover fragments (brackets/comments); no declarative per-language config registry; `StdlibCompletions` is a hand-rolled Kotlin-only fallback.
- **Comments (threads): MISSING** (no comment-thread concept — extension-surface feature).
- **Language status item: PARTIAL** — LSP banner exists in-editor (extracted in #2722); no per-language status surface with server state.
- **Background tokenization: UNKNOWN** — IncrementalTm is incremental per-edit; whole-view re-tokenize on huge files §6.
- **Injections (embedded grammars): UNKNOWN** §6.
- **Multiple language servers: PARTIAL** — single KLS = Kotlin-first; adapters exist for other languages? (`LspManager` single-server; StdlibCompletions compensates for non-Kotlin) §6.
- **onEnter/auto-indent rules: UNKNOWN** — autoIndent behaviors in CursorBehaviors §6.

## §4 Verdict table

| Feature (VS Code) | Verdict | Gap |
|---|---|---|
| TextMate syntactic tokenization | HAVE | same tech family (IncrementalTmHighlighter) |
| Background/async tokenization | PARTIAL | incremental per-edit; big-file + scroll-far behavior §6 |
| Grammar injections (embedded langs) | UNKNOWN | §6 |
| Semantic token overlay | HAVE | SemanticTokensApplier |
| LanguageConfiguration registry (static behaviors) | PARTIAL | fragments hardcoded; no declarative config files |
| Toggle comment | HAVE | verified |
| Bracket pairs + auto-close | HAVE | BracketPairConfig |
| Bracket guides (B01 gap) | PARTIAL | config exists; guides themselves B01-flagged |
| Folding (provider + fallbacks) | HAVE | LSP + persisted folds |
| Sticky scroll | HAVE | R10, re-test pending |
| Call hierarchy | HAVE | CallHierarchyPanel |
| Type hierarchy | HAVE | same panel |
| Indentation detect/convert | PARTIAL | tab handling exists §6 |
| Comment threads (contrib/comments) | MISSING | extension-surface; N/A until B10 |
| Language status item | PARTIAL | LSP banner; no status-item model |
| Multi-language servers | PARTIAL | Kotlin-first single server; others via fallback completions §6 |
| Word-pattern / selections | PARTIAL | lineSelection/wordHighlight equivalents partial (B04 wordHighlight verdict) |

## §5 Cross-subsystem connection edges

- tokenization ↔ **B01**: incremental TM highlighter is why big-file editing stays smooth; guides/bracket items flagged there.
- hierarchy panels ↔ **B04/B02**: call/type hierarchy rides LSP requests + palette actions; same registry pivot.
- sticky scroll ↔ **R10 re-test batch**: the only batch where parity research and a pending device test coincide — R10-1..12 verify B09's strongest claim.
- LanguageConfiguration ↔ **new-file-type support**: declarative config files (comment tokens, brackets per language) would make FileDetector's per-type handling data-driven instead of code-driven.
- comments/languageStatus ↔ **B10**: both are extension-API surfaces — parked until extension-host question is settled.

## §6 Open questions / on-device verification

1. IncrementalTmHighlighter: does a huge file (e.g. 10k-line) re-tokenize whole view on far-scroll, or true viewport incremental?
2. Embedded-grammar support (Markdown w/ code blocks? HTML?) — injections?
3. LspManager: single KLS instance only, or per-language spawning exists (Python/TS via different server)?
4. onEnter rules: does Enter inside brackets auto-indent correctly (Kotlin)?
5. CallHierarchyPanel: incoming+outgoing both, on-device latency on mid-size project?
6. StdlibCompletions scope — Kotlin-only or other languages have local fallbacks too?

## Status

**DONE** — 2026-09-14. Next: B10 Extension system (impossible-parity check + analog mapping).
