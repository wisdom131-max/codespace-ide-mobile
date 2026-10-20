# G-E PLAN — Capability table for providers and models (PLAN ONLY, no code)

> Advisor GO 2026-10-20 (item 2). Status: PLAN. No code until per-phase approval.
> Goal: the app KNOWS what each provider/model can do — images, audio, video,
> PDF, tools/function calling, max context, streaming — and NEVER claims a
> capability it does not know. One seam feeds the existing `supportsImages`
> gate, the U4 PDF page-image path, and the future U5 media parts.

---

## Part 1 — SOURCE FIRST: how real projects represent and obtain capabilities

| Project | Where capabilities live | File / function | Images | Tools / function calling | Max context | How obtained | READ vs DOC |
|---|---|---|---|---|---|---|---|
| **Cline** | `ModelInfo` interface, per-model booleans | `apps/vscode/src/shared/api.ts` — `maxInputTokens?`, `supportsImages?`, `supportsPromptCache`, `contextWindow`, `supportsReasoningEffort?`; helper `supportsImages(modelInfo)` in `apps/vscode/webview-ui/src/components/settings/utils/pricingUtils.ts` | `supportsImages: boolean` | hardcoded per vendor | `contextWindow`, `maxInputTokens` | HARDCODED tables in `src/shared/` (e.g. `onboarding.ts` model rows: `contextWindow: 128_000, supportsImages: false`) | READ (GitHub code search, 2026-10-20) |
| **Continue.dev** | `ModelCapability` on each model config | `core/index.d.ts:1220` — `interface ModelCapability { uploadImage?; tools?; nextEdit? }`; YAML accepts strings `["tool_use", "image_input"]`, converted to the typed object in `core/config/yaml/models.ts` (`capabilities: { tools: model.capabilities?.includes("tool_use"), ... }`) | `uploadImage` | `tools` | `defaultCompletionOptions.contextLength` | config + live: `core/llm/fetchModels.ts` builds capabilities from provider responses (Ollama capability keys `vision`/`audio`/`embedding`); `core/llm/autodetect.ts` checks `capabilities?.upload` | READ |
| **Zed** | Per-provider crates, per-model functions | `crates/mistral/src/mistral.rs` — `fn supports_tools(&self) -> bool` hardcoded per model enum variant; `crates/llama_cpp/src/llama_cpp.rs` — `supports_tools` from `chat_template_caps`, vision from `modalities.vision`; `crates/ollama/src/ollama.rs` — `ModelShow::supports_tools` reads live metadata | `modalities.vision` | `supports_tools()` | `max_output_tokens` | MIXED: hardcoded per provider + live metadata where the local server offers it | READ |
| **VS Code (Copilot)** | Public LLM surface has NO capability booleans | `src/vscode-dts/vscode.d.ts` — `LanguageModelChat`: `vendor`, `family`, `version`, `maxInputTokens` (~L20266-20629), `maxOutputTokens` (L20629) | NOT exposed | NOT exposed | `maxInputTokens` / `maxOutputTokens` | Copilot's own model picker carries vendor-internal metadata the API does not publish | DOC (public API surface READ from vscode.d.ts; Copilot-internal metadata DOC) |
| **OpenRouter** | Rich live /models endpoint | `https://openrouter.ai/api/v1/models` (verified LIVE 2026-10-20): `architecture.input_modalities: ["text","image","file"]`, `architecture.output_modalities`, `context_length`, `supported_parameters` incl. `"tools"`, `response_format`, `structured_outputs`, plus pricing | input_modalities | supported_parameters | `context_length` | LIVE fetch, one call for the whole catalog | READ (live call) |
| **LiteLLM** | One bundled JSON catalog | repo root `model_prices_and_context_window.json` (4224 rows, verified LIVE): per-model `supports_vision`, `supports_function_calling`, `supports_parallel_function_calling`, `supports_pdf_input`, `supports_prompt_caching`, `supports_response_schema`, `supports_system_messages`, `supports_tool_choice`, `max_input_tokens`, `max_output_tokens`, `mode` + cost fields + `*_priority` override precedence fields | `supports_vision` | `supports_function_calling` + parallel + tool_choice | `max_input_tokens` | BUNDLED file updated per release; consumed by `litellm/utils.py` (`supports_function_calling()`, `get_model_info()`) | READ (live file) |
| **Hugging Face router** | Provider-level, OpenAI-compatible | `docs.huggingface.co` router docs; live `/v1/models` requires an auth token (unauthenticated call returns the site shell) | DOC | DOC | DOC | DOC | DOC |
| **OpenAI / OpenAI-compatible /models** | NOTHING | `platform.openai.com` API reference: response is `id`/`object`/`created`/`owned_by` only | — | — | — | matches our G-C refetch finding: OpenAI-compatible model lists carry NO capability data | DOC |

**Synthesis.** Every real project uses one of three shapes, in this order of
richness: (1) LIVE catalog fetch (OpenRouter — richest, one call, exact
fields we need); (2) BUNDLED curated table (LiteLLM — 4k+ rows, per-model
booleans + token ceilings, the de-facto standard for offline capability
data; Cline's shared tables are the same idea at small scale); (3) hardcoded
per-provider logic (Zed — honest but per-crate maintenance). VS Code's public
API shows what NOT to do for us: token ceilings without capability booleans
forces every consumer to guess. Continue is the closest to our shape: a
config-level capability object converted from short string lists, fed by a
live fetch when available, `undefined` otherwise.

The `*_priority` fields in LiteLLM's catalog are worth copying: user-supplied
rows override bundled rows, explicitly and per-field.

---

## Part 2 — DESIGN (plan only; every item needs approval before code)

### G-E-1. Data model (new, pure Kotlin, one small file)

```
ModelCapabilityFlags(
  acceptsImages:  Boolean?,   // null = UNKNOWN — never claimed
  acceptsAudio:   Boolean?,   // U5: pre-declared so U5 does not re-shape
  acceptsVideo:   Boolean?,   // U5
  acceptsPdf:     Boolean?,   // native PDF input (file part), NOT our extraction
  supportsTools:  Boolean?,   // function/tool calling
  maxContextTokens: Long?,    // max INPUT tokens when known
  supportsStreaming: Boolean?,
)
```

Rationale: nullable booleans with an explicit UNKNOWN state (Cline uses
`supportsImages?: boolean`; Continue uses optional fields; LiteLLM uses absent
keys). Resolution happens in ONE function:
`ModelCapabilities.forModel(endpointId, modelId): ModelCapabilityFlags`.

### G-E-2. Sources in priority order (first non-null wins, per field)

1. **USER OVERRIDE** — persisted per endpoint+model in the existing MK
   per-endpoint registry (LiteLLM's `*_priority` pattern: explicit, per-field,
   deletable). UI toggle rows, not YAML.
2. **LIVE provider metadata** — piggybacks the existing G-C model-list
   refetch (`registeredModelEntries` + `liveModelsFetched` in
   `CopilotChatPanelOverlay.kt`): only OpenRouter-style endpoints that return
   `architecture.input_modalities` / `supported_parameters` / `context_length`
   populate fields; OpenAI-compatible endpoints that return nothing are
   SKIPPED (nothing claimed). Fetched values cache per endpoint alongside the
   existing cached model lists, same staleness window.
3. **BUNDLED DEFAULT TABLE** — a curated JSON asset (~100 rows: the common
   Gemini / GPT / Claude / Llama / Mistral / DeepSeek families), keyed
   `provider:family-prefix`, updated per release from LiteLLM's
   `model_prices_and_context_window.json` (which is exactly what it exists
   for). Absent models stay UNKNOWN — no fuzzy guessing.
4. **PROVIDER-LEVEL DEFAULT** — today's `ChatProviderRegistry.supportsImages`
   remains the last-resort fallback for built-in providers only, so behavior
   before any data loads is IDENTICAL to today.

### G-E-3. UNKNOWN handling (the core rule)

NEVER claim an unknown capability. Concretely:

- The gate function `acceptsImages(model) ?: false` treats UNKNOWN as NO for
  gating attachments (same as today for non-image providers).
- When a user attaches an image / scanned PDF and the target model is
  UNKNOWN, the chip shows the **metadata fallback** (honest "image support
  unknown for this model — nothing sent beyond metadata") PLUS a plain
  offer: a toggle "This model accepts images" (writes the user override,
  row source 1). One tap, remembered.
- The toggle is also reachable from the model picker row (a small
  capabilities line: "images ✓ · tools ✗ · context 128k" with UNKNOWN
  shown as "unknown", never guessed).

### G-E-4. How it feeds the existing gates (the seam)

Call sites TODAY (READ): `CopilotChatPanelOverlay.kt` —
`pdfProviderAcceptsImages` (U4 extraction-time gate) and the send-path
`provider.supportsImages` checks for image chips + PDF page images;
`ChatAttachmentInjector` wording follows the same booleans.

Plan: ONE seam change — `provider.supportsImages` call sites become
`ModelCapabilities.acceptsImages(model)` lookups (same call sites, same
signatures, zero UI restructuring). `ChatProvider.supportsImages` itself is
NOT deleted (still source 4 + still used when no model is selected). No
protocol/request changes.

- **U4 PDF**: `acceptsImages` gates the scanned-page fallback (unchanged
  behavior); `acceptsPdf` (when known) can later let providers that take PDF
  file parts natively receive the file instead of extracted text — parked
  sub-item G-E-4b, NOT in phase 1.
- **U5 media**: audio/video parts gate on `acceptsAudio`/`acceptsVideo`
  through the same seam — no new gate sites.

### G-E-5. UI (plain wording, per the mandatory UI rule: 8-12dp corners, 12h/10v padding)

- Model picker row: one muted capabilities line under each model name.
- Attach sheet verdict text gains the unknown-capability offer.
- Settings: "Model capabilities" section inside the existing MK endpoint
  registry — per-endpoint, per-model override rows + "reset to defaults".
- Every override row shows WHERE the current value came from
  ("bundled" / "fetched" / "you set this") — LiteLLM priority made visible.

### G-E-6. Update and staleness policy

- Bundled table: release cadence (docs note in the changelog when refreshed).
- Live fetch: rides the existing G-C refetch; values refresh when the user
  refetches models. No background polling, no extra network.
- User overrides: never auto-expire; "reset" is manual and per-model.

### G-E-7. Tests (pure JVM, decision-only — no device)

1. Resolution order: override > live > bundled > provider default, per field.
2. UNKNOWN: `acceptsImages` null → gates OFF; refusal message offers the
   toggle; nothing claimed.
3. Missing bundled row → UNKNOWN, no crash, no guessing by prefix beyond the
   exact designed match rule.
4. Override deletion falls back cleanly to the next source.
5. The U4 send-path re-gate keeps working with an UNKNOWN model (page images
   dropped, text sent, no failed send).

### G-E-8. Risks

- **Wrong bundled data** (stale catalog row says images=no): user toggle
  escapes it; one line to fix. Mitigation: bundled rows copied from LiteLLM,
  which is release-updated, and the row source is visible in the UI.
- **Live fetch misreporting** (endpoint lies): same escape + source label.
- **Scope creep into MK restructure**: G-E only READS the endpoint registry;
  registry CRUD stays the MK plan. Hard line.
- **APK size**: bundled JSON ~100 rows ≈ <30 KB, negligible.
- **UNKNOWN-as-no confuses users** ("but Gemini takes images!"): the toggle
  line + bundled defaults for every common provider make UNKNOWN rare.

### G-E-9. Rollback

The seam is ONE function. Rollback = a `FeatureFlags.capabilityTable` off
switch returning the provider-level constant for every lookup — behavior
byte-identical to today. The bundled asset and overrides stay dormant on disk.

### G-E-10. What changes in the chat send path (summary)

- Nothing in the wire protocol. Nothing in streaming.
- Image chips / scanned-PDF page images: gated by the table instead of the
  provider constant; UNKNOWN = same honest refusal + toggle offer.
- Injection block wording reads the same booleans (already parameterized).
- Model picker + attach sheet + MK settings gain read-only capability lines
  and override rows.

### Phasing (each phase = one CI run, owner go before the next)

- **G-E-a**: data model + resolution + bundled table + tests (no UI, no seam
  change; flag off).
- **G-E-b**: seam switch (send path + picker line) + tests; flag default on
  for the device round, off-switch documented.
- **G-E-c**: override UI in MK settings + attach-sheet unknown-offer.
- **G-E-d** (parked, own decision): `acceptsPdf` native file parts.
