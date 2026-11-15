# Copilot repair stages, 1 to 8c: consolidated record

Date: 2026-11-15 (Africa/Lagos). Base at the start of the run: 6987276.
Green base at the end: this commit's parent.

This is the consolidated record the owner asked for: every stage that shipped, one row each,
with the commit, the CI run that proved it, what actually changed, and what is still only a
code-level claim. It exists because AGENTS.md's change log was not written for this run, and the
running "how many stages" count drifted between fifteen and seventeen while it was not.

## Why the count drifted, and the count

Seventeen stage commits shipped. The drift came from a stage-label collision, not from lost work:
two different commits were both labelled `phase 8a` (terminal polling at `47f2f96`, model
catalogue at `9faf89a`). The record below numbers commits, not labels, and the model catalogue is
renumbered 8d so the collision cannot recur.

Every row is one commit, one bug, one green CI run before the next push.

| # | Stage | Commit | CI run | What shipped |
|---|---|---|---|---|
| 1 | Phase 1 | `b537e61` | GREEN 43632852707 | Staging, commit and push are three separate approvals. `add -A` is gone, so a commit no longer sweeps the whole tree. Pushing to a protected branch needs a second confirmation, and declining keeps the commit. |
| 2 | Phase 2a | `8dfeac3` | GREEN 43636558841 | Closing the last chat window parks a live runtime instead of closing it, so a running turn survives the panel. A parked runtime is closed by an idle reaper once its work ends. |
| 3 | Phase 2b | `db65f4c` | GREEN 43660331882 | A turn killed with the process restores as an interrupted reply and offers an explicit Resume that continues the same message without resending. A new send retires the offer. |
| 4 | Phase 3a | `e44acb0` | GREEN 43661682327 | One file-level lock for every transcript write and delete, so a delete cannot land inside a write. This is the recurring ENOENT. A concurrency test forces the old interleaving. |
| 5 | Phase 4a | `87a673e` | GREEN 43662829995 | `WritePolicy` gates every tool call as ALLOW / STAGE / REFUSE from `ToolEffectIndex`. Ask refuses every side effect; Edit stages all ten file writers instead of three remembered names. |
| 6 | Phase 4b | `aeb53c8` | GREEN 43665059206 | The gate follows the approval level: Auto All writes with no diff, Auto Safe and Always Ask stage an Edit proposal, Ask stays read-only at every level. |
| 7 | Phase 4c | `f51cca6` | GREEN 43667103869 | The connector card hosts its own setup (token field for `pat`, consent page opened from the card for `oauth`) instead of redirecting to the Connectors Hub. The Hub is unchanged for Settings. |
| 8 | Phase 5a | `705793a` | GREEN 43666264415 | `guestPath` delegates to `ToolPathResolver`, so `checksum` and `read_file` cannot disagree about the same path any more. |
| 9 | Phase 5b | `c9a9fb4` | GREEN 43690923093 | Every file-mutating tool is containment-checked against the open project root before dispatch (copy and move were the only ones that were). The refusal names the path and the root and says nothing was written. |
| 10 | Phase 6a | `a443b77` | GREEN 43685790736 | A skill can be disabled without being deleted: the flag persists, older files restore enabled, disabled skills leave the model index. |
| 11 | Phase 6b | `cb4b4ca` | GREEN 43688779272 | Settings gains a Local Skills manager: list with switches, create, edit, delete with confirm, import from the phone, and a line naming the folder it reads. |
| 12 | Phase 6c | `afb21b2` | GREEN 43692528320 | An explicit "remember / forget / what do you remember" is routed as a directive naming `save_memory`, `delete_memory` or `read_memory` for that turn. Ordinary prose about memory is untouched. |
| 13 | Phase 7 | `e011d1f` | GREEN 43677389348 | The plus chip continues into a new session with the handoff instead of opening a blank one. Blank creation stays on the Sessions surface. |
| 14 | Phase 8a | `47f2f96` | GREEN 43684172102 | A shell command that outlives the inline window returns a handle (poll with `terminal_list`, stop with `terminal_kill`) instead of a false timeout that invited a duplicate run. |
| 15 | Phase 8d | `9faf89a` | GREEN 43697552275 | Settings > AI Agent gains a Model Catalogue: context window (floored, not rounded up), images/pdf, tri-state tool support, and the providers each model is verified for, with a footer saying an unlisted model still works. |
| 16 | Phase 8b | `7d398fb` | GREEN 43698473801 | "Stop and Send" is a real stop-and-send. It cancels, waits for the stream to clear, then sends the typed text, and says so if the request never clears. Queue and Steer map to nothing instead of silently stopping the answer. |
| 17 | Phase 8c | `6147c55` | GREEN 43705466562 | A tool call whose arguments never closed is refused with the tool named and nothing executed. The old path fell back to `{}` and invoked the tool with every parameter missing. A parameterless tool still runs. |

## What is verified, and what is not

Verified at code level, with JVM tests where the change is pure logic: the staging split, the
runtime parking, the resume decision, the write gate at both levels, path resolution and
containment, skill enable/disable and import planning, memory intent routing, the handoff, the
terminal handle, the catalogue maths, send-menu dispatch, and argument integrity.

Verified by CI only, meaning it compiles, dexes and the whole unit suite passes on the CI host:
everything above, plus the files no test drives.

Not verified: anything that needs the phone. That list is the device round, and it is the honest
gap in this report:

- Resume after a real process kill, and that Resume does not resend the request.
- The parked runtime surviving a panel close while a turn is mid-stream.
- The in-chat connector card for a `pat` connector and for an `oauth` connector.
- The write gate's three approval levels against a real provider, including Ask refusing.
- Stop and Send landing mid-stream, and its three-second give-up path.
- A reply cut mid-tool-call producing the refusal instead of a tool run with no arguments.

## Research that shaped the last three stages

VS Code (`chatToolInvocation.ts`, fetched 2026-11-15) keeps a Streaming state whose input is a
display-only `partialInput`; the executable `parameters` object is only set when the call leaves
that state, and `updatePartialInput` refuses to touch a call that is no longer streaming. Tools
declare an `inputSchema`, so arguments are structured rather than text.

Brahma (APK strings, `b.apk`, v1.0.1) also uses a text dialect: the prompt asks for
````action {"action": ..., "args": {...}}``` matched by ```action\s*(\{.*?\})\s*```, with an
"Unknown action: " path. Its opening fence must be closed before anything matches, so a partly
streamed block never fires at all.

Ours was the weakest of the three: a closed tag with an unclosed `arguments` object matched, and
the null fallback became `{}`.

## Next

Device round over the six items above, then future work.
