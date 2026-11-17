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
