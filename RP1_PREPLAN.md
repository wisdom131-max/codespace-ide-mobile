# RP1 PRE-PLAN — Action Registry + ContextKeyExpr-lite (NO CODE until Wisdom approves)

Status: PLAN v3 2026-09-19 — Wisdom round 2: palette option (B) LOCKED for P2, plus a hard no-drop requirement and exact before/after check (section 3a updated); P1 still hard-gated on his CW7 device result; no code in either plan until he says go. Round 1 had: menu counts
RECOUNTED from current source (his stale-count catch confirmed); command
palette located (ProjectShellScreen inline, static 35-item list) + wiring
options added; P1 HARD-GATED on CW7 passing on his device; dead keybindings
removed from RP1 into DEAD_KEYBINDINGS_REPORT.md (separate report, not
bundled). Modeled on R6_PREPLAN.md: decisions locked only after Wisdom review.
Source facts READ today unless marked GUESS or RESEARCH-BASED.

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
  10, ExplorerPane.kt 10 (9 overflow + 1 file-row context), 
  NotificationDrawerOverlay.kt 7, CopilotChatPanelOverlay.kt 7,
  EditorPane.kt 7, SettingsScreen.kt 2, HomeScreen.kt 2,
  SqliteViewerDialog.kt 1, BuildPanel.kt 1
  = ~123 items across 14 files.
- RECOUNT (v2, Wisdom's stale-count catch — exact rows from current source):
  - Explorer ⋮ overflow (ExplorerPane.kt :921-983): NINE rows, not the seven
    the v1 plan claimed: 1 Multi-select Mode (:923), 2 Show hidden files
    (:929, CW7 — ALREADY LANDED), 3 Sort by (:938), 4 Expand All (:942),
    5 Collapse All (:952), 6 Import Images (:958), 7 Add Folder to Workspace
    (:963), 8 Device Folders (:973), 9 Change Folder (:977) + a HorizontalDivider
    after Collapse All. (Wisdom's "10" expectation: the CW7 row is ALREADY in
    the 9 — I count no 10th row; recount posted for his check.)
  - Editor tab context menus (EditorPane.kt): SEVEN rows across TWO menus —
    primary tab menu :1006-1042 (Close, Close Others, Close All, Close Saved,
    Copy Path) + split-view tab menu :1110-1118 (Close Split View, Copy Path).
  - Terminal: TerminalPane.kt 13 rows (icon-chip menus: extra-keys, quick
    actions, session chips; 6 `enabled =` conditions), TerminalRootMenu.kt 10
    rows (icon rows incl. location/pin, folder, checkmark; 6 `enabled =`).
- THE COMMAND PALETTE ALREADY EXISTS (READ, v2 — earlier audit's "static list"
  confirmed): it lives INLINE in ProjectShellScreen.kt, not its own file.
  State `showCommandPalette` (:780), opened via menu-bar items (:1073/:1078),
  three query modes: plain = file search, ">" prefix = command search
  (:2279-2295, a hardcoded listOf ~35 command STRINGS, dispatched through
  handleMenuAction(item) — a when{} at ~:1050-1090), "@" prefix = symbol
  search. There is ALSO a desktop-style static menu bar (:400-460:
  File/Edit/View/Go/Run/Terminal/Help MenuBarItem lists) routed through the
  same handleMenuAction dispatcher.
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

## 3a. Registry visible-payoff question (Wisdom, v2): why isn't the palette
wired in P1/P2?

P1-P3 alone change NOTHING a user can see (identical menus, data-driven
underneath) — true, and deliberate for revert-safety. But the audit's point
stands: a registry nobody reads is unverifiable from the outside. Options:

(A) P1 wires the palette: P1 additionally swaps the palette's hardcoded
    ~35-string list (:2279-2295) to read from the registry (Explorer's 9
    actions appear as runnable commands; the static strings stay until their
    surfaces migrate). Payoff visible from P1. RISK: touches ProjectShellScreen
    inline code (huge file, 64KB headroom) in the very first phase, and the
    palette's ">" list is ALSO used by the static menu bar (:400-460) — the
    first phase then has two blast radii (Explorer menu + shell palette).

(B) P2 wires the palette (RECOMMENDED): P1 stays pure-Explorer (minimal,
    CW7-gated, single surface). P2 migrates the editor tab menus AND switches
    the palette's ">" list to read the registry — by then ~16 real actions
    exist, the palette shows them, and `handleMenuAction` string dispatch for
    those items is deleted (the registry entry runs the action directly).
    The palette's file/@ symbol modes are untouched in both options (separate
    data sources). RISK: low — one extra consumer added to a working registry.

(C) Palette stays static; registry serves menus only. RISK: the registry has
    no visible payoff until a future command-palette project — weakest review
    signal, hardest for Wisdom to verify on device.

WISDOM DECIDED (round 2): option (B) LOCKED — the palette is wired to the
registry in P2.

### P2 HARD REQUIREMENT — the palette loses NOTHING (Wisdom, round 2)

Mechanics: P2 converts the ~35 static command strings
(ProjectShellScreen.kt :2279-2295) into registry ENTRIES in a new registration
file, each entry's run = the SAME handleMenuAction(...) call it runs today —
zero dispatch semantics change in the conversion commit. Surfaces migrate
LATER and swap their entries' handlers in place; non-migrated entries keep
their handleMenuAction-backed handler until their phase. The palette file/@
symbol modes are untouched.

EXACT before/after check (device + code):
  CODE CHECK (pre-merge): the new registration file must contain exactly the
  35 labels from :2279-2295, verbatim, same strings — diffed against the
  source list before the commit goes in.
  BEFORE (on #2854, before P2 ships): open palette ">" with an empty query ->
  the full command list is visible; screenshot top and bottom; count the rows
  (expect the same list as source: 35). Tap at least: "Git: Commit",
  "Toggle Word Wrap", "Notifications: Clear All", "Open Folder",
  "Collapse All in Explorer" — note what each does.
  AFTER (P2 build): empty query -> EVERY one of the 35 labels still present,
  none dropped, none renamed; registry-added actions (Explorer's 9 + the
  tab-close entries) appear APPENDED at the end. Tap EACH of the 35 commands
  once: each dispatches the same visible effect as the BEFORE notes.
  FAILURE: any missing label, any renamed label, any dead tap, any changed
  dispatch, or a migrated entry whose effect differs from its pre-P2
  handleMenuAction behavior.

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

P1 — INFRA + ONE SURFACE (the smallest useful slice) — HARD GATE: starts
  only AFTER the CW7 "Show hidden files" fix PASSES on Wisdom's device
  (CONFIRMED v2). P1 re-touches the same Explorer rows CW7 touched; stacking
  an unverified-then-refactored surface would make his CW7 retest results
  uninterpretable.
  ContextKeyExpr + ActionRegistry + migrate Explorer ⋮ overflow (NINE rows,
  ExplorerPane.kt :921-983 incl. the already-landed CW7 row). Same rows, same
  order, same ✓ toggle rendering, now data-driven with when-clauses and typed
  actions.
P2 — EDITOR TAB CONTEXT MENU: Close/Close Others/Close All/Close Saved/Copy
  Path (EditorPane.kt ~1005-1045) + first real enable-rules ("Close Saved"
  hidden when no saved tabs, etc.).
P3 — TERMINAL MENUS: TerminalPane (13 items, 6 enabled= conditions) and
  TerminalRootMenu (10) — the highest enable/disable density; introduces the
  ContextKeys value map updated from terminal state.
P4 — KEYBRIDGE (unchanged in round 2): map the 10 LIVE EditorActions (CodeEditor.kt dispatch) into
  registry ids so a binding and a menu item share one implementation. The 29
  DEAD bindings are NOT part of RP1 (Wisdom, v2): they are documented with
  grep evidence in DEAD_KEYBINDINGS_REPORT.md and await a separate review.
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

P1 (after CW7 passes on device): open project -> ⋮ overflow menu: EXACTLY
NINE rows in the same order as the CW7-verified build (screenshot-compare):
Multi-select Mode, Show hidden files, Sort by, Expand All, Collapse All,
Import Images, Add Folder to Workspace, Device Folders, Change Folder. Tap each row once: Multi-select toggles
selection mode; Sort by cycles Name->Date->Size->Type; Expand/Collapse All
work on a folder with children; Import Images opens the picker; Add Folder
opens the folder picker; Device Folders shows/hides the ✓; Change Folder opens
the picker. FAILURE: any row missing/renamed/reordered, any dead tap, or
Multi-select select-all including internals when hidden-file rules say no.
P2 (palette wiring per 3a decision): open 3 tabs (a.py dirty, b.py saved,
c.py saved) — tab menu tests as below; PLUS palette ">" search lists the
registry actions (Explorer + tab-close entries), tapping one runs it and
closes the palette (FAILURE: stale list, dead tap, or the static strings
disappearing before their surface migrates). Long-press b.py tab:
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
