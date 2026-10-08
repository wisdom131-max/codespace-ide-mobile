# G PHASE C PLAN: AI-Keys / Endpoint UI Pass (MK restructure, phase C of B–E)

**Status:** PLAN — presented to the owner for approval before any build (advisor ruling 2026-10-07).
**Context:** phase B shipped the ModelCatalog facade (185aacc, CI green) — shared merge/dedupe for
CUSTOM endpoints, typed EndpointModels, cache-preserving refreshLive (built + JVM-tested, UNWIRED).
Phase C is the UI pass that makes the facade visible and wires the deferred scope.

---

## 0. What already exists (READ-verified — phase C is wiring, not a rebuild)

- **Endpoint registry:** CustomEndpointStore is already the CRUD registry the locked MK
  plan called for — add/update/delete, per-endpoint manualModels, `providerIdFor(id) =
  "custom_<slug>"`, per-endpoint live-model cache + fetchedAt.
- **Per-endpoint key slots:** ChatKeyPool is keyed by providerId, and every endpoint IS
  its own provider instance — so the "one shared ai_CUSTOM key" problem from the
  2026-09-16 audit is already solved at the data layer. Phase C does NOT touch key storage.
- **CRUD UI:** AiKeysSection hosts CustomEndpointsSection (MK-v2 comments, Settings).
- **Facade:** ModelCatalog.catalog()/groupEntries() (+ 11 JVM tests); refreshLive returns
  typed Success/Failed(cachedModels, cachedAt) and rethrows CancellationException.
- **Picker:** custom groups are built from catalog()/groupEntries() since phase B;
  picker-open still fetches live models per endpoint (unchanged in B).
- **Error path:** ChatHttpException caught at CopilotChatPanelOverlay (~1400) with the
  402 out-of-credits branch + notification bell; classifyHttpError in
  OpenAiCompatibleTransport builds the message.

---

## 1. What phase C ships

### C-1. Per-group targeted Refetch (wires the phase-B API)
- Each custom-endpoint GROUP in the model picker gains a Refetch action → calls
  `ModelCatalog.refreshLive(endpointId)` (cache-preserving) and re-renders just that group.
- **Advisor condition 2b:** a refresh IN FLIGHT ignores repeat taps and shows an
  in-progress state ("Refreshing…" row + disabled action) until it settles — one
  tap, one network call.
- On failure: last-good cached list + timestamp STAY; the group shows an inline
  "last fetched <time> — refresh failed (<reason>)" line. Manual entries always render.
- **Finding 2c (READ-verified):** the fetch-all does NOT run per picker open — it runs
  ONCE PER PANEL MOUNT (LaunchedEffect at CopilotChatPanelOverlay ~1127 →
  fetchLiveModelEntries over EVERY available provider, key-failover per provider).
  Cost: every chat-panel open re-fetches every provider = the mobile-data hit.
  PROPOSAL (own item, C-1b): a short per-provider cache window — persist per-provider
  fetchedAt + last-good list; the mount-fetch SKIPS providers fetched within the
  window (default 10 min) and reuses their cached lists; Refetch (C-1) bypasses the
  window; a "last fetched <t>" line makes staleness visible so real changes are not
  hidden. Applies uniformly to built-ins AND customs. Real changes are one Refetch
  tap away at all times.

### C-2. Error bubbles carry endpoint identity
- ChatHttpException gains optional `endpointLabel` + `endpointUrl` + `statusCode`
  fields, populated in classifyHttpError where the response/request is in hand
  (SUSPECT pending read of ChatHttpException's current shape).
- The red bubble renders: "<Endpoint label> (<base URL>): <code + reason line, e.g.
  429 Too Many Requests> — <vendor text>" for custom endpoints; built-in providers
  keep today's text plus the exact code.
- **Advisor condition 2a:** the displayed base URL is SANITIZED — query strings and
  user-info stripped (render scheme://host[:port]/path only, via Uri parsing, never
  string surgery), because bubbles persist into session blobs and backups; and the
  SAME C12 token-pattern redaction (sk-, AKIA, hf_, AIza, xox, eyJ, BEGIN PRIVATE KEY
  blocks, ghp_) is applied to bubble text before it is rendered or persisted.
- The EXACT status code is shown (advisor 2a), not only the class.
- Notification bell wording unchanged (402 branch untouched).

### C-3. AiKeysSection key-health checks stay DIRECT (READ-verified purpose)
- The Test/status calls to fetchModels are KEY-HEALTH checks, not catalog reads — they
  must keep hitting the endpoint with the tested key. No facade indirection. Documented
  here so the next audit doesn't "fix" it.

### C-4. OPEN OWNER QUESTION — built-in providers behind the facade (VERIFIED, premise corrected)
My earlier premise ("built-ins have static curated lists") was WRONG — the advisor's
9/6 recollection is correct. READ-VERIFIED: every built-in overrides fetchModels with a
LIVE list fetch — AnthropicProvider:170, GeminiProvider:217, OpenAiProvider:32,
DeepSeekProvider:32, OpenRouterProvider:48, XaiProvider:35 — and the panel-mount
fetch-all loop (fetchLiveModelEntries, CopilotChatPanelOverlay:431/1127) calls
provider.fetchModels for EVERY available provider with key failover, added as the
"404-fix" (stale hardcoded IDs 404ing). SUSPECT (build-time read): built-in results are
NOT cached anywhere today — every panel mount refetches them all.
Re-presented options:
- **(a) Keep built-ins DIRECT (still recommended for phase C):** the acute cost of
  (a) — per-mount refetch of every provider — is fixed by the C-1b cache window, which
  sits at the fetch-all loop and covers built-ins WITHOUT moving them behind the
  facade. Smaller diff; two code paths live on.
- **(b) Move built-ins behind ModelCatalog:** NOW has real value, not negligible —
  uniform live-fetch + cache + dedupe + per-group Refetch for every provider, one
  ordering home. Cost: touches ChatProviderRegistry wiring, the fetch-all call sites,
  the picker's built-in list source; bigger diff, extended JVM tests, EXPANDED batch.
- Recommendation: **(a) for phase C + ship C-1b** so built-ins get the data savings
  immediately; fold (b) into phase D/E only if per-group Refetch for built-ins is
  wanted in the picker.
- **RESOLVED by advisor 2026-10-08: (a) + C-1b, approved with conditions — owner
  confirmation of C-4 still gates the build.** Conditions (all locked into the
  build spec):
  - **(i) Single cache per provider:** customs REUSE CustomEndpointStore's existing
    live cache + fetchedAt; a NEW persisted cache is added ONLY for built-ins
    (same prefs pattern). No duplicate cache for the same endpoint.
  - **(ii) Invalidation triggers:** a provider's cache drops when its KEY changes
    (ChatKeyPool add/remove/setActive for that provider), when an endpoint's BASE
    URL changes (CustomEndpointStore.update), or when the ENDPOINT IS DELETED
    (CustomEndpointStore.delete).
  - **(iii) 404 / model-not-found auto-drop:** a model-not-found or 404 response on a
    chat request drops that provider's cache (the stale-ID class that started the
    9/6 work).
  - **(iv) A FAILED FETCH NEVER ADVANCES fetchedAt** — only a successful fetch stamps
    the time (a failed fetch keeps the previous timestamp so the window reflects
    last-good, matching refreshLive's cache-preserving semantics).
  - **Observability for the device round:** every cache-skip ("cache hit, within
    window: <provider>, age <t>") and invalidation ("cache dropped: <provider>,
    <trigger>") logs to the debug output channel so the owner can VERIFY the window
    behavior on device without network tricks.
  - **Commit structure:** separate revertable commits — C-1 (Refetch + in-flight
    guard), C-1b (cache window + invalidations), C-2 (error bubble) — each green
    on CI before the next.

---

## 2. Removal list, tags, rollback

- **Removed:** nothing. C-1 and C-2 are additive wiring; C-3 is a documented non-change;
  C-4 is a ruling, not code.
- **READ:** registry/key-pool/CRUD-UI/facade/picker/error-catch existence (verified above).
- **SUSPECT:** ChatHttpException field shape (C-2 build starts by reading it); the picker
  group action surface for the Refetch affordance; whether fetchedAt formatting needs a
  shared helper.
- **Rollback:** each item is its own revertable commit; facade and store untouched; no
  storage migrations; no data written that older code cannot ignore.

## 3. Device checks (ride round 2 step (a) — NON-DESTRUCTIVE, key-dependent)

1. Picker custom groups identical to phase B (lists, labels, manual/live split,
   fetched-at lines) — no regression from Refetch wiring.
2. Refetch on one group refreshes ONLY that group; with the key severed (airplane-mode
   quick toggle or a bad key on a scratch endpoint), the group keeps last-good lists +
   the inline failure line with reason.
3. A custom-endpoint error bubble shows endpoint label + base URL + status class
   (scratch endpoint with a key that 401s is enough).
4. Key-dependent by nature — these MUST run in round 2 step (a), BEFORE the E17
   uninstall/reinstall round-trip (a full uninstall wipes saved API keys by design).
