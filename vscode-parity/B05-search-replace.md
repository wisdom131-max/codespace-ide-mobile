# B05 — GLOBAL SEARCH & REPLACE

> VS Code parity research, batch 5 of 14.
> VS Code: `microsoft/vscode` @ `main`, verified live 2026-09-14 (GitHub API listings).
> Ours: `codespace-ide-mobile` @ `187d36c`, grep-verified.

## §0 Scope & sources inspected

VS Code: `workbench/contrib/search/browser/` (searchView, patternInputWidget, replace.ts + replaceService.ts, searchActions* family incl. copy/find/nav/symbol, anythingQuickAccess.ts, quickTextSearch.ts, searchAccessibilityHelp.ts, AISearch.ts — AI search mode now in-tree), `workbench/contrib/searchEditor/browser/` (searchEditor.ts, searchEditorModel.ts, searchEditorSerialization.ts), `editor/contrib/find/browser/` (findController, findState, findWidget, findWidgetSearchHistory, replaceAllCommand, replacePattern, findOptionsWidget).

Ours: `editor/FindReplaceBar.kt`, `editor/CodeEditor.kt` find paths, `chat/SearchResultsAttach.kt` (project content search — chat-attachment only), `ui/panes/ProjectFileSearchPanel.kt` (name search), `ui/panes/SymbolSearchPanel.kt`, `ui/panes/ShellHistorySearchOverlay.kt`, `editor/FileIndexer.kt`.

## §1 What VS Code has (citations)

- **Search view** (`searchView.ts` + `patternInputWidget.ts`): project-wide text search with case/word/regex toggles, include/exclude glob filters (files to include/exclude), live results tree grouped per file, collapsible matches, per-file replace-all, match count badges, multi-root aware, `search.exclude` respected; **replaceService.ts** performs preview-diff'd replace before commit; replace pattern supports capture groups (`$1`).
- **Search actions family** (`searchActions*.ts`): copy results, re-run, open-and-reveal, navigate matches, search-symbols top-bar, remove/replace individual results.
- **Search editor** (`searchEditor/`): run search INTO a virtual read-only editor — results become a document (rerunnable, shareable, editable query), serialized with results (`searchEditorSerialization.ts`).
- **Quick search** (`anythingQuickAccess.ts`, `quickTextSearch.ts`): lightweight quick-input text search over open files/recent — zero-view overhead.
- **AI search** (`AISearch.ts`): AI-mode search in the search view (new).
- **Editor find/replace** (`contrib/find`): findState machine (matches, cursor, options persisted per editor), find widget w/ history (`findWidgetSearchHistory.ts`), replace w/ `replacePattern` (capture groups, case-preserve), replaceAllCommand, findOptionsWidget, incremental highlight as you type, find-with-selection seeds.

## §2 Architecture — shared state & connections

- `searchView` consumes the `ISearchService` whose result model streams asynchronously; text search provider is pluggable (ripgrep in core; AI search is another provider).
- `replaceService` opens each file through the normal editor/working-copy machinery → replace participates in dirty/undo (one undo per replace-all — ties to B01 editStack note).
- searchEditor reuses editor infra entirely — search results ARE a text model; find widget in it searches the results themselves.
- Quick search modes ride the quick access picker (B02 reuse).

## §3 What OUR app has (verified)

- **Editor find/replace**: `FindReplaceBar.kt` — regex, match nav, replace/replace-all, portrait adaptive layout (F1, #2794); chat find bar (F2/F3, R7); per-editor state.
- **Project-wide content search**: `SearchResultsAttach.searchProjectContent` — walk ≤500 files, ≤100KB each, binary/hidden skip, ignore-case contains, `file:line: text` output ≤80 lines / 4k chars — **chat-attachment only** (`buildAttachment`, SELECTION-kind); overlays staged PendingChangesStore content (nice: searches pre-apply AI edits too). No regex, no globs, no UI pane.
- **Name search**: `ProjectFileSearchPanel` + FileIndexer. **Symbol search**: `SymbolSearchPanel` (LSP). **Shell history search**: overlay. **Chat-internal search**: R7 find-in-chat.
- **No search view UI** (no results tree/pane anywhere), no replace-across-files, no include/exclude filters, no search editor, no quick text search mode.

## §4 Verdict table

| Feature (VS Code) | Verdict | Gap |
|---|---|---|
| In-editor find/replace w/ regex + history | PARTIAL | regex + nav + replace-all HAVE (F1-fixed); no find-history, no case-preserve, capture-group replace unverified §6 |
| Project-wide search view (results tree) | MISSING | engine exists (SearchResultsAttach) — UI absent; also no async streaming, caps at 500 files |
| Regex/case/word toggles project-wide | MISSING | contains-ignoreCase only |
| Include/exclude glob filters | MISSING | — |
| Replace across files w/ preview (replaceService.ts) | MISSING | biggest single feature gap in batch |
| Replace capture groups ($1) | UNKNOWN | editor-side replacePattern equivalent §6 |
| Search editor (results as document) | MISSING | — |
| Quick text search (quickTextSearch.ts) | MISSING | — |
| AI search mode (AISearch.ts) | PARTIAL-ish | inverse direction: our search FEEDS the AI (attachment) instead of AI feeding search; different shape, both exist |
| Symbol search | HAVE | SymbolSearchPanel |
| File-name search | HAVE | ProjectFileSearchPanel + FileIndexer |
| Search results copy/export | MISSING | — |
| Search ↔ staged-edit awareness | HAVE (unique) | searchProjectContent overlays PendingChangesStore — VS Code does NOT search unsaved AI-staged state; ours does (R6 synergy) |

## §5 Cross-subsystem connection edges

- search ↔ **B13 chat**: our only project-search path IS the chat attachment (VS Code's search results are attachable to chat too — same edge, different owner).
- replace-all ↔ **B01**: editor replace-all participates in undo (verify one-undo-per-replace-all §6); project-wide replace would need the working-copy pivot noted in B03.
- search UI ↔ **B03 explorer**: results tree would be a new pane in the same shell (B12 UI rules).
- staged-aware search ↔ **R6**: PendingChangesStore overlay makes search see the AI's pending edits — documented as a genuine parity ADVANTAGE.
- quickTextSearch ↔ **B02**: would slot into the palette's fuzzy widget.

## §6 Open questions / on-device verification

1. Does editor replace support capture groups (`$1`) today? (FindReplaceBar regex mode)
2. Does editor find keep per-file search history across sessions? (VS Code persists findWidgetSearchHistory)
3. One-undo for replace-all? (undo semantics after replace-all)
4. If/when we build a search pane: streaming vs full-result-block — which fits mobile (device RAM on 500+ match files)?
5. SearchResultsAttach cap behavior: does it log a visible truncation notice when >80 matches hit?

## Status

**DONE** — 2026-09-14. Next: B06 Terminal.
