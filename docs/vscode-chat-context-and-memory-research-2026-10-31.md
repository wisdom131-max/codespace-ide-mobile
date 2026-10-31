# VS Code chat: how context and memory actually work, and what VN Code does instead

Research note, 2026-10-31. Written for items 10 / 16 and for the "the assistant forgets my
conversation" report. Everything below is either **VERIFIED** from a page listed in the
Sources section, or explicitly marked **NOT VERIFIED** / **INFERRED**. Nothing here is
presented as knowledge of Copilot's closed source.

## 1. What was actually read

| Source | Layer | What it gave |
|---|---|---|
| `code.visualstudio.com/docs/agents/reference/workspace-context` (HTML and the `.md` variant) | VS Code docs | semantic search `#codebase`, index sources, fallback behaviour |
| `code.visualstudio.com/docs/chat/copilot-chat-context.md` | VS Code docs | context item types, `#`-mentions, context picker, drag-drop |
| `code.visualstudio.com/docs/copilot/reference/copilot-vscode-features` | VS Code docs | feature inventory (nav only, see limits) |
| `code.visualstudio.com/docs/copilot/concepts/memory.md`, `.../docs/agents/concepts/memory.md` | — | **404**, see limits |

### Limits, stated plainly

- The docs navigation lists pages named **Memory**, **Manage Sessions**, **Session History**,
  **Workspace Context**, **Tools and context**, and **Context Engineering**. I did **not** read
  the Memory page: both likely paths returned 404 and I stopped guessing URLs rather than
  fabricate its contents. So: VS Code documents a memory concept (**VERIFIED** that the page
  exists; **NOT VERIFIED** what it says).
- Copilot Chat's implementation is not open source. The `vscode` repository hosts the chat UI,
  the language model API and the tool/context plumbing, but the Copilot agent itself is not in
  it. Anything about how the hosted model assembles its prompt internally would be **INFERRED**,
  and is not used below.
- No claims here are based on remembered source code. Where a mechanism is described, it is
  described at the level the public docs describe it.

## 2. How the context reaches the model (VERIFIED)

1. **Automatic workspace context.** "By default, VS Code uses workspace indexing to
   automatically include relevant files as context based on the conversation."
2. **Explicit context.** Files, folders, symbols, tools, terminal output and source-control
   changes can be attached by `#`-mention, by the context picker, or by dragging them from the
   Explorer, Search view or editor tabs.
3. **Semantic search is a separate, index-backed capability.** `#codebase` "finds code by
   meaning rather than exact keywords" and "requires building and maintaining an index".
   Copilot maintains it automatically, partly locally and partly from remote sources.
4. **The index is not required for the agent to work.** Without it, "agents still search your
   code effectively by using text search, grep, file search, and language intelligence."
5. **Agents use semantic search themselves.** The docs say you do not have to write `#codebase`
   for the agent to use it.

The shape of that is familiar and it is the same shape VN Code uses, minus the embeddings:
a workspace block plus tools the model can call. What VS Code adds is retrieval ranking
(semantic index) on top of the tools.

## 3. How conversation continuity is described (partially VERIFIED)

- VS Code documents **Session History** and **Continue a Session**, and its Agents window keeps
  sessions as first-class objects you can reopen. That much is **VERIFIED** from the navigation
  and the cheat sheet.
- The mechanism by which a reopened session's prior turns are re-sent to the model is **NOT
  VERIFIED** from public sources. I am not going to describe it as if I had read it.

## 4. What VN Code had, and the defect this exposed

VN Code already had the durable half: one transcript file per (project, conversation), restored
into the panel's rows on open, capped at 300 messages / 40 000 chars per message.

What it did **not** have was the other half: the engine. `ChatSession` builds a fresh
`ChatEngineImpl` on open, its `SessionState` started empty, and there was no way to hand it an
existing conversation. The request carried exactly one user message. The restored rows were
decoration, so closing the panel and asking a follow-up produced total amnesia while the
transcript on screen still showed the earlier discussion.

**Fixed** (commit `1d59a0`, CI green): `SessionState.restore` + `ChatEngine.restoreHistory`, with
`ChatSeed` deciding what crosses over (user and assistant turns in order; this UI's own note
rows excluded; interrupted replies kept exactly as stored). `clearConversation()` now clears
the engine too, so a cleared conversation stops being remembered as well as looking cleared.

## 5. What VN Code does not have, and should not pretend to

| Capability | VS Code | VN Code today |
|---|---|---|
| Conversation continuity across reopen | session objects, docs-verified existence | transcript replay into the engine — **DONE** |
| Explicit file/folder/symbol context | `#`-mentions, picker, drag-drop | in-app picker staging files as attachments — **DONE** (item 4/13) |
| Automatic relevant-file inclusion | workspace indexing | a size-capped workspace block (paths/structure), not content selection — **PARTIAL** |
| Semantic code search | `#codebase` over a maintained index | text/symbol tools only — **MISSING**, and an on-device embedding index is a deliberate non-goal on this hardware |
| Long-term memory | docs page exists, contents not read | `AgentMemory` key/value with per-session attribution and a scope switch (M5) — **PARTIAL** |

The honest conclusion for item 10/16: mode behaviour is governed by our own tool registry, and
this research does not license copying VS Code's agent design. It does confirm one thing worth
acting on — that a chat which loses its own conversation is broken by any standard, which is why
that fix came first.
