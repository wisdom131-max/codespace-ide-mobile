# P8.D - MCP calls ask every time

The spec is explicit: *MCP calls always require explicit approval, and AUTO_ALL must not bypass MCP
approval.* They did not.

## The hole

`AgentFlowGate.requiresForcedApproval` forced a card for exactly one tool: `use_connector`. Every
`mcp_*` tool fell through to the ordinary rule, `!(autoApproved || sessionAllowed)`, so a level that
auto-approves tool calls reached a third-party MCP server without a card. `ChatPermissionStore` even
notes that MCP tools "always respect the level" - the level was the problem.

## Why it matters

An MCP tool is not a local tool. It runs on a server the owner connected, with whatever that server
holds and whatever access its credentials grant. "Always allow" a local file read is not consent for a
remote one, and AUTO_ALL was silently converting one into the other.

## The fix

`requiresForcedApproval` now forces `use_connector` **or** any name starting with `mcp_`, which is the
namespace `McpLifecycle` assigns. Because the whole gate funnels through `mustAsk`, no call site needed
changing: the session-level approval path, the auto-approval path and AUTO_ALL all ask now.

A name that merely mentions MCP (`search_mcp_docs`) is not forced - the rule is the namespace prefix,
not a substring - which is asserted so the exemption cannot spread.

## Tests

`McpApprovalForcedTest` (4): an MCP tool asks even when auto-approve and session-allow are both true,
`use_connector` still asks, ordinary tools keep their level, and a name that merely mentions MCP is not
forced. `AgentFlowGateScopeTest` still passes unchanged.
