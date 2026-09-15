# FINAL-UI — Cross-Batch Synthesis (U01–U08)

> UI parity sweep, research phase complete. 2026-09-15. Companion to the functional sweep (vscode-parity/ B01–B14 + FINAL-REVIEW): that one asked "what features exist"; this one asked "what does it look and feel like on a phone, and what does VS Code do about it."

## §1 Batch roll-up

| Batch | Focus | Dominant finding |
|---|---|---|
| U01 | Chat panel | flat composer squeeze → split-row structure (SPEC-1..5) |
| U02 | Design tokens | their scales are CI-ENFORCED; our typography matches their ramp exactly; icons + text floor are the drift |
| U03 | Shell & navigation | their phone = single-pane + overlay cards (60vh sheet w/ drag handle, full-screen editors, 44px floor); our philosophy already matches; edge-swipe = deliberate skip (system-back) |
| U04 | Editor surface | our adaptive FindReplaceBar is AHEAD of their unre-styled in-editor find; tabs/gutters match; compact-tier 28-32dp chrome sanctioned |
| U05 | List surfaces | our rows already do their two phone rules (always-visible actions, press feedback); our SCM is more mobile-aware than theirs |
| U06 | Menus & popovers | they REPLACE menus (pickers/sheets), never restyle; our long-press flows are ahead; M3-dropdown audit spec |
| U07 | Overlays | search panel imePads (model citizen) but undersized (92%); notification banner is desktop-toast-sized |
| U08 | Terminal & debug | terminal AHEAD (they ship desktop terminal unre-styled); their phone-tested diff color trio up for adoption |

**Net position:** structurally we are already the phone-native IDE VS Code's sessions layer is converging toward (B12's differentiator, now confirmed against their own phone CSS). The gaps are almost all *token-level*: icon scale, text floor, touch targets, surface widths, one affordance (drag handle), one padding type (imePadding). No structural rebuilds required by this sweep.

## §2 Spec ledger (all per-item, Wisdom)

**Approved — queued behind CW2/CW8 (Phase 1 chat, implementation order fixed):**
SPEC-1 (split composer + imePadding) · SPEC-2 (flat 8dp input box) · SPEC-3 (36dp filled 4dp send) · SPEC-4 (32dp mic target) · SPEC-5 (rounded-top input container)

**Approved — cross-cutting, applied PER-SURFACE at each surface's own polish turn:**
SPEC-U2-1 (16/12 icons) · U2-2 (10sp floor) · U2-3 (radius snap) · U2-4 (kill 1dp verticals) · U2-5 (CsTokens + CI lint) · U3-1 (44dp floor w/ compact tier) · U3-2 (bottom sheet + drag handle) · U3-3 (16sp entry fields) · U3-5 (imePadding audit) · U5-1 (padded row targets) · U5-2 (press-feedback audit) · U6-1 (DropdownMenu audit) · U7-1 (search panel edge-to-edge) · U7-2 (toast banner phone sizing)

**Pending verdicts:**
SPEC-U4-1 (find thumb-target pass — rides the F1 on-device retest) · SPEC-U8-1 (adopt mobile diff trio at diff surfaces' polish turns)

**Confirmed closed / deliberate keeps:**
SPEC-6 (bottom-sheet model picker — skipped, header dropdown) · ChatModelMenuButton dropdown closed · Edge-swipe nav (system-back conflict) · M3 AlertDialog platform styling · System Toast tier · Scrollable tab strip (editor-first) · Connected-tab look (theme-level) · Android Toasts · Gutter width discipline · Adaptive FindReplaceBar (ahead) · Long-press flows (ahead)

## §3 The three transferable lessons

1. **Discipline is enforced, not reviewed.** Their token scales come with a build-time validator (`build/lib/stylelint/validateDesignTokens.ts`). Our equivalent — CsTokens + lint (SPEC-U2-5) — is the single spec that protects every other spec after surfaces close.
2. **Phone = one pane + everything else becomes an overlay with an affordance.** Sheets get drag handles; modals go full-screen; hovers die; actions stay visible. We match the philosophy; the missing pieces are affordance-level (handle, imePadding, target padding).
3. **Where they route around (menus, terminal, in-editor find), we already built for touch.** Our long-press flows, purpose-built terminal, and adaptive find bar are genuinely ahead of their phone layer — documented per-batch so the "off" feeling isn't misattributed to surfaces that are actually winning.

## §4 Execution order (per approved plan — unchanged)

1. **Phase 1 code:** CW2 + CW8 features → **then** chat panel polish = SPEC-1..5 + first application of U2 cross-cutting specs (icons, floor, radius, 16sp entry, imePadding) on ONE surface.
2. Every later surface (explorer, SCM, problems, terminal, search, dialogs, notifications, diff cards) closes feature work → polish turn → surface closed; re-opening requires a declared re-polish pass (standing rule).
3. CsTokens + lint (U2-5) lands early so later turns consume constants instead of literals.
4. On-device gates: Wisdom's IME tap-test + landscape nav-bar check (one session), F1–F6 + PS + R10 retest batches — SPEC-U4-1 rides F1.

## §5 Scoreboard

| Verdict | Count (batch-dominant items) |
|---|---|
| MATCH / MATCH-or-AHEAD | typography ramp, spacing, gutter, single-pane philosophy, list rules, menus-via-flows, terminal, debug visuals, find bar, dialog platform tier |
| DIVERGES-BAD (all specced, all approved) | icon scale, sub-10sp text, row-inline targets, sub-44 clickables, notification banner, search panel width, sheet affordance, imePadding coverage, off-scale radii, 1dp verticals |
| DIVERGES-DELIBERATE (closed) | edge-swipe, dropdown pickers, tab strip, M3 dialogs, system toasts, connected-tab look |

**Research phase: CLOSED.** Implementation remains gated exactly as approved. Nothing in this sweep re-opens closed surfaces.
