# FIX-PLAN.md — CodeSpace IDE gap remediation sequence (committed 2026-09-24)

## P-SERIES COMPLETION (2026-09-25)

**P0 → P3d SHIPPED. All planned fix phases before P4 are complete, each green on first
push and revertable as one commit:**

| Phase | Commit | CI | Gap IDs |
|---|---|---|---|
| P0 — Settings write-safety | 29e80c8 | #2905 | SK01, SK02 |
| P1 — Data-loss chain | 7216ec0 | #2907 | G01, TB03, TB01, SG02, CH01, CH05 |
| P2a — Containment utility | e707ccc | #2909 | EX04, EX05, EX07, RG03, RG04, RG07, OG04 (+ VG02, VG02-b, IG01 closed by same consumption) |
| P2b — Loopback bind | 988b4a0 | #2911 | VG01 (VG02/IG01 verified closed by P2a) |
| P2c — Credentials/consent + TrustState | 0342c96 | #2915 | SG04, SG16, IG02, IG15, CH03 |
| P3a — Typed results S01 | 5ddb03b + 1802702 | #2918/#2919 | TP03, PR01, PR03, XG05, EX01, EX02, EX06, SR03, SR04, SG05, IG05, RG02, OG02, IC04 |
| P3b — PLAN A canonical store | ae5633a | #2922 | G03, CH02, DG02, LS06, DG11, DG08 (PR14 partial: editor side) |
| P3c — Polling → flows | fdb660d | #2924 | PG02, PG04, TP08, PG05 |
| P3d — Delete-the-duplicates | d6669fc | #2926 | VG04, VG10, IG06, IG03, IG04 |

**Tally (verified scan of MASTER-GAPS.md, 249 gap rows): 52 closed, 7 moved to
F-TRACK (TG01-TG07, owner-ruled feature-build), ~190 remain for P4.** TP01 resolved
pre-plan (kept for audit trail); TP02 hotfix shipped 9d4923b, device-verification
batched into P5. PR14 PANEL side (Problems rows from the store) remains open for P4.

**Next: P4 by group, or F-TRACK F1 — awaiting owner go.**


Owner-approved sequence. Each phase is its own commit(s), revertable alone: reverting a phase re-opens exactly the gap IDs it closed and nothing else. Every phase lists "what did I remove" in its commit message. CI must be green before the next phase starts. Full audit context: MASTER-GAPS.md (249 gaps), MASTER-CONNECTIONS.md (cross-group ledger), WHERE-TO-LOOK.md (symptom index), the 18 GROUP docs + 2 addenda.

## PR14 STATUS (explicit, per owner 2026-09-25)

**Partial — explicit open item.** P3b (code `ae5633a`, CI #2922 green) shipped PR14's EDITOR side: squiggle ranges now live in one canonical store (`editor/PerFileStateStore.kt`) and the editor consumes it. The Problems PANEL rows still read the last-published `LspManager` cache — the mid-edit shift mismatch (rows stale between publishes while squiggles shift through EditShiftHelper) is NOT closed. Panel rewiring to consume the same store is an explicit open item for P3c/P4. Do not record PR14 closed until the panel reads the store.

## TP02 STATUS (explicit, per owner 2026-09-24)

**Fix shipped, DEVICE-UNCONFIRMED — not "closed."** Hotfix `9d4923b` (CI #2903 green) shipped loopback-only bind + per-process bearer token on AgentApiServer, with the token exported to the guest shell profile. The owner batches TP02's device verification (no-token→401, token→tools, LAN refused) into the FULL TEST PASS at the end (P5), not in isolation. Until that pass confirms, TP02 stays flagged "fix shipped, device-unconfirmed" in this file and in MASTER-GAPS.md — never recorded as closed.

## Phases

| Phase | Scope | Closes | Notes |
|---|---|---|---|
| **P0 — Settings write-safety** | Atomic writes (tmp+rename) + corrupt-parse quarantine in JsonSettingsStore; facade swallow-sites log real errors; observable write-failure state (importJson typed-Boolean is the in-codebase model). | SK01, SK02 | Owner-ruled first: every later fix persists config through/alongside this store; settings write-safety first = every other fix's persistence trustworthy. |
| **P1 — Data-loss chain (one workstream)** | Typed disk-write result + dirty-state truth (G01); close-without-dirty-warning dialog (TB03); backup-delete verification (TB01); typed restore results (SG02); chat-apply typed undo + refuse-on-staging-failure (CH01+CH05 together, owner-ruled). | G01, TB03, TB01, SG02, CH01, CH05 | Coupled chain G01→TB03→TB01: silent write failure, close without dirty warning, backup deletion with false restoration success. Treat together; a TB03 dialog alone cannot recover an already-deleted backup. |
| **P2a — Containment utility + archive sites** | ONE canonical-path containment utility (ProotInstaller.kt:107 boundary form) consumed at all 10 mandatory call sites: zip/tar extraction, copy boundaries, wizard name (OG04). | EX05, RG03, RG04, EX04, EX07, RG07, OG04 | Also confirms the shared-fix pairing EX05↔RG03/RG04. |
| **P2b — Host-facing network/state sites** | Loopback bind for preview server (VG01); getPreviewUrl boundary (VG02, VG02-b LivePreviewServer:155); agent_data entity-name sanitization (IG01). | VG01, VG02, IG01 | Same containment form, network- and store-facing consumers. |
| **P2c — Credential & consent + TrustState** | Typed GitService.clone with token param (SG04); NotificationStore scrub pass (SG16); FlowGate for scheduled commands — gate at schedule time AND run time (IG02); per-project TrustState (default untrusted, persisted post-P0 in the projects index) consumed via ONE shared request choke point at scheduler/launch/MCP/connector/tool-API surfaces (F03 design input). CH03 card gets a "Trust this project" quick-action. | SG04, SG16, IG02 (+ IG15 consent, CH03 card) | Trust ≠ authentication: TP02's auth fix already shipped; TrustState decides what an authenticated caller may do unattended. |
| **P3a — S01 typed-result design** | One typed-result pattern, TP03 typed ProtoResult first (upstream seam), then the remaining S01 members: EX01, EX02, EX06, SR03, SR04, SG05, IG05, RG02, OG02, XG05, IC04, PR01/PR03, TG01 fix-or-delete. | S01 family (~16) | SR03/SR04 have their own Replace All implementations — the typed-result design must specifically cover both write paths (sidebar ExplorerPane + modal ProjectFileSearchPanel). |
| **P3b — PLAN A canonical store** | Per-file canonical store keyed by canonical path for line-highlights, squiggles, markers; collapses the path-dialect family (CH02, DG02, problems-jump, tab identity). NOTE: CH02's host↔guest boundary translation is its own fix (recommended option (b): one AgentTools translation choke point shared with ScmState), NOT covered by PLAN A as scoped. | G03, CH02, DG02 + path-identity family | MASTER-CONNECTIONS.md §2: containment + dialect translation + key canonicalization = one root problem, PLAN A the unifier. |
| **P3c — Polling → StateFlow** | One pass over fixed-cadence polls. | PG02, PG04/TP08, PG05 | |
| **P3d — Delete-the-duplicates** | Remove duplicate/orphaned implementations (parallel-safe anytime; shrinks what P4 must reason about). | VG04, VG10, IG06, IG03, IG04 | **SHIPPED 2026-09-25 (d6669fc, CI #2926 GREEN, first-push clean).** IG06 ruling: orphaned SSHJ+TOFU stack deleted; the live trust store (rootfs known_hosts) gained its missing visible surface in SshManagerSheet (ssh-keygen -F / -R). IG04 ruling: removal, not routing — the only real downloader (rootfs fetch) needs Range-resume, richer than the engine. **P-SERIES P0→P3d NOW COMPLETE.** |
| **P4 — Everything else by group** | Remaining rows batched per group, each batch revertable. Verified scan 2026-09-25 (supersedes plan-time 54/114/62 estimate): ~190 rows — HIGH 22 (+ TB02, OG01 high-tier), MEDIUM 98, LOW 61, CRITICAL/TOP-class 4 (TP01 resolved-trail, TP02 shipped-pending-P5, +2 prose matches), enablers 2, unclassified 3. | remaining gaps Includes SK04 (PIN lock) batching with any future app-level security work. |
| **P5 — Verification round** | All pending device checks per group (BI/OB/TE/IMC/TT/TC/SN/PM + prior batches), CI green per commit, same rules as the audit. **TP02's batched device verification lands HERE (owner's full test pass).** | verifies all | TP02 only reaches "closed" after this pass. |

## Standing decisions recorded before this plan

- TP01: resolved 2026-09-24 (removal, `57236a0`, post-redeploy probe confirmed) — kept top-tier for audit trail.
- TP02: shipped device-unconfirmed (see status block above). Backlog question logged, NOT in any phase: stdio-vs-TCP for local AgentApiServer (VS Code uses stdio pipes locally; HTTP + OAuth + per-tool-confirm for remote).
- Forward-coverage backlog decisions (owner, not blocking): F01 Notebooks, F02 remote-dev model, F09 tree-sitter. F04-F08 + thin/minor list ruled non-goals.
- P1 must land BEFORE P2a's containment utility touches file-write paths? No — P1 is editor/chat write chains; P2a is archive extraction. They are independent; the order above is the owner-approved sequence.
- ROADMAP CONTINUITY: every AGENTS.md changelog entry lists all pending items.
