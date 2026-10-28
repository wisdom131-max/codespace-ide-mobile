# CHECKPOINT 2 — device round sheet (WP1-WP9a)

ONE install round covering the whole chat rebuild (owner batching rule: no per-phase rounds).
Everything below was green in CI before this sheet was written; commits + runs are listed.

## Install target
Current main @ the commit this sheet rode in on. Build via the usual CI APK artifact.

## What to check (chat panel)

1. **Keys (WP1/WP7)** — Settings → Manage keys: your existing provider keys appear (presence
   only, values never shown). Add a pool key, replace one, delete one. Deleting a key must NOT
   touch the endpoint list.
2. **Models (WP4/WP5/WP7)** — provider dialog: model list loads from the live endpoint; select a
   model; reopen the app → the selection is still there. Blank custom endpoint key (Ollama style)
   → no Authorization header, request still goes out.
3. **Capability override (WP7)** — in the provider dialog set a context-token override for one
   model (e.g. 200000). The budget estimate should scale up immediately for that model; blank
   the field → the bundled value returns.
4. **Chat data rows (WP7)** — Clear conversation empties the transcript, keys stay. Reset chat
   settings returns failover/selection overrides to defaults, keys stay.
5. **Send + stream (WP2/WP3)** — one message per provider you have a key for: Gemini, Anthropic,
   and an OpenAI-compatible preset. Streaming text, thinking tick, Stop.
6. **Failover (WP6)** — with two pool keys and failover OPT off: a bad first key shows the error
   and stops. Turn failover ON (manual switch card appears) → the send retries on the next key
   and the InfoNote says which key was used.
7. **Attachments (WP8)** — attach a PNG and a PDF: chips show name + size, tap removes a chip,
   send → the model answers about the image. Attach a file over 10 MB → honest refusal note
   (no crash, no partial send). Retry after an error → the attachment is re-sent, not dropped.
8. **Text/context attachments (WP9a)** — attach a source file: the payload carries
   `--- attached file: name ---` + content (ask "what does this file do?"). Attach `.env` or
   `id_rsa` → refused with the credential note. Attach a mixed pair (text + PNG) → both arrive.
9. **Rotation/landscape** — panel keeps draft text + pending chips across rotation.

## Report back
For each numbered item: PASS / FAIL with the exact text of any error card. A log snippet from
the send path (no keys, no attachment contents) is enough for failures.
