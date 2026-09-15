# U07 — OVERLAYS: PALETTE/SEARCH, DIALOGS, NOTIFICATIONS

> UI-sweep batch 7 of 8. 2026-09-15.
> VS Code: phone quick-input/dialog/notification rules (already source-verified in U03 from `phoneLayout.css` + `workbench.ts` `PHONE_NOTIFICATION_ROW_HEIGHT=44`).
> Ours: `ProjectFileSearchPanel.kt`, 23 `AlertDialog` files, `NotificationDrawerOverlay.kt` (NotificationToastBanner), 26 `Toast.makeText` sites.

## §1 VS Code phone rules (from U03, cited here for comparison)

- **Quick input (palette):** pinned `left/right: 8px !important` (≈98% screen width), list `max-height: 50vh`, rows `min-height: 44px`; toolbar rows exempt (compact).
- **Dialogs:** width `calc(100% - 32px)`, buttons `min-height: 44px`, button font `16px`.
- **Notifications:** top-anchored at `safe-area-top + 48px`, `left/right: 8px` (full width minus 8), `cornerRadius-xLarge` (12px), row height 44px constant, full-width toasts.

## §2 Ours (line-verified)

- `ProjectFileSearchPanel.kt`: `fillMaxWidth(0.92f)` (≈92% width), 8dp radius card, **imePadding ✓** (the app's only imePadding site — model citizen), 13sp monospace entry, Text/Replace mode chips 11sp/4dp radius.
- Dialogs: 23 files use M3 `AlertDialog` — platform-default 28dp corners, ~40dp buttons, 14sp button text; our per-dialog overrides touch inner chips only (4dp), not the dialog shape.
- `NotificationToastBanner`: custom toast card, fixed `320dp` width (≈78% of 412dp screen), 6dp radius, configurable corner alignment; plus 26 system-Toast sites.

## §3 Verdicts

| Item | VS Code phone | Ours | Verdict |
|---|---|---|---|
| Palette width | edge-to-edge minus 8px (~98%) | 92% | **DIVERGES-SMALL** → SPEC-U7-1 |
| Palette imePadding | (native) | ✓ present | **MATCH** (the one place we already do it) |
| Palette entry font | 16px forced | 13sp | covered by APPROVED SPEC-U3-3 (16sp entry fields) |
| Palette row height | 44px min | unmeasured | audit in SPEC-U7-1 polish turn |
| Dialog shape | xLarge 12px | M3 28dp platform default | **DIVERGES-DELIBERATE** (Material platform convention; our standing 8-12dp rule is for OUR custom surfaces, not M3-native dialogs — log, don't fight M3) |
| Dialog buttons | 44px, 16px font | M3 ~40dp, 14sp | covered by APPROVED SPEC-U3-1 floor (action buttons) at each dialog surface's turn — no new spec |
| Custom toast banner | full-width-8px, xLarge 12px, 44px rows, top-anchored | 320dp fixed, 6dp radius | **DIVERGES-BAD** → SPEC-U7-2 |
| System toasts | n/a | 26 Toast sites | DIVERGES-DELIBERATE (platform-native, U03 keep stands) |

## §4 Specs

- **SPEC-U7-1 — Search panel edge-to-edge.** Now: `fillMaxWidth(0.92f)` vs their ~98%. After: widen to 96-98% (side margins ~8dp) at the panel's polish turn; same turn applies approved SPEC-U3-3 (16sp entry) and audits result-row heights toward the 44dp floor. Why: their phone palette deliberately reaches screen edges — 8% dead margin each side makes a touch-first palette feel inset/webby.
- **SPEC-U7-2 — NotificationToastBanner phone sizing.** Now: fixed 320dp (~78% width), 6dp radius. After: full-width minus 16dp, 12dp radius (their xLarge), ~44dp rows, positioned below the status bar — banner polish turn. Why: their phone notification card is the proven spec (top-anchored, near-full-width, xLarge radius); ours is desktop-toast-sized on a phone.

No dialog spec — M3 defaults are deliberate keeps; the 44dp button floor rides already-approved SPEC-U3-1 at each dialog's own turn.

## §5 Connections

- U03/SPEC-U3-5: search panel's imePadding is the template; the audit spreads it (approved).
- U03/SPEC-U3-1: dialog buttons + palette rows covered by the approved floor.
- U03/SPEC-U3-3: palette entry 13→16sp lands via this surface's polish.
- U06: picker measurements deferred from U06 land here — palette verdict closes that loop.

## §6 Open questions

1. Palette result-row height (dp) — measured during SPEC-U7-1's polish turn, not blocking.
2. NotificationToastBanner anchor: default corner position vs their top-anchor — check at banner polish turn.

## Status

**DONE** — 2026-09-15. SPEC-U7-1..2 logged pending per-item approval. Next: U08 terminal & debug visuals (final research batch).
