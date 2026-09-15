# U06 — MENUS, DROPDOWNS & POPOVERS AT NARROW WIDTH

> UI-sweep batch 6 of 8. 2026-09-15.
> VS Code: code-search (context-menu restyle hits: **0**), `sessions/contrib/providers/copilotChatSessions/browser/mobilePermissionPicker.contribution.ts` + `PickerActionViewItem` pattern, phone quick-input rules (captured U03).
> Ours: 14 files using Compose `DropdownMenu`, custom surfaces (`TerminalRootMenu.kt`, `ChatModelMenuButton.kt`, `NotificationDrawerOverlay.kt`, inline shell menus), explorer long-press flows.

## §1 VS Code's phone menu strategy (source-verified)

**They don't restyle menus on phone — they replace them.**

- **Context menus: zero phone rules.** A repo search for context-menu restyling in the sessions layer returns nothing — the Monaco context menu ships desktop-styled on their phone workbench (22px rows, hover-oriented). Their answer is to **route around it**:
- **Dropdown triggers become pickers.** `mobilePermissionPicker.contribution.ts`: a dropdown action view item is swapped for `PickerActionViewItem` → `MobilePermissionPicker` (a QuickPick-based sheet). Same for mode/model pickers → `MobileChatInputConfigPicker` bottom sheet (U01).
- **When a menu/picker DOES open, the quick-input phone rules apply** (U03): widget pinned edge-to-edge minus 8px, rows min 44px, list max 50vh — i.e., pickers get the full mobile treatment, dropdown/context menus get none.

## §2 Ours (line-verified)

14 files use Material3 `DropdownMenu` (platform handles insets, dismissal-on-outside, elevation); custom menu overlays exist (TerminalRootMenu, ChatModelMenuButton, NotificationDrawerOverlay, shell inline menus with 24dp icon boxes on 4dp-rounded backgrounds — compact tier); explorer long-press opens purpose-built flows (e.g., image import) rather than a generic context menu; standing UI rule: all menus 8-12dp radius + ≥12h/10v row padding (verified in shell: 16h/10v rows, one 12h/5v straggler).

## §3 Verdicts

| Item | VS Code phone | Ours | Verdict |
|---|---|---|---|
| Generic context menu | unre-styled desktop menu (weak on touch) | no generic context menu; long-press → purpose-built flows | **MATCH-or-AHEAD** (deliberate; theirs is a gap they route around, we solve with flows) |
| Dropdown → sheet conversion | dropdown triggers swap to pickers/sheets | ChatModelMenuButton stays a dropdown | **DIVERGES-DELIBERATE** (this was SPEC-6 — Wisdom: skip, header dropdown fine) |
| Picker/list treatment | 44px rows, edge-to-edge-8px, 50vh cap | ProjectFileSearchPanel (search panel) | deferred to U07 (palette/overlay batch measures it) |
| Menu radius | context menu unre-styled; cards xLarge 12px | 8-12dp standing rule | **MATCH** (on their scale; M3 default 4dp must stay overridden — audit item) |
| Menu row padding/height | — | 16h/10v (mostly), one 12h/5v straggler | on-scale (12/16=size120/160); the 5v straggler → SPEC-U6-1 |
| Compact icon boxes in menus | navbar 28px compact tier | 24dp boxes, 4dp rounded | MATCH (compact tier, re-confirmed SPEC-U3-1) |

## §4 Specs

- **SPEC-U6-1 — DropdownMenu audit per polish turn.** *(APPROVED 2026-09-15.)* Now: 14 files use M3 DropdownMenu; M3's default 4dp corners + default row insets drift from our standing rule (8-12dp radius, ≥12h/10v padding); one shell menu row at 12h/5v (below vertical floor). After: each surface's polish turn confirms its menus use the standing radius/padding and ≥40dp rows; fix the 5v straggler when that surface's turn comes. Why: menus are where our own standing rules leak most easily — M3 defaults silently fight them; their card tier (xLarge 12px) shows menus-on-phone want the *larger* radius.

No other specs — the dropdown-vs-sheet question is closed (SPEC-6 skip, confirmed), and long-press flows are a deliberate ahead-of-them pattern.

## §5 Connections

- U03/SPEC-U3-1: 24dp compact icon boxes = sanctioned compact tier.
- U07: the picker measurement deferred here lands there (palette/dialogs/notifications full pass).
- U02 scale: 12/16h + 10v padding on-scale; 4dp M3 default = their `small` tier but standing rule prefers `large` 8 for menus — keep 8+.
- B11 (Problems/diagnostics) overflow menus: audited in their own polish turn under SPEC-U6-1.

## §6 Open questions

1. Does any DropdownMenu file override M3 shape to 4dp? (audit rides polish turns; not blocking research)

## Status

**DONE** — 2026-09-15. SPEC-U6-1 APPROVED per-item by Wisdom 2026-09-15. ChatModelMenuButton dropdown: confirmed CLOSED (consistent w/ SPEC-6 skip). U07 done. Next research batch: U08 terminal & debug visuals.
