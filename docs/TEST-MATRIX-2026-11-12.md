# Executable test matrix - VN Code Copilot (P1-P8)

The point of this file is regression, not a report. Every row names a test that **runs**, so a future
change can ask "did I break this?" and get an answer instead of re-reading a changelog. Rows are grouped
the way a failure would be diagnosed.

Definitions used everywhere in this repo's docs:

| Term | Means |
|---|---|
| **Unit** | JVM test, no Android, no network |
| **Stub** | JVM test against a loopback double (`LocalHttpStub`, in-memory FS) |
| **CI** | Green in GitHub Actions at the stated commit - the only place the *whole* suite runs |
| **Device** | Needs the owner's phone. **None of these have been run on a device yet.** |

Local full suite at `f5fc3e5`: **2647 tests, 1 failure** (`BackupTarTest.failureMessageCarriesRealTotals`,
root-owned sandbox - L15), 3 skipped. CI: 43286546707 (`6aeb5c8`), 43289533532 (`c94a02d`), 43289925449 (`f5fc3e5`) - all success.

## 1. Core Copilot behaviour

| Feature | Component | Test | Type | Expected behaviour | Security coverage | Result | Evidence |
|---|---|---|---|---|---|---|---|
| Tool dispatch and duplicate call suppression | `agent/ToolDispatchGuard.kt` | `ToolDispatchGuardTest.aRepeatedCompletedCallReturnsTheStoredResultAndDoesNotRunTheBody` | Unit | A completed call replayed in the same turn returns the stored result and does not execute twice | Prevents a duplicate side effect from a repeated tool block | Pass | `6aeb5c8` |
| Dispatch ledger persistence | `agent/ToolCallLedger.kt` | `ToolCallLedgerTest.aFirstExecutionStartsAsUnknownNotSafe` | Unit | An in-flight call is recorded as unknown, never as safe-to-retry | Blocks blind repeat after a crash | Pass | `6aeb5c8` |
| Reconciliation instead of retry | `agent/ToolReconciler.kt` | `ToolReconcilerTest.aWriteWhoseTargetAlreadyHoldsTheContentIsProvenApplied` | Unit | An uncertain write is proven by state, or refused | No duplicate write | Pass | `36e8495` |
| Planning and dependency waves | `agent/ToolPlanner.kt` | `ToolPlannerTest.aWriteNeverSitsInTheSameWaveAsTheReadItNeeds` | Unit | Reads precede the writes that depend on them; independent steps share a wave | Prevents a write racing its own precondition | Pass | `ed8160f` |
| Result verification | `agent/ToolResultCheck.kt` | `ToolResultCheckTest` | Unit | Invalid/anomalous results are flagged rather than passed on | Stops a bad result from seeding a later step | Pass | `ed8160f` |
| Tool context trimming | `agent/ToolContextTrim.kt` | `ToolContextTrimTest.theLatestResultOfAToolSupersedesTheEarliest` | Unit | Stale repeats are dropped, pinned evidence survives | Keeps stale untrusted content from outliving its relevance | Pass | `ed8160f` |
| Per-turn tool budget | `agent/ToolResultBudget.kt` | `ToolResultBudgetTest.aLongChainIsCompressedToTheBudget` | Unit | A long chain is compressed to a finite budget | Bounds how much external text one turn can absorb | Pass | `6aeb5c8` |
| Dynamic tool loading | `chat/panel/DynamicTools.kt` | `DynamicToolsTest.searchRespectsTheMode` | Unit | Search never returns a tool the mode forbids | A hidden write tool cannot be reactivated by search | Pass | `6aeb5c8` |
| Tool relevance and groups | `chat/panel/ToolCatalogue.kt` | `ToolCatalogueTest.everyAdvertisedToolAppearsExactlyOnce` | Unit | Every advertised tool appears once, with a group and a title | - | Pass | `6aeb5c8` |
| Registry integrity | `chat/panel/ToolRegistry.kt` | `ToolRegistryTest.theRegistryCoversEveryRegisteredToolExactlyOnce` | Unit | 120 registered tools, each exactly once, no duplicates | - | Pass | `6aeb5c8` |
| Schema honesty | `chat/panel/ToolRegistry.kt` | `ToolRegistryTest.everySchemaDeclaresTheArgumentsTheExecutorActuallyReads` | Unit | A schema that stops describing the executor's arguments fails | - | Pass | `6aeb5c8` |
| Mode exposure | `chat/panel/ToolRegistry.kt` | `ToolRegistryTest.askExposesTheReadOnlyWaveAndNothingElse` | Unit | ASK exposes reads only; `service_write` is not among them | Write tools unavailable in ask mode | Pass | `6aeb5c8` |
| Finish reasons | `chat/panel/FinishReason.kt` | `FinishReasonTest.anthropicCeiling` | Unit | Provider ceilings normalise to `OUTPUT_LIMIT`; natural stops do not | - | Pass | `6aeb5c8` |
| Output-limit continuation | `chat/panel/Continuation.kt` | `ContinuationTest.aCeilingProducesAContinuationThatForbidsRepetition` | Unit | A continuation is offered, and it forbids repeating tools | - | Pass | `6aeb5c8` |
| Continuation gating | `chat/panel/ContinuationGate.kt` | `ContinuationGateTest` | Unit | One continuation at a time per turn | No duplicate concurrent turns | Pass | `4c6e9be` |
| Partial-result presentation | `chat/panel/ToolResult.kt` | `ToolResultTest.aFailedToolReadsAsAnErrorAndSaysSoInTheTitle` | Unit | A failed call reads as an error; an empty result is not blank | - | Pass | `28553f4` |
| Transcript cap presentation | `chat/panel/ChatPresentationIntegrationTest.kt` | `ChatPresentationIntegrationTest.aMessageCappedByTheTranscriptReadsAsPartialAfterwards` | Unit | A capped message stays labelled partial after reload | - | Pass | `6aeb5c8` |
| Streaming interruption | `chat/panel/ChatSession.kt` | `ChatSessionProviderSwitchTest` | Unit | A provider switch does not splice two streams | - | Pass | `6aeb5c8` |
| Text tool protocol | `chat/panel/AgentToolWire.kt` | `AgentToolWireTest` | Unit | Fragmented/malformed tool markup is parsed or refused, never shown as prose | - | Pass | `6aeb5c8` |

## 2. Documents and archives

| Feature | Component | Test | Type | Expected behaviour | Security coverage | Result | Evidence |
|---|---|---|---|---|---|---|---|
| ZIP listing/creation/extraction | `util/ZipEngine.kt` | `ZipEngineTest.traversalAndAbsoluteNamesAreRefused` | Unit | ZIP-Slip and absolute names refused; extraction stays inside the root | Path containment | Pass | `c5e4b21` |
| ZIP symlink refusal | `util/ZipEngine.kt` | `ZipEngineTest.aSymlinkedDirectoryInsideTheDestinationIsDetected` | Unit | A symlinked destination is detected before writing through it | Symlink escape | Pass | `c5e4b21` |
| TAR and gzip | `agent/ArchiveEngine.kt` | `ArchiveEngineTest.tarGz` | Unit | `.tar`, `.tar.gz`, `.tgz` list and extract through the tar walk | Same containment as ZIP | Pass | `a5410d3` |
| RAR detection | `agent/RarEngine.kt` | `RarEngineTest.rarIsDetectedByItsSignatureNotItsName` | Unit | RAR detected by signature, not extension | Wrong-engine confusion | Pass | `45d13f4` |
| RAR limits | `agent/RarEngine.kt` | `RarEngineTest.rar5AndRar4MarkersBothReadAsRar` | Unit | 4.x/5.x recognised; oversize dictionary and unsupported variants refused with a reason | Decompression-bomb ceiling | Pass | `1953a06` |
| Zstandard stream | `agent/ZstdEngine.kt` | `ZstdEngineTest.aPlainZstIsOneEntryNamedAfterTheFile` | Unit | A plain `.zst` decodes as one bounded stream | 64 MiB window cap | Pass | `1953a06` |
| Tar.zst entry list | `agent/ZstdEngine.kt` | `ZstdEngineTest.tarBytes` | Unit | `.tar.zst` gets a real entry list via the tar walk | Same containment as TAR | Pass | `1953a06` |
| Truncated frame detection | `agent/ZstdEngine.kt` | `ZstdEngineTest` (truncation case) | Unit | A truncated frame is named, not silently ended | Silent-truncation honesty | Pass | `1953a06` |
| PDF/DOCX/PPTX/XLSX text | `agent/docs/DocText.kt` | `DocTextTest.anUnsupportedTypeIsNamedNotDecoded` | Unit | Supported types extract; unknown types are named, never decoded as UTF-8 | - | Pass | `83b062f` |
| Resource ceilings | `util/ZipEngine.kt` | `ToolOutputBoundsTest` | Unit | Entry count, per-entry and total byte ceilings hold | Decompression bomb | Pass | `900240f` |
| Overwrite safety | `util/ZipEngine.kt` | `ZipToolsTest` | Unit | Existing targets require `--force` | No silent overwrite | Pass | `c5e4b21` |
| Real RAR archive | `agent/RarEngine.kt` | *(none)* | - | - | - | **Not tested** | L2 - no legally producible fixture |
| Nested archives | `agent/ArchiveEngine.kt` | *(none)* | - | - | - | **Not tested** | Extract-then-extract is the user's two steps |

## 3. Agent and tool intelligence, memory, continuity

| Feature | Component | Test | Type | Expected behaviour | Security coverage | Result | Evidence |
|---|---|---|---|---|---|---|---|
| Facts store | `agent/facts/FactsStore.kt` | `FactsStoreTest.projectFactWithoutAProjectIsRefused` | Unit | A project fact without a project is refused, not defaulted | No context bleed between projects | Pass | `1c10232` |
| Fact retrieval | `agent/facts/FactRetrieval.kt` | `FactRetrievalTest.anEmptyRequestRetrievesNothingRatherThanEverything` | Unit | Relevance gates retrieval; nothing means nothing | Bounded prompt exposure | Pass | `1c10232` |
| Fact text safety | `agent/facts/FactText.kt` | `FactTextTest` | Unit | Secrets are rejected; facts are not injected as instructions | Prompt injection via memory | Pass | `1c10232` |
| Memory facade | `agent/facts/AgentMemoryFacade.kt` | `AgentMemoryFacadeTest` | Unit | The facade preserves legacy behaviour on the new store | - | Pass | `1c10232` |
| Session handoff | `agent/SessionHandoff.kt` | `SessionHandoffTest.noCredentialEverEntersAHandoff` | Unit | Decisions/constraints survive; credentials never do; approvals are not inherited | Credential leak via context | Pass | `f6c7578` |
| Session deletion | `chat/session/SessionDeletionTombstone.kt` | `SessionDeletionTombstoneTest` | Unit | A deleted session leaves a tombstone, not a resurrection | - | Pass | `6aeb5c8` |
| Session index | `chat/session/SessionIndexStore.kt` | `SessionIndexStoreTest` | Unit | Index survives a corrupted file | - | Pass | `6aeb5c8` |
| MCP lifecycle | `agent/McpLifecycle.kt` | `McpLifecycleTest.blankNameIsRefusedWithAnActionableMessage` | Unit | Bad server names are refused with a reason | Tool-key injection | Pass | `6aeb5c8` |
| MCP picking | `agent/McpToolPicker.kt` | `McpToolPickerTest` | Unit | Ranking plus a mandatory approval before the pick | Untrusted remote tool | Pass | `ed8160f` |
| Package intelligence | `agent/PackageTools.kt` | `PackageToolsTest.aMissingLockfilePathIsRefusedWithTheSupportedList` | Unit | Nine lockfile formats parsed offline; unknown path refused with the supported list | No network on parse | Pass | `6aeb5c8` |
| Research synthesis | `agent/research/ResearchSynthesis.kt` | `ResearchSynthesisTest.aDisputeCarriesBothSidesAndNamesNoWinner` | Unit | Conflicts are surfaced as unresolved, never averaged | No fabricated consensus | Pass | `269f909` |
| Research adapters | `agent/research/` | `AdaptersSafetyTest` | Unit | An adapter cannot widen its own scope | - | Pass | `269f909` |

## 4. Security and integrations

| Feature | Component | Test | Type | Expected behaviour | Security coverage | Result | Evidence |
|---|---|---|---|---|---|---|---|
| Host binding | `agent/CredentialHostGuard.kt` | `CredentialHostGuardTest.anAbsoluteUrlToAnotherHostIsRefused` | Unit | A credential reaches only its host, on label boundaries | SSRF / credential steering | Pass | `bf047bf` |
| Redirect refusal | `agent/LocalConnectorCall.kt` | `ConnectorSafetyTest` | Unit | A 3xx is reported, never followed | Credential exfiltration via redirect | Pass | `10b2380` |
| MCP forced approval | `agent/AgentFlowGate.kt` | `McpApprovalForcedTest.anMcpToolAsksEvenWhenEverythingElseIsAllowed` | Unit | MCP asks even under AUTO_ALL | Remote-code consent | Pass | `1182ff6` |
| `service_write` approval | `agent/AgentFlowGate.kt` | `AgentFlowGateScopeTest.serviceWriteAsksEvenWhenAutoApprovedAndSessionAllowed` | Unit | A write asks at every level; "always allow" cannot silence it | Consent for external mutation | Pass | this commit |
| Service request planning | `agent/ServiceApi.kt` | `ServiceApiTest.aWriteThroughTheReadToolIsRefusedAndViceVersa` | Unit | Read/write split is structural | Cannot smuggle a write | Pass | `6aeb5c8` |
| URL / path placeholders | `agent/ServiceApi.kt` | `ServiceApiTest.pathValuesCannotEscapeTheOperation` | Unit | `..`, absolute URLs and query smuggling refused | Path traversal, host escape | Pass | `6aeb5c8` |
| Query encoding | `agent/ServiceApi.kt` | `ServiceApiTest.queryValuesAreEncodedRatherThanInjected` | Unit | `a&admin=true` cannot add a parameter | Parameter injection | Pass | `6aeb5c8` |
| Response limits | `agent/ServiceApi.kt` | `ServiceApiTest.anOversizedResponseIsTruncatedWithANotice` | Stub | 256 KB ceiling with an explicit notice | Prompt flooding | Pass | `6aeb5c8` |
| Token scrubbing | `agent/ServiceApi.kt` | `ServiceApiTest.aServiceThatEchoesTheTokenDoesNotGetItIntoTheTranscript` | Stub | An echoed credential becomes `***` | Credential leak via response | Pass | `6aeb5c8` |
| No egress on refusal | `agent/ServiceApi.kt` | `ServiceApiTest.aRefusedPlanNeverOpensASocket` | Stub | A refused plan never opens a socket | - | Pass | `6aeb5c8` |
| Rate limiting | `agent/ServiceApi.kt` | `ServiceApiTest.aSecondCallInTheSameInstantIsRefused` | Unit | 400 ms + 40/min per service; refused plans cost nothing | Runaway loop | Pass | `6aeb5c8` |
| Timeouts | `agent/ServiceApi.kt` | `ServiceApiTest.aSlowServiceTimesOutWithoutBeingRetried` | Stub | 10s/20s, reported, never auto-retried | - | Pass | `6aeb5c8` |
| Untrusted response labelling | `agent/ServiceApi.kt` | `ServiceApiTest.aSuccessfulReadIsLabelledAsUntrusted` | Stub | Output is labelled data, ANSI stripped | Prompt injection via response | Pass | `6aeb5c8` |
| Credential redaction | `agent/ServiceApi.kt` | `ServiceApiTest.aPlanNeverPrintsItsCredential` | Unit | `Plan`/`Secret` print `***` | Log/transcript leak | Pass | `6aeb5c8` |
| Credential migration | `chat/keys/StoredCredentialsMigration.kt` | `StoredCredentialsMigrationTest` | Unit | Credentials migrate without duplication | - | Pass | `6aeb5c8` |
| Service auth header per style | `agent/ServiceApi.kt` | `ServiceApiTest.eachServiceBuildsItsOwnAuthHeader` | Unit | Bearer for GitHub/Bitbucket, `PRIVATE-TOKEN` for GitLab, `token` for Gitea | Credential shaped by the service definition, never by the model | Pass | `f5fc3e5` |
| Jira Basic auth | `agent/ServiceApi.kt` | `ServiceApiTest.jiraUsesBasicAuthAndNeedsBothSecrets` | Unit | Both secrets required; sends base64(email:token), scrubs the raw token | No half-configured credential sent | Pass | `f5fc3e5` |
| Self-hosted base | `agent/ServiceApi.kt` | `ServiceApiTest.aSelfHostedServiceWithoutBaseIsRefusedByName` | Unit | Gitea without `gitea_base_url` refused by name; a plain-http base refused | https-only for user-supplied hosts | Pass | `f5fc3e5` |
| JQL encoding | `agent/ServiceApi.kt` | `ServiceApiTest.jqlIsEncodedSoItCannotAddAParameter` | Unit | `=` and `"` allowed, percent-encoded; no `&` can be introduced | Parameter injection | Pass | `f5fc3e5` |
| CI operations | `agent/ServiceApi.kt` | `ServiceApiTest.theCiOperationsAreClassifiedAndBodylessOnesPlan` | Unit | cancel/retry are writes, not readable through `service_read`; they plan with no body | Consent for a run that spends minutes | Pass | `f5fc3e5` |
| Registry invariants | `agent/ServiceApi.kt` | `ServiceApiTest.everyOperationIsWellFormed` | Unit | Unique ids, allowlisted methods, relative paths, https static bases, a baseSecret when the base is blank | A careless addition cannot introduce a plain host | Pass | `f5fc3e5` |
| Placeholder names | `agent/ServiceApi.kt` | `ServiceApiTest.everyPlaceholderInATemplateIsAValidName` | Unit | Every `{name}` in every template is well-formed | - | Pass | `f5fc3e5` |
| Live GitHub / GitLab / Bitbucket / Gitea / Jira | - | *(none)* | - | - | - | **Not tested** | L18 - no live credentials |

## Maintainability rules for this file

1. A row is added when a test is added, in the same commit, not in a later documentation pass.
2. `Not tested` rows stay. They are the honest half of the matrix and the reason L2 and L18 exist.
3. No row may say "CI" unless the run ID and verdict are named in Evidence.
4. Never write "verified" for stub tests.

## Items 35-37 (added 2026-11-12)

| Feature | Test class | Cases | Evidence |
|---|---|---|---|
| Context extraction and provenance | `ContextDigestTest` | 12 | local + CI `43296311098` |
| Handoff evidence fields | `SessionHandoffTest` | +4 | local + CI `43296311098` |
| Turn-level retry policy | `ProviderRetryTest` | 9 | local + CI `43305393117` |
| Retry verdict text | `ChatFailureAdviceTest` | 4 | local + CI `43305393117` |
| Failure card stays single and last | `ChatSessionMappingTest`, `KeyFailoverMappingTest` | 7 | CI `43305393117` (caught the regression) |
| Credential-free redirect / L20 | `ServiceApiTest` | 36 | local + CI `43305393117`; stub only |

/L15 `BackupTarTest.failureMessageCarriesRealTotals` remains the single known sandbox failure and is
unmodified.
