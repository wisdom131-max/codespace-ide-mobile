# B01 — EDITOR CORE

> VS Code parity research, batch 1 of 14.
> VS Code source: `microsoft/vscode` @ `main`, verified live 2026-09-14 via GitHub API contents listings.
> Our source: `codespace-ide-mobile` @ `9e9cb6a` (#2794 GREEN), verified by grep of `android/app/src/main/java/com/codespace/ide/`.

## §0 Scope & sources inspected

VS Code paths listed/inspected:
- `src/vs/editor` (root: `browser`, `common`, `contrib`, `standalone`, `editor.api.ts`, `editor.main.ts`)
- `src/vs/editor/contrib` (full 55-entry feature listing — the inventory in §1)
- `src/vs/editor/common` + `common/model` + `common/cursor` + `common/viewModel` (core model/cursor/view)
- `src/vs/editor/browser` (`coreCommands.ts`, `view.ts`, `viewParts/`, `widget/`)
- Spot-read: `common/model/editStack.ts`, `common/model/pieceTreeTextBuffer/pieceTreeTextBuffer.ts`, `common/cursor/cursorCollection.ts`

Out of scope (→ other batches): suggest, inlineCompletions/ghost text, hover, parameterHints, rename, codeAction, codelens, format, smartSelect, snippet, gotoSymbol/gotoError/quickAccess (→ B04/B02), diff & merge editor (→ B07), workbench editor groups/tabs (→ B03), semanticTokens pipeline detail (→ B09, noted here).

## §1 What VS Code has (with citations)

**Core model & buffer** — `src/vs/editor/common/`:
- Piece-tree text buffer: `model/pieceTreeTextBuffer/pieceTreeTextBuffer.ts` (`class PieceTreeTextBuffer`) — snapshot-based, low-memory large-doc editing; `model/mirrorTextModel.ts` (worker-side mirror); `model/prefixSumComputer.ts` (line offsets), `model/intervalTree.ts` (decoration ranges, O(log n)).
- `model/textModel.ts` + `model/textModelTokens.ts` + `tokenizationTextModelPart.ts` — tokenized model; `tokenizationRegistry.ts`.
- Bracket pairs: `textModelBracketPairs.ts`; indent/bracket guides: `textModelGuides.ts` + `model/guidesTextModelPart.ts`; indent detection: `model/indentationGuesser.ts`.
- Search in model: `model/textModelSearch.ts`. Edit provenance: `model/textModelEditSource.ts`.
- Config: `common/config/editorOptions.ts` (every editor option is a typed, user-configurable setting); context keys: `editorContextKeys.ts`; core command registry: `common/commands.ts` + browser `coreCommands.ts`.

**Undo/redo** — `model/editStack.ts`: `SingleModelEditStackData` — linear per-model undo/redo stack with **cursor/selection state stored per undo element** and alternate-version IDs (`textModelEvents`/`viewEvents` restore).

**Cursors** — `common/cursor/`: `cursor.ts` (Cursor facade over view model), `cursorCollection.ts` (`class CursorCollection` — primary + N secondary cursors, each with own state), `cursorColumnSelection.ts` (true column-box selection), `cursorMoveOperations.ts`/`cursorMoveCommands.ts`, `cursorWordOperations.ts`, `cursorDeleteOperations.ts`, `cursorTypeOperations.ts`/`cursorTypeEditOperations.ts` (typing, auto-indent, electric chars), `cursorAtomicMoveOperations.ts`, `oneCursor.ts`.

**Contrib features** (`src/vs/editor/contrib/`, each = a shipped feature):
anchorSelect, bracketMatching (match pair highlight/jump), caretOperations (add above/below, move), clipboard (multiline paste/copy-down), contextmenu, cursorUndo (back out cursor moves), dnd (drag-drop text), editorState (persisted layout/scroll state), find (+decorations for all matches, find-in-selection, replace preview, multiline), floatingMenu, folding (+sectionHeaders: h1-style doc headers fold), fontZoom, gpu (GPU-rendered view via `browser/viewParts/gpuRenderer`), indentation (auto-indent + tabs/spaces detect), inlayHints, inlineProgress, insertFinalNewLine, lineSelection (expand-line), linesOperations (copy/move/delete line, sort, trim whitespace), linkedEditing (paired-tag rename), longLinesHelper (no soft-wrap on huge lines), message (editor banner messages), middleScroll, multicursor (add cursor via click, select-all-occurrences), placeholderText, readOnlyMessage, stickyScroll (`stickyScroll/` — multi-line sticky scoped headers from folding), toggleTabFocusMode, tokenization (TextMate + LSP pipeline hooks), unicodeHighlighter (ambiguous/invisible chars), unusualLineTerminators (mixed EOL detection), wordHighlighter (occurrences incl. write-access), wordOperations/wordPartOperations (word nav), zoneWidget (shared overlay host: find/peek/etc.), links (clickable URLs in text).

**View layer** — `browser/view.ts` + `browser/viewParts/*` (line numbers, current-line highlight, whitespace renderer, minimap, overview ruler, decorations, content widgets, overlay widgets, block widgets), `common/viewModel/viewModelImpl.ts` (view = projection of model incl. wrapping via `monospaceLineBreaksComputer.ts` and folds via `modelLineProjection.ts`), `stableEditorScroll.ts` (scroll stability across edits).

## §2 Architecture — shared state & connections (VS Code)

- **Single source of truth**: `ITextModel` holds text + tokens + decorations + bracket pairs; the **viewModel** (`viewModelImpl`) is a *projection* (folds, wrapping, injected decorations). All contribs read model state through services, never own copies.
- **Cursor state** is centralized in `CursorCollection` — every feature (find next, multi-cursor, column select) issues move/edit *commands* through `cursor.ts`, never mutates positions directly.
- **Decorations** are a shared registry (`intervalTree`-backed) consumed by: minimap, overview ruler, find match highlights, wordHighlighter, bracket guides, git (workbench SCM injects change/blame decorators), ErrorLens-style renderers.
- **Undo stack** is per-model and stores cursor state — chat edits (workbench `chatEditing`) snapshot the same edit stack for checkpoints (→ B13).
- **editorState contrib** persists view state per editor per group across restarts (→ B03 restores it).
- Tokenization is pluggable: TextMate (`workbench` theme service) AND LSP semantic tokens both feed the same token display pipeline (→ B09).

## §3 What OUR app has (verified against our source)

Files: `editor/CodeEditor.kt` (5,292 lines — the monolith), plus the extracted modules below (all under `com/codespace/ide/editor/` unless noted).

- Text: plain `String` buffer split to `rawLines` in CodeEditor; `EditorBufferStore.kt` (open-buffer cache, staging base for R6 chat apply); `FileCache.kt`. No piece tree, no worker mirror — whole doc in memory.
- Undo: `undo/SnapshotUndoManager.kt` — snapshot stack (`push`, `pushForce` [R6 pre-apply], `undo`, `redo`); `ToolbarUndoRedoHandler.kt` restore ordering; `SnapshotUndoInit.kt`. No per-undo cursor-state restore (cursor reset to 0 / recomputed).
- Cursors: `EditorPosition.kt`, `CursorBehaviors.kt` (word ops, selection), `CursorOverlay.kt`, `VisualLineMapper.kt`, `EditorLinePositioning.kt`, `LineWidthMeasurer.kt`, `EditShiftHelper.kt`, `HorizontalDragInterceptor.kt`.
- Multi-cursor: `MultiCursorEngine.kt`, `McEditTransaction.kt` (single chokepoint — VS Code-parity design, #2735), `MultiCursorModeStore.kt`, `McTapOverlay.kt`, `EditorExtraKeysRow.kt` chip. Fan-out mirrors primary-cursor ops; no per-cursor independent state machine.
- Find/replace: `FindReplaceBar.kt` (regex, case, word, preserve-case, backrefs, replace/all, adaptive portrait layout `9e9cb6a`), `EditorFindState.kt` (persisted). Match navigation exists; no find-in-selection, no replace-preview.
- Folding: `foldedLineIndices` in CodeEditor from `lspFoldingRanges` + manual fold gestures (line 839 area); `VisualLineMapper` projection.
- Brackets: `BracketPairConfig.kt`; auto-close/electric behavior in CodeEditor; **no bracket guides, no pair-colorization, no indent guides** (grep: none).
- Decorations: `DecorationStore.kt`, `EditorDecorations.kt`, `decorations/BlockLineOverlay.kt`, `ErrorLensOverlay.kt` (inline diagnostics), P20-A git blame gutter (`blameData`), LSP Document Highlight occurrence tint (CodeEditor:3347), diagnostic squiggles. No overview ruler, no glyph margin lanes, no current-line frame options.
- Minimap: `MinimapSection.kt` (271 lines — renders + interacts).
- Sticky scroll: single sticky header line from LSP document symbols (`stickyLine`, CodeEditor:1135), gated off during word-wrap; not multi-line, not fold-derived.
- Tokenization: full TextMate engine — `textmate/` (OnigRegexFactory, TmTokenizer, TmTheme, TmGrammarLoader, state stack) + `IncrementalTmHighlighter.kt`, `IncrementalHighlighter.kt`, `SyntaxHighlighter.kt`; semantic tokens consumed in CodeEditor/DecorationStore.
- Clipboard: cut/copy/paste in CodeEditor + `CursorBehaviors` (incl. emoji IME diagnostics); multiline paste handled in `EditorActions`/CodeEditor.
- Lines ops: `BuiltinSourceActions.kt` (duplicate/move/delete line, trim etc. — 448 lines).
- Editor state persistence: `EditorViewStateEffects.kt` (PERSIST-A: live capture on dispose, mount-restore, per-view scroll/cursor), `ViewScrollLockStore.kt` (per-view scroll locks), `SplitViewStore.kt` (≤4 views/file), `EditorFindState.kt`. This is our **editorState contrib**.
- Misc: `PerfProbe.kt` (frame/keystroke telemetry), `GotoLineBar.kt`, `PeekWidget.kt`, context menu via `EditorOverlays.kt`/`LightbulbMenuOverlay.kt`, word wrap param (`wordWrap`), whitespace render (CodeEditor), `EditorMetrics.kt`, `KeyInsertDispatcher.kt` (keyboard insert routing — our analog of `coreCommands` dispatch), `KeyBindingRegistry.kt` (289 lines — see B02).

## §4 Verdict table (HAVE / PARTIAL / MISSING)

| Feature (VS Code path) | Verdict | Gap |
|---|---|---|
| Piece-tree buffer (model/pieceTreeTextBuffer) | MISSING | plain String + rawLines; large-file memory cost (device-app acceptable, note perf headroom) |
| Undo/redo with cursor-state restore (model/editStack.ts) | PARTIAL | snapshot stack exists; cursor state not restored per undo |
| Multi-cursor core (cursor/cursorCollection.ts) | PARTIAL | fan-out mirrors primary; no independent per-cursor ops; chokepoint design matches |
| Column-box selection (cursorColumnSelection.ts) | MISSING | — |
| Add cursor above/below (caretOperations, multicursor) | PARTIAL | add via tap/double-tap + chip; not keyboard add-above/below (verify on device §6) |
| Select all occurrences (multicursor) | PARTIAL | `select_all_occurrences` programmatic path exists (CodeEditor:4080); UI reachability verify |
| Cursor undo (cursorUndo contrib) | MISSING | — |
| Find widget (contrib/find) | PARTIAL | no find-in-selection, no replace preview; occurrence decorations only via LSP docHighlight |
| Multiline find patterns | MISSING | — |
| Folding (contrib/folding) | PARTIAL | LSP + manual; no indent-based fallback, no fold-all API surface |
| Section headers (contrib/sectionHeaders) | MISSING | — |
| Bracket matching + jump (bracketMatching) | PARTIAL | BracketPairConfig; no on-type pair highlight-offscreen jump UI (verify) |
| Bracket/indent guides (textModelGuides.ts) | MISSING | — |
| Decorations registry (intervalTree, overview ruler, glyph lanes) | PARTIAL | squiggles, error lens, blame gutter, block overlay, occurrence tint; no overview ruler/glyph margin/rulers |
| Minimap (viewParts/minimap) | PARTIAL | MinimapSection renders; slider-drag/zoom parity verify |
| Sticky scroll (stickyScroll) | PARTIAL | single line from LSP symbols; VS Code = multi-line fold-derived |
| Word wrap (viewModelLines, monospaceLineBreaksComputer) | PARTIAL | boolean wrap; no word-wrap columns/indent-subsequent, no soft-wrap escape for long lines (longLinesHelper) |
| Whitespace/EOL render + unusual terminators | PARTIAL | whitespace render exists; no mixed-EOL detector (unicodeHighlighter/unusualLineTerminators both MISSING) |
| Unicode highlighter | MISSING | — |
| Lines ops (linesOperations) | PARTIAL | BuiltinSourceActions; sort-lines verify |
| Comment toggle (contrib/comment) | MISSING | no toggle-comment action found in editor/ |
| Indentation (contrib/indentation, indentationGuesser.ts) | PARTIAL | auto-indent on enter + DocumentFormatter; no indent auto-detection per file |
| Clipboard contrib (copy line down etc.) | PARTIAL | core clipboard yes; copy-line-down verify |
| Links in editor (contrib/links) | MISSING | no clickable URL detection |
| DnD text (contrib/dnd) | N/A on touch | long-press drag select exists instead (different paradigm) |
| Font zoom (contrib/fontZoom) | MISSING | global font size only |
| Placeholder text | PARTIAL | empty-state placeholder text exists |
| Read-only mode banner (readOnlyMessage) | MISSING | — |
| Editor state save/restore (contrib/editorState) | HAVE | PERSIST-A + per-view maps + find-state — exceeds VS Code scope (survives process death) |
| TextMate tokenization | HAVE | full onig-based engine with incremental re-tokenize |
| Editor options system (editorOptions.ts — typed, user-settable) | PARTIAL | FeatureToggleStore/ProjectSettingsStore cover a subset; no per-language options registry |
| GPU renderer (contrib/gpu) | N/A | Compose Canvas pipeline; PerfProbe is our frame telemetry |
| Core command dispatch (coreCommands.ts) | PARTIAL | KeyInsertDispatcher + KeyBindingRegistry (→ B02 for verdict) |

## §5 Cross-subsystem connection edges (both directions)

- editor ↔ **B04 IntelliSense**: selection/cursor feeds completions (`EditorSelectionStore.kt` publishes live selection); ghost text, signature help, rename, lightbulb all render as editor overlays (`GhostTextOverlay`, `SignatureHelpPopup`, `RenameDialogOverlay`, `LightbulbIndicator`).
- editor ↔ **B07 SCM**: blame gutter (P20-A), `GitDiffAnalyzer.kt` inline change markers, R6 `PendingChangesStore` stages edits back into the buffer; VS Code equivalent: workbench SCM injects decorations into the shared registry.
- editor ↔ **B02 command system**: `KeyBindingRegistry.kt` + `KeyInsertDispatcher.kt` route keys; VS Code `coreCommands.ts` + `editorContextKeys.ts` gate commands.
- editor ↔ **B03 files/tabs**: `SplitViewStore`/`EditorBufferStore`/`EditorViewStateEffects` are per-tab/per-view state owners; VS Code's `editorState` contrib persists under editor-group identity.
- editor ↔ **B09 language platform**: LSP feeds squiggles, folding, docHighlight, semantic tokens, document symbols (sticky), inlay hints (`InlayHintAnalyzer`), call hierarchy, lint (`LintAnalyzer`, `TestLensDetector`, `PowerUserAnalyzer` — our analogs of ErrorLens/CodeLens extensions).
- editor ↔ **B13 chat/AI**: R6 apply flow snapshots `SnapshotUndoManager` (`pushForce`) before buffer writes — the checkpoint edge VS Code wires via `SingleModelEditStack`.
- editor ↔ **B12 UI shell**: `EditorMetrics`/`PerfProbe` emit to Output; popups honor IME insets + rounded/padded UI rules.

## §6 Open questions / needs on-device verification

1. Does add-cursor-above/below exist on the extra-keys row? (grep suggests tap/double-tap only — verify chip actions.)
2. Minimap slider drag + viewport indication behavior on device.
3. Sort-lines / copy-line-down presence in BuiltinSourceActions menu.
4. Bracket-pair offscreen jump (match-pair seek) behavior.
5. Measure: does the String-buffer approach hit device memory ceilings before ~10k-line files? (PerfProbe STALL lines will now name these — `9e9cb6a`.)

## Status

**DONE** — 2026-09-14. Next: B02 Command & keybinding system.
