# How VS Code handles tool calls — read from source

Date: 2026-11-05. Sources are the local mirrors: `vscode-copilot-chat/` (MIT) and `vscode-src/` (MIT).
Every claim below carries a `file:line` that was actually opened.

## 1. Tools are OFFERED, not assumed

The request handler asks for the tool set as part of building the prompt:
`getAvailableTools()` returns `LanguageModelToolInformation[]`
(`vscode-copilot-chat/src/extension/prompt/node/defaultIntentRequestHandler.ts:732`).

## 2. Above a threshold, tools are GROUPED into virtual tools

This is the part worth copying.

- A hard limit exists: `HARD_TOOL_LIMIT = 128`
  (`src/platform/configuration/common/configurationService.ts:510`), exposed as the experiment-based
  setting `chat.virtualTools.threshold` (same file, line 959).
- When grouping is active, Copilot builds a virtual tool TREE: `ToolGrouping`
  (`src/extension/tools/common/virtualTools/toolGrouping.ts:28`) whose root is itself a virtual tool.
  A virtual tool is described to the model by a `description` with `inputSchema: undefined`
  (`virtualTool.ts:108-109`) - it is a handle, not a real tool.
- Selection is computed per prompt: `toolGrouping.compute(request.prompt, token)`
  (`defaultIntentRequestHandler.ts:749`). If that takes longer than 1000 ms the user sees progress
  text "Optimizing tool selection..."
  (`defaultIntentRequestHandler.ts:750-757`) - an honest wait signal rather than a frozen panel.
- Groups EXPAND on use: `didCall(turn, toolName)` walks the tree and marks `lastUsedOnTurn` for every
  node on the path (`toolGrouping.ts:63-75`), so a tool that was just used is in the offered set next
  turn instead of being re-hidden.
- Explicit references win: `request.toolReferences` are expanded before compute
  (`defaultIntentRequestHandler.ts:745-747`), i.e. a `#tool` mention forces that tool in.

Net effect: with 32 tools nothing is grouped; with 200 tools the model sees a handful of handles and
the ones it used recently, and the prompt stays affordable.

## 3. Every invocation is a STATE MACHINE

`IChatToolInvocation.StateKind`: `Streaming`, `WaitingForConfirmation`, `Executing`,
`WaitingForPostApproval`, `Completed`, `Cancelled`, `WaitingForAuthentication`
(`vscode-src/src/vs/workbench/contrib/chat/common/chatService/chatService.ts:897-910`), with
explicit predicates for done/cancelled (`chatService.ts:1077-1090`). Approval and authentication are
STATES of the invocation, not a separate UI concept.

## 4. Each invocation renders itself

`ChatToolInvocationPart` (`browser/widget/chatContentParts/toolInvocationParts/chatToolInvocationPart.ts:80`)
owns its own title, subtitle and collapsible body, and list rendering is pooled
(`CollapsibleListPool`) so a long transcript does not pay per-row construction cost.

## What VN Code does today, and the decision

| Aspect | VS Code | VN Code | Decision |
|---|---|---|---|
| Tool offering | subset above 128 via virtual tools | full registry every turn (`AgentTools.toolNames()`) | fine at 32 builtins; revisit if MCP + connectors push past ~40 |
| Selection progress | "Optimizing tool selection..." after 1 s | none | not needed while everything is offered |
| Per-tool state | 7 explicit states incl. approval/auth | tool rounds only; approvals are a separate card | adequate now, recorded as a divergence |
| Rendering | one collapsible part per invocation, pooled list | ToolActivity rows, now grouped with a chevron (3562) | matches the intent |
| Discovery | user `#tool` references force a tool in | `/tools` lists what the chat can call (this commit) | done |

The one thing NOT to copy now: virtual tool grouping. It exists to survive a tool count this app does
not have, and it costs an embeddings pass per prompt. The trigger for building it is written above.
