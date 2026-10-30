# Provider, endpoint, credential and model pipeline: three-way audit

Date: 2026-10-29
Status: AUDIT ONLY. No implementation changes were made or committed while producing this
report. The working tree still carries the nine uncommitted files listed in section 7.

Revisions inspected:

| Source | Revision | How verified |
| --- | --- | --- |
| Current VN Code | `77d4c5b` (main, HEAD) plus uncommitted working tree | `git log`, `git status --porcelain`, `git diff` in this session |
| VN Code archive | `ab5dc6c` "Chat + provider layer reference snapshot (2026-10-26)" | `git log` in `archive/vn-code-archive`, remote `github.com/wisdom131-max/vn-code-archive` |
| VS Code core | `main` branch of `github.com/microsoft/vscode` (`src/vscode-dts/vscode.d.ts`, `src/vscode-dts/vscode.proposed.chatProvider.d.ts`) | files fetched and read in this session |
| VS Code docs | `code.visualstudio.com/docs/copilot/customization/language-models` | page text fetched and quoted in this session |

Claims in this report are labelled:

* **[V]** verified in source code, with path and line.
* **[T]** confirmed by a test that exists and asserts the behaviour.
* **[D]** documentation statement, not verified against source.
* **[H]** hypothesis or inference that still needs a test.

---

## 1. Executive summary

The searchable model picker (commit `f698d7f`, green in `0aad66f`) is real but it was only
ever the *selection UI*. It sits on top of a pipeline that cannot carry a custom endpoint
from the settings form to the wire. Three defects explain every symptom reported so far,
and all three are independent of the picker:

1. **Chat URL construction [V]**: `OpenAIAdapter.endpoint()` returns `config.baseUrl`
   untouched (`OpenAIAdapter.kt:12` at `77d4c5b`), while the built-in presets store a
   *complete* path (`ProviderPresets.kt:48,57,66,75,84,93,102`). The custom endpoint form
   asks for a *base* URL (`CustomEndpointEditor.kt`, label "Base URL (OpenAI-compatible)").
   A correctly filled base URL therefore POSTs to `.../v1` and is rejected.
2. **Discovery URL construction [V]**: `ModelListFetcher.fetch()` appends `/models` to the
   same stored string (`ModelListFetcher.kt:54` at `77d4c5b`). One string cannot satisfy
   both halves: the base form makes discovery work and chat fail; the full-path form makes
   chat work and discovery fail (it requests `.../chat/completions/models`).
3. **No authoring surface for model ids [V]**: the endpoint form had exactly three fields
   (name, base URL, API key). The store already supported many ids per endpoint
   (`CustomEndpointStore.kt:241-300`), but the only way to populate them was "Test
   connection" on an *already saved* endpoint, which **replaced** the whole list
   (`store.setManualModels(existing.id, r.ids.joinToString("\n"))`, pre-diff editor code).
   So "one endpoint, many model ids" existed in the data layer and not in the product.

Also verified: the selected model id **does** reach the request body
(`ChatSession.kt:316-329` builds `EngineConfig(modelId = sel.modelId, ...)`;
`OpenAIAdapter.payload()` serialises `"model":"<id>"`), so this is not a "label only"
problem. The problem is the URL contract, the authoring surface, and two smaller
robustness gaps (endpoint edit invalidates the saved selection; the `live` model list has
no writer in the rebuild).

---

## 2. Current VN Code: full trace from form to HTTP request

### 2.1 Endpoint creation and editing [V]

* Form: `chat/panel/CustomEndpointEditor.kt`, `EndpointFormDialog` (line ~164 onward).
  Fields at `77d4c5b`: `label`, `baseUrl`, `apiKey` (masked, `PasswordVisualTransformation`).
* Save path: `store.add(label, baseUrl)` or `store.update(id, label, baseUrl)`, then
  `tokenStore.setAiKey(SlotNaming.customStoreKey(provider), apiKey.ifBlank { null })`.
* Provider identity: `CustomEndpointStore.providerIdFor(id)` (`CustomEndpointStore.kt:158`)
  returns `"custom"` for the legacy endpoint and `"custom_" + id` otherwise;
  `endpointIdForProvider()` is the inverse (`:160-164`).

### 2.2 Endpoint data model and persistence [V]

* `CustomEndpointStore.Endpoint(id, label, baseUrl, enabled)` (`:60-66`).
* Storage seam: `interface EndpointStorage` (`:73-115`) with a prefs implementation and an
  in-memory implementation used by JVM tests. Failure of a write is observable, and the
  form reports "Could not save this endpoint" instead of closing.
* Per-endpoint model registry: `manualModels(id)` (`:241`), `setManualModels(id, raw)`
  (`:250`), `addManualModel` (`:256`), `removeManualModel` (`:264`), `liveModels(id)`
  (`:271`), `liveModelsFetchedAt` (`:276`), `clearLiveModels` (`:281`),
  `setLiveModels` (`:287`).
* **The `live_*` rows have no writer anywhere in the app.** `setLiveModels` /
  `clearLiveModels` are declared in the store and never called outside it (verified by
  grep over `app/src/main` and `engine/src/main`). Discovery results are instead written
  into the *manual* list by the editor button.

### 2.3 Credential storage [V]

* Slots: `SlotNaming.primarySlot(pid)` = `"ai_" + pid.uppercase()` (`keys/SlotNaming.kt:37`),
  `customStoreKey(pid)` = `pid.uppercase()` (`:52`), consumed by
  `tokenStore.setAiKey(...)`/`aiKey(...)` which take the part after `ai_`.
* Read path: `StoredCredentialsReader.keyValue(slotName) = store.aiSlotValue(slotName)`
  (`keys/StoredCredentialsReader.kt`), documented as the only place a key value surfaces.
* Isolation: keys are addressed by provider id, so `custom_alpha` and `custom_beta` cannot
  read each other's key **[V, by construction]**. No test asserts it **[H untested]**.
* The key is never logged in this path; the only logging in the reader is slot counts.

### 2.4 Base URL storage and normalisation [V]

* Stored verbatim, trimmed by the form (`baseUrl.trim()` in `onSaved`), no normaliser
  existed at `77d4c5b`. There is no `EndpointUrls`-equivalent in the engine at HEAD.
* Presets and custom endpoints therefore use **two different contracts** for the same
  field (full path vs base), and nothing reconciles them.

### 2.5 Protocol selection [V]

* There is **no** per-endpoint protocol field. `ChatSession.adapterFor(providerId)`
  (`ChatSession.kt:191-194`) picks `GeminiAdapter` for `"gemini"`, `AnthropicAdapter` for
  `"anthropic"`, else `OpenAIAdapter`. Every `custom_*` provider is therefore
  OpenAI-shaped, which is correct today only because the form says "OpenAI-compatible".

### 2.6 Model discovery and parsing [V]

* `ModelListFetcher.fetch(baseUrl, apiKey)`: `val url = baseUrl.trimEnd('/') + "/models"`
  (`ModelListFetcher.kt:54`), `Authorization: Bearer <key>` only when a key is present,
  15 s connect/read timeouts, `FetchResult.Models(ids)` or `FetchResult.Failed(status, body)`.
* Parsing: `parseModelsJson` reads `data[].id` (`:24-36`), OpenAI shape only.
* `verdictOf` maps 401/403 to "key was rejected", 404 to "/models was not found".
* Discovery is never called from the request path, only from the editor's "Test
  connection" button.

### 2.7 Selection state, persistence and propagation [V]

* `PanelSelection(providerId, keySlot, modelId, baseUrl)` (`ChatSession.kt:55-59`).
* Persistence: `encodeSelection`/`decodeSelection` store providerId, keySlot, modelId,
  baseUrl (`ChatSettingsPrefs.kt:20-41`); `setSelection` (`:99`).
* Restore: `initialResolvedSelection(reader)` (`ChatPanelSetupLogic.kt:27-31`) calls
  `SelectionResolver.resolve(persisted, cfg, keySlotFor)` (`SelectionResolver.kt:99-112`).
* Apply: `onApply` in `ChatPanelScreen.kt:219-226` calls `session.select(sel)`,
  `persistSelectionIfCoherent(...)`, and updates the resolved state. So **selecting a model
  does change the real configuration**, not just the label **[V]**.
* Request assembly: `ChatSession.sendCore` builds
  `EngineConfig(modelId = sel.modelId, providerId = sel.providerId, baseUrl = sel.baseUrl,
  apiKey = key, ...)` (`ChatSession.kt:316-329`); the key comes from `keyFor(sel.keySlot)`
  (`:296`); `needsKey` defaults to **true** for providers with no preset
  (`ProviderPresets.byId(pid)?.requiresKey ?: true`), which is right for custom endpoints.

### 2.8 Request construction, auth, and response handling [V]

| Protocol | Endpoint | Auth | Model id placement |
| --- | --- | --- | --- |
| OpenAI-compatible | `baseUrl` verbatim (`OpenAIAdapter.kt:12`) | `Authorization: Bearer` unless key blank (`:16-17`) | JSON body `"model"` (`payload`, `:27`) |
| Anthropic | `baseUrl` verbatim (`AnthropicAdapter.kt:27`) | `x-api-key` + `anthropic-version` (`:29-30`) | JSON body `"model"` |
| Gemini | `base + "/v1beta/models/" + id + ":streamGenerateContent?alt=sse"` (`GeminiAdapter.kt:16-19`) | `x-goog-api-key` (`:21-22`) | **URL path** |

* Streaming: `ChatEngineImpl` calls `transport.stream(adapter.endpoint(config),
  adapter.headers(config), payload)` (`ChatEngineImpl.kt:44-50` at HEAD).
* Response: SSE via `SseParser`, deltas via `adapter.extractDelta`.
* Error mapping: `ErrorTaxonomy.classify(status, headers, body)` (`ChatError.kt:71-84`),
  `404 -> ModelNotFound(modelIdHint(body))`.

### 2.9 Answers to the specific questions asked

| Question | Answer | Evidence |
| --- | --- | --- |
| Is the displayed model the id sent to the server? | **Yes**, for OpenAI and Anthropic (body) and Gemini (path) | `ChatSession.kt:316-329`, adapter `payload()`/`endpoint()` [V] |
| Does selecting a model change the request or only the label? | **Changes the request** | `ChatPanelScreen.kt:219-226` -> `session.select` -> `EngineConfig.modelId` [V] |
| Survives leaving and reopening settings? | **Yes**, selection is persisted on apply | `ChatSettingsPrefs.kt:20-41,99` [V] |
| Survives app restart? | **Yes** if still coherent; coerced to another provider's default if not | `SelectionResolver.kt:104` [V] |
| Survives switching endpoint and back? | **Partially**: model id is remembered per provider only as "the persisted selection"; switching away overwrites it | `SelectionResolver.kt:126-132` [H, needs a test] |
| Survives switching models under one endpoint? | **Yes**, same path as above | [V] |
| Survives editing the endpoint's base URL? | **No**: `isCoherent` compares the persisted baseUrl with the current one (`:80-81`), so the selection is discarded and the resolver falls back to a provider default | [V] |

---

## 3. Verified defects and root causes

### Defect A: chat POST goes to the base URL, not to the completions path [V, verified broken]

* Code: `OpenAIAdapter.endpoint(config) = config.baseUrl` (`OpenAIAdapter.kt:12`, `77d4c5b`).
* Presets supply the full path (`ProviderPresets.kt:48,57,66,75,84,93,102`).
* The custom form asks for a base URL (`CustomEndpointEditor.kt`, label and the new hint
  text "Requests go to <base>/chat/completions").
* Result: preset providers work; a custom endpoint filled in correctly fails with
  404/405 from the provider. Only a user who pastes the *full* completions path works,
  which contradicts the field's label and the discovery path.
* Test coverage: none. `OpenAIAdapterTest.kt:31` asserts the verbatim behaviour but only
  with an input that is already a full path, so it cannot catch this.

### Defect B: discovery and chat assume incompatible URL shapes [V, verified broken]

* Code: `baseUrl.trimEnd('/') + "/models"` (`ModelListFetcher.kt:54`).
* Worked example:
  * base `https://api.groq.com/openai/v1` -> discovery `.../openai/v1/models` (200),
    chat `.../openai/v1` (404/405).
  * full `https://api.groq.com/openai/v1/chat/completions` -> chat works,
    discovery `.../chat/completions/models` (404, reported to the user as
    "/models was not found, check the base URL").
* No configuration string satisfies both, which is why the endpoint can look "connected"
  in one dialog and fail in chat.

### Defect C: multiple model ids per endpoint had no authoring path [V, verified broken]

* The store supports it (`CustomEndpointStore.kt:241-300`); the form did not expose it.
* The only path was "Test connection" then "Save N models", which (a) required
  `existing != null`, so a brand-new endpoint could not gain models at all, and
  (b) called `setManualModels`, **replacing** the list with the fetched one, so a
  dispatch over a narrower `/models` response silently deleted the user's ids.
* The picker already read the list (`ChatPanelScreen.kt:435-470`), so the feature was
  present but unreachable.

### Defect D: editing an endpoint discards the chosen model [V, verified by reading, untested]

* `isCoherent` rejects a persisted selection whose `baseUrl` differs from the endpoint's
  current one (`SelectionResolver.kt:80-81`); `resolve` then walks `cfg.withKey` and picks
  `defaultModelFor(pid)`, which for a custom endpoint is `""` (`ProviderPresets.byId`
  returns null) and is skipped, so the user lands on another provider entirely or
  `NeedsSetup`.
* Root cause: the selection stores a *copy* of the endpoint URL instead of referencing the
  endpoint, so endpoint identity and endpoint configuration are two sources of truth.

### Defect E: the `live` model group is dead in the rebuild [V]

* `setLiveModels` has no caller. All discovered ids are folded into the manual list, so
  "discovered vs manual" is not distinguishable in the UI, and there is no refresh
  timestamp logic in the rebuild. The archive had both (`ModelCatalog.refreshLive`,
  `ProviderModelCache`).

### Not a defect (verified working)

* Model id reaches the outgoing payload or path for all three protocols [V].
* Keys are per provider id and are read from the secure store at send time [V].
* Blank key omits the Authorization header (local/Ollama presets) [V, `OpenAIAdapterTest`].
* Selection persists across restarts when coherent [V].

---

## 4. Archive analysis (`ab5dc6c`)

### 4.1 How the archive stored endpoint configuration [V]

`archive/vn-code-archive/src/chat/CustomEndpointStore.kt`:

* `manual_<id>` rows, split on comma/semicolon/space/newline, de-duplicated (`:159-171`).
* `addManualModel` / `removeManualModel` (`:174-188`).
* `live_<id>` plus `live_ts_<id>` cache (`:190-205`), and a documented rule that a 404
  drops the live list so the next open refetches.
* `syncProviders()` (`:256`) re-registered one provider instance per endpoint.

### 4.2 Chat and discovery URL construction [V]

`src/chat/providers/CustomOpenAiProvider.kt`:

```
internal fun chatUrl(base: String): String {          // :70-73
    val b = base.trim().trimEnd('/')
    return if (b.endsWith("/chat/completions")) b else b + "/chat/completions"
}
internal fun modelsUrl(base: String): String {        // :76-79
    val b = base.trim().trimEnd('/')
    return if (b.endsWith("/models")) b else b + "/models"
}
```

Both are applied to the *same stored base*, so the two halves agree. This is exactly what
the rebuild lost.

### 4.3 Endpoint isolation [V]

One `CustomOpenAiProvider(endpointId)` per endpoint, registered by
`ProviderBootstrap.registerCustomEndpoints()`; provider id is
`CustomEndpointStore.providerIdFor(endpointId)`, so each endpoint has its own key slot and
its own model list. This matches what the rebuild already does through `providerIdFor` +
`SlotNaming` **[V same identity scheme]**, so the registry layer does not need restoring,
only the URL contract it enforced.

### 4.4 Multiple models, discovery and manual coexistence [V]

`src/chat/providers/CustomOpenAiProvider.kt:116-134` (`fetchModels`):

* returns `manual` immediately when there is no key or no base;
* on success writes the live cache and returns `(manual + live).distinct()`;
* on failure with manual ids present, logs the failure and **returns the manual list**;
* rethrows `CancellationException` so a cancelled compose scope is not reported as an
  endpoint error.

`src/chat/ModelCatalog.kt`:

* `mergeDistinct(manual, live)` with manual first (`:78-83`);
* `groupEntries` splits picker entries into manual vs live per endpoint (`:100-118`);
* `refreshLive` (`:126-170`): on success `Ok(merged)`; on failure `Failed(reason,
  cachedModels, cachedAt)` and **writes nothing**, so a failed refresh can never delete
  configuration.

UI: `src/ui/screens/CopilotChatPanelOverlay.kt:2289` (`removeManualModel`),
`:2296` (`addManualModel`), `:2301-2325` (per-group Refetch). The archived chat panel is
deleted in the rebuild and must not be restored; the *behaviour* is what matters.

### 4.5 Errors and diagnostics [V]

`CustomOpenAiProvider.complete()` wraps failures with the endpoint label and the exact
request URL ("MK-C rejection diagnostics"), so a 404 could be read as either a wrong model
id or a wrong endpoint. That is the same distinction Defect A makes impossible today.

### 4.6 Lost, worth adapting, do not restore

| Item | Status in rebuild | Recommendation |
| --- | --- | --- |
| `chatUrl` / `modelsUrl` | lost | **adapt** (this is the uncommitted `EndpointUrls`) |
| per-endpoint provider instances | lost, but replaced by `endpointIdForProvider` + slot naming | **not needed** |
| manual + live merge with manual first | lost (live unused) | **adapt** in the picker |
| refresh failure writes nothing | lost | **adapt** |
| endpoint label + URL in errors | lost | **adapt** (uncommitted path in 404 verdict) |
| placeholder model `"custom-model"` with `defaultModelIsPlaceholder` | lost | optional, not needed if the form now requires a model |
| archived chat panel, model groups, Refetch UI | removed | **do not restore** |

---

## 5. Official VS Code and Copilot analysis

### 5.1 The contract: a provider returns a LIST of models [V, source]

`src/vscode-dts/vscode.proposed.chatProvider.d.ts` (main):

* `interface LanguageModelChatProvider<T extends LanguageModelChatInformation>` line 176,
  with `provideLanguageModelChatInformation(options, token): ProviderResult<T[]>` line 177.
  The provider answers with an **array**: many models per provider is the core contract.
* `interface LanguageModelChatInformation` line 37, with `readonly id: string` line 118,
  `readonly isUserSelectable?: boolean` line 77, `readonly configurationSchema?` line 87,
  plus token/capability fields (`maxContextWindowTokens`, `isBYOK`, `isDefault`, ...).

### 5.2 Model identity is provider-scoped, not a bare id [V, source]

`src/vscode-dts/vscode.d.ts:20321-20346`: `LanguageModelChatSelector` carries
`vendor`, `family`, `version`, `id`. The picker resolves a model by that tuple, not by a
global id string. This corroborates the design VN Code already uses
(`providerId` + `modelId`), and warns against a flat model id space.

Related real-world evidence [D]: `microsoft/vscode#325069` reports that custom chat model
settings collide when multiple custom providers are configured because the settings were
"keyed only by the model id". Provider-scoped identity is therefore a known failure point,
not a stylistic choice.

### 5.3 Custom Endpoint provider configuration [D, official docs]

From `code.visualstudio.com/docs/copilot/customization/language-models` (fetched today):

* "Add a model provider in the Language Models editor. This configures model access, not a
  different harness." Access selection (plan / harness credentials / API key or custom
  endpoint / local model) is separate from model selection.
* The Custom Endpoint provider "replaces the deprecated OpenAI Compatible provider and
  supports additional API types"; it supports "three API types, which you can select per
  provider or per model: Chat Completions, Responses, and the Anthropic Messages API".
* Configuration is a provider object plus a `models` **array**; "the provider-level
  properties are set on the provider object, and the model-level properties are set on each
  entry of the models array. When a property is set on both levels, the model-level value
  takes precedence."
* Provider-level `baseUrl`: "Base URL used to discover models automatically. When set, VS
  Code queries the endpoint for the list of available models instead of using the models
  array. Omit it to configure models explicitly with the models array."
* Model-level properties present in the reference: `id`, `name`, `url`, `apiType`,
  `toolCalling`, `maxInputTokens`, `maxOutputTokens`, plus provider-level `apiKey`.
* Auth: when the endpoint already declares a known auth header (`Authorization`,
  `api-key`, `x-api-key`, `x-goog-api-key`, `apikey`), "VS Code does not also send the
  default inferred auth header, so the endpoint doesn't receive conflicting credentials."

### 5.4 What is Copilot-specific and not in core [V/D]

* The model picker UI, BYOK provider catalogue (Anthropic/Azure/OpenAI/OpenRouter/Ollama/
  Gemini), plan-aware gating and auto-selection are implemented in the Copilot Chat
  extension (`github.com/microsoft/vscode-copilot-chat`, e.g.
  `src/extension/conversation/vscode-node/languageModelAccess.ts`, which builds
  `LanguageModelChatInformation` plus a `configurationSchema` for the picker), not in
  `microsoft/vscode` core. Core publishes the provider contract only.
* Therefore "VS Code-style" for VN Code means: **provider object + model list + explicit
  API type + provider-scoped identity**. It does not mean reproducing Copilot's catalogue,
  credits UI, or harness selection.

### 5.5 Not verified [H]

* Whether VS Code validates model ids client-side before sending. The docs suggest the
  server is the authority (discovery replaces the array), but no source path was read.
* Whether non-OpenAI discovery response shapes are supported by the Custom Endpoint
  provider. The docs describe discovery as a `baseUrl` query without specifying shapes.
* Exact JSON field names of `chatLanguageModels.json` beyond the properties listed in the
  docs table (the file is only described in docs; its parser lives in the Copilot Chat
  repo, which was not read line by line).

---

## 6. Three-way comparison

| Capability | Current VN Code | Archived VN Code | Official VS Code reference | Finding / recommendation |
| --- | --- | --- | --- | --- |
| 1. Provider configuration structure | Presets as `ProviderPreset` list (`ProviderPresets.kt:25-105`); custom endpoints as `Endpoint(id,label,baseUrl,enabled)` (`CustomEndpointStore.kt:60-66`) | One provider object per endpoint, registered in a registry (`ProviderBootstrap`) | Provider object with `apiType`, `apiKey`, optional `baseUrl`, plus `models[]` | **Partially implemented.** Model the provider/endpoint object we have; add `apiType`; keep identity `custom_<id>` |
| 2. Endpoint / base URL handling | Stored verbatim; form asks for a base URL | Stored base; both URLs derived from it | `baseUrl` is the discovery base; per-model `url` override exists | **Verified broken** (Defects A, B). One canonical base per endpoint |
| 3. URL normalisation | None at HEAD | `chatUrl` / `modelsUrl` (`CustomOpenAiProvider.kt:69-79`) | Path determined by `apiType`; docs do not specify string handling | **Verified broken at HEAD; fix in progress** (`EndpointUrls`, uncommitted) |
| 4. Protocol-specific request paths | OpenAI `baseUrl`; Anthropic `baseUrl`; Gemini appends `/v1beta/models/<id>:streamGenerateContent` | OpenAI family only | Chat Completions, Responses, Messages, per provider or per model | **Partially implemented.** Paths exist but chat has no protocol field; Gemini/Anthropic cannot be used as custom endpoints |
| 5. Auth and key isolation | Per-provider slots (`SlotNaming`), `x-api-key` / `x-goog-api-key` / Bearer per adapter | Per-endpoint key via provider id | One auth header only; provider-level `apiKey`, model-level override | **Verified working by construction, untested.** Docs' single-header rule already holds (each adapter sends exactly one) |
| 6. Model discovery | `ModelListFetcher`, OpenAI `data[].id`, from the editor only | `fetchModels` + `ProviderModelCache` with timestamp | Discovery triggered by provider `baseUrl`, replaces the model array | **Partially implemented**: works, but URL contract broken (Defect B) and no non-OpenAI shapes |
| 7. Discovery failure handling | Fetched ids can **replace** the stored list (Defect C) | Failure writes nothing, returns cached (`ModelCatalog.kt:126-170`) | Not specified in docs | **Verified broken at HEAD; improved but not finished** in the working tree (now folds instead of replacing) |
| 8. Manual model-id entry | No form field at HEAD | add/remove rows in the panel + store API | Explicit `models` array is the manual path | **Verified broken at HEAD; in progress**: Add/Remove rows added in the working tree |
| 9. Multiple models per endpoint | Store yes, UI no | Yes (store + panel) | Yes, core contract | **Verified broken at HEAD**; fix in progress |
| 10. Merging discovered + manual | Manual only; `live` unused | manual-first `mergeDistinct`, labelled groups | `baseUrl` discovery XOR `models` array | **Not implemented** (fold into manual is a simplification; acceptable short term, document it) |
| 11. Selection persistence | `providerId+keySlot+modelId+baseUrl` (`ChatSettingsPrefs.kt:20-41`) | Persisted per provider/model | Model picker state persisted by VS Code | **Partially implemented**: persists, but a stale `baseUrl` invalidates it (Defect D) |
| 12. Model id reaching the request | Yes: body (OpenAI/Anthropic), path (Gemini) | Yes, via provider `complete()` | Yes | **Verified working** |
| 13. Endpoint isolation | Key slots and model rows keyed by provider id | Same, plus separate provider instance | Per provider group | **Verified working by construction; untested** |
| 14. User-facing errors | Taxonomy (`ChatError.kt`), 404 says only "Model not found" | Wraps with endpoint label + URL | Not documented | **Partially implemented**; the 404 ambiguity fix is in the working tree |
| 15. Test coverage | Adapter unit tests assert verbatim URL (`OpenAIAdapterTest.kt:31`); no test covers custom endpoints, discovery, multi-model or the request pipeline | Archive had tests for catalog/refresh (`tests/`) | n/a | **Insufficient**: the defects above are all invisible to the current suite |

---

## 7. Review of the current uncommitted changes

`git status --porcelain` at the time of writing:

```
 M android/app/src/main/java/com/codespace/ide/chat/keys/ModelListFetcher.kt
 M android/app/src/main/java/com/codespace/ide/chat/panel/ChatSession.kt
 M android/app/src/main/java/com/codespace/ide/chat/panel/CustomEndpointEditor.kt
 M android/engine/src/main/kotlin/com/codespace/ide/engine/AnthropicAdapter.kt
 M android/engine/src/main/kotlin/com/codespace/ide/engine/ChatEngineImpl.kt
 M android/engine/src/main/kotlin/com/codespace/ide/engine/ChatError.kt
 M android/engine/src/main/kotlin/com/codespace/ide/engine/GeminiAdapter.kt
 M android/engine/src/main/kotlin/com/codespace/ide/engine/OpenAIAdapter.kt
?? android/engine/src/main/kotlin/com/codespace/ide/engine/EndpointUrls.kt
```

Diff size: 8 files, +129/-19, plus the new untracked file.

### 7.1 What was changed

1. **`EndpointUrls.kt` (new, untracked)**. `chatCompletions`, `models`, `messages`,
   `geminiStream`, `validBase`, `redactedPath`. Accepts a base or a full path and never
   duplicates the segment it knows about.
2. **Adapters**: `OpenAIAdapter.endpoint` -> `EndpointUrls.chatCompletions`;
   `AnthropicAdapter.endpoint` -> `EndpointUrls.messages`;
   `GeminiAdapter.endpoint` -> `EndpointUrls.geminiStream` (strips a pasted `/v1beta`).
3. **`ChatEngineImpl`**: resolves `requestUrl` once and uses the same string for the
   request and for the failure verdict.
4. **`ChatError` / `ErrorTaxonomy.classify`**: `ModelNotFound(modelId, endpointPath)` and an
   optional `endpointPath` argument; the 404 verdict now says "Model not found: X, or that
   path is wrong. The request went to <path>." Path only, query stripped.
5. **`ModelListFetcher`**: `EndpointUrls.models(baseUrl)` so chat and discovery share one
   contract.
6. **`CustomEndpointEditor`**: `models` state seeded from `store.manualModels(id)`, Add /
   Remove rows, "Add all N models" folds fetched ids in instead of replacing, base URL
   validation (`isError` + explanatory text, Save disabled), and
   `store.setManualModels(savedId, models.joinToString("\n"))` on save.
7. **`ChatSession`**: the "No model selected" verdict now tells the user where to add
   model ids.

### 7.2 Does it address the verified defects?

| Defect | Addressed? | Remaining gap |
| --- | --- | --- |
| A (chat URL) | **Yes, in principle**: base or full path both resolve to one completions URL | Gemini/Anthropic custom endpoints still impossible (no protocol field); no test yet |
| B (discovery URL) | **Yes, in principle**: one normaliser for both | Only OpenAI shape parsing; no per-protocol discovery path |
| C (multi-model UI) | **Yes, partly**: add/remove/fold works for new and saved endpoints | Discovered vs manual is not distinguished; no "fetch without saving key" nuance beyond the existing Test connection; no duplicate handling on save beyond `distinct()` |
| D (stale selection) | **No** | Selection still persists a copy of `baseUrl`; editing an endpoint still discards the model |
| E (dead `live` group) | **Not addressed** (folded into manual) | Documented as a deliberate simplification |

### 7.3 Compile and API risk (source read only, nothing executed)

* `EndpointUrls` is referenced from the engine module only, and from the app module
  (`ModelListFetcher`, `CustomEndpointEditor`) via `com.codespace.ide.engine.EndpointUrls`.
  The app module already depends on the engine module, so the import resolves **[H risk if
  engine is not an `api` dependency of the app, needs a build to confirm]**.
* `CustomEndpointEditor` uses `Modifier.weight(1f)` inside `Row` scopes and
  `ChatTokens.FONT_SMALL`, both already used in this file family. `supportingText` was
  written and then removed in favour of a plain `Text`, because Material3 version support
  for that parameter is unverified in this project.
* `ChatError.ModelNotFound` gained a second parameter with a default, so existing
  constructor calls still compile. `ChatStatusMapping.kt:43` uses only `userVerdict`, so the
  mapping is unaffected. No test asserts the old string (grep over `app/src/test` and
  `engine/src/test` returns nothing).
* `OpenAIAdapterTest.kt:31` still passes: its input already ends with `/chat/completions`,
  which the normaliser returns unchanged. `AnthropicAdapterTest.kt:26` likewise.
* **Nothing here has been compiled or tested.** No claim of green is made.

### 7.4 Missing tests for the uncommitted work

None exist yet for: the URL matrix, discovery URL/auth, multi-model add/remove/merge,
request URL + header + body from a `PanelSelection`, endpoint isolation, or 404 verdict
content.

### 7.5 Regression risk to built-in providers

* Presets store full paths, and the normaliser is idempotent on a full path, so presets
  should be unchanged **[H, needs the adapter tests to confirm]**.
* Gemini: a base ending `/v1beta` is now stripped instead of doubled. `GeminiAdapterTest`
  line 31 asserts `startsWith("https://example.test/v1beta/")`, which still holds.
* Discovery is only reachable from the custom endpoint editor, so built-in presets are not
  touched.

### 7.6 Consistency, safety, secrets

* Chat and discovery now share one normaliser: **consistent**.
* Manual and fetched models are both preserved on a fetch (fold instead of replace), but a
  fetched id can overwrite nothing and cannot delete; **safe**.
* Selected model id reaches the request: unchanged path, still correct.
* Endpoint credentials stay isolated by provider id; `redactedPath` strips the query string
  so a key in a query parameter can never appear in an error card. No new logging added.

---

## 8. Recommended design (target state)

### 8.1 Provider-level

* `Endpoint(id, label, baseUrl, apiType, enabled)` where `apiType` is one of
  `OPENAI_CHAT_COMPLETIONS` (default), `ANTHROPIC_MESSAGES`, `GEMINI_GENERATE`.
  Defaulting to OpenAI keeps every stored endpoint valid with no migration.
* Credential: one secret per endpoint slot (`ai_CUSTOM_<ID>`), unchanged.
* No new speculative fields (no per-model pricing, no custom headers yet; custom headers
  stay parked as the presets' header note already says).

### 8.2 Model-level

* Keep the existing two lists and give them their real meaning back:
  `manual_<id>` = user-authored ids, `live_<id>` + `live_ts_<id>` = discovered ids.
* Model identity in a selection: `providerId` + `modelId`. **Do not** persist `baseUrl` as
  part of identity; resolve it from the endpoint at request time. That single change fixes
  Defect D and makes endpoint edits safe.
* Display: one flat list per endpoint with a small "discovered" marker, manual first.
  Do not reintroduce the archived grouped picker.

### 8.3 Request pipeline

`EngineConfig.baseUrl` stays the endpoint base; the adapter (or a protocol-aware resolver
the adapter calls) derives the request URL. Selection never carries a URL copy.

---

## 9. Discovery and manual-entry experience

1. Settings -> AI providers -> endpoint form: name, base URL, API type, API key.
2. **Fetch models** queries the protocol's discovery path (`/models` for OpenAI-compatible;
   Gemini `models` endpoint; Anthropic has no public list, so it is reported as
   "not supported for this API type" rather than attempted).
3. Results are shown as a checklist; the user adds the ones they want. Existing ids are
   never removed by a fetch, and a failed fetch leaves the list untouched and says why
   (401 vs 404 vs unreachable vs malformed), reusing `verdictOf`.
4. **Add model manually** stays available in every state, including when discovery is
   unsupported or fails.
5. The chat picker lists manual first, then discovered, with a "Refresh" action that only
   ever adds.

---

## 10. URL normalisation and protocol contract

| Protocol | Input accepted | Chat URL | Discovery URL |
| --- | --- | --- | --- |
| OpenAI-compatible | base (`https://h/v1`), base with prefix (`https://h/openai/v1`), full (`.../chat/completions`), trailing slash, `/models` | base + `/chat/completions` unless already present | base + `/models` unless already present; `/chat/completions` converted |
| Anthropic Messages | base, `/v1`, full (`/v1/messages`) | base + `/v1/messages`, `/v1` + `/messages`, full unchanged | not supported |
| Gemini | base, `/v1beta` | base (minus `/v1beta`, `/v1`) + `/v1beta/models/<id>:streamGenerateContent?alt=sse` | `/v1beta/models?key=...` (or `x-goog-api-key`) |

Cases the uncommitted normaliser handles: trailing slash **[V by code]**, full path
**[V]**, `/v1` base **[V]**, `/openai/v1` **[V]**, `/models` input **[V]**, duplicate
`/v1beta` for Gemini **[V]**, invalid scheme via `validBase` **[V]**.

Cases it does **not** handle, and should:

* **Query strings**: `https://h/v1?x=1` becomes `https://h/v1?x=1/chat/completions`
  (wrong). Should either reject a base with a query or append to the path before the query.
* **Duplicate version segments** other than Gemini's (`https://h/v1/v1`): deliberately not
  "fixed" because guessing is worse than saying so; recommend detecting and warning in the
  form instead of rewriting.
* **Per-protocol discovery**: only OpenAI shape is implemented.
* **Unexpected path prefixes**: correctly untouched (a base of `https://h/api/openai/v1`
  yields `https://h/api/openai/v1/chat/completions`) **[V]**.

Placement recommendation: keep `EndpointUrls` in the engine as the single protocol-aware
resolver, and have each adapter call the function for *its* protocol. Do not put
normalisation in the UI and do not apply OpenAI rules to Gemini or Anthropic.

Error taxonomy required:

| Situation | Distinguishable today? | Recommendation |
| --- | --- | --- |
| Invalid base URL | Yes, at save time (`validBase`), uncommitted | keep, plus refuse to send |
| Connection failure | Yes (`ChatError.Network`) | unchanged |
| Auth failure | Yes (401/403 -> `Auth`) | unchanged |
| Unsupported endpoint/protocol | No | 404 verdict now names the path (uncommitted); add "the endpoint may not be a <protocol> API" wording in phase 3 |
| Model not found | Yes, but indistinguishable from wrong path | uncommitted path-in-verdict fix; keep the hedge wording, never assert certainty |
| Rate limit | Yes (429 + Retry-After) | unchanged |
| Malformed response | Partially (parse failures surface as network/EOF) | add an explicit `Malformed` verdict |
| Server failure | Yes (5xx -> `Server`) | unchanged |

Secrets: no key in any verdict, log, or test output. `redactedPath` strips query strings;
`StoredCredentialsReader` logs counts only **[V]**.

---

## 11. Phased implementation plan (not executed)

**Phase 1: protocol + URL contract** (engine)
- Files: `engine/EndpointUrls.kt` (finish query handling), `OpenAIAdapter`, `AnthropicAdapter`,
  `GeminiAdapter`, `ChatEngineImpl`, `ChatError`.
- Tests: URL matrix per protocol, idempotence, query rejection, 404 verdict contains the path
  and no key.

**Phase 2: data structure** (`apiType` on `Endpoint`; selection without `baseUrl`)
- Files: `chat/CustomEndpointStore.kt` (schema + default), `chat/panel/SelectionResolver.kt`,
  `ChatSession.kt`, `ChatSettingsPrefs.kt`, `CustomEndpointEditor.kt`.
- Tests: round-trip with `apiType`; migration of an existing endpoint JSON; selection
  survives an endpoint URL edit; `resolve` re-derives the base from the endpoint.

**Phase 3: discovery UI**
- Files: `CustomEndpointEditor.kt`, `ModelListFetcher.kt` (per-protocol URL + non-OpenAI
  shapes), `ChatPanelScreen.kt` picker data.
- Tests: fetch merges (never replaces), failure leaves the list intact, manual add works
  with discovery unsupported, 401 vs 404 verdicts.

**Phase 4: selection and request pipeline**
- Files: `ChatPanelScreen.kt` (picker), `ChatSession.kt`, adapters.
- Tests: one endpoint, three model ids, each selection produces its own URL/body; switching
  models under the same endpoint keeps endpoint and key; provider switch never reuses the
  previous provider's key.

**Phase 5: automated coverage and regressions**
- Engine tests: URL matrix, adapter payload/headers, taxonomy.
- App tests with a recording `ChatTransport`: exact URL, headers, body `model` per protocol;
  endpoint isolation; secret redaction; persistence across a simulated restart
  (`ChatSettingsPrefs` + store round-trip).

**Phase 6: build, CI, device**
- Build both modules, push in small commits, poll Actions to a verdict, then a device round:
  add endpoint, fetch models, add two ids, switch between them, restart the app, edit the
  base URL, confirm the request URL in the error card when deliberately wrong.

---

## 12. Open questions and unverified behaviour

1. Does the app module have a compile-time dependency on the engine module for
   `EndpointUrls`? (`[H]`, one build answers it.)
2. Anthropic and Gemini have no discovery story in the rebuild; the archive only supported
   OpenAI discovery. Decide whether custom Anthropic/Gemini endpoints are in scope at all.
3. Whether `Response` API type (VS Code's third type) is wanted. It is out of scope today.
4. Whether discovered ids should be visually distinct from manual ids in the picker; the
   recommendation is a marker, but that is a UI decision for the owner.
5. Whether the picker's free-text field should remain once discovery works (recommend:
   yes, as the manual fallback).
6. Exact VS Code `chatLanguageModels.json` schema beyond the documented property list
   (`[D]`, parser not read).
7. Whether a per-model `url` override is needed (VS Code has it; VN Code has no use case
   yet).

---

## 13. Recommendation

Finish Phase 1 and Phase 2 before any further UI work. The uncommitted work is the right
direction and should be completed rather than reverted, but it is not sufficient: the
selection model still copies `baseUrl` (Defect D), there is no protocol field (so custom
Anthropic/Gemini endpoints are unreachable), and nothing is tested. The smallest correct
next step is to finish `EndpointUrls` (query handling), add `apiType` with an OpenAI
default, resolve the request base from the endpoint instead of the persisted selection, and
land the request-pipeline tests that would have caught Defects A and B.
