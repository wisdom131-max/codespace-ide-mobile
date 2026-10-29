# Chat panel audit: archive vs rebuilt VN Code chat

Date: 2026-10-29 (v2, supersedes the v1 report of the same day).
Status: ANALYSIS ONLY. No application source changed, no UI change, no build started, nothing committed.
Written for the owner's decision on what to build next.

Evidence base, inspected directly in this session:

- Archived reference repo: `archive/vn-code-archive` (commit `ab5dc6c`, tag `chat-archive-2026-10-26`).
  78 `chat`/`capabilities`/`providers` Kotlin files, 10 chat UI files under `src/ui/screens`, and
  7 topic docs (`docs/providers.md`, `send-loop.md`, `attachments.md`, `failover.md`,
  `model-catalog.md`, `chat-ui.md`, `feature-map.md`).
- Rebuilt app: `android/app/src/main/java/com/codespace/ide/chat/**` (32 files, 5,643 lines) and
  `android/engine/src/main/kotlin/com/codespace/ide/engine/**` (27 files), at HEAD `0f10b39`
  (docs commit on top of `362e080`).
- Repo docs: `docs/ROADMAP.md`, `docs/chat-p0-gaps.md`, `docs/u2-feature-inventory.md`,
  `docs/checkpoint-2-sheet.md`, `docs/vscode-refs/`.
- Prior research: `mirrors/archive-update/docs/feature-map.md` (Copilot Chat complete map with P0..P3
  tiers), `mirrors/archive-update/docs/dependency-map.md` (what was removed and what was supposed to
  survive), `REBUILD-NOTES.md`, `incoming_files/767a6b6cd_VN_Code_Copilot_UI_Agent_Improvements.md`,
  `incoming_files/3375f9c60_Advisor_AI_Exhaustive_Handoff.md`.
- GitHub Actions API, per-commit run lookup (run numbers and conclusions are quoted in section 2).

## 1. Executive summary

The rebuild has a **stronger engine and a much more honest failure taxonomy** than the archive, and
its agent path is now real end to end (tools, approvals, terminal record, Explorer reveal, Files
Changed with Keep/Undo). It is still **narrower as a product surface**, and it has three defects that
the previous report and the roadmap both missed:

1. **The transcript never auto-scrolls.** `ChatPanelScreen` creates `rememberScrollState()` and never
   reads or drives it (verified: zero `scroll.` calls in the file). A streamed reply is appended below
   the viewport; the user has to drag the list up to read it. This is the single most damaging current
   defect for normal use, and it is cheap to fix.
2. **Nothing tells the model which project is open.** `ChatSession` builds the system prompt from
   `ChatModePrompts.instruction(mode)` plus the tool protocol, and nothing else. No workspace roots,
   no active file, no instruction files. `AutoInstructionsProvider` (AGENTS.md style instructions) is
   wired only into the HTTP agent server, not into chat. This contradicts the owner-approved V0-f
   decision that the AI receives structured multi-root context and never infers `pwd`.
3. **EDIT mode promises a review-and-accept flow that has no producer.** The EDIT system prompt tells
   the model "nothing is applied until the user accepts it", and `PendingChangesStore` plus
   `AiReviewStrip` still exist, but `PendingChangesStore.stage()` has **zero callers** anywhere in the
   app. In AGENT mode writes go straight to disk behind the approval gate; in EDIT mode the model is
   instructed to hand-format diffs into the chat. The staged-review seam is dead code.

Two more structural gaps dominate the feature comparison: **no conversation persistence at all**
(restart loses everything), and **document extraction was deleted while its dependencies still
ship** (`pdfbox-android` and `jtokkit` are still declared in `app/build.gradle.kts`, lines 248/234,
with no consumer).

Corrections to the v1 report, both verified this session:

- The **Thinking row is reachable**. The panel, not the session, appends it
  (`ChatPanelScreen.kt:116`, gated on `streaming && firstDeltaMs == 0L` with a 1s ticker at lines
  83-86). Unreachable rows are **four**, not five: `RateLimited`, `NoCredits`, `Offline`,
  `TrimmedHistory`, each produced only by the DEBUG design gallery
  (`ChatDesignGalleryActivity.kt:66-70`).
- Three of the four "shipped" U6 features **first went red and needed a fix commit**. The run numbers
  quoted in the roadmap are the fix runs (section 2 has the exact mapping).

## 2. Verified current baseline

### 2.1 Commits and CI, verified through the Actions API per commit

| Item | Feature commit | Its own run | Fix commit | GREEN run |
|---|---|---|---|---|
| U6-c agent commands visible in terminal | `46d1b61` | 3446 failure | `bc969c8` | **3447 success** |
| U6-d Explorer reveal | `457a84f` | **3448 success** | (none) | **3448** |
| U6-e Files Changed row | `ca5f811` | 3449 failure | `95aefe5` | **3450 success** |
| U6-f visible approval row | `8e4d185` | 3451 failure | `d384d60` | **3452 success** |
| Roadmap continuity docs | `362e080` | **3453 success** | (none) | **3453** |

`362e080` is the docs commit the prompt refers to as "362e080 roadmap update"; the roadmap file is
`docs/ROADMAP.md`, and it does carry U6-c..f with commits and runs. `0f10b39` (the chat plan doc) sits
on top and has **no CI run**, so it is either unpushed or docs-only and untriggered. Nothing beyond
the roadmap rows supersedes these features.

### 2.2 What the repository actually contains for each reported feature

| Reported | Present in code | Traced execution path | Verdict |
|---|---|---|---|
| U6-c terminal visibility | `agent/AgentTerminalFeed.kt`, `ui/panes/TerminalAgentRunStrip.kt`, mounted at `TerminalPane.kt:2974` | `AgentTools.runCommand` calls `begin()` before `ProotInstaller.execOnce`, `finish(out)` after, `finish(failed=true)` in the catch and rethrows (`AgentTools.kt:263-276`) | **Shipped, with two gaps** (7.1) |
| U6-d Explorer reveal | `util/ExplorerReveal.kt`, `ui/panes/ExplorerRevealEffect.kt` | effect drains the queue, expands ancestors, consumes; writes call it after `writeText` returns | **Shipped, correct** |
| U6-e Files Changed | `agent/AgentWriteJournal.kt`, `chat/panel/ChatFilesChangedRow.kt` | `ChatSession.runTools` opens one set per tool round (`ChatSession.kt:420`); row mounted only in AGENT mode and only when `sets` is non-empty | **Shipped, with bounded-history caveat** (7.3) |
| U6-f approval row | `chat/panel/ChatApprovalCard.kt`, `ApprovalPresentation.kt`, `agent/AgentFlowGate.kt`, `agent/ChatPermissionStore.kt` | card renders `AgentFlowGate.pending`; resolution goes through the same `awaitApproval` the tool loop awaits; scopes ONCE/SESSION/ALWAYS; duplicate and late taps inert | **Shipped, security intact** |
| Tests for the batch | `AgentTerminalFeedTest`, `AgentWriteJournalTest`, `AgentFlowGateScopeTest` under `app/src/test/.../agent/` | JVM, no Android deps | Present |

A green run proves compilation and the JVM tests, not device behaviour. The device round is
outstanding by the batching rule and is **not** repeated here.

## 3. Complete archived feature inventory

Grouped by capability, from the archive source. Line counts are `wc -l`. Where a file's behaviour
matters for a recommendation, the mechanism is summarised.

### 3.1 Conversation and session management

| Capability | Archive evidence | Mechanism |
|---|---|---|
| Session persistence | `CopilotChatPanelOverlay.kt:142-559` | `saveSessions`/`loadSessions` per session key `session_v1_<id>`, `msgToJson`/`msgFromJson`, roster with `relativeTime`, `scrubSessionsOnce` redaction pass, `sessionCopiesWithEviction` |
| Sessions roster | overlay (roster UI + `sessions` state list) | multiple named sessions, switch, rename via `/rename`, delete |
| Per-session mode + custom mode | `ChatSession` model in overlay, `CustomModeStore.kt` (126) | session carries `mode` and `customModeId`; custom modes loaded from `.codespace/modes/*.agent.md` with frontmatter parsing |
| Plan state persistence | `ChatPlanStore.kt` (215) | `plans_v1` prefs, typed mutations with rollback, corruption quarantine |
| Input history | `ChatInputHistory.kt` (102) | per-project, MAX 100, consecutive-dedupe, typed `push` returning success, corrupt payload quarantined to `.corrupt-<ts>` |
| Retry and export | `ChatRetryExport.kt` (130) | retry bar plus `writeSessionMarkdown` to `<project>/.codespace/exports/chat-<ts>.md` with typed `ChatExportResult` (Ok / NoProject / WriteFailed) |
| Message rating | overlay (`rateMessage`, `ChatMessageActions`) | thumbs per assistant message, persisted with the session |
| Find in transcript | overlay `ChatFindBar` (line 2471) | live filter with match count, closes clean |
| Empty state | overlay | bot icon, "Ask me anything about your code", mode cards |
| Follow-up suggestions | overlay `ChatFollowUpChips` + `suggestedFollowUps` | deterministic chips from mode/pending-plan state, no model call |

### 3.2 Composer and context

| Capability | Archive evidence | Mechanism |
|---|---|---|
| Composer structure | `ChatComposerMobile.kt` (209) | SPEC-1..5: toolbar row above the field, `imePadding` + `navigationBarsPadding`, 36dp filled send, 16dp icons, 12dp radii |
| Slash commands | `ChatSlashCommands.kt` (72) | `/clear /new /rename /models /skills /tools /help`, pure parser, `/help` and `/tools` render as markdown |
| Attach sheet | `ui/screens/ChatAttachPicker.kt` (951) | in-project picker with search, `walkProjectFiles` capped at 400 with an honest `truncated` flag, `searchProjectFiles`, chips row `ChatAttachmentChips` |
| System file import | `ChatStagedFileImport.kt` (128) | SAF `OPEN_DOCUMENT`, name sanitising, private `chat-staging` dir, 10 MB streamed cap, partial-copy cleanup, 24h sweep |
| Attachment intake | `AttachmentIntake.kt` (111) | verdict + extraction, 5s timeout, 10 MB source cap, 60k snapshot cap, 12k per-file send cap |
| Notices | `AttachmentSheetNotices.kt` (52) | pure `sendableChars` / `counterLine` / `sizeHeaderChars`: never lies about truncation, chars not bytes |
| PDF | `PdfAttachmentExtractor.kt` (374) + `PdfExtractionGate.kt` (132) | text layer plus up to 3 page images, dedicated single-thread executor, abandoned-job flag, Busy refusal while stuck |
| Office documents | `DocumentTextExtractor.kt` (773) | docx/xlsx/pptx/odt/html/csv/rtf via linear XML scan, caps, OOM caught |
| Archives | `ArchiveEntryLister.kt` (234) + `SafeZipReader.kt` (122) | 500-entry listing, zip-bomb ratio guard, entry names never used as paths |
| Images and audio | `ChatImageAttachments.kt` (261) | image 5 MB / audio 10 MB caps, per-vendor multimodal parts, images never enter the text path, copies into private `chat-images/` |
| Context sources | `SearchResultsAttach.kt` (65), `ChatScreenshotAttach.kt` (80), `ChatContextSources.kt` (`DebugConsoleCapture`) | attach search results (staged-content aware, 4000-char cap), screenshots, terminal output |
| Attachments in history | `ChatAttachment.kt` (296) + injector | ATTACHED CONTEXT block on the LAST user message only, restored messages re-inject their own, per-message 24k cap, 8-attachment persistence cap |
| Implicit context toggle | `ChatComposerMobile` (`implicitCtxOn`) | user can switch automatic project context off |

### 3.3 Providers, models, keys

| Capability | Archive evidence | Mechanism |
|---|---|---|
| Provider abstraction | `ChatProvider.kt` (187), `ChatProviderRegistry.kt` (33), `ProviderBootstrap.kt` (59) | interface with capability flags, `complete`, `completeStreaming`, `countTokens`, `fetchModelInfos` |
| Vendor adapters | `providers/*` (Gemini 285, Anthropic 191, OpenAI-compatible transport 389, Custom 136, OpenRouter 65, xAI 51, DeepSeek 46) | per-vendor wire shapes, Gemini bounded `thinkingConfig`, SSE |
| Model selection | `ChatModelSelection.kt` (121), `ChatModelMenuButton.kt` (310) | `providerId:model` strings, AUTO mode, **pinned models** (star, sorted first), **per-mode selection** (`getForMode`), custom endpoint groups labelled "From server (<age>)" vs "Manual", inline add-model, Refetch |
| Live model lists | `ProviderModelCache.kt` (84) | cached `/models` fetch merged with bundled defaults, stale-cache fallback |
| Catalog and capabilities | `ModelCatalog.kt` (157), `capabilities/*` (table 160, hints 109, overrides 148, flags 48, gate 64, seam 96) | per-model context/token limits, image/PDF/audio/tools flags, user overrides, feature-flagged gate with byte-identical OFF path |
| Keys | `ChatKeyPool.kt` (171), `ChatKeyFailover.kt` (143), `AiKeyFormats.kt` (57), `KeyProbe.kt` (163) | ordered key slots with `AUTH_COOLDOWN_MS`, failover classification, key-format validation, **key probe with three distinct verdicts** (ACCEPTED / REJECTED / NOT VERIFIED, HF whoami-v2 plus anonymous control call) |
| 402 policy | `Chat402FailoverSetting.kt` (43) | opt-in next-key on out-of-credits, default OFF, explicit wording that credits are per account |
| Reasoning budget | `GeminiThinkingBudgetSetting.kt` (60) | bounded thinking budget setting |

### 3.4 Agent, tools, editing

| Capability | Archive evidence | Mechanism |
|---|---|---|
| Tool loop | `send-loop.md`, overlay `chat()` | agentic iteration, tool-call cap, `ToolExecutionEvidence` (29) so a model sentence is never an execution receipt |
| Native tool protocol | `NativeToolProtocol.kt` (125) | parses native `tool_calls` **and** text tags, preserves call ids, malformed arguments fail visibly |
| Plan review | `ChatPlanStore.kt` (215) | model writes the full step list, turn stops, card offers Approve / Revise / Reject, statuses update as the todo list |
| Staged edits | `PendingChangesStore.kt` (458) + `AiReviewStrip` | stage/apply/discard overlay per file, drift detection, undo gate in the editor |
| Skills | `SkillsCatalog.kt` (217) | `/skills` prefills input with a skill's context for review before sending |
| Commit messages | `ScmCommitMessageService.kt` (86) | AI commit message for Source Control, consumed by `SourceControlPane` |

### 3.5 Telemetry, safety, reliability

| Capability | Archive evidence | Mechanism |
|---|---|---|
| Token accounting | `TokenCounter.kt` (114) | jtokkit BPE where available, real `countTokens` endpoints for Gemini/Anthropic, chars/4 fallback, context-gauge readout |
| Timing telemetry | `ChatTimingLog.kt` (63) | phase/provider/model/ms/bytes/status/TTFB only; the signature makes prompt text impossible to log |
| Prefs robustness | `ChatPrefsBackend.kt` (44) | `commit()`-based typed writes, honest failure, JVM test seam |
| Export redaction | `AttachmentSecrets.exportRedact` | redaction applied to exported/terminal output too |
| Capability seam | `ChatCapabilityGate`/`Seam` | resolution order user override > live metadata > bundled table, UNKNOWN falls back to today's answer |

## 4. Current versus archive comparison

Classification: **Complete**, **Partial**, **Missing**, **Defective**, **Intentionally excluded**,
**Unverified**. "Different design" notes where the rebuild solves the same problem another way and
should not be re-restored.

### 4.1 Conversation and session

| Feature | Archive | Rebuild | Class | Evidence |
|---|---|---|---|---|
| Send and stream | yes | yes, SSE deltas, cursor, first-delta timestamp | Complete | `ChatSession.onEvent`, `ChatCards.StreamingText` |
| Cancel | yes | yes (`cancel()` cancels the send job) | Partial: a blocking `execOnce` tool call is not interrupted (7.1) | `ChatSession.cancel` |
| Retry | yes, restores attachments | yes, `retry()` reuses `lastStaged` | Complete | `ChatSession.retry`, `lastStaged` |
| Thinking indicator with elapsed seconds | yes | yes, panel-level ticker | Complete (v1 report was wrong) | `ChatPanelScreen.kt:83-86,116` |
| Conversation persistence | sessions file + roster | none, `ChatSessionManager` holds a `HashMap` | **Missing** | `ChatSession.kt:40,476-480` |
| Multiple sessions and switch | yes | no | **Missing** | same |
| Session rename | `/rename` | no | **Missing** | - |
| Message rating | thumbs persisted | none | **Missing** | `thumbs` greps 0 |
| Export session | markdown, typed result, redacted | none | **Missing** | `ChatExport` greps 0 |
| Find in transcript | yes | none | **Missing** | `findQuery` only editor paths |
| Empty state | bot icon, hint, mode cards | blank area | **Missing** | no `rows.isEmpty` branch |
| Follow-up chips | yes | none | **Missing** | `FollowUp` greps 0 |
| Queue while streaming | FIFO, drains on completion | none, `send()` returns early | **Missing** (deliberate, documented in `ChatSendState.kt:10`) | `ChatSession.kt:201` |
| Input history | per-project, 100, quarantine | none | **Missing** | `ChatInputHistory` 0 |
| Slash commands | 7 commands | none | **Missing** | `SlashCommand` 0 |
| Auto-scroll to newest row | archive used `LazyColumn` + list state | **`rememberScrollState()` never driven** | **Defective** | `ChatPanelScreen.kt:80,109`; zero `scroll.` uses |
| Transcript virtualization | `LazyColumn` + `itemsIndexed` | `Column` + `verticalScroll` | **Defective** (perf on long threads) | `ChatListRenderer.kt:34-40` |
| Accessibility labels | some | zero `contentDescription` in chat UI | **Missing** | grep count 0 |

### 4.2 Composer and context

| Feature | Archive | Rebuild | Class | Evidence |
|---|---|---|---|---|
| Multiline with max height and internal scroll | yes | yes, `heightIn` + `maxLines` + scroll | Complete | `ChatInputComposer.kt:191-209` |
| IME handling | `imePadding` + `navigationBarsPadding` in composer | branch on `WindowInsets.ime` only; no inset padding in panel | **Unverified**: likely host-level `adjustResize`, not proven | `ChatInputComposer.kt:82-85` |
| Blue border on text | archive neutral flat field | yes, blue when text present, neutral when empty | Complete (better) | `ChatComposerBorder.kt` |
| Single file attach | picker (project walk + system import) | system `GetContent("*/*")`, one file | Partial | `ChatPanelScreen.kt:158` |
| In-project file picker with search, 400 cap, honest truncation | yes | none | **Missing** | - |
| System file import staging (private dir, sweeps, partial cleanup) | yes | reads URI inline, no staging, no sweep | Partial | `ChatPanelScreen.readAttachment` |
| Attachment sheet with notices | yes | none | **Missing** | - |
| PDF/document/archive extraction | yes (three extractors + gates) | none | **Missing** (owner paused; `pdfbox` still ships) | `app/build.gradle.kts:248` |
| Images | per-vendor parts, 5 MB, private copies | per-vendor parts, capability-gated, 10 MB intake | Complete | `OpenAIAdapter.kt:61`, `GeminiAdapter.kt:62`, `AnthropicAdapter.kt:71` |
| Audio | MP3/WAV, per-vendor gates | none | **Missing** | no `input_audio` in engine |
| Attachment caps and redaction | send caps, notices, credential refusal | 10 MB cap, credential-name refusal, no send-cap notice | Partial | `ChatSession.addPending` |
| Attachments persisted in history | text plus stored copies with eviction | text only (deliberate redaction rule) | **Intentionally excluded** | `ChatSession.kt:245-247` |
| Context sub-kinds (#selection, #problems, terminal output, search results, screenshots) | yes | none | **Missing** | `ChatContextSources` absent |
| Implicit project context toggle | yes | n/a | **Missing** (no context at all, 1.2) | - |
| Slash-command autocomplete | parser only | none | **Missing** | - |

### 4.3 Providers, models, keys

| Feature | Archive | Rebuild | Class | Evidence |
|---|---|---|---|---|
| Provider breadth | 7 adapters | 3 adapters + 10 presets | Complete via different design (OpenAI-compatible transport) | `ProviderPresets.kt` |
| Custom endpoints | registry, per-endpoint keys, model entries | `CustomEndpointStore` + `CustomEndpointEditor` + live fetch | Complete | `CustomEndpointStore.kt` |
| Key entry and slots | pool, formats, probe | `KeysManagerDialog`, `SlotNaming`, presence-only read | Partial: no format validation, **no key probe** | `KeyProbe` absent |
| Multi-key failover | per-key cooldown pool, 429 ladder, 402 opt-in | single pass, opt-in, visible note, no cooldown memory | Partial | `ChatSession.kt:380-395`; no `cooldown` anywhere |
| Searchable model picker | `ChatModelMenuButton` + pinned + per-mode | `ModelPickerSpec` written, **zero callers**; `ProviderModelDialog` in use | **Missing in UI** (spec exists) | `ModelPickerSpec.kt:88`; `ChatPanelScreen.kt:190` |
| Pinned models | yes | none | **Missing** | - |
| Per-mode model memory | `getForMode` | none | **Missing** | - |
| Live model list in picker | cached merge with age label | fetched only inside `CustomEndpointEditor` | Partial | `ModelListFetcher` callers |
| Capability table | table + hints + overrides + gate | `ModelCatalog` + `resolveCapability` + payload budget | Partial (no user overrides, no audio flag) | `ModelCatalog.kt:30-72` |
| Reasoning control | ledger per provider + setting | per-provider `reasoningKind`, fixed 1024 for Gemini/Anthropic | Partial (not user-adjustable) | `ChatSession.kt:225` |
| 402 handling | dedicated card + opt-in next key | taxonomy + opt-in next key, but **NoCredits card unreachable** | Partial | 8.2 |
| 429 handling | Retry-After + 2 same-key retries, visible countdown | taxonomy parses Retry-After, **no retry, card unreachable** | Partial | 8.3 |
| Key probe verdicts | three distinct verdicts | none | **Missing** | - |

### 4.4 Tools, agent, editing

| Feature | Archive | Rebuild | Class | Evidence |
|---|---|---|---|---|
| Tool calling | text tags + native | text tags only, mode-filtered, round cap | Complete via different design (documented decision) | `AgentToolWire.kt` |
| Tool inventory | 30 + MCP | 32 + MCP bridge | Complete | `AgentTools.BUILTIN_TOOL_NAMES` |
| Per-call approval | `AgentFlowGate` | same gate, card with scopes, session scope in memory | Complete (better UI) | `ChatApprovalCard`, `AgentFlowGateScopeTest` |
| Agent command visibility | terminal typing (re-executing risk) | real record strip from `AgentTerminalFeed` | Complete via different design (correct choice) | 7.1 |
| Agent output attachment to chat | `TerminalAiBridge` | `TerminalAiBridge.recordRun`, attachable | Complete | `AgentTools.kt:277` |
| Explorer refresh and reveal | FsChangeNotifier + reveal | same, queue-drained, ancestors expanded | Complete | `ExplorerReveal.kt` |
| Files Changed with Keep/Undo | `PendingChangesStore` staging + review strip | `AgentWriteJournal`, per-operation set, drift-safe | Complete via different design | 7.3 |
| Per-file accept/reject | yes | per-operation only | Partial | 7.3 |
| Staged edits (`stage()` producer) | yes, chat staged proposals | **no caller** for `stage()`; EDIT prompt still promises it | **Defective** | zero `stage(` call sites |
| Undo of an earlier operation after 3 more | retain old sets | oldest set dropped at `MAX_SETS` | Partial (bounded) | `AgentWriteJournal.kt:49,64` |
| Plan review card | full | none; `plan` tool returns a stub string | **Missing** | `AgentTools` plan branch |
| Progress/todo status | plan statuses | InfoNote step labels only | Partial | `runTools` |
| Skills | `/skills` | none | **Missing** | - |
| AI commit messages | `ScmCommitMessageService` | none (roadmap lists it as pending) | **Missing** | `SourceControlPane` has no generator |
| Tool evidence guard | `ToolExecutionEvidence` | InfoNote lines state Done/Failed from the real output | Complete via different design | `runTools` |
| Cancellation of a running tool | - | `AgentTerminalFeed.cancel` never called; blocking exec not interruptible | **Partial/Defective** | 7.1 |

### 4.5 Reliability, telemetry, security

| Feature | Archive | Rebuild | Class | Evidence |
|---|---|---|---|---|
| Error taxonomy | classifyHttpError with 402 split | richer sealed `ChatError` + `ErrorTaxonomy` | Complete (better) | `ChatError.kt` |
| Error cards shown | yes | only `Failed` and `OverBudget` reachable | Partial | 8.2 |
| Payload budget | `HistoryBudget` (60k chars) | `HistoryBudget` modes + `BudgetGuard` + per-send override | Complete (better) | `engine/HistoryBudget.kt` |
| Honest trim reporting | row with dropped count | drop is silent, no count returned | Partial | 8.4 |
| Token accounting and gauge | yes | none (`jtokkit` still declared) | **Missing** | `app/build.gradle.kts:234` |
| Timing telemetry | sanitized by signature | none | **Missing** | `ChatTimingLog` absent |
| Prefs robustness | commit-based, quarantine | plain SharedPreferences writes | Partial | `ChatSettingsPrefs` |
| Credential redaction | secrets scrub, export redact | `AttachmentSecrets`, credential-name refusal, `OutputExportRedactionTest` | Complete | `AttachmentSecrets.kt` |
| Project context in prompt | workspace roots + instructions | none | **Missing/regression** | 1.2, 8.5 |
| Instructions files (AGENTS.md) | yes | `AutoInstructionsProvider` exists, wired only to `AgentApiServer` | Partial | `AgentApiServer.kt:236` |
| Headless tool route | - | HTTP `/tool/*` gated by project trust, **not** by `AgentFlowGate`/`ChatPermissionStore` | Documented boundary (7.5) | `AgentApiServer.kt:196-226` |
| Long thread performance | lazy list | eager `Column` | Defective | 4.1 |

## 5. Findings the previous report missed or got wrong

| # | Finding | Why it was missed | Class |
|---|---|---|---|
| 1 | No auto-scroll in the transcript (`rememberScrollState` never driven) | v1 read row models, not layout modifiers | Defective |
| 2 | No workspace/project context in the system prompt; `AutoInstructionsProvider` not wired to chat | v1 compared chat files, not prompt composition | Missing, contradicts V0-f |
| 3 | EDIT mode's accept-before-apply flow has no producer (`stage()` zero callers) | v1 treated `PendingChangesStore` as "present" | Defective |
| 4 | Thinking row is reachable; the unreachable set is 4 rows, not 5 | v1 grepped producers only in `ChatSession` | Correction |
| 5 | Three of four U6 rows needed fix commits; "shipped GREEN" runs are fix runs | v1 trusted commit messages | Correction |
| 6 | No audio attachment path (archive had MP3/WAV with per-vendor gates) | v1 checked images only | Missing |
| 7 | No per-key cooldown state, so a rejected key is retried first on the next send | v1 credited "failover" wholesale | Partial |
| 8 | `U6-c`: `AgentTerminalFeed.cancel()` has no caller; `exitCode` never passed, so a non-zero exit reads "done" in the strip while the output says "Exit code N" | v1 accepted the commit message | Defective |
| 9 | Transcript is a plain `Column`, not a lazy list | v1 reviewed data flow only | Defective |
| 10 | Zero `contentDescription` in the chat UI | v1 had no a11y pass | Missing |
| 11 | `pdfbox-android` **and** `jtokkit` still declared with no consumer (shipped dead weight) | v1 found pdfbox only | Partial |
| 12 | No key probe, no key-format validation | v1 listed keys as "parity" | Partial |
| 13 | AI commit-message generation was lost with the chat layer | v1 scoped to the panel | Missing |
| 14 | Skills attach (`/skills`) gone with no replacement | v1 had no skills row | Missing |
| 15 | Headless `/tool/*` route bypasses the per-call gate (trust only) | v1 assumed the gate covered all execution | Documented boundary |
| 16 | `AgentWriteJournal.activeSetId()` can attach an out-of-band write (for example an HTTP tool call) to the chat's unsettled set | v1 read `beginOperation` only | Risk |

The rest of v1 stands as written: persistence, unreachable rows, dead extractors, unwired picker,
markdown, context sub-kinds, input affordances, export/rating, per-file review.

## 6. Prior research, integrated

Sources: `mirrors/archive-update/docs/feature-map.md` (complete Copilot Chat map, P0..P3),
`dependency-map.md`, the advisor handoff, `docs/u2-feature-inventory.md`, and the owner's own
Copilot UI/agent brief. Only findings that change a decision are listed.

**Tiering that still governs** (feature map): transcript, sessions/history, modes, model picker plus
BYOK, tool picker and confirmations, context attachments, slash commands, diff review, error/quota
states and usage/budget are **P0**. Checkpoints/undo, instructions files, welcome state and
accessibility are **P1**. Inline chat and keyboard shortcuts are P2/P3.

Consequences for this audit:

1. **Virtualized rows are named P0 in that map** ("rebuild adds virtualized rows"). The rebuild used
   an eager `Column`, so that P0 row is not met even though `docs/chat-p0-gaps.md` marks "Chat view
   (transcript)" CLOSED. That file's closure is defensible for *content*, not for *list implementation*.
2. `docs/chat-p0-gaps.md` says "Sessions/history OPEN (P0 residual)" and "\@participants, /slash OPEN
   (P1)" and "Missing sub-kinds: #selection / #problems / terminal output (P1)". Those rows are
   consistent with this audit and should stay open.
3. The same file marks "Error/quota/rate-limit states CLOSED (taxonomy cards: Failed / RateLimited /
   NoCredits / Offline / OverBudget)". **This is wrong as written**: the taxonomy exists, but four of
   those five cards cannot appear in the real chat (8.2). Recommend downgrading that row to PARTIAL.
4. The dependency map's KEEP-FOR-NOW list names exactly what did not survive: the whole attachment
   intake/extractor core, `ChatStagedFileImport`, `DebugConsoleCapture` consumers, and
   `ScmCommitMessageService` consumers. It labels them "STAYS"; they did not. That is the source of
   most "Missing" rows in 4.2 and 4.4.
5. Archive licensing note (feature map): MIT sources may be studied and adapted with attribution;
   **do not ship codicons or the "Copilot" name/logo**. Any icon work should use Material icons or the
   owner's own SVGs.
6. Advisor constraint that still applies: `XG01-04` extension enablers are parked and must never be
   bundled into a chat batch.
7. From the owner's UI brief, two things the rebuild has and must keep: blue-on-text border and the
   mic removal. Two things it lacks and the brief explicitly asked for: a *context attachment control
   positioned with the input* (partially met by the Add Context chip) and **visible agent activity**
   (met by U6-c/d/e/f).

Nothing in the prior research requires restoration of the archived panel itself.

## 7. Current defects and uncertainties, with the recent batch reviewed in detail

### 7.1 U6-c, agent commands visible in the terminal

Traced: `AgentTools.runCommand` announces with `begin()` before `execOnce`, finishes with the real
output, finishes FAILED with the real message on a throw and rethrows. `finish` updates only its own
id, so overlapping runs cannot swap output. Non-zero exits appear in the output text
(`ProotInstaller.execOnce` returns `"Exit code N"` plus stdout). Permission checks happen one layer up
in `ChatSession.runTools` through `AgentFlowGate.awaitApproval`, and a refused call never reaches
`begin()`.

Gaps found:

- **`AgentTerminalFeed.cancel()` has no caller.** It exists for "a command that never got to run",
  but nothing invokes it. Stop during a long agent command cancels the coroutine while the blocking
  `execOnce` keeps running on its thread; the row stays `running` until that call returns. If the
  process dies first, the strip shows a permanent `running` row until the app restarts.
- **`exitCode` is never passed to `finish()`**, so `statusLabel` returns `done` for any completed
  exec, including one whose output begins `Exit code 1`. The strip's headline can therefore read
  "done · 4s" for a failed command. This is the same "never let a failure read as success" rule the
  project applies elsewhere, so it is worth a small fix.
- Repeated updates are fine: one row per execution id, `MAX_RUNS 20` with oldest dropped and output
  clipped at 4000 chars with an explicit truncation marker.
- Uncertainty: whether the terminal pane is auto-opened on an agent command was not traced to a
  definitive branch; the strip renders inside `TerminalPane` (`TerminalPane.kt:2974`), so the row is
  only visible if the pane is visible. The owner brief asked for "open or reveal when appropriate".
  Mark **Unverified**.

### 7.2 U6-d, Explorer reveal

`ExplorerReveal` queues paths, `ExplorerRevealEffect` drains, expands ancestors outermost-first and
consumes, so a second batch appends rather than overwriting. `writeFile` requests reveal only after
`writeText` returns, so failed writes reveal nothing. Nested and newly created directories are covered
by the ancestor expansion and tested (12 JVM tests quoted in `457a84f`). No defect found.

### 7.3 U6-e, Files Changed

Facts: one journal set per tool round; a failed write records nothing; undo re-reads disk and refuses
on drift with a visible reason; created files are deleted only if they still hold the agent's content;
partial reverts are reported as partial; the row renders only in AGENT mode and only when a set has
entries; `95aefe5` fixed the empty-row case (a test caught it).

Gaps:

- **`MAX_SETS = 3` drops the oldest set silently** (`AgentWriteJournal.kt:49,64`). After four write
  rounds, the first operation can no longer be undone from the row, and nothing says so.
- **Per-file Keep/Undo is not available**; granularity is one operation. The archive staged per file
  and could apply or discard individually.
- `activeSetId()` falls back to "the last unsettled set". A write performed outside a chat tool round
  (for example through the HTTP `/tool/write_file` route) can therefore be recorded into the chat's
  open set, attributing it to the wrong operation. Low likelihood, worth a guard.
- The row cannot show a change made by a tool that wrote and then failed partway; by design it shows
  only what reached disk, which is correct, but the user has no "attempted but failed" record beyond
  the InfoNote lines.

### 7.4 U6-f, visible approval row

Verified: the card renders `AgentFlowGate.pending` (the real pending request) and resolves through the
same `awaitApproval`; the level, persisted always-allow switch and 10 minute timeout still decide the
outcome; no pending request means no row; duplicate taps cannot complete the deferred twice; late taps
after expiry are inert; `Always allow` is disabled when the gate did not offer it; `Skip` is a
rejection through the gate. `ApprovalPresentation` is pure and tested (11 + 11 JVM tests per the
commit message, `AgentFlowGateScopeTest` present).

Security position after the removal of the visible "Local Approvals" status row: the level is still
configurable in `InProjectSettingsDialog` (`ChatPermissionStore.setLevel` at lines 1274-1288, per-tool
disallow at 1312). Removing the row cost no control. No unauthorized execution path was found inside
the panel.

Boundary worth recording: **the headless HTTP route bypasses this gate.** `AgentApiServer` `/tool/*`
authorizes on project trust (`TrustState.isActiveProjectTrusted`) and refuses `use_connector` outright,
but does not consult `ChatPermissionStore` levels or `AgentFlowGate`. That is a deliberate unattended
mode, not a defect, but it means "approval row" is a panel-level guarantee only.

### 7.5 Other current defects and uncertainties

| Item | Status | Evidence |
|---|---|---|
| No auto-scroll | Confirmed defect | `ChatPanelScreen.kt:80,109`, no `scroll.` usage |
| No virtualization | Confirmed defect for long threads | `ChatListRenderer.kt:34-40` |
| EDIT-mode staging dead | Confirmed defect | `PendingChangesStore.stage()` zero callers |
| No project context in prompt | Confirmed gap | `ChatSession.kt:230-231` |
| Panel header has no history or new-chat control | Confirmed gap (roadmap agrees) | `ChatPanelScreen.kt:96-112` |
| IME/send-button coverage | Unverified (host insets) | no `imePadding` in panel |
| Robolectric missing, prefs-backed stores untested on JVM | Known | roadmap note |
| Dead dependencies (`pdfbox`, `jtokkit`) | Confirmed | `app/build.gradle.kts:234,248` |
| Design gallery is DEBUG-only but ships the only producers of 4 row types | Confirmed | `ChatDesignGalleryActivity.kt:66-70` |

## 8. Explanations of the key findings

### 8.1 Conversation persistence

**What is stored today.** Nothing about the conversation. `ChatSessionManager.session(projectId)`
returns an in-memory `ChatSession` from a `HashMap<String, ChatSession>` (`ChatSession.kt:476-497`).
Inside a session, `ChatPanelUiState` holds `rows` (a `List<ChatRowModel>`), `streaming`,
`streamStartedMs`, `firstDeltaMs`, `lastUserText`, plus `pendingAttachments` and `mode`. Attachments
are explicitly memory-only (`ChatSession.kt:40`). What *is* persisted is adjacent configuration:
`ChatSettingsPrefs` (last selection, failover opt-in, per-model context overrides) and the stored keys
via `SecureTokenStore`.

**What disappears on restart.** All rows, the last user text, the pending chips, the current mode,
the tool journal contents (`AgentWriteJournal` is in-memory), the terminal agent record
(`AgentTerminalFeed` is in-memory), and the duplicate/dedup state of anything the user typed. Files
the agent wrote stay on disk; the story of why they were written is gone.

**Is there another persistence mechanism?** No. `SessionStateStore` and `SessionHandoffManager` store
editor and shell session state (open tabs, working directories), not chat. Nothing in `chat/` writes
rows to disk.

**What the archive supported.** Per-session JSON under `session_v1_<id>`, a roster with relative
timestamps, per-session mode and custom mode, redacted attachment copies with eviction, a scrub pass
over stored sessions, and plan state persisting separately in `plans_v1`.

**Consequences today.** Any restart during agent work loses the transcript; a user cannot review what
was done after the fact; the batched device round has to re-enter prompts after each reboot; and no
future feature that needs history (memory, retrieval, plan state) can be built without this first.

**Planning note.** Do not persist attachment bodies by default. Persist row text plus a
credential-scrubbed marker, mirroring the archive's `scrubSessionsOnce` lesson.

### 8.2 Unreachable status rows

**Produced today** (verified by grepping producers, not renderers):
`ChatRowModel.User`, `AssistantStream`, `Attachments`, `InfoNote`, `Failed`, `OverBudget` from
`ChatSession`; and `Thinking` from `ChatPanelScreen` (`:116`) while streaming before the first delta.

**Never produced** outside the DEBUG gallery: `RateLimited`, `NoCredits`, `Offline`,
`TrimmedHistory`. The renderer handles all four (`ChatListRenderer.kt:49-53`) and the cards exist
(`ChatCards.kt:131-241`).

**Why the transitions matter.** `onEvent` (`ChatSession.kt:376-400`) collapses every non-auth,
non-credits, non-budget error into `ChatRowModel.Failed(err.userVerdict, "", err.retryable)`:

- A 429 arrives as `ChatError.RateLimited(retryAfterSec)` with the provider's wait, and the user sees
  a red failure with retry. The number the server sent is computed and thrown away.
- A 402 arrives as `OutOfCredits` and becomes either an opt-in failover note or a generic failure, so
  "out of credits" (an account state, retrying is pointless) looks the same as "request failed"
  (retry might help).
- A dropped connection arrives as `Network` and gets no offline affordance.
- A trimmed request sends fewer turns than the user wrote, silently.

**What the user sees instead.** A single failure card with a plain-words verdict, plus whatever
`InfoNote` the surrounding code added (key switch, skipped tool). For 429/402 that verdict text is
accurate, so this is a clarity and affordance gap rather than a false statement.

**What the taxonomy already computes.** `ChatError` carries `retryAfterSec` for 429,
`providerHint` for 402, and `retryable` for Server/Network, so a correct card is a pure mapping away.
`ErrorTaxonomy.retryAfterSec(headers)` already parses the header.

**What a future implementation needs.** A pure `statusRowPolicy(error) -> RowKind` with a decision
table test; emission inside `onEvent`; a session-level 429 ladder (8.3) so `RateLimited` has a live
countdown; a dropped-turn count from `HistoryBudget` (8.4) so `TrimmedHistory` has a number.

### 8.3 A controlled 429 retry ladder (options, not requirements)

Current state: no retry. The engine parses Retry-After, the session does not use it, and
`streamedAnything`-style protection exists only implicitly (a failed send never streamed text).

Design space, documented for a future decision:

1. **Server-directed wait (preferred default).** Honour `Retry-After` when present, else a short fixed
   wait. One or two same-key retries, then surface the card with a manual Retry. Pros: cheapest
   correct behaviour, no invented timings, predictable credit cost. Cons: a long Retry-After (60s)
   needs a visible countdown or it looks frozen.
2. **Progressive backoff (exponential with jitter).** 1.5s, 4s, then stop, or jittered exponential
   capped. Pros: smooths shared rate-limit windows. Cons: arbitrary timings, more credit burn, harder
   to explain.
3. **Immediate key switch when the provider has several keys.** Treat a 429 as "this key is hot",
   move to the next stored key, and place the used key on a cooldown so the next send does not start
   with it. This is the archive's `ChatKeyPool` idea and the one thing most likely to matter with
   pooled keys.
4. **Manual only.** Show the wait and a Retry button; never auto-retry. Pros: maximum clarity and
   zero surprise credit use. Cons: the user must wait and tap.

Cross-cutting requirements for any option:

- **Cancellation**: the wait must be cancellable by Stop, and Stop must also mark the attempt
  cancelled rather than leaving a card on screen.
- **Duplicate prevention**: never retry an attempt that already produced visible text; never retry a
  payload that grew (the send budget should be re-checked); never retry a 402 (account state), a 401
  (credential), a 400/404 (request) or a 5xx without a user-visible decision.
- **Classification discipline**: 429 = slow down, 402 = buy credits / switch account, 401/403 =
  credential, 400/404 = wrong request or model, 5xx = provider fault, network = connectivity. Each
  has a different affordance; only 429 and transient network are auto-retry candidates.
- **Observability**: attempts, wait total and TTFB should be recorded in a sanitized form (the archive's
  `ChatTimingLog` signature rule is the model to copy).

Nothing here recommends a final timing policy; that is an owner decision.

### 8.4 Honest history trimming

`HistoryBudget.select(history, mode)` (engine) keeps the newest `keepMessages` entries and then drops
the oldest until the char cap fits. It returns only the surviving list; the same list instance when
nothing was dropped, and a fresh list when something was. Nothing counts what fell out, so the panel
cannot render `TrimmedHistoryRow(droppedCount)` even though the card exists.

The user-visible effect: a long thread's early turns silently stop reaching the model, so a follow-up
question can be answered without the context the user believes is present. The archive handled this by
turning dropped turns into one-line notes and showing an explicit "Older messages trimmed: N" row.

Minimum future change: have the budget return `(messages, droppedCount)` and surface the count as a
row (the archive's row already exists). Note that `BudgetGuard` handles a *too-large* request
separately with an explicit confirm, which is already honest; only trimming is silent.

### 8.5 WP7 and the model picker

**1. What WP7 means.** In `docs/ROADMAP.md`, the chat rebuild is packaged A and WP1..WP9. WP7 is
"Settings + storage cleanup + capability overrides + model persistence", shipped as `4b268d8` plus
fix `2aa3eca`, GREEN run 3403. It is a *package*, not a picker specification.

**2. Where the specification lives.** `docs/ROADMAP.md` (package row), `docs/chat-p0-gaps.md`
(P0 row "Model picker + Manage Models/BYOK", marked CLOSED by WP4 endpoints + WP5 catalog + WP7
persistence), and `docs/checkpoint-2-sheet.md` items 1-4, which are the owner-facing acceptance
checks for keys, live model list, capability override and chat data reset.

**3. What the specification requires.** Per those documents: multi-key management with presence-only
display, live model lists from the endpoint, a per-model context override, persistence of the chosen
provider and model, and clear-conversation / reset-settings actions. WP7 did **not** specify a
searchable popup; that requirement comes from the owner's later UI reference (a picker with a search
field and a gear, pinned models, an "Other Models" group), which produced `ModelPickerSpec`.

**4. What `ModelPickerSpec` defines.** A pure builder: `Effort` labels and `fromBudget`, `isOther`
classification, `PickerRow.Model`/group rows, `build(models, query, ...)` producing grouped rows with
a `showsEmptyMessage` and an "Other Models" group that opens when a search hits inside it.

**5. Why it has zero callers.** It was written as the pure half of the popup and the popup itself was
never built. The panel still opens `ProviderModelDialog`, whose own KDoc calls it "Minimal
provider/model/key picker over the WP1 snapshot (WP7 replaces it)". By the time WP7 shipped, that
replacement scope meant *persistence*, not a new dialog, so the comment now over-promises.

**6. What the current basic picker supports.** Provider list from presets, stored-key presence, model
ids from the bundled catalog plus live fetch inside the custom endpoint editor, applying a selection,
clearing the conversation, and opening `KeysManagerDialog`. `ChatSettingsPrefs` persists the last
coherent selection and per-model context overrides.

**7. What is missing versus the intended searchable picker.** Search with an empty-state message,
pinned models, the "Other Models" grouping, an inline add-model row, a refresh row with a fetch-age
label, the gear entry to keys, and per-mode memory.

**8. How the pieces must stay synchronized.** One source of truth for the selection
(`PanelSelection`: providerId, modelId, baseUrl, keySlot), persisted only when coherent
(`persistSelectionIfCoherent` already refuses incoherent pairs). Picker, composer chip, capability
gate, payload budget and request builder must all read that same value; the endpoint registry owns
base URLs and key slots so a model can never be sent to a foreign endpoint; a provider switch must
re-resolve the key slot, and the chip must show the same `providerId:model` string the request uses.
Any pin or per-mode value is a *pointer* into the same registry, never a copy.

**9. Tests that would verify complete behaviour.** Pure: `build()` grouping/search/empty-state (some
exist), pinned ordering and round-trip, per-mode resolution, coherence rejection (already exists),
capability gating per selection, endpoint/model mismatch refusal. Integration-ish (JVM): picker
selection produces exactly one request with that provider, base URL and key slot; live fetch merge
dedupes and marks staleness; a provider with no key surfaces `NeedsSetup` rather than a send.

### 8.6 Defect analysis method note

For each of the above, the rule applied was: a row, card, button or interface counts as present only
if something produces it, mounts it, or calls it. That rule is what turned `RateLimited`,
`NoCredits`, `Offline`, `TrimmedHistory`, `ModelPickerSpec`, `PendingChangesStore.stage` and
`AgentTerminalFeed.cancel` from "implemented" into "unreachable", and what would have caught the
auto-scroll gap had it been applied to layout modifiers as well as to producers.

## 9. Deferred features recorded (documentation only, no implementation authorised)

### 9.1 Conversation persistence and session-aware memory

Target: persist conversations, support multiple sessions, and (later) let the assistant retrieve
relevant prior conversations.

Architectural considerations to settle before code:

- Storage format and versioning, plus whether to migrate the archive's `sessions_v2` data if any
  survives on device. Rows are typed now (`ChatRowModel`), so a DTO map with a version tag is the
  natural shape; the archive's `msgToJson` is a reference, not a copy target.
- Redaction: persist text only; run a scrub pass on write and on read; never persist attachment
  bodies; record an explicit marker when an attachment existed.
- Eviction and size caps per session, plus a project-level cap, with an honest notice when chat
  history is pruned.
- Retrieval default: **the active session only**. A future Settings option may offer "current session
  only" vs "all sessions". Cross-session access must be opt-in, must never merge sessions into one
  transcript, and must label which session a retrieved memory came from.
- Concurrency: the same project can be open in two panels only if persistence has a single writer;
  assume one writer and document it.
- Plan and journal state: decide whether plan steps persist with the session (the archive did) and
  whether the Files Changed journal persists (recommended: no, it reflects this run only, but say so
  in the row).

### 9.2 Live status rows and rate-limit handling

See 8.2 and 8.3. Recorded as: four unreachable rows, the required emission points, the ladder options,
and the tests (error to row decision table; ladder honours Retry-After, caps attempts, never retries a
streamed attempt, cancels cleanly).

### 9.3 Markdown and code Copy/Insert

Intended behaviour: assistant text renders as markdown (fenced code, inline code, bold/italic,
headings, bullets, links styled but not navigated on tap without confirmation), with per-code-block
actions.

- **Copy** puts the block's exact text on the clipboard, using the pre-render source (never the
  styled text), and confirms without a toast spam loop.
- **Insert** inserts at the active editor cursor in the active tab, or opens a new tab when no editor
  is open. It must respect the current undo stack (one undoable edit), refuse when the editor is
  read-only or the file is a binary preview, and never append to a different file than the one
  named in the block header.
- Edge cases to settle: unclosed fences, nested backticks, very long single lines (horizontal scroll,
  not wrap), indentation preservation, tabs vs spaces, and a block with no language tag. Streaming
  text should render progressively without re-parsing the whole document each delta if it proves
  expensive.

### 9.4 Searchable model-picker wiring

See 8.5. Recorded as: spec exists, popup missing, pinned/per-mode/refresh missing, synchronization
rules and tests listed.

### 9.5 Document extraction

Paused by owner decision. Not restored, not scoped, not prototyped. Recorded consequence only: the
PDF capability gate currently *refuses* a PDF for a model without PDF support instead of degrading to
extracted text, and `pdfbox-android` remains a declared dependency with no consumer.

### 9.6 Other deferred items carried from the roadmap

Header history and new-chat controls; vector icons to replace text glyphs; Robolectric for
SharedPreferences-backed JVM tests; `@participants`; slash autocomplete; `#selection`, `#problems`,
terminal output attach; per-file review; skills; AI commit messages; key probe; token gauge; timing
telemetry.

## 10. Prioritised potential future batches

None of these is authorised by this document. Each row is scoped so it can be approved or rejected
individually. Every batch follows the standing rules: one component per commit, CI green between
pushes, no inline composable bodies (64 KB rule), 8-12dp radii and 12/10dp padding for panels, no
sub-agents, no device round per batch (batched instead).

### B0 (defect fixes, smallest and highest value)

- **Scope**: transcript auto-scroll (drive `scroll` to `maxValue` as rows grow, keep user scroll
  position when they have scrolled up), stop-cancel wiring for a running agent command
  (`AgentTerminalFeed.cancel` call from `ChatSession.cancel` where a tool round is active), pass the
  real exit code into `AgentTerminalFeed.finish`.
- **Dependencies**: none.
- **Tests**: pure helper deciding "follow the bottom or hold position" from a state snapshot;
  feed test for cancel and for non-zero exit label.
- **Risks**: auto-scroll can fight the user's drag; mitigate with the hold-position rule.
- **Done when**: a streamed reply stays visible without user action; Stop during a command leaves a
  `cancelled` row, not `running`; a failing command reads `exit N`.

### B1 Sessions persistence and roster

- **Scope**: `ChatSessionStore` (DTO, version, per-project file, append-only rows), restore on open,
  clear-conversation, a minimal roster (current session plus switch/create).
- **Dependencies**: none in app code; B0 not required.
- **Tests**: round-trip all persisted row kinds, scrub, eviction caps, corrupt file to empty session,
  restore order and mode.
- **Risks**: redaction mistakes (persisting a secret), file growth, concurrent writers.
- **Done when**: restart restores the transcript text and mode; no attachment body is on disk; a
  corrupt store degrades to an empty session with a note.

### B2 Status rows, 429 ladder, honest trim count

- **Scope**: pure error-to-row policy plus emission in `onEvent`; a cancellable ladder honouring
  Retry-After with a visible countdown; `HistoryBudget` returning dropped count and the trim row.
- **Dependencies**: engine signature change for the budget; B1 not required but nicer after it.
- **Tests**: decision table; ladder attempt/wait/cancel/streamed-guard; budget drop counts.
- **Risks**: credit burn from aggressive retries; mitigation: server-directed wait, low attempt cap,
  visible countdown, Stop.
- **Done when**: 402, 429, offline and trimmed states each render their own card from real events;
  a streamed attempt is never retried.

### B3 Markdown with code Copy/Insert

- **Scope**: pure parser to blocks, renderer per block type, copy and insert actions, streaming-safe
  rendering.
- **Dependencies**: editor insert seam (verify the exact entry point used by other panels first).
- **Tests**: parser golden cases; action receives exact block text; read-only refusal; no markdown
  applied to user rows.
- **Risks**: 64 KB method limit in the renderer (extract per block), parse cost per delta.
- **Done when**: fenced code renders as a block with working copy and insert; malformed markdown
  falls back to plain text.

### B4 Model picker popup

- **Scope**: popup shell with search and gear, `ModelPickerSpec.build()` as the row source, pinned
  models, per-mode memory, refresh with fetch-age label.
- **Dependencies**: `ChatSettingsPrefs` extension; `ModelListFetcher` reuse.
- **Tests**: as listed in 8.5.
- **Risks**: selection drifting from request (mitigate with the single-source rule and coherence
  persistence), stale live cache.
- **Done when**: search narrows the list, pinned sorts first, the chip and the request always agree.

### B5 Project context and instructions in the prompt

- **Scope**: inject workspace roots (all roots, `[ACTIVE]` marker, open file path) and, when enabled,
  the instructions block from `AutoInstructionsProvider`, into the system instruction. Keep it bounded
  and formatted, with an honest note when it is omitted for size.
- **Dependencies**: `getAllWorkspaceRoots` (exists), `AutoInstructionsProvider` (exists).
- **Tests**: pure prompt assembly given roots/active/open file/instructions; budget cap behaviour;
  no secrets or file contents in the block.
- **Risks**: prompt growth against the payload budget; mitigation: hard cap plus the existing
  over-budget path.
- **Done when**: the model can name the open project and the active file without being told.

### B6 Context sub-kinds

- **Scope**: `#selection`, `#problems`, terminal output chip (and, if cheap, search results).
- **Dependencies**: `EditorSelectionStore`, problems source, `DebugConsoleCapture`.
- **Tests**: resolver per kind with empty and size-capped cases.
- **Risks**: attaching stale or huge blocks; caps and a size line in the chip.
- **Done when**: each chip produces an exact, capped block or an honest refusal.

### B7 Input affordances

- **Scope**: slash parser plus a small command set, per-project input history, send queue while
  streaming.
- **Dependencies**: B1 for persistence of history.
- **Tests**: parser cases, history boundaries, queue order and attachment retention on drain.
- **Risks**: command surface creep; keep the first set tiny (`/clear`, `/new`, `/model`, `/help`).
- **Done when**: `/help` lists exactly the implemented commands; a queued send keeps its chips.

### B8 Export, rating, find

- **Scope**: redacted markdown export with typed result, message rating persisted with the session,
  transcript find with match count.
- **Dependencies**: B1.
- **Tests**: redaction; rating round-trip; find counts.
- **Risks**: export writing outside the project root; always write under `.codespace/exports`.

### B9 Plan card and staged edit review

- **Scope**: either wire `PendingChangesStore.stage()` behind an EDIT-mode propose-and-accept flow, or
  rewrite the EDIT prompt to stop promising it. Plus a plan card if plan review is wanted.
- **Dependencies**: B1 for plan persistence.
- **Tests**: staging and apply/discard with drift; plan approve/revise/reject state machine.
- **Risks**: two competing change-application mechanisms (journal vs staged). Decide which one owns
  EDIT mode before building either.

### Explicitly not recommended

Restoring the archived panel, the 3,076-line overlay, per-send attachment re-injection, a second retry
path, blocking MCP calls, native tool payloads (three shapes for no added capability), or
`ChatScreenshotAttach` as its own flow.

## 11. Evidence index for significant conclusions

| Conclusion | Command or file evidence |
|---|---|
| Four rows unreachable | `grep -rn "ChatRowModel.RateLimited\|...NoCredits\|...Offline\|...TrimmedHistory"` → only `ChatDesignGalleryActivity.kt:66-70` and the renderer |
| Thinking row reachable | `ChatPanelScreen.kt:83-86,116`; `ChatListRenderer.kt:47` |
| No auto-scroll | `ChatPanelScreen.kt:80` (`rememberScrollState`), `:109` (`verticalScroll(scroll)`), `grep "scroll\."` → no other use |
| No virtualization | `ChatListRenderer.kt:34-40` (`Column`, `forEach`) |
| No persistence | `ChatSession.kt:40`, `:476-497` |
| No project context in prompt | `ChatSession.kt:230-231`; `ChatModePrompts.kt:19-31` |
| Instructions provider not in chat | `AgentApiServer.kt:236-237` only caller |
| EDIT staging dead | `grep "PendingChangesStore.stage("` → no hits; `AiReviewStrip.kt` calls only apply/discard |
| 429/402 taxonomy exists | `ChatError.kt` (`RateLimited(retryAfterSec)`, `OutOfCredits`), `ErrorTaxonomy.retryAfterSec` |
| Trim is silent | `HistoryBudget.kt:28-47` returns list only |
| Model picker spec unwired | `grep -rn ModelPickerSpec` → definition only; `ChatPanelScreen.kt:190` KDoc |
| Dead dependencies | `app/build.gradle.kts:234` (jtokkit), `:248` (pdfbox) |
| U6-c cancel unused | `grep "AgentTerminalFeed.cancel"` → definition only |
| U6-c exit code unused | `AgentTools.kt:274` (`finish(runId, out)`), `AgentTerminalFeed.kt:149-153` |
| Journal set cap | `AgentWriteJournal.kt:49,61-66` |
| Approval gate path | `ChatSession.kt:430`; `ChatApprovalCard`; `AgentFlowGateScopeTest` |
| Level still configurable | `InProjectSettingsDialog.kt:1274-1288,1312` |
| CI mapping | Actions API per `head_sha` for `46d1b61`, `bc969c8`, `457a84f`, `ca5f811`, `95aefe5`, `8e4d185`, `d384d60`, `362e080` |
| Archive extractors and caps | `PdfAttachmentExtractor.kt`, `DocumentTextExtractor.kt`, `SafeZipReader.kt`, `AttachmentIntake.kt`, `ChatStagedFileImport.kt` |
| Archive picker/menu/pins | `ui/screens/ChatAttachPicker.kt:76-128`, `ChatModelMenuButton.kt:60-272` |
| Archive session and history | `CopilotChatPanelOverlay.kt:142-559`, `ChatInputHistory.kt`, `ChatPlanStore.kt` |

## 12. What was and was not examined

**Examined directly this session**

- All 32 current chat files at signature and behaviour level, the 27 engine files at signature level
  with four read in full (`ChatError`, `HistoryBudget`, `ModelCatalog` head, adapters head).
- The four recent features end to end, including their mounts, gates and tests.
- Archive chat layer: all 78 files at declaration level, 22 read in substance (composer, slash,
  history, stream UI, retry/export, plan store, token counter, key probe, timing log, native tools,
  evidence, intake, notices, PDF gate, search attach, staged import, capability gate/seam, image
  attachments, prefs backend, key failover/pool heads, model menu, picker walk).
- Repo docs listed in the header, plus `docs/vscode-refs/` inventory.
- CI conclusions per commit through the Actions API.

**Partially examined**

- `CopilotChatPanelOverlay.kt` (3,076 lines): the session, send-loop, roster, export, find, cards and
  slash wiring sections were read; the middle of the agent loop and some dialog bodies were sampled.
- `ChatAttachPicker.kt` (951): the walk, search and caps were read; the sheet's visual composition was
  sampled.
- `DocumentTextExtractor.kt` (773) and `PdfAttachmentExtractor.kt` (374): headers, caps and seams read;
  the per-format scanners were not read line by line (not needed to judge parity).
- Archive `providers/*` (Gemini 285, transport 389): shapes and capability handling read; not every
  branch.

**Not examined**

- No device run, no emulator, no visual verification, no performance profiling, no memory measurement.
- No runtime log or crash trace.
- No verification that the archived `sessions_v2` data still exists on the device (relevant only if
  persistence is later approved).
- No examination of non-chat subsystems beyond their chat-facing seams (editor insert path, problems
  source, `EditorSelectionStore`, `DebugConsoleCapture`), so B3/B6 dependencies are named but not
  proven.
- The `c12-stage3-u4-test-sheet.md` and `device-round-consolidated.md` sheets were not re-derived;
  device items are taken as documented by the owner-side process.

**Confidence statement.** The gap list in sections 4 and 5 is reproducible from the commands in
section 11. The three top defects (auto-scroll, missing project context, dead EDIT staging) were each
verified by a negative grep plus a positive read of the code that should have produced the behaviour.
Items marked Unverified are exactly that: the IME inset path, and whether the terminal pane
auto-opens on an agent command.
