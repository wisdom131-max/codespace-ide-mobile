# P4.6 / P4.7 - the tool-output ceiling, wired into the turn

`ToolContextTrim` existed and was tested, and nothing called it. That is the worst of both worlds: a
guarantee in a file with no effect. This wires it into the one place a tool chain grows - the turn's
accumulated tool results.

## What changed

`ChatSession` accumulated every tool result for a turn into one string and sent the whole thing as the
next request's context. Ten tools with large outputs meant ten large outputs re-sent, on a phone.

Now the same results are also kept itemised, and at the send boundary:

- under 6,000 characters, nothing happens at all (the text is passed through untouched);
- past it, `ToolContextTrim` keeps the newest result per tool, keeps anything pinned or carrying a
  citation marker, drops exact repeats, compresses the rest with an explicit
  `… [N chars omitted]` marker, and renders within the ceiling.

The ceiling is `ChatSession.TOOL_RESULT_BUDGET_CHARS = 6_000`, chosen to match the trim's own default
and to leave room for the system prompt and the conversation inside a phone-sized context.

## Why the newest survives

Recency is usually what the next step needs: the model has just acted and is about to react. Older
results are still represented by their marker, so the model can re-read a file rather than being told
it remembers one.

## Tests

6 tests in `ToolResultBudgetTest`: the ceiling's value and finiteness, a long chain compressed, the
newest result surviving with a truncation marker, a cited result kept as evidence, an exact repeat
dropped (2 of 3), and a short turn left untouched.

Full suite: 2506 tests, one known `BackupTarTest` root-sandbox failure (green in CI).

## Honest limitations

- The wiring is asserted through `ToolContextTrim` and the budget constant, not through a live turn:
  the send boundary needs an Android `Context` and a provider. The pure transform is what is tested;
  the call site is three lines and reviewed by hand.
- `ToolPlanner` is still uncalled in production. Its dependency waves are a prompt-ordering idea with
  no consumer yet, and inventing one to make a class look used is exactly what this project has been
  trying to avoid. It stays parked with that stated.
