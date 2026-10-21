# G-D PLAN — model/endpoint registry (PLAN ONLY, no code, advisor 2026-10-20)

> Source-first: every reference claim below was READ from the live repos on
> 2026-10-20 (repo, file, function cited). Our-side claims are READ from the
> current tree at 0271b7b. Nothing here is code. V1, backup, container and
> link work stay parked.

## 1. What we have now (READ, our tree)

- **ChatProvider** (`chat/ChatProvider.kt`) — the one registration interface:
  `id`, `displayName`, `defaultModel`, `isLocal`, `requiresApiKey`,
  `supportsImages` (line ~89, default true), `isAvailable()`, `fetchModels()`,
  `complete()/completeStreaming()`. Registration in `ProviderBootstrap.kt`,
  lookup via `ChatProviderRegistry.byId()` (the seam the G-E gate will wrap).
- **CustomEndpointStore** (`chat/CustomEndpointStore.kt`) — endpoints_v2 JSON
  `{id, label, baseUrl}` in plain prefs, slug ids, provider id mapping
  `providerIdFor()` = `custom_<slug>` (legacy "default" → `custom`),
  `endpointsJson()`, version counter, `migrateLegacyOnce()` (the v1→v2
  migration that already shipped — the precedent G-D would follow).
- **ChatKeyPool** (`chat/ChatKeyPool.kt`) — numbered key slots per provider
  (`slotSuffix()`: "OPENAI", "OPENAI_2", …), labels JSON, manual-active-slot
  first ordering.
- **ChatModelSelection** (`chat/ChatModelSelection.kt`) — prefs string
  `KEY_SELECTED` = "provider:model" + per-mode selections + pinned list.
- **ProviderModelCache** (`chat/ProviderModelCache.kt`) — per-provider live
  model list cache with TTL (`freshModels()`).
- **ModelCatalog** (`chat/ModelCatalog.kt`) — per-endpoint manual + live model
  groups (`StoreReader` seam over CustomEndpointStore).
- **G-E capability layer** (just shipped, flag OFF) — `ModelCapabilityFlags`
  resolution override > live > bundled > provider constant; the registry's
  `supportsImages` is the FALLBACK source in that chain, so G-D must not
  remove it (it becomes source 4 permanently).

## 2. Reference survey (READ from live sources, 2026-10-20)

- **Continue** (continuedev/continue) — `core/config/profile/doLoadConfig.ts`,
  `doLoadConfig()`: config is a FILE (config.yaml primary, legacy config.json
  via `getConfigJsonPath()`; `loadContinueConfigFromYaml` /
  `loadContinueConfigFromJson`), models are list entries with
  `provider`/`model`/`apiKey`/`roles` (chat, edit, autocomplete), the ACTIVE
  selection is per-ROLE and rectified against the config at load
  (`rectifySelectedModelsFromGlobalContext`, core/config/selectedModels), and
  a legacy-shape migration runs at load time (`migrateJsonSharedConfig`).
  Lesson for us: selection survives config change by being RE-RECTIFIED
  against the registry at every load, not by being trusted.
- **Cline** (cline/cline) — `apps/vscode/src/shared/storage/state-keys.ts`
  ("SINGLE SOURCE OF TRUTH FOR STORAGE KEYS"): one flat field map split into
  `GLOBAL_STATE_FIELDS` (global) and `API_HANDLER_SETTINGS_FIELDS`
  (per-provider knobs as FLAT keys: `openAiBaseUrl`, `anthropicBaseUrl`,
  `ollamaBaseUrl`, `liteLlmBaseUrl`…), per-mode selections as ID + ModelInfo
  PAIRS (`planModeOpenRouterModelId` + `planModeOpenRouterModelInfo`); API
  keys live in SecretStorage (`ConfiguredAPIKeys = Partial<Record<ApiProvider,
  string>>`), never in globalState; the shape is code-generated
  (`scripts/generate-state-proto.mjs` → `proto/cline/state.proto`,
  `StateManager.ts`); `ModelInfo`/`ApiProvider` in `shared/api.ts`.
  Lesson: typed key registry + secrets NEVER in the state blob + selection
  pairs (id + cached info) so a stale id degrades gracefully.
- **Zed** (zed-industries/zed) — `crates/language_models/src/settings.rs`,
  `AllLanguageModelSettings`: ONE named struct per first-class provider
  (anthropic, openai, deepseek…) PLUS `HashMap<String, …Settings>` for
  `openai_compatible` / `anthropic_compatible` — N NAMED custom endpoints,
  each its own settings entry in settings.json `[language_models]`;
  `crates/language_models/src/provider/api_compatible.rs`,
  `ApiCompatibleProviderState`: API keys go to the OS KEYRING via
  `ApiKeyState` + `credentials_provider` (`set_api_key()`,
  `load_if_needed()`, env-var fallback `{PROVIDER}_API_KEY`), custom
  providers are REMOVABLE named entries (`remove_provider()` →
  `settings::update_settings_file`). Lesson: built-ins and customs share one
  read path; customs are first-class removable registry citizens; keys are
  OS-secure storage (we already match with SecureTokenStore).
- **VS Code** (microsoft/vscode) — no provider registry at all in the host:
  providers are EXTENSIONS contributing via the `languageModels` /
  `languageModelChatProviders` contribution point (package.json), models are
  addressed by `LanguageModelChatSelector {vendor, family, version}`
  (vscode.d.ts `LanguageModelChat`), per-model config has an optional JSON
  SCHEMA (`vscode.proposed.chatProvider.d.ts`: "users can specify per-model
  configuration in their language models" settings). Lesson: our
  ChatProvider/Registry already follows this architecture (that was the
  design note in ChatProvider.kt's KDoc); G-D does NOT redesign it.

## 3. What G-D actually fixes (the honest gap list, READ-based)

1. **Selection strings are parsed ad-hoc** — "provider:model" is split with
   `split(":")` at several call sites; a model id containing ":" is a
   corruption hazard; the parse is not owned by one function.
2. **Dead selections are never cleaned** — delete a custom endpoint and any
   selection/pin/model-override rows that referenced `custom_<slug>` keep
   pointing at a provider id that no longer registers (Cline solves this by
   rectifying at load; Continue does the same at config load).
3. **No tombstoned migration discipline** — v1→v2 endpoints migration exists
   but is bespoke; selections/mode-selections/pins/overrides have NO
   migration path if their shape ever changes.
4. **Mixed registry enumeration** — Settings and the picker each merge
   built-ins + customs themselves instead of asking the registry for ONE
   ordered list.
5. **No per-endpoint extra headers** (Cline `openAiHeaders`, Zed custom
   headers) — several OpenAI-compatible gateways need auth headers that are
   NOT the api-key. (Flagged, not necessarily G-D scope — could be its own
   small phase.)

## 4. Phases (each phase = one CI run, own owner go)

- **G-D-a — selection model, rectify-at-load (SMALL, low risk).** One
  typed `Selection` (providerId, modelId) with ONE parse/format owner
  (`ChatModelSelection.parse/format`), plus a RECTIFY step: on registry
  change (version counter already exists) or load, a selection pointing at
  an unregistered provider falls back to provider default + user-visible
  note (Continue `rectifySelectedModelsFromGlobalContext` precedent).
  MIGRATION: none needed — the stored strings keep their shape; only readers
  change. ROLLBACK: revert = old ad-hoc parses still work on the same
  strings. REMOVES: ad-hoc `split(":")` at call sites (list compiled before
  the phase). TESTS: parse/format round-trip incl. colon-in-model-id,
  rectify-on-dead-provider, per-mode selections rectified, pins filtered.
- **G-D-b — orphan cleanup on endpoint delete (SMALL, low risk).** Deleting
  a custom endpoint drops (and logs) its references: selection strings,
  mode selections, pinned entries, ProviderModelCache rows,
  ModelCapabilityOverrides pairs. MIGRATION: a one-time sweep of the SAME
  rule over existing data (ids whose `custom_` prefix has no endpoint) —
  dry-run logged before delete. ROLLBACK: sweep only deletes what the
  registry says is dead; pre-sweep backup copy of the four prefs keys is
  written once (plain prefs are not secrets). TESTS: sweep with seeded
  orphans, delete-endpoint flow, backup/restore of the keys.
- **G-D-c — one ordered enumeration (SMALL, low risk).** Settings + picker
  ask the registry for one ordered list (built-ins then customs, each with
  label/availability); no call site merges lists itself. REMOVES: the
  per-callsite merge loops. TESTS: registry-order fixtures, availability
  flags, version-bump re-enumeration.
- **G-D-d (parked, own decision) — per-endpoint custom headers** (Cline/Zed
  precedent): header name/value pairs per endpoint, stored in the
  endpoints_v2 JSON, applied by OpenAiCompatibleTransport only. Security
  review needed (header values are near-credentials; SecureTokenStore vs
  prefs decision is part of the phase).

Sizes: a+b+c each ~= a dozen files-touched-or-fewer, all JVM-testable, no
device-risk surface (pure data + enumeration), one CI run each. Combined
risk: LOW, but each still gets its own go per standing rules.

## 5. Explicitly NOT in G-D

No UI redesign, no registry persistence redesign (endpoints_v2 shape stays),
no key storage change (SecureTokenStore stays the ONLY secret primitive),
no G-E interaction (flag stays OFF and untouched), no V1/backup/container/
link work.

## 6. Migration discipline (advisor requirements, 2026-10-21 — PLAN ONLY)

Stored shapes this plan is written against (READ from current code, not
assumed): selections live in the `chat_model_selection` SharedPreferences
file as FLAT "provider:model" strings (global key + per-mode
`selected_model_<MODE>` keys, ChatModelSelection.kt:21); endpoints live in
the `custom_endpoint` file under the `endpoints_v2` key as a JSONArray
(CustomEndpointStore.kt:59); live model cache in `provider_model_cache`
under `<providerId>.models` (ProviderModelCache.kt:71); KEYS live in
SecureTokenStore slots via ChatKeyPool — G-D NEVER writes those.

**(a) What I am removing — the honest list.** G-D-a/b/c remove NO stored
key and NO file format. Replaced (not deleted): every ad-hoc
`split(":")`/`substringBefore(":")` parse call-site becomes a typed
Selection parse; every per-call-site registry enumeration becomes ONE
ordered enumeration. The only data ever DELETED is derived dead data:
selection strings whose provider prefix no longer exists (G-D-b, after the
dry-run report). SecureTokenStore, endpoints_v2, provider_model_cache:
never written by any G-D phase.

**(b) Exact migration steps per phase (old key/format -> new, idempotence,
versioning).**
- G-D-a (typed selection + rectify-at-load): NO migration at all — the
  stored format IS the input format (flat strings, unchanged). Load parses
  "provider:model" into a typed Selection and rectifies against the
  registry in memory; nothing is written back. Idempotent by construction
  (pure read transform); versioning unnecessary (no format change).
- G-D-b (orphan cleanup): step 0 BACKUP (see (d)); step 1 dry-run sweep
  computes orphans (selection prefix not in registry + custom endpoints,
  AUTO excluded) and LOGS the list — no deletion on the first run; step 2
  on the NEXT run (or same run behind a written "sweep_applied_v1" marker
  key in `chat_migration_backups`) deletes exactly the logged orphans, one
  prefs.apply per key. Idempotent: a re-run finds zero orphans and writes
  nothing; the marker makes double-application impossible.

  ORPHAN DEFINITION (advisor ruling 2026-10-21, LOCKED): an orphan is a
  selection whose provider id DOES NOT EXIST AT ALL — not in the static
  registry AND not among custom endpoints (any state). DISABLED endpoints
  and endpoints with EMPTY key slots are NOT orphans: their selections are
  NEVER swept, exactly as live endpoints'. The sweep's existence check is
  by id only; it never consults enabled/removed-on-device/key state.
- G-D-c: pure code, zero migration.

**(c) Rollback path.** All phases are code-only/additive except G-D-b's
orphan deletion, which is: (1) preceded by the key-by-key backup copy, (2)
restorable by a dedicated restore function (tested), (3) revertable by
plain commit revert since no other stored shape moved. The AUTO sentinel
and every live selection are untouched by construction.

**(d) Failed/interrupted migration safety.** BEFORE the first mutation of
any kind, every key/value of `chat_model_selection` is copied into a
separate `chat_migration_backups` prefs file (JSON snapshot per key, with
timestamp), KEPT until a later version (advisor rule — no auto-expiry).
Every step (backup written, sweep computed, N deleted, restore available)
is logged to the debug channel with counts. An interruption at ANY point
leaves: endpoints untouched (never written), keys untouched (SecureTokenStore
has no G-D write path at all), and the selection either the original
(no deletion happened yet) or byte-restoreable from the backup. Deletion is
the LAST step and applies one key at a time.

**(e) Tests.** Old-format fixtures built from the CURRENT code's real
stored shapes, not guesses: fixture A = `chat_model_selection` with global
+ `selected_model_ASK/AGENT/PLAN` literal strings incl. one orphan
("goneep:modelx") and the AUTO sentinel; fixture B = `endpoints_v2` array
exactly as today's writer emits it; fixture C = `<providerId>.models`
cache entry. Tests: parse->rectify roundtrip preserves every live
selection; the sweep finds EXACTLY the orphan; backup->restore is
byte-identical; interrupted-migration simulation (throw between steps)
proves endpoints/keys/selection unchanged; marker-key double-run is a no-op.

**G-D-d (per-endpoint custom headers) — what a security review must decide.**
Header VALUES are near-credentials (org tokens, "X-Api-Key"-class), so it
stays parked until: (1) storage ruling — SecureTokenStore vs plaintext
prefs (today's MK fields are plaintext; headers would be the first
secret-ish thing outside SecureTokenStore if kept plaintext); (2) redaction
discipline in every log/error/diagnostic path (the 402/429 error bubbles
echo request config today — headers must never join them); (3) Authorization
collision rule — can a custom header override the key-slot's Authorization
injection (double-header hazard) or is that combination refused; (4) export/
diagnostic-state exclusion; (5) per-endpoint header count/size caps for
poison-safety like the other stores.
