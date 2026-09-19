# RP1 PRE-PLAN — Action Registry + ContextKeyExpr-lite (NO CODE until Wisdom approves)

Status: PLAN v1 2026-09-19, RP1 = parity-sweep "real project #1" (biggest
structural gap). Modeled on R6_PREPLAN.md: decisions locked only after Wisdom
review. Source facts READ today unless marked GUESS or RESEARCH-BASED.

## 1. What it is, in plain language

Today, every menu, button and shortcut in the app is hand-written where it
appears: the ⋮ menu in the Explorer, the long-press menu on an editor tab, the
terminal menus. Each one decides for itself when it is visible, when it is
greyed out, and what it does. If two places need the same action, it gets
written twice — and they drift apart.

An ACTION REGISTRY is one central list: "here are all the things the app can
do, here is when each is allowed, here is what happens when you pick it".
Menus then stop being hand-written lists and become a small loop that asks the
registry "what can I show right now?".

WHAT YOU GET: (a) adding a menu item becomes one registry entry instead of UI
surgery in a 3000-line file; (b) enable/grey-out rules become testable data
instead of scattered ifs; (c) the same action can appear in a menu, on a
button, and on a keyboard shortcut with ONE implementation; (d) the ground is
laid for a command palette later (a palette is just a searchable list of
registry entries).

## 2. What VS Code actually does (RESEARCH-BASED — file paths from the
2026-09 parity sweep of the live microsoft/vscode tree; NOT re-read today)

- Commands: every user-visible action is a command with a string id
  ("workbench.action.files.save") registered via CommandsRegistry in
  src/vs/platform/commands/common/commands.ts. Anything (keybinding, menu,
  palette) triggers commands by id, never by direct function calls.
- Menus & actions: MenuRegistry (src/vs/platform/actions/common/actions.ts)
  receives contributions: action id + title + category + which menu it appears
  in + a WHEN clause. Menu ids are declared in MenuId (same file tree).
- When-clauses: a tiny expression language, ContextKeyExpr
  (src/vs/platform/contextkey/common/contextkey.ts): `explorerResourceIsFolder
  && !inputFocus`, supports `&& || ! == != =~` over named context keys, with
  `true`/`false` literals. Parsed once, evaluated against the current context.
- Context keys: named values (booleans/strings) maintained by
  IContextKeyService (src/vs/platform/contextkey/common/contextKeyService.ts);
  workbench-wide keys live in src/vs/workbench/common/contextkeys.ts and
  editor keys near the editor part (e.g. `editorHasSelection`,
  `editorIsReadonly`). Rules: a false when-clause HIDES menu rows (not
  greys them); greying is done via `precondition` on commands.
- Keybindings: the keybinding layer (src/workbench/services/keybinding) maps a
  chord -> command id + when-clause. Same registry, one source of truth.

## 3. What VN Code does today (READ today, with file:line)

- There is already a MINI action registry: editor/KeyBindingRegistry.kt.
  - EditorAction enum with 39 actions (line 39+): SAVE, FIND, GO_TO_LINE,
    RENAME, QUICK_FIX, COMMAND_PALETTE, NEXT_TAB, ...
  - KeyBindingRegistry object (line ~85): VS Code-matching default chords,
    persistence, reset, Settings UI at ui/panes/KeybindingSettingsPanel.kt.
  - BUT dispatch exists in exactly ONE place: CodeEditor.kt ~line 2936
    (KeyBindingRegistry.match -> when(kbAction)) and it handles only 10 of
    the 39 actions (UNDO, REDO, SAVE, FIND, GO_TO_LINE, COMMENT_TOGGLE,
    DUPLICATE_LINE, DELETE_LINE, MOVE_LINE_UP/DOWN). The other ~29 are
    bindable in Settings but have NO handler anywhere (grep: no other
    KeyBindingRegistry.match call site). They are dead bindings.
- Menus are hand-written DropdownMenuItem lists, counted today:
  ProjectShellScreen.kt 25, ChatModelMenuButton.kt 17, TerminalPane.kt 13,
  InProjectSettingsDialog.kt 11, TerminalRootMenu.kt 10, SourceControlPane.kt
  10, ExplorerPane.kt 10, NotificationDrawerOverlay.kt 7,
  CopilotChatPanelOverlay.kt 7, EditorPane.kt 7, SettingsScreen.kt 2,
  HomeScreen.kt 2, SqliteViewerDialog.kt 1, BuildPanel.kt 1
  = ~123 items across 14 files.
- Context menus: editor tab long-press (EditorPane.kt ~1005-1045: Close /
  Close Others / Close All / Close Saved / Copy Path ...), gutter breakpoint
  long-press (EditorPane.kt ~2148), file-row long-press in Explorer.
- Enable/disable logic: scattered inline (TerminalPane has 6 `enabled =`
  conditions; guards like exists-checks and mode checks surround many items).
  No central condition store exists (grep for registry/command patterns:
  zero hits outside KeyBindingRegistry).
- HOW MANY PLACES WOULD MOVE: full adoption touches all 14 files (~123 items),
  but the plan below migrates ~50-60 items (the menu surfaces with real
  enable/disable logic) across 4 phases; Chat/Settings menus can stay
  hand-written until a later phase without hurting the design.

## 4. The design — what gets added, what stays

ADDED (new files, no inline bodies in huge files — 64KB rule):
- util/ActionRegistry.kt: ActionSpec(id: String, title: String, icon: Any?,
  whenExpr: CtxExpr?, run: (ActionContext) -> Unit) + per-surface registries
  ("explorer.overflow", "editor.tabMenu", "terminal.menu", ...). Registration
  happens in small per-surface registration FILES (e.g.
  actions/ExplorerActions.kt), never inside composable bodies.
- util/ContextKeyExpr.kt (the "lite"): a TYPED tree, not a string parser —
  data classes And/Or/Not/Key(name)/Eq(name,value); a fun matches(values:
  Map<String, Boolean|String|Int>): Boolean. Deliberately NO runtime string
  deserialization (GUESS avoided, not a gap: string parsing buys us nothing
  without extension JSON contributions, and risks the #2753-class parser
  bugs). "Lite" = the boolean core VS Code uses for 90% of its when-clauses.
- util/ContextKeys.kt: a tiny observable value map + helper to publish
  (hasOpenEditor, editorDirty, isGitRepo, hasSelection...). P1 sources values
  at render time from the parameters the menus ALREADY receive — no separate
  staleness store until P3 needs one.
- ActionContext carries the surface's existing lambdas (e.g. the Explorer
  overflow actions receive the toggle callbacks they capture TODAY). The
  registry stores CREATORS (state -> ActionSpec), so actions resolve live
  state at tap time — never stale state captured at registration time.
STAYS AS IS:
- KeyBindingRegistry + EditorAction enum untouched (P4 only MAPS ids).
- Menu styling (rounded 8-12dp, 12/10dp padding) — the registry provides
  entries; the rendering composable keeps the existing look exactly.
- All hand-written menus not migrated in a phase keep working unchanged.
- ChatPanel permission gates (AgentFlowGate) — the registry does NOT route
  tool calls; it is a UI-layer construct.

## 5. Phases (each leaves the app fully working, each revertable alone)

P1 — INFRA + ONE SURFACE (the smallest useful slice):
  ContextKeyExpr + ActionRegistry + migrate Explorer ⋮ overflow (7 rows,
  ExplorerPane.kt ~888-944: Multi-select, Sort by, Expand/Collapse All,
  Import Images, Add Folder, Device Folders, Change Folder + the CW7
  "Show hidden files" row when it lands). Same rows, same order, same ✓
  toggle rendering, now data-driven with when-clauses and typed actions.
P2 — EDITOR TAB CONTEXT MENU: Close/Close Others/Close All/Close Saved/Copy
  Path (EditorPane.kt ~1005-1045) + first real enable-rules ("Close Saved"
  hidden when no saved tabs, etc.).
P3 — TERMINAL MENUS: TerminalPane (13 items, 6 enabled= conditions) and
  TerminalRootMenu (10) — the highest enable/disable density; introduces the
  ContextKeys value map updated from terminal state.
P4 — KEYBRIDGE: map the ~10 live EditorActions into registry ids so a binding
  and a menu item share one implementation; DECIDE the ~29 dead bindings
  (give handlers or hide from Settings) — separate review, no silent pruning.
P5 (optional/later) — ProjectShellScreen (25) + chat surfaces (31).

## 6. Risks + per-phase "what did I remove or change" check

- SILENT DROP (the scrollToLine lesson): migrating a hand-written menu to a
  loop is exactly the refactor class that silently dropped scrollToLine in a
  call-site move. CHECK PER PHASE: before migrating, I dump the current menu
  (row labels in order + what each onClick touches) into the plan/PR; after
  migrating, I diff — every row must survive with identical label, order,
  icon, and enabled logic; the diff is posted with the phase for Wisdom to
  eyeball. On-device: row-count + label check, then one tap per row.
- JVM 64KB: registration code lives in NEW small files; migrated menus become
  LOOPS (net code REMOVED from ProjectShellScreen/ExplorerPane bodies — those
  bodies are already near the limit; CodeEditor has zero headroom, P4 touches
  it only by id-mapping, not by adding body code).
- Behavior changes users would notice: (a) menu ORDER must stay identical;
  (b) hidden-vs-greyed — VS Code hides when-clause-false rows; P1-P2 preserve
  TODAY's exact visibility instead (nothing currently hidden becomes greyed or
  vice versa); (c) ✓ toggle states must render identically; (d) P4 must not
  change what any existing chord does.
- Context staleness: P1/P2 compute when-values at render time from live params
  (no cache to go stale). P3 adds the shared ContextKeys map — keys published
  from the same state objects that already drive the UI.

## 7. What it will NOT do (scope fence)

No command palette (separate future project), no user-editable when-clauses,
no JSON/string when-parsing at runtime, no extension contribution points, no
moving visual styling into the registry, no routing of chat TOOL calls, no
multi-window/menu.json concepts, no behavioral redesign of any menu — this is
an infrastructure refactor with byte-identical visible behavior except where
a phase explicitly adds enable-rules (P2+).

## 8. Surfaces touched per phase + re-polish owed (surfaces-close rule)

P1: Explorer pane (overflow menu) — OWES an Explorer re-polish pass (folded
    into the already-owed CW7 dotfile re-polish; one pass covers both).
P2: Editor chrome (tab strip context menu) — OWES editor-chrome re-polish.
P3: Terminal surface (two menus) — OWES terminal re-polish.
P4: Keyboard layer + KeybindingSettingsPanel — OWES settings re-polish.
P5: Project shell + chat surfaces — re-polish scheduled when P5 is approved.
Each phase ships its re-polish check BEFORE the phase is called closed.

## 9. Per-phase tap-by-tap tests (with failure checks)

P1: open project -> ⋮ overflow menu: EXACTLY the same rows in the same order
as build #2854 (screenshot-compare). Tap each row once: Multi-select toggles
selection mode; Sort by cycles Name->Date->Size->Type; Expand/Collapse All
work on a folder with children; Import Images opens the picker; Add Folder
opens the folder picker; Device Folders shows/hides the ✓; Change Folder opens
the picker. FAILURE: any row missing/renamed/reordered, any dead tap, or
Multi-select select-all including internals when hidden-file rules say no.
P2: open 3 tabs (a.py dirty, b.py saved, c.py saved). Long-press b.py tab:
Close, Close Others, Close All, Close Saved, Copy Path (paste into chat input
to verify). Long-press with exactly ZERO saved tabs -> "Close Saved" behaves
per P2 decision (hidden or disabled — whichever Wisdom picks). FAILURE: a
close action closing the WRONG tab set (verify each by remaining tabs).
P3: terminal open -> menu: every item's enabled state matches #2854 for:
no session / session idle / command running. FAILURE: an action enabled in
the wrong state (e.g. Copy during running when it was disabled before).
P4: with a bluetooth keyboard: Ctrl+S still saves (toast), Ctrl+Z/Y still
undo/redo, Ctrl+F still opens find — all via the registry path. FAILURE: any
chord changing behavior. Settings -> Keybindings still lists/resets bindings.
P5: (defined when P5 approved).

## 10. Where I am guessing (explicit)

- VS Code file paths in section 2 are RESEARCH-BASED (2026-09 parity sweep of
  the live tree); I did not re-open them today. The MECHANISM description
  (registry + when-clauses + hide-vs-precondition) is high-confidence.
- "The other ~29 EditorActions have no handler": verified by grep today
  (single dispatch site in CodeEditor.kt), but I did NOT hand-audit whether a
  few are triggered through other channels (GUESS: some, like COMMAND_PALETTE,
  may have dead UI elsewhere). P4 audits before pruning anything.
- Exact when-clause feature coverage (e.g. =~ regex matching) — not needed for
  P1-P4; claiming 90% boolean coverage is an estimate, not a measurement.
- Whether ProjectShellScreen's 25 items are all MENU actions vs some being
  navigation buttons (they may partially stay hand-written) — audited at P5.
- No performance numbers for registry-lookup menus (Compose recomposition
  cost of a 7-row data loop): expected negligible, GUESS until P1 profiling.
