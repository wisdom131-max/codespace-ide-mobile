# VN Code Copilot - Final Implementation Report (Items 28-38)

Date: 2026-11-12. Repository `wisdom131-max/codespace-ide-mobile`, branch `main`.
Verified green state: **6db186f** (CI run **43305393117**, success).

This report states what exists, what has evidence, and what does not. Labels used:

- **Implemented** - code is on `main`.
- **Locally tested** - the named tests ran on this machine and passed.
- **Stub-tested** - exercised against a loopback HTTP stub, not the real service.
- **CI-verified** - a GitHub Actions run on that exact commit completed successfully.
- **Live-service verified** - a real third-party API answered. **No item in this report is.**
- **Device-verified** - checked on the phone. **Nothing in this report is**; device rounds stay on hold.

## A. Completed work

### Delivered earlier (unchanged here)

P1 research planner, P2 document/media intelligence, P3 git/debugger tool surfaces, P4 advanced tool
intelligence (planner, result check, failure policy, MCP picker, context trim), P5 reliability, P6 session
handoff, P7 copilot UX surface, P8 custom-header vetting. Items 28-31 (limitations register, docs
reconciliation, test matrix), Item 32 (external service infrastructure), Item 33 (Bitbucket/Gitea
expansion of `ServiceApi`). Commits through `d4b5ebc`.

Item 34 (Voice Chat) is explicitly **paused** and is not claimed anywhere in this report.

### Item 35 - Long-context management - commit `364392a`, CI run `43296311098` (success)

- `ContextDigest` extracts evidence with provenance instead of writing prose. A model-only claim is
  rendered `(assistant, unconfirmed)`; a user statement supersedes the model's paraphrase of it.
- Patterns are narrow on purpose: a hash must be backticked or follow "commit", so `deadbeef` in prose is
  not treated as evidence. Output is hard-capped in characters, dropping whole lines rather than cutting
  sentences.
- `retrieve()` ranks evidence over prose, user over model, newer over older, and is bounded.
- `SessionHandoff` now carries commits, test/CI evidence (assistant lines only - a user quoting "12 tests"
  is not evidence of 12 tests), limitations (so a deferral is not "refixed"), and lineage ("Session 2 from
  Git"). It delegates extraction to `ContextDigest` rather than duplicating the rules.
- Tests: `ContextDigestTest` (12), four new `SessionHandoffTest` cases. **CI-verified.**

### Item 36 - Cross-provider retry intelligence - `ProviderRetry` in `364392a`, integration in `71b4ea4` + `6db186f`

`engine/ProviderRetry` is the turn-level policy: a completed side effect outranks every other reason to
retry; non-retryable errors stay non-retryable; `Retry-After` is honoured up to 60 s and refused beyond
that rather than held open; backoff is bounded; an output ceiling is continued, not retried. Nine unit
tests.

Wiring: `ChatFailureAdvice` consults the policy where a retry is **offered**, not where one is taken. The
owner's 2026-11-01 ruling stands - no automatic retry ladder; the engine renders the error and resends only
when Retry is pressed - so the policy decides what to **tell** the user, never to send on its own. The
verdict rides inside the failure card as its detail text; a retryable failure adds nothing, so a key the
user just fixed is not greeted with a stale warning.

Tool-level duplication was already covered by `ToolRetry` (`EXECUTE` / `REUSE_PERSISTED` /
`RECONCILE_FIRST` / `REFUSE`) plus `ToolCallLedger`; this is the turn-level counterpart and does not repeat
it.

Regression caught by CI and fixed in `6db186f`: the advice was first appended as its own row, which broke
`overBudgetCardThenSendAnywayOverridesOnce` and `failoverOffByDefaultShowsFailureWithoutRetry` (both
assert the failure card is the last row - correctly). The tests were not weakened; the layout was fixed.

Tests: `ProviderRetryTest` (9), `ChatFailureAdviceTest` (4). **CI-verified at `6db186f`.**

### Item 37 - L20: GitHub Actions job logs - `485fecc`, verified in `6db186f`

`GET /repos/{o}/{r}/actions/runs/{id}/logs` answers 302 to signed blob storage, and every redirect was
refused (`10b2380`) because following one is how a server moves a credential to a host of its choosing. The
refusal is now narrowed rather than removed. A redirect is followed only when all of these hold:

1. the request is a `GET` (a redirect on a write is never re-sent);
2. it is the first hop (one maximum);
3. the destination is https - checked through the **same** `requireHttps` gate as the first request, so
   production cannot send a plain request and therefore cannot reach a plain redirect (asserted with
   `requestCount == 0`);
4. the destination host is the service's own host, or one it declares in `redirectHosts` (GitHub:
   `objects.githubusercontent.com`, `codeload.github.com`, `github.com`).

The follow-up request carries **no credential at all**, so a host that passes every check still receives
nothing but the request line. Anything else is refused with the destination host named in the message.
`run_logs` is now in the GitHub catalogue.

Tests in `ServiceApiTest` (36 in class, all green): credential-free follow, same-host restriction,
arbitrary-host refusal, non-GET refusal, https production gate, `run_logs` registry shape, plus the
pre-existing refusal suite. **Stub-tested, not live-verified.**

## B. Architecture

Research planner (`ResearchPlanner`) deduplicates search hits and bounds rounds. Thinking Tree holds
reasoning state. Dynamic tool loading (lean mode, relevance, groups, on-demand) selects the catalogue that
`ToolRegistry`/`AgentTools` expose. Facts and persistent memory (`agent/facts/`: Fact model, `FactGuard`,
atomic `FactsStore`, `FactRetrieval`) is a facade that `AgentMemory` delegates to. MCP and connectors run
through `McpClientManager` with forced approval; external REST through `ServiceApi` (host binding, https,
credential isolation, per-service rate limits). Document/media intelligence (`DocText`) and archive
extraction (`ArchiveEngine` over zip/tar/7z, with `RarEngine` and `ZstdEngine`) are bounded readers.
Package intelligence inspects registries read-only. Tool planning and execution run through `ToolPlanner`,
`ToolResultCheck`, `ToolFailurePolicy`, `ToolContextTrim`, `ToolResultPresentation`, `ToolRetry` and the
ledger. Provider capability intelligence resolves model capability. Long-context management is
`ContextDigest` + `SessionHandoff` (Item 35). Provider retry and stream handling is `ProviderRetry`,
`FinishReason`, `Continuation`, `ProviderRetry` advice (Item 36). External API services are
`ServiceApi` + `CredentialHostGuard` (Items 32/33/37).

## C. Security

Credential isolation (`Secret`, redacted `toString`, scrubbed responses); host binding and the custom-header
blacklist (`806f9b3`); redirect refusal, now with a one-hop credential-free exception (`10b2380`, L20);
https required for every service request and every hop; forced approval for MCP calls; approval for
external writes; archive path containment, symlink rejection and entry/size/ratio caps; untrusted-response
handling (retrieved content is data, never instructions); duplicate side-effect prevention at both dispatch
(`ToolRetry` + ledger) and turn level (`ProviderRetry`).

## D. Verification evidence

| Area | Commits | CI | Tests | State |
|---|---|---|---|---|
| Research, docs, media, archive, package, tool intelligence, reliability, handoff, UX, headers | through `d4b5ebc` | green runs recorded in `docs/TEST-MATRIX-2026-11-12.md` | full suite | CI-verified |
| Item 35 long-context | `364392a` | `43296311098` success | `ContextDigestTest` 12, `SessionHandoffTest` +4 | CI-verified |
| Item 36 retry policy + wiring | `364392a`, `71b4ea4`, `6db186f` | `43299201575` failure (my regression), `43305393117` success | `ProviderRetryTest` 9, `ChatFailureAdviceTest` 4, `ChatSessionMappingTest`, `KeyFailoverMappingTest` | CI-verified at `6db186f` |
| Item 37 L20 | `485fecc`, `6db186f` | `43299230714` failure (same regression), `43305393117` success | `ServiceApiTest` 36 | Stub-tested, CI-verified compile+suite |

Known failure kept visible: `BackupTarTest.failureMessageCarriesRealTotals` (L15) fails in this sandbox
because `/root`-owned paths are not writable there; it is an environment artefact, the test is unchanged,
and CI (which does not hit that path the same way) is the arbiter.

## E. External integration verification

- GitHub, GitLab, custom REST: **stub-tested**. GitHub `run_logs` is now reachable but **not
  live-verified**; the shape of a real signed URL is assumed from documented behaviour.
- Bitbucket, Gitea, Jira: **planning-tested** through the executor path.
- **No live external service call has been made or observed.** No token refresh (L21); credentials remain
  project-scoped (L22); there is no OAuth dance for these services.

## F. Remaining limitations

- **L2 / L5**: unchanged, as recorded in the register; not touched in this phase.
- **L15**: `BackupTarTest` sandbox artefact, deliberately still failing and unmodified.
- **L18**: external services remain stub-tested only.
- **L20**: RESOLVED in code and stub-tested; not live-verified.
- **L21**: no credential refresh.
- **L22**: credentials are project-scoped.
- **Real-RAR fixtures**: `RarEngine` is implemented and bounded, but there is no genuine RAR fixture in the
  tree; it is compile- and logic-tested, **not** verified against a real archive.
- **Item 34 (Voice Chat)**: paused by the owner; not started.
- **Device verification**: none in this phase; no APK was produced for testing and none was requested.
