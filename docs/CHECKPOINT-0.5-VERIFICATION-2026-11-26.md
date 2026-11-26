# Checkpoint 0.5 — Reply Identity & Lifecycle Reliability: verification record

26 November 2026. Verified against `main` at `3f460bc6d2ea77fcd768a63043fd57b98ce57a25`, working
tree clean, 0 ahead / 0 behind `origin/main`.

## Status vocabulary

**VERIFIED** — implemented, and a test or CI run proves the behaviour.
**IMPLEMENTED, NOT INDEPENDENTLY VERIFIED** — code is in place and compiles; no test isolates it.
**KNOWN LIMITATION** — accepted gap, recorded so it is not mistaken for completeness.

## Stage table

| Stage | What it does | Where | Proof | Status |
|---|---|---|---|---|
| 0 | Retry continues from its text; no NUL padding | `ChatSession` retry prefix | transcript + retry tests | VERIFIED |
| 1 | One bounded command queue, one consumer, one serialised writer for cancel/finalise | `SessionCoordinator.kt` | `SessionCoordinatorTest` 6 | VERIFIED |
| 2 | Generation ownership; atomic bump so two attempts cannot share a number | `RequestGate.kt` | `RequestGateTest` 6 | VERIFIED |
| 3 | Buffer, protocol filter, job, publication clock owned per attempt | `RequestAttempt.kt`, `RequestAttempts` | `RequestAttemptTest` 7 | VERIFIED |
| 4 | 50 ms / 512-character coalesced publication | `ChatSession` publish block | `StreamLifecycleRegressionTest` 2 | VERIFIED |
| 5 | One finalisation path with an ownership check; handoff does not flicker | `endStream` / `performEndStream` | `ChatSessionMultiSessionTest` 12 | VERIFIED |
| 6 | Event-level execution identity; dispatch reserved before the call runs | `DeliveryLedger`, `DispatchReservations` | tool ledger tests | VERIFIED |
| 7 | Reply identity by `replyId`, not text containment | rows + transcript | transcript identity tests | VERIFIED |
| 8 | Transcript keeps replyId, turnId, finish reason; restore never upgrades a failed reply | `ChatTranscript` | restoration tests | VERIFIED |
| 9 | Typed notice categories; only WORK_STEP reaches the tree | `NoticeKind`, 7 producers | tree tests | VERIFIED |

## Known limitations (accepted, not outstanding defects)

1. **Stall watchdog polling is deliberately not wired.** The policy (`StreamProgressWatchdog`) is
   documented and tested: armed at request start, reset only by new visible text, heartbeats are not
   progress, 120 s. The polling timer is not started because a self-rescheduling delay loop prevents
   the harness reaching quiescence. Accepted by the owner; do not reopen as unfinished work.
2. **Process-death recovery is not claimed.** Nothing here establishes exactly-once execution across
   a process kill. Attempt jobs are cancelled through the coordinator on every terminal path, but a
   terminated process cannot run that path.
3. **4.8 of Project Four and this checkpoint are not device-verified.**

## Test and CI record

- Local: `./gradlew :app:testProdDebugUnitTest :engine:test` — app 4113 tests, 0 failed, 4 skipped;
  engine 686 tests, 0 failed, 0 skipped. BUILD SUCCESSFUL in 4m 17s.
- The 4 skips are `assumeTrue` guards needing a real rootfs / tar environment, not suppressed tests.
- CI: run 4131 (id 45743324117) on `3f460bc` — completed **success**.
- Device verification: **pending**, TECNO Pop 9.
