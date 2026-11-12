# VN Code Copilot - consolidated final report (P1-P8)

Scope: the eight-project programme the owner set out on 2026-11-11/12. Every claim below is tied to a
commit and, except where marked, to a green CI run on that commit's own SHA. The honest gaps are in
`docs/LIMITATIONS-REGISTER-2026-11-12.md`; nothing here is asserted that the register contradicts.

## Evidence

| Project | Delivered | Commit | CI |
|---|---|---|---|
| P1 Research | `ResearchPlanner`: dedupe by normalised URL and title-token overlap, per-host caps, bounded rounds, follow-up generation, honest stop reason | `4955c04`, `269f909` | 43190927614 |
| P2 Documents & media | Document text extraction wired for PDF/DOCX/PPTX/XLSX; **archive engine** for ZIP/APK/JAR/AAB, TAR, TAR.GZ, GZ, BZ2, XZ, 7Z by signature, ZipEngine's Slip/bomb/ratio/containment rules reused, RAR/ZST refused with a reason | `83b062f`, `256df3e` | 43209910777, 43242733907 |
| P3 Autonomous operation | Result verification per tool call, planner on the request path, reconciler that proves or refuses an uncertain side effect, dispatch guard that will not duplicate one; **writes verified by reading the file back** | `ed8160f`, `86e7ba0`, `9c7f7db` | 43201549176, 43245172190 |
| P4 Tool intelligence | Planner, dependency-aware coordinator, result checker, failure policy, MCP picker, context trim, output ceiling | `d0707ee`, `500ab94`, `900240f`, `a50a245` | 43214188973, 43212548804 |
| P5 Capability intelligence | Capability lookup prefers the exact row and marks a guess as a guess | `bd2177c` | 43233399188 |
| P6 Session continuity | Handoff into a linked fresh session, with the origin recorded | `f6c7578`, `92212ac` | 43207442899, 43231249011 |
| P7 Copilot experience | Duplicate-reply prevention, partial-output labelling, continuation action, **reply read-aloud** with honest unavailable states | `ea508c4`, `28553f4`, `1182ff6` | 43205321717, 43250981072 |
| P8 Integration & security | MCP approval forced (**401cedd**, red on stale tests, corrected in **1182ff6**), custom-header leash, redirect header refusal, **credential host binding** | `806f9b3`, `10b2380`, `401cedd`, `bf047bf` | 43217340494, 43221633663, 43251310965 |

Cross-cutting, delivered alongside: `4c6e9be` continuation guard reads the flag it sets; `876f4ec`
fingerprints long ledger arguments instead of storing them.

## The defects that mattered most

**AUTO_ALL reached third-party MCP servers with no card.** `requiresForcedApproval` forced only
`use_connector`. A permission level the user set for local file reads silently authorised code on a
remote server, against an explicit requirement that MCP always asks. Fixed in `401cedd`.

**A service credential could be posted to any host.** `LocalConnectorCall.build` accepted an absolute URL
from the model and attached the Supabase/n8n/Firebase key to it, and OkHttp followed redirects. Fixed in
`bf047bf`: https only, host binding with label-boundary matching, redirects refused with a reason.

**"Done" was claimed for writes that did not land.** Fixed in `9c7f7db`: every write is read back and a
failure is reported to the user *and* appended to the tool results, so the model cannot build on it.

## Test evidence

Full suite at head: **2600 tests, 1 failure, 3 skipped.** The failure is `BackupTarTest
failureMessageCarriesRealTotals`, which fails only under the root-owned local sandbox and passes in
GitHub Actions (L15 in the register). New tests added by this programme: `ResearchPlannerTest` (12),
`ArchiveEngineTest` (11), `WriteVerifierTest` (9), `SpeechTextTest` (7), `CredentialHostGuardTest` (8),
`McpApprovalForcedTest` (4), plus the existing dispatch and lifecycle suites.

## Method notes

One bug per commit, compile and the relevant tests before the push, `IMPL.md`/`IMPLEMENTED.md` updated in
the same commit as the code. The owner asked for the last five commits to be pushed back to back and CI
checked for all of them afterwards; that is why `401cedd` is red in history rather than never having
existed. `1182ff6` corrected the two stale assertions that caused it, and the register says so plainly.

No sub-agents, no background workers: every item above was implemented in-line in the main app, as the
owner required.
