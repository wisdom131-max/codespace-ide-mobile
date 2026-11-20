# VN Code Copilot — next-phase roadmap (planning only, nothing implemented)

Status vocabulary: **present** (in the repo and wired), **partial**, **absent**.

## Recommended order

1. **Integration Reliability & Security** (project 8) — first, because D1 (approval card never
   published) shows that a mechanism nobody had tested end to end was broken in production. Every
   other project sits on top of the gate.
2. **Advanced Agent Capabilities** (3) — sessions, labels, carried context: the user-facing gaps
   with the highest daily cost.
3. **Long Context Management** (6) — continuation truth and output limits; depends on 3's session
   identity.
4. **Documents & Media Intelligence** (2) — archive/doc inspection parity; independent of 3 and 6.
5. **Research Improvements** (1) — source integrity on top of the existing reconciliation code.
6. **Advanced Tool Intelligence** (4) — dynamic loading, relevance, lean modes.
7. **Cross Provider Intelligence** (5) — capability differences between adapters.
8. **Copilot Experience Improvements** (7) — polish last, once behaviour is trustworthy.

## 8 — Integration Reliability & Security — P0

- **Present:** single execution gate (`AgentTools.executeTool`), architectural guard, approval
  policy and now a working card, archive boundary, redaction, `DiagTransport`.
- **Gaps:** no MCP server or real provider in tests; duplicate-reply not proven end to end; no
  instrumented UI test; approval cancel on process death unverified.
- **Objective:** every gated action either runs with a recorded user decision or is refused, always.
- **Scope:** test harnesses for MCP and provider fakes; duplicate-reply scenario tests; UI test
  infrastructure (Robolectric or instrumented).
- **Security:** do not weaken `requiresForcedApproval`; keep redaction inside `record()`.
- **Acceptance:** a card appears, is answerable, and its decision is recorded for every gated tool.
- **Tests:** concurrent sends, tab close/reopen, backgrounding, cancellation mid-approval.
- **Checkpoints:** (a) MCP fake server, (b) duplicate-reply scenarios, (c) UI test infra.
- **Complexity:** medium. **Why first:** D1.

## 3 — Advanced Agent Capabilities — P1

- **Present:** `SessionHandoff`, `HandoffGuard`, `SummaryStore`, `HistoryRetriever`, multi-session
  manager, `DeliveryLedger`.
- **Gaps:** explicit session labels ("Session 2 from git") are absent; carrying a conversation into
  another session with rich context is partial; external API/code-hosting credential management is
  partial (`SecureTokenStore`, `CredentialHostGuard`, GitHub sign-in dialog).
- **Objective:** start a new session that knows what the last one did, and say so in its title.
- **Scope:** session naming derived from the first meaningful action; handoff payload builder;
  per-provider credential slots for code hosting.
- **Security:** carried context must be project-scoped; no credential ever enters a summary.
- **Acceptance:** a second session opens with a label naming its origin and a summary that is
  traceable to the first session's messages.
- **Tests:** label derivation, handoff scoping, refusal when the source session is gone.
- **Complexity:** medium-high.

## 6 — Long Context Management — P1

- **Present:** `Continuation`, `ContinuationDiag`, `ContextOffload`, `BudgetGuard` char limits,
  token accounting, `ToolContextTrim`.
- **Gaps:** the full continuation turn is not driven by a test; offload retrieval back into the
  reply is unverified; provider-specific output limits are not modelled per adapter.
- **Objective:** a cut-off answer is always visibly incomplete and always resumable.
- **Acceptance:** a length-limited finish shows as incomplete, offers Continue, and completes once.
- **Tests:** continuation success/failure/cancel through `ChatSession`; offload round trip.
- **Complexity:** medium.

## 2 — Documents & Media Intelligence — P2

- **Present:** `DocText`, `DocxEditor/Writer/Outline`, `PptxDeck`, `FramePlan`, `ImageMeta`,
  `ZipEngine` with boundary enforcement, `ArchiveDestinationVerifier`.
- **Gaps:** no archive browser experience comparable to ZArchiver/MT Manager (tree view, in-archive
  preview, selective extract); extraction is tool-driven, not a panel.
- **Objective:** open an archive, see inside it, pull out one file, without a shell.
- **Scope:** archive tree UI over `ZipEngine.list`, selective extract, preview via existing viewers.
- **Security:** reuse `ZipEngine` limits and the symlink walk; never add a second extractor.
- **Acceptance:** a malicious archive is refused with a visible reason; a normal one browses.
- **Complexity:** medium.

## 1 — Research Improvements — P2

- **Present:** `ResearchSources`, `ClaimReconciliation`, `ContradictionScan`, `ResearchCancel`,
  `HtmlExtractor`, bounded presentation with an exact omitted count.
- **Gaps:** no test proves a failed fetch is never presented as a source; citation integrity across
  partial failures is untested.
- **Acceptance:** an interrupted research turn says what it could not read.
- **Complexity:** low-medium.

## 4 — Advanced Tool Intelligence — P3

- **Present:** `tool_search`, tool groups, `/tools` catalogue, `ToolHealth`, `ToolResultCheck`.
- **Gaps:** relevance selection and lean modes are not measured; discovery must be proven not to
  execute.
- **Acceptance:** `tool_search` returns names only and never runs a body (assert with the gate
  counter).
- **Complexity:** medium.

## 5 — Cross Provider Intelligence — P3

- **Present:** `GeminiAdapter`, `AnthropicAdapter`, `OpenAIAdapter`, `DiagTransport`, error taxonomy,
  opt-in key failover.
- **Gaps:** per-provider output limits and tool-call compatibility are not modelled; Cloudflare is
  not a first-class adapter.
- **Security:** never switch provider automatically; credentials never move between providers.
- **Acceptance:** each adapter's capability set is declared and asserted, not assumed.
- **Complexity:** medium.

## 7 — Copilot Experience Improvements — P4

- **Present:** `CopilotBindings`, `CopilotPresentation`, 7.11 seams, tool rows, refusal wording.
- **Gaps:** 7.9 accessibility is partial; no Compose UI tests anywhere.
- **Acceptance:** every row type has a UI test.
- **Complexity:** low-medium.

## Dependencies

8 -> everything. 3 -> 6 (session identity before carried context). 2 independent. 1 depends on 8's
provider fakes for interruption tests. 4 depends on 8's gate counter. 5 depends on 8's harness.
7 last.
