# MEASUREMENTS — VS Code design tokens & chat values (source-extracted)

> Extracted 2026-09-15 from `microsoft/vscode` @ `main` (sessions chat media CSS).
> All U-batches cite these; update only with new source citations.

## Radius tokens (EXACT per build/lib/stylelint/validateDesignTokens.ts — U02)
| Token | Value | Used for |
|---|---|---|
| cornerRadius-xSmall | 2px | |
| cornerRadius-small | **4px** | phone send button, toolbar buttons |
| cornerRadius-medium | 6px | |
| cornerRadius-large | **8px** | chat input box (all corners), input container top |
| cornerRadius-xLarge | 12px | |
| cornerRadius-circle | 9999 (>=100px) | desktop send button (ghost circle) |

## Full scales (U02, from validateDesignTokens.ts)
- Spacing: [2,4,6,8,10,12,16,20,24,28,32,36,40]px (size{px*10}, sizeNone=0) — CI-enforced snap
- Font ramp: 26/18/13 (headings,600) 13/12/11/10 (body1/label1/body2-label2/label3) weights 400/600 only
- Icons: 16px base / 12px compact ONLY (13-15px = "always a mistake" per validator)
- Borders: strokeThickness = 1px
|---|---|---|

## Chat input (sessions UI)
| Element | Desktop | Phone (`phone-layout`) |
|---|---|---|
| Input box | radius-large all corners, `agentsChatInput-background`, borderless | same + editor padding 8px top / 6px bottom |
| Editor height | min 50px (JS clamp), ~64px running-session | same |
| Toolbar row | padding 4px 6px 6px 6px, gap `spacing-size40`(4px) | same, compact |
| Toolbar buttons | 22px compact pickers, min-width 30px targets, radius-small, `touch-action: manipulation` | mode/model via bottom SHEET (MobileChatInputConfigPicker) — no CSS, dedicated contribution |
| Send button | 36px target, ghost circle, transparent bg, icon-foreground | **36x36 FILLED rounded square (radius-small), primary button fill** |
| Input container | rounded-top radius-large, `session-view-background` | same |
| Editor margins | 0 6px 0 10px, padding-top 2px | + 8px/6px vertical padding |
| Content column | centered, max-width 950px, h-pad `spacing-size320` (32px) | same rules resolve to full-width |

## Spacing scale (observed)
4 / 6 / 8 / 10 / 16 / 32 px — paddings across chat CSS. Toolbar gaps 2-4px. `spacing-size20`=2px, `size40`=4px, `size320`=32px.

## Font sizes
Body: `fontSize-body2`. Labels: `fontSize-label2` (11px). Codicon compact: `codiconFontSize-compact` (~14px).

## Notes
- `.agent-sessions-workbench.phone-layout` class only exists in the **web sessions workbench** (vscode.dev phone) — the mobile overrides cascade AFTER desktop rules via specificity, never `!important`.
- Desktop send is ghost/circle; phone send is filled/square — deliberate mobile divergence.
- Live vscode.dev chat render was auth-gated (2026-09-15 attempt, documented in U01 §1) — CSS is the authoritative spec.
