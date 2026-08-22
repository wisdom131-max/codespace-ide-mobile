# Codespace IDE — AI Agent Context

> Repo: wisdom131-max/codespace-ide-mobile
> Last updated: 2026-08-22 23:53 WAT

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
| Latest commit | daad9f09 |
| CI build | FAILED (build #2456, commit d55de045) |
| Backend | Render -> https://codespace-ide-backend.onrender.com |
| Device | TECNO KL4, Android 14 |
| CodeEditor.kt lines | 5,661 |

---

## CHANGE LOG

(empty — all old phases and changelogs purged on 2026-08-22)
