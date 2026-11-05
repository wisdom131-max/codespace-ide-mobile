# Stage 2 / Stage 3 status — 2026-11-05

Working record so the remaining work has one home. Verdicts are CI run numbers; a claim without a
run id is not a claim.

## Closed, with verdicts

| Item | What changed | Run |
|---|---|---|
| Stage 1 contract (a-e) | engine/turn model, capability tri-state, retry-after-tools | 3541-3546, 3548, 3550 |
| Guard false positive | text-dialect guard no longer eats structured calls | 3545, 3547 |
| Storage repair logging | one name-only line when the transcript store recreates/retries | 3549 |
| Error UX (#8) | Retry-After both RFC 7231 forms, units in the verdict, raw markdown link gone, 402 != 429 | 3551 |
| Dead local / build | `_wrapped` removed — it crashed the Kotlin JVM back end | 3554 |
| Undo/redo mechanics | EditorUndoHost + EditorUndoBus, out of every composable | 3555 |
| Sparkle | "Refactor with AI" sends a real instruction, not `__refactor_ai__` | 3556 |
| Green chip menu | focusable popup only while open + open-state on the host | 3557 |
| SCM AI button | real handoff to the chat, prompt built from the change set | 3558 |
| SCM reply channel | answer returns to the message box, extractor + draft protection | 3559 |
| #16 completed | Edit menu Undo/Redo run the file's stack, honest answers | 3560 |
| Activity grouping | consecutive tool rows fold into one chevron row, no x; `ActivityGrouping` + 7 tests | 3562 |
| Tool discovery | `/tools` answers from `AgentTools.toolNames()` + cached MCP; VS Code research recorded | 3563 |
| Terminal dismiss x | x on FINISHED agent runs only, refuses unfinished, presentation-only | 3564 |

## Remaining, in order

1. **Chip drag.** No change made; the maths were rewritten once already and the failure has not been
   reproduced. Device round decides: does a long-press pick the chip up, and does it follow?
2. **Undo/redo redo survival.** Verified at the STACK level (`RedoSurvivalTest`, 6 tests):
   `externalContentSync` never pushes, so the sync cannot consume redo; the only things that kill
   redo are a new edit and a force push, which is VS Code semantics. Two things still need the
   device: the two-edit undo/redo sequence, and the cursor - the pane's menu undo builds its snapshot
   with `TextRange.Zero`, so an undo jumps the caret to offset 0 instead of restoring the selection
   that `EditorUndoHost` documents carrying. **Plumbed 2026-11-05** (`EditorSelectionMemory`,
   `EditorCaretReporter`, `resolveRestore`; 9 tests): the editor reports its caret per file, the pane
   builds its snapshot from it, and on apply it leaves a single-shot restore that the next content
   sync consumes. Device check is now only "does the caret come back where it was".
3. **Tool discovery.** `/tools`-style listing inside the app: what tools exist for the current mode,
   from `AgentTools.toolNames()` (already the execution registry) plus cached MCP names. No second
   list — the drift bug that produced a hand-maintained 30-name list must not come back.
4. **Activity grouping.** Consecutive tool rows should collapse into one group with a chevron and no
   `x` (owner rule). Nothing renders as a group today: tool results come through as individual rows.
5. **Stage 3 UI compliance.** NOTE (2026-11-05): nothing anywhere in the repo enumerates four
   Stage 3 UI items - the row above was carried from an earlier session and the number was never
   written down. Rather than invent four, the stage's own surfaces were audited against the standing
   panel rule (8-12dp radius, 12dp horizontal / 10dp vertical). Two violations found and fixed
   (`ToolActivityRow` on its own 10/6, the terminal agent strip on 6dp + 6/4); `ActivityGroupRow`,
   `ThinkingCard` and the diff card were already on tokens. If the four items were something
   specific, say which and they get done next.
6. **Install target + device package.** One target, assembled after the above, then one device round
   carrying everything (device-round batching rule).

## Standing constraints that shaped the above

- 64KB composable rule: the #16 work was moved out of CodeEditor after CI 3552 proved it
  (`Method too large: CodeEditorKt.CodeEditor(...)`).
- One bug per commit, CI verdict before the next push, no pushing on a red base.
- Research before each fix, against VS Code source, recorded in the commit message.
