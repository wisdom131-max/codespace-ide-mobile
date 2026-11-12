# Limitations register - VN Code Copilot programme (P1-P8)

Everything below is a limitation **in the shipped code**, not a task left half-typed. Each entry says
what does not work, what the user sees instead, and why it is not simply a bug to fix later. Anything
not listed here is implemented and covered by tests; the evidence for that is in the consolidated report.

## P2 - Documents and media

**L1. Document readers return text, not structure.** PDF, DOCX, PPTX and XLSX extraction (`83b062f`)
gives the model the words. Tables arrive as flattened lines, slide boundaries are lost, and a spreadsheet
becomes rows of text rather than cells. The agent can read a document; it cannot reason about a table it
cannot see. Structure-aware reading is a separate, sizeable piece of work per format.

**L2. RAR and ZST archives are read, with named exceptions (PARTIALLY RESOLVED, P2.C - locally
compiled and unit-tested 2026-11-12, real-RAR coverage still absent).** This entry
used to say both were refused outright. They are now read: RAR 1.4/4.x/5.x list through junrar
(`RarEngine`) and 4.x/5.x extract under the same containment, entry-count, byte and ratio ceilings as
ZIP; Zstandard decodes as a stream, and `.tar.zst` gets a real entry list through the same tar walk as
`.tar.gz` (`ZstdEngine`). What is still NOT supported, said out loud rather than attempted: **RAR 1.4
extraction** (junrar parses those headers only, so listing works and extraction refuses with a reason);
**password-protected RAR of any variant** (no archive-password prompt exists yet); **RAR symlink
entries** (junrar exposes no link flag, and links are never created because the reader only writes
regular files); **RAR dictionaries above 64 MiB** (capped so a 4 GiB window cannot be allocated on a
phone); and **solid RAR archives are unverified** until the device round - sequential extraction is
junrar's job, and this build has not exercised it. Neither format can be CREATED here: reading only, by
design (see the UnRAR licence note in `docs/P2-C-RAR-ZST-2026-11-12.md`).

**L3. Images are downscaled before the model sees them.** A phone photo sent straight through would be
rejected by the payload budget, so `view_image` downscales. Fine detail in a screenshot may not survive.

## P3 - Autonomous operation

**L4. An interrupted multi-step task restarts; it does not resume.** If the process dies mid-batch, the
`ToolCallLedger` records that a side-effecting call was in flight and the reconciler refuses to repeat it
blindly - but the *plan* is not persisted, so the user's next message starts a fresh turn rather than
continuing from step 4 of 7. Resumption needs durable per-turn state, which the ledger deliberately is
not.

**L5. Verification covers file writes only.** `WriteVerifier` reads a written file back. A `run_command`,
a `git_commit_push`, a `zip_extract` or a connector call is reported as executed on the tool's own word.
Where an uncertain outcome exists, the reconciler proves or refuses rather than repeating, but "proved"
means "no duplicate side effect", not "verified correct".

## P4 - Advanced tool intelligence

**L6. The planner is goal-based, not outcome-based.** `ToolPlanner` plans dependency waves from the
requested goal and re-plans on contradiction. It does not simulate what a tool would return, so a plan
can still be wrong in a way only the first result reveals.

**L7. MCP tool picking is heuristic.** `McpToolPicker` ranks a server's tools against the request and
requires approval before the choice is made. With many similarly named tools on one server the ranking
can pick a neighbour; the approval card names the tool, which is the mitigation.

## P5 - Capability intelligence

**L8. Capability lookup is a table, not a discovery service.** `bd2177c` prefers an exact row and labels
a guess as a guess. A capability that is genuinely absent resolves to "no known capability", which is
correct but not helpful when the user wanted it to be built.

## P6 - Session continuity

**L9. A continued session is linked, not merged.** `92212ac`/`f6c7578` record where a continuation came
from and carry the handoff, but the new session is a new transcript: the old one is referenced, not
folded in, and search across the pair is the user's job.

## P7 - Copilot experience

**L10. Voice is half a conversation.** Input (dictation) and output (read-aloud, `1182ff6`) exist as
independent halves. There is no continuous listen-while-speaking loop, so voice is a way to compose and a
way to hear, not a hands-free mode. This is also what keeps text chat unaffected when no speech engine
exists.

**L11. Read-aloud speaks text, not meaning.** Code fences are dropped and long answers are cut at a
sentence boundary, but a reply full of dense tables or file paths is still read as words. It is a reading
aid, not a summary.

## P8 - Integration, reliability, security

**L12. Each external service keeps its own credential entry.** Redaction and host binding are enforced
(`bf047bf`, `10b2380`, `806f9b3`), but there is no shared token cache across services: connecting five
services means five sign-ins.

**L13. Connector calls through the cloud proxy are bounded, not verified.** The proxy path returns at
most 6000 characters and an actionable error, but its result is trusted as the service's answer. Host
binding applies to on-device services, where the credential lives.

**L14. Approval covers tools, not data.** `use_connector` and every `mcp_*` call require an explicit card
even under AUTO_ALL (`401cedd`, superseded by `1182ff6`), but a tool the user already auto-approved can
read a file or reach a host without a new card each time. That is the design; it is stated here because
"approved tool" is not the same as "approved read of this file".

## Cross-cutting

**L15. The `BackupTarTest` failure is local, not in the app.** `failureMessageCarriesRealTotals` fails
under the root-owned sandbox used for local verification (confirmed again 2026-11-12: the test makes a
directory non-writable and expects the writes to fail, but the sandbox runs as uid 0, so they succeed and
`fileFailures` is 0 instead of 2) and passes in GitHub Actions, where the runner is not root. It is a
test environment artefact and has been left visible rather than muted.

**L16. `401cedd` is red in history.** That commit changed the MCP approval rule while two tests still
asserted the old rule. The tests were corrected in `1182ff6` (green), so main is green at every head from
`1182ff6` onwards, but the older run stands as a failure. It is not rewritten: a green history that
disagrees with what the CI actually said would be worse than a truthful red.
