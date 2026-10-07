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
- On failure: last-good cached list + timestamp STAY; the group shows an inline
  "last fetched <time> — refresh failed (<reason>)" line. Manual entries always render.
- Picker-open fetch-all behavior is UNCHANGED (no behavior surprise in the batch);
  SUSPECT: whether the open-fetch should later be dropped in favor of Refetch-only —
  separate owner question, NOT in this phase.

### C-2. Error bubbles carry endpoint identity
- ChatHttpException gains optional `endpointLabel` + `endpointUrl` + `statusClass`
  fields, populated in classifyHttpError where the response/request is in hand
  (SUSPECT pending read of ChatHttpException's current shape).
- The red bubble renders: "<Endpoint label> (<base URL>): <status class> — <vendor text>"
  for custom endpoints; built-in providers keep today's text plus the status class.
- Notification bell wording unchanged (402 branch untouched).

### C-3. AiKeysSection key-health checks stay DIRECT (READ-verified purpose)
- The Test/status calls to fetchModels are KEY-HEALTH checks, not catalog reads — they
  must keep hitting the endpoint with the tested key. No facade indirection. Documented
  here so the next audit doesn't "fix" it.

### C-4. OPEN OWNER QUESTION — built-in providers behind the facade
Phase B deferred this to the phase-C ruling. Options:
- **(a) Keep built-ins DIRECT (recommended for C):** smaller diff; facade serves customs
  only; built-ins' model lists are static curated lists today, so dedupe/merge logic buys
  them little. Cost: two code paths live on.
- **(b) Move built-ins behind ModelCatalog:** uniform path, one ordering/dedupe home,
  future cache behavior for free; cost: touches every built-in fetchModels call site,
  bigger diff, extended JVM tests — an EXPANDED batch.
- Recommendation: **(a) now**; revisit (b) in phase D/E only if a concrete need appears
  (e.g. built-ins gaining live model-list fetching). Advisor decides.

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
