# Project Five — Cross Provider Intelligence

Implementation record. Status vocabulary used throughout: **IMPLEMENTED** (code exists), **LOCALLY
TESTED** (green in this sandbox), **CI-GREEN** (workflow run on the commit), **DEVICE-VERIFIED**
(nothing here is). Following `AGENTS.md`: one stage per commit, dated rows in the changelog, limits
stated at the point they are created.

## Repository rules found before starting

`AGENTS.md` (7,288 lines) is the authority: `## RULES`, `## CURRENT STATE` with a Latest-commit row,
`## KNOWN LIMITATIONS`, then `## CHANGE LOG` entries shaped
`### [YYYY-MM-DD HH:MM WAT] [CI-GREEN <run> @ <sha>] TITLE — detail`. Documentation lives in `docs/`,
with `docs/IMPLEMENTED.md` as the row-per-feature ledger and per-stage reports as separate files. No
new format was invented; this file plus a changelog entry plus an `IMPLEMENTED.md` row follow it.

## Architecture audit (what already exists, so nothing is duplicated)

- `PanelSelection` (`chat/panel/ChatSession.kt`) already carries the provider identity: `providerId`,
  `keySlot` (`ai_GEMINI` style), `modelId`, `baseUrl`, plus the Anthropic and OpenAI-compatible base
  constants. Provider choice is per-selection, not global.
- `CustomEndpointStore` (`chat/CustomEndpointStore.kt`) owns user-added OpenAI-compatible endpoints,
  their manual and live model lists, and read-abort-on-unreadable discipline.
- `SecureTokenStore` holds provider keys by slot; nothing here reads a key.
- `ChatSession` + `AgentToolWire` + `AgentTools.executeTool` already form the single execution gate
  that Project Four hardened. Provider code is upstream of that gate and must stay there.
- No provider capability model existed: nothing knew a context window, a tool-call guarantee or a
  structured-output restriction. That was the honest gap.

## Stage 5.1 — provider and model capability intelligence (IMPLEMENTED, LOCALLY TESTED)

`chat/provider/ProviderCapabilities.kt`

- `ProviderCapability` (tool calls, streaming, structured output, reasoning, vision, cancellation) and
  `ModelLimits` with **nullable** numbers. A limit that has not been checked is `null`; it is never a
  plausible-looking guess, because a selector sizing a request against an invented window is worse
  than one that admits it does not know.
- `ModelProfile` / `ProviderProfile` carry `verified` and per-model `notes`; `restrictions()` surfaces
  them. Declared facts are only for families the picker already ships (Gemini flash/pro, Anthropic
  Opus), and the Anthropic note records that its structured output is a tool call, not a response
  format.
- `ProviderCatalogue.resolve(...)` returns `unknown()` for anything else, which is the truthful answer
  for a user-supplied base URL.
- `TaskRequirement` describes what the turn needs (estimated tokens, tools, structured output, vision).
- `ProviderSelector.assess` returns SUITABLE / RISKY / UNSUITABLE with reasons. Unknown capability is
  RISKY, never UNSUITABLE: an unverified provider has not been proved broken.
- `ProviderSelector.choose` returns **the user's own model**, its assessment, and at most one
  alternative that would fit, marked as offered only. There is no code path that switches the provider;
  a switch would send the same conversation and its credentials somewhere the user did not ask for.

**Limits of this stage.** The catalogue numbers are documentation-sourced declarations, not values read
from a live provider API at runtime; nothing probes them. Selection is an assessment, not a policy
engine, and the alternative is not yet surfaced in the UI. Areas C–G of the brief (tool compatibility,
streaming normalisation, error intelligence, conversation continuity, key protection) are untouched so
far and are the next stages.

**Tests.** 11 cases in `Stage51ProviderCapabilitiesTest`: known models carry both limits; an
uncharacterised endpoint reports unknown rather than guessed; a fitting request is suitable with no
reasons; an oversized request is unusable and states the window; over-80% is risky but allowed; tools on
an unknown model are risky; a verified gap (Anthropic structured output) is unusable; the user's choice
is never switched even when it cannot work; a suitable choice offers no alternative; the offered
alternative genuinely fits and can call tools; restrictions are reportable.

**Results.** 3,219 tests on this branch (main plus Stage 5.1; the Stage 4.8 tests live on `stage-4.8`),
`BackupTarTest.failureMessageCarriesRealTotals` the only failure, unchanged and reproducible on a clean
tree as a sandbox artifact. Dev-debug compile and D8 dex green. Not CI-green: the branch has not been
pushed. Not device-verified.

## Next stage (5.2)

Cross-provider tool compatibility and streaming normalisation: normalise tool definitions and tool-call
event shapes per provider, prove providers cannot bypass `AgentTools.executeTool`, and normalise
finish reasons and partial streams without touching the Project Four delivery guarantees.
