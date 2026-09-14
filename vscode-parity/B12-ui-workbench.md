# B12 — UI & WORKBENCH CHROME

> VS Code parity research, batch 12 of 14.
> VS Code: `microsoft/vscode` @ `main`, verified live 2026-09-14 (GitHub API listings).
> Ours: `codespace-ide-mobile` @ `702151e`, grep-verified.

## §0 Scope & sources inspected

VS Code: `workbench/browser/parts/` (verified listing: activitybar, auxiliarybar, banner, dialogs, editor, notifications, panel, sidebar, statusbar, titlebar, views, paneCompositeBar/Part/Service, compositeBar/Actions, globalCompositeBar, visibleViewContainersTracker), workbench layout service, `contrib/preferences` (settings editor), `contrib/format`? (no — formatting is B04).

Ours: `ui/screens/ProjectShellScreen.kt` (5,189 lines), `WorkspaceShapes.kt`, `ui/screens/NotificationDrawerOverlay.kt` (bell @77, toast banner @122, drawer @241), `SettingsScreen.kt`, `InProjectSettingsDialog.kt`, `KeybindingSettingsPanel.kt`, `ui/panes/` (30+ dialog/pane files), `EditorTabColors.kt`, `SplitViewStore.kt` (B03), CommandPalette (B02), touch layers in CodeEditor (MC double-tap, long-press, IME insets), STANDING UI RULES (rounded 8-12dp + padding 12h/10v — our chrome spec).

## §1 What VS Code has (citations)

- **Part-based layout**: workbench = titlebar + activitybar + sidebar + auxiliarybar (SECONDARY side bar) + editor + panel + statusbar, each a `part` composed via paneCompositePartService; views movable between containers (`views/`, `visibleViewContainersTracker.ts`), drag-rearrangeable.
- **Activity bar** (`parts/activitybar`): icon rail, badge counts, hide/show, drag reorder; global composite bar.
- **Status bar** (`parts/statusbar`): per-segment items (remote indicator, problems count, encoding/EOL/lang, cursor pos, feedback smiley) — third-party contributions allowed.
- **Notifications** (`parts/notifications`): toast stack + notification CENTER (drawer) with sources, actions, silence/filter.
- **Banner** (`parts/banner`): persistent top banner w/ actions (e.g. "settings sync off").
- **Dialogs** (`parts/dialogs`): themed file/message dialogs w/ buttons/checkboxes.
- **Menus**: declarative MenuService — menus registered by identifier, contributions from everywhere, context-menu stitching (`workbench/services/actions`).
- **Settings editor** (`contrib/preferences`): searchable/settings-in-tabs UI, modified indicators, scope filtering (user/workspace), keybinding editor w/ conflicts view.
- Zen mode, custom editor sizes, centered layout, editor groups in arbitrary grids, window state serialization.

## §2 Architecture — shared state & connections

- Everything is a Part registered into the layout; grid = editor groups (B03); views float between containers by ID. State (sizes, visibility) serializes into workspace state (→ PERSIST-B's VS Code counterpart).
- Status items / notifications / menus are all *contribution* surfaces — B10's extension host feeds them, but core also contributes.
- Menus + keybindings + palette all resolve through the same action registry (B02's ContextKeyExpr gating).

## §3 What OUR app has (verified)

- **Shell**: ProjectShellScreen (5,189 lines — PSS helpers, bottom-panel content, editor column; the 64KB-mitigated file), WorkspaceShapes (ActivityBar shapes), pane system (ExplorerPane, SourceControlPane, BuildPanel, ProblemsPanel, LogcatPanel, TerminalPane, DiagnosticsOverlay…).
- **Status bar**: `StatusBarContent` (@4181) — status surface exists (content scope §6).
- **Activity bar**: adapted (grep hits ProjectShellScreen/WorkspaceShapes; likely rail/tab hybrid §6).
- **Notifications**: `NotificationDrawerOverlay` — bell + toast banner + drawer ≈ VS Code notification center, full trio present.
- **Settings**: SettingsScreen + per-project InProjectSettingsDialog + KeybindingSettingsPanel (keybinding editor exists — rare parity point).
- **Dialogs**: 30+ themed dialogs in ui/panes, all under the STANDING UI RULES (rounded 8-12dp, padded 12h/10v, IME insets) — our equivalent of VS Code's dialog theming spec, enforced by convention.
- **Editor layout**: tab system + SplitViewStore (one live split view, B03) — no arbitrary group grids (mobile-accepted).
- **Menus**: bespoke composables per surface (no declarative menu registry) — menu content is code, actions wired directly.
- **Zen**: hits in ProjectShellScreen/InProjectSettingsDialog (scope §6).
- **Touch chrome (HAVE-UNIQUE)**: double-tap multi-cursor, long-press context, IME-inset-aware popups, portrait adaptive rows (F1) — VS Code has zero touch chrome.
- **MISSING**: auxiliarybar/secondary side bar, movable/draggable views, banner part (toast ≈ partial), settings search + modified-flags (§6), declarative menu service.

## §4 Verdict table

| Feature (VS Code) | Verdict | Gap |
|---|---|---|
| Status bar | HAVE | content breadth §6 |
| Activity bar (w/ badges) | PARTIAL | adapted rail; badge counts §6 |
| Notification center (toasts + drawer + bell) | HAVE | full trio |
| Banner | PARTIAL | toast banner covers transient; no persistent w/ actions banner |
| Settings UI | PARTIAL | two screens; search + modified indicators §6 |
| Keybinding editor | HAVE | KeybindingSettingsPanel (conflict view §6) |
| Themed dialogs | HAVE | enforced by standing UI rules |
| Editor groups | PARTIAL | single split view; grids skipped (mobile) |
| Secondary side bar (auxiliarybar) | MISSING | one sidebar model |
| Movable/draggable views | MISSING | fixed panes |
| Declarative menu service | MISSING | code-defined menus; fine at current scale |
| Zen mode | UNKNOWN | grep hits §6 |
| Title bar | PARTIAL | app-bar model (mobile) |
| Touch-first chrome | HAVE-UNIQUE | no VS Code analog |
| State serialization (layout) | PARTIAL | PERSIST-B audit covers panel state (pending) |

## §5 Cross-subsystem connection edges

- layout ↔ **PERSIST-B**: VS Code serializes part sizes/visibility into workspace state — exactly the pending PERSIST-B audit's counterpart; if our pane/tab state isn't fully persisted, B12 records where.
- status bar ↔ **B11/B06/B08**: problems count, terminal mode, debug state would live in StatusBarContent — item inventory needed (§6).
- menus ↔ **B02/B10**: a declarative menu registry would unify palette + context menus + contributed items — only worth it if B10's "extension-lite" format is ever built.
- notifications ↔ **B13**: chat session errors currently toast; VS Code routes provider failures to notification center w/ actions (retry etc.) — pattern note.
- settings UI ↔ **B09 LanguageConfiguration**: per-language settings editing would need the declarative config data from B09.

## §6 Open questions / on-device verification

1. StatusBarContent inventory: which segments exist (problems count? branch? cursor pos? LSP state?) vs VS Code's standard set.
2. ActivityBar: rail with badges or plain tab strip? Badge counts wired (problems/dirty)?
3. Zen-mode hits: real distraction-free mode or naming coincidence (grep in ProjectShellScreen + InProjectSettingsDialog)?
4. SettingsScreen: search + modified-flags present? Scope (user vs project) split — InProjectSettingsDialog suggests project scope exists.
5. KeybindingSettingsPanel: conflict detection like VS Code's keybindings "when" conflict view?
6. Notification drawer: source labels + action buttons + do-not-disturb?

## Status

**DONE** — 2026-09-15. Next: B13 Chat & AI/Copilot.
