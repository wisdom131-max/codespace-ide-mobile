# B02 — COMMAND & KEYBINDING SYSTEM

> VS Code parity research, batch 2 of 14.
> VS Code: `microsoft/vscode` @ `main`, verified live 2026-09-14 (GitHub API listings).
> Ours: `codespace-ide-mobile` @ `483081c`, grep-verified.

## §0 Scope & sources inspected

VS Code: `src/vs/platform/commands/common/` (commands.ts), `platform/keybinding/common/` (keybindingsRegistry.ts, keybindingResolver.ts, abstractKeybindingService.ts, usLayoutResolvedKeybinding.ts, resolvedKeybindingItem.ts), `platform/contextkey/common/` (contextkey.ts, scanner.ts, contextkeys.ts), `platform/actions/common/` (actions.ts, actions.contribution.ts, menuService.ts), `platform/quickinput/common/` (quickInput.ts, quickAccess.ts), `workbench/contrib/quickaccess/browser/` (commandsQuickAccess.ts, viewQuickAccess.ts).

Ours: `editor/KeyBindingRegistry.kt`, `editor/KeyInsertDispatcher.kt`, `editor/EditorActions.kt`, `ui/panes/KeybindingSettingsPanel.kt`, `ui/screens/ProjectShellScreen.kt` (command palette at ~2132, MenuAction table ~441).

## §1 What VS Code has

- **Command service** (commands.ts): global `CommandsRegistry` — string-id → handler with metadata (label/category/when/precondition). Extensions, core, UI all register into ONE registry; `ICommandService.executeCommand` is universal dispatch.
- **Keybinding engine** (keybinding/*): `KeybindingsRegistry` (core + default + extension contributions, incl. keybinding overrides in keybindings.json); `keybindingResolver.ts` builds a lookup trie resolved by chord + `usLayoutResolvedKeybinding` (layout independence — S keypress on AZERTY resolves to US physical code); `abstractKeybindingService` handles chords (multi-stroke), dispatch on keydown with `when` matching; per-context bubbling (editor focused vs list vs terminal — contexts own different maps).
- **Context keys** (contextkey.ts): `ContextKeyExpr` parsed by `scanner.ts` — a boolean expression DSL (`editorTextFocus && !inputFocus`, `editorLangId == 'kotlin'`). Every `when` clause (keybinding, menu, view) evaluates against a shared `IContextKeyService` context map; `contextkeys.ts` defines common keys. This is the glue that lets the SAME keybinding mean different things per focus.
- **Actions & menus** (actions/*): `MenuRegistry` (actions.contribution.ts) — declarative menus (editor title, context menu, touch bar, status bar) contributed by id + `when`; `menuService.ts` instantiates them; `actions.ts` = Action base (id/label/tooltip/enabled/checkstate).
- **Quick input / quick access** (quickinput + quickaccess): the universal fuzzy picker widget; `commandsQuickAccess.ts` = command palette mode (`>` prefix), `viewQuickAccess.ts` = view mode; other prefixes: files, symbols, lines, tasks (each a mode over the same widget).
- User-side: keybindings.json editing + UI (`workbench/contrib/preferences`), per-OS defaults, `Keyboard Shortcuts` editor with record-key and conflict view.

## §2 Architecture — shared state & connections

- ONE registry per concept: commands, keybindings, menus, context keys. UI never hard-codes actions — it renders from the registry filtered by `when`.
- Dispatch chain: keydown → keybinding service (layout-normalized, chord-aware, context-evaluated) → command id → command service → handler; keybindings and menus share command IDs, so rebinding one rebinds both.
- Quick access modes are tiny classes (`IQuickAccessProvider`) over one picker — palette, file open, symbol search all reuse fuzzing/history/keynav.

## §3 What OUR app has (verified)

- `KeyBindingRegistry.kt` (289 lines): `KeyCombination` (ctrl/shift/alt + Key), fixed `EditorAction` enum (34 actions: FIND…GO_TO_LINE, COMMENT_TOGGLE, DUPLICATE_LINE, SELECT_WORD/LINE, UNDO/REDO, TAB_ACCEPT_COMPLETION, GO_TO_DEFINITION, QUICK_FIX, RENAME, ORGANIZE_IMPORTS, TOGGLE_WORD_WRAP, TOGGLE_INLAY_HINTS, ZOOM_IN/OUT/RESET, COMMAND_PALETTE, OPEN_FILE, CLOSE_TAB, NEXT/PREV_TAB…), defaults table, per-action rebind (`setBinding`), persist to prefs (`persistBinding/persistAll/clearPersisted`, `init`), `match(KeyEvent)` + `match(key, ctrl, shift, alt)`, `resetAllBindings`.
- `KeyInsertDispatcher.kt`: registry of per-editor text-insert handlers (dispose-safe; split views can't steal keys — #8ebfab3 class).
- `KeybindingSettingsPanel.kt` (250 lines): the user-facing shortcut editor (record/rebind/reset per action).
- Command palette: `ProjectShellScreen` ~2132 — centered VS Code-style dropdown card, focus race guarded, outside-tap bounds detection (P-CMDPAL-FIX-2), `commandQuery` fuzzy filter over ~55 `MenuAction` entries incl. "Go to File"/"Keyboard Shortcuts" routing; back-gesture close at ~1244.
- `EditorActions.kt`: multi-line indent/tab ops behind actions.
- COMMENT_TOGGLE is wired (CodeEditor:3006), ZOOM_* actions exist (B01 erratum — was marked MISSING).

## §4 Verdict table

| Feature (VS Code) | Verdict | Gap |
|---|---|---|
| Global command registry (string-id commands) | PARTIAL | enum of 34 fixed actions + MenuAction strings; no open registry extensions can extend |
| Keybinding resolution w/ chord support | PARTIAL | single-stroke only, no multi-stroke chords |
| Keyboard-layout independence (usLayoutResolvedKeybinding) | MISSING | raw Android keycodes; mobile external keyboards rare — low priority |
| When-clause context system (ContextKeyExpr) | MISSING | no context expression DSL; dispatch is focus-location ad-hoc |
| Per-context keybinding maps (editor vs terminal vs chat) | PARTIAL | KeyInsertDispatcher scopes inserts per editor; terminal/chat have own key paths but no unified model |
| Declarative menu registry (MenuRegistry) | MISSING | menus are hand-built composables; no when-gated contribution |
| Command palette (commandsQuickAccess) | PARTIAL | have centered palette + fuzzy query + file/command routing; no `>`/`@`/`#` prefix modes, no per-mode providers |
| Keybinding UI editor (record, conflicts) | PARTIAL | KeybindingSettingsPanel rebinds/resets; no conflict detection, no "when" column |
| Fuzzy finder reuse across modes | MISSING | single palette mode only |
| Command metadata (category, enabled-when) | MISSING | — |
| Keybinding persistence | HAVE | prefs-backed, survives restart, reset-all present |

## §5 Cross-subsystem connection edges

- → **B01**: actions map into editor ops (find, comment, undo…); COMMENT_TOGGLE/ZOOM corrections land in B01 errata.
- → **B04**: GO_TO_DEFINITION/QUICK_FIX/RENAME/ORGANIZE_IMPORTS actions are the dispatch entry for IntelliSense features.
- → **B03**: OPEN_FILE/CLOSE_TAB/NEXT_TAB/PREV_TAB route through the tab system; "Go to File" palette mode targets recent files.
- → **B06**: terminal owns a separate key path (writeToDisplay) — the "terminal context" VS Code models via context keys.
- → **B12**: MenuAction list + palette styling follow shell UI rules (rounded, padded, IME-aware).
- ↔ chat: ESCAPE action interacts with chat send/stop paths (mode-dependent — ad-hoc, the gap B02 flags).

## §6 Open questions / on-device verification

1. Chord demand: do any of our 34 actions realistically need multi-stroke on a touch device? (Extra-Keys row may make chords moot.)
2. Palette fuzzy ranking quality — is commandQuery match ordering good enough at 55 entries?
3. Does KeybindingSettingsPanel record Android keycodes that IME keyboards won't emit?

## Status

**DONE** — 2026-09-14. Next: B03 File explorer & workspace.
