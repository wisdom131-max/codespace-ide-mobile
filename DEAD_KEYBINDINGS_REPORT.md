# Dead Keybindings Report — PLAN ONLY, no code, not bundled into RP1

2026-09-19. Context: editor/KeyBindingRegistry.kt declares 39 EditorAction
ids (line 39+), all listed as bindable in Settings
(ui/panes/KeybindingSettingsPanel.kt). Grep evidence: OUTSIDE
KeyBindingRegistry.kt and KeybindingSettingsPanel.kt, the ENTIRE codebase
references EditorAction exactly 10 times, all in editor/CodeEditor.kt's single
dispatch site (KeyBindingRegistry.match -> when(kbAction), ~line 2936):

    grep -rn "EditorAction\." --include=*.kt . \
      | grep -v "editor/KeyBindingRegistry.kt|KeybindingSettingsPanel.kt" \
      | grep -o "EditorAction\.[A-Z_]*" | sort | uniq -c
    -> 10 hits, one each: UNDO, SAVE, REDO, MOVE_LINE_UP, MOVE_LINE_DOWN,
       GO_TO_LINE, FIND, DUPLICATE_LINE, DELETE_LINE, COMMENT_TOGGLE

The remaining 29 have ZERO dispatch references anywhere: they are bindable in
Settings and persisted, but pressing the chord does NOTHING. Default chords
below from KeyBindingRegistry.registerDefaults() (lines ~90-140).

## The 29 dead bindings

| # | Action | Default chord | Feature exists elsewhere? |
|---|--------|---------------|--------------------------|
| 1 | FIND_NEXT | F3 | YES — find bar next button (EditorPane find bar) |
| 2 | FIND_PREVIOUS | Shift+F3 | YES — find bar prev button |
| 3 | FORMAT | Ctrl+Shift+I | YES — editor format action (menu/bulb) |
| 4 | INDENT | Tab | YES — built into CodeEditor typing path |
| 5 | UNINDENT | Shift+Tab | YES — built into CodeEditor typing path |
| 6 | SELECT_ALL | Ctrl+A | YES — editor selection code |
| 7 | SELECT_WORD | (no default) | UNKNOWN — GUESS: selection code exists, no UI found by this grep |
| 8 | SELECT_LINE | (no default) | UNKNOWN — same guess class |
| 9 | COPY | Ctrl+C | PARTIAL — system IME handles some copy; no in-app dispatch |
| 10 | PASTE | Ctrl+V | PARTIAL — same as COPY |
| 11 | CUT | Ctrl+X | PARTIAL — same as COPY |
| 12 | TAB_ACCEPT_COMPLETION | Tab | YES — completion accept exists in CodeEditor (own key path, not via registry) |
| 13 | ESCAPE | (no default) | YES — dismiss paths exist (own handlers, not via registry) |
| 14 | SMART_ENTER | Enter | YES — auto-indent enter exists in CodeEditor (own path) |
| 15 | GO_TO_DEFINITION | F12 | YES — long-press/LSP go-to-def exists (not via registry) |
| 16 | SHOW_HOVER | Ctrl+K | YES — hover/long-press exists (not via registry) |
| 17 | QUICK_FIX | Ctrl+. | YES — lightbulb/quick-fix exists (not via registry) |
| 18 | RENAME | F2 | YES — LSP rename exists (not via registry) |
| 19 | ORGANIZE_IMPORTS | (no default) | YES — editor/BuiltinSourceActions.kt:7 "Organize Imports" |
| 20 | TOGGLE_WORD_WRAP | Alt+Z | YES — word-wrap state in EditorPane/CodeEditor (toolbar, not registry) |
| 21 | TOGGLE_INLAY_HINTS | (no default) | YES/UNKNOWN — inlay hints exist; toggle path not verified by grep (GUESS) |
| 22 | ZOOM_IN | Ctrl+= | YES — zoom control exists in editor chrome |
| 23 | ZOOM_OUT | Ctrl+- | YES — same |
| 24 | ZOOM_RESET | Ctrl+0 | YES — same |
| 25 | COMMAND_PALETTE | Ctrl+Shift+P | YES — palette exists (ProjectShellScreen, opened via menu only) |
| 26 | OPEN_FILE | Ctrl+O | PARTIAL — quick-open/"Go to File" exists (palette file mode) |
| 27 | CLOSE_TAB | Ctrl+W | YES — tab close exists (tab menu/strip, not registry) |
| 28 | NEXT_TAB | Ctrl+Tab | YES — tab switching exists (not via registry) |
| 29 | PREV_TAB | Ctrl+Shift+Tab | YES — same |

Reading of the table: most actions ARE reachable by touch UI; what is dead is
the CHORD DISPATCH — a bluetooth/soft keyboard chord bound in Settings either
does nothing (rows 1-29) or duplicates a built-in hard path (Tab/Enter class:
the registry binding is never consulted because CodeEditor handles those keys
before the match()). Copy/Paste/Cut/Select_All are the gray zone: the IME or
platform may handle some chords natively, which is WHY wiring them via the
registry needs care (double-handling).

## Decision needed later (NOT part of RP1)

For each row: give it a real registry-backed handler, or remove it from the
bindable list. Removing rows changes what Settings shows (visible behavior
change — needs Wisdom's explicit approval per row class). Nothing is pruned or
wired by RP1; this report is the menu for that separate review.
