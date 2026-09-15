# VS CODE PARITY — UI/RESPONSIVE RESEARCH (U-series)

> UI/visual/layout research sweep, distinct from the feature-parity sweep in [`../vscode-parity/`](../vscode-parity/) (B01-B14 + S01 + FINAL-REVIEW).
> Question: how does VS Code's own UI adapt at narrow/mobile widths, and where does OUR layout diverge in ways that make it "feel off"?
> Rules: verdicts are MATCH / DIVERGES-BAD (fix) / DIVERGES-DELIBERATE (keep) / NO-COMPARISON. All specs await per-item approval; code follows the approved ordering plan (per-surface: features → polish → closed).

## Files

| Batch | Topic | File | Status |
|---|---|---|---|
| — | Source-extracted VS Code tokens (radius, chat values, spacing) | [MEASUREMENTS.md](MEASUREMENTS.md) | ✅ living |
| U01 | Chat panel UI (composer, buttons, spacing) | [U01-chat-panel-ui.md](U01-chat-panel-ui.md) | ✅ DONE 2026-09-15 |
| U02 | Design tokens & density foundation | [U02-design-tokens.md](U02-design-tokens.md) | ✅ DONE 2026-09-15 |
| U03 | Shell & navigation (narrow-width) | [U03-shell-navigation.md](U03-shell-navigation.md) | ✅ DONE 2026-09-15 |
| U04 | Editor surface (tabs, find bar, gutters) | [U04-editor-surface.md](U04-editor-surface.md) | ✅ DONE 2026-09-15 |
| U05 | Panels & list surfaces (explorer/SCM/problems rows) | [U05-panels-lists.md](U05-panels-lists.md) | ✅ DONE 2026-09-15 |
| U06 | Menus, dropdowns & popovers | [U06-menus-popovers.md](U06-menus-popovers.md) | ✅ DONE 2026-09-15 |
| U07 | Overlays (palette, dialogs, notifications) | — | ⬜ |
| U08 | Terminal & debug visuals | — | ⬜ |

## Method (per batch)

1. VS Code narrow-width behavior from source (CSS/TS citations; live vscode.dev renders where auth permits — chat is sign-in-gated, documented in U01 §1).
2. Our implementation read at line level (actual dp/px, not memory).
3. Verdict table; every DIVERGES-BAD → a numbered now→after→why SPEC awaiting per-item approval.
4. UI code changes stay MINIMAL; approved plan: per-surface features first, polish last, surfaces close + re-polish on reopen.

## Key findings so far

- **U01 (chat, the flagged surface):** VS Code ships a dedicated phone stylesheet (`chatInputMobile.css`) — toolbar row SEPARATE from input, borderless radius-large input box, 36dp FILLED rounded-square send (vs desktop ghost circle), bottom-sheet mode pickers. Our composer squeezes ~180dp of field between 48dp IconButtons in one row, uses a bordered OutlinedTextField, and a ghost send — that stack is the "off" feeling. SPEC-1..5 written, awaiting per-item approval after CW2/CW8.

- **U02 (tokens):** VS Code's scales are CI-ENFORCED (build stylelint validator): spacing [2..40 on 2-6px steps], radius 2/4/6/8/12, fonts 26/18/13/12/11/10 (400/600 only), icons 16/12 ONLY. Ours: typography MATCHES their ramp exactly (11/12/13sp dominant — best-parity dimension), spacing on-scale, but icons drift badly (14/18/20dp in their "always a mistake" zone) and 129 sub-10sp text uses. SPEC-U2-1..5 logged. Exact radii fed back into U01 SPEC-2/3.

- **U03 (shell):** VS Code phone = single-pane + everything becomes OVERLAY CARDS: 60vh bottom sheet w/ 16px rounded top + 36x5px drag handle, full-screen modal editors, 44x44 touch floor, ALL inputs forced 16px, hovers disabled, edge-swipe sidebar (16px zone/48px commit). Ours: single-column philosophy already MATCHES (B12 differentiator = their phone direction); real gaps = drag-handle affordance, input font size, sub-44dp clickables, imePadding app-wide. Edge-swipe LOGGED-DELIBERATE skip (system-back conflict). SPEC-U3-1..5 pending.
- **SPEC-1..5 (U01 chat) APPROVED per-item 2026-09-15** — approved-pending-its-turn behind CW2/CW8 (Phase 1). SPEC-U2-1..5 ALL APPROVED per-item 2026-09-15 (per-surface application confirmed); SPEC-U3-1..5 awaiting verdicts.
- **U04 (editor surface):** VS Code phone collapses tabs to a single-tab title (their shell is chat-first — we deliberately keep our scrollable strip); phone FIND = commandeered full-width row (52px, thumb targets, single input) while their in-editor find widget stays desktop-styled — our <480dp adaptive FindReplaceBar is arguably AHEAD (F1 retest will confirm). REFINEMENT: their compact chrome runs 28-32px (editor-tabs-compact-height) — SPEC-U3-1 amended with a compact-tier exemption pending Wisdom re-confirm. SPEC-U4-1 (find thumb-target pass) rides F1. Edge-swipe skip + mobile-layer logging confirmed by Wisdom.

- **U05 (lists):** VS Code's only phone list rules: actions ALWAYS VISIBLE (hover killed), :active row = hover bg, touch-action hygiene; SCM gets ZERO phone restyling (desktop-tight on their own phone workbench). Ours already matches "always visible" by default and is more mobile-aware on SCM. Gaps: 14/18dp bare clickable row icons (SPEC-U5-1 padded targets + SPEC-U2-1 icon fix) + press-feedback audit (SPEC-U5-2).
- **U06 (menus):** VS Code phone menu strategy = REPLACE menus, don't restyle them — zero context-menu phone rules anywhere; dropdown triggers swap to QuickPick sheets (PickerActionViewItem / MobilePermissionPicker); when pickers open they get 44px rows + edge-to-edge-8px + 50vh. Ours: M3 DropdownMenus (14 files) + long-press purpose-built flows = MATCH-or-AHEAD; ChatModelMenuButton dropdown = deliberate keep (SPEC-6 skip). One audit spec: SPEC-U6-1 (M3 default 4dp corners + one 12h/5v straggler row vs standing rule).
- **Approvals ledger (all per-item, Wisdom):** SPEC-1..5 ✅ (queued behind CW2/CW8) · SPEC-U2-1..5 ✅ (per-surface) · SPEC-U3-1..5 ✅ ALL (U3-4 confirmed-closed) · SPEC-U4-1 pending (rides F1) · SPEC-U5-1..2 ✅ · SPEC-U6-1 ✅ (ChatModelMenuButton dropdown confirmed closed) · SPEC-U7-1..2 presented 2026-09-15, pending.
- **U07 (overlays):** our search panel already imePads (the model citizen) but sits at 92% width vs their ~98% edge-to-edge quick-input (44px rows, 50vh cap) -> SPEC-U7-1 widen+audit; custom NotificationToastBanner 320dp/6dp vs their full-width-8px xLarge-12px 44px-row top-anchored card -> SPEC-U7-2. Dialogs = M3 platform defaults (28dp corners ~40dp buttons) logged DELIBERATE keeps; 44dp button floor + 16sp entry already covered by approved SPEC-U3-1/U3-3 at each dialog's turn.
