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
- Device check for the SVG viewer and the branch picker: neither has run on the TECNO yet.
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
