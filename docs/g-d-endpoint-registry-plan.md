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
