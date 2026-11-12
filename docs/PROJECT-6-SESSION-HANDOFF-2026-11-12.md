# Project 6 - Long-Context Management: Continue Chat in Another Session

A conversation that has run for hours costs more to carry than to summarise. This adds a handoff: a
linked, fresh session seeded with what still matters, while the old conversation stays intact as the
record.

## What the handoff carries

| Bucket | How it is recognised |
| --- | --- |
| Decisions | "we decided", "I chose", "we settled on", "we agreed to", or a `Decision:` line |
| Constraints | "must not", "never", "do not", "cannot", "only if", "required to" |
| Progress | the assistant's own statements of "done", "landed", "remaining", "next step" |
| Files | path-shaped tokens with an extension |
| Sources | `[S1]`-style markers and URLs already cited in the answer |
| Still open | "todo", "blocked on", "still need", "unresolved", "pending" |

Each bucket is capped at 8 items of 240 characters, so a three-hour conversation produces a handoff
of a few hundred words rather than a transcript.

## Two rules enforced in code, not promised in prose

1. **No credentials.** A line carrying a secret *value* is dropped entirely: `token: …`,
   `api_key = …`, `ghp_…`, `glpat_…`, `sk-…`, an AWS key id, or a private-key block. The word
   "token" alone is not a secret, so "never log the token" (a real constraint) survives while
   "token: ghp_xxx" does not. Dropped rather than masked, because a masked line still points at where
   to look.
2. **No inherited approval.** The rendered handoff states plainly that approvals are not carried
   over, so a continuation cannot act on a grant given in a different session.

## Where it is wired

`ChatOverflowAction.CONTINUE_IN_NEW_SESSION`, appended after the approved seven items so their
signed-off order is untouched. `ChatPanelScreen` builds the handoff from the current session's own
rows, creates a new session through the existing `manager.createSession`, seeds it with the rendered
text, and reports what was carried in the notice line. The old session is never modified.

## Linkage, honestly

The new session is linked by its seeded first message, which names the label of the conversation it
came from. There is no `parentSessionId` field: adding one means changing `SessionMeta` and the text
index codec, which risks every existing stored session for a bookmark. A multi-session chain works
(session B can itself be continued), and each label states its origin.

## Tests

10 tests in `SessionHandoffTest`: each bucket, credential exclusion, approval disclaimer, bounded
lists, label shortening, dedupe, and the constraint-not-progress rule. Full suite 2463, one known
`BackupTarTest` root-sandbox failure.

## Limitations

- Extraction is lexical, like the research claim scanner. A decision phrased unusually may not be
  caught; it will still be in the old session.
- The handoff is a message, not a system block, so the model can ignore it. That is deliberate: a
  user-visible message can be corrected by the user.
- Not verified on a device.
