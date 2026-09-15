# U04 — EDITOR SURFACE AT NARROW WIDTH (tabs, find bar, gutters)

> UI-sweep batch 4 of 8. 2026-09-15.
> VS Code: `src/vs/sessions/browser/parts/media/editorPart.css` (210L), `mobileChatShell.css` phone rules (editor/find sections), `modernUI/browser/connectedEditorTabs.ts`.
> Ours: `EditorPane.kt`, `editor/FindReplaceBar.kt`, `ui/panes/EditorStripQuickActions.kt`, `editor/CodeEditor.kt` (GUTTER_WIDTH).

## §1 VS Code at narrow width (source-verified)

- **Tabs collapse to a single-tab title.** `editorPart.css` styles `.title:not(.tabs) > .single-tab-and-actions-container` — with one editor (and on phone, sessions are modal single-editors anyway) there is NO tab strip; the title bar becomes a breadcrumb-ish single label. Multi-tab strip: height 32px (`spacing-size320`), first tab 8px left-corner radius, compact navbar tier 28px (`editor-tabs-compact-height`, `spacing-size280`).
- **Connected-tab look (modernUI):** tab surface literally = editor background color; strip background flattened against it; hover painted as one surface — tabs read as pages of the editor, not separate buttons.
- **Phone find = commandeered full-width row.** Desktop's tiny type-filter (24px input, 22×22 close — "designed for a mouse cursor") expands on phone to a full-width row: **min-height 52px, padding 6/4/6/8, thumb-sized targets, single visible input**. The header row HIDES on phone (label + toolbar `display:none`; the (+) moves to the title bar) and only reappears when the find widget opens.
- **Safe-area bottom:** `padding-bottom: max(16px, env(safe-area-inset-bottom))` — a guaranteed ≥16px gutter above the iOS home indicator.
- **In-editor Monaco find/replace widget:** NO phone restyling found in the sessions layer — the real editor find widget stays desktop-styled there.
- **Gutters:** no phone-specific changes; standard Monaco gutters.

## §2 Ours (line-verified)

Tab strip rendered at `EditorPane.kt:660` (`tabs.forEachIndexed` in a horizontally-scrollable strip) with `scrollToTab` auto-scroll to active; `EditorStripQuickActions` = 28dp targets (font ±, wrap, lock, nav back/forward); `FindReplaceBar` compact-stacks below 480dp (`compact = maxWidth < 480.dp`, two-row stacked, landscape identical — F1 retest pending); gutters unified behind one `GUTTER_WIDTH` constant ("single source of truth" per its own doc comment).

## §3 Verdicts

| Item | VS Code | Ours | Verdict |
|---|---|---|---|
| Multi-tab strip vs single-tab collapse | phone = single-tab title (chat-first shell) | scrollable strip + auto-scroll + quick actions | **DIVERGES-DELIBERATE** (editor-first app; strip is core; their sessions shell isn't an IDE shell) |
| Tab strip density | 32px strip / 28px compact navbar | strip + 28dp quick-action targets | **MATCH** — and this REFINES SPEC-U3-1: VS Code's own compact chrome runs 28-32px; the 44px floor governs action buttons/rows, not dense editor chrome. SPEC-U3-1 must keep a compact exemption tier (their quick-input/chat-toolbar exception class) |
| Find bar on narrow | phone: full-width commandeered row, 52px, thumb targets; in-editor widget NOT restyled | stacked 2-row <480dp, full-width | **MATCH — arguably AHEAD**: our FindReplaceBar adaptive layout is a phone treatment their in-editor find never got. F1 on-device retest will confirm |
| Connected-tab look | tab surface = editor bg | EditorTabColors (own scheme) | DIVERGES-DELIBERATE (theme-level, not layout; revisit only if tabs feel "separate") |
| Gutter | unchanged at narrow | GUTTER_WIDTH constant, already unified | **MATCH** |
| Header-row hiding | non-essential rows hidden on phone | quick-actions strip always visible | DIVERGES-DELIBERATE (strip = our primary editor nav) |
| Bottom safe gutter | max(16px, safe-area) | unverified | UNKNOWN → §6 |

## §4 Specs

- **SPEC-U4-1 — FindReplaceBar thumb-target pass.** Now: compact 2-row layout (F1 pending). After: during F1 retest, verify controls ≥44dp and row height ~52dp-equivalent vs their phone find; adjust only what the on-device result flags. Why: their phone find row is the proven thumb-sized reference; we're close but unverified on-device.

(No other specs — this surface is largely MATCH or deliberate. The SPEC-U3-1 refinement above is logged as an amendment, not a new spec.)

**Amendment to SPEC-U3-1 (from this batch):** the 44dp floor applies to action buttons and interactive rows; dense editor chrome keeps a compact tier at 28-32dp (VS Code's own `editor-tabs-compact-height` exception). SPEC-U3-1 wording updated to include this exemption on approval.

## §5 Connections

- U03/SPEC-U3-1: compact-tier exemption refined here (prevents over-application).
- F1 retest batch (AGENTS.md): FindReplaceBar is F1 — SPEC-U4-1 rides the same on-device pass.
- U02 tokens: tab strip values land on their scale (28/32 = size280/size320).

## §6 Open questions

1. Bottom safe gutter: does our editor/terminal content clear the gesture-nav bar in landscape edge-to-edge? (on-device: landscape editor, check last line overlap)
2. Tab strip height measured on-device during next APK polish turn (dp value unrecorded here).

## Status

**DONE** — 2026-09-15. SPEC-U4-1 logged (rides F1). SPEC-U3-1 amendment RE-CONFIRMED APPROVED 2026-09-15 (compact tier: EditorStripQuickActions stays as-is). Wisdom confirmed keeps: adaptive FindReplaceBar (ahead of VS Code), scrollable tab strip (editor-first), gutter width discipline. Landscape gesture-nav check + IME tap-test pending from Wisdom (together). Next research batch: U06 menus & popovers.
