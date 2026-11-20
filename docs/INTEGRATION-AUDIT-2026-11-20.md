# Cross-project integration audit — 2026-11-20

Baseline `main` at the commits below. Classification is deliberately strict: **end-to-end** means
the production code path ran with only the outermost dependency faked; **component-level** means the
decision logic is tested but the surrounding wiring is compile-verified only; **partial** means some
stages of the chain are not exercised at all.

| # | Scenario | Classification | Evidence |
|---|---|---|---|
| 1 | Tool execution | Component-level verified | `AgentTools.executeTool` -> `executeToolInner` -> `executeGated` -> `dispatchGuard.run`; `SingleExecutionGateGuardTest` proves the gate is the only entry; `ApprovalLifecycleDiagTest` proves the approval decision and that a denial never authorizes. The full chain to a rendered assistant reply is not driven in one test. |
| 2 | MCP approval | Partially integrated | `AgentFlowGate.requiresForcedApproval` forces `mcp_*`, `use_connector`, `service_write`; `mustAsk` returns true under AUTO_ALL + always-allow (tested). No MCP server is connected in tests, so discovery, execution and refusal against a real server are unverified. |
| 3 | Large tool output | Component-level verified | `agent/ContextOffload.kt` + `ContextOffloadTest`; wired in `ChatSession`. Pointer-plus-excerpt retrieval back into a reply is not driven end to end. |
| 4 | Research and retrieval | Component-level verified | `agent/RetrievalFusion.kt` + `RetrievalFusionTest`; `ResearchPresentation.bound` caps sources at 40 with an exact omitted count (`Stage711IntegrationTest`). No semantic/vector layer exists, by design. |
| 5 | Provider failure and retry | End-to-end (provider faked) | `DiagRetryIntegrationTest` drives the real `ChatSession.send` / `retry()` with a fake `ChatTransport`: one wire attempt, then a card, then a user-initiated retry. There is no automatic retry ladder, so RETRY_SCHEDULED/EXHAUSTED only fire on the opt-in key failover, which is not driven in tests. |
| 6 | Continuation | Partially integrated | `ContinuationDiag` covers COMPLETED/FAILED/CANCELLED and `ChatSession` calls it from the Completed, Failed and cancellation paths. The call sites are compile-verified; no ChatSession test drives a real continuation turn to a terminal event. |
| 7 | Archive inspection | Component-level verified | `util/ZipEngine.extract` boundary + `ZipEngineBoundaryTest` (10 attack shapes); `ArchiveDestinationVerifier` reads the destination rather than trusting the extraction report. |
| 8 | Restart | Verified through the persisted file | `DiagHistoryModelTest` records, flushes, clears the ring (process death), re-attaches the file (restart) and reads the event back through the same model the UI renders. Not device-verified. |
| 9 | Sensitive information | Component-level verified | `RedactPatternsTest` (Google/AWS/JWT/Basic/bearer/assignments) and `DiagTransportTest` (URL, bearer token and prompt never recorded). Redaction runs inside `record()`, before the JSONL write and before the UI reads it. Binary and non-UTF8 tool output are not covered. |

## What this audit does NOT claim

- No scenario is device-verified. Nothing here was run on the TECNO KL4.
- No scenario is verified against a real provider or a real MCP server; the outermost dependency is
  always faked.
- Scenarios 1, 6 and 2 have stages that no test exercises, which is why they are not called
  end-to-end.

## Remaining limitations

1. Retry events for the opt-in key failover have no test driving the failover branch.
2. `APPROVAL_REQUIRED` emission is compile-verified: the card waits up to ten minutes, so a test
   that expects a timeout would stall the suite.
3. Continuation cancellation is wired but not driven by a test.
4. Compose rendering of the diagnostic history screen has no UI test.
