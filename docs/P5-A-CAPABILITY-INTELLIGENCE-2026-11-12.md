# P5.A/E - the capability lookup stops guessing

## The defect

`ModelCatalog.capabilityFor` was `BUNDLED.firstOrNull { modelId.contains(it.idMatch) }`.

- **First match wins over an unordered list.** Two rows can match one id, and the winner was whichever
  sat higher in the file.
- **The loser's context window set the payload budget.** `payloadBudgetChars` derives the send budget
  from `contextTokens`, so a wrong row silently under- or over-fed the model: either the conversation
  was truncated early, or `finish_reason = length` arrived sooner than it needed to.
- **`providerId` was accepted and never used.** A lookup that takes a provider and ignores it will
  eventually be fed the wrong provider's row and cannot tell you.
- **Nothing distinguished a verified row from a guess.** The UI presented an estimate with the same
  confidence as a verified figure.

## The fix

`ModelCatalog.resolve(providerId, modelId)` returns `Resolution(capability, source)`:

- `EXACT` when the id equals a row,
- `SUBSTRING` when a row's id is contained in the model id, choosing the **longest** match, which is
  the one the model id actually names,
- `UNKNOWN` when nothing matches, and the caller falls back to the user's own settings rather than an
  invented number.

Rows can now carry `providers` to scope disambiguation; an empty set means the id names the model
whatever serves it, which is the previous behaviour for every bundled row.

`honestyNote(providerId, modelId)` returns null for an exact match and one plain line otherwise. It is
shown when a model is selected, so an unknown model says its limits fall back to settings instead of
presenting invented limits.

## Tests

`ModelCatalogSpecificityTest` (7): exact reported as exact, a dated or variant id is a substring match,
an unknown model has no capability rather than an invented one, a blank id is unknown, the most specific
row wins, honesty is silent only for exact matches, and an unknown model still gets the user's own
budget.

One test changed rather than deleted: `KeyFailoverMappingTest.optInFailoverSwitchesKeyVisiblyAndCompletes`
asserted the switch note was the ONLY `InfoNote`. The honesty note makes that false. The assertion now
names the switch note, which is what the test was protecting (the switch must be visible, not silent).

Full suite 2561 tests, one known `BackupTarTest` root-sandbox failure (green in CI); engine module green.

## P5 status after this commit

- A. Capability identification and honest unknowns: this commit.
- B. Unified response handling: `ChatError`, `ChatErrorVerdict`, `ErrorTaxonomy`, `SseParser` and
  `FinishReason` already normalise provider errors, incomplete streams and finish reasons; a stream
  that ends without `[DONE]` is recorded as completed honestly rather than as a failure. Duplicate
  terminal events are ignored via the `done` latch.
- C. Text `<TOOLS>` protocol preserved: unchanged, and the adapters never rely on native function
  calling.
- D. Retry identity and duplicate side effects: `ToolCallLedger` and `ToolDispatchGuard` own that, and
  P4's duplicate suppression runs before execution.
- E. Sensible fallback: `UNKNOWN` means user settings, never a guess.
