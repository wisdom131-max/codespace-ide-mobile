# P6.C - session lineage

`f6c7578` made "continue in another session" work. It built a handoff, created a session, seeded it,
and left no record of the relationship: after three hops the list was three unrelated titles.

## What is recorded

`SessionLineage`, a small file beside the transcripts (`chat/<project>/session-lineage.tsv`):

- `sessionId`, `parentId` (null for a root), `seq`, `origin`.
- `seq` counts from the ROOT, so hop five is `Session 5`, and `origin` is the ROOT's title, so hop
  five still says what the chain is about. Reading `Session 5 from explain git` is the whole feature.
- A parent with no link (a session created before lineage existed) is treated as a root: the successor
  starts at 2 with the parent's own title as the origin. Nothing is lost and nothing is invented.

## Where it is wired

`ChatPanelScreen`'s `CONTINUE_IN_NEW_SESSION` overflow action:

1. builds the handoff exactly as before,
2. records the current session as a root if it is not known yet,
3. creates the new session, records it as the successor, and renames it to
   `Session N from <root topic>`,
4. the notice now leads with that label.

So the label is visible in the sessions list with no new UI, the chain is stored for multi-hop
continuation, and `SessionHandoff` itself is untouched.

## Why a separate file, not `SessionMeta`

The session index is a strict, versioned format: every field is validated and a bad record drops the
session. Lineage is presentational. A format bump plus a migration for a display label is a poor trade,
and a damaged lineage file degrades to "no lineage" instead of costing a conversation. `SessionMeta`
is untouched, so the index stays at `vsessions 2` and existing sessions load unchanged.

## Tests

`SessionLineageTest` (11): no lineage on an untouched project, a root is session one, a successor
increments and keeps the root label, a third hop counts from the root, continuing from a pre-lineage
session starts at two, lineage survives a reload, re-recording does not duplicate, tabs and newlines
survive escaping, a blank title falls back to the standard label, damaged lines are skipped rather than
fatal, and a very long origin is bounded.

Full suite 2561 tests, one known `BackupTarTest` root-sandbox failure (green in CI).

## P6 status

- A. Continue in another session, in the overflow menu, distinct from Continue Response: done (`f6c7578`).
- B. Structured handoff (goal, constraints, decisions, paths, results, risks, no credentials): done (`f6c7578`).
- C. Lineage, numbering, labels, multi-hop: this commit. Restoration of a successor session works
  because it is an ordinary session; the chain is read from the lineage file whenever it is needed.
- D. Long-context intelligence (compression, retrieval, budgeting): `ToolContextTrim`, `FactRetrieval`
  and the continuation budget already provide it. What is NOT done is automatic conversation
  summarisation of old turns; it is listed as a limitation rather than claimed.
