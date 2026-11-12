# MCP and Connectors Lifecycle — 2026-11-12

Baseline: `1c10232` (CI 43125607414 GREEN). Scope: make MCP servers discoverable, addable,
connectable, disconnectable and removable, with unambiguous tool routing and no weakening of the
connector approval model. No APK was built; device testing stays deferred to the consolidated round.

## 1. What already existed (audited, not rebuilt)

`agent/McpClientManager.kt` — one manager for config, sessions, tool cache and dispatch:

- Config at `filesDir/mcp_servers.json`: name, command, enabled, disabledTools, envKeys (env values
  are `SecureTokenStore` keys, never raw secrets).
- Transport: stdio JSON-RPC 2.0 inside the Ubuntu/proot session, one persistent `McpServerSession`
  per server, `PROTOCOL_VERSION 2024-11-05`, 30 s init and 60 s call timeouts.
- Discovery: lazy, once per process, `tools/list`, cached under `mcp_<server>_<tool>`, plus
  best-effort `prompts/list` surfaced as read-only skills.
- Execution: `callTool` from `AgentTools`, gated by `AgentFlowGate`; MCP tools are excluded from
  AUTO_ALL in `ChatPermissionStore.decideAutoApproved` and never inherit an approval.
- Prompt integration: `docsFor(names)` for the subset a task loaded, `toolDocs()` for the panel.
- UI: `ui/panes/McpServersSection.kt` — list, enable switch, per-tool switches, add, delete-confirm,
  env-var dialog, Refresh, runtime install, inline discovery errors.

## 2. Gaps found

1. **Wrong-server routing.** `callTool` resolved the server with `fullName.startsWith("mcp_<name>_")`
   and took the FIRST match. Two servers can spell the same key (`files`+`b_x` vs `files_b`+`x`), so
   the call could execute on the wrong server silently.
2. **Unvalidated add.** `addServer(name)` ran the name through a regex that turned `my server` into
   `my_server`, accepted `mcp_whatever` (which produces `mcp_mcp_...` keys), and reported every
   refusal as "name blank or duplicate".
3. **Config not atomic.** `saveConfig` used `writeText`; one interrupted write loses every server.
   A corrupt file parsed to an empty list, which is indistinguishable from "no servers".
4. **Stale tools after a server died.** Tools stayed in the cache and kept being offered after the
   process exited, so the model called a tool nobody was listening for.
5. **Discovery latch vs cache.** `ensureDiscovered` returned early on the process latch, so a session
   whose cache had been pruned reported "no MCP tools" even though servers were connected.
6. **Docs lost without a Context.** `docsFor(context, names)` required a non-null Context; headless
   continuations got empty docs for tools the model had just discovered.
7. **Untrusted results reached the model raw**, with no size cap.
8. **No explicit connect/disconnect**, and the row could not distinguish "not started" from "died".
9. **Arbitrary schemas** were passed through; a non-object `inputSchema` rendered argument lines that
   did not exist.

## 3. Implementation

New: `agent/McpLifecycle.kt` — the pure rules, with no Android imports so the JVM tests exercise the
real code: name/command/add validation, `key`, `resolve` (cache-first, longest-name fallback),
`sanitizeSchema`, `pruneStale`, `wrapResult` (boundary markers + 8,000-char cap), `describeFailure`.

Changed:

| File | Change |
|---|---|
| `agent/McpClientManager.kt` | atomic config write via `AtomicJson`; corrupt config quarantined to `mcp_servers.json.corrupt-<ts>`; `addServerChecked` returns the refusal reason; `setCommand` validates and returns a reason; `connect`/`disconnect`; `stateOf` (DISABLED/STOPPED/RUNNING/FAILED); routing through `McpLifecycle.resolve`; stale-tool pruning in `cachedToolNames`; `sanitizeSchema` in both discovery paths; `docsFor(context?, names)` answers from a last-enabled snapshot when there is no Context; `ensureDiscovered` re-discovers when an enabled server has no tools and no recorded failure |
| `chat/panel/ChatSession.kt` | docs are no longer dropped when `appContext` is null |
| `ui/panes/McpServersSection.kt` | explicit Connect/Disconnect per server; spelled-out state line; the add dialog shows the real validation message |
| `agent/McpLifecycle.kt` (new) | the pure rules above |

## 4. Security decisions

- No new bypass. MCP tools remain outside AUTO_ALL and outside the per-tool allowlist
  (`decideAutoApproved` returns false for anything starting `mcp_`); the connector rule still forces
  approval for connector use. `ConnectorApprovalTest` asserts both and was not modified.
- Credentials stay where they were: `envKeys` holds `SecureTokenStore` KEYS, values are read at spawn
  and never written to config, logs, tool descriptions or prompts.
- Server output is now wrapped in `<mcp_result server="…" tool="…">` and capped, so a server cannot
  pose as instructions and one runaway answer cannot consume the context window.
- Server-supplied `inputSchema` is normalised, so the definition line cannot advertise arguments the
  server does not accept.
- Trust and runtime gates are unchanged: a server is never spawned in an untrusted project, and a
  missing runtime is reported instead of silently failing.

## 5. Tests

New `app/src/test/java/com/codespace/ide/agent/McpLifecycleTest.kt` — 21 tests: validation (blank,
spaces, slashes, reserved `mcp_` prefix, overlong, duplicates case-insensitive), key/round-trip,
cache-wins-over-lookalike, longest-name fallback, non-MCP names not routed, schema sanitising
(null, no properties, required cleaned), stale pruning, result wrapping, truncation note, failure text.

Focused runs: `com.codespace.ide.agent.*` + `com.codespace.ide.chat.panel.*` → 966 tests, 0 failures.
Full suite: 2,079 tests, 1 failure — `BackupTarTest.failureMessageCarriesRealTotals`, which fails only
because the sandbox runs as uid 0 (root can read the files the test needs unreadable); it passes in CI.

## 6. Known limitations

- Only the stdio transport is implemented, because it is the only one the proot session can host and
  test. HTTP/SSE servers are not supported and are not offered in the UI.
- Duplicate tool names across servers are namespaced by server, so they are distinct; the residual
  ambiguity is a server name that is a prefix of another ending at an underscore, which the cache
  resolves and the longest-name fallback handles only as a best guess.
- Discovery is per process. A session started after a restart re-discovers on first chat; there is no
  background poller, deliberately, to keep mobile data and battery use predictable.
