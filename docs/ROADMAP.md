# VN Code — Master Roadmap
Updated: 2026-10-28. Status legend: DONE / IN PROGRESS / PENDING / PARKED.
Each DONE row carries commit + CI run. PASTE CHANGES in later reports.

## Chat rebuild (A / WP1-WP9)
Updated 2026-10-28 after WP9(a). Each DONE row carries commit + CI run.

| Package | Status | Commit / Run |
|---|---|---|
| A(a) token layer (single source + Compose + CSS) | DONE | 228350e/8df9ed9 GREEN 3377 |
| A(a) component library + ChatListRenderer interface | DONE | 4e535b7 GREEN 3378 |
| A(a) design gallery (dev-only, DEBUG-gated) | DONE | f69dc73 GREEN 3379 |
| A(b) ProviderAdapter seam + OpenAIAdapter | DONE | f1ba20c GREEN 3381 |
| A(b) GeminiAdapter (bounded thinking, thought-part filter) | DONE | 2bd5b92 + a7d4c33 GREEN 3383 |
| WP1 (c) read-only key/endpoint migration | DONE | d6bf9e8 GREEN 3386 -> b90fc0e GREEN 3388 (CHECKPOINT 1) |
| WP2 (d-min) panel on engine, Gemini first | DONE | b90fc0e GREEN 3388 |
| WP3 providers (Anthropic native + OpenAI-compatible presets, reasoning defaults) | DONE | 8b590ef GREEN 3390 / 7e1f489 GREEN 3394 / dbbdbaa GREEN 3396 |
| WP4 custom endpoints (per-endpoint keys, live model list, CRUD) | DONE | 3397 series GREEN 3397 |
| WP5 model catalog + capability data (context-window budget) | DONE | f21a5b1 GREEN 3399 |
| WP6 multi-key pool + opt-in failover + manual switch | DONE | e653fe5 GREEN 3401 |
| WP7 Settings + storage cleanup + capability overrides + model persistence | DONE | 4b268d8 + fix 2aa3eca GREEN 3403 |
| WP8 (e) attachment pipeline (picker, 10 MB cap, staging, redaction) | DONE | a79c8e7 + fixes 46ee083/13244a2 GREEN 3406 |
| WP9 (a) text/context attachments + credential redaction gate | DONE | 74fe423 + fix 5532eb7 GREEN 3409 |
| CHECKPOINT 2 (install target + batch sheet WP1-WP9a) | READY | docs/checkpoint-2-sheet.md |
| WP9 (b) P0 residuals: sessions/history roster, modes, tool loop, @participants//slash | PENDING | see docs/chat-p0-gaps.md |
| Read-only docs: rebuild-plan.md v2, ui-research.md | PENDING | - |

### Chat PENDING list (continuity — every open item)
- Sessions/history roster + restore (P0 residual; needs storage format + sessions_v2 migration).
- Ask/Edit/Agent modes + tool loop with confirmations (P0 residual; after Explorer P1-P6).
- @participants + /slash autocomplete (P1).
- #selection / #problems / terminal-output attach sub-kinds (P1).
- rebuild-plan.md v2 and ui-research.md (read-only docs, owner review).
- Device round: docs/checkpoint-2-sheet.md (ONE round for WP1-WP9a, per batching rule).

## Explorer/editor (advisor rulings 2026-10-28; P1+ starts AFTER CHECKPOINT 1)
| Phase | Status |
|---|---|
| P1 editor registry (behavior-identical) + Utf16 long-press residual fix (first commit) | PENDING |
| P2 Open With... + per-extension user association | PENDING |
| P3 binary read-only BANNER (no dialog) — "Binary content — read-only", Hex view / Enable editing | PENDING |
| P4 2 MB truncated read-only preview + "Load full file" (measured cap) | PENDING |
| P5 markdown preview | PENDING |
| P6 third-party viewer registration | PENDING |
| Source Control: Initialize-repository offer; Generate-commit-message returns with chat | PENDING |

## PARKED (never propose in a group batch; each needs its own go-decision)
- Link-tap work (Bug 2, D1-D3, redaction/rename checks)
- backup/restore credential-path hazard (restore over a working container loses .ssh/.gitconfig/.aws etc.)
- auto-backup design (docs/automatic-container-backup-design.md)
- U4 isolated-process PDF worker (PDF extraction being rebuilt in WP8)
- rea inert review before any extension
- mobile preview own build (lirobi notes)
- custom headers security review (WP4 dependency: near-credential values, redaction, Authorization collision)
- tool-registry source of truth
- S1-c warm-path re-enable (owner mobile-data)
- XG01-04 extension-architecture enablers (owner ruling 2026-09-26)
- docs: rebuild-plan.md v2 + ui-research.md (queued read-only)
