# B10 — EXTENSION SYSTEM (impossible-parity check + analog map)

> VS Code parity research, batch 10 of 14.
> VS Code: `microsoft/vscode` @ `main`, verified live 2026-09-14 (GitHub API listings).
> Ours: `codespace-ide-mobile` @ `0a97a07`, grep-verified.

## §0 Scope & sources inspected

VS Code: `workbench/services/extensions/` (browser/ common/ electron-browser/ worker/ — extension host variants per environment), `workbench/api/common/` (extHost.api.impl.ts — the `vscode` API surface implementation, extHost.protocol.ts — the RPC protocol, extHostApiCommands, configurationExtensionPoint.ts, extHostAiRelatedInformation/AiSettingsSearch — AI API too), plus prior-batch touchpoints (mainThreadLanguageFeatures — B04, scm provider abstraction — B07, debug adapter contributions — B08, language contributions — B09).

Ours: `agent/McpClientManager.kt` + `AgentTools.kt` + `ChatPermissionStore.kt` (mcp_* tool surface), `chat/CustomModeStore.kt`, `chat/SkillsCatalog.kt`, `ui/screens/ChatModeCustomSection.kt` (R9 surfaces), `ui/panes/PackageManagerPane.kt` (apt for proot Ubuntu), `chat/ChatKeyPool.kt` (BYOK), domain/ai provider stack.

## §1 What VS Code has (citations)

- **Out-of-process extension host**: extensions run in a separate Node.js process (per environment: electron-browser, worker, browser); the workbench talks to them over the **RPC proxy protocol** (`extHost.protocol.ts`) through generated `mainThread*`/`extHost*` pairs (e.g. mainThreadLanguageFeatures ↔ extHostLanguageFeatures). API surface is `extHost.api.impl.ts` — one namespaced `vscode.*` object.
- **Activation events**: extensions are lazy — activated on `onLanguage`, `onCommand`, `onView`, `onStartupFinished`, URI patterns… not at boot.
- **Contribution points** (package.json): commands, menus, keybindings, languages/grammars/snippets, views/container, configuration, debuggers, themes/icons, formatters, task providers, chat participants/agents/skills, walk-throughs… Each is a declared point the host validates and registers.
- **Marketplace + built-ins**: extensions install/enable/disable/update via a marketplace; notably GIT ITSELF, themes, markdown support, the terminal shell-integration scripts, Copilot chat modes/skills — are all extensions. Core is deliberately small.
- **AI API**: `extHostAiRelatedInformation` / `extHostAiSettingsSearch` — extensions can contribute AI-related info; chat participants/skills are first-class extension contributions (our R9 targets the same files our app reads itself).

## §2 Why full parity is impossible on-device (honest verdict)

A VS Code extension host requires Node.js + npm-style package installs + long-lived RPC processes. On our proot stack that's heavyweight, and the marketplace/trust/signing infrastructure is entirely out of scope for a single-device product. **This batch therefore maps slots, not the host.**

## §3 Analog map — which of our built-ins occupy which extension slots

| VS Code extension slot | Our built-in occupying it | Verdict |
|---|---|---|
| Git extension | SourceControlPane + scm/ (B07) | HAVE as built-in |
| Debugger extensions (DAP contributions) | debug/ adapters, compile-time (B08) | HAVE, 2 languages, not pluggable |
| Language extensions (grammars, snippets, language config) | IncrementalTmHighlighter + StdlibCompletions + lsp/ (B09) | PARTIAL — data-driven grammars exist; no third-party install |
| Themes | EditorTabColors + editor theming | PARTIAL — no theme packages |
| Chat modes extension | CustomModeStore + ChatModeCustomSection (R9) — .agent.md files | HAVE — our closest true "installable extension" shape |
| Chat skills / prompt files | SkillsCatalog (R9) — prompt FILES with frontmatter | HAVE |
| Chat participants/tools | AgentTools 31 tools + MCP mcp_* tools | HAVE — MCP is our pluggable boundary |
| Task providers | TaskRunner/PendingTasks (→ B14) | built-in |
| Commands/palette contributions | KeyBindingRegistry + palette (B02) | HAVE, app-defined only |
| Marketplace (install/trust/update) | **NONE** — PackageManagerPane is apt (OS packages for proot), not app plugins | MISSING by design |
| Activation events / lazy loading | N/A — everything compiled in | N/A |
| Extension enable/disable UI | PackageManagerPane install/remove (apt) | PARTIAL (different layer) |
| Extension settings contribution | Settings screen sections (app-defined) | PARTIAL |

**The one true pluggable boundary we have: MCP.** McpClientManager + mcp_* tools (permission-gated via ChatPermissionStore levels, R5) is functionally our extension API — third parties add tools without touching the APK. R9 modes/skills are the second: user-supplied declarative files that extend chat behavior.

## §4 Verdict table

| Feature (VS Code) | Verdict | Gap |
|---|---|---|
| Out-of-process extension host | MISSING (impossible-parity) | documented, not chased |
| Marketplace + install/update/trust | MISSING | deliberate; no infrastructure |
| Contribution points (declarative) | PARTIAL | modes/skills/modes files are declarative; editor features are code |
| Lazy activation events | N/A | all built-ins; MCP connects on demand — closest analog |
| Extension API surface (vscode.*) | MISSING | MCP tools = our API; no editor-API exposure to plugins |
| BYOK AI providers as extensions | HAVE-UNIQUE | our 7-provider stack + custom endpoints + key pool = VS Code needs an extension; ours is core (→ B13) |
| Chat modes as installables | PARTIAL | files yes; no share/packaging format |
| Skills | HAVE | SkillsCatalog |
| Third-party language support | PARTIAL | TextMate grammars could load from files §6; servers via proot apt §6 |

## §5 Cross-subsystem connection edges

- MCP ↔ **B13 chat**: every mcp_* tool renders through tool-chip/permission flow (R4/R5) — the plugin boundary and the AI layer meet here.
- modes/skills ↔ **R9 walkthrough**: B10's "installable" claims are exactly R9's test surface.
- apt PackageManager ↔ **B06 proot**: package installs ride the same proot terminal correctness (TWO-REPO constraint).
- declarative gaps ↔ **B09 LanguageConfiguration**: snippet packs + comment/bracket configs as loadable files = the realistic "extension-lite" path for us (no host needed).
- themes ↔ **B12 UI**: a theme JSON format would slot into EditorTabColors/theming.

## §6 Open questions / on-device verification

1. TextMate grammar loading: does IncrementalTmHighlighter read grammar files from disk (extensible) or bundled assets only?
2. MCP servers: how many configured concurrently on-device? (RAM/perf — proot)
3. Skills/modes packaging: should we define a shareable zip/folder format (modes+skills+grammars) as our "extension pack"? ← candidate roadmap item
4. Could snippet packs (JSON) be loadable from project `.codespace/snippets/`? (cheap win, mirrors modes)
5. apt-in-proot as language-server installer (python-lsp-server via apt) — does LspManager support pointing at proot-installed servers?

## Status

**DONE** — 2026-09-14. Next: B11 Problems, output & diagnostics.
