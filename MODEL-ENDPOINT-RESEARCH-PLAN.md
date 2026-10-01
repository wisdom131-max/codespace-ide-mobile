# Model calling and configuration: findings and approval-gated plan

Research date: 2026-10-01. Application implementation baseline: `b032295`. This report changes no implementation code. Items 1 and 2 require the owner's explicit go before implementation, including the deficiencies found below.

## 1. Native tool calling: what the app actually does

### Answer to the primary-path question

**Yes: native OpenAI Chat Completions tools are the primary request path for every registered custom OpenAI-compatible endpoint when Agent mode has permitted tools. This is not conditional on a Qwen model name.**

Source chain:

1. [ProviderBootstrap.kt](android/app/src/main/java/com/codespace/ide/chat/ProviderBootstrap.kt), `customEndpointProviders`, constructs the same `CustomOpenAiProvider(ep.id)` implementation for every endpoint.
2. [CustomOpenAiProvider.kt](android/app/src/main/java/com/codespace/ide/chat/providers/CustomOpenAiProvider.kt) declares `supportsNativeTools = true` for every instance and forwards `request.tools` through both normal and streaming completion.
3. [CopilotChatPanelOverlay.kt](android/app/src/main/java/com/codespace/ide/ui/screens/CopilotChatPanelOverlay.kt), the chat tool loop, creates native schemas for Agent mode when that provider property is true. The appended system instruction explicitly prefers native definitions over text tags.
4. [OpenAiCompatibleTransport.kt](android/app/src/main/java/com/codespace/ide/chat/providers/OpenAiCompatibleTransport.kt), `call` and `callStreaming`, send standard `tools` and `tool_choice: auto` when the schema array is nonempty. Ask/Plan mode does not send these schemas; an empty allowlist also means no available schemas.
5. [NativeToolProtocol.kt](android/app/src/main/java/com/codespace/ide/chat/NativeToolProtocol.kt), `responseText`, prefers a nonempty native `tool_calls` array, preserves IDs/arguments and strips competing text tags from the content to avoid executing both representations. Streaming accumulates native delta fragments before normalizing the response.
6. [AgentTools.kt](android/app/src/main/java/com/codespace/ide/agent/AgentTools.kt), `TOOL_REGEX` and `parseToolCalls`, accept both Qwen `<tool_call>` and old `<tool>` text forms when the model produces tags instead of native calls.

Scope: these custom providers implement the OpenAI-compatible **Chat Completions** contract. An arbitrary Responses or Anthropic Messages URL is not supported merely because a user enters it as a custom base URL. Other built-in provider adapters have their existing wire formats and are outside the claim that all custom OpenAI-compatible instances send native schemas.

### Important qualification: parsing fallback is not negotiated transport fallback

The implementation does not first discover whether the selected endpoint/model accepts native tools. It assumes support.

- A server rejecting `tools` or `tool_choice` receives no automatic tag-only retry. `OpenAiCompatibleTransport` throws the HTTP error; the custom provider prefixes endpoint context. Key failover is not capability fallback.
- A server returning a successful text-only response can use Qwen/legacy tags because the parser accepts them. This does not prove that the server supports native tool messages.
- The tool loop determines native result formatting from the provider-wide `supportsNativeTools` property, not the provenance of each returned call. Text-tag calls on a custom provider therefore still become native assistant `tool_calls` and `role: tool` result messages on the next request. That can fail against a genuinely content-only backend.
- It does not silently mark prose as verified completion. The Round 1 evidence labels remain valuable, but they do not replace capability detection or mode gating.

**Verdict:** the primary-path generalization is already present. Capability-aware eligibility and a genuine legacy-wire compatibility path are missing. Those are the needed fixes, pending go.

### Current per-model capability gap

[ChatProvider.kt](android/app/src/main/java/com/codespace/ide/chat/ChatProvider.kt) has a nullable `ChatModelInfo.supportsToolCalling`, but the current source has no consumer enforcing it. Custom providers inherit `fetchModelInfos`, which wraps fetched model ID strings with unknown capability. [CustomEndpointStore.kt](android/app/src/main/java/com/codespace/ide/chat/CustomEndpointStore.kt) stores per-endpoint manual/live ID lists, not capability declarations. Provider availability checks a configured endpoint and key, not whether this selected model can execute tools.

This conflates three separate facts:

- The adapter knows how to encode native tools.
- The endpoint accepts that protocol.
- The selected model supports tool calling.

They must not be represented by one hardcoded provider boolean.

## 2. VS Code: capability discovery and unsupported models

### Evidence and freshness

Checked Microsoft's current [language-model documentation](https://code.visualstudio.com/docs/agent-customization/language-models) on 2026-10-01. Source checkout used for implementation comparison: VS Code `832cf23c`, dated 2026-09-19. These are pinned source findings, not a claim to have run every VS Code release or remote agent harness.

### Where capabilities come from

The Language Model API lets each provider contribute model information, including `capabilities.toolCalling` and `imageInput`. The API allows toolCalling to be a boolean or a maximum number of tools per request; it is not simply inferred from the model's display name.

- API: [vscode.d.ts, LanguageModelChatCapabilities](https://github.com/microsoft/vscode/blob/832cf23c/src/vscode-dts/vscode.d.ts), and [extHostLanguageModels.ts](https://github.com/microsoft/vscode/blob/832cf23c/src/vs/workbench/api/common/extHostLanguageModels.ts), `registerLanguageModelChatProvider`.
- For manually configured Custom Endpoint models, the documented `chatLanguageModels.json` model properties include `toolCalling`, `vision`, limits, URL and optional per-model `apiType`.
- [customEndpointProvider.ts](https://github.com/microsoft/vscode/blob/832cf23c/extensions/copilot/src/extension/byok/vscode-node/customEndpointProvider.ts) and [customOAIProvider.ts](https://github.com/microsoft/vscode/blob/832cf23c/extensions/copilot/src/extension/byok/vscode-node/customOAIProvider.ts) use the configured model's capability; absent toolCalling resolves to false in endpoint construction.
- [byokProvider.ts](https://github.com/microsoft/vscode/blob/832cf23c/extensions/copilot/src/extension/byok/common/byokProvider.ts), `resolveModelInfo` and `byokKnownModelToAPIInfo`, translate declared/known model metadata to the provider/API representation. Explicit model capabilities take precedence over known model information.
- Where a provider exposes metadata, adapters can use it: [openRouterProvider.ts](https://github.com/microsoft/vscode/blob/832cf23c/extensions/copilot/src/extension/byok/vscode-node/openRouterProvider.ts) checks whether `supported_parameters` includes `tools`; [ollamaProvider.ts](https://github.com/microsoft/vscode/blob/832cf23c/extensions/copilot/src/extension/byok/vscode-node/ollamaProvider.ts) reads advertised tool capabilities.

This is metadata/configuration-driven eligibility, not a universal hidden network probe proving every endpoint works.

### What happens without tool support

For ordinary workbench/local chat:

- [languageModels.ts](https://github.com/microsoft/vscode/blob/832cf23c/src/vs/workbench/contrib/chat/common/languageModels.ts), `suitableForAgentMode`, requires toolCalling and does not permit an explicitly disabled agentMode capability.
- [chatInputModelUtils.ts](https://github.com/microsoft/vscode/blob/832cf23c/src/vs/workbench/contrib/chat/browser/widget/input/chatInputModelUtils.ts), `filterModelsForSession` and `isModelSupportedForMode`, exclude incompatible models from the Agent model pool. Non-Agent modes do not apply that same tool requirement. Editor inline chat also requires toolCalling in this source version.
- [chatInputModelSelectionController.ts](https://github.com/microsoft/vscode/blob/832cf23c/src/vs/workbench/contrib/chat/browser/widget/input/chatInputModelSelectionController.ts), `ensureCurrentModelSupported`, re-evaluates the selected model and chooses a compatible default when necessary. There can also be a no-models-available state.
- Session-specific remote agent harnesses can provide their own model pools; the ordinary workbench policy is not proof of identical behavior for every harness.

The current official docs say models lacking tool calling are not shown in the picker when using agents. This is **not** a special document-only mode and does **not** mean VS Code globally removes Agent mode whenever one configured model is text-only. A text-only model can remain useful for non-Agent chat; incompatible Agent selections are filtered/reset.

### Comparison

| Question | VS Code evidence | Current app |
|---|---|---|
| Capability ownership | Per model, supplied by provider/configuration | Native wire flag hardcoded per custom provider |
| Unknown custom model | Explicit declaration is needed for Agent eligibility | Treated as native-capable because its provider is custom |
| Known no-tools model | Excluded from ordinary Agent pool | No equivalent eligibility gate |
| Incorrect capability declaration/server rejection | Provider request can still fail; metadata is not a guarantee | Generic endpoint error, no negotiated legacy-wire retry |
| Text without a tool call | Not proof of execution | Round 1 labels it NOT EXECUTED/unverified |
| Qwen/legacy support | Provider-specific adapters/contracts | Shared tag parser, but fallback result transcript remains native for custom providers |

## 3. Item 1: proposed corrective scope, not implemented

1. **Separate capability from transport.** Keep native Chat Completions as the normal custom endpoint path. Add model-level tool status supported/unsupported/unknown, evidence source and supported calling protocol. Do not guess Qwen capabilities from the name.
2. **Populate and expose metadata.** Accept useful discovery metadata where available; allow explicit per-model declarations for manual IDs. Unknown is not false, and listing a model from `/models` is not proof of tool support. Manual IDs remain valid configuration entries without client-side existence validation.
3. **Gate Agent, not the whole model.** Known unsupported models remain available in Ask but cannot execute Agent tools. Show why. For unknown custom models, show an explicit warning and require the owner's capability/compatibility choice before enabling execution. Do not silently switch providers or forward the same key to a different endpoint. Apply the same check at send time, not only in the picker.
4. **Preserve call provenance.** Native calls keep real IDs and native result roles. An explicitly selected compatible tag-only path uses a content transcript, not fabricated native role requirements. Accept text-tag parsing as a fallback without turning all fallback calls into native wire messages.
5. **Handle rejection conservatively.** A clearly identified unsupported-tools response may offer Ask or an explicitly enabled legacy compatibility path. Never infer unsupported tools from every 400/404, bad schema, bad key, invalid model, timeout or empty native call array. Never replay after partial streaming or any execution. No automatic action-to-prose downgrade presented as success.
6. **Keep safety and scope.** Existing trust, approval, allowlist, staging and execution evidence gates remain. Initially apply this correction to custom endpoint handling; do not disable existing built-in/local Agent behavior just because those adapters use the default `supportsNativeTools = false` for their wire format.

Acceptance matrix before shipping: two differently named custom models emit standard native schemas; native and fragmented calls preserve IDs; mixed native-plus-tags executes once; no-tools stays usable in Ask; unknown is explained; explicit tag-only flow has no native result roles; unrelated 400/401/404 never marks unsupported; no retry after streaming/execution; all permission/staged-only labels remain correct. Use a fake HTTP server to inspect complete request/result transcripts, not only parser fixtures, plus live endpoint/device checks after green Android CI.

## 4. Model/provider configuration: what VS Code actually unifies

The right analogy is **one catalog/management surface with separate feature bindings**, not one model selection shared by every feature.

- The provider API registers models into the language-model service. The Language Models editor lists models grouped by provider, with capabilities, limits and visibility. BYOK/custom groups are configured through provider-level plus model-level data in `chatLanguageModels.json`.
- [languageModelsConfigurationService.ts](https://github.com/microsoft/vscode/blob/832cf23c/src/vs/workbench/contrib/chat/browser/languageModelsConfigurationService.ts) uses the current profile's language-model configuration resource; [languageModels.ts](https://github.com/microsoft/vscode/blob/832cf23c/src/vs/workbench/contrib/chat/common/languageModels.ts) provides registration and selection.
- Main/panel chat selects a model from its picker. Inline chat has a separate `inlineChat.defaultModel` and a session override. [inlineChatDefaultModel.ts](https://github.com/microsoft/vscode/blob/832cf23c/src/vs/workbench/contrib/inlineChat/browser/inlineChatDefaultModel.ts) draws eligible models from the language-model service, rather than inventing another independent endpoint registry.
- Current docs separately describe inline suggestion model selection and utility defaults `chat.utilityModel` / `chat.utilitySmallModel`. The main chat selection does not control these tasks. Inline suggestions have their own available-model list; do not assume every BYOK chat model is a completion model.
- `imageInput`/vision is not evidence of image **generation**. These inspected sources do not establish a matching first-class Gemini image-generator feature. The recommendation below borrows catalog/binding separation, not an invented VS Code image-generation setting.

## 5. Our current split, verified from source

| Responsibility | Existing source of truth |
|---|---|
| Chat provider adapters | [ChatProviderRegistry.kt](android/app/src/main/java/com/codespace/ide/chat/ChatProviderRegistry.kt) + [ProviderBootstrap.kt](android/app/src/main/java/com/codespace/ide/chat/ProviderBootstrap.kt) |
| Custom endpoint label/URL/identity, manual and cached live IDs | [CustomEndpointStore.kt](android/app/src/main/java/com/codespace/ide/chat/CustomEndpointStore.kt) |
| Chat choice, per-mode memory, Auto, pinned choices | [ChatModelSelection.kt](android/app/src/main/java/com/codespace/ide/chat/ChatModelSelection.kt) and [ChatModelMenuButton.kt](android/app/src/main/java/com/codespace/ide/ui/screens/ChatModelMenuButton.kt) |
| Chat model metadata/context usage | [ChatProvider.kt](android/app/src/main/java/com/codespace/ide/chat/ChatProvider.kt), provider `fetchModelInfos`, [TokenCounter.kt](android/app/src/main/java/com/codespace/ide/chat/TokenCounter.kt) |
| Image model override | [ProjectSettingsStore.kt](android/app/src/main/java/com/codespace/ide/editor/ProjectSettingsStore.kt): `geminiImageModel`, preference `gemini_image_model`, synced to settings JSON |
| Image model settings UI | [InProjectSettingsDialog.kt](android/app/src/main/java/com/codespace/ide/ui/screens/InProjectSettingsDialog.kt): `gemini_image_model` row in AI_AGENT |
| Image generation execution/default | [ImageGenDialog.kt](android/app/src/main/java/com/codespace/ide/ui/panes/ImageGenDialog.kt) reads the project override; [ImageGenService.kt](android/app/src/main/java/com/codespace/ide/ui/panes/ImageGenService.kt) defaults to literal `gemini-2.5-flash-image` and uses native Gemini image generation |
| Image credential | [ExplorerPane.kt](android/app/src/main/java/com/codespace/ide/ui/panes/ExplorerPane.kt) passes `tokenStore?.aiKey("GEMINI")` to the image dialog |

The image ID is a separate project setting and the image service is not registered as a chat provider/model selection. Keeping an image-specific execution adapter is appropriate. Maintaining unrelated model-management fields is the part to unify.

## 6. Item 2: proposed consistent design, not implemented

### Target contract

One **Models & Endpoints** management surface, reached from existing AI settings and model pickers. It manages provider groups, endpoint URL/API kind, secure credential references, manual/discovered models and capability provenance. No raw key values in catalog JSON, exports, logs or display rows.

A stable model reference includes endpoint/provider identity and literal API model ID. Two endpoints exposing the same ID remain different entries; labels can change without breaking references. Distinguish image input from image generation, and adapter-supported tasks from unverified server capabilities.

Separate bindings choose what each feature uses:

- Chat: preserve existing Auto, per-mode and session behavior.
- Image generation: preserve project-scoped override/default behavior. Do not turn this into a global selection just to make the UI look unified.
- Future features can add bindings when approved; this plan does not implement new inline, utility, extension or XG features.

The catalog is shared; bindings retain their appropriate scope. A missing/deleted model or endpoint produces a clear unresolved-binding state, never an arbitrary replacement model or credential crossover.

### Approval/build phases

**A. Read-only schema and migration specification.** Inventory existing persisted values, ownership, secure key slots and cache keys. Define endpoint records, model references, capability provenance, task bindings and versioned/idempotent migration. Reuse existing on-device stores and atomic persistence; no server backend and no XG foundation dependency. Unit-test reference identity and migration fixtures before changing callers.

**B. Shared catalog facade.** Adapt existing built-in/custom registry and manual/live models into one query layer. Keep fetched and manual entries distinct and mergeable; keep free-form IDs and deletion/refetch behavior. Carry source/timestamp and unknown capabilities rather than invent support. Keep literal IDs unchanged. This phase does not change actual chat/image dispatch.

**C. Unified management UI.** Provide provider/endpoint groups, models, capability controls and feature default bindings in one location. Chat and image dialogs link to this surface; the old image setting becomes a link/compatibility facade, not a second editable authoritative field. Extract new composables/effects into their own files; preserve 8-12 dp corners and minimum 12 dp horizontal / 10 dp vertical padding.

**D. Migrate and bind image selection.** Preserve a nonempty `gemini_image_model` verbatim as a project image binding to the Gemini image adapter. Empty keeps the current default. A legacy/manual override records the user's intended task, not fabricated proof that the server accepts it. Preserve the current Gemini credential behavior until a tested explicit credential-reference migration exists. The image request still uses its own native Gemini endpoint, response modalities, auth header and image-size cap; do not route it through Chat Completions merely to unify settings.

**E. Wire capability policy and verify cross-feature isolation.** Use item 1's capability contract at picker/send boundaries. Context and discovery caches are keyed by endpoint + model identity. Editing/deleting one endpoint does not change another's model list, key or bindings. Capability invalidation follows endpoint/model/config changes; model API errors still carry endpoint/model context. Only implemented image adapters are selectable for image generation; this does not silently add new image vendors.

**F. Verification and legacy writer removal.** Migration tests cover existing chat selection, per-mode preferences, Auto, pins, manual/live lists, image empty/nonempty values, reused IDs across endpoints, deletion/recreation and repeated migration. Test that choosing chat never changes image and vice versa. Verify same adapter URLs and key slots before/after migration. Remove old duplicate writers only after source proof, green Android CI and device checks. Each approved build phase ships its own code/CI/doc receipt; no implicit go to later phases.

## 7. Handoff mapping: exact template supplied, mapping delivered

The owner supplied `65cb7281b_EXHAUSTIVE_PROJECT_KNOWLEDGE_HANDOFF_CONTEXT_PERSISTENCE_FRAMEWORK.md` on 2026-10-01 after this report was written. Its 80 numbered Parts are mapped in [HANDOFF-80-SECTION-MAP.md](HANDOFF-80-SECTION-MAP.md), with exact file/heading references, quick factual answers and advisor-side/partial-coverage qualifications. The earlier 50-part and 23-part uploads remain historical material; they were not mislabeled as the requested 80-Part template.

The mapping flags stale front matter, older audit snapshots, source-versus-runtime evidence and the explicit TP02 device gate. It is not 80 rewritten histories or a fresh implementation audit. The template blocker is resolved; model/capability and configuration implementation still require the owner's go.

## Pending work and boundaries

- Owner go required for item 1 capability/fallback correction and each approved item 2 implementation phase.
- Item 3 mapping delivered; missing advisor-only history is not fabricated.
- Existing Round 1 device verification is still pending; build/parser fixtures are not device proof.
- Previous items 4-9 remain frozen pending owner review; XG01-04 remain paused/individually owner-gated; F6 JVM debug remains owner-gated. Full terminal comparison and remaining P5/source-derived test work retain their prior place in the roadmap. This research implements none of them.
