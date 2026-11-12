# Limitations register - VN Code Copilot programme (P1-P8)

Everything below is a limitation **in the shipped code**, not a task left half-typed. Each entry says
what does not work, what the user sees instead, and why it is not simply a bug to fix later.

**Reviewed 2026-11-12 against the code and the tests that actually run.** Every entry now carries a
status, the files it lives in, and the test that would catch a regression:

| Status | Means |
|---|---|
| **STILL VALID** | The limitation exists as described. Nothing in the code claims otherwise. |
| **PARTIALLY RESOLVED** | Part of the original statement no longer holds; the rest is named precisely. |
| **RESOLVED** | The limitation no longer exists, and a test proves the replacement behaviour. |
| **DEFERRED** | Deliberately not addressed in this programme. Reason given. |
| **NOT VERIFIED** | Implemented and unit-tested, but the claim cannot be made without a live/real input that does not exist here. |

Standing evidence ladder used throughout: **implemented** → **unit-tested** (JVM, no Android) →
**stub-tested** (loopback HTTP/API double) → **CI-verified** (GitHub Actions green at that commit) →
**live-service verified** (a real credential against the real API) → **device-verified** (the owner's
phone). Most of this programme stops at CI-verified. Nothing here is device-verified yet, and nothing in
the external-service work is live-service verified.

## P2 - Documents and media

**L1. Document readers return text, not structure. - STILL VALID**
PDF, DOCX, PPTX and XLSX extraction (`83b062f`) gives the model the words. Tables arrive as flattened
lines, slide boundaries are lost, and a spreadsheet becomes rows of text rather than cells. The agent can
read a document; it cannot reason about a table it cannot see. Structure-aware reading is a separate,
sizeable piece of work per format. Code: `agent/docs/DocText.kt`. Tests: `agent/docs/DocTextTest.kt`
(extraction of each format plus honest failure on encrypted/scanned/broken input) - the *structure* gap
has no test because there is no structure to test.

**L2. RAR and ZST archives are read, with named exceptions. - PARTIALLY RESOLVED, real archives NOT VERIFIED**
RAR 1.4/4.x/5.x list through junrar (`RarEngine`) and 4.x/5.x extract under the same containment,
entry-count, byte and ratio ceilings as ZIP. Zstandard decodes as a stream, `.tar.zst` gets a real entry
list through the tar walk, and a truncated frame is now detected and named (`1953a06`) rather than
silently ending. Multi-frame `.zst` is handled by the continuous-frame mode, and that behaviour is
asserted (`ZstdEngineTest`). What is still NOT supported, said out loud: **RAR 1.4 extraction** (listing
works, extraction refuses with a reason); **password-protected RAR of any variant**; **RAR symlink
entries** (junrar exposes no link flag, and only regular files are ever written); **RAR dictionaries
above 64 MiB** (capped so a 4 GiB window cannot be allocated on a phone); **solid RAR archives**
(sequential extraction is junrar's job and has not been exercised). Neither format can be CREATED here:
reading only, by design (UnRAR licence, `docs/P2-C-RAR-ZST-2026-11-12.md`).
**Why NOT VERIFIED:** every RAR assertion runs on archives this build cannot legally produce. The tests
that exist (`RarEngineTest`, `ArchiveEngineTest`) exercise the engine's guards - unsupported variant,
oversize dictionary, containment, entry ceilings - not "this real RAR from the internet extracts
correctly". A legally distributable RAR fixture remains the missing input.

**L3. Images are downscaled before the model sees them. - STILL VALID**
A phone photo sent straight through would be rejected by the payload budget, so `view_image`
downscales. Fine detail in a screenshot may not survive.

## P3 - Autonomous operation

**L4. An interrupted multi-step task restarts; it does not resume. - STILL VALID**
If the process dies mid-batch, `ToolCallLedger` records that a side-effecting call was in flight and the
reconciler refuses to repeat it blindly - but the *plan* is not persisted, so the user's next message
starts a fresh turn rather than continuing from step 4 of 7. Code: `agent/ToolCallLedger.kt`,
`agent/ToolReconciler.kt`. Tests: `ToolCallLedgerTest`, `ToolReconcilerTest` assert the refusal, not
resumption, because resumption does not exist.

**L5. Verification covers file writes only. - PARTIALLY RESOLVED**
`WriteVerifier` reads a written file back. A `run_command`, a `git_commit_push`, a `zip_extract` or a
connector/service call is reported as executed on the tool's own word. What changed: an *uncertain*
outcome is no longer simply repeated - `ToolReconciler` proves the effect happened (through a state
check such as a file-exists probe) or refuses, and path-modifying tools are reconciled rather than
retried (`36e8495`). What remains: "proved" means "no duplicate side effect", not "verified correct".
Code: `agent/ToolReconciler.kt`, `agent/ToolResultCheck.kt`. Tests: `ToolReconcilerTest`,
`ToolResultCheckTest`.

## P4 - Advanced tool intelligence

**L6. The planner is goal-based, not outcome-based. - STILL VALID**
`ToolPlanner` plans dependency waves from the requested goal and re-plans on contradiction. It does not
simulate what a tool would return, so a plan can still be wrong in a way only the first result reveals.
Tests: `ToolPlannerTest`, `ToolBatchPlanTest`.

**L7. MCP tool picking is heuristic. - STILL VALID**
`McpToolPicker` ranks a server's tools against the request and requires approval before the choice is
made. With many similarly named tools on one server the ranking can pick a neighbour; the approval card
names the tool, which is the mitigation. Tests: `McpToolPickerTest` (ranking and the approval
requirement, not ranking *quality*).

## P5 - Capability intelligence

**L8. Capability lookup is a table, not a discovery service. - STILL VALID**
`bd2177c` prefers an exact row and labels a guess as a guess. A capability that is genuinely absent
resolves to "no known capability", which is correct but not helpful when the user wanted it to be built.

## P6 - Session continuity

**L9. A continued session is linked, not merged. - STILL VALID**
`92212ac`/`f6c7578` record where a continuation came from and carry the handoff, but the new session is a
new transcript: the old one is referenced, not folded in, and search across the pair is the user's job.
Tests: `SessionHandoffTest` (what survives, and that no credential or approval is inherited),
`ContinuationTest`, `ContinuationGateTest`, `ContinuationDispatchTest`.

## P7 - Copilot experience

**L10. Voice is half a conversation. - STILL VALID**
Input (dictation) and output (read-aloud, `1182ff6`) exist as independent halves. There is no continuous
listen-while-speaking loop, so voice is a way to compose and a way to hear, not a hands-free mode. This
is also what keeps text chat unaffected when no speech engine exists.

**L11. Read-aloud speaks text, not meaning. - STILL VALID**
Code fences are dropped and long answers are cut at a sentence boundary, but a reply full of dense tables
or file paths is still read as words. It is a reading aid, not a summary.

## P8 - Integration, reliability, security

**L12. Each external service keeps its own credential entry. - STILL VALID**
Redaction and host binding are enforced (`bf047bf`, `10b2380`, `806f9b3`), but there is no shared token
cache across services: connecting five services means five sign-ins. `ServiceApi` (Item 32) inherits this
deliberately - it reads one named project secret per service and never caches a token in memory beyond a
single call. Tests: `CredentialHostGuardTest`, `StoredCredentialsMigrationTest`.

**L13. Connector calls through the cloud proxy are bounded, not verified. - STILL VALID**
The proxy path returns at most 6000 characters and an actionable error, but its result is trusted as the
service's answer. Host binding applies to on-device services, where the credential lives. Note that this
is the *only* path where a credential never touches the phone; `ServiceApi` is the opposite trade (the
credential stays local, so every call is on-device and host-bound).

**L14. Approval covers tools, not data. - STILL VALID**
`use_connector`, every `mcp_*` call and now `service_write` require an explicit card even under AUTO_ALL,
but a tool the user already auto-approved can read a file or reach a host without a new card each time.
That is the design; it is stated here because "approved tool" is not the same as "approved read of this
file". Tests: `McpApprovalForcedTest`, `ConnectorApprovalTest`, `AgentFlowGateScopeTest`,
`ApprovalLevelSpecTest`.

**L17. Service operations are a curated catalogue, not the whole API. - STILL VALID**
`service_read`/`service_write` cover a fixed list of named operations per service (GitHub, GitLab,
Bitbucket, Gitea, Jira, CI/CD operations and one configured custom base). An endpoint outside the
catalogue cannot be called at all; adding one is a code change, not a setting. That is the trade for
never letting the model name a URL, and the reason a service that changes its API needs a release rather
than a config edit. Tests: `ServiceApiTest` (registry invariants: unique ids, fixed methods, leading-slash
paths).

**L18. External services were tested against a loopback stub, not the real APIs. - STILL VALID**
Every rule (host binding, redirect refusal, bounds, scrubbing, read/write split, per-service auth header
construction) is asserted, and request shapes follow the published API, but no live token was used: the
first real call is the user's. A service that changes a path or an auth header surfaces as a 404/401 the
agent reports rather than a silent wrong answer. Tests: `ServiceApiTest` (24 tests at Item 32).

**L19. The custom API is one base and one credential per project. - STILL VALID**
`api_base_url` + `api_token` name a single host; a second internal API needs a second project or a new
registry entry. Writes are gated by the same approval card as the named services, but there is no
per-operation allowlist beyond the method, so `post` can reach any path under the configured base.

## Discovered during Items 32 and 33

**L20. GitHub Actions job logs were not retrievable through this app. - RESOLVED (stub-tested, not live-verified)**
`GET /repos/{owner}/{repo}/actions/runs/{id}/logs` answers with a 302 to signed blob storage, and every
redirect was refused (`10b2380`), so the operation was left out of the catalogue rather than shipped broken.
The scoped exception named here as the correct fix is now implemented in `ServiceApi`: a redirect is
followed only when **all** of these hold - the request is a `GET`, it is the first hop (one maximum), the
destination is https, and the destination host is the service's own host or one the service names in
`redirectHosts` (GitHub: `objects.githubusercontent.com`, `codeload.github.com`, `github.com`). The
follow-up request carries **no credential at all**, so a host that passes the check still receives nothing
but the request line. Anything else is refused with the host named in the message. `run_logs` is now in the
GitHub catalogue.

The https rule is one gate covering both the first request and the hop, threaded through the same
`requireHttps` flag; production passes it, so a plain-http base or hop is unreachable there and the loopback
test seam cannot mask that (asserted: `productionNeverFollowsAPlainHttpRedirectBecauseItNeverSendsAPlainRequest`).

Evidence: `ServiceApiTest` - `aSameHostRedirectIsFollowedWithoutTheCredential`,
`aRedirectToAnotherHostIsStillRefused`, `aRedirectOnAWriteIsNeverReSend`,
`productionNeverFollowsAPlainHttpRedirectBecauseItNeverSendsAPlainRequest`, `githubNowOffersRunLogs`, plus
the pre-existing refusal tests. Stub-tested only: no live GitHub run log has been fetched, so the shape of a
real signed URL is assumed from the documented behaviour, not observed (see L18).

**L21. Service credentials are used as stored; there is no refresh. - STILL VALID**
A personal access token (or Bitbucket app password, or Jira API token + email) is read from
`SecureTokenStore` and used until it stops working. An expired token surfaces as a 401 that names the
credential, not as a silent failure, but nothing renews it: OAuth for these services stays with the
connector stack, which has its own flow.

**L22. Service credentials are project-scoped. - STILL VALID**
`SecureTokenStore.projectSecret(projectId, name)` means the same GitHub token must be entered once per
project, and a call with no project open is refused with that reason rather than falling back to a global
secret.

## Cross-cutting

**L15. The `BackupTarTest` failure is local, not in the app. - STILL VALID (environment artefact)**
`failureMessageCarriesRealTotals` fails under the root-owned sandbox used for local verification (confirmed
again 2026-11-12: the test makes a directory non-writable and expects the writes to fail, but the sandbox
runs as uid 0, so they succeed and `fileFailures` is 0 instead of 2) and passes in GitHub Actions, where
the runner is not root. Evidence for both halves on the same tree: local full suite **2639 tests, 1
failure** (this one) at `6aeb5c8`; CI **43286546707, success** at the same commit. The test is left
visible rather than muted or weakened - muting it would hide a real permission assumption.

**L16. `401cedd` is red in history. - RESOLVED (historical record)**
That commit changed the MCP approval rule while two tests still asserted the old rule. The tests were
corrected in `1182ff6` (green), so main is green at every head from `1182ff6` onwards. The entry is kept
as RESOLVED rather than deleted: the red run stands as what the CI actually said, and rewriting it would
be worse than a truthful red.
