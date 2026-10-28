# U2 — Chat feature inventory and VS Code gap analysis

Written 2026-10-28 for the build-657 remediation. Two questions, answered from the code and
from git history only, no memory of intent:

1. **What does the rebuilt chat do today?**
2. **What did it stop doing, and how far is it from VS Code's chat?**

Evidence base: `git log --diff-filter=D` on `chat/` (41 deleted `.kt` files, four commits),
`wc -l` on the current sources, and targeted greps for each capability. VS Code side: official
documentation plus four screenshots pulled from the docs site and stored in `docs/vscode-refs/`
(dimensions verified from the PNG headers, captions are the docs' own alt text).

---

## 1. What the rebuilt chat has

3,887 lines across 22 files. Grouped by what a user can actually do:

| Area | State | Evidence |
|---|---|---|
| Send / stream / cancel | Works. Thinking row with live seconds, cursor while streaming | `ChatSession.kt`, `ChatRowModel.Thinking`, `ChatCards.StreamingText` |
| Retry after failure | Works (`canRetry` on the failed row) | `ChatRowModel.Failed`, `ChatSession.retry()` |
| Providers | Gemini, Anthropic, OpenAI-compatible presets (OpenRouter, DeepSeek, Groq, Mistral, xAI, Ollama), plus user-defined custom endpoints | `engine/` adapters, `CustomEndpointStore.kt` |
| Keys | Real slots (`ai_GEMINI`, `ai_MYKEY`), keys manager, multi-key pool with opt-in failover, legacy read-only migration | `keys/SlotNaming.kt`, `keys/StoredCredentialsReader.kt`, `KeysManagerDialog.kt` |
| Model choice | Live-fetched model lists, bundled catalog with context window and image/PDF capability, manual ids, reasoning-effort control | `engine/ModelCatalog.kt`, `ModelListFetcher.kt`, `CustomEndpointEditor.kt` |
| Honest failure cards | No credits (402), rate limit with wait, offline, over-budget with "Send anyway?", visible key-switch notes, trimmed-history note | `ChatCards.kt` (11 card types) |
| Attachments | A file is read, text kinds sent as text, everything else base64. Sniffer and secrets redaction exist | `ChatPanelScreen.readAttachment`, `AttachmentTypeSniffer.kt` (259), `AttachmentSecrets.kt` (163) |
| Pending changes | Store exists with apply/reject and staging | `PendingChangesStore.kt` (458) |
| Settings | Per-provider model, context override, key management | `ChatSettingsPrefs.kt` |

That is a credible *chat*. It is not yet a *coding agent*, which is the problem below.

---

## 2. What the rebuild removed

Four commits deleted 41 chat files. Mapping each to what replaced it, or not:

| ID | Removed capability | Lines | Replacement | Severity |
|---|---|---|---|---|
| REG-01 | **Tool calling.** `NativeToolProtocol.kt` (wire format for `functionCall`/`tool_calls`) | — | **None.** Zero `tool`/`functionCall`/`tool_calls` references in `chat/panel/` or `engine/` | **Critical** |
| REG-02 | **Document text extraction**: DOCX/XLSX/PPTX/ODT/HTML/CSV/RTF via the hand-rolled linear XML scanner | 773 | None | **Critical** |
| REG-03 | **PDF extraction**: text layer + up to 3 rendered page images, page/char/size caps, OOM caught | 374 + 132 gate | None | **Critical** |
| REG-04 | **Archive reading**: zip listing, entry chooser, 500-entry cap, zip-bomb ratio guard, entry names never used as paths | 234 + 122 | None | High |
| REG-05 | **Attachment intake pipeline**: source caps, snapshot caps, per-file/per-message send caps, UNTRUSTED FILE CONTENT labelling, eviction why-markers, sheet notices | 111 + 52 + 296 | Partial: `readAttachment` caps bytes and sniffs type, but no sheet, no send caps, no untrusted labelling | **Critical** |
| REG-06 | **Staged file import from the app** (open a file from Explorer into chat) and **search-results attach** | 128 + 65 | None | Medium |
| REG-07 | **Input history** (per-project, up to 100 entries, dedup, corrupt-payload quarantine) | ~150 | None; `ChatInputHistory.kt` deleted | Medium |
| REG-08 | **Plan store** (`ChatPlanStore`) — plan mode state | — | None; `AgentTools.kt` still references plan staging | High |
| REG-09 | **Hardened prefs backend** (`ChatPrefsBackend`): `commit()`-based typed writes, corruption quarantine, honest failure | — | None; settings writes are fire-and-forget | High |
| REG-10 | **Token counter and timing log** | — | Partial: over-budget estimate only, no token accounting, no timing instrumentation | Low |

REG-01 is the one to read twice. `AgentTools.kt`, `AgentFlowGate.kt` and `ChatPermissionStore.kt`
all still exist and still work, and `AgentTools` still stages into `PendingChangesStore`. But
nothing in the new chat calls them: the panel never advertises tools to a provider and never
parses a tool call back. The rebuilt chat therefore **cannot read a file, write a file, run a
command, or use an MCP server**, which is precisely the "ask it to do something in my project"
behaviour that items 5-10 were supposed to deliver. The plumbing survived; the wire was cut.

---

## 3. VS Code reference images

Stored locally so they survive on device (`docs/vscode-refs/`), with the official source.

**Ref-1 — Chat view: sessions list, conversation, input.**
`docs/vscode-refs/chat-view-expanded.png` (1560x807)
Source: https://code.visualstudio.com/assets/docs/agents/agents-overview/chat-view-expanded.png
Docs caption: "the Chat view with the sessions list, conversation, and chat input."
Reference for: three-zone layout. Sessions are a first-class column, not a menu; the input is
pinned at the bottom and never collapses.

**Ref-2 — Context picker (the `#` mention surface).**
`docs/vscode-refs/copilot-chat-view-chat-variables.png` (1105x499)
Source: https://code.visualstudio.com/assets/docs/chat/copilot-chat/copilot-chat-view-chat-variables.png
Docs caption: "the chat variable picker."
Reference for: context is *selected from a picker* over the workspace, with the chosen items
shown as chips in the input.

**Ref-3 — Send button dropdown: queue, steer, stop and send.**
`docs/vscode-refs/send-dropdown.png` (749x249)
Source: https://code.visualstudio.com/assets/docs/chat/chat-sessions/send-dropdown.png
Docs caption: "options to queue, steer, or stop and send a new message."
Reference for: while a request is running, the input stays usable and the send action branches
into three explicit choices instead of a single disabled button.

**Ref-4 — Pending messages with reorder handles.**
`docs/vscode-refs/pending-messages.png` (759x450)
Source: https://code.visualstudio.com/assets/docs/chat/chat-sessions/pending-messages.png
Docs caption: "pending messages in the chat input box with drag handles to reorder them."
Reference for: queued messages are visible, editable and reorderable before they are sent.

---

## 4. VS Code parity gaps

Not every gap should be closed: VS Code is a desktop IDE with an extension host and cloud
models. Each row says what the gap is and whether it matters *for a phone*.

| ID | Gap | VS Code reference | Ours today | Priority |
|---|---|---|---|---|
| VS-01 | **Agent mode with tools** (read/write files, run commands, MCP) | Ref-1 | REG-01: no tool wire at all | P0 |
| VS-02 | **`#` context mentions** (files, symbols, problems, terminal output) | Ref-2 | Absent; context arrives only as attached files | P0 |
| VS-03 | **Queue / steer while streaming** | Ref-3 | Only Stop; input is disabled during a request | P1 |
| VS-04 | **Pending message list with reorder** | Ref-4 | Absent | P2 |
| VS-05 | **Sessions list, multiple parallel conversations** | Ref-1 | One conversation, `clearConversation` only | P1 |
| VS-06 | **Slash commands** (`/explain`, `/fix`, skills) | — | Absent (R10-B history handled `/` as plain text before) | P1 |
| VS-07 | **Session export** | — | Absent | P2 |
| VS-08 | **Find inside the conversation** | — | Absent | P2 |
| VS-09 | **Checkpoints / revert to a previous turn** | — | Absent | P1 |
| VS-10 | **Image attachments** with carousel | Ref-1 | Sniffed and base64-sent, no carousel, no per-model vision gating beyond the catalog line | P1 |
| VS-11 | **Document/PDF/archive attachments** | Ref-2 | REG-02/03/04: gone | P0 |
| VS-12 | **Token and cost visibility** | — | Over-budget estimate only (REG-10) | P2 |
| VS-13 | **Edit a previous message** | — | Absent | P2 |
| VS-14 | **Copy code block** | — | Copy exists; per-block copy button absent | P3 |
| VS-15 | **Voice mode** | — | Out of scope for this phase | P3 |
| VS-16 | **Inline / quick chat surfaces** | — | Out of scope (different surface) | P3 |

### Honest notes on the reference images
- Ref-1 is the *Agents* window layout, which is what current VS Code docs show for the chat
  view; it is wider than a phone. What transfers is the ordering and persistence of the three
  zones, not the widths.
- The `#` picker (Ref-2) is a desktop popover over a file tree. On a 6-inch screen it has to
  become a full-height sheet with search first, because there is no hover and no second pane.
- Queue/steer (Ref-3, Ref-4) assume a persistent desktop input. On mobile the keyboard is the
  scarce resource, so the equivalent is: keep the field mounted, and make Send a two-choice
  action (queue vs stop-and-send) rather than a disabled button.

---

## 5. What this means for the order of work

The gap table says the rebuild's failures are not cosmetic. In dependency order:

1. **VS-01 / REG-01 — restore the tool wire.** Everything agentic depends on it, and the
   permission, staging and gating layers are already built and tested. Highest value per line.
2. **VS-11 / REG-02-05 — restore the attachment pipeline.** 2,090 lines were deleted; the
   extractors are pure JVM and were covered by fixtures, so this is restoration plus re-wiring,
   not new design.
3. **VS-02 — context mentions.** This is the input-side of the same idea as VS-01: let the
   agent see the file the user is looking at without an attach round-trip.
4. **VS-03/VS-09/VS-05 — streaming control, checkpoints, sessions.** These are what make a chat
   usable for real work rather than single questions.
5. **REG-07/REG-09 — input history and the hardened prefs backend.** Small, self-contained,
   and REG-09 is a correctness issue (silent write failures).

Items 1-2 are the owner's original items 5-10 in substance. They were not delivered by the
rebuild; they were deleted by it.
