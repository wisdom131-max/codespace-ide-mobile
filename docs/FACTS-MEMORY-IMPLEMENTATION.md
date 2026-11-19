# Facts & Memory — Master Implementation Checklist

Scope: the 11 authorized stages of the Facts & Memory architecture
(owner authorization 2026-11-19). One row per stage, updated as each stage lands.

Status vocabulary:
- **Implemented** — code is on `main`.
- **Unit-tested** — focused JVM tests pass locally.
- **CI-verified** — a GitHub Actions run for the exact commit is green.
- **Device-tested** — verified on the TECNO KL4 (NOT done in this campaign).
- **User-visible verified** — confirmed by the owner in the app (NOT done).

| Stage | Scope | Status | Commit | Tests | CI |
|---|---|---|---|---|---|
| 1 | Reactive Facts panel + date labels + history toggle | Implemented, Unit-tested | (see AGENTS.md) | 8 (MemoryEventsTest 5, FactDatesTest 3) | pending |
| 2 | Category inference precedence | Implemented, Unit-tested | (see AGENTS.md) | 9 (CategoryInferenceTest) | pending |
| 3 | Transcript v3 (ids, send time, header, record types) | Implemented, Unit-tested | (see AGENTS.md) | 17 (TranscriptV3Test) | pending |
| 4 | Fact timestamps + timeSource + source conversation link | not started | | | |
| 5 | Supersession wiring | not started | | | |
| 6 | Chronological retrieval intents | not started | | | |
| 7 | Automatic extraction + settings + idempotency | not started | | | |
| 8 | Review Queue + 90-day rejection ledger | not started | | | |
| 9 | Memory Timeline view | not started | | | |
| 10 | Thinking Tree step persistence | not started | | | |
| 11 | Evaluation harness and metrics | not started | | | |

## Stage 1 — Reactive Facts & Memory panel

- **Files:** `agent/facts/MemoryEvents.kt` (new), `agent/facts/FactDates.kt` (new),
  `agent/facts/FactsStore.kt`, `ui/screens/FactsScreen.kt`,
  `test/.../facts/MemoryEventsTest.kt` (new).
- **Implementation:** one process-wide `MemoryEvents.revision` StateFlow; `FactsStore` publishes
  only after a write returns success (save, update, delete, supersede); `FactsScreen` collects it
  instead of owning a counter, so Copilot writes and panel writes converge on one signal. A failed
  delete no longer bumps anything. Date labels: "Saved", "Updated", "Last used", and
  "Conversation date: Not recorded" until stage 4 supplies the real value — `createdAt` is never
  printed as the conversation date. "Current only / With history" filter hides superseded records
  by default without deleting them.
- **Limitations:** the conversation date is hard-wired to "Not recorded" until stage 4; the
  "With history" view still renders superseded rows with their normal actions.

## Stage 3 — Transcript v3 (implemented)

**Schema** (line-oriented, one record per line, tab-separated, text escaped):

| Record | Fields |
|---|---|
| header | `v3` |
| conversation id | `#c` <conversationId> |
| conversation start | `#s` <startedAtMs> |
| message | `m` <id> <role> <complete 0/1> <sentAtMs> <savedAtMs> <finish or -> <text> |
| tool step | `s` <callId> <turnId or -> <atMs> <ok 0/1> <label> <detail> |

**Stable id rules:** assigned on first persistence, inherited positionally when role matches,
never regenerated on load, unique within a conversation. Legacy ids are deterministic
`legacy-<hash>-<index>`; the prefix is the synthetic flag and survives disk round trips.

**Timestamp semantics:** `sentAtMs` is the first time persistence OBSERVED the message (an app
event, never later than the true send time, never rewritten by a later save). `savedAtMs` is the
save. Legacy lines keep `sentAtMs = 0` = unknown and must be shown as "not recorded".
`startedAtMs` is written once per conversation and never moved.

**Recovery:** malformed record, unknown record type and duplicated id each cost only that record;
duplicates are counted in `DecodedTranscript.duplicateRecords`; an alien header is `unreadable`
rather than silently empty; a missing trailing newline is not a torn write.

**Not in scope here:** Stage 4 fact linking, Stage 10 Thinking Tree production of steps, and the
Thinking Tree presentation/collapsed height (still separate outstanding work).
