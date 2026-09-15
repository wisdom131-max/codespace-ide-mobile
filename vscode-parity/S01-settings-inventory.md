# S01 — SETTINGS INVENTORY (VS Code census + access paths + our comparison)

> Parity research, settings series. 2026-09-15.
> VS Code: `microsoft/vscode` @ `main`, verified live (API listings + raw source greps).
> Ours: `codespace-ide-mobile` @ `59974b0`, grep-verified.

## §1 The census — how many settings VS Code actually has

There is **no published canonical count**: the settings schema is assembled at RUNTIME from contributions. Verified method + numbers:

- **144 registration points** call `configurationRegistry.registerConfiguration` (GitHub code-search total_count, verified today) — workbench/platform services (window, editor, files, update, chat, git, terminal, search…).
- **Editor settings machinery**: `src/vs/editor/common/config/editorOptions.ts` (6,958 lines, ~50 option classes) + `editorConfigurationSchema.ts` (registers the `editor`/`diffEditor` configuration node) — the editor.* family alone is hundreds.
- **107 built-in extension manifests** under `extensions/` — most contribute `contributes.configuration` blocks (git, search, emmet, CSS, HTML, JSON, TypeScript-language-features… each with dozens).
- **Order of magnitude: ~1,000+ user-facing settings** in current builds (stated as method-derived estimate — no official number exists; the count grows monthly).

## §2 Every access path (verified citations)

All under `src/vs/workbench/contrib/preferences/browser/` unless noted:

1. **Settings UI editor** (`settingsEditor2.ts`): tabbed User/Workspace, category tree (`settingsLayout.ts`), typed rows — combo/checkbox/number/text/link (`settingsTree.ts`), **search** (`settingsSearchMenu.ts` — new dropdown search menu; `preferencesSearch.ts` — searches remote + extension settings too), **modified/scope indicators** (`settingsEditorSettingIndicators.ts` — "modified" gutter + scope conflict badges).
2. **settings.json** (JSON-first): full JSON-schema validation + IntelliSense inside the editor; commands open User/Workspace JSON directly (`preferencesActions.ts`); **language-specific overrides** (`"[kotlin]": {...}` blocks); **default-settings viewer** — the generated "Default Settings (JSON)" document with comments (`preferencesEditor.ts`, `preferencesRenderers.ts`).
3. **Command palette**: Preferences: Open Settings (UI)/(JSON), Open Workspace Settings, Open Keyboard Shortcuts (+JSON), Configure Language-Specific Settings, Settings Sync, Profiles.
4. **Keybindings editor** (`keybindingsEditor.ts` + `keybindingWidgets.ts`): searchable, "when" clause editing, record-key UI, conflict view.
5. **Workspace scope**: `.vscode/settings.json` auto-detected per project; **Profiles** (`workbench/contrib/profiles`) — settings scoped per named profile; **Settings Sync** (`userDataSync`) — user settings across machines; **remote** settings (search reaches them).
6. **In-editor quick paths**: context menu on any setting row → Copy Setting ID / Reset. Filter syntax: `@tag`, `@lang`, `@ext`, `@modified`, `@source`.
7. **Extension settings**: every installed extension injects its section into the same tree (B10 boundary).

## §3 Our inventory (verified today)

**Project settings** — `InProjectSettingsDialog.kt` (1,797 lines): structured registry — `SettingsRow(id, category, label, description, RowType)` with **18 categories** (Commonly Used, AI Agent Flow, Connectors, Editor Features, Notifications, Text Editor, Formatting, Python/LSP, LSP Servers, Accessibility, JS/TS Format, Tsserver, Inlay Hints, Workspace Symbols, Window, Terminal, Keybindings, Extensions, Task), **52 declared rows + ~12 special rows** (McpToolsSection, ZenModeExit, LspIdleTimeout, LspEnabled, SmartCompletion, CursorMode, CustomCursorOverlay, FormatOnSave, TextMateHighlight, FormatterDropdown, FlowMode, VerboseToolOutput…), typed renderers (dropdown/checkbox/toggle), **search bar EXISTS** (Icons.Default.Search @103 — B12 §6-4 answered).

**App settings** — `SettingsScreen.kt` (787 lines, 12 ListItems): Appearance, Security (app lock), Accounts (GitHub), Container Backup, Ubuntu Container, Workspace Memory, Formatter Selection, Clear Data + AI keys (`AiKeysSection`), keybinding editor (`KeybindingSettingsPanel`).

**Total ours: ~70-80 settings** across 3 surfaces vs VS Code's ~1,000+.

## §4 Verdict table

| Access path / capability | VS Code | Ours | Verdict |
|---|---|---|---|
| Typed settings UI w/ categories | HAVE (tree) | HAVE (18-category registry) | HAVE |
| Settings search | HAVE (incl. remote+ext) | HAVE in project dialog; NONE in app screen | PARTIAL |
| JSON settings file + schema IntelliSense | HAVE | MISSING | MISSING (deliberate — see §5) |
| Default-settings viewer | HAVE | MISSING | MISSING |
| Reset-to-default per row | HAVE (context menu) | MISSING (grep-verified: no reset path) | MISSING — cheap add |
| Modified indicator + scope badges | HAVE | MISSING | MISSING — cheap add |
| Copy setting ID / export settings | HAVE (copy) | MISSING — export would pair w/ CloudBackup | MISSING — cheap add |
| Language-specific overrides | HAVE | MISSING | MISSING — parked until RP5 (B09) |
| Profiles / Settings Sync | HAVE | N/A (single device) — CloudBackup covers restore | DELIBERATE |
| Workspace (project) scope | HAVE | HAVE (InProject dialog is project-scoped) | HAVE |
| Keybindings editor | HAVE | HAVE (KeybindingSettingsPanel) | PARTIAL (conflict view unknown §6) |
| Extension-contributed settings | HAVE | N/A (B10) | N/A |
| Filter syntax (@tag/@lang/@ext) | HAVE | MISSING | MISSING — small add on existing search |
| Remote settings | HAVE | N/A | N/A |

## §5 Worth adding (recommendation, ties into roadmap)

1. **Reset-to-default per row** (context menu on any project-settings row) — cheapest, highest-trust win; needs a defaults registry (we already have RowType + id — add `defaultVal` to SettingsRow).
2. **Modified indicator** ("● modified" in row) — same registry work as #1.
3. **Export/import settings as JSON** — pairs with CloudBackupManager; also becomes our "settings.json" analog WITHOUT building a JSON editor (honest scope call: a real settings.json + schema experience is a VS Code-shaped feature we don't need on-device).
4. **Filter tokens in existing search** (@term-style) — rides the working search bar.
5. **App-screen search** — low priority (only ~12 rows).
6. **Language-specific overrides** — do NOT build ad hoc; lands naturally with RP5's LanguageConfiguration registry (B09).

Settings-surface sequencing note (per approved ordering plan): #1-#4 are small FEATURE changes to the settings surface — they slot into the settings feature block after Phase 4, before any settings visual touch-ups (U07 covers dialog rendering).

## §6 Open questions

1. KeybindingSettingsPanel conflict detection — exists? (B12 §6-5, still open)
2. Does the 52-row registry persist values via SharedPreferences keys matching `id`? (verify when implementing #1)
3. TS/JS categories (Format/Tsserver/Inlay/Symbols) — are these wired to real TS servers in proot, or placeholders? (→ B09 multi-server question)

## Status

**DONE** — 2026-09-15. Next research: U01 chat panel UI (with vscode.dev narrow-width renders).
