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
| 3 | Transcript v3 (ids, send time, header, record types) | not started | | | |
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
