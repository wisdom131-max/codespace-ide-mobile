# Chat rebuild — P0 tier audit (WP9 feature-map pass)

Source of tiers: docs/feature-map.md (VS Code Copilot Chat COMPLETE map, P0 = rebuild must have).
This audit walks every P0 row of that map against the REBUILT chat (WP1-WP9) and records
what is closed, what stays open, and which tier it moves to. Updated 2026-10-28.

## P0 rows — status against the rebuilt chat

| P0 feature | Status | Evidence / where |
|---|---|---|
| Chat view (transcript) | CLOSED | native Compose rows (ChatListRenderer.MessageList), streaming cursor, thinking tick |
| Sessions/history | OPEN — P0 residual | rebuilt chat is session-lived; roster/restore not rebuilt. Tier: next chat batch (needs storage format + migration from sessions_v2) |
| Modes Ask/Edit/Agent | OPEN — deferred | single-chat rebuild keeps agent tools out of scope; tool approval store (ChatPlanStore) still exists for the agent path. Tier: after Explorer P1-P6 |
| Model picker + Manage Models/BYOK | CLOSED | WP4 endpoints + WP5 capability catalog + WP7 model selection persistence |
| Tool picker + confirmations | OPEN — deferred | same scope ruling as Modes; approvals surface exists but the rebuilt engine has no tool loop |
| Context attachments | CLOSED for file/context kinds | WP8 images+PDF (staged, capped, redacted) + WP9(a) text files as quoted blocks with credential-name gate. Missing sub-kinds: #selection / #problems / terminal output (P1) |
| @participants, /slash | OPEN — P1 | input autocomplete; cheap follow-up batch |
| Diff review (agent edits) | EXISTS (not rebuilt) | PendingChangesStore + AiReviewStrip kept from the audit-era system; unchanged by the rebuild |
| Error/quota/rate-limit states | CLOSED | taxonomy cards: Failed / RateLimited / NoCredits / Offline / OverBudget with estimate |
| Status bar/usage (budget) | CLOSED | BudgetGuard + per-send estimate + "Send anyway?" override (WP2/WP5) |
| Key management + storage cleanup | CLOSED | WP1 migration, WP7 KeysManagerDialog (pool keys, presence-only), Clear conversation / Reset settings |

## What WP9 closes vs leaves

WP9(a) closed the text/context half of the attachments row. The remaining P0 rows
(sessions/history, modes, tool loop, @participants//slash) are NOT rebuild-blocking for
the device round: each is a distinct batch with its own design questions (storage format,
tool-loop semantics, prompt parsing). They are listed here so no row silently disappears.
