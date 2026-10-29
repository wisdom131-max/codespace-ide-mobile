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
| U3 composer rebuilt to the video (single card #3C3C3C, toolbar row, send cluster, keys moved to Settings) | DONE | 4e62781 GREEN 3432 |
| U4 modes wired Ask/Edit/Agent (provider system slot; per-provider payloads) | DONE | 085e577 + fix 855b371 GREEN 3437 |
| U5 composer polish (plus -> own row, mic removed, blue-on-text border, tighter chips) | DONE | d573aef GREEN 3438 |
| U6-a tool-wire decision layer (pure: parse, mode rules, round cap, result turn) | DONE | 31259f4 GREEN 3439 |
| U6-b chat panel ACTS behind the existing approval gate (runTools + approval card) | DONE | ce98d03 + fix ebf8b9a GREEN 3441 |
| U7 composer proportions + height (Add Context, gear, no approvals row, bounded field) | DONE | 7a9880d + fix b5cc8ec GREEN 3443 |
| U8 compact composer pass (ring, card top pad, chips hug text, glyph 28, row 40dp, field max) | DONE | 091b192 GREEN 3444 |
| U6-c agent commands visible in the terminal (real record strip: command, status, duration, output) | DONE | 46d1b61 + fix bc969c8 GREEN 3447 |
| U6-d Explorer refresh + reveal on agent file writes (ancestor expansion, reveal-only) | DONE | 457a84f GREEN 3448 |
| U6-e FILES CHANGED row (journal with before/after, real line diff, Keep/Undo, drift-safe revert) | DONE | ca5f811 + fix 95aefe5 GREEN 3450 |
| U6-f VISIBLE APPROVAL row (existing gate, Allow once/session/always, dual-click + expiry safe) | DONE | 8e4d185 + fix d384d60 GREEN 3452 |
| Read-only docs: rebuild-plan.md v2, ui-research.md | PENDING | - |

### Composer PENDING list (continuity — every open item, owner spec 2026-10-29)
- Composer height is now ~24dp shorter (138 -> ~114); if the owner still wants less, the
  remaining terms are the 40dp control row and the 22dp field floor - both near the floor
  of "still tappable / still readable", so the next cut would have to come from the
  Add Context row being moved INTO the control row rather than sitting above the field.
- Device round for U5+U6-a..f together (batching rule, ONE round): composer height and Add
  Context proportions, gear, tool row position, multiline growth, the approvals row now being
  VISIBLE again (U6-f), the Files Changed row's Keep/Undo on a real multi-file write (U6-e),
  the terminal agent strip after a run_command (U6-c), and Explorer reveal into a deep closed
  folder (U6-d).
- Model picker popup with search (currently reuses the provider dialog).
- Text glyphs still stand in for vector icons across the composer/approval rows.
- Edit strip is NOT wired to PendingChangesStore; the chat path uses AgentWriteJournal
  (direct-to-disk writes) while the staged overlay flow remains a separate, unwired surface.
- Header history / new-chat controls.
- Robolectric dependency still missing, so SharedPreferences-backed stores stay untested on JVM.

### Chat PENDING list (continuity — every open item)
- Sessions/history roster + restore (P0 residual; needs storage format + sessions_v2 migration).
- Ask/Edit/Agent modes + tool loop with confirmations: MODES DONE (085e577), TOOL LOOP DONE
  (ce98d03), and agent actions are now VISIBLE end to end: terminal record (U6-c 46d1b61),
  Explorer reveal (U6-d 457a84f), Files Changed with Keep/Undo (U6-e ca5f811), visible
  approval row (U6-f 8e4d185). What remains in this area is device verification only.

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
