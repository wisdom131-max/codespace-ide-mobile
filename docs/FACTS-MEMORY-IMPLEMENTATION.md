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
| 3.1 | Persistent message identity (content-first matching) | Implemented, Unit-tested | (see AGENTS.md) | 11 (TranscriptIdentityTest) | pending |
| 4 | Fact timestamps + timeSource + source conversation link | Implemented, Unit-tested | (see AGENTS.md) | 16 (FactProvenanceTest) | pending |
| 5 | Supersession and history | Implemented, Unit-tested | (see AGENTS.md) | 12 (FactSupersessionTest) | pending |
| 6 | Chronological retrieval | Implemented, Unit-tested | (see AGENTS.md) | 12 (FactChronologyTest) | pending |
| 7 | Automatic extraction + message provenance | Implemented, Unit-tested | (see AGENTS.md) | 13 (MemoryExtractionTest) | pending |
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

## Stage 4 — Fact timestamps and source linking (implemented)

**Fields:** `conversationStartedAt`, `sourceConversationAt`, `sourceMessageId`, `sourceMessageAt`,
`supersededAt` (reserved for Stage 5), `timeSource`. `sourceConversationId` reused, not duplicated.

**Trust rules:**
- `TRANSCRIPT_VERIFIED` only when a v3 `#s` header supplied the conversation date.
- `USER_STATED` reserved for a date the user states explicitly; never inferred from message text.
- `UNKNOWN` otherwise, with the date left 0 and omitted from JSON.
- `legacy-` ids and ids not present in the transcript are never verified source identity.
- A legacy `sentAtMs = 0` stays 0. It is never stamped with the save time.

**Production path:** chat panel claims `FactSourceContext` (conversation id, project id, transcript
root `filesDir/chat`); `MemoryTools.save` resolves provenance there and passes it through
`FactsStore.saveTo` into the fact. Manual screen saves pass `NONE` and stay unlinked.

**Migration:** absent keys decode to 0/null/UNKNOWN; existing provenance is never overwritten by a
migration; a blocked write reports refusal or write failure, never "Saved".

**Limitations:** first-observed vs exact send time; `sourceMessageId` unset for tool-initiated
saves until Stage 7 supplies the originating message.

## Stage 3.1 — Persistent message identity (implemented)

Identity is matched content-first: same-position identical content, then identical content anywhere,
then an in-place edit only when that role's population is unchanged. Consequences: deleting,
pruning or reordering a message cannot renumber the survivors; appending cannot touch existing ids;
an edit that follows a deletion gets a NEW id rather than inheriting the deleted message's id.
Legacy ids stay deterministic (`legacy-<hash>-<index>`), stay flagged, and are persisted on the next
successful save. Stage 4 provenance fields are untouched by this stage.

## Stage 5 — Supersession and history (implemented)

A correction creates a replacement record and retires the corrected one: `SUPERSEDED`,
`supersededBy`, `supersededAt`, with content, category, confidence, provenance and `updatedAt`
preserved. One write covers both records, so the pair cannot half-land. `supersedeIn` is idempotent
and refuses self-supersession. `FactRetrieval.history` exposes retired facts; `rank` excludes them.
Duplicate statements do not create supersession records; different scopes never supersede each
other. Legacy records without supersession metadata load unchanged (`supersededAt = 0`).

## Stage 6 — Chronological retrieval (implemented)

`temporalRank` = relevance score + bounded temporal bonus (verified +8, user-stated +5, other +2)
+ recency scaled within the retrieved set. Deterministic tie-break: score, factTime, updatedAt, id.
Unknown dates earn no bonus but are never excluded. `chronological(includeHistory)` and `latest()`
cover "what is the latest / what changed" questions. The prompt path (`rankedFactsWithOverlap` →
`forRequest` → `blockFor`) uses the time-aware order, so production retrieval is chronological, not
just the helper.

## Stage 7 — Automatic extraction and message provenance (implemented)

`MemoryExtraction.candidates` recognises durable shapes only (preference, decision, standing rule,
project context), refuses transient messages, questions, secrets and over-long text, and caps output
at 3 candidates of 240 chars. `capture` stores through `FactsStore.saveTo` as INFERRED/INFERRED, so
duplicate, conflict and supersession behaviour is shared with the explicit path. `ChatSession`
captures in the background after a user turn, un-awaited and exception-guarded. Provenance resolves
the source message by unique content match; anything ambiguous or synthetic leaves `sourceMessageId`
unset rather than guessing.

**Limitation:** extraction is rule-based, not model-based. It will miss a durable statement phrased
in an unusual way and it will occasionally promote a sentence that reads like a rule but is
transient. Precision over recall is the deliberate choice for a memory that costs prompt space.


## Stage 8 — Review Queue and Rejection Ledger (implemented 2026-11-20, commit 0a89b2d)

Behaviour: Stage 7 detection now ENQUEUES (`ReviewQueue.enqueue`) instead of saving. States are
PENDING / ACCEPTED / REJECTED in one document (`memory-review.json`); there is no second ledger file,
because two stores could disagree about whether a candidate was rejected. Accept goes through
`FactsStore.saveTo` with `FactSource.INFERRED` and the original provenance. Reject records
`reviewedAt` plus an optional reason; entries older than 90 days are pruned on write. Corruption
reads as an empty queue and can never accept anything. The Facts screen draws at most five pending
rows.

Tests: `ReviewQueueTest` (21) plus `MemoryExtractionTest` rewritten to assert queuing, not saving.
Full suite: 3687 tests, 4 skipped, 0 failures. Device: not tested.

## Stage 9 — Memory Timeline

**Implemented.** `MemoryTimeline` + events emitted from `FactsStore` (save, edit, delete,
supersede) and `ReviewQueue` (accept, reject, reconsider). Separate bounded document, 400 events.
Timestamp rule: `atMs` = app event time; `subjectTimeMs`/`timeSource` = when the information is
from, taken from Stage 4 provenance with no createdAt fallback. Retention: oldest events drop
first; no fact is ever pruned. Corruption: reads as empty, so missing history is never invented.
Tests: `MemoryTimelineTest` (20). Facts 187, full 3707 / 4 skipped / 0 failures.
Status: implemented, automated-tested, pending CI, not device-tested.

## Stage 10 — Thinking Tree persistence, collapsed height, padding rule

| Item | Value |
|---|---|
| Status | Implemented, unit-tested. NOT device-verified (layout change). |
| Commit | recorded in the changelog entry of the same push |
| CI | polled after push |

Persistence: `ThinkingTreeStore` writes `chat/<project>/<conversation>.tree.json`, max 120 nodes,
atomic write, idempotent by node id, per-conversation isolation. Restore merges steps before the last
assistant reply; a missing or corrupt file is an empty tree, never a guessed one. Interrupted steps
restore flagged and never render as success.

Collapsed height: title and summary are single-line with ellipsis while collapsed; the summary stays
visible so a failure reason is not hidden.

Padding: AGENTS.md rule 4 corrected from a universal 12dp/10dp minimum to component-specific spacing.
No 48dp padding rule exists in the repo; the 48dp rules are touch targets and remain.

Limitations: no device verification of the collapsed layout; tree steps are written per tool row, so
a very long tool loop costs one small file write per step.

## Stage 11 - Evaluation and regression (FINAL)

| Item | Value |
|---|---|
| Status | Implemented, unit-tested, CI pending at commit time |
| Test file | `app/src/test/java/com/codespace/ide/agent/facts/FactsMemoryEvaluationTest.kt` |
| Run it | `./gradlew :app:testProdDebugUnitTest --tests "com.codespace.ide.agent.facts.*"` |
| Full suite | 3741 tests, 4 skipped, 0 failures |

### Dataset design
- Extraction: 8 labelled messages (4 must yield one durable candidate, 4 must yield none:
  chatter, a question, an observation, an acknowledgement).
- Retrieval: 3 facts, 3 paraphrased questions, one expected top-1 each.
- Chronology: verified / user-stated / unknown facts with a fixed clock.
- Provenance: one fully-specified transcript link and one empty provenance.
- Review: capture, accept, reject, reworded repeat, manual save.
- Timeline: create, supersede, refused save, duplicate event.
- Tree: append twice, restore, cross-conversation isolation, interrupted step.

### Metrics and method
- Extraction precision = tp / (tp + fp); recall = tp / (tp + fn); tp = min(expected, actual) per
  message. Measured: 1.00 / 1.00.
- Retrieval top-1 accuracy = correct top fact / questions. Measured: 3/3.
- Scope isolation violations, duplicate events, fabricated provenance: asserted at zero.
- Model quality (does Copilot phrase a good memory?): UNAVAILABLE. The suite never calls a
  provider, so no percentage is reported and none should be inferred.

### Defects this stage found and fixed
1. `latest()` could return an undated fact as the most recent.
2. A reworded rejected candidate could return to the queue.
3. An interrupted Thinking Tree step restored as ACTIVE/ok.
