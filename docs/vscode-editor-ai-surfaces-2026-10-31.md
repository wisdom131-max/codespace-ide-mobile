# VS Code editor AI surfaces: research and VN Code gap report

Date: 2026-10-31. Read-only research plus the changes committed alongside it. Every VS Code claim
below names the file it came from; every VN Code claim names the file on disk. Anything I could
not verify is marked as unverified rather than asserted.

## The split that matters

VS Code connects AI to the editor in **two separate systems**, and only one of them is a tool
inventory.

1. **Agent tools.** Enumerated in `vscode-copilot-chat/src/extension/tools/common/toolNames.ts`
   (77 names, already mapped in `vscode-tool-mapping-2026-10-31.md`). These are what the model can
   *call*.
2. **Editor surfaces.** Menus, margin glyphs, ghost text, inline chat, code-block actions. These are
   contributions registered in `microsoft/vscode`, and they are **not** tools. The model never
   "calls" ghost text; the editor renders it and the user accepts it.

The gap you feel as "the AI lacks editor things" is almost entirely in system 2. Adding more agent
tools cannot close it, and that is why the editor work is its own batch.

## Verified VS Code surfaces

### Inline completions (ghost text) and next edit suggestions

`src/vs/editor/contrib/inlineCompletions/browser/inlineCompletions.contribution.ts` registers a
single editor contribution plus these actions:

```
TriggerInlineSuggestionAction, ShowNextInlineSuggestionAction, ShowPreviousInlineSuggestionAction,
AcceptNextWordOfInlineCompletion, AcceptNextLineOfInlineCompletion, AcceptInlineCompletion,
AcceptInlineCompletionAlternativeAction, ToggleInlineCompletionShowCollapsed, HideInlineCompletion,
JumpToNextInlineEdit, ToggleAlwaysShowInlineSuggestionToolbar, SnoozeInlineCompletion,
CancelSnoozeInlineCompletion, DevExtractReproSample
```

It also registers an `InlineCompletionsHoverParticipant` (a hover toolbar on the suggestion) and an
`InlineCompletionsAccessibleView`.

Two conclusions worth stating plainly:

* Ghost text and NES are **editor-level**, not agent-level. There is no `inline_completion` tool in
  the 77-name inventory, so "wire ghost text to the chat agent" is not a thing VS Code does either.
* `JumpToNextInlineEdit` is next-edit-suggestions navigation. It lives in the same contribution, so
  NES and ghost text share the accept/skip machinery.

### Inline chat

`src/vs/workbench/contrib/inlineChat/browser/inlineChatActions.ts`:

* `StartSessionAction` (`ACTION_START`, title "Open Inline Chat"), keybinding **Ctrl/Cmd+I**,
  registered into `MenuId.EditorContext` at group `1_chat`, order 3, and into
  `MenuId.ChatTitleBarMenu`.
* `MenuId.InlineChatEditorAffordance` gets an "Ask for Edits" entry with a sparkle icon, shown only
  when `EditorContextKeys.writable` and `EditorContextKeys.hasNonEmptySelection`.
* `FixDiagnosticsAction` (`inlineChat.fixDiagnostics`, icon `editSparkle`) is registered into
  `MenuId.InlineChatEditorAffordance` group `1_quickfix`, gated on
  `EditorContextKeys.selectionHasDiagnostics`.

So VS Code's editor has **three** AI entry points that a phone should also have: a keyboard/hover
"Ask for Edits" affordance on a non-empty selection, a **Fix** affordance on a selection that has
diagnostics, and the inline chat input itself anchored near the cursor.

### Code-block actions inside chat

`src/vs/workbench/contrib/chat/browser/actions/chatCodeblockActions.ts`: the menu is
`MenuId.ChatCodeBlock`; copy is a custom view item; apply-in-editor is the command
`workbench.action.chat.applyInEditor`, implemented by `ApplyCodeBlockOperation`, alongside
`InsertCodeBlockOperation` and a `reviewEdits` step. Insert and apply are different operations:
insert writes at the cursor, apply routes the edit through the editing (diff) layer.

### Breakpoints

`toolNames.ts` contains **no breakpoint tool**. Breakpoints are a debug-contribution surface
(margin glyph plus the debug API for extensions), which means VS Code's agent touches them only
through command execution, not through a dedicated tool. This is the honest answer to "does VS Code
let the agent set visual breakpoints": not as a tool, only as a side effect of running commands.

## VN Code, surface by surface

| Surface | VS Code | VN Code today | Evidence |
|---|---|---|---|
| Ghost text | editor contribution, accept word/line, next/prev, hide, snooze, hover toolbar | Present but thin: `editor/GhostTextEffect.kt`, `editor/GhostTextOverlay.kt`, wired from `editor/CodeEditor.kt`. No next/prev cycling, no accept-word, no snooze, no hover toolbar | grep: no `inlineCompletion` symbol; no next/prev command |
| Next edit suggestions | `JumpToNextInlineEdit` | Absent | no `nextEdit` symbol anywhere |
| Inline chat | Ctrl+I, selection-gated "Ask for Edits", diagnostics "Fix" | Absent. The editor has no AI entry point of its own | no `inlineChat` symbol |
| Selection to chat | implicit (inline chat gets the selection) | Absent. Selection never leaves the editor | no selection-forwarding call in `editor/` |
| Code actions / lightbulb | `editor.codeAction` menu, quickfix filter | LSP side exists (`lsp/LspCodeActionHandler.kt`, `lsp/LspIntegration.kt`) but no visible affordance in the editor UI | lightbulb references are in key dispatch, not a rendered affordance |
| Breakpoints | margin glyph + debug API | DAP request layer only (`debug/JvmDAPAdapter.kt:83` sends `setBreakpoints`); no gutter target, no AI path to it | no breakpoint UI symbol in `ui/panes` |
| Code-block actions in chat | apply / insert / copy via `MenuId.ChatCodeBlock` | Copy and Insert exist from item 11; no apply-through-diff route | `chat/ui/MarkdownView.kt` |

## What to build, and how each gets a UI

Ordered by value per unit of risk. Each entry states its UI representation, because a capability
with no affordance is invisible on a phone.

1. **Selection toolbar in the editor.** When a non-empty selection exists, show a small floating
   row above it: *Ask*, *Edit*, *Fix*. *Fix* appears only when the selection overlaps a diagnostic.
   This is VS Code's `InlineChatEditorAffordance` ported to touch. It is the single highest-value
   item: it converts the editor into an AI entry point without needing a new agent tool.
2. **Inline chat field.** Tapping *Edit* from that row opens a one-line composer anchored under the
   selection; the reply streams into a diff the user accepts or discards. VS Code anchors near the
   cursor for the same reason: the instruction and its target must be visible together.
3. **Ghost-text completion upgrades.** Add next/previous cycling, accept-word/accept-line, hide, and
   a snooze. UI: a 3-chip hover row on the ghost text itself (accept · next · hide) and a
   last-suggestion indicator in the editor status area. Without cycling, one bad suggestion is a
   dead end.
4. **Breakpoints.** Two parts: a gutter tap that toggles a breakpoint dot per line (pure UI over the
   existing `setBreakpoints`), and a `debug_breakpoints` agent tool (list/set/clear) so the chat can
   do it too. UI: the gutter dot is the affordance; the tool needs none.
5. **Apply-through-diff for chat code blocks.** Insert already exists; apply should route through
   `PendingChangesStore` so a code block becomes a reviewable proposal. UI: a third code-block chip
   next to copy and insert.

Item 1 alone changes how the editor feels, and it needs no new tool. Items 2 and 5 reuse the
approval/diff surfaces that already exist.

## Tool naming: keep `vscode_` where the tool is VS Code's

Renaming `vscode_get_confirmation` to `vncode_get_confirmation` would break nothing internally (a
single registry feeds both the prompt and the gate), but it would lose something real: the model has
seen these names during training, and unfamiliar names are used less reliably. The four surviving
`vscode_` names are exact ports, so the names are accurate rather than borrowed. Recommendation:
keep them, and reserve any future `vncode_` prefix for tools that are genuinely ours.
