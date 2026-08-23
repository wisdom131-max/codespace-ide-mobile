# Codespace IDE — AI Agent Context

> Repo: wisdom131-max/codespace-ide-mobile
> Last updated: 2026-08-23 08:35 WAT

---

## RULES

1. TWO-REPO: Main IDE -> codespace-ide-mobile. Proot/Ubuntu/rootfs -> ubuntu-proot-test ONLY. Never push proot fixes to the main repo.
2. CHANGE LOG: After every commit, add entry at BOTTOM with timestamp, commit SHA, CI build #, what changed, files touched, next on roadmap.
3. TAGS: [BUILD-FIX], [LSP], [UI], [CRASH], [GIT], [EDITOR], [PERF], etc.
4. UI: ALL menus/popups use RoundedCornerShape(8-12.dp) + padding(horizontal=12dp, vertical=10dp) minimum. No sharp corners, no edge-to-edge.
5. NO SUB-AGENTS: Do all work directly.
6. NO RE-DO: Never re-do work already marked done.
7. KOTLIN PITFALLS (do NOT repeat):
   - Raw newlines in double-quoted strings -> use \n or triple-quoted strings
   - remember() inside if/else or LazyColumn items -> call unconditionally at top
   - Double quotes inside interpolation -> use single quotes or string templates
   - LocalContext.current inside coroutine lambdas -> capture at top of composable
8. JVM 64KB LIMIT: CodeEditor.kt and ProjectShellScreen.kt are near the limit. New UI -> separate file, single-line call. "Method too large" = always extract.
9. STRING FORMATTING: All line breaks in string literals use explicit \n.
10. Backend: Render -> https://codespace-ide-backend.onrender.com. Old Railway is dead.
11. Device: TECNO KL4, Android 14.

---

## CURRENT STATE

| Field | Value |
|---|---|
| Latest commit | 8a84afa |
| CI build | pending |
| Backend | Render -> https://codespace-ide-backend.onrender.com |
| Device | TECNO KL4, Android 14 |
| CodeEditor.kt lines | 5,740 |

---

## CHANGE LOG

(empty — all old phases and changelogs purged on 2026-08-22)

### [2026-08-23 07:15 WAT] — AI Agent: Claude, Commit e0cf91a, CI Build pending
**Fixed:** 24 R3 restructuring compile errors across 7 files that blocked builds since #2450.
**Files:** CodeEditor.kt, GotoLineBar.kt, BlockLineOverlay.kt, DiagnosticTooltip.kt, LspDiagnosticsHandler.kt, LspManager.kt, EditorPane.kt
**Details:**
- Added missing imports (DiagnosticTooltip, BlockLineOverlay, clickable, height, Language)
- Fixed EditorColors import path in BlockLineOverlay (com.codespace.ide.editor → com.codespace.ide.ui)
- Added onFindReplaceOpen/onGoToLineOpen/onSave callback params to CodeEditor, wired at all 4 call sites in EditorPane
- Fixed return@onKeyEvent → return@onPreviewKeyEvent (wrong lambda label)
- Fixed screenHeightDp → LocalConfiguration.current.screenHeightDp
- Fixed LspManager.getCodeActions: pos.line/pos.character → line/character params directly
- Added saveCurrentFile lambda in EditorPane for Ctrl+S keyboard shortcut
**Next:** Verify CI build passes, then resume LSP test fixes (Tests 60, 62-66, 70)

### [2026-08-23 08:35 WAT] — AI Agent: Claude
**RULES REMINDER:** TWO-REPO | CHANGE LOG | TAGS | CURRENT STATE | NO RE-DO | KOTLIN PITFALLS | JVM 64KB LIMIT | UI ROUNDED CORNERS + PADDING

**Commits:** c6a6bd1, 14e2cff, beb80ea, a1f8478
**CI build:** pending (all 4 commits)

**4 audit fixes implemented from the fresh 45-feature audit:**

1. **[EDITOR] Snippet Transform — c6a6bd1**
   - Implemented `applyActiveStopTransform()` in SnippetEngine.kt
   - Wired into Tab advance and Shift+Tab retreat handlers in CodeEditor.kt
   - Supports: uppercase, lowercase, capitalize, as-is transforms
   - Files: SnippetEngine.kt (+45), CodeEditor.kt (+12)

2. **[UI] Tab Indent / Shift+Tab Unindent — 14e2cff**
   - Multi-line selection: Tab indents (4 spaces), Shift+Tab unindents
   - Handles both space and tab indentation
   - Selection offsets adjusted to maintain visual range
   - Single-line Tab still falls through to snippet expansion / tab insertion
   - Files: CodeEditor.kt (+63)

3. **[LSP] Wire didSave Notification — beb80ea**
   - LspManager.didSave() was implemented but never called (same class as previous dead-handler bug)
   - Added didSave notification in both save paths in EditorPane.kt:
     - Format-on-save LaunchedEffect (after formatting + file write)
     - saveCurrentFile lambda (Ctrl+S handler)
   - Both paths now notify running LSP servers with textDocument/didSave
   - Files: EditorPane.kt (+14)

4. **[INTELLIGENSE] Completion Loading Indicator — a1f8478**
   - Added `lspCompletionLoading` derived state: smartCompletion && !lspHasResponded && !lspTimedOut
   - Shows small "Loading..." popup with 12dp CircularProgressIndicator at cursor position
   - Only appears when LSP is fetching AND no local completions available yet
   - Disappears when LSP responds, times out, or completions become available
   - Files: CodeEditor.kt (+48)

5. **[EDITOR] Smart Enter: auto-close brackets on Enter — 9f8d607**
   - When user presses Enter after unmatched opener (`{`, `[`, `(`):
     - Adds extra indent (4 spaces) on new line
     - Inserts matching closing bracket on next line at original indent level
     - Cursor left on indented line between opener and closer
   - When user presses Enter after `:` in Python: adds extra indent (4 spaces)
   - Extends extra indent to `(` (was only `{` and `[` before)
   - Files: CodeEditor.kt (+25, -3)

6. **[PERF] Incremental syntax highlighting — 8a84afa**
   - Created IncrementalHighlighter.kt (313 lines): per-line caching with bracket depth + block comment state tracking
   - On text change: only re-highlights changed lines; stops when bracket depth + comment state match cache
   - Single-char edits: O(1) — just the changed line (was O(n) full-file scan)
   - Multi-line block comments (`/* */`) handled via per-line state tracking
   - Wired into SyntaxTransformation.kt synchronous path (falls back to full highlighter when null)
   - Folded display path still uses full SyntaxHighlighter (different display string)
   - Files: IncrementalHighlighter.kt (new, 313 lines), SyntaxTransformation.kt (+12), CodeEditor.kt (+6)

**LSP Method Audit (final):**
- 74 total public methods in LspManager.kt
- 56 genuinely wired end-to-end (real callers in editor/UI code)
- 9 internal helpers (called within LspManager)
- 9 truly dead (defined but never called: getColorPresentations, getOnTypeFormatting, getLinkedEditingRanges, getMonikers, getCompletionWithMeta, getDiagnostics, clearDiagnosticsHandler, clearTrackedDocuments, getIdleTimeoutMs)
- 4 dead LSP feature methods skipped (niche, not requested)

**Next on roadmap (ALL pending items):**
- Format-on-save wiring verification (verify ProjectSettingsStore.formatOnSaveEnabled is correct)
- Pinch-to-zoom for editor font size
- Word boundary detection for double-click select
- Configurable keybindings
- Extensible/pluggable bracket pairs (per-language rules)
- Wire 4 dead LSP feature methods (getColorPresentations, getOnTypeFormatting, getLinkedEditingRanges, getMonikers) — deferred, niche
- Remaining LSP test fixes (Tests 60, 62-66, 70)
