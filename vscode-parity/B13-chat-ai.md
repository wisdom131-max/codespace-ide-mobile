# B13 — CHAT & AI / COPILOT

> VS Code parity research, batch 13 of 14.
> VS Code: `microsoft/vscode` @ `main` — chat subtree verified live 2026-09-13 (workbench/contrib listing: chatEditing*, inlineChat/, chatTerminalCommandPaste, contextContrib, chatStatus*, chatQuotaNotification, promptSyntax/, agentPluginsView, chatSessions/, voiceClient + speechToText + pcmCaptureWorklet, chatScreenshotContext, sessionPullRequestPill) + Copilot-chat parity catalog v2 (2026-09-12).
> Ours: `codespace-ide-mobile` @ `d64ef66`, ls/grep-verified today: `chat/` (21 files + 7 providers), `agent/` (10 files), ui/screens chat surfaces.

## §0 Scope — this batch SYNTHESIZES

B13 merges three earlier threads: (1) the 14-touchpoint integration map (I1-I6, 2026-09-13), (2) the shipped R1-R9 + multi-key + custom-endpoint state, (3) chat-context edges found in B07 (SCM history), B11 (problems), B06 (terminal transcript).

## §1 What VS Code has (citations)

**Panel core**: sessions, modes (Ask/Edit/Agent + custom .agent.md), permission levels (Default/Assisted/AutoApprove/Autopilot), Auto model + effort tiers + pinning, attach-context (files/symbols/search/screenshots/git/terminal/problems), ~20 slash commands, @workspace participants, 13 skills, 38 tools, 50+ render parts (thinking/tool-calls/multi-diff/checkpoints/todos/plan-review/follow-ups/feedback/retry), find-in-chat, export/import, queue.

**14 touchpoints OUTSIDE the panel** (integration map): in-editor review overlay + checkpoint timeline (`chatEditing*`), editor-zone inline chat (`inlineChat/`), terminal command-paste→chat (`chatTerminalCommandPaste`), context contributors (`contextContrib`), status dashboard (`chatStatus*`), quota/promo notifications (`chatQuotaNotification`), prompt FILES w/ codelens + hooks + skills view (`promptSyntax/`, `agentPluginsView`), durable chat sessions as editor TABS (`chatSessions/`), voice (`voiceClient`, `speechToText`, `pcmCaptureWorklet`), screenshot attach (`chatScreenshotContext`), session PR pill (`sessionPullRequestPill`).

## §2 What OUR app has (verified today)

**Core stack** (chat/): 7 providers — OpenAI, Anthropic, Gemini, DeepSeek, OpenRouter, Xai, Custom (OpenAiCompatibleTransport) — all BYOK; `ChatKeyPool` (unlimited keys/provider, manual+auto failover via `ChatKeyFailover`), `CustomEndpointStore` (+ manual model-ID escape hatch, WAF/Cloudflare classification, F4/F5/F6 fixes shipped), `ChatModelSelection` (Auto + pinning + per-mode memory), `ChatSlashCommands` (R1), `ChatContextSources` + `ChatAttachment` (files/selection/search/images ≤10MB/audio MP3-WAV), `ChatImageAttachments`, `TokenCounter` + context gauge, `ChatInputHistory`, `ChatPlanStore` (plan guard, R7), `SkillsCatalog` + `CustomModeStore` (R9), `PendingChangesStore` (R6 staging + checkpoints + drift-closed apply + undo), `ScmCommitMessageService` (I4), `SearchResultsAttach` (B05).

**Agent layer** (agent/): `AgentTools` (31 built-in + `mcp_<server>_<tool>` bridge — 72 entries incl. docs), `AgentFlowGate` + `ChatPermissionStore` (3 levels + always-allow), `AgentMemory`, `AgentScheduler`, `AgentApiServer` (CLI /system-prompt + endpoints), `AgentConnectorManager`, `AgentEntityManager`, `AutoInstructionsProvider` (R2), `McpClientManager` (B10's plugin boundary).

**UI**: inline panel + mobile chip system, `ChatModelMenuButton`, `ChatApprovalCard` ("Always allow <tool>"), `ChatErrorBubble` w/ raw-response expand, `ChatToolChip`, `ChatDiffReviewCard` (R6), find-in-chat (R7), attach picker (search bar + scrollable, F5), voice-to-text logging (R8), toast + notification routing (B12).

**Editor integration**: ghost-text, selection attach (`EditorSelectionStore`, R4), in-editor review card at transcript end (R6's shape — VS Code's is in-editor overlay + timeline, ours is transcript card), OSC 7777/terminal transcript context (B06), run_command staging discipline (R6).

## §3 Verdict table (condensed — v2 catalog + touchpoint map cross-checked)

| Feature (VS Code) | Verdict | Gap |
|---|---|---|
| Sessions + search + filter | HAVE | R7 |
| Modes incl. custom | HAVE | R9 (.agent.md) |
| Permission levels + always-allow | HAVE | R5 |
| Model picker: Auto, pinning, per-mode | HAVE | R5 |
| Multi-key + failover | HAVE-UNIQUE | VS Code has no BYOK surface at all (B10) |
| Custom endpoints + manual model IDs | HAVE-UNIQUE | CE-ESCAPE F6 |
| Attach: files/selection/search/images/audio | HAVE | R3/R4/R9; video no |
| Attach: symbols | PARTIAL | LSP symbols reachable; no picker row §6 |
| Attach: terminal transcript | HAVE | I3/TerminalAiBridge |
| Attach: problems / SCM history | MISSING | 2 of 3 chat-context edges unconnected (B11/B07 rows) |
| Attach: screenshots | MISSING | chatScreenshotContext analog §6 |
| Slash commands | PARTIAL | R1 core set; not extensible |
| Skills + prompt files | HAVE | SkillsCatalog; codelens/hooks partial (R9 walkthrough) |
| Tools (38 vs 31+MCP) | PARTIAL | count close; per-tool render parts thinner |
| Thinking/tool-call/diff/error render parts | PARTIAL | tool chips + error bubble + diff card HAVE; todos/plan-review/follow-ups/feedback/retry parts missing |
| Checkpoints + checkpoint timeline UI | PARTIAL | R6 checkpoints exist; no timeline UI (chatEditing timeline) |
| Inline chat in editor zone | MISSING | inlineChat/ analog parked (PssEditorColumn could host) |
| Staged apply + review (chatEditing) | HAVE | R6 = our strongest parity surface |
| Voice (TTS/STT pipeline) | PARTIAL | R8 logging + audio attach; no STT pipeline (voiceClient/speechToText) |
| Chat sessions as editor tabs | MISSING | chatSessions/ — sessions are panel-owned |
| Status dashboard / quota notifications | MISSING | chatStatus*/chatQuotaNotification |
| PR pill | MISSING | parked w/ integration map |
| In-editor review overlay | PARTIAL | transcript card vs in-editor — deliberate mobile shape |
| Find-in-chat | HAVE | R7 (+F2/F3 fixed) |
| Export/import sessions | MISSING | — |
| Queue messages | MISSING | — |
| Retry / feedback thumbs | MISSING | — |

## §4 Cross-subsystem connection edges (the B13 net)

- **Three unconnected context edges**: problems (B11 markersChatContext), SCM history (B07 scmHistoryChatContext), + terminal transcript (connected). Two rows in the attach picker would close them — cheapest remaining chat-parity work.
- chat ↔ **B10**: MCP tools = plugin boundary; skills/modes = installables; a pack format would unify.
- chat ↔ **B01/B08/B11**: open-at-line entry shared by OSC 7777, stack links, output links (when built).
- inlineChat ↔ **B12**: editor-zone chat would live in PssEditorColumn — placement decided by B12's layout model, not chat code.
- chatSessions ↔ **B03**: sessions-as-tabs rides the tab system if ever built.
- chatStatus/quota ↔ **B12 notifications**: provider errors → notification center w/ retry actions (pattern note from B12).

## §5 Cheap wins vs real projects (for final review)

**Cheap**: problems + SCM-history attach rows; retry button; export session (share text); screenshot attach (MediaProjection? — verify feasibility); quota/cooldown surfacing in status bar.
**Real projects**: inline chat zone; checkpoint timeline UI; voice STT pipeline; sessions-as-tabs; todos/plan-review render parts.

## §6 Open questions / on-device verification

1. Symbols attach: worth a picker row (LSP workspace symbols already exist)?
2. ChatPlanStore: does plan guard cover agent mode edits (R6 discipline) end-to-end?
3. Session size: at what transcript length does the panel recompose badly? (perf budget — PerfProbe idle-gate could measure)
4. Voice: device STT engine (Android SpeechRecognizer) viable as speechToText analog?
5. Export format: markdown transcript vs JSON — which matches import expectations if we ever import?

## Status

**DONE** — 2026-09-15. Next: B14 Tasks, run & lifecycle. Then FINAL REVIEW.
