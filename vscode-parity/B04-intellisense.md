# B04 — INTELLISENSE

> VS Code parity research, batch 4 of 14.
> VS Code: `microsoft/vscode` @ `main`, verified live 2026-09-14 (GitHub API listings).
> Ours: `codespace-ide-mobile` @ `187d36c`, grep-verified.

## §0 Scope & sources inspected

VS Code: `src/vs/editor/common/languageFeatureRegistry.ts` + `languages.ts` (the feature-registry pivot), `editor/contrib/` confirmed members: `suggest`, `snippet`, `hover`, `parameterHints`, `gotoSymbol`, `rename`, `format`, `codeAction`, `inlineCompletions`, `wordHighlighter`, `smartSelect`, `inlayHints`, `documentSymbols`, `multicursor`, `gotoError`, `unicodeHighlighter`, `links`, `quickAccess`. Workbench side: snippet management (`workbench/contrib/snippets`), outline (`contrib/outline`), call/peek paths via quickAccess.

Ours: `lsp/` package (19 files, ~6.9k lines: JsonRpcClient, LspManager, LspServerLifecycle, LspDocumentSync, LspIntegration, LspCompletionHandler, LspCodeActionHandler, LspHoverHandler, LspFormattingHandler, LspSignatureHandler, LspDiagnosticsHandler, LspWorkspaceHandler, SemanticTokensApplier, DocumentSymbolCache, CompletionEngine, CompletionContextDetector, CompletionHistoryStore, SnippetEngine) + editor-side: CompletionFetchEffect.kt, CompletionPopupOverlay.kt, StdlibCompletions.kt, PathCompletionProvider.kt, SnippetChoicesPopup.kt, SignatureHelpEffect/Popup/Analyzer, GhostTextEffect/Overlay, RenameDialogOverlay, LightbulbIndicator, DocumentFormatter, FormatterConfig, DecorationStore (document highlights), WordBoundary, FeatureToggleStore, CodeEditor smart-select/expand code.

## §1 What VS Code has (citations)

- **Language feature registry** (`editor/common/languageFeatureRegistry.ts`): ALL features register as typed registries keyed by `LanguageSelector` — extensions contribute hover/completion/format/etc. per-language without core changes. This is the architectural pivot of IntelliSense: core editor knows only interfaces (`languages.ts`), features arrive via registration.
- **Suggest** (`contrib/suggest`): widget with detail/doc panes, ghost-ish preview, commit chars, `suggestMemory` (fuzzy remember recent items), resolve-provider (async doc), snippet-vs-text interplay, sortText/filterText/kind semantics, `editor.suggest` config family, tab-vs-enter acceptance, shareable model with parameterHints (no-flicker while typing args).
- **Snippets** (`contrib/snippet` + workbench manager): tabstop navigation, nested placeholders, choice popups, transform regexes, `snippetVariables`; user/workspace snippet JSON files.
- **Hover** (`contrib/hover`): markdown hover card, multiple providers merged, keyboard accessible, hover-extensions (goto-def from hover).
- **Parameter hints** (`contrib/parameterHints`): signature popup, active-parameter tracking, doc-side render, trigger chars.
- **Goto/rename** (`contrib/gotoSymbol`, `contrib/rename`): definition/declaration/implementation/type/reference peek + widget; rename preview + `prepareRename` range; results show in peek or sidebar.
- **Code actions** (`contrib/codeAction`): lightbulb + autofix, codeAction groups, quick-fix-vs-refactor menus, diagnostics-driven auto-fix-on-save option, on-save participants.
- **Format** (`contrib/format`): format doc / selection / on-type (typing brackets triggers formatRange); multiple providers with fallback.
- **Inline completions** (`contrib/inlineCompletions`): ghost text, multi-line, jump-with-Tab, providers incl. share/Copilot extensions.
- **Word highlighter** (`contrib/wordHighlighter`): documentHighlight (write/read/after semantics) + next/prev occurrence navigation.
- **Smart select** (`contrib/smartSelect`): expand/shrink selection by logical unit (word → expression → line → block …), bracket-guided.
- **Inlay hints** (`contrib/inlayHints`): hint render + click actions; **documentSymbols** feeds outline/breadcrumbs; **gotoError** = F8 problem nav; **multicursor** covered B01; **unicodeHighlighter/links** in B01.

## §2 Architecture — shared state & connections

- One registry per feature (`languageFeatureRegistry` instances) — hover/completion/etc. resolve providers by document's language + selector. Model events (`textModel`) push change→feature recompute; controllers are view-layer.
- Suggest/parameterHints/snippet share an insertion pipeline (a snippet commit inside a completion must be atomic — `SnippetSession` wraps it).
- documentHighlight/documentSymbols/sticky/inlay all ride LSP document state — same providers (→ B09 wiring).
- Quick access symbols/lines reuse the fuzzy picker (→ B02).

## §3 What OUR app has (verified)

- **LSP engine**: real client (`JsonRpcClient`, ~6.9k-line lsp/ package): document sync, hover, completion, signature, codeAction, formatting, diagnostics, semantic tokens, workspace symbols, document symbols cache, server lifecycle. KotlinLanguageServer-based (KLS).
- **Completion**: `CompletionEngine` + `LspCompletionHandler` + `CompletionFetchEffect` — freeze/refilter model + gen-watch + monotonic versions (closed 2026-09-06, on-device verified: real dot-completion works; cancellation-ID off-by-one + 5s timeout non-blocking left as-is per user decision). History/ranking via `CompletionHistoryStore`; context via `CompletionContextDetector`; `StdlibCompletions` + `PathCompletionProvider` are local fallback providers.
- **Popup**: `CompletionPopupOverlay` with refilter, snippet placeholder handling, choices popups (`SnippetChoicesPopup`, `SnippetEngine`).
- **Signature help**: full trio (Effect/Analyzer/Popup) + LSP handler.
- **Hover**: via LspHoverHandler + SHOW_HOVER action (rendered in CodeEditor; markdown-verified on device).
- **Rename**: `RenameDialogOverlay` + prepareRename flow (wired to RENAME action; workspace-wide edits via LspWorkspaceHandler).
- **Code actions**: `LspCodeActionHandler` + `LightbulbIndicator` (lightbulb indicator exists!) + QUICK_FIX action.
- **Formatting**: `DocumentFormatter` + `FormatterConfig` + `LspFormattingHandler` (FORMAT action; organize imports action exists).
- **Inline completions**: `GhostTextEffect` + `GhostTextOverlay` (feature-toggleable).
- **Document highlight**: via `DecorationStore` occurrences; **smart select/expand** in CodeEditor; **inlay hints**: TOGGLE_INLAY_HINTS action + inlay render.
- **Document symbols**: `DocumentSymbolCache` → breadcrumbs/outline panels/explorer section.

## §4 Verdict table

| Feature (VS Code) | Verdict | Gap |
|---|---|---|
| Language feature registry (languageFeatureRegistry.ts) | PARTIAL | handlers are LSP-shaped; no third-party provider registration (extension host absent → B10) |
| Completion widget + resolve + memory | HAVE | popup + history store + refilter; async resolve/doc pane quality §6 |
| Snippet engine (tabstops, transforms, choices) | PARTIAL | tabstops + choices popup verified in files; transforms/variables unknown §6 |
| User-snippet JSON files | MISSING | snippets are KLS-borne only |
| Hover | HAVE | LSP + action wired |
| Parameter hints | HAVE | full trio + handler |
| Goto def/refs/impl + peek | PARTIAL | def exists (GO_TO_DEFINITION); peek PARKED (user decision); impl/type-def/references unknown §6 |
| Rename w/ preview | PARTIAL | dialog + workspace handler; no preview-diff before apply |
| Code actions + lightbulb | HAVE | indicator + handler + QUICK_FIX; action grouping (fix vs refactor) unknown §6 |
| Format doc/selection/on-type | PARTIAL | doc + selection via handler; on-type formatting unknown §6 |
| Format on save | UNKNOWN | §6 (ties to B03 save-participant question) |
| Inline completions (ghost) | PARTIAL | ours = AI/Copilot ghost text; no provider model |
| Word highlighter + occurrence nav | PARTIAL | highlights in DecorationStore; next/prev-occurrence nav §6 |
| Smart select expand/shrink | PARTIAL | expand exists in CodeEditor; full bracket-guided shrink unknown §6 |
| Inlay hints + click actions | PARTIAL | toggle + render exist; click actions §6 |
| gotoError (F8 next problem) | PARTIAL | diagnostics exist (LspDiagnosticsHandler, squiggles); panel nav vs in-editor cycle unknown §6 |
| Completion cancellation correctness | PARTIAL | known off-by-one + timeout softness — accepted, closed 2026-09-06 |

## §5 Cross-subsystem connection edges

- LSP ↔ **B01**: same engine feeds folding, sticky, squiggles, semantic tokens, doc highlight (single server state) — our strongest equivalence to VS Code's registry pivot.
- completions ↔ **B02**: TAB_ACCEPT_COMPLETION / QUICK_FIX / RENAME / ORGANIZE_IMPORTS dispatch entries.
- documentSymbols ↔ **B03** breadcrumbs symbol segment (the PARTIAL there) and outline panel.
- ghost text ↔ **B13 chat**: AI ghost completions share the Copilot/AI provider stack (prompt-building, key pool, failover).
- diagnostics ↔ **B11 problems**: LspDiagnosticsHandler output is the problems panel source.
- format-on-save ↔ **B03** save participants (both open questions pair up).

## §6 Open questions / on-device verification

1. Snippet transforms/variables: does SnippetEngine handle `${1:var}` transforms + `$name` variables? (grep suggests tabstops+choices only)
2. Goto references/type-def/implementation reachability from editor (only def is a bound action).
3. On-type formatting (format after typing `}`) — likely absent; cheap add via LSP.
4. Format-on-save — confirm absent/present (pairs with B03 Q2).
5. Word-highlight next/prev navigation — is there an action bound?
6. Code-action grouping UI (fix vs refactor split like VS Code menus)?

## Status

**DONE** — 2026-09-14. Next: B05 Global search & replace.
