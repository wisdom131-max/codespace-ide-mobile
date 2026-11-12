# Implemented — everything requested and delivered

One canonical list, grouped by area. Each row is something that was asked for and is now in the
app. Commit + CI run where the record exists; rows marked *backfill* were reported in an earlier
session and still need their commit id pasted here.

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
| P2 Document reading | `DocText` extracts text from PDF (content streams), DOCX, PPTX and XLSX with honest failures (encrypted, scanned, broken) and hard input/output bounds; wired into `read_file` so a document is no longer decoded as UTF-8 | this commit |
| P6 Session handoff | `SessionHandoff` extracts decisions, constraints, progress, files, citations and open points from a conversation, drops credential lines and states that approvals are not inherited; wired as "Continue in New Session" in the chat overflow menu | this commit |
| ZIP tools (`zip_create`, `zip_extract`, `zip_list`) | `java.util.zip` engine with ZIP-Slip, symlink, overwrite and resource limits; bounded listing | see `ZIP-TOOLS-2026-11-12.md` |
| Package Intelligence (`npm_info`, `pypi_info`, `lockfile_parse`) | Registry metadata plus nine offline lockfile formats, injectable fetcher, untrusted-text flagging | see `PACKAGE-INTELLIGENCE-2026-11-12.md` |
Note on counts: Wave 1 is recorded as twelve tools in total (three in Group A, nine in B/C/D). The
Group A commit carried the prompt/toolset rework that the rest of Wave 1 depended on, which is why
the twelve are spread across two commits rather than four.
