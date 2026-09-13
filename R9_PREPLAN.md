# R9 PRE-PLAN — Skills + Custom Agent Modes (.agent.md)

**Status:** v1 — AWAITING WISDOM APPROVAL. **No code until approved.**
**Date:** 2026-09-13 · **Author:** AI Agent · **Base:** green #2778 (5629f30)

---

## 0. What VS Code does that we're answering

From the live-source integration map (2026-09-13):

| VS Code touchpoint | Ours today | R9 answer |
|---|---|---|
| Custom agent modes (.github/chatmodes/*.chatmode.md, .agent.md) — frontmatter name/description/tools/model + instruction body | 3 fixed modes (ASK/AGENT/PLAN), nothing file-driven | **R9-A** |
| Skills / prompt-capability packages (agentPluginsView, promptSyntax) | I6 prompt files = raw text insert only, no metadata/context/confirmation | **R9-B** |
| MCP server prompts capability (prompts/list, prompts/get) | McpClientManager implements tools only | **R9-C** (optional phase) |
| Chat hooks (pre/post chat event hooks for extensions) | N/A — no extension/plugin runtime on-device | **PARKED** (non-goal) |

---

## 1. R9-A — Custom Agent Modes from .agent.md files

### 1.1 Discovery
`CustomModeStore` (new `chat/CustomModeStore.kt`, pure Kotlin — no Compose) scans at panel-open and mode-menu-open:
- `<project>/.codespace/modes/*.agent.md` + `*.chatmode.md`
- `<project>/.github/chatmodes/*.chatmode.md` (VS Code location — a repo with VS Code chat modes works unmodified)

Cache with mtime check (same pattern as AutoInstructionsProvider). Malformed files are skipped with one Output log line, never crash the panel.

### 1.2 Format — frontmatter-lite, no YAML dependency
```
---
name: Reviewer
description: Strict code-review pass before commit
tools:
  - read_file
  - grep
model: anthropic:claude-sonnet-5
---
You are a merciless senior code reviewer. Flag anything...
```
Parser: `key: value` lines + `  - item` dash lists inside the `---` block. Unparsable keys ignored, body still used. **Mode files never execute anything** — the body is prompt text only.

- `name` required (fallback: filename), `description` optional (mode-menu subtitle)
- `tools` optional allowlist of AgentTools names (MCP tools excluded from custom modes in R9 — see 1.4)
- `model` optional pin (prefill only — user can still switch)

### 1.3 Session persistence — additive, zero migration
`ChatSession` keeps `mode: ChatMode` (builtin) **plus a new nullable `customModeId: String?`**. JSON additive — old sessions load unchanged; new sessions with a custom mode load in old builds as plain AGENT (id ignored). `ChatSessionIO` unchanged except writing the extra field. **No enum-to-string migration** — that refactor stays out of scope.

### 1.4 Runtime semantics
A custom mode **inherits AGENT behavior** (FlowGate, PendingChangesStore staging, tool execution) and adds:
1. **Rider:** body text appended to the system prompt via the same `tail` mechanism as AutoInstructionsProvider (one insertion point in `buildSystemPrompt`).
2. **Tool allowlist:** if `tools:` present, the tool-dispatch layer filters AgentTools to that list. Allowlist only ever **RESTRICTS** — FlowGate + ChatPermissionStore levels + ALWAYS-ALLOW allowlist remain supreme in all cases. MCP tools are excluded from custom-mode tool sets in R9 (mcp_* stays level-gated globally).
3. **Model pin:** prefill `selectedModel` once on mode switch; user can override.

**Deleted/missing mode file at session load** -> session falls back to AGENT + one-line notice chip in transcript ("Custom mode 'X' no longer exists — using Agent").

### 1.5 UI
Mode selector gains a **"Custom"** section under the 3 builtin chips: one row per discovered mode (name + description, rounded 10dp, 12/10 padding — UI rule). Files added on disk appear at next mode-menu open (mtime re-scan). 64KB rule: the section is a new internal composable in a **new file** (`ui/screens/ChatModeCustomSection.kt`), called with one line — nothing inline in the panel body.

---

## 2. R9-B — Skills

### 2.1 What a "skill" is here
A reusable prompt package with **metadata + optional context hints**:
```
---
name: Write unit tests
description: Generate tests for the current selection or file
context: selection        # selection | file | diff | problems | clipboard | none
---
Write thorough unit tests for the attached code. Cover edge cases...
```
Sources:
1. **Built-in curated set** (app-shipped): Explain this file (file), Write unit tests (selection), Fix this problem (problems), Review working diff (diff — parity with the I3 pill), Explain this stack trace (clipboard/terminal).
2. **Project skills:** `<project>/.codespace/skills/*.md` (same parser as modes).
3. (Phase R9-C only) **MCP prompts.**

### 2.2 Interaction model — prefill + CONFIRM, never auto-send
`/skills` slash command opens the list; a **"Skills" section** also appears in the attach picker (below I6's "Prompts"). Tap = **run**: prefill the input with the body + auto-attach the hinted context (existing ChatAttachment machinery, I4 rows) — then **stop**. The user reviews and presses send. No auto-send anywhere in R9.

### 2.3 Prompts vs skills (deliberate separation)
- I6 prompt files = raw text insertion, still works, unchanged.
- Skills = metadata, context hints, description-badged rows, confirmation flow.
- Different dirs (`.codespace/skills` vs `.github/prompts` + `.codespace/prompts`) so both coexist without collision.

---

## 3. R9-C — MCP prompts as skills (OPTIONAL phase — needs your call)
MCP spec has a `prompts` capability (prompts/list, prompts/get with arguments) that McpClientManager does not implement. If approved: extend client + surface each server's prompts as read-only skills ("via MCP" badge); arguments become one optional single text field (no argument-form UI — mobile-simple). Honest effort: moderate — new request plumbing per server + refresh lifecycle. **Recommendation: include, but sequenced after R9-A/B land and retest.**

## 4. R9-D — Hooks: PARKED (non-goal)
VS Code chat hooks assume an extension host. We have no plugin runtime on-device and none planned. Documented non-goal.

---

## 5. Locked decisions for approval

| # | Decision | Recommendation |
|---|---|---|
| D1 | Custom modes inherit **AGENT** only (no ASK/PLAN-based customs in R9) | YES — simplest correct; ASK-based customs can be a later round |
| D2 | Session persistence = **additive `customModeId`** (no enum-to-string migration) | YES — zero-risk vs. big refactor |
| D3 | Mode `tools:` allowlist **only restricts**; FlowGate/permissions always supreme | YES — non-negotiable safety |
| D4 | Skills **never auto-send** — prefill + user confirm | YES — mobile-correct |
| D5 | Frontmatter-lite parser, **no YAML lib, no code execution** from mode/skill files | YES |
| D6 | Include R9-C (MCP prompts) in this round? | Include, sequenced after A/B retest |

## 6. Build order & files (if approved)
1. `chat/CustomModeStore.kt` (parse+cache)
2. `buildSystemPrompt` rider + tool-dispatch allowlist + ChatSession field + IO
3. `ui/screens/ChatModeCustomSection.kt` (menu section, 64KB-safe) + mode-menu wiring
4. `chat/SkillsCatalog.kt` (builtins + project scan, reuses parser) + `/skills` command
5. `ui/screens/ChatSkillsSection.kt` (picker section + run flow) — ChatAttachPicker gains one callback (`onRunSkill`), wired like I6's `onInsertPrompt`
6. AGENTS.md changelog + re-test batch R9-1..R9-10

## 7. Re-test batch preview (added to the batched pile — not run until Wisdom's session)
R9-1 create `.codespace/modes/reviewer.agent.md` -> appears in mode menu with description; switch -> rider active (ask "what mode are you" -> describes reviewer persona).
R9-2 mode with `tools:` allowlist -> agent can read_file; a blocked tool is unavailable/not attempted; FlowGate still prompts as usual.
R9-3 delete the file -> reopen session -> AGENT fallback + notice chip.
R9-4 `/skills` -> 5 builtins listed; tap "Write unit tests" -> input prefilled + selection attached, nothing sent until user presses send.
R9-5 project skill in `.codespace/skills/` -> appears with description badge.
R9-6 I6 prompt files still insert raw text (no regression).
R9-7 mode menu rounded corners + padding (UI rule).
R9-8 old session JSON loads unchanged.
R9-9 model pin prefills once, user override sticks.
R9-10 (if D6) MCP server with prompts -> skills listed with "via MCP".

## 8. Out of scope
No hooks, no skill/mode marketplace or download, no .agent.md script execution, no auto-send, no MCP tools inside custom-mode allowlists.
