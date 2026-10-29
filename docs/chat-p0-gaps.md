# Chat rebuild — P0 tier audit (WP9 feature-map pass)

Source of tiers: docs/feature-map.md (VS Code Copilot Chat COMPLETE map, P0 = rebuild must have).
This audit walks every P0 row of that map against the REBUILT chat (WP1-WP9) and records
what is closed, what stays open, and which tier it moves to. Updated 2026-10-29 after the
batch 1-8 remediation: see docs/chat-batches-1-8-report.md for commits, CI run IDs and the
checks that remain device-only.

## P0 rows — status against the rebuilt chat

| P0 feature | Status | Evidence / where |
|---|---|---|
| Chat view (transcript) | CLOSED | native Compose rows (ChatListRenderer.MessageList), streaming cursor, thinking tick |
| Sessions/history | CLOSED for single-conversation restore (2026-10-29, `6ac4ec3`) | `ChatTranscript` + `ChatTranscriptStore`: atomic write, `.bak` recovery, interrupted replies labelled, restore never overwrites a live conversation. NO roster/picker: one conversation per project, which is what the owner authorised. A session list is still a separate future batch |
| Modes Ask/Edit/Agent | CLOSED (2026-10-29, section-6 wiring + `ad79d63`) | Ask reads only, Edit PROPOSES via `PendingChangesStore.stage()` (no disk write before acceptance, model told "nothing has been written"), Agent acts behind the approval gate; the mode chip and the request payload cannot disagree |
| Model picker + Manage Models/BYOK | CLOSED — picker wired (2026-10-29, `f698d7f`) | WP4 endpoints + WP5 catalog + WP7 persistence, now with the tested `ModelPickerSpec` behind the dialog: search, preferred vs Other Models, no invented prices, provider changes delegated to `SelectionResolver` |
| Tool picker + confirmations | CLOSED (2026-10-29, section-6 wiring) | the rebuilt engine has a tool loop again (read/write/list/search/run_command), each call behind the approval gate, with an approval card carrying full args; staged edits get their own row |
| Context attachments | CLOSED for file/context kinds | WP8 images+PDF (staged, capped, redacted) + WP9(a) text files as quoted blocks with credential-name gate. Missing sub-kinds: #selection / #problems / terminal output (P1) |
| @participants, /slash | OPEN — P1 | input autocomplete; cheap follow-up batch |
| Diff review (agent edits) | EXISTS (not rebuilt) | PendingChangesStore + AiReviewStrip kept from the audit-era system; unchanged by the rebuild |
| Error/quota/rate-limit states | CLOSED — now LIVE (2026-10-29, `c758268`) | `ChatStatusMapping` maps the taxonomy to cards and clears them on the next send; bounded 429 retries (`2c65917`) with an honest wait card and a Stop that actually stops |
| Status bar/usage (budget) | CLOSED | BudgetGuard + per-send estimate + "Send anyway?" override (WP2/WP5) |
| Key management + storage cleanup | CLOSED | WP1 migration, WP7 KeysManagerDialog (pool keys, presence-only), Clear conversation / Reset settings |

## What WP9 closes vs leaves

WP9(a) closed the text/context half of the attachments row. The remaining P0 rows
(sessions/history, modes, tool loop, @participants//slash) are NOT rebuild-blocking for
the device round: each is a distinct batch with its own design questions (storage format,
tool-loop semantics, prompt parsing). They are listed here so no row silently disappears.
