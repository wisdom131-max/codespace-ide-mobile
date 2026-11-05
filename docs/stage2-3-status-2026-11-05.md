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
| Chip root cause | invisible same-size overlay ate every tap and down: open + drag both dead | 3569 |
| Chip second menu | language-server menu shared no focus flag; both menus on `anyMenuOpen` | 3570 |

## Remaining, in order

1. **Chip drag / chip menu — ROOT CAUSE FOUND AND FIXED 2026-11-05.** Not the drag maths, not the
   popup focus flag, not the host menu state: those were three earlier attempts at a node that never
   received input. `EditorChipButton` emitted a SECOND clickable Box of the same 36dp size (the
   visibility dot) as a sibling of the chip; the caller's `Surface` lays siblings out in a Box, so
   that control sat on top of the chip and took every down event. Tapping cycled Solid/Ghost/Hidden
   instead of opening, long-press never started a drag, and HIDDEN shrank only the glyph so the dead
   zone stayed 36dp. Fixed in 9828264 (run 3569): the dot is an indicator, cycling moved into the
   menu. Second defect on the same surface fixed in 18191f9 (run 3570): the language-server menu
   opened inside a non-focusable popup because its flag was separate from `focusable`; both menus now
   share `anyMenuOpen` on the host. Device check is now "tap opens the list, long-press picks it up".
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
   panel rule (8-12dp radius, 12dp horizontal / 10dp vertical). Two violations were found
   (`ToolActivityRow` on its own 10/6, the terminal agent strip on 6dp + 6/4). The styling change was
   committed as a020e3b and then REVERTED at the owner's request the same day (a020e3b stays the
   audit, not the change): both surfaces are back on their original values and must not be restyled
   again without an explicit ask. `ActivityGroupRow`, `ThinkingCard` and the diff card were already
   on tokens. If the four items were something specific, say which and they get done next.
6. **Install target + device package.** One target, assembled after the above, then one device round
   carrying everything (device-round batching rule).

## Standing constraints that shaped the above

- 64KB composable rule: the #16 work was moved out of CodeEditor after CI 3552 proved it
  (`Method too large: CodeEditorKt.CodeEditor(...)`).
  **CORRECTION 2026-11-05 (measured, not assumed):** moving work out of that body did not produce
  headroom, and this document previously implied it had. Measured on the `prodDebug` classes at
  commit `ed44bf0`, `CodeEditorKt.CodeEditor` was **65,453 bytes — 99.9% of the 65,535-byte method
  limit, about 82 bytes from a hard failure.** The refactors bought survivability, not room. The
  ceiling was reached again on 2026-11-05 and the overlay stack was then moved into a composable
  lambda, taking the method to **41,999 bytes (64.1%)**; the full top five is in
  `docs/editor-method-ceiling-2026-11-05.md`. Any "there is room in CodeEditor" statement anywhere
  in the docs is wrong and is retracted here.
- One bug per commit, CI verdict before the next push, no pushing on a red base.
- Research before each fix, against VS Code source, recorded in the commit message.

## Rulings recorded 2026-11-05 (advisor, after the SCM and secrets findings)

1. **Secrets in tool results: no code change.** VN Code matches VS Code here, and matching is the
   decision, not an oversight. Evidence: VS Code redacts for *logging*, not for context
   (`chatHookService.ts:45-47` names `['toolArgs','tool_input']` and `_redactForLogging` at `:71`
   applies to hook logging only); `redacted_thinking` in `anthropicMessageConverter.ts:17-21` is
   provider-side reasoning redaction and has nothing to do with secrets. There is no scrub of tool
   output entering chat context in either product. VN Code's redaction stays where it already is:
   `AttachmentSecrets.redact` (two tiers, `:99`) on the attachment send path, and the output-export
   redaction. The known consequence is stated plainly: a secret the agent reads can reach the model.
2. **SCM commit button: unchanged.** `CommitMessagePrompt.build` sends file *names* only (up to 20,
   plus "and N more"), the staging scope and a "read the diffs yourself" instruction - no diff text,
   so there is no diff cap, no binary exclusion and no redaction step to add. Compared with VS Code,
   which does send real diffs (`gitCommitMessageServiceImpl.ts:97` calls `getChangeDiffs`, `:99-101`
   refuses outright when content exclusion removed everything) and also sends branch name and recent
   commit subjects. **Noted for later, not scheduled:** adding branch name and recent subjects is
   the one parity gap worth closing, and it costs prompt budget rather than correctness.
3. **Item 10 of the investigation file is the green floating control, not the chip's menu.** Quoted
   from `VN_Code_Advisor_Full_Investigation_Prompt.txt`: "10. GREEN FLOATING EXPLORER MENU -
   Observed: The green floating Explorer menu cannot currently be dragged, opened, or dismissed as
   expected. Required: Inspect its gesture handling, z-order, hit targets, window/inset positioning,
   and state wiring..." `9828264` (run 3569) is that fix: an invisible same-size sibling Box sat over
   the chip and consumed every pointer event.
4. **Workflow identity, corrected.** `.github/workflows/android-build.yml`, display name
   "Build Android APK". There is no `build.yml` in this repository; the earlier report said there was.
