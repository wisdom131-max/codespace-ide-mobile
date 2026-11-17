# Project Eight — Integration reliability and security

**Branch:** `project-eight`, based on `main` at `b9b4008`. Project Seven (`project-seven`, tip
`21478b5`) is still unmerged, so this branch is the integrated baseline of Projects One to Six plus
`main`'s Copilot UX; Project Seven's own suite is green on its own branch and is quoted, not merged.

**CI: run `44005876121` on `1cb669b`, 26/26 steps, success.**

**Status: PARTIAL.** Ten areas were inspected. Three areas produced confirmed defects, all fixed and
tested here. The remaining areas were audited and produced no confirmed defect in this pass, which is
a statement about the evidence gathered, not a claim that they are safe against everything.

## Confirmed findings

| # | Severity | Area | Component | Finding | Fix | Regression test | Status |
|---|---|---|---|---|---|---|---|
| 8-01 | HIGH | 8.6 File/archive | `ArchiveVerifier.verifyListing` | Only an entry containing `..` was refused. An ABSOLUTE entry (`/data/data/<pkg>/...`), a drive-rooted entry (`C:/...`), a UNC path (`//host/share`) and a control-character name all reached a **VERIFIED** verdict, so the caller was told a hostile archive had been checked. | `unsafePath()` rejects absolute, network-rooted, drive-rooted, climbing and control-character names, and every entry is filtered through it. | `Stage8ArchiveTest.an absolute entry is refused rather than verified`, `a drive-rooted or network entry is refused`, `a climb hidden mid-path is refused` | Fixed, locally tested, CI-green |
| 8-02 | HIGH | 8.6 File/archive | `ArchiveVerifier.parseListing` / `verifyListing` | Listing lines the parser could not read were silently DROPPED, and the archive was still reported **VERIFIED**. A malicious entry written as an unparseable line therefore escaped review and was approved. | Unparsed lines are counted; any skipped line now returns INCONCLUSIVE with the count. Size parsing widened from 2–9 to 1–9 digits so a one-digit size is no longer mistaken for prose. | `Stage8ArchiveTest.an absolute entry is refused rather than verified` | Fixed, locally tested, CI-green |
| 8-03 | LOW | 8.6 File/archive | `ArchiveVerifier.CORRUPT` markers | Common corrupt-archive wording ("not a zip archive", "truncated", "unexpected eof") fell through to INCONCLUSIVE instead of being recognised as a corrupt archive. Not a security hole: the verdict was still not VERIFIED. | Markers extended. | `a corrupt archive is refused and an unreadable one is not called clean` | Fixed, locally tested, CI-green |

## Areas audited with no confirmed defect in this pass

Assertions below are behaviour tests against the production component, not dependency scans.

| Area | What was checked | Evidence | Residual risk |
|---|---|---|---|
| 8.1 Threat model | Trust boundaries read from the code: model output, tool args, archive entries, connector responses, persisted retention, credentials | This report | No formal review by an external party |
| 8.2 Provider reliability | Project Five normalisation, bounded retries, finish reasons, cancellation | Existing provider suites, unchanged by this branch | Live provider behaviour is mocked |
| 8.3 Tool execution | `AgentTools.executeTool` is the only gate; approvals, roles, MCP, prerequisites, duplicate execution | Existing dispatch/journal suites | A future second execution path would not be caught automatically — see deferred item 8-D1 |
| 8.4 MCP and connectors | Forced approval for `mcp_*` and `use_connector` even under AUTO_ALL; no always-allow offer | `Stage8SecurityTest` | Connector output trust is bounded by the tool layer, not re-derived here |
| 8.5 Credentials | Key-shaped secrets removed from message and attachment text, idempotent redaction, credential headers replaced whole | `Stage8SecurityTest` | Redaction is pattern-based; an unusual key shape could pass |
| 8.7 Conversation and memory | Per-session retention, inert historical exchanges, HandoffGuard, corrupt-file recovery | Project Six suites | None new |
| 8.8 Agent and roles | Role intersection, plan approval, bounded recovery | Project Three suites | Malicious-plan corpus is finite |
| 8.9 Android lifecycle | Voice cleanup, stale callback rejection, capture muted while speaking | Project Seven voice suites | Device lifecycle not exercised |
| 8.10 Cross-project | Provider retry with tools, continuation with historical exchanges, archive plus hostile paths | Project Four/Six suites plus this branch | Interaction matrix is sampled, not exhaustive |

## Deferred and open

- **8-D1 (MEDIUM, deferred):** no automated guard fails the build if a second tool-execution path
  appears. A source-scanning architecture test is the right fix and needs the registry's exact call
  shape; it is not in this pass.
- **8-D2 (MEDIUM):** symlink entries inside an archive are not parsed from `tar -tv` output, so a
  symlink that resolves outside the destination is only caught as a count mismatch
  (INCONCLUSIVE), not as a named refusal. Detection bias is safe; the message is not.
- **8-D3 (LOW):** redaction is pattern-based; an unrecognised credential shape survives.
- **Device verification:** none performed for this branch.

## Preservation

No permission, approval, role, MCP, prerequisite or side-effect control was weakened. The only
production changes are the three archive-verifier findings above, each a tightening.

## Checkpoint 2 — execution boundary, archive links, structural redaction

### 8.3 FIXED — the single execution gate is now an enforced invariant

Call graph, read from the source rather than assumed: `AgentTools.executeTool` is the only public entry;
it consults the plan, permission store, approval gate, prerequisite ledger and duplicate guard, then
calls the private `executeToolBody`, which dispatches through one `when` over the tool names. There are
two call sites of the body, both inside the gate file (the normal path and the bounded retry).

`ExecutionBoundaryScan` enforces four things over the real sources and the live `AgentTools.toolNames()`:
the body stays `private`; no other file calls it; no other file declares an `executeTool`; and the
registry and the dispatcher agree in BOTH directions (a name without an arm, or an arm nobody can
reach, fails). Comments are stripped first so documentation about the gate is not mistaken for a call.

Measured on this tree: **122 registry names, 122 dispatch arms, 0 uncovered, 0 unadvertised.**

Negative evidence: five tests inject a bypass and assert the guard fails and names the file - a helper
calling the body directly, a second `executeTool`, an advertised-but-unwired tool, and a wired-but-
unadvertised arm.

Blind spots, stated plainly: it is a source scan, so reflection or a name assembled at runtime would
not be caught, and a tool that executed work outside the gate file through a different function name
would need a matching rule. It catches the ordinary regression, not a determined bypass.

### 8.6 PARTIALLY FIXED — archive link targets

Checkpoint 1 disclosed that link entries were not examined. Now `unsafeLink()` reads every `name -> target`
line and refuses: targets that climb (`../`), absolute targets, drive-rooted, network-rooted, targets
with control characters, and link metadata with no readable target. Targets are compared as text and
never resolved, so nothing is followed while verifying. Link lines now parse as entries, so a clean
archive containing only safe relative links can still reach VERIFIED instead of being permanently
INCONCLUSIVE.

**Formats actually tested:** the plain listing format the verifier consumes (`path size` and
`path -> target`). **Not tested:** real ZIP symlink attributes (the ZIP parser used here exposes no link
metadata) or tar's verbose `lrwxrwxrwx` line, which still reads as unparseable and therefore yields
INCONCLUSIVE, not VERIFIED. Hard links and device/fifo entries are not inspected.

### 8.5 PARTIALLY FIXED — structural credential shapes

The old tiers only knew issuer prefixes. Added structural rules that apply at both tiers and keep the
name while removing the value: `Bearer`/`Basic` schemes, credential query parameters
(`api_key`, `token`, `access_token`, `refresh_token`, `secret`, `password`, `client_secret`, `key`), and
JSON keys (`api_key`, `token`, `secret`, `password`, `authorization`, `x-api-key`).

Evidence: 8 synthetic cases assert the value never survives while the header name, tool name, status
code, model name and unrelated query parameters do. Idempotence, empty and malformed input are covered,
and ordinary code (`val token = ...`, `basicAuthEnabled()`) is asserted unchanged.

Known blind spots: a bare credential with no prefix, scheme, parameter name or JSON key is
indistinguishable from an ordinary string and is not redacted; base64 payloads are not decoded to look
for secrets; and `CrumbLog` keeps its own narrower rules.

### Deferred issues, status

| Issue | Status |
|---|---|
| Automated guard against a second execution path | FIXED (source-level invariant, 5 negative tests) |
| Archive symlink entries unexamined | PARTIALLY FIXED (listing-level; ZIP link metadata unavailable) |
| Pattern-only redaction | PARTIALLY FIXED (structural shapes added; unlabelled secrets still out of reach) |
| Real ZIP/tar attribute parsing for links | STILL OPEN (needs an archive API that exposes attributes) |
| Runtime proof of link refusal during extraction | STILL OPEN (verifier-level only; extraction path unchanged) |
