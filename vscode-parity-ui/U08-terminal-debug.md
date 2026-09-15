# U08 — TERMINAL & DEBUG VISUALS (final research batch)

> UI-sweep batch 8 of 8. 2026-09-15.
> VS Code: `sessions/browser/parts/mobile/contributions/mobileDiffColors.ts` (44L, full), `parts/mobile/mobileVisualViewport.ts` (174L, key sections). Terminal: no phone-layout restyling anywhere in `workbench/contrib/terminal` (confirmed by the phone-layout search hit list — terminal absent).
> Ours: `TerminalPane.kt`, `CodeEditor.kt` (P54 debug visuals, diff status colors), `BinaryDiffViewerDialog.kt`.

## §1 VS Code mobile facts (source-verified)

- **Terminal: zero phone rules.** Their phone workbench ships the desktop terminal unre-styled. Everything mobile-special lives in the sessions layer (diff colors, viewport, chip lane, edge swipe) — the terminal didn't make the cut.
- **A dedicated MOBILE diff color trio** (`mobileDiffColors.ts`): added `#81b88b`, modified `#E2C08D`, deleted `#c74e39` (dark variants), registered as theme colors for "the mobile changes-list and diff overlay" — tuned separately from desktop diff colors.
- **Keyboard height as a first-class signal** (`mobileVisualViewport.ts`): the actual IME height is published as `--vscode-keyboard-height` (e.g. `320px`) so any consumer can pin itself above the keyboard (`bottom: var(--vscode-keyboard-height, 0px)`); context flips only on a "clearly keyboard-sized delta" (noise tolerance — a few dozen pixels doesn't count).

## §2 Ours (line-verified)

Terminal: own mobile-tuned terminal surface (tab strip w/ 10h/4v rows, 12sp tab labels, 13sp/10sp labels, 9sp rename pencil on 2dp padding, IME-diagnostics logging from the TerminalView). Diff: `DiffStatus.MODIFIED → #E5C07B` yellow (≈ their mobile `#E2C08B`-family hue), added/deleted per GitDiffAnalyzer render; BinaryDiffViewerDialog has its own trio. Debug: P54 yellow gutter arrow (▶) + `#CCA700` current-line highlight.

## §3 Verdicts

| Item | VS Code | Ours | Verdict |
|---|---|---|---|
| Terminal at narrow | unre-styled desktop terminal | purpose-built mobile terminal (tabs, IME diag, terminal-native view) | **MATCH-or-AHEAD** (deliberate; our terminal is a core surface — B06) |
| Terminal text micro-sizes | n/a (desktop sizes) | 9sp rename pencil (sub-floor) | covered by APPROVED SPEC-U2-2 at terminal's polish turn |
| Diff colors (mobile tier) | dedicated trio #81b88b/#E2C08D/#c74e39 | modified-yellow already ≈ theirs; added/deleted unaligned | **DIVERGES-SMALL** → SPEC-U8-1 |
| Debug current-line visual | stack-frame yellow conventions | P54 arrow + #CCA700 highlight | **MATCH** (same convention) |
| Keyboard signal | keyboard-height var + delta threshold | Compose `imePadding` (approved U3-5 to spread) | **MATCH-mechanism** (WindowInsets ≈ their visual-viewport var; their delta-threshold is guidance for where insets jitter) |

## §4 Specs

- **SPEC-U8-1 — Align added/deleted diff colors to their mobile trio.** Now: our modified yellow ≈ theirs; added/deleted drift from their mobile-tuned trio. After: at each diff surface's polish turn (ChatDiffReviewCard first), adopt added `#81b88b` / modified `#E2C08D` / deleted `#c74e39` (dark) as our diff color tokens. Why: they tuned this trio specifically for phone legibility (their desktop diff colors differ); we get their phone-tested values for free.

No terminal spec — ahead of them; no debug spec — same convention.

## §5 Connections

- U02: diff trio becomes color tokens in CsTokens (approved SPEC-U2-5) — theme tokens aren't just gray scale.
- U03/SPEC-U3-5: Compose insets are our analog of their keyboard-height var; the search panel's imePadding is the template.
- R6 (ChatDiffReviewCard) + B07 (SCM): diff-color consumers, first application surfaces.

## §6 Open questions

1. Inset jitter (their "few dozen pixels" noise class) — watch during SPEC-3-1 (chat) implementation; not blocking.

## Status

**DONE** — 2026-09-15. SPEC-U8-1 logged pending per-item approval. Research phase COMPLETE — synthesis in FINAL-UI.md.
