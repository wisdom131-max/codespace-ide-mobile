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

## §4 Cross-cutting specs (pending per-item approval; applied PER-SURFACE inside each U-batch's polish turn, never as one global sweep)

- **SPEC-U2-1 — Icon normalization to 16/12-only.** All icons 16dp primary / 12dp compact; 14→16 (toolbar/panel icons) or →12 (dense rows), 18/20→16. Highest-impact density fix; phased per-surface.
- **SPEC-U2-2 — 10sp text floor.** 7/8/9sp → 10sp minimum (badges/labels); 129 uses phased per-surface.
- **SPEC-U2-3 — Radius snap.** 3dp→4dp (or 2dp chips), 10dp→8dp, 14dp→12dp (~44 uses).
- **SPEC-U2-4 — Kill 1dp vertical paddings** (0 or 2dp).
- **SPEC-U2-5 — Compose token object + lint script.** One `CsTokens` file (radius/spacing/type constants mirroring this table) + a small CI-side script that greps new code for off-scale values — our analog of `validateDesignTokens.ts`. Tooling, not UI code; separate approval.

## §5 Connections

- Every U-batch cites this table; MEASUREMENTS.md updated with EXACT values (supersedes estimates).
- **U01 revisions from these exact values:** SPEC-2 input box radius 10dp → **8dp** (radius-large = 8px, not ~10); SPEC-3 send button radius 8dp → **4dp** (radius-small = 4px). Amended in U01 §4.
- dp↔px: on Wisdom's 412dp-class device, 1dp ≈ 1 CSS px, so VS Code px values map to dp 1:1. No scaling needed.

## §6 Open questions

1. FontWeight census — we use SemiBold(600) + Normal(400)? spot-check per-surface during polish turns.
2. Off-scale font 14sp(74) — their validator treats 14/16 as "common, often intentional" (advisory only) — no action beyond floor items.

## Status

**DONE** — 2026-09-15. SPEC-U2-1..5 logged pending per-item approval (per-surface application). Next: U03 shell & navigation.
