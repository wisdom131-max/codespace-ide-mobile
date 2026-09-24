# FORWARD-COVERAGE-CHECK — VS Code feature areas vs the audit corpus (2026-09-24)

The 18-group audit + 2 addenda were app-first: OUR features compared against VS Code's equivalent. That method cannot surface VS Code features with NO trace in our app. This is the reverse pass: enumerate VS Code's user-facing feature areas from the clone itself and cross-check each against the full audit corpus (18 GROUP docs + TextMate/ImageGen addenda + MASTER-GAPS + MASTER-CONNECTIONS). **Nothing here is ranked or fixed — every flag is "never compared, possibly entirely absent, needs a group or addendum decision."**

Method: `src/vs/workbench/contrib/` (111 dirs) + `src/vs/workbench/services/` enumerated; word/camel-case/keyword search per area; hits quality-checked manually (word-noise like "profile"=shell-profile, "trust"=untrusted-input, "interactive"=debug prose were rejected as non-comparisons).

## A. Confirmed covered (real comparisons exist — no action)

Editor core chrome (minimap, sticky scroll, word wrap, folding, snippets incl. tab stops, bracket pairs), breadcrumbs (Explorer C-X02, Tabs B09), split views, tabs, search/find-replace + palette file/symbol modes, tasks.json + problem matchers (Problems), markers/problems, debugger incl. DAP, SCM commit/history/stash/conflicts, timeline, local history, terminal, extensions, testing (vs Testing view), chat + inline chat, authentication (ruled strongest parity in audit), settings incl. settings sync (Settings row), keybindings incl. export, telemetry (thin: perf marks + crash telemetry via RG01), performance/process explorer, output/logs, multi-root workspaces (Explorer X02, LSP LS04), inlay hints, call/type hierarchy (LSP LT30/LP10), code actions, outline, language status, format, webview-as-container (Extensions), notebook=NO (flagged below).

## B. FLAGGED — zero real comparison anywhere in the audit (decision needed)

### B1. Major areas (likely need a group or addendum pass)

| # | VS Code area (clone evidence) | Audit status | Notes for the decision |
|---|---|---|---|
| F01 | **Notebooks** (`contrib/notebook`, `interactive`, `replNotebook`, `services/notebook`) | ZERO mentions. App has no notebook trace found. | Largest surfaced gap: Jupyter-style notebooks are a major VS Code surface; on-device Jupyter via proot is plausible. Needs an explicit build/not-build ruling at minimum. |
| F02 | **Remote development as a MODEL** (`contrib/remote`, `remoteTunnel`, `remoteCodingAgents`, `services/tunnel`, remote explorer/ports UX) | ZERO real comparisons. Integration audited our SSH/ports app-first; the forward comparison was abandoned when portsView wasn't found in the clone (ports model lives in older paths). | The remote extension-host model (auto-install server components, ports auto-forward UX, tunnel relay) was never forward-compared. Pairs with IG06 (orphaned sshj stack) decision. |
| F03 | **Workspace Trust** (`platform/workspace/common/workspaceTrust.ts`) | ZERO mentions (all "trust" hits = untrusted-input prose). | VS Code's restricted mode gating tasks/debug/terminal on untrusted folders is the VS Code analog of our FlowGate story — comparing could inform FlowGate/IG02 design. |
| F04 | **Profiles** (`contrib/userDataProfile`, `services/userDataProfile`) | ZERO real mentions (all "profile" hits = P32 shell-profile noise). | Per-profile settings/extensions/keybindings, import/export. Probably a non-goal for single-user mobile; needs the ruling. |
| F05 | **Accessibility as a feature area** (`contrib/accessibility`, `accessibilitySignals`, accessible view) | One incidental Terminal row (xterm a11y). Never compared as an area. | Screen-reader mode, keyboard nav, announcements, accessible view. Android side has its own a11y stack (TalkBack) — comparison may be mobile-idiomatic rather than VS Code-shaped. |
| F06 | **Emmet** (`contrib/emmet`) | ZERO mentions. | HTML/CSS abbreviation expansion. No app trace. Cheap Editor addendum if wanted. |
| F07 | **Speech/voice** (`contrib/speech`, `agentsVoice`, `services/localTranscription`, `voiceClient/speechToText` touchpoints) | ZERO in the audit corpus — BUT already surfaced in the 2026-09-13 VS Code Integration Map (voice STT among ~14 Copilot touchpoints, rounds I1-I6, awaiting owner prioritization). | Not a new discovery; sits in the older integration-map queue. Folding it into this decision avoids two queues. |
| F08 | **Localization / language packs** (`contrib/localization`, `services/localization`) | ZERO mentions. | Probably a deliberate single-locale non-goal; never formally ruled. |
| F09 | **Tree-sitter syntax backend** (`editor/common/model/tokens/treeSitter/`) | ZERO mentions — the TextMate addendum compared only against vscode-textmate (VS Code's LEGACY token path). | VS Code's newer incremental parser (tree-sitter) is the direction of travel; affects any TM01 fix scope. Editor addendum decision. |

### B2. Thin/minor (addendum-level or explicit non-goal ruling)

| # | Area | Status |
|---|---|---|
| F10 | **Edit Sessions** (`contrib/editSessions` — cloud resume of uncommitted edits) | 0 real comparisons; conceptually adjacent to Recovery's local session restore. |
| F11 | **Untitled/scratch buffers** (VS Code Untitled model) | 0; our scratch-root flow was audited app-first, VS Code's model never compared. |
| F12 | **Comments / code review** (`contrib/comments`) | 0 real mentions (prose noise only); our PR surface is dead code (ApiService createPr). |
| F13 | **Merge editor (3-way UI)** (`contrib/mergeEditor`) | 0 direct; SCM covered our conflict flow only. |
| F14 | **Update/relauncher/splash** (auto-update + restart, boot splash) | 0; app updates are manual APK installs (RG05 context). Likely fine, needs one line. |
| F15 | **Zen mode, watermark, imageCarousel, surveys, emergencyAlert, share, customEditor, externalUriOpener/opener, indent guides/rulers** | 0/thin each; mostly cosmetic or N/A for mobile. |
| F16 | **Command palette FULL range** | Partially covered (Search compared file/symbol modes); the systemic consequence (action registry + ContextKeyExpr) is ALREADY recorded in the parity sweep (FINAL-REVIEW §4) and Extensions XG. No new flag — listed for completeness. |

### B3. Already ruled elsewhere (no decision needed, recorded here for the ledger)

- **Sash/movable views/aux sidebar**: parity sweep FINAL-REVIEW §5 explicitly ruled DO-NOT-BUILD (2026-09-15).
- **Extension host, multi-window, group grids, PR pill**: same DO-NOT-BUILD ruling.
- **Enterprise/policies/menubar**: not applicable to a single-user mobile IDE (no formal ruling; grouped with F15).

## C. Recommendation (for the owner decision, not executed)

- B1 items most likely to change the fix plan if audited: F01 (notebooks), F03 (workspace trust ↔ FlowGate design), F09 (tree-sitter ↔ TM01 scope).
- Items that can be closed with a one-line non-goal ruling: F04, F08, F14, F15, enterprise items.
- F07 folds into the existing integration-map queue.
- This check itself changes no ranking and no plan phases; it only protects the audit from the app-first blind spot.
