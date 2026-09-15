# U05 — PANELS & LIST SURFACES AT NARROW WIDTH (explorer / SCM / problems rows)

> UI-sweep batch 5 of 8. 2026-09-15.
> VS Code: `src/vs/sessions/contrib/sessions/browser/media/sessionsList.css` (phone rules, 1187L), `sessionsViewPane.css` (242L), `workbench/contrib/scm/browser/media/scm.css` (813L — **zero phone rules**).
> Ours: `ExplorerPane.kt`, `SourceControlPane.kt`, `AdvancedProblemsPanel.kt`.

## §1 VS Code's phone list rules (source-verified)

The sessions list is the only list they phone-restyle, with four rules (`sessionsList.css:1051-1077`):

1. **Row actions are ALWAYS VISIBLE on phone** — desktop's hover-revealed `.actions` get `display:flex; visibility:visible; opacity:1 !important`. Hover doesn't exist on touch, so actions stop hiding.
2. **`:active` row = hover background** — pressing a row paints the hover color (touch feedback; no hover state to borrow).
3. `touch-action: manipulation` + `-webkit-touch-callout: none` on every row (kills double-tap-zoom lag / long-press callouts).
4. Inset rows get horizontal-only margins (JS-positioned rows can't take vertical margins).

**SCM: no phone rules at all** — their phone workbench ships desktop-tight 22px SCM rows unre-styled. The quick-input 44px row floor applies only to pickers/dialogs, not tree panels. Desktop pane headers shrink via compact tiers (28px, `editor-tabs-compact-height`).

## §2 Ours (line-verified)

`ExplorerPane.kt` rows: `combinedClickable` (tap + long-press), **always-visible inline actions** — MoreVert 18dp clickable (row menu), close-tab 14dp clickable, inline status icons 16dp; header rows with chevron expanders. `SourceControlPane.kt` rows: clickable branch/stage rows, combinedClickable present. `AdvancedProblemsPanel.kt`: clickable file rows (expand) + clickable problem rows. Compose `clickable` ships ripple by default (press feedback built in).

## §3 Verdicts

| Item | VS Code phone | Ours | Verdict |
|---|---|---|---|
| Row actions always visible | enforced (hover killed) | inline always-visible actions | **MATCH** — we already do their phone rule by default |
| Press feedback | `:active` = hover bg | Compose ripple (default) | **MATCH** (equivalent mechanism) |
| Touch gesture hygiene | touch-action: manipulation | native (N/A) | N/A |
| Row-inline action targets | action items ≥44px (phone floor) | MoreVert 18dp, close-tab 14dp bare clickables | **DIVERGES-BAD (minor)** → SPEC-U5-1 (padded targets, not bigger icons) |
| Icon scale in rows | codicon 16px | 18dp MoreVert, 14dp close = off-scale | **DIVERGES-BAD** → SPEC-U2-1 application (18→16, 14→12) |
| SCM rows on phone | unre-styled, 22px desktop-tight | compact mobile rows | **MATCH-or-AHEAD** (our SCM is more mobile-aware than their phone workbench) |
| Pane header compaction | 28-32px compact tiers | pane headers per-pane | verify per-surface in polish turns |

## §4 Specs

- **SPEC-U5-1 — Padded hit targets for row-inline actions.** Now: bare 14-18dp clickable icons in rows (explorer MoreVert/close, similar in SCM). After: icons 16dp (per approved SPEC-U2-1) inside padded targets ≥36dp wide × row-height, centered — target grows, icon doesn't, density preserved. Why: their 44px action floor is about the TARGET, not the glyph; we can keep rows tight while making every tappable miss-proof. Per-surface (explorer's polish turn first).
- **SPEC-U5-2 — Press-feedback audit.** Now: ripple via Compose defaults — believed universal but unaudited (some rows may override indication). After: during each surface's polish turn, confirm every list row shows pressed state. Why: their `:active` rule exists because touch needs visible response; any ripple-less row reads dead.

## §5 Connections

- SPEC-U2-1 (approved): the 18→16 / 14→12 icon half of SPEC-U5-1 is that spec's per-surface application.
- SPEC-U3-1 (re-confirmed w/ compact tier): row-inline actions sit between floor and compact tier — padded targets are the resolution.
- B03 (file explorer), B07 (SCM): functional maps; this adds the responsive tier.
- U06 (menus/dropdowns): row overflow menus (MoreVert) get their own batch.

## §6 Open questions

1. Any ripple-less rows? (audit rides polish turns — no dedicated research)
2. Explorer indentation per depth level (dp value per level) vs their 8px/level — measure during explorer polish turn.

## Status

**DONE** — 2026-09-15. SPEC-U5-1..2 logged pending per-item approval. Next: U06 menus, dropdowns & popovers.
