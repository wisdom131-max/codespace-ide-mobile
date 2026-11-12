# P3.C - an attempted write is not a successful write

The turn reported `Done - wrote file` the moment `executeTool` returned without throwing. A write can
return cleanly and still not be on disk in the state that was asked for: the path resolved somewhere
else, a replacement matched nothing, the filesystem was full. The Copilot then told the user the change
was made, and the model moved on believing it.

## What happens now

After every executed tool call, `WriteVerifier` reads the target back and answers with one of:

- **Verified** - the file holds exactly what was asked for. Silent: this is the expected case, not news.
- **Differs** - the file exists but is not the requested content.
- **Missing** - the file is not there. A path that cannot be read is *not* evidence of success.
- **Uncheckable** - the tool writes but does not record the content it wrote (a patch). Silent rather
  than a false claim either way.
- **NotApplicable** - not a write.

`Differs` and `Missing` produce one activity row AND one line appended to the tool results, so the model
sees it too instead of continuing on a wrong assumption.

Comparison is exact for short content and by SHA-256 for a body over `ArgFingerprint.KEEP`, so a large
file is verified without holding a second copy of it in memory. A partial edit (replace/insert) is
verified by its fragment being present, which is all that operation guarantees.

## Tests

`WriteVerifierTest` (9): a landed write is verified and silent, a write that did not land says
"Attempted, not confirmed" and names the path, a missing file is not success, a partial edit that
applied is verified, a partial edit that did not apply is reported, a patch without recorded content
claims nothing, a read tool is not applicable, a long body is compared by fingerprint, and every
verifiable tool is actually classified as side-effecting in `ToolEffectIndex` (so the verifier cannot
drift out of sync with the retry policy).

Full suite 2581 tests, one known `BackupTarTest` root-sandbox failure (green in CI).

## P3 status

- A. Autonomous task execution across steps: P4.A's plan plus P4.B's coordinator provide ordering,
  dependencies, duplicate suppression and cancellation. **Resume of an interrupted multi-step task
  after process death is not implemented**; the ledger records the interrupted call, not a resumable
  plan.
- B. Reasoning and planning: `ToolPlanner` is on the request path (P4.A) and adapts per goal.
- C. Verification: this commit.
- D. Recovery: `ToolReconciler` proves or refuses an uncertain write before any repeat; `ToolRetry`
  classifies; `ToolDispatchGuard` refuses rather than duplicating a side effect.
- E. User control: the approval gate is unchanged and still runs before any execution, including
  connector calls under AUTO_ALL.
