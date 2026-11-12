# Copilot areas 4-6 — state on 2026-11-12

Registry: **118 tools**. Tool Groups, `/tools` catalogue, Git recovery and debugger tools are complete and CI-verified.

| Area | Status |
| --- | --- |
| Tool Groups | Complete |
| `/tools` picker | Complete |
| Git tools and recovery | Complete — `0197002`, run 43170924773 green |
| Debugger integration | Complete — `0197002`, run 43170924773 green |
| Chat presentation | Implemented and wired — `28553f4`, `32ea140`, `d7e40e0` green |
| Conversation reliability | Stream ownership implemented — `471081f`; cancellation/retry tool-side coverage still open |
| Output limits and continuation | Partial — turn summary refuses to say "Done"; detection/continuation still open |

## What shipped

**Area 4.** `ToolResultPresentation` is the single ruler for a tool result: kind (RESULT / PARTIAL / EMPTY / ERROR), bounded title, bounded summary, collapsed body, and an explicit withheld-line count. Only the tail of a result is inspected for cap markers, so a body that merely contains the word "truncated" stays a normal result. `ToolActivityRow` now draws those fields: verdict in the error colour, reason visible without expanding, "(partial)" for a capped result, and a stated withheld count for a bounded body. `ToolActivity.ok` defaults to true so every existing producer stayed valid.

**Area 5.** `RequestGate` numbers each turn; `onEvent` drops any event whose number is not current before it can touch rows, tool calls or the buffer. One rule covers Stop with bytes in flight, a replacing send, an armed 429 retry firing late, and a session switch — the gate is per session object, so switching stops the old stream without touching the one on screen.

## Tests

- `ToolResultTest` — 13.
- `ChatPresentationIntegrationTest` — 7, tying `ChatTranscript.cap` to the presentation layer so a capped result cannot reopen as complete.
- `RequestGateTest` — 6.

## Known limitations

- Continuation (area 6C) is not implemented. A turn cut by the output ceiling still reports honestly via `turnSummary` but cannot be resumed in place.
- Provider-reported finish reasons are not yet threaded into the truncation decision; detection currently rests on the existing cap markers.
- Tool-side retry classification (never-started vs failed vs completed-but-response-lost) is not implemented.
- The umbrella name for the four Tool System branches could not be recovered from repository records and is deliberately left unnamed.
