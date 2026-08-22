# Codespace IDE — AI Agent Context

> Repo: wisdom131-max/codespace-ide-mobile
> Last updated: 2026-08-22 23:17 WAT

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
| Latest commit | (pending push) |
| CI build | (pending) |
| Active phase | R3-A: LSP Cleanup + Quick Wins (implementing) |
| Backend | Render -> https://codespace-ide-backend.onrender.com |
| Device | TECNO KL4, Android 14 |
| CodeEditor.kt lines | ~6,170 (pre-R3-I extraction) |

---

## CHANGE LOG

### [2026-08-22 23:17 WAT] — R3-A: LSP Cleanup + Quick Wins

**Model:** Base44 Superagent (Claude)

**Commit:** (pending)

**[LSP] [EDITOR] [PERF]** R3-A — 7 items implemented:

1. A1: LspCompletionHandler wired — getCompletion() and getCompletionWithMeta() now delegate param building to LspCompletionHandler.buildCompletionParams(). Removed inline positionParams() + manual completionContext building from both functions.
2. A2: LspHoverHandler wired — getHover() now delegates to LspHoverHandler.buildHoverParams(). Removed inline positionParams().
3. A3: LspSignatureHandler wired — getSignatureHelp() now delegates to LspSignatureHandler.buildSignatureHelpParams(). Removed inline positionParams().
   - All 9 LSP handler files are now referenced in LspManager.kt — zero dead handler files.
4. I3: TAB-to-accept completion — Added Key.Tab interceptor in onPreviewKeyEvent before snippet expansion. Guards: showCompletions && snippetSession == null && allCompletions.isNotEmpty(). Uses selectedLabel for tracking, falls back to first item. Records acceptance via CompletionHistoryStore.recordAccepted().
5. B2: Highlight threshold lowered — Background syntax highlight threshold changed from 500 to 200 lines (textLineCount < 200).
6. E2: Sticky scroll word-wrap guard — Sticky scroll now disabled when wordWrap is enabled (!wordWrap in condition). Line positions are unreliable in wrap mode.
7. I2: Completion show delay — Added 70ms delay after 150ms debounce in completion LaunchedEffect to reduce flicker on rapid typing.

**Files touched:**
- android/app/src/main/java/com/codespace/ide/lsp/LspManager.kt (A1, A2, A3)
- android/app/src/main/java/com/codespace/ide/editor/CodeEditor.kt (I3, B2, E2, I2)

**Next on roadmap:**
- R3-B: Search Polish (D3 cyclic nav, D4 auto-scroll, D1 preserve-case, D2 regex backrefs)
- R3-I: CodeEditor.kt extraction (5 composables -> separate files)
- R3-C: Event system + completion polish (C1 scroll event, C2 focus event, I1 loading indicator, I4 positioning modes)
- R3-D: Incremental syntax highlighting (B1 per-line, B3 hash cache)
- R3-E: LSP enhancements (A5 resolve, A4 inlay hints, E1 multi-line sticky)
- R3-F: Bracket refactor + theme color slots (G1-G4)
- R3-G: Rendering polish (H1-H4, optional)
- R3-H: Minimap polish (F1-F2, optional)
