# F03 — Workspace Trust: source-only comparison pass (2026-09-24)

Short pass requested by the owner before P2c's FlowGate/IG02 design is locked: VS Code's Workspace Trust is its answer to "how much should an untrusted environment be allowed to do automatically" — the general version of our IG02 problem. `READ V/path:N` is 1-based in `vscode-src/` (repo root `src/vs/`). Nothing here changes any gap ranking; it is design input for P2c, plus one live-status verification of TP02.

## 1. VS Code's Workspace Trust design (verified in clone)

**The trusted object is the WORKSPACE CONTENT (folder URIs), and the default is UNTRUSTED.** A tri-service architecture:

| Piece | Evidence | What it does |
|---|---|---|
| Core contracts | READ V/src/vs/platform/workspace/common/workspaceTrust.ts:1-60 — `WorkspaceTrustScope { Local, Remote }`, `WorkspaceTrustRequestButton` types `ContinueWithTrust/ContinueWithoutTrust/Manage/Cancel`, `IWorkspaceTrustEnablementService` | Trust is per-folder-URI with Local/Remote scopes; the ask-dialog has a fixed button vocabulary including an explicit "continue WITHOUT trust" choice |
| Management service | READ V/src/vs/workbench/services/workspaces/common/workspaceTrust.ts:90 (`class WorkspaceTrustManagementService`), :635 `setTrustedUris`, :856 `requestWorkspaceTrust`; storage-backed (`WORKSPACE_TRUST_STORAGE_KEY`), `onDidChangeTrust` / `onDidChangeTrustedFolders` events; `workspaceTrustEditor.ts` for management UI | Persistent per-folder trust list, parent-folder grants, change events that flip feature availability live, a dedicated editor to manage trusted folders |
| Request service | `requestWorkspaceTrust(options)` returning `boolean \| undefined` | ONE shared ask-choke-point consumed by every executing feature |

**Enforcement is placed at the FEATURE-ENTRY of every code-executing surface — verified call sites:**

- **Debug/Run:** READ V/src/vs/workbench/contrib/debug/browser/debugService.ts:353-358 — `startDebugging` awaits `requestWorkspaceTrust({ message: "Debugging executes build tasks and program code from your workspace." })` and **returns false (refuses to start) if untrusted**.
- **Tasks:** READ V/src/vs/workbench/contrib/tasks/browser/abstractTaskService.ts:2393 — custom tasks from an untrusted workspace are not even ENUMERATED (`if (!knownOnlyOrTrusted || isWorkspaceTrusted())`).
- **Terminal:** READ V/src/vs/workbench/contrib/terminal/browser/terminalInstance.ts:1971-1980 (`_trust()`) — creating a terminal PROCESS requires trust ("Creating a terminal process requires executing code"), **with a documented, user-owned bypass setting** (`TerminalSettingId.AllowInUntrustedWorkspace`).
- **Chat/agent sessions:** READ V/src/vs/workbench/contrib/chat/browser/agentSessions/agentHost/agentHostSessionHandler.ts:6299,6317 — `isWorkspaceTrusted()` checks gate agent sessions; also chatSetup, promptSyntax promptsService, and the plugin marketplace.
- **MCP:** `contrib/authentication/browser/actions/manageTrustedMcpServersForAccountAction.ts` — MCP servers are a per-account TRUSTED list with management UI.
- **Extensions:** `extensionEnablementWorkspaceTrustTransitionParticipant.ts` — extensions enable/disable on trust transitions.

**The invariant: reading/editing files is always allowed; EXECUTION is gated, and the gate is a single shared request-choke-point, not N ad-hoc dialogs.**

## 2. Our situation, mapped against that design

| VS Code concept | Our analog | Status |
|---|---|---|
| Trusted object = workspace content | **Project content (cloned repo, npm postinstall, curl'd scripts in proot)** — the object that actually carries untrusted code on this device | **MISSING ENTIRELY.** No trust state exists anywhere in the app |
| Trust axis = the ACTOR | Our FlowGate gates AI-initiated commands (per-command consent card, always-allow) | We have the actor axis but NOT the content axis |
| Single requestWorkspaceTrust choke point | Nothing — FlowGate is inline in the chat terminal path only | IG02 (schedule_task) and TP02 (AgentApiServer) BYPASS FlowGate entirely because there is no shared choke point to bypass |
| Default-untrusted + explicit grant | N/A — absent | The reason IG02/IG15 read as "bypasses": there is nothing to bypass, so they're just ungated |
| Documented user-owned bypass setting (terminal AllowInUntrustedWorkspace) | IG02's silent bypass is the anti-pattern version of this | VS Code shows the right shape: a VISIBLE, user-owned toggle — not a code path nobody asked about |
| Trust events flipping features (onDidChangeTrust) | Nothing analogous | A per-project trust grant could flip FlowGate's card frequency, scheduler permission, MCP connect in one event |

**The mismatch, stated plainly:** VS Code asks "should this CONTENT be allowed to execute?" at every process-creation boundary. We only ever ask "should this ACTOR (the AI) run this command?" — and only on ONE of the four execution surfaces. IG02 (scheduled exec), TP02 (any guest process → full toolset), CH05 (ungated direct write), and IG15 (connector use without per-call consent) are all the SAME missing content-axis, viewed from four surfaces.

**One nuance worth keeping:** VS Code gates terminal PROCESS CREATION; our interactive proot terminal is user-driven and should NOT be gated — the VS Code terminal's own bypass setting shows even they treat interactive use as user-vouchable. Our gated surfaces should be the AUTOMATED ones: scheduler, agent tools, the local tool API, debugger/task launch, MCP session start, connector use.

## 3. P2c design implications (input for the locked design, not executed)

1. **Add a per-project TrustState to the P2c workstream**: default UNTRUSTED, persisted in the `projects` index (must therefore come after P0/SK01 write-safety), one ask dialog with VS Code's button vocabulary (Trust / Continue without trust / Manage), parent-path not needed (single-project model — simpler than VS Code's URI list).
2. **One shared request choke point** (`WorkspaceTrust.requestExecution(feature)`-shaped) consumed at: FlowGate (pre-check), AgentScheduler (gate at SCHEDULE time and refuse at RUN time when untrusted), debugger/task launch, MCP session start, use_connector (IG15), and AgentApiServer's tool route (paired with, not replacing, its auth fix).
3. **Trust ≠ authentication.** TP02's fix remains loopback bind + per-session token (authentication of the caller). Trust decides what an authenticated caller may do unattended (authorization). Workspace Trust informs IG02's design; it does not absorb TP02.
4. **CH03 approval-card redesign gets a "Trust this project" quick-action** — the VS Code shape suggests the card-fatigue problem and the trust problem share one UI moment.
5. **IG02's fix becomes**: schedule-time trust check + run-time trust re-check + FlowGate routing, instead of just "add a consent dialog to schedule_task".

## 4. TP02 live-status verification (requested alongside, 2026-09-24)

**Source-verified LIVE on every app launch — no toggle gates it.** READ A/CodeSpaceApplication.kt:74 — `AgentApiServer.start(this)` runs unconditionally in Application startup; READ A/agent/AgentApiServer.kt:45-48 — `ServerSocket(PORT)` with no bind address (Java default = all interfaces), port 8765; :157-170 — POST /tool/{name} routes straight to `AgentTools.executeTool` with no auth, no FlowGate, no R6 staging; READ A/terminal/McpShellProfile.kt:35 — the bashrc profile re-starts it as a fallback and advertises `AGENT_API_URL=http://localhost:8765` in every shell.

Reachability difference vs TP01: TP01 was internet-facing (probeable remotely — and probed); TP02 is **device-local**, so it cannot be probed from this sandbox — only from the phone. Two live channels: (a) any proot guest process reaches the host loopback (shared network namespace) — npm postinstall, curl'd scripts, prompt-injected CLI output; (b) LAN peers via the all-interfaces bind. **On-device proof (read-only, no secrets dumped), run in the app's own terminal pane — which IS the guest environment, so a successful call proves the attacker path end-to-end:** `curl -s http://localhost:8765/tools` (expect the JSON tool list; do NOT call get_secret). LAN half, from another device on the same Wi-Fi: `curl -s http://<phone-ip>:8765/tools`.

**Recommendation (mirrors TP01 handling): immediate hotfix, outside the phase plan.** Unlike TP01 this server has live callers (McpShellProfile, CLI agent wrapper), so hardening rather than removal: loopback bind + per-session random token + token check on every route, exported to the guest shell profile so legitimate CLI use carries the token. Small diff, revertable alone. Awaiting owner approval; no code written.
