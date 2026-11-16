---
title: Project Seven — Copilot Experience Improvements
summary: Stage 7.4 ships one honest line about what happened to the conversation history. The remaining experience items are listed, not claimed.
---

# Project Seven — Copilot Experience Improvements

## Stage 7.4 — context and session visibility (IMPLEMENTED, LOCALLY TESTED, CI-GREEN)

`ChatSession.appendRow(ChatRowModel.InfoNote(...))` after the send completes.

Before this, Project Six could reduce a request and the user would never know: the transcript looked
identical whether the model received twelve turns or three. Now, when `ChatEngineImpl.lastContextDecision`
carries a notice, the session shows it once, and only again when the sentence itself changes, so a long
session is not narrated the same fact after every message.

What the user reads, verbatim from `ContextPreparer`:

- `3 older message(s) were left out to fit this model's known context window (131072 tokens).`
- `This conversation is larger than the model's known context window (131072 tokens), so it was not
  sent. Start a new session or shorten the history setting; nothing was silently cut.`
- For an unknown model whose history was shortened: a sentence that names the count and says explicitly
  that nothing was trimmed on a guess about the limit.

## Not started, listed so it is not implied

7.1 chat usability and response presentation, 7.2 Thinking Tree and chevrons, 7.3 `/tools` catalogue
presentation, 7.5 partial/interrupted/output-limited rendering, 7.6 error explanations, 7.7 session
controls, 7.8 provider/model capability explanations, 7.9 approval interactions, 7.10 research source
presentation, 7.11 documents and media, 7.12 restoration and continuation, 7.13 accessibility and
low-resource performance, 7.14 voice-chat readiness. Stage 7.4 is one item of fourteen.

## CI

Run `43892624026` (job `build`, 25 steps, none failed) succeeded on `52d264b`, which carries this stage.
