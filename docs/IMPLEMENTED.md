# Implemented — everything requested and delivered

One canonical list, grouped by area. Each row is something that was asked for and is now in the
app. Commit + CI run where the record exists; rows marked *backfill* were reported in an earlier
session and still need their commit id pasted here.

## Copilot repair programme (owner-approved 2026-11-14)

| Delivered | Commit / Run |
|---|---|
| Phase 1: staging, commit and push are three approvals; `add -A` removed; protected-branch push needs a second confirmation; declining keeps the commit | b537e61 / GREEN 43632852707 |
| Phase 2a: closing the last chat window parks a live runtime instead of closing it, so a turn survives the panel; a parked runtime is closed by an idle reaper once its work ends | 8dfeac3 / GREEN 43636558841 |
| Phase 2b: a turn killed with the process restores as an interrupted reply and offers an explicit **Resume** that continues the same message without resending the request; a new send retires the offer | db65f4c / GREEN 43660331882 |
| Phase 3a: every transcript write and delete runs under one lock, so a delete cannot land inside a write (the recurring ENOENT); concurrency regression test forces the old interleaving | e44acb0 / GREEN 43661682327 |
| Phase 4a: WritePolicy gates every tool call as ALLOW/STAGE/REFUSE from ToolEffectIndex; Ask refuses all side effects, Edit stages all ten file writers (not three remembered names) | 87a673e / GREEN 43662829995 |
| Phase 4b: the gate follows the approval level - Auto All writes with no diff, Auto Safe and Always Ask stage an Edit proposal; Ask stays read-only at every level | aeb53c8 / GREEN 43665059206 |
| Phase 4c: the connector card hosts its own setup (token field for `pat`, consent page opened from the card for `oauth`) instead of redirecting to the Connectors Hub; the Hub is unchanged for Settings | f51cca6 / GREEN 43667103869 |
| Phase 7: the plus chip continues into a new session with the handoff instead of opening a blank one; blank creation stays on the Sessions surface | e011d1f / GREEN 43677389348 |
| Phase 8a: a shell command that outlives a 20s inline window returns a handle (poll with `terminal_list`, stop with `terminal_kill`) instead of a false timeout that invited a duplicate run | 47f2f96 / GREEN 43684172102 |
| Phase 8b: the composer chevron dispatches by entry - "Stop and Send" really cancels then sends the typed text (and says so if the request never clears), while the unbuilt Queue/Steer entries map to nothing instead of silently stopping the answer | 7d398fb / GREEN 43698473801 |
| Phase 8c: a tool call whose arguments never closed is refused with the tool named and nothing executed - the old path fell back to "{}" and invoked the tool with every parameter missing; a parameterless tool is still allowed | 6147c55 / GREEN 43705466562 |
| Phase 6a: a skill can be disabled without deleting it (flag persisted, older files restore enabled, disabled skills absent from the model index) | a443b77 / GREEN 43685790736 |
| Phase 6b: Settings gains a Local Skills manager - list with on/off switches, create, edit, delete with confirm, import from the phone, and a line naming the folder it reads | cb4b4ca / GREEN 43688779272 |
| Phase 5b: every file-mutating tool is containment-checked against the open project root before dispatch (copy/move were the only ones that were); the refusal names the path and root and says nothing was written | c9a9fb4 / GREEN 43690923093 |
| Phase 6c: explicit "remember / forget / what do you remember" is routed as a directive naming save_memory, delete_memory or read_memory for that turn; ordinary prose that mentions memory is untouched | afb21b2 / GREEN 43692528320 |
| Phase 8a: Settings > AI Agent gains a Model Catalogue - each known model with its context window (floored, not rounded up), images/pdf, tri-state tool support and the providers it is verified for, plus a footer that says an unlisted model still works and keeps its tools | 9faf89a / GREEN 43697552275 |
| Phase 5a: `guestPath` delegates to ToolPathResolver, so every file tool resolves a path the same way checksum/read_file disagree no more | 705793a / GREEN 43666264415 |

## Chat rebuild (owner-requested rewrite, A / WP1-WP9)

| Delivered | Commit / Run |
|---|---|
| Token layer, single source for Compose + CSS | 228350e / 8df9ed9 GREEN 3377 |
| Component library + ChatListRenderer interface | 4e535b7 GREEN 3378 |
| Design gallery (debug-only) | f69dc73 GREEN 3379 |
| ProviderAdapter seam + OpenAI adapter | f1ba20c GREEN 3381 |
| Gemini adapter (bounded thinking, thought-part filter) | 2bd5b92 + a7d4c33 GREEN 3383 |
| Key/endpoint migration (read-only) | d6bf9e8 GREEN 3386 → b90fc0e GREEN 3388 |
| Chat panel on the engine | b90fc0e GREEN 3388 |
| Anthropic native + OpenAI-compatible presets, reasoning defaults | 8b590ef / 7e1f489 / dbbdbaa GREEN 3390-3396 |
| Custom endpoints: per-endpoint keys, live model list, CRUD | 3397 series GREEN 3397 |
| Model catalog + capability data, context-window budget | f21a5b1 GREEN 3399 |
| Multi-key pool, opt-in failover, manual switch | e653fe5 GREEN 3401 |
| Settings, storage cleanup, capability overrides, model persistence | 4b268d8 + 2aa3eca GREEN 3403 |
| Attachment pipeline: picker, 10 MB cap, staging, redaction | a79c8e7 + 46ee083 / 13244a2 GREEN 3406 |
| Text/context attachments + credential redaction gate | 74fe423 + 5532eb7 GREEN 3409 |

## Composer and agent activity (the video-derived rebuild)

| Delivered | Commit / Run |
|---|---|
| Composer rebuilt to the reference: single card, toolbar row, send cluster, keys in Settings | 4e62781 GREEN 3432 |
| Ask / Edit / Agent modes wired, per-provider payloads | 085e577 + 855b371 GREEN 3437 |
| Composer polish: plus on its own row, no mic, blue-on-text border, tighter chips | d573aef GREEN 3438 |
| Tool-wire decision layer (parse, mode rules, round cap, result turn) | 31259f4 GREEN 3439 |
| Chat panel acts, behind the approval gate | ce98d03 + ebf8b9a GREEN 3441 |
| Composer proportions: Add Context, gear, no approvals row, bounded field | 7a9880d + b5cc8ec GREEN 3443 |
| Compact pass: ring, card padding, chips hug text, 28 glyph, 40dp row | 091b192 GREEN 3444 |
| Agent commands visible in the terminal: command, status, duration, output | 46d1b61 + bc969c8 GREEN 3447 |
| Explorer refreshes and reveals on agent writes | 457a84f GREEN 3448 |
| FILES CHANGED row: journal, real line diff, Keep/Undo, drift-safe revert | ca5f811 + 95aefe5 GREEN 3450 |
| VISIBLE APPROVAL row: Allow once/session/always, dual-click, expiry | 8e4d185 + d384d60 GREEN 3452 |

## Editor

| Delivered | Commit / Run |
|---|---|
| Editor AI menus answer: prompt handed to the chat and sent once idle | 950e19e GREEN 41202315547 |
| Green chip draggable, edge-snap, clamped, persisted; Solid/Ghost/Hidden | 950e19e GREEN 41202315547 |
| Chip drag actually moves the chip: gesture-order + absolute tracking (popup window feedback) | fe22784 GREEN 41324787347 |
| Formatter choice moved beside Format on Save, one surface for one value | fe22784 GREEN 41324787347 |
| GitHub sign-in from Settings opens the shared dialog (hub bypass closed); hub row keyed on the auth epoch; "github" focus lands | fe22784 GREEN 41324787347 |
| AI chip stays reachable with the keyboard up, clamped above the IME | 6f2e9a2 GREEN 41339078514 |
| Edit tools documented in the tool protocol (prefer replace_string_in_file over full rewrites) | 6f2e9a2 GREEN 41339078514 |
| Chip menu is AI-first: Fix with AI pinned, nine transforms, "More actions..." | 950e19e GREEN 41202315547 |
| Code-producing actions land through write_file as reviewable proposals | 950e19e GREEN 41202315547 |
| Lightbulb: no longer fires on every word, hidden with keyboard open, selection/diagnostic driven | 950e19e GREEN 41202315547 |
| Formatter settings surfaced in the app-wide settings page | backfill; relocated fe22784 |
| Find & Replace, Go to Line, multi-cursor | backfill |
| Source Control pane: history, stash, branch delete/rename, conflicts, .gitignore, tags, local snapshots | backfill |
| Editor action-menu spec, 18 unit tests | 950e19e GREEN 41202315547 |

## Notebooks

| Delivered | Commit / Run |
|---|---|
| `.ipynb` cell view: cells in order, markdown as prose, outputs shown | fada95c GREEN 41234630438 |
| Tap to edit source, add/delete cells, saved back as valid `.ipynb` | fada95c GREEN 41234630438 |
| Notebook tools the model can call: create, summary, read_cell_output, edit_cell, run_cell | backfill |

## Settings and skills

| Delivered | Commit / Run |
|---|---|
| App-wide settings regrouped: settings → environments → data | fada95c GREEN 41234630438 |
| Custom skills written to `.vncode/skills`, four tools, index always in instructions, bodies loaded by name | 35c9952 GREEN 41237798083 |
| Unreadable skill files reported instead of overwritten | 35c9952 GREEN 41237798083 |

## Preview panel

| Delivered | Commit / Run |
|---|---|
| Real served origin (`127.0.0.1:5500`) for HTML, SVG, Markdown | 3e1c74b GREEN 41253179294 |
| Console strip: `onConsoleMessage` + `window.onerror` | 3e1c74b GREEN 41253179294 |
| Reload on every verified save, `no-store` on both response writers | 3e1c74b GREEN 41253179294 |
| Markdown: syntax highlighting + copy button | 3e1c74b GREEN 41253179294 |
| SVG: transparency checkerboard, fill-to-viewport | 3e1c74b GREEN 41253179294 |
| Double-tap a block to open that line in the editor | 3e1c74b GREEN 41253179294 |
| Link clicks: relative → editor, web → Chrome, anchors jump | 4ea48e9 GREEN 41255130664 |
| Offline TeX → MathML (`$x^2$`, `$$…$$`), no bundled assets | 4ea48e9 GREEN 41255130664 |
| Code spans protected from the maths pass | 4ea48e9 GREEN 41255130664 |
| Scroll sync, editor cursor → preview | c72ed94 GREEN 41256640337 |
| Custom stylesheet setting, hardened against closing its own tag | c72ed94 GREEN 41256640337 |
| Mermaid diagrams from fences, engine inlined only when needed | c72ed94 GREEN 41256640337 |
| HTML re-render while typing confirmed on device | dcb86f5 (docs) |

## Browser

| Delivered | Commit / Run |
|---|---|
| One `BrowserSession` owner; rotation keeps page, DOM, history | 3e72899 GREEN 41264729591 |
| Coherent identity: engine-derived UA, `; wv` stripped, no fake Chrome 125 | 3e72899 GREEN 41264729591 |
| Error page only for main-frame failures | 3e72899 GREEN 41264729591 |
| Renderer death recovered instead of wedging the view | 3e72899 GREEN 41264729591 |
| `window.open` and `target=_blank` open in the live view | 3e72899 GREEN 41264729591 |
| Long-press sheet: open image, download, copy link | 3e72899 GREEN 41264729591 |
| Downloads, uploads, camera/mic/geolocation, third-party cookies handled | 3e72899 GREEN 41264729591 |
| Full-screen zoom actually fills the screen; 38dp slim header | 3157555 GREEN 41275724473 |
| Forward, PC/Mobile UA toggle, ↗ hand a page to Chrome | 3157555 GREEN 41275724473 |
| Loopback links from terminal and Ports panel open in the panel | 3157555 GREEN 41275724473 |
| Standalone browser bench app for device testing | b74cc37 GREEN 41259951642 |
| Tests: identity rule, loopback routing, theme/mermaid injection, skill naming | 1535705 |

## Earlier delivered, commit id still to backfill

- Trust gate neutralised: every call site treats projects as trusted, redundant prompt removed.
- Shared token storage for GitHub/GitLab and other services; write-once-bump-once sign-in so one
  sign-in lights up every panel (`SecureTokenStore` + `AuthEvents`).
- Connector audit of the 22 requested services: which exist, what they ask for, what is broken.
- Terminal link tap: extraction, file probe, staged diagnostic modes, async resolution.

## Requested, not yet delivered (exact asks)

| Ask | State |
|---|---|
| Agent scheduler's own task list panel | No panel exists. TaskRunnerPanel covers tasks.json only. |
| Task runner: VS Code `tasks.json` parity *and* compare against VS Code behaviour | Runner, spec and panel exist; the behavioural comparison has not been done. |
| MCP: wire exists, "verify end to end, do not rebuild" | `mcp_` dispatch exists in AgentTools; no verification pass recorded. |
| Undo boundaries (item 19): prune on delete, shared paths to the open session, rename continuity | Prune ships (AgentWriteJournal.forgetSession). The other two are unverified. |
| Preview scroll sync in both directions | Editor to preview ships; preview to editor does not. |
| Full TeX maths | MathML subset only; macros outside it render as their names. |
| Editor P1-P6: registry, Open With, binary banner, 2 MB truncation + Load full file, markdown preview via registry, third-party viewers | Not started. |
| Source Control: initialize-repository offer, generate-commit-message from chat | Not started. |
| Connectors: Wave 0 request shaping (Notion version header, Jira cloudId), then the 11 missing services | Plan written, nothing shipped. |
| Terminal link tap: diagnostic mode switch (Off / Log / Resolve / Full), async, fsync breadcrumbs | Extractor and probe ship; the mode switch needs verification. |
| Chat: @participants and /slash autocomplete, #selection / #problems / terminal-output attach kinds | Menus ship (ef8e446 GREEN 41320521740); the #selection / #problems / terminal-attach kinds are still not done. |
| Inline chat discovery | Chip reachable above the keyboard (6f2e9a2). A true inline chat bar (prompt + accept/reject in the editor) does not exist yet. |
| Write/edit tool gap | Edit tools now documented and preferred (6f2e9a2). No new edit capability was added; the gap was documentation. |
| CI minutes: mirrors stopped, proot repo consolidated | The 2,000-minute allowance was being spent by mirror repos running upstream pipelines. Actions disabled on nine `vn-ref-*` mirrors and the dormant `ubuntu-proot-bash-test`; `ubuntu-proot-test` rewritten as one workflow with concurrency cancellation, docs-only pushes ignored, gradle cache (9c993c3, run 41349769309 green, 4.1 min). `codespace-ide-mobile` untouched: it is public, so its runs are free, and its build is the verification pipeline. |
| Chat: trimmed-history row and 429 card removed | Both surface types deleted outright, with their composables, mapping branches, stale-status entries and gallery samples (5ab85c1, run 41384534493 green): trimming is silent, a 429 is an ordinary retryable failure with manual Retry. 402 and offline keep their cards. |
| Connectors Hub: Firebase / Supabase / n8n | The Project Services page is deleted; the three are connectors under "On this device" in the Hub (121f6b2, run 41352688590 green), reachable by the agent through `use_connector` with per-provider auth shaping. |
| Connector discoverability | The agent refused a GitLab repo creation because it looked for a `gitlab_*` tool and there is none by design; `list_connectors` printed a bare `[CONNECTED]` line. Connected services now print their canonical `use_connector` calls (d67dc97), recipes for all 19 hub services live in ConnectorRecipes.kt, and the footer states the no-per-provider-tools rule. |
| SVG viewer (strong-viewer parity) | Rebuilt as a real viewer (f34a020): toolbar zoom 10-3200%, pinch and drag pan, fit, 1:1, four backgrounds, PNG export, data URI copy, source overlay, parse error with line number, size/node/byte metrics. Old JS-off thumbnail and its dead composable removed. Survey and plan: docs/SVG-VIEWER-PLAN-2026-11-01.md |
| Status bar branch item | Real branch, guest-aware, refreshes on checkout, tap opens branch picker + create-and-switch (4bf1bfb). |
| Two-repo split: proot work in ubuntu-proot-test only | Outstanding; proot signal-31 work unfinished. |
| Device round covering everything since the U-round | Not done. |

## Still open (continuity — nothing above is claimed as done unless listed here)

- Preview: reverse scroll sync (preview → editor) is not implemented; maths is a MathML subset,
  not full TeX.
- SVG viewer: export at an explicit size (cssho `Alt+Shift+S X` equivalent) is not built; the
  viewer still reads the saved file, so live update needs a save; no symbol/outline tree, which
  would need a document model. SVGO-style minification is a deliberate no.
- Device check for the SVG viewer, the branch picker and the Hub's on-device group: none has run
  on the TECNO yet.
- Local connectors (Firebase/Supabase/n8n) store per project; the Hub resolves the last opened
  project, so a second project's credentials are only reachable after opening that project.
- Connectors: the recipe fix makes a CONNECTED service discoverable, but nothing yet proves the
  backend proxy reaches GitLab with a real token (needs one live `POST /projects`); Wave 0 request
  shaping (Notion version header, Jira cloudId) is still open.
- CI minutes: mirrors are off, so the burn is now near zero until the December 1 reset. Undo for any
  mirror is `PUT /repos/{owner}/{repo}/actions/permissions {"enabled": true}`. If a mirror is ever
  needed for a build, re-enable it and run one job by hand rather than leaving upstream CI live.
- Browser: no device verification of this build yet.
- Explorer/editor P1-P6: editor registry, Open With, binary banner, 2 MB truncation + Load full
  file, markdown preview via the registry, third-party viewers. Source Control: initialize-repo
  offer, generate-commit-message.
- Connectors: Wave 0 request shaping (Notion version header, Jira cloudId), then the 11 missing.
- `vn-ref-rea` and the other clones: their Actions are now disabled on purpose (2026-11-01), which
  supersedes the earlier billing block. The SDK errors recorded against them were never read, and
  reading them means re-enabling one mirror for a single dispatched job.
- Chat: the `#selection` / `#problems` / terminal-output attach kinds are still unbuilt (the
  `@` and `/` menus ship).
- Inline chat as an in-editor prompt bar with accept/reject: not built. What ships is the chip
  being reachable while the keyboard is open.
- Cumulative device round covering the two menus, notebook cell view, skills, settings, preview
  and browser, plus this round: formatter relocation, one GitHub sign-in (fe22784), keyboard-up
  chip (6f2e9a2), edit-tool protocol (6f2e9a2).

## Durable project record (backfill 2026-11-12)

The rows above record features as they landed. This section records the SEVEN projects the owner
signed off as complete, with the commit each one finished on, so a later session can find the work
without reading chat history. Verified against `git log` on 2026-11-12; CI figures are the runs that
verdict each commit.

| Project | Delivered | Commit / CI |
|---|---|---|
| Thinking Tree and chevron behaviour | One task tree per conversation; chevron-only collapse on activity rows | 8eff1e2 (tree), chevron rule in activity rows; wave-level CI green |
| Autonomous Research R1-R6 | Bounded fetch + real page text, evidence ledger, continuation across rounds | 02a959f GREEN (research) / 02a959f-adjacent wave runs |
| Clickable research sources | Sources an answer used, tappable, survive a reopen | f5a840d GREEN |
| Dynamic Tool Loading | ToolMeta index, lean offer, relevance scoring, progressive load, `tool_search` | 32e6b36 GREEN 3634 |
| Wave 0 tool + connector permission work | Connector use always asks; 14 malformed schemas made valid; allowlisted commands | 19bed0c (connector), 94dc434 (schemas), 887fb27 (commands) GREEN |
| Wave 1 Group A | Prompt carries only the offered tools; lean set; budget guard | 1b3340a GREEN 43069139441 |
| Wave 1 Groups B/C/D | Nine inspection tools: file_stat, disk_usage, copy_path, terminal_list, terminal_kill, job_status, coverage_read, entity_schema_read, memory_search | 3f4c72a GREEN 43082361285 |
| Facts and Persistent Memory | Structured facts with scope/provenance/conflicts, Facts screen, prompt retrieval, `memory_search` over the store | 1c10232 GREEN 43125607414 |

| Tool Groups, `/tools` picker, Git recovery, debugger | Grouped catalogue, four git recovery tools, seven DAP tools; 118-tool registry | 0197002 GREEN 43170924773 |
| Chat presentation (area 4) | `ToolResultPresentation` is the single ruler for a tool result; row draws kind, reason, partial marker, withheld count | 28553f4 GREEN 43171888030, d7e40e0 GREEN 43172592726 |
| Request identity (area 5) | `RequestGate` numbers each turn; a stale event cannot touch rows, tools or buffer | 471081f GREEN 43173679208 |
| Finish reasons (area 6) | `FinishReason` normalizes each adapter's stop value; absent or unknown is UNKNOWN, never NATURAL; only a ceiling continues | 0a19ccb GREEN 43176200916 |
| Continuation dispatch (area 6) | `ChatSession.continueTurn()` reuses `sendCore` with a continuation prefix and a visible seam; composer offers Continue only for a ceiling | a418aaf GREEN 43177377367 |
| Tool retry ledger (area 5) | `ToolCallLedger` + `ToolDispatchGuard` in front of `AgentTools.executeTool`; persisted, bounded, per-id serialized | a418aaf GREEN 43177377367 |
| Narrow reconciliation (area 5) | `ToolReconciler.fileWrites` proves a write by reading the target back; every other class refuses honestly | see `COPILOT-AREAS-4-6-2026-11-12.md` |

## VN Code Dynamic Tool Loading System

The umbrella initiative for how tools reach the model. Four branches, all complete and
CI-verified. This heading is the single authoritative grouping: the individual rows below are
referenced from the roadmap, not restated there.

| Branch | Status | Implementation evidence |
| --- | --- | --- |
| Lean Tool Mode | Complete | `ToolOffer.kt` (offer for one turn and the reason for it), `ToolMeta.kt` core sets; prompt carries only the offered tools - `1b3340a` GREEN 43069139441 |
| Tool Relevance | Complete | `ToolMeta` index with deterministic search and relevance scoring; lean set and budget guard - `32e6b36` GREEN, `1b3340a` GREEN 43069139441 |
| Tool Groups | Complete | Grouped `/tools` catalogue over the 118-tool registry - `0197002` GREEN 43170924773 |
| On-Demand Tool Loading | Complete | `tool_search` discovery plus progressive load, six added capabilities, metadata index - `32e6b36` GREEN |

Distinction worth keeping: **Tool Groups is the tool-catalogue grouping branch.** It is not the
Thinking Tree and not the chevron activity grouping, both of which are separate features recorded
above.


| P1 Research: selection and dedupe | `ResearchPlanner` collapses equivalent URLs and mirrored titles, caps a host at two slots while others remain, turns uncovered question terms into bounded follow-up queries, and refuses rounds past `MAX_ROUNDS` or the search budget; wired into `web_search` | `1f0ba59` baseline, this commit |
| P1 Research: source intelligence | `SourceQuality` classifies primary / official / secondary / community / weak by structure, flags staleness only when the question needs current information, and states its reason; search orders hits by source kind before engine rank | this commit |
| P1 Research: contradictions | `ContradictionScan` compares only same-subject, same-unit values and polarity, keeps both sides with sources and quotes, separates corroborated from single-source, renders "do not pick one silently" | this commit |
| P1 Research: synthesis and recovery reporting | `ResearchSynthesis` builds the brief the answer must respect: claim-level citations, corroborated / single-source / disputed / unresolved buckets, both sides of every dispute, targeted follow-ups only when a round is still allowed, and a stop reason that never reads as completion unless the evidence was enough | this commit |
| P4 Tool intelligence: planning | `ToolPlanner` reads the goal off the request, plans read-before-write steps with explicit dependencies, derives deterministic waves, flags cycles, duplicates, missing dependencies and blind writes, and drops steps already completed | this commit |
| P4 Tool intelligence: validation | `ToolResultCheck` separates OK / legitimately empty / incomplete / suspicious success / failed / malformed, so a tool that returned normally is never taken as a tool that worked | this commit |
| P4 Tool intelligence: failure policy | `ToolFailurePolicy` classifies nine failure kinds into retry / alternative / ask-user / stop, caps attempts at 2, and refuses any retry of a side effect whose outcome is unknown | this commit |
| P4 Tool intelligence: MCP selection | `McpToolPicker` ranks server tools by task relevance, drops disconnected and stale definitions, keeps server ownership, and stamps every pick `requiresApproval = true` | this commit |
| P4 Tool intelligence: context | `ToolContextTrim` keeps evidence and the latest result per tool, drops repeats, compresses long output with an explicit omitted-chars marker, and respects a total budget | this commit |
| P4 wiring: result validation and failure interpretation | `ToolResultCheck` and `ToolFailurePolicy` run inside `AgentTools.executeTool`, so every result carries its verdict note and every exception carries its classified action | this commit |
| Duplicate reply prevention | `ReplyOwnership` decides who may write an answer: send-time ownership (double tap ignored, second request replaces the first), one completion per generation, no user bubble for a retry or continuation, no restore over a live stream | this commit |
| P8.B credential host binding | A service key is only attached to the host the user configured (or that service's documented API hosts), https only, refused on lookalikes, and redirects are not followed at all | this commit |
| P7.D reply read-aloud | Speech output with Idle/Loading/Speaking/Unavailable states, one reply at a time, stop that stops, fenced code not spoken, long answers cut at a sentence and admitted; appended last in the overflow menu, off by default | this commit |
| P8.D MCP approval forced | Every `mcp_*` call now requires an explicit card: the forced-approval rule covered only `use_connector`, so AUTO_ALL silently reached third-party MCP servers | this commit |
| P3.C write verification | Every executed write is read back: verified, differs, missing or uncheckable. A failed write is reported to the user AND appended to the tool results, so the model cannot continue believing an edit landed | this commit |
| P2.B archive engine | `zip_list`/`zip_extract` open ZIP/APK/JAR/AAB, TAR, TAR.GZ, GZ, BZ2, XZ and 7Z by signature, reuse ZipEngine's Slip/bomb/ratio/containment rules, skip links, refuse RAR and ZST with a reason, and label partial lists | this commit |
| P5.A/E capability intelligence | Model lookup prefers an exact id then the longest match, honours the provider, reports how it matched, and shows an honest line when a model's limits are an estimate rather than a verified figure | this commit |
| P6.C session lineage | Continue-in-another-session records parent, sequence counted from the root, and the root's topic, names the successor `Session N from <root>`, and keeps the chain for multi-hop continuation in a separate lineage file so the strict session index is untouched | this commit |
| P4.B execution coordinator | Independent read-only calls run together, bounded at 2, on IO; writes, commands, connectors, MCP and research stay sequential and in plan order; results keyed by index so order is deterministic; a failing call cancels its wave | this commit |
| P4.A planner wired | `runToolCalls` plans the batch: dependency waves order execution, exact repeats reuse the first result instead of repeating a side effect, and the wave shape shows in the Thinking Tree. Fixes a planner defect where a dropped step blocked its dependents | this commit |
| Audit: ledger args bounded | Long tool arguments are fingerprinted (sha256) rather than stored whole, the arg map is capped at 12 keys, and reconciliation compares through the same rule so "did the write land" still works | this commit |
| Audit: continuation gate | `continuationInFlight` is now read (was write-only): a second Continue inside the send-to-first-event window is refused, a failed continuation clears the flag and offers retry | this commit |
| P8 redirect leak | Caller headers no longer survive a cross-host or protocol-downgrade redirect; the "ignored headers" note moved from a shared field onto the fetch result | this commit |
| P8 request headers | `HeaderPolicy` blocks transport-owned and credential headers, refuses casing collisions outright, redacts credential values and reports ignored headers by name in the fetch result | this commit |
| P4 context trim wiring | `ToolContextTrim` is called at the turn's send boundary: past 6,000 characters the older tool results are compressed with an explicit marker, exact repeats dropped, newest and cited results kept | this commit |
| Area 5 dispatch proof | `AgentTools.executeGated` is the production dispatch seam; 16 wrapper-level tests cover reuse, refusal, cancellation, concurrency, restoration and MCP reuse. `ToolCallLedger` terminal records now keep their arguments (a real bug: reconciliation had no evidence) | this commit |
| Area 6 lifecycle proof | `Area56LifecycleTest` binds the dispatch gate, the ledger file, the finish-reason mapping, the continuation plan and the request gate across one turn | this commit |
| P2.C RAR + Zstandard | `RarEngine` reads RAR 1.4/4.x/5.x (listing all three, extraction for 4.x/5.x) with a 64 MiB dictionary ceiling, a counting sink that stops an entry mid-write, honest refusals for RAR 1.4 extraction and password-protected archives; `ZstdEngine` decodes `.zst` as a bounded stream (64 MiB window cap, continuous frames) and `.tar.zst` as a real tar; `ArchiveEngine` gains `TAR_ZST` and now lists 7z entries instead of reporting an empty archive | this commit |
| P2 Document reading | `DocText` extracts text from PDF (content streams), DOCX, PPTX and XLSX with honest failures (encrypted, scanned, broken) and hard input/output bounds; wired into `read_file` so a document is no longer decoded as UTF-8 | this commit |
| P6 Session handoff | `SessionHandoff` extracts decisions, constraints, progress, files, citations and open points from a conversation, drops credential lines and states that approvals are not inherited; wired as "Continue in New Session" in the chat overflow menu | this commit |
| Item 29 limitations review | Every register entry re-checked against the code and a named test, with a status (STILL VALID / PARTIALLY RESOLVED / RESOLVED / DEFERRED / NOT VERIFIED), the evidence ladder made explicit, and three limitations discovered during Items 32/33 recorded (L20 logs refused by design, L21 no token refresh, L22 project-scoped credentials) | `docs/LIMITATIONS-REGISTER-2026-11-12.md` |
| Item 31 test matrix | `docs/TEST-MATRIX-2026-11-12.md` maps features to the exact test that runs, including `Not tested` rows for real-RAR fixtures and live services. Two gaps it found were filled: `AgentFlowGateScopeTest.serviceWriteAsksEvenWhenAutoApprovedAndSessionAllowed` and `ServiceApiTest.everyOperationIsWellFormed` / `everyPlaceholderInATemplateIsAValidName` | this commit |
| Item 33 additional integrations | `ServiceApi` gained Bitbucket Cloud, self-hosted Gitea and Jira Cloud, plus GitHub `run_jobs`/`run_cancel`/`run_rerun` and GitLab `job_logs`/`pipeline_cancel`/`pipeline_retry`. Auth is resolved from the service definition (Bearer, Gitea `token`, GitLab `PRIVATE-TOKEN`, Jira Basic `email:token`), so header construction is asserted per service instead of trusted. Stub/planning-tested, not live-verified | `f5fc3e5`, CI 43289925449 |
| P3/Item 32 external services (`service_read`, `service_write`) | `ServiceApi`: a registry of named operations over GitHub, GitLab and one configured custom REST base, planned by a pure function (https, `CredentialHostGuard` host binding, placeholder validation, read/write split, body bounds) and executed with no redirects, bounded time and 256 KB, a per-service rate limit, token scrubbing on the way out, and external output labelled untrusted. `service_write` is a forced-approval tool even under AUTO_ALL | this commit |
| ZIP tools (`zip_create`, `zip_extract`, `zip_list`) | `java.util.zip` engine with ZIP-Slip, symlink, overwrite and resource limits; bounded listing | see `ZIP-TOOLS-2026-11-12.md` |
| Package Intelligence (`npm_info`, `pypi_info`, `lockfile_parse`) | Registry metadata plus nine offline lockfile formats, injectable fetcher, untrusted-text flagging | see `PACKAGE-INTELLIGENCE-2026-11-12.md` |
Note on counts: Wave 1 is recorded as twelve tools in total (three in Group A, nine in B/C/D). The
Group A commit carried the prompt/toolset rework that the rest of Wave 1 depended on, which is why
the twelve are spread across two commits rather than four.

## Limitations

What does not work, and why it is not simply a bug to fix later: `docs/LIMITATIONS-REGISTER-2026-11-12.md`.
| Post-implementation testing (2026-11-13) | `DynamicToolLoadingIntegrationTest` (9 tests) tests the four Dynamic Tool Loading branches as one system; two defects fixed (`ToolLoadout` version no longer bumps on a replayed load; `ToolRelevance` gained `git status`/`zip`/`unzip`/`archive`/`merge` synonyms); L23-L25 recorded; `docs/TOOL-PROMPT-MATRIX-2026-11-13.md` holds 10 derived prompts for each of the 120 tools | this commit |
| Gate 1: an Edit-mode proposal is containment-checked when it is STAGED (refused with nothing staged) and again when it is APPLIED (Blocked, nothing written), so a proposal and a capped tool call are no longer two policies for one workspace | _pending CI_ |
| Gate 2: the persisted tool-call ledger is actually used. The chat path supplied no call id, so every dispatch minted a fresh UUID and REUSE/RECONCILE/REFUSE never fired. A write tool now carries a content-addressed id (tool + canonicalised arguments), so a repeated identical write is answered from the ledger instead of applied twice; commands, git and connectors keep fresh ids on purpose | _pending CI_ |
| P1 Research: question decomposition | `ResearchPlanner.decompose` splits a compound question into the two or three asks it actually contains: comparisons keep short named alternatives, additive prose drops thin fragments so a split never spends a search on noise, near-identical parts collapse at 0.7 token overlap, capped at three. TESTED but NOT YET WIRED: `web_search` still searches the query as given, so this is IMPLEMENTED + TESTED, not COMPLETE | _pending CI_ |
| P1 Research: decomposition wired | `web_search` now searches the asks a compound question contains when the first search came back thin (fewer than four distinct results), at most two extra asks, and says in its output which parts it searched. `ResearchPlanner.followUpSearches` holds the decision as a pure function, so the data cost is a testable rule rather than a habit | _pending CI_ |
| P1 Research: history and continuation | `ResearchHistory` keeps the last 12 questions with the sources each one read, on disk (filesDir/research-history.json, atomic write, corrupt file reads as empty). A new question that shares two content words with an earlier one reuses those sources from disk instead of fetching them again, and says so in the output. One shared word is a topic, not a follow-up, so it does not match | _pending CI_ |
| P1 Research: per-turn source scope | `SourceLedger.reset()` had zero callers while its own doc claimed the ledger is cleared per task, so a source read for an earlier question could appear in a later answer. A new turn now resets it; a continuation keeps it because a continuation is the same answer. Continuation sources are re-noted from `ResearchHistory`, so nothing is lost by the reset | _pending CI_ |
| P1 Research: cancellation stops the spending | `ResearchCancel` is a generation counter bumped by Stop; the search loop asks it before each queued subquestion, because those are blocking socket reads that a cancelled coroutine cannot interrupt. A cancelled round reports how many of its searches never ran | _pending CI_ |
| P1 Research: progress line | A round publishes what it is doing (`ResearchProgress`), and the panel reads it on a 500ms ticker so `Searching part 2 (2 of 3) 4s` is visible while a thin search escalates. Forced clear on every exit path, so a finished answer never sits under a line that still claims to be searching | _pending CI_ |
| P1 Research: structured results | A finished brief is saved as typed data in three buckets (corroborated, single-source, disputed) with source ids, so another project can read claims instead of scraping prose. `ResearchResults.forQuestion` returns the closest earlier result, `latest` the newest | _pending CI_ |
| P2 Word: document structure | `DocxOutline` reads `w:pStyle` (Heading1..9, Title, Subtitle) and `w:outlineLvl`, so a docx is reported as an outline then its text. Body paragraphs are not outline entries; a malformed file yields no outline rather than an exception | _pending CI_ |
| P2 Excel: cells and formulas | `XlsxWorkbook` reads the workbook's real sheet list (workbook.xml + rels), resolves shared strings, keeps A1 references, and reports each formula beside its result. Row order follows row numbers, not file order; unreadable file falls back to the old flat read | _pending CI_ |
| P2 PowerPoint: deck order and titles | `PptxDeck` walks `presentation.xml`'s `sldIdLst` through the rels, so slides come out in the author's order, with each title placeholder separated from its bullets and speaker notes read where they exist. File-name order is only the fallback | _pending CI_ |
| P2 Word: creating documents | New tool `docx_create` writes a real .docx (four parts, heading styles, bullets, escaped markup) from markdown-ish content. Refuses an existing path and any extension but .docx | _pending CI_ |
| P2 Word: editing documents | New tool `docx_edit` appends to an existing .docx by rewriting only `word/document.xml`; every other package part is copied byte for byte, and the write goes through a temp file so a failure cannot destroy the original | _pending CI_ |
| P2 Excel: writing workbooks | New tool `xlsx_write` creates a real .xlsx from CSV (`## Name` starts a sheet); a cell starting with `=` becomes a formula, numbers stay numbers, `fullCalcOnLoad` makes Excel calculate on open. Illegal sheet-name characters are cleaned rather than dropped | _pending CI_ |
| P2 PowerPoint: creating decks | New tool `pptx_create` writes a structurally complete .pptx (presentation, master, two layouts, theme, slides with title and body placeholders), because a package missing that chain opens as a repair prompt. Not opened in PowerPoint yet | _pending CI_ |
| P2 media: audio and video metadata | New tool `media_info` reads format, duration, sample rate and channels from the header (wav, flac, mp4/m4a/mov, mp3). Exact durations say exact; an MP3 estimate says estimated. Reads the first 4MB only | _pending CI_ |
| P2 media: image metadata | `media_info` now also answers for images from their headers alone (png, gif, webp, bmp, jpeg), including EXIF orientation, so a rotated phone photo is reported as displayed rather than as stored | _pending CI_ |
| P2 media: image conversion | New tool `image_convert` converts to jpeg/png/webp and shrinks to a long edge, reading bounds first and decoding at a power-of-two sub-sample so a 12MP photo never allocates 48MB. Refuses to overwrite and never upscales | _pending CI_ |
| P2 media: video frames | New tool `video_frames` writes up to 60 JPEG stills planned evenly across the duration, never at 0ms or the very end; retriever released in a finally block; existing files never overwritten | _pending CI_ |
| P2.12 OCR | New tool `image_ocr`: bundled ML Kit Latin, reading order reassembly with paragraph grouping, column splitting, code indentation recovery, confidence reported only when the engine gives it | _pending CI_ |
| P2.14 voice selection | `VoiceSettings`: male-English preference chain that admits when it cannot honour the preference, local-only filter, pitch/rate bounds, recipe 0.95 pitch / 1.02 rate | _pending CI (awaiting push approval)_ |
| P2.15 VAD | `VadPolicy`: calibration at start, adaptive noise floor, hysteresis start/stop, truncation cap, RMS and peak | _pending CI (awaiting push approval)_ |
| P2.16 speech script | `VoiceScript`: fences replaced, tables and markers stripped, sentence segments for progressive speech, oversize cut at commas | _pending CI (awaiting push approval)_ |
| P2.17 turn machine | `VoiceTurnMachine`: one submission per utterance, stale turn refused, barge-in stops speech, background closes mic, stop releases all | _pending CI (awaiting push approval)_ |
| P2.18 recognition plan | `RecognitionPlan`: on-device required when available, cloud only with consent and disclosed, never a silent fallback | _pending CI (awaiting push approval)_ |
| P2.19 provider catalogue | `VoiceProviders`: Edge marked UNSUPPORTED, Gemini CONFIGURABLE without a key, fallbacks reported as degraded | _pending CI (awaiting push approval)_ |
| P2.20-2.24 Android layer | `VoiceCapture` (AudioRecord, 16 kHz, 20 ms frames), `VoiceRecognizer` (no recogniser activity, honest error text), `AndroidTtsProvider`, `VoiceSettingsStore`, `VoiceController` | _pending CI (awaiting push approval)_ |
| P2.25-2.27 interface | `VoiceModeScreen` orb driven by measured amplitude, four phases plus error, provider strip, `Voice` header chip opens it, `VoiceSettingsSection` in Settings | _pending CI (awaiting push approval)_ |
| P3.1 task plan | `TaskPlanEngine`: objective-level steps with dependencies and milestones, evidence required to complete a step, failures skip dependents transitively, one recovery per step (no retry chains), at most six revisions, cycles and dangling dependencies refused with the offending path | _pending CI_ |
| P3.2 plan state and tool | `PlanState` holds one plan per conversation; new tool `task_plan` (action create/start/complete/fail/show/clear) answers with the plan's pending, running, done, failed and skipped steps, so the work is visible in the transcript. The plan records intent and evidence; it never executes a step | _pending CI_ |
| P3.3 subagent roles | `SubagentRoles`: ten roles (planner, researcher, coder, debugger, tester, docs, file_analysis, security, review) each bound to `ToolMeta` categories; `scope()` intersects a role with what the session already permits, drops every destructive tool for read-only roles, reports withheld tools instead of silently granting or hiding them, and refuses an unknown role or an empty intersection with the reason | _pending CI_ |
| P3.4 bounded delegation | `DelegationScheduler`: a task board with QUEUED/RUNNING/COMPLETED/FAILED/CANCELLED/BLOCKED, where parallel is a privilege (read-only, independent, capped) and a write runs alone in plan order; two writers on one path are BLOCKED with the holder named; a duplicate request is answered from the first task; cancellation reaches everything; a child board is strictly smaller (depth, agents, tasks, tools intersected with the parent task's scope); completion without evidence is recorded as a failure; a read result from before a project change is stale and unverified | _pending CI_ |
| P3.5 safe recovery | `RecoveryPlanner`: nine failure categories mapped from the existing `ToolFailurePolicy` taxonomy, a closed set of eight repairs, and a `Plan` that keeps the original error verbatim and states why nothing was applied when it was not. Read-only repairs (re-read, resolve a stale path inside the project, narrow a range, one retry) apply only when the call has no side effect, its outcome is known, Ask mode is not in force and the repair budget is unspent; a write, an unknown outcome, a permission refusal or a dependency failure becomes a proposal. `verdict()` refuses to call a repair recovered without evidence, and `Ledger` keeps the original error, the repair and its outcome | _pending CI_ |
| P3.6 execution reflection | `ReflectionEngine`: the verdict is derived, never supplied. It compares the objective, the plan, the actions the tool layer actually recorded and graded evidence (IMPLEMENTED < TESTED < CI_GREEN < DEVICE_VERIFIED). A completion with no observation, with \"done\"/\"no exception thrown\", or vouched for by the executor itself, is UNSUPPORTED; a step done before its dependency, or over a failed tool, is a CONTRADICTION; disagreeing subagents, unverified delegated results, blocked delegations and a recovery that did not hold all count; failed and skipped work stays in the report; a criterion must be met at the level it asks for. `headline()` never claims more than the reflection does | _pending CI_ |
| P3.7 Project Three integration | One recording point in `AgentTools.executeToolBody` feeds `ReflectionState` (session-scoped, bounded at 200 actions and 40 criteria), so reflection judges what the session actually observed rather than the model's account; `task_plan action=reflect` audits the live plan on demand with optional criteria and observations, and returns a verdict rather than a summary; nine integration cases run plan -> role selection -> bounded delegation -> permitted recovery -> reflection, including the lying variants | _pending CI_ |
| P3.8 runtime integration | The policy layers now run during real conversations. `RuntimeBridge` is called from `AgentTools.executeTool`: before dispatch the live plan can refuse a call (naming the stalled steps, never blocking `task_plan` itself, which is the only exit from a dead plan); after dispatch the real output is recorded in `ReflectionState` as an observed action; on failure `RecoveryPlanner` decides, at most one permitted repair runs through the same body, and it is validated before it may be called a recovery. `task_plan action=delegate` builds a `DelegationScheduler` board whose allowed tools are what the current mode exposes, narrowed by the role; a task naming a tool its role lacks is refused at enqueue and `task_plan` cannot be delegated. `RuntimeSession` carries the live Ask/Edit/Agent mode and defaults to Ask. Tested: 20 runtime cases including a dead-plan refusal, the deadlock exit, a role reaching a tool the session lacks, a repair that throws, and evidence-versus-claim reflection. |
| P4.1 capability coverage and tool health | `ToolCapabilityPlan` reads a request, names the capabilities it needs (find + replace for a project-wide rename, test, build, git, diff, web, shell, structure, edit, read) and classifies each as OFFERED, LOADABLE, OTHER_MODE or MISSING, so a gap is named before the work starts instead of after a refusal. The offer note carries it. `ToolHealth` remembers real dispatch outcomes: two consecutive failures demote a tool in the ranking by 30, a success clears the streak, the map is capped at 40, and it reorders proposals only - it never disables a tool or touches a permission decision. Fed from `RuntimeBridge.afterDispatch`. Tested: 8 cases against the real registry, including Ask-mode rename naming the mode gap and a demoted tool still being allowed. |
| P4.2 dependency awareness | `ToolPrerequisites` (capability graph + tool→capability table), `DependencyPlanner` (waves, cycle refusal, IMPOSSIBLE/BLOCKED dependents, optional edges never promoted), `ToolTaskCoverage` (whole-request coverage reusing 4.1's assessment) and `PrereqLedger`. Enforced in `RuntimeBridge.prerequisiteRefusal`, called from `beforeDispatch` in the live dispatch path, recording real outcomes from `afterDispatch`. No permission path changed: the gate runs before the existing mode/approval/MCP/role checks. 26 cases. |
| P4.3 orchestration and persistence | `Orchestrator` executes dependency waves: read-only steps concurrently behind a bounded semaphore (2 by default, max 3), writes and destructive steps alone; a failure stops later waves and marks them skipped by name; throws are failures; cancellation is reported as CANCELLED and never as success. `RuntimeBridge.delegate` now runs its wave through it. `PrereqStore` persists the ledger to `filesDir/prereq-ledger.json` (atomic rename, 200 entries, 240 chars of evidence, structure only); `PrereqLedger.attach` binds it per conversation and revision and a corrupt file is reported untrusted. `OrchestrationResume` classifies restored evidence: done writes not repeated, uncertain writes need the owner, cheap reads retry-safe, foreign or stale evidence blocked. 16 cases. |
| P4.5 result validation | `ToolOutputContract` + `ResultValidator` (eight outcomes) + `SideEffectVerifier`, wired into `AgentTools.executeTool`. Only validated evidence satisfies a prerequisite; truncation, validator bugs and cancellation no longer count against tool health. 14 new cases. |
| P4.6 selection and verification | `ToolSuitability` (eligible-mandatory, six documented ranking signals, explanations), `ToolCapabilityPlan` picks the best candidate, `GitStateVerifier`, `ArchiveVerifier`, `OutcomeVerifier`, `EvidenceReconciler` (contradiction vs legitimate change vs stale). 31 new cases; in-memory observation digests are a stated limit. |
| P4.7 persistence and fresh state | `EvidenceStore` (atomic, bounded, restart-surviving observations), `GitFreshVerifier` (read-only re-read, real temp repos), `ArchiveDestinationVerifier` (disk state, types, sizes, traversal), eleven suitability signals with unknown staying unknown, `ClaimReconciliation` (supported/conflicting/unsupported/unverified, duplicate-source identity). 48 new cases; branch `stage-4.7`, not pushed, so not CI-green. |
| P4.7 completion | Fresh git/archive verification routed through `OutcomeVerifier` in the live gate, research claims reconciled into the brief and persisted result, `ProviderContext` (signal 11: workspace plus provider state, stale-safe, no secrets), four reply-identity rules added to `ReplyOwnership`. 26 new cases; `ChatSession` delivery rewiring deferred. |
| P4.8 delivery ledger (superseded by the two rows below) | `DeliveryLedger`/`DeliveryStore` wired into `ChatSession` at the send, event and completion boundaries; per-submission execution ids, session-scoped replacement, cancellation closing delivery, atomic bounded record, corrupt record = UNKNOWN. 21 cases. Store not yet attached in-app; no ViewModel integration test. Branch `stage-4.8`. |
| P4.8 completion | `DeliveryStore` attached per conversation (`filesDir/chat-delivery/<conv>/`), lazy restore, submission identity including submission time (fixes refusal of a legitimate re-ask). 11 runtime integration tests over the real `ChatSession` + fake transport. 3,315 local tests, `BackupTarTest` unchanged. Locally tested; CI-green established by the commit below. Not device-verified. |
| P4.8 follow-up continuation | Continuation rows absorbed by containment (no duplicated opening text); continuation flag derived from the prefix; answer-only delivery right. 3,326 local tests, `BackupTarTest` unchanged. Tool dispatch component-tested only (no Robolectric). **CI-green**: run `43838917586`, job `build`, success, on `50505e8`. Device-verified: no. |
| P5.1 provider capability intelligence | `ProviderCatalogue`/`ProviderSelector`: declared capabilities with nullable (never guessed) limits, SUITABLE/RISKY/UNSUITABLE assessment with reasons, unknown provider = RISKY, and a selector that never switches the user's model (offers an alternative instead). 11 cases. CI-green: run `43850633942` job `build` on `27a08b6`. Not device-verified. |
| P5.2 cross-provider tool-call and stream normalisation (component) | `ToolCallNormaliser` (Gemini/OpenAI/Anthropic/text wire -> `NormalisedToolCall`, unknown tool refused), `ToolExecutionGateway` (validates then delegates to `AgentTools.executeTool`, no permissions of its own), `ToolResultNormaliser`, `ProviderStreamNormaliser` (bounded buffers, INCOMPLETE_TOOL_CALL/OUTPUT_LIMIT/UNKNOWN kept distinct), wired into `ChatSession.runTools`. 25 cases. CI-green: run `43863485364` job `build` on `4ea3b96`/`8998135`. Not device-verified. |
| P5.2b normaliser on the live streaming path | Dialects moved into `engine` (`ToolCalls`, `ProviderStreamNormaliser`, dependency-free `JsonLite`); `ProviderAdapter.streamKind`; `ChatEngineImpl` renders a COMPLETE native call into the text protocol the reply path parses and drops an incomplete one with a `Truncated` note; app keeps `WireCalls` + `ToolExecutionGateway` -> `AgentTools.executeTool`. 5 engine runtime tests via fake transport with real `SseParser` and adapters. CI-green: run `43863485364` job `build` on `8998135`. Not device-verified; no live provider contacted. |
| P5.3 cross-provider error intelligence and recovery | `engine/ProviderErrorModel.kt` (facts from Gemini/Anthropic/OpenAI shapes, symbolic code over numeric, code-before-status classification, redaction, 15 classes), `RecoveryPlanner` (9 actions, delegates retry arithmetic to existing `ProviderRetry`), `SideEffectRecovery` + `ToolOutcome.UNCERTAIN` (a throwing executor is unsure, never failed; uncertain writes never retried), `ChatFailureAdvice.adviceFor` on the live advice path. 20 engine + 13 app cases. CI-green: run `43868731369` job `build` on `52f36e1` (ancestor of the tested `6b95b70`). Not device-verified. |
| P5.3b typed engine-to-app error propagation | `ChatError.facts` written once by `ErrorTaxonomy.classify` (which now extracts evidence before choosing the verdict and receives providerId/modelId), read by `ChatFailureAdvice.adviceFor`/`planFor`, and used by the live failure row in `ChatSession`. A 400 with no readable evidence stays UNKNOWN. 10 engine + 9 app cases from `ErrorTaxonomy.classify` to the rendered advice. CI-green: run `43868731369` job `build` on `6b95b70`. Not device-verified. |
| P5.4 cross-provider tool-pair continuity | `ConversationContinuity` canonical `ToolExchange` + `ExchangeStatus`, Gemini/OpenAI/Anthropic renderers with id, order and status preservation, refusal for unknown endpoints, id dedupe, string-safe `splice`; `ProviderAdapter.payloadWithHistory` (default plain history) with the three adapters opting in; `ChatEngineImpl` carries `exchanges` into the main and retry payloads. 15 engine cases including an end-to-end payload assertion. Producer side wired in P5.4p2; Area E context reduction NOT implemented. CI-green: run `43884356305` job `build` on `c129b30`. Not device-verified. |
| P5.4p2 tool-exchange producer | `ToolExchangeLog` written from `ChatSession.noteAndRecord` (stable `wire-<turn>-<index>` ids, status read from the result text, bounded 60, dedupe by id) and read through a supplier on `ChatEngineImpl` at payload time. 12 app cases ending in a transport-payload assertion. No JVM test drives `runTools` (needs Context); refusals before `noteAndRecord` not logged; log not persisted. CI-green: run `43884356305` job `build` on `c129b30`. Not device-verified. |
| P5.4p3 refusal recording | `ChatSession` records gate-level refusals (`WritePolicy.refuse`, approval declined/timed out) through `recordRefused` with the shared `exchangeId(idx)`; `NOT RUN` maps to REFUSED; a refusal cannot be overwritten by a later result; Anthropic renders it `is_error`. 3 new cases (15 in `Stage54bProducerTest`). Runtime Android-aware testing of `runTools` NOT undertaken (Robolectric/instrumentation download needs owner approval). CI-green: run `43888770297` job `build` on `f44dac6`. Not device-verified. |
| P6.1 context compatibility | `engine/ContextPreparer` decides FITS / REDUCED / LIMIT_UNKNOWN / OVERFLOW from catalogue limits only, subtracting the output allowance from the input budget and naming the char-per-token estimate; `ChatEngineImpl.lastContextDecision` records it on every send. Exchanges are rendered after the fitted window, so Five's call/result pairing survives reduction. 9 engine cases. No summarisation; OVERFLOW is reported, not blocked before dispatch. CI-green: run `43892624026` job `build` on `52d264b`. Not device-verified. |

| P7.4 context notice | `ChatSession` shows one `InfoNote` when the last send left turns behind or could not fit, and again only when the sentence changes. CI-green: run `43892624026` job `build` on `52d264b`. Not device-verified. |

| P8.1 output bounding + credential audit | Exchange outputs cut at 4,000 chars with the cut stated in the text; four-part credential audit over the Stage 5.4 and 6.1 diffs (no key-shaped strings, log not persisted, credential-free origin, Firebase config untouched). 2 cases. CI-green: run `43892624026` job `build` on `52d264b`. Not device-verified. |
| P6.2 summarisation + retention | Extractive `Summariser` (verbatim excerpts, ids, literal-marker labels with failures/uncertainty ranked first, neutral `OTHER` when no evidence), `SummaryLedger` (24 lines, never re-summarises a covered id, admits what fell off the bound), summary counted INSIDE the send budget and capped at 25% of it, carried as a `SYSTEM` message with the synthetic id `summary-6.2`. 16 engine cases. No compression; not persisted; exchanges reach a continuation as labelled text, not provider pairs. CI-green: run `43896451765` job `build` on `78d51e4`. Not device-verified. |

| P6.2B continuation | `Continuation` (label `Session 2 from git`, inert exchange records, no executor/approval inheritance) wired through `SessionHandoff.generatedSummary` + `ChatSession.generatedSummary` + `startHandoffSession`, so the successor starts with what the predecessor left out. 3 app cases. UI path only; no JVM test drives the manager. CI-green: run `43896451765` on `78d51e4`. Not device-verified. |

| Backend prober (`backend-keepalive`) | Cold-start red run fixed: `scripts/backend-health-probe.sh` retries with a 120s cold-start timeout, `--fail` so a 503 can never pass, non-zero exit when all attempts fail; `scripts/test-backend-health-probe.sh` covers six behaviours and runs in both workflows. CI-green: run `43903082582` (HTTP 200 in 42.4s) on `7ee6868`. Not the same thing as ProviderCapabilities, ToolHealth, ProviderSelector or Project Five error intelligence. |

| P6.3A provenance + certainty | `SummaryLine` carries `status` (CONFIRMED/ASSUMED/UNRESOLVED/UNKNOWN, negatives checked first), `origin` (MESSAGE/TOOL_EXCHANGE/CARRIED_SUMMARY) and `seq`; rendered text marks `[assumed]` / `[unresolved]`. Locally tested. |

| P6.3B corrections | `SummaryLedger.absorb` supersedes a NEWER statement of the same subject (3 shared distinctive words + 60% containment), never supersedes failures, states how many were replaced; duplicates still stored once. Locally tested. |

| P6.3C retrieval | `HistoryRetriever` quotes older turns VERBATIM under `RETRIEVED FROM EARLIER TURNS`, eighth of the budget, max 3 turns, never re-quotes the window or the summary, no executor on the path. Locally tested. |

| P6.3D retention store | `SummaryStore(filesDir/chat-retention)`: atomic temp+rename, session-keyed with foreign-session refusal, corrupt reported with a reason and rebuilt, 20-session bound, secret redaction, `false` when unwritable. Wired through `ChatSession.buildEngine`. Locally tested. |

| P6.4 handoff guard | `HandoffGuard` reuses an empty successor instead of minting a duplicate "Session 2 from git"; a successor that already holds a turn is a conversation of its own, so a new branch is created. Wired into `startHandoffSession` via `SessionLineage.childrenOf`. 4 app cases. Locally tested. |

| INTEGRATION 4.8 + Five/Six | `main` `9754f66` -> `55d36fd` via `--no-ff` merges `9d94455` (Stage 4.8, 6 commits) and `58f6baa` (Projects Five + Six, 26 commits); one docs conflict resolved by keeping BOTH sides; `ChatSession.kt` auto-merge hand-checked for every project's markers; `BackupTarTest` made privilege-aware rather than assumed harmless. app 3408 tests / 0 failures, engine 278 / 0, compile + D8 green. CI-green: run `43910173612` job `build` on `55d36fd`. Source branches intact (`4a96def`, `a67a238`). Not device-verified. |

| P7 first pass - outcome truth (7.5) | `CopilotPresentation.outcome`: RAN = YES/NO/UNKNOWN, retry safety per outcome, UNCERTAIN and CANCELLED never failed nor safe to retry, refusal never ran, success offers no action. Locally tested. |
| P7 first pass - bounded tool rows (7.4) | `toolRow` flattens and cuts at 600 chars stating both numbers. Helper tested; the tool-result row is NOT yet rewired to it. |
| P7 first pass - task states (7.3) | `taskState` (unknown word -> UNKNOWN, never DONE), `taskTree` on runtime ids, `progress` counting only reported states. Presenter tested; the Thinking Tree is NOT yet bound. |
| P7 first pass - carried context (7.7) | `carriedContext` + `kindsFromRenderedSummary`; `startHandoffSession` now returns what the successor carries, retention-on-disk checked, exchange count 0 because the path does not expose it. WIRED. |

| P7 cp2 - 7.3 tree binding | `CopilotBindings.taskState/taskNode/groupHeader/childLine`; `ChatCards` header counts reported states, children spell the state out, identity is the runtime row id. `ok=true` is ACTIVE, never DONE. 7 cases. Locally tested. |
| P7 cp2 - 7.4/7.5 tool row | Row detail bounded at creation via `CopilotPresentation.toolRow`; `outcomeOf`/`nextAction` give YES/NO/UNKNOWN, refusal never ran and retryable, uncertain may-have-run and not retryable. Locally tested. |
| P7 cp2 - 7.9 accessibility | Tree header and single tool row are 48dp `fillMaxWidth` controls with merged semantics descriptions; inner competing clickables removed; `CopilotAccessibility` supplies tool/group/message labels and one distinct word per state. 8 cases. Locally tested; NOT device-verified. |

| P7 cp3 - 7.9A contrast | `ContrastAudit` measures the real `ChatTokens` pairs for both themes; nine pass AA, the hairline divider fails 3:1 and is documented as decorative (tested), `strongBorder` derives a compliant edge for meaningful borders. 6 cases. Locally tested; visual verification on device outstanding. |
| P7 cp3 - 7.9E announcements | `CopilotAccessibility.announcement`: start, every 400 chars, and every terminal transition; small growth silent; bounded tail. 6 cases. Helper tested; not yet called from the render path. |
| P7 cp3 - 7.6 picker | Header shows `Provider·Model` in `ModelPickerSection` (WIRED) with a TalkBack description; `ModelPickerPresentation` gives capability honesty, custom-endpoint non-assumption, optional alternatives and `selectionAfterDismiss`. 9 cases. Selection logic untouched. |

| P7 cp4 - 7.6A list state | Audit: no network fetch exists, so no loading state was invented. `listNote` (WIRED in `ChatPanelScreen`, null renders nothing) states a never-reported endpoint and an empty list truthfully. 4 cases. |
| P7 cp4 - 7.6B errors | `listError` classifies network/auth/rate-limit/empty/malformed/custom; tests assert no status codes, no credentials and no silent-switch claim. Not wired to a fetch path, because none exists. 3 cases. |
| P7 cp4 - 7.9A truncation | Source rows keep their single-line layout and gain a merged contentDescription with the full title, domain and opens action. No visual change. |
| P7 cp4 - 7.9C dialog titles | `RowActionsDialog`, `FilterDialog`, `KeysManagerDialog` titles marked `semantics { heading() }`. `ChatLogDialog` open for a dedicated pass. |

| P7 cp5 - 7.9C closed | `ChatLogDialog` title carries `semantics { heading() }`; all four Project Seven dialog titles are headings. Semantics-only, no pixels changed. Manual TalkBack check listed. |
| P7 cp5 - 7.1D scroll | `ChatScrollFollow.shouldFollow` (follow within 96dp of the live end, never after the reader scrolled back), WIRED in `ChatPanelScreen` on row count + streaming; cancelled scroll swallowed. 5 cases. |
| P7 cp5 - 7.1C copy target | `CopyIconRow` gains `minimumInteractiveComponentSize()` (48dp touch area, glyph unmoved) and `role = Button`. Copy payload audited: message text only; code Copy keeps the whole block. Manual tap/TalkBack check listed. |
| P7 cp5 - 7.1/7.2 audit | Message typing, incomplete/ceiling marking, memoised Markdown, capped code with full copy, one-send and duplicate-reply protection, cancellation and stale-event generation bump, tool-to-round identity, announcement policy: verified as already correct in existing code and suites; not rebuilt. |

| P7 cp6 - Copy target restored | 48dp expansion reverted at the owner's explicit request: target is the glyph's own 30x24dp, glyph unmoved, role and action label kept, no parent hitbox. `CopyTarget` + 4 cases pin it, including a guard against the modifier returning. |
| P7 cp6 - 7.8 sources bounded | `SourcesRow` draws at most 40 and states the exact remainder; null when nothing omitted. `ResearchPresentation` supplies the header count, per-source TalkBack sentence and empty-research wording. WIRED. 8 cases. |
| P7 cp6 - 7.8 documents/media | Audited: rows carry real execution status and bounded detail; no inspection/transcription/analysis claim exists and none was invented. No change made. |

## Chat composer extras (2026-11-17)

- **Add to Queue**: while an answer streams, a typed message can be queued instead of cancelling the
  answer. FIFO, max 10, duplicates refused, drained only when the turn genuinely ends, and skipped
  (kept, not lost) when the turn failed or was cancelled. Implementation: `chat/panel/ChatQueue.kt`
  plus `ChatSession.queueMessage` / `dropQueued` / `drainQueueAfterTurn`. Not persisted across a
  process death - it is an in-memory queue for the live session.
- **Steer Message**: the instruction is folded into the next tool-result continuation of the same turn
  (`Steering.apply`), so the turn is corrected without restarting it. Not supported for a single-shot
  answer that makes no tool round, and it does not claim to be.
- Both entries were previously disabled placeholders; `ChatSendState.actionOf` now returns `QUEUE` /
  `STEER`, never `STOP`.
- **Cloudflare (verified live 2026-11-17)**: use base `https://api.cloudflare.com/client/v4/accounts/<account_id>/ai/v1`
  (OpenAI-compatible, SSE streaming confirmed) or the inference path `.../ai/run/@cf/<model>` (also works;
  its response is unwrapped). The endpoint test always asks `.../ai/models/search`, because neither
  `/ai/v1/models` nor `/ai/run/<model>` accepts GET - that GET is what produced the reported 405.
  Response shape on `/ai/run/<model>`: `{"result":{"choices":[{"message":{"content":...}}]}}` - an ordinary
  OpenAI completion inside `result`, unwrapped by `ProviderStreamNormaliser`. Device status: chat over
  `.../ai/v1` is device-proven on the `ae14a1d` APK; the catalogue fix, the `/ai/run` handling and
  streaming are not yet device-tested.

## 2026-11-20 - Tool-calling architecture, Stages 1-4 (CI 44696877512, 44697191921, 44697252429, 44697500267)

- Stage 1 `d00931d`: `ToolCallScan` replaces the lazy `<(tool|tool_call)>` regex with a scanner that
  tracks JSON string and escape state, so nested objects, arrays, braces inside strings, escaped
  quotes and backslashes, and unicode all parse whole. `ToolCall(id, name, args, source)` is the one
  normalized shape; the `<tool>` JSON dialect and the Hermes/Qwen XML dialect both produce it.
  Parse failures are typed and yield no call: the previous path fabricated a call to a tool named
  "error", which no tool answers.
- Stage 2 `6ba0d94`: `ProtocolStreamFilter` holds a tail that could still become an opener and holds
  a whole call region until it closes, so a marker split across chunks is never painted as prose.
  Wired into the live delta path and reset per turn; an unfinished call at turn end is dropped.
- Stage 3 `f52addd`: `NativeToolCalling` shapes declarations for OpenAI-compatible, Anthropic and
  Gemini and parses each provider's structured call back into the same `ToolCall`. `wireFor()`
  requires the TOOL_CALLS capability AND a verified profile; custom endpoints stay on text even when
  verified, because OpenAI-compatible services do not share one contract. No endpoint is marked
  verified yet, so native calling is inert in production by design.
- Stage 4 `380e2f3`: measurement harness records CHARACTER counts only and prints "Token measurement
  unavailable for this endpoint or protocol." No percentage improvement is claimed anywhere.
- Tests: 20 scanner, 14 filter, 14 native, 4 measurement. Full suite 3793, 4 skipped, 0 failures.
- Device status: NOT device-tested. The leak fix is only observable on a live streamed turn.

---

### [2026-11-20 08:05 WAT] [TOOLS] P2 - context offloading

- `ContextOffload`: past 12,000 characters a tool result is written complete to
  `files/workspace/offload/<project>/<session>/<tool>-<hash>.txt` and the model receives a pointer,
  an explicit "not shown in full" line, and 1,000-character head and tail excerpts.
- Small results are untouched; the existing `TOOL_RESULT_BUDGET_CHARS` trimming still runs on top.
- The same output twice reuses one file. References are refused if they escape their session
  directory, so one session cannot read another's scratch output.
- A failed write says so plainly and says the full output is NOT available - it never pretends to
  be complete and never loses the text silently.
- Retention: scratch files older than 7 days are deleted on the next offload; a cleanup failure
  never fails the tool call.
- Wired at the single result path in `ChatSession.executeOnce`, so approvals and the execution gate
  are unchanged.
- 14 tests including a simulated 5 MB build log. Not device-tested.

---

### [2026-11-20 08:30 WAT] [TOOLS] P3 - lexical retrieval

- `LexicalRanker`: identifier-aware tokenization (snake_case, camelCase, paths), exact-phrase and
  exact-identifier bonuses, path bonus, explainable reasons per score, duplicate-id collapse.
- Short queries (under 3 chars) score on tokens only, so a one-letter query cannot win by substring
  and drown real matches in false positives.
- Integrated into `grep_search`: matches are ordered so exact identifiers, filenames and phrases
  lead. Nothing is filtered or dropped, so no result disappears to make the ranking look good.
- Semantic retrieval is untouched; `fuse()` combines the two with explicit weights so a surprising
  order can be traced.
- Offloaded P2 references are rankable as plain text but never resolved by the ranker - only the
  caller's scoped read may open them.
- 14 fixture tests (5 documents). This measures deterministic routing, not model quality.

---

### [2026-11-20 08:55 WAT] [TOOLS] P4 - observability lite

- `CopilotDiag`: bounded in-memory ring (200 events) of typed events - SESSION, TOOL_START,
  TOOL_END, APPROVAL, OFFLOAD, RETRIEVAL, PROVIDER_ERROR, CONTINUATION - each with time, session
  correlation id, name, ok flag and a short detail. No network, no file writes, no telemetry.
- Redaction runs on every field before it is stored: bearer headers, provider key shapes and
  key=value credentials are stripped. Raw prompts and complete tool outputs are never recorded.
- Events die with the process by design; that is stated, not hidden.
- HONEST GAP: the ring is not yet called from `executeTool` or the chat path, so no events are
  produced at runtime. The component and its tests exist; the wiring does not. Recorded here so
  nobody reads this entry as "diagnostics are live".
- 14 tests.

---

### [2026-11-20 09:30 WAT] [TOOLS] P3 completion - fusion wired into real retrieval

- Closes the gap recorded at 08:30: `LexicalRanker` alone was not retrieval. `RetrievalFusion` now
  normalises the semantic hit count to 0..1, weights it (1.0) above the lexical score (0.45), and
  returns the reasons behind each order. Exact identifiers and phrases can lead; a weak substring
  match cannot outrank a strong semantic one.
- `semanticSearch` in AgentTools now ranks through the fusion. The term count is still computed and
  kept, and every matched file still reaches the caller - fusion reorders, it never filters.
- `FactRetrieval.rank` adds +10 for an exact phrase and +8 for an exact identifier in fact text.
  Scope, status and `belongsTo` filters run first, so the bonus can never lift a fact out of its
  scope or revive a superseded one. Provenance and confidence scoring are untouched.
- No second memory store, no second retrieval engine, no change to fact persistence.
- Tests: 14 fusion + 6 Facts & Memory retrieval (22 facts-package regression alongside). Fixture is
  small and labelled as such; this measures deterministic routing, not model quality.

---

### [2026-11-20 09:50 WAT] [TOOLS] P4 completion - diagnostics emit real events

- Closes the gap recorded at 08:55 ("no events are produced at runtime"). `CopilotDiag.trace()`
  now wraps the single execution gate: `AgentTools.executeTool` delegates to `executeToolInner`
  through it, so every call records TOOL_START and exactly one TOOL_END carrying outcome, elapsed
  ms and result size. A "FAILED" result records ok=false; a thrown exception is recorded then
  rethrown unchanged; the returned string is never altered.
- `ContextOffload.apply` records an OFFLOAD event with the real size and whether the write
  succeeded - including the failure case. A small result records nothing.
- `semanticSearch` records a RETRIEVAL event with candidate count and returned count.
- Every diag call is wrapped in runCatching, so a diagnostic fault can never change execution.
- Still NOT wired: approval-required / approved / denied / cancelled events, provider request and
  retry events, continuation events, and any UI surface for reading the ring. The ring is in
  memory only and dies with the process. These remain open and are recorded as such.
- Tests: 11 runtime tests that execute the real traced pathway and read the events back, plus the
  14 existing component tests.
- Full suite after this work: 3866 tests, 4 skipped, 0 failures.

---

### [2026-11-20 10:40 WAT] [TOOLS] P4 completion - persistence, approval and continuation

What is now real:

- Persistence (P4.4): `CopilotDiag.persistTo(file)` appends JSONL, `loadFrom(file)` reads it back
  with a 500-event / 3-day retention and skips malformed or torn lines. Ring raised 200 -> 500 so
  a reloaded history is not silently halved. 12 tests including a restart simulation, ordering,
  retention, expiry, corrupt line, torn final write, unwritable path and redaction-before-disk.
- Approval (P4.1, partial): `WritePolicy.decide` in ChatSession records APPROVAL_GRANTED (ALLOW),
  APPROVAL_REQUIRED (STAGE) and APPROVAL_DENIED (REFUSE) at the point the decision is made. The
  decision value is computed first and used unchanged.
- Continuation (P4.3, partial): `continueTurn` records CONTINUATION_SKIPPED with the state when the
  plan is not allowed, and CONTINUATION_STARTED with the finish reason when it is.
- Event taxonomy extended with the approval, continuation and provider kinds; 9 lifecycle tests
  cover distinctness, denial-not-grant, skipped-not-started, correlation and redaction.

What is NOT real, stated plainly:

- `persistTo`/`loadFrom` have no call site yet. Nothing invokes them at startup or shutdown, so
  diagnostics do NOT currently survive a restart in the app, even though the storage works and is
  tested. The acceptance criterion "diagnostics survive process restarts" is therefore NOT met.
- PROVIDER_REQUEST_START/SUCCESS/FAILURE exist as kinds only. No provider pathway emits them; the
  provider request layer was not instrumented.
- CONTINUATION_COMPLETED and CONTINUATION_FAILED are not emitted; only started/skipped are.
- APPROVAL_CANCELLED is not emitted; user cancellation in the approval sheet is not instrumented.
- MCP hard approval and general Agent permission settings were not separately instrumented or
  tested; the WritePolicy hook does not cover them.
- No diagnostic history UI exists.
- The ChatSession call sites are compile-verified only. No unit test drives them, so their runtime
  emission is unverified.

---

### [2026-11-20 11:20 WAT] [TOOLS] P4.4 wiring - diagnostics now survive a restart

- Closes the gap recorded at 10:40. `CopilotDiag.attach(file)` is called in
  `CodeSpaceApplication.onCreate` against `filesDir/diagnostics/copilot-events.jsonl`, restoring
  whatever the previous process left and pointing the sink at that file.
- `record()` flushes every 5 events; `flush()` writes on demand; `detach()` stops writing and
  leaves the file alone. `clear()` resets the write counters so a cleared ring cannot skip a save.
- A failed write or an unwritable path leaves diagnostics memory-only and never blocks startup:
  the Application call is wrapped, and every diag call site is already wrapped in runCatching.
- 8 wiring tests: restore after restart, ordering across a restart, flush-every-5, explicit flush,
  no sink, unattached recording, unwritable sink, and clear/detach isolation. 54 diagnostic tests
  total, 0 failures.
- Full suite was NOT re-run after this commit; the last green full run was 3887 tests, 4 skipped,
  0 failures at e1d60d3.

---

### [2026-11-20 12:05 WAT] [UI] P4.5 - diagnostic history screen

- New `ui/diag/DiagHistoryModel.kt` holds all the logic (filter by kind, failures-only, bounding,
  labels, clock time, summary, clear) so it is testable without Compose.
- New `ui/diag/DiagHistorySection.kt` renders it and is mounted in `SettingsScreen` directly under
  `ProvidersAndModelsSection`. It reads `CopilotDiag.recent()` - there is no second event list, so
  the screen shows exactly what the recorder stored and already redacted.
- Rows show time, kind label, ok/failed; tapping expands tool name, detail and session. Filters:
  All / Failures only / per-kind list. Clear asks for confirmation and states that project files
  are untouched. Empty state and no-match state are distinct.
- Bounded: `MAX_ROWS` 200 rows and a 320dp list, so a long history cannot exhaust a low-memory
  device. Compose LazyColumn with keys.
- 13 tests: empty state, newest-first ordering, kind filter, failures filter, bounding, labels for
  every kind, clock-time format, summary counts, redaction through the model, available kinds,
  outcome text, clear, and the integration test the campaign asked for - an event recorded, flushed,
  cleared (process death), re-attached (restart) and then visible through the model the UI renders.
- The screen itself is not instrumented by a Compose UI test; the logic and the persisted pathway
  are covered, rendering is compile-verified only.

---

### [2026-11-20 12:25 WAT] [PROVIDER] P4.3 - provider request instrumentation

- New `chat/diag/DiagTransport.kt`: a `ChatTransport` decorator wired in `ChatSession.buildEngine`,
  so every provider (Gemini, Anthropic, OpenAI-compatible) emits through one code path instead of
  per-adapter hooks that could double-record or disagree.
- Emits PROVIDER_REQUEST_START, then exactly one terminal event: SUCCESS (with elapsed ms),
  FAILURE (exception class + ms), or CANCELLED. Cancellation is rethrown as CancellationException
  and is never recorded as a failure or a success.
- Pure pass-through: chunks are emitted unchanged and in order, failures are rethrown as the same
  instance, streaming and cancellation semantics are untouched.
- Recorded metadata is provider id, model id, elapsed time and a short reason. Never recorded:
  URL, headers, Authorization values, payload, response body. Tests assert the URL, the bearer
  token and the prompt text never appear in any recorded event.
- 7 tests through the real ChatTransport contract: success ordering, chunk fidelity, failure
  rethrow identity, cancellation classification, one-start-one-terminal, header secrecy, payload
  secrecy. 74 diagnostic tests total, 0 failures.

Still NOT done in P4: retry scheduled / retry started / retry exhausted events, MCP hard-approval
events, APPROVAL_CANCELLED, and CONTINUATION_COMPLETED / FAILED / CANCELLED. P4 is therefore NOT
complete.

---

### [2026-11-20 12:45 WAT] [CONTINUATION] P4.3 - continuation terminal events

- New `agent/ContinuationDiag.kt` (pure, testable) and two call sites in `ChatSession`:
  the `EngineEvent.Completed` handler records CONTINUATION_COMPLETED with the real finish name
  after capturing whether the turn was a continuation, and the `EngineEvent.Failed` handler
  records CONTINUATION_FAILED with the error class before `endStream()` clears the flag.
- An ordinary turn emits nothing, so the log is not flooded and a continuation is never reported
  as completed merely because it started. A failed continuation cannot also appear as completed.
- CONTINUATION_CANCELLED added for user-stop/cancellation paths.
- 7 tests: completion, length-limited finish keeping its reason, failure not completion, failure
  vs cancellation distinctness, silent ordinary turn, one event per terminal call, and the
  mutual exclusion of failed and completed.

Still NOT done in P4: RETRY_SCHEDULED / RETRY_STARTED / RETRY_EXHAUSTED events, MCP hard-approval
events, APPROVAL_CANCELLED at the approval sheet, and EXECUTION_AUTHORIZED. The ChatSession call
sites for continuation are compile-verified; no ChatSession integration test drives them, so their
runtime emission is unverified. P4 is therefore still NOT complete.

---

### [2026-11-20 13:05 WAT] [RETRY] P4.3 - retry lifecycle events on the real paths

- RETRY_SCHEDULED / RETRY_STARTED / RETRY_EXHAUSTED added and wired to the two retry mechanisms
  that actually exist in ChatSession:
  * user retry (`retry()`) records RETRY_STARTED with the provider id before `sendCore`.
  * the opt-in key failover records RETRY_SCHEDULED when a next stored key is chosen, and
    RETRY_EXHAUSTED when the policy has no further key (guarded by `failoverAttempts > 0`, so a
    first failure with failover disabled emits nothing).
- No new retry system was introduced and no retry limit, backoff or cancellation behaviour was
  changed; these are the same decisions the code already made, now recorded.
- Provider id only: no key material, no slot names, no prompts, no URLs.
- 27 focused diagnostic tests pass; compile of the prod-debug variant clean.
- LIMITATION: the retry call sites are compile-verified. No ChatSession integration test drives
  them, so runtime emission of these three events is unverified. Failover only runs on Auth or
  OutOfCredits with opt-in enabled.

---

### [2026-11-20 13:20 WAT] [SECURITY] P8.5 - pattern-based redaction hardening

- `CopilotDiag.redact` gained shape-based families that a key=value rule alone missed: Google API
  keys (AIza...), AWS access key ids (AKIA...), JWTs (three dot-separated base64url segments),
  Basic auth blobs, and a wider assignment list (apikey, access_token, refresh_token,
  client_secret, key, sig) so a credential pasted into an error message or a URL query is caught.
- Fail-closed by design: a bare `key:` assignment is now redacted too. Over-redaction is
  acceptable; a leaked credential is not. Verified that ordinary prose ("keyboard shortcut",
  "tool write_file failed after 1200ms") survives unchanged.
- 10 new tests in RedactPatternsTest; 23 focused diagnostic tests pass.
- Applies before persistence and before display, because the UI reads the same recorded events.

---

### [2026-11-20 13:55 WAT] [APPROVAL] P4.3 - approval, MCP and write-policy lifecycle

- Kinds added: EXECUTION_AUTHORIZED, WRITE_POLICY_ALLOW, WRITE_POLICY_STAGE, WRITE_POLICY_REFUSE.
- `AgentFlowGate.awaitDecision` now records APPROVAL_REQUIRED (with "forced" or "ask") before the
  card, and `recordVerdict` maps the user's answer to APPROVAL_GRANTED + EXECUTION_AUTHORIZED,
  APPROVAL_DENIED, or APPROVAL_CANCELLED (timeout). A cancelled wait is recorded as a cancellation
  and rethrown, never as a grant. The no-card path records EXECUTION_AUTHORIZED with detail
  "policy", so a policy shortcut is never logged as a user decision.
- `WriteBoundary.proposalRefusal` records WRITE_POLICY_STAGE; `WriteBoundary.refusal` records
  WRITE_POLICY_REFUSE; `AgentTools.executeGated` records WRITE_POLICY_ALLOW when a writer tool ran
  without a refusal. Policy, approval requirement, user decision, authorization and outcome stay
  five separate stages.
- MCP invariant untouched and now asserted: `mcp_`, `use_connector` and `service_write` stay in
  `requiresForcedApproval`, so AUTO_ALL plus "Always allow" still asks. Diagnostics decide nothing.
- Continuation cancellation: the `CancellationException` path in the send job records
  CONTINUATION_CANCELLED when a continuation was in flight and clears the flag, so a stopped
  continuation can never later read as completed.
- Tests: 13 approval/policy tests (AgentFlowGate + WriteBoundary), 5 ChatSession retry integration
  tests driving `send`/`retry` with a fake transport. The forced-ask path is deliberately NOT
  driven in tests because the card waits ten minutes for an answer.

---

### [2026-11-20 14:20 WAT] [SECURITY] P8.3 + P8.6 - execution gate guard and archive boundary

- P8.3 architectural guard, `SingleExecutionGateGuardTest`: a source scan of the module asserts that
  `executeToolBody(`, `executeGated(` and `ToolDispatchGuard(` appear only in `AgentTools.kt`
  (excluding the class declaration). A second execution path now fails the build rather than
  silently skipping the plan gate, dispatch guard, idempotency, approval and diagnostics. It also
  asserts the gate still wraps itself in `CopilotDiag.trace`.
- P8.6 archive boundary, `ZipEngineBoundaryTest` (10 cases): absolute path, `..`, Windows drive
  path, nested archive (written as a file, never auto-extracted), per-entry size limit, entry-count
  limit with a partial report, malformed archive reported not thrown, destination symlink escape,
  legitimate extraction, and the format's own refusal of duplicate entry names.
- Existing `ZipEngine.extract` already had the right shape: canonical-root containment, a
  `passesThroughSymlink` walk, ratio and size limits, streaming. These tests pin it rather than
  replace it; no extraction behaviour was changed.
- Verified as already present, not rewritten: destination verification in
  `ArchiveDestinationVerifier` (refuses escaping entries, reports duplicates).

---

### [2026-11-20 14:40 WAT] [SEVEN] Project Seven reconciliation

`Stage711IntegrationTest.kt` restored (4 voice-independent cases) after `a015d0f` deleted it along
with Voice Mode. See AGENTS.md for the full record.

---

### [2026-11-20 14:55 WAT] [AUDIT] nine-scenario cross-project audit

See `docs/INTEGRATION-AUDIT-2026-11-20.md`. Classifications: 5 end-to-end (provider faked),
8 verified through the persisted file, 1/3/4/7/9 component-level, 2/6 partially integrated.

---

### [2026-11-20 15:10 WAT] [CI] campaign verification record

All campaign commits verified green on GitHub Actions, polled together after implementation:

| Commit | CI run | Result |
|---|---|---|
| `0b04edb` | 44726883394 (attempt 2) | success (attempt 1 failed on transient Hilt plugin resolution) |
| `4ce0bb2` | 44730373790 | success |
| `3ad349e` | 44731320828 | success |
| `27cc030` | 44733104513 | success |
| `8a1b56e` | 44733242616 | success |
| `2bc231e` | 44733338688 | success |
| `a828d90` | 44733358061 | success |

Local full suite at `a828d90`: 3,968 tests, 4 skipped, 0 failed.

### P4 status: INTEGRATED, with named gaps (not declared complete)

| Objective | State | Evidence |
|---|---|---|
| persistent storage | complete | `attach()` in `CodeSpaceApplication.onCreate`, restart tests |
| history UI | complete | `14fa5fa`, 13 tests, restart-visible |
| provider requests | complete | `4026967`, 7 transport tests |
| retry lifecycle | integrated | `4ce0bb2`; 5 ChatSession tests for user retry, failover branch untested |
| approval + MCP | integrated | `27cc030`, 13 tests; MCP hard approval asserted under AUTO_ALL |
| continuation terminal | integrated | COMPLETED/FAILED (`0b04edb`), CANCELLED (`27cc030`); call sites compile-verified |
| redaction | complete | `3ad349e`, 10 pattern tests, runs before persistence |

Named gaps, not silently dropped: the opt-in failover branch is not driven by a test; the forced
approval card cannot be driven without a ten-minute wait; no Compose UI test for the history screen;
nothing device-verified.

---

### [2026-11-20 15:45 WAT] [CRITICAL] Approval card was never published - every gated call would hang

- FOUND: `AgentFlowGate.awaitDecision` built a `CompletableDeferred` and awaited it, but never
  published a `PendingApproval`. `PendingApproval(` appeared only as a declaration and
  `pending.value` was only ever assigned `null`. Consequences: no approval card could appear,
  `approve()` / `reject()` always returned false, and every call that requires approval (MCP
  `mcp_*`, `use_connector`, `service_write`, and every non-auto tool under MANUAL) waited the full
  ten minutes and was then reported to the user as "no answer in 10 minutes".
- FIX: publish `PendingApproval(toolName, argsSummary, deferred, onAlwaysAllow, onTrust)` before
  the wait. The existing `finally { pending.value = null }` already cleared it, so no second change
  was needed. Policy, forced-tool rules and the timeout default (600_000 ms) are untouched.
- EVIDENCE: `ApprovalCardFlowTest` (6) now drives grant, decline, expiry, double-approve and the
  MCP-forced card through `approve()` / `reject()` with no long sleep; the expiry path uses a new
  injectable `timeoutMs` whose production default is unchanged. 31 approval tests pass.
- This was a runtime defect invisible to unit tests of the pure policy functions, and it is exactly
  the kind of thing that device testing would have caught first. It is NOT device-verified.
- P4.1 closed: `DiagFailoverIntegrationTest` (5) drives the opt-in key failover through the real
  `send` path with an injected slot pool and a fake 401 transport. It also pinned the real
  semantics: the pool holds ALTERNATIVE slots, so a one-slot pool fails over exactly once and then
  reports exhaustion.

---

### [2026-11-20 16:05 WAT] [AUDIT] integration audit and roadmap

See `docs/INTEGRATION-AUDIT-2026-11-20.md` and `docs/ROADMAP-NEXT-PHASE-2026-11-20.md`.
P4.3 remains open with a stated reason; duplicate reply is PARTIALLY ADDRESSED.

---

### [2026-11-20 16:35 WAT] [CI] final campaign CI record

| Commit | CI run | Result |
|---|---|---|
| `215c835` (approval card fix, P4.1/P4.2) | 44739749901 | success (attempt 1) |
| `b0bb689` (audit + roadmap docs) | 44739787514 | success (attempt 2) |

Local full suite at `b0bb689`: 3,979 tests, 4 skipped, 0 failed.

**Recurring infrastructure flake, recorded honestly.** The APK step failed twice with
`Plugin [id: 'com.google.dagger.hilt.android', version: '2.52'] was not found` - once on `0b04edb`
(code) and once on `b0bb689`, which changed only Markdown files. A documentation-only commit cannot
affect Gradle plugin resolution, so this is a runner-side fetch failure against Google Maven / the
plugin portal, not a repository defect. Both were resolved by re-running the failed job; no workflow,
signing or plugin version was changed. If it recurs, the next evidence to gather is whether the
runner can reach `dl.google.com` at build time.

---

### [2026-11-20 16:20 WAT] [P1] Integration Reliability & Security - duplicate replies at the runtime boundary

- Added `DuplicateReplyScenariosTest` (5), driving the real `ChatSession` with a real `DeliveryStore`
  on a temp directory. Covered: a different second question while the first is running (one answer,
  no second stream, and the replacement question is still answerable), closing the tab mid-answer
  and reopening (the abandoned tab gets nothing, the reopened one gets exactly one answer),
  cancel-then-retry (one answer), session restoration over the same conversation (no phantom
  answer, a new question answered once), and five rapid taps on one question (one wire attempt,
  one answer).
- Duplicate-reply status moves from PARTIALLY ADDRESSED to **verified for those five scenarios**.
  Still unproven and stated as such: two sessions in separate processes delivering at once, and a
  provider that emits two terminal events on one stream. Neither has a harness in this repo yet.
- Approval enforcement re-verified by `ApprovalCardFlowTest` (6) and `ApprovalLifecycleDiagTest`
  (13) against the `215c835` fix: the card is published before the wait, `approve()`/`reject()`
  resolve it, expiry is a cancellation, a second approve cannot double-grant, and `mcp_*` asks even
  with AUTO_ALL plus always-allow.
- Execution gate unchanged: `SingleExecutionGateGuardTest` still fails the build if any file outside
  `AgentTools.kt` reaches `executeToolBody(` / `executeGated(` / `ToolDispatchGuard(`.

---

### [2026-11-20 17:25 WAT] [PROGRAMME] eight-project status after the first pass

| # | Project | Status |
|---|---|---|
| 1 | Integration Reliability & Security | **implemented and verified for the scoped work** - approval card fix `215c835`, duplicate-reply runtime scenarios `3fbf399` |
| 2 | Research Improvements | not started |
| 3 | Documents & Media Intelligence | not started |
| 4 | Advanced Agent Capabilities | not started |
| 5 | Advanced Tool Intelligence | not started |
| 6 | Cross Provider Intelligence | not started |
| 7 | Long Context Management | not started |
| 8 | Copilot Experience Improvements | not started |

CI: `3fbf399` -> 44755439433 success. Local full suite: 3,984 tests, 4 skipped, 0 failed.

Why only project 1: this run's budget was spent on the two highest-value defects and their runtime
proof (the unpublished approval card and the duplicate-reply scenarios). The remaining seven were
deliberately not begun rather than started and left half-integrated, which the programme forbids.
Each is specified in `docs/ROADMAP-NEXT-PHASE-2026-11-20.md` with scope, acceptance criteria and
checkpoints, so the next run starts from a plan rather than a guess.
