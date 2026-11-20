# VN Code Copilot — integration, reliability and security audit (2026-11-20)

Baseline `eeab5b8`, audited on `main`. Evidence levels used below:

1. end-to-end integration-tested
2. runtime integration-tested with controlled/faked dependencies
3. component-level tested
4. source-level architectural guard
5. not verified

## Confirmed defects found in this campaign

### D1 — CRITICAL — the approval card was never published

| Field | Value |
|---|---|
| Scenario | Tool execution / MCP approval: user request -> approval -> execution |
| Expected | A gated call pauses, a card appears, the user's answer resolves the wait |
| Actual | No card was ever published. `PendingApproval(` existed only as a declaration and `pending.value` was only ever assigned `null`. `approve()` / `reject()` always returned false |
| Root cause | `AgentFlowGate.awaitDecision` created a `CompletableDeferred` and awaited it without publishing the `PendingApproval` that `approve()`/`reject()` act on |
| Severity | Critical: every call requiring approval (`mcp_*`, `use_connector`, `service_write`, every non-auto tool under MANUAL) waited the full ten minutes and was then reported as "no answer in 10 minutes". Nothing could be approved |
| Fix | Publish `PendingApproval(toolName, argsSummary, deferred, onAlwaysAllow, onTrust)` before the wait; the existing `finally` already clears it |
| Regression test | `ApprovalCardFlowTest` (6): grant, decline, expiry, double-approve, MCP-forced card, auto-approved no-card |
| Verification | Unit-tested + runtime-tested through the real gate object. Not device-tested |
| Commit | `215c835` |

Why this survived: every existing approval test asserted the *pure* policy functions (`mustAsk`,
`requiresForcedApproval`, `verdictFor`). Those were correct, so the suite was green while the
mechanism that answers the card was missing. The fix is one statement, which is exactly why the
absence of it was invisible.

### D2 — MEDIUM — Project Seven's 7.11 regression evidence was deleted with Voice Mode

`a015d0f` deleted `Stage711IntegrationTest.kt` whole because it imported the removed voice turn
machine, taking four voice-independent cases with it. Behaviour was intact; the evidence was not.
Restored unchanged, all four pass. Commit `2bc231e`.

## Scenario results

| # | Scenario | Level | Evidence |
|---|---|---|---|
| 1 | Tool execution: discovery -> permission -> approval -> execution -> result | 2 | `AgentTools.executeTool` -> `executeToolInner` -> `executeGated` -> `dispatchGuard.run`; `ApprovalCardFlowTest` drives the approval decision through the real gate; `DiagTransport` + `executeGated` record the outcome |
| 2 | MCP approval: selection -> mandatory approval -> decision -> execution/refusal | 2 (decision), 5 (remote execution) | `requiresForcedApproval` forces `mcp_*`; `ApprovalCardFlowTest` shows the card appearing under AUTO_ALL + always-allow. No MCP server is connected in tests, so execution against a real server is unverified |
| 3 | Large tool output: limit -> offload -> pointer/excerpts -> retrieval | 3 | `agent/ContextOffload.kt` + `ContextOffloadTest`, wired in `ChatSession`; the round trip back into a reply is not driven |
| 4 | Research: discovery -> retrieval -> fusion -> ranking -> citations | 3 | `RetrievalFusion.kt` + `RetrievalFusionTest`; `agent/research/` holds `ClaimReconciliation`, `ContradictionScan`, `ResearchCancel`; `ResearchPresentation.bound` caps sources at 40 with an exact omitted count |
| 5 | Provider failure and retry | 2 | `DiagRetryIntegrationTest` (5) and `DiagFailoverIntegrationTest` (5) drive `send`/`retry` with a fake transport: one attempt, then a card; failover schedules each alternative key then reports exhaustion once |
| 6 | Continuation: eligibility -> execution -> terminal -> duplicate protection | 3 | `ContinuationDiag` wired at the Completed, Failed and cancellation paths; call sites compile-verified, no test drives a real continuation turn to its terminal event |
| 7 | Archive: validation -> safe extraction -> inspection | 3 | `ZipEngine.extract` containment + symlink walk + limits; `ZipEngineBoundaryTest` (10 shapes); `ArchiveDestinationVerifier` reads the destination instead of trusting the report |
| 8 | Restart: event -> persistence -> process death -> restoration -> UI | 2 | `DiagHistoryModelTest`: record, flush, clear the ring, re-attach the file, read back through the model the UI renders |
| 9 | Sensitive information: provider/tool -> redaction -> storage/display | 3 | `RedactPatternsTest` (10) + `DiagTransportTest` (URL, bearer, prompt never recorded); redaction runs inside `record()` before the write |

## Duplicate-reply prevention — status: existing protection verified, not proven end to end

- Authoritative identity: `ReplyOwnership` (generation-based `mayComplete`), `DeliveryLedger`
  (persisted delivery identities), and `ChatSession.lastCompletedGen` / `requestGate.stale(gen)`.
- A `Completed` event that fails `ReplyOwnership.mayComplete` returns before anything is appended,
  so a late or duplicated terminal event cannot add a second assistant bubble.
- Tests present: `ReplyOwnershipTest`, `Stage47ReplyIdentityTest`, `Stage48DeliveryLedgerTest`,
  `Stage48RuntimeDeliveryTest`.
- NOT proven: no test drives rapid tab close/reopen, backgrounding, or two concurrent sends in one
  session. Classification: **partially addressed**. Do not describe the historical duplicate-reply
  issue as fixed.

## Remaining risks

1. Compose rendering of the diagnostic history screen has no test (see P4.3 limitation).
2. No MCP server or real provider is exercised anywhere.
3. Continuation terminal events and failover exhaustion are wired and now partly driven, but the
   full continuation turn is not.
4. Nothing is device-verified.

## P4.3 limitation, stated precisely

The Compose UI test cannot be written and verified here: `ui-test-junit4` is declared only as
`androidTestImplementation`, which needs a connected device or emulator, and that is deferred. The
alternative (Robolectric with Compose) requires a dependency download that is not authorized. The
state model behind the screen is covered by `DiagHistoryModelTest` (13): rendering inputs, filters,
empty state, restart visibility and the 200-row bound. What remains unverified is the composable
itself.
