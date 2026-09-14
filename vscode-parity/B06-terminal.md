# B06 — TERMINAL

> VS Code parity research, batch 6 of 14.
> VS Code: `microsoft/vscode` @ `main`, verified live 2026-09-14 (GitHub API listings).
> Ours: `codespace-ide-mobile` @ `187d36c`, grep-verified.

## §0 Scope & sources inspected

VS Code: `workbench/contrib/terminal/browser/` (terminalInstance, terminalGroup, terminalGroupService, terminalEditor* — terminals as editor tabs, terminalEditorSerializer, terminalEditingService, terminalContextMenu, terminalIconPicker, terminalEscapeSequences, environmentVariableInfo, detachedTerminal, agentHostPty/agentHostTerminalService, chatTerminalCommandMirror, remotePty/remoteTerminalBackend), `browser/xterm/` (xtermTerminal, decorationAddon, lineDataEventAddon, markNavigationAddon), `common/` (environmentVariableService + contribution, terminalConfiguration, terminalContextKey, scripts/ — shell-integration scripts).

Ours: `terminal/` package (18 files) + `ui/panes/TerminalPane.kt` (2,245 lines), `TerminalRootMenu.kt`.

## §1 What VS Code has (citations)

- **Core**: xterm.js frontend (`xterm/xtermTerminal.ts`) + addon model (decorationAddon, markNavigationAddon, lineDataEventAddon) over a pty host; windows/contributed/local/remote/agent-host pty variants (`basePty.ts`, `remotePty.ts`, `agentHostPty.ts`).
- **Shell integration** (`common/scripts/`): injected OSC 633 sequences → prompt/command/output regions, command navigation (markNavigationAddon), "recent command" for AI context, run-recent-command, go-to-error links.
- **AI/agent touchpoints**: `chatTerminalCommandMirror.ts` (agent-executed commands mirrored into a visible terminal), `agentHostTerminalService` (agent-created terminals), terminal transcript as chat context.
- **Terminal-as-editor** (`terminalEditor*.ts`): terminals live as editor tabs incl. serialization/deserialization across restarts.
- **Groups + panes** (`terminalGroup*`), **profiles** (terminalConfigurationService: user-defined profile JSON, icon picker, default per platform), **environment-variable collection** (`environmentVariableService.ts` — extensions contribute per-session env), **find widget**, **word-wrap + buffer editing** (`terminalEditingService.ts`), sticky scroll, shell-dependent **context keys** (`terminalContextKey.ts`), detached terminals, escape-sequence keybindings (`terminalEscapeSequences.ts`).

## §2 Architecture — shared state & connections

- One `ITerminalInstance` per pty; groups contain instances; instances optionally backed by editor inputs (terminal-as-tab) — tab system (B03) owns placement.
- Shell integration annotates the buffer → marks feed navigation, AI context, decorations — the buffer becomes structured data, not just text.
- Environment collection composes per-profile + per-extension contributions at spawn — deterministic re-build of env.
- Terminal context keys (B02's when-DSL) gate keybindings inside the terminal.

## §3 What OUR app has (verified)

- **Core PTY**: real native stack — `NativePty.kt` (JNI `createSubprocess` via libptynative.so), vendored termux `TerminalEmulator`/`TerminalView` (Android-side xterm.js equivalent), `TerminalSession.kt`, `TerminalService.kt`, scrollback buffer in TerminalPane.
- **Environments**: `ProotInstaller`/`TermuxBootstrapInstaller`/`BusyboxInstaller`/`DeviceCompatibility` (proot Ubuntu), `RemoteTerminalSession` + `SshProfile`/`SshProfileStore` (remote dev), `McpShellProfile` — modes managed by `TerminalModeManager`.
- **Session persistence**: `TerminalSessionStore` — saved tabs per project, restore-once claim guard; `BackupManager`.
- **Editor bridge**: `IdeTerminalBridge` — OSC 7777 (`ESC]7777;open;TYPE;PATH;LINE`) handled by vendored emulator → host-path translation → open file at line in editor. Acode-compatible, custom (NOT VS Code's scheme).
- **AI bridge**: `TerminalAiBridge` — `recordPaste`, `recordRun(command, output)`, `transcriptTail(6000)` — terminal transcript feeds AI context (I3 attach; run_command tool executes via terminal with FlowGate gating, R6).
- **Text expansion** (`TextExpansionStore`), terminal enhancements, root menu (TerminalRootMenu).
- **No** OSC 633 shell integration, no prompt/command detection, no terminal-as-tab, no user-defined profiles (modes are built-in), no env-var collection, no icon profiles, no terminal find widget, no groups/panes.

## §4 Verdict table

| Feature (VS Code) | Verdict | Gap |
|---|---|---|
| PTY + emulator + scrollback | HAVE | native JNI pty, vendored emulator; emoji/IME input diagnostics open (standing item) |
| Multiple terminal tabs/sessions | HAVE | TerminalService + per-project saved tabs |
| Terminal groups/panes (split terminal) | MISSING | — |
| Profiles (user-defined, icons) | PARTIAL | built-in modes (proot/ssh/mcp) ≈ profiles; no user profile definitions |
| Shell integration OSC 633 (prompt/cmd/output marks) | MISSING | custom OSC 7777 only (file links) — no command regions |
| Command navigation / run-recent | PARTIAL | ShellHistorySearchOverlay = command history search; no buffer-mark navigation |
| Terminal→editor file links | HAVE-UNIQUE | OSC 7777 open-at-line (Acode scheme) — VS Code uses shell-integration links instead |
| AI: command mirror (chatTerminalCommandMirror) | PARTIAL | agent run_command executes in-terminal (I2); mirror shape differs (ours is the ONLY path, not a mirror of a background path) |
| AI: transcript as chat context | HAVE | TerminalAiBridge transcriptTail + attach (I3) |
| Terminal-as-editor tab | MISSING | terminal is a fixed pane |
| Environment-variable collection | MISSING | — |
| Find in terminal buffer | MISSING | only shell-history search overlay |
| Buffer word-wrap/editing (terminalEditingService) | MISSING | — |
| Persistent sessions across restart | PARTIAL | tab layout saved (TerminalSessionStore); processes do NOT survive restart (VS Code local revives via pty host) |
| Detached/background terminals | MISSING | — |
| Unicode/emoji input | PARTIAL | standing IME diagnostic (emoji tap test protocol in AGENTS.md) |

## §5 Cross-subsystem connection edges

- terminal ↔ **R6/B13 chat**: run_command gating (FlowGate permission levels), transcript attach, paste-suggestion — our densest AI-terminal integration, mirrors VS Code's agentHost/chatTerminalCommandMirror family.
- terminal ↔ **B03**: terminals are pane-bound, not tab-bound (VS Code's terminalEditor makes them first-class tabs — a structural divergence, likely fine for mobile).
- OSC 7777 ↔ **B01/B04**: file-link lands in the editor open-at-line entry (same entry as diagnostics/stack-trace links).
- proot/ssh ↔ **ubuntu-proot-test repo**: environment correctness (signal-31 history) is TWO-REPO — parity findings here do NOT change the proot fix flow.
- shell-integration absence ↔ **B11**: no command/output regions → no "run this failing command again" smarts; ShellHistoryStore partially compensates.

## §6 Open questions / on-device verification

1. Does TerminalSessionStore restore per-tab working directories (SavedTab cwd) or only tab identity?
2. Proot guest↔host path translation correctness for OSC 7777 links from inside proot (guest paths translated — verify with deep path).
3. Multiple concurrent sessions on a 4GB device — does TerminalService cap sessions? (RAM)
4. Would OSC 633-style marks help R6 (run_command output→diff pairs) or is TerminalAiBridge.recordRun sufficient?
5. Terminal find-in-buffer: cheap to add? (scrollback exists in memory)

## Status

**DONE** — 2026-09-14. Next: B07 Source control & git.
