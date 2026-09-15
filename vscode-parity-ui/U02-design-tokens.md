# U02 — DESIGN TOKENS & DENSITY FOUNDATION

> UI-sweep batch 2 of 8. 2026-09-15.
> VS Code: `build/lib/stylelint/validateDesignTokens.ts` (539 lines — the CI token enforcer, read directly), `workbench/contrib/modernUI/browser/media/fontRamp.css` + `padding.css`.
> Ours: full-tree grep fingerprint over `ui/` + `editor/`.

## §1 VS Code's token system (EXACT, from their own build validator)

They don't just have tokens — **a stylelint validator in their CI build flags every off-token value**. This is why their UI reads as disciplined. The enforced scales:

| Dimension | Scale | Enforcement |
|---|---|---|
| Spacing | `[2,4,6,8,10,12,16,20,24,28,32,36,40]px` (`--vscode-spacing-size{px×10}`, `sizeNone`=0) | off-scale → snap-to-nearest violation |
| Corner radius | xSmall **2** / small **4** / medium **6** / large **8** / xLarge **12** / circle (≥100px) | off-scale → token suggestion |
| Font ramp | heading1 **26**/600, heading2 **18**/600, heading3 **13**/600, body1 **13**/400, label1 **12**/400, body2 & label2 **11**/400, label3 **10**/400 | exact-match → token suggestion (advisory) |
| Icon size | codicon **16** base / **12** compact ONLY | 13–15px = "**always a mistake**" near-miss violation |
| Borders | `strokeThickness` = **1px** for all strokes | literal 1px → token violation |
| Weights | 400 / 600 (semiBold) only | via font ramp |

The `modernUI` contrib (experimental `workbench.experimental.modernUI` setting) applies the ramp workbench-wide; the sessions/chat UI already uses these tokens unconditionally.

## §2 Our density fingerprint (grep census, 2026-09-15)

| Dimension | Top values (counts) | On their scale? |
|---|---|---|
| Radius | 4dp(81), 8dp(58), **3dp(28)**, 12dp(25), 6dp(19), 2dp(17), **10dp(13)**, **14dp(3)** | mostly yes; 3/10/14 off-scale |
| Spacing-h | 12dp(190), 8dp(98), 4dp(56), 16dp(42), 20dp(33), 6dp(29) | **ALL on-scale** |
| Spacing-v | 4dp(119), 10dp(116), 2dp(82), 8dp(61), 6dp(53), **1dp(30)** | all but 1dp on-scale |
| Fonts | 11sp(393), 12sp(352), 10sp(291), 13sp(255), **9sp(106), 14sp(74), 8sp(21), 15sp(8), 7sp(2)** | 11/12/13 = EXACT ramp; **129 uses below their 10px floor** |
| Icons | 16dp(106), **14dp(80), 18dp(43), 20dp(37)**, 12dp(26), 28/32/24dp | 14/18/20 = 160 uses in their "near-miss/mistake" zone |

## §3 Verdicts

| Item | Verdict |
|---|---|
| Typography ramp | **MATCH** — dominant 11/12/13sp IS their exact body2/label1/body1 ramp. The single best-parity dimension. |
| Text floor | **DIVERGES-BAD** — 7/8/9sp (129 uses) below VS Code's 10px floor; tiny text is our biggest legibility drift |
| Icon sizes | **DIVERGES-BAD** — biggest token drift: mixed 12/14/16/18/20 across rows vs their strict 16/12-only; inconsistent icon scale reads as "off" more than any single surface |
| Spacing | **MATCH** — top values all on-scale (our 12h/10v standing rule = their 12px/10px slots) |
| Radius | MOSTLY-MATCH — 4/8/12 dominant; 3/10/14dp (~44 uses) off-scale |
| 1dp vertical paddings | DIVERGES-BAD (minor) — 30 uses; scale has 0 or 2 |
| CI token enforcement | **MISSING** — they lint, we don't |

## §4 Cross-cutting specs — now → after → why (ALL FIVE APPROVED per-item by Wisdom 2026-09-15; applied PER-SURFACE inside each U-batch's polish turn, never as one global sweep)

**SPEC-U2-1 — Icon normalization to 16/12-only.**
- Now: mixed icon scale across the app — 14dp (80 uses), 18dp (43), 20dp (37), alongside 16dp (106) and 12dp (26); adjacent rows often mix 14 and 18 in the same panel.
- After: every icon is 16dp (primary) or 12dp (compact/dense rows); 14dp→16 in toolbars/panel headers, 18/20dp→16; dense inline icons→12. Applied per surface during its own polish turn.
- Why: their CI validator calls 13–15px icons "always a mistake" — 16/12-only is enforced discipline, and mixed icon scale is the single biggest driver of an inconsistent, unpolished read.

**SPEC-U2-2 — 10sp text floor.**
- Now: 9sp (106 uses), 8sp (21), 7sp (2) — session timestamps, snippet previews, badges.
- After: minimum 10sp (their label3 floor) for all text; 7/8/9→10.
- Why: their ramp's floor is 10px; below it text stops being readable on-device and reads as "crammed" rather than "dense."

**SPEC-U2-3 — Radius snap.**
- Now: off-scale radii 3dp (28), 10dp (13), 14dp (3) alongside dominant on-scale 4/8/12.
- After: 3dp→4dp (chips may go 2dp), 10dp→8dp, 14dp→12dp — full conformance to their 2/4/6/8/12 scale.
- Why: their validator flags every off-scale radius; snapping aligns the ~17% off-scale corners with the 83% already correct, cheap mechanical fix.

**SPEC-U2-4 — Kill 1dp vertical paddings.**
- Now: 30 uses of `vertical = 1.dp` (sub-visual nudge spacing).
- After: 0dp or 2dp (their scale has no 1).
- Why: 1dp is invisible-but-jittery; their scale is CI-enforced from 2 up; trivial fix, done per-surface.

**SPEC-U2-5 — CsTokens object + scale lint script.**
- Now: no shared constants — dp literals inline everywhere; nothing enforces any scale; drift recurs by default.
- After: one `CsTokens` object (radius/spacing/type constants mirroring this table) for new code, + a small grep-based lint script in CI that flags off-scale values in changed files — our mirror of `build/lib/stylelint/validateDesignTokens.ts`.
- Why: VS Code's discipline comes from CI enforcement, not reviewer taste; a lint gate makes every U-batch spec mechanical and prevents regressions after surfaces close.

## §5 Connections

- Every U-batch cites this table; MEASUREMENTS.md updated with EXACT values (supersedes estimates).
- **U01 revisions from these exact values:** SPEC-2 input box radius 10dp → **8dp** (radius-large = 8px, not ~10); SPEC-3 send button radius 8dp → **4dp** (radius-small = 4px). Amended in U01 §4.
- dp↔px: on Wisdom's 412dp-class device, 1dp ≈ 1 CSS px, so VS Code px values map to dp 1:1. No scaling needed.

## §6 Open questions

1. FontWeight census — we use SemiBold(600) + Normal(400)? spot-check per-surface during polish turns.
2. Off-scale font 14sp(74) — their validator treats 14/16 as "common, often intentional" (advisory only) — no action beyond floor items.

## Status

**DONE** — 2026-09-15. SPEC-U2-1..5 ALL APPROVED per-item 2026-09-15 (per-surface application confirmed by Wisdom). U03, U04 done. Next research batch: U05 panels & list surfaces.
