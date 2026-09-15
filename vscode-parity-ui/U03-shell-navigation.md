# U03 — SHELL & NAVIGATION AT NARROW WIDTHS

> UI-sweep batch 3 of 8. 2026-09-15.
> VS Code: sessions workbench mobile layer — `src/vs/sessions/browser/` (`workbench.ts`, `media/phoneLayout.css` 220L full-read, `parts/mobile/mobileEdgeSwipe.ts` 142L, `parts/mobile/mobileChatShell.css` 975L key greps, `parts/mobile/mobileLayout.ts` 33L full-read).
> Ours: `ProjectShellScreen.kt` + B12's structural map (cited, not re-derived).

## §1 VS Code's phone shell (source-verified)

Their phone layout is a **single-pane architecture where every other part becomes an overlay card** (`agents-part-card` pattern; `SinglePaneLayoutEnabledContext` in workbench.ts):

- **Runtime breakpoint, rotation-aware:** `phone-layout` class toggles on the main container via the layout policy's `viewportClass` observable; `Mobile Part` subclasses are chosen at construction, but the class re-evaluates on rotation (`mobileLayout.ts`). A `MobileTitlebarPart` **replaces the desktop titlebar** (48px, safe-area padding, 44px buttons).
- **Overlays, not splits:** sidebar = full overlay (`mobile-overlay-sidebar`); **panel = bottom sheet pinned to 60vh, rounded 16px top corners, slide-up 250ms ease-out, with a 36×5px drag-handle pseudo-element**; auxiliary bar = right drawer 85vw (max 400px) slide-in; **editors open FULL-SCREEN slide-up** (`.monaco-modal-editor-part`, 250ms, scrim rgba(0,0,0,.7)).
- **Edge-swipe navigation** (`mobileEdgeSwipe.ts`): swipe from the last **16px** screen edge, commit at **48px travel** (500ms window, 32px vertical tolerance) → opens sidebar.
- **Touch discipline:** ALL action items/buttons minimum **44×44px** (quick-input + chat toolbars exempt→compact); `touch-action: manipulation`; `-webkit-touch-callout/user-select: none` on toolbars.
- **Hovers are OFF on phone:** `.monaco-hover { display: none }` — hover-revealed UI becomes taps (`.visible-on-mobile` opt-in).
- **ALL inputs forced to 16px font** (`input, textarea, .monaco-inputbox input` — the anti-zoom/accessibility rule), overriding the 13px body ramp on phone.
- **Quick input (palette):** edge-to-edge minus 8px, rows 44px min, list max 50vh. **Dialogs:** width 100%-32px, buttons 44px + 16px font. **Notifications:** anchored top below safe-area+48px, xLarge radius, 44px row height constant.
- `overscroll-behavior: contain` on scrollables; `viewport-fit=cover` meta injected.

## §2 Our shell (line-verified + B12)

Single-column touch-first shell (B12 differentiator): 48dp activity bar (hamburger menu, VS Code-style; landscape MRU single visible panel), toggleable side pane (`SidePanel.EXPLORER|...|null`), fixed-height bottom panel (300px default, draggable height + maximized state, tabs Terminal/Preview/Split/Problems), portrait-only `statusBarsPadding()` shield (landscape edge-to-edge "fine" per inline comment), FindReplaceBar <480dp adaptive, fullScreen/zenMode states.

## §3 Verdicts

| Item | VS Code phone | Ours | Verdict |
|---|---|---|---|
| Single-pane architecture | parts → overlay cards | single-column, side pane + bottom panel inline | **MATCH** (philosophy) — our B12 differentiator is their phone direction |
| Bottom panel | 60vh overlay sheet, 16px rounded top, slide-up, drag handle 36×5 | inline fixed-height, draggable height (state exists), square corners, no handle | **DIVERGES-BAD** (affordance: handle + rounded top signal "grab me") |
| Touch-target floor | 44×44 enforced | IconButtons 48dp pass; bare clickables < 44dp exist (20dp mic, 12-16dp icons) | **DIVERGES-BAD** → SPEC-U3-1 |
| Input font on phone | ALL inputs 16px | 13sp chat input, 11sp panel search fields | **DIVERGES-BAD** → SPEC-U3-3 (accessibility tier, deliberate phone override of ramp) |
| Hovers | disabled on phone | touch-only, none | **MATCH** (trivially) |
| Edge-swipe nav | 16px zone, 48px commit | absent | **DIVERGES-DELIBERATE — do NOT build**: Android owns left/right edge swipes (system back). Web VS Code has no system back, we do. |
| Modal editor full-screen | editors slide up full-screen | editor IS the main surface | N/A (their shell is chat-first; ours is editor-first) |
| Palette (quick input) | edge-to-edge, 44px rows, 50vh | ProjectFileSearchPanel (has the app's only imePadding) | deferred to U07 overlay batch |
| Dialogs | 100%-32px, 44px buttons, 16px font | Material3 AlertDialog defaults | MATCH-via-platform (M3 already 44+); no action |
| Notifications | top toast cards, xLarge radius, 44px rows | Android native Toasts | DIVERGES-DELIBERATE (platform-native; keep) |
| Safe-area/insets | env() everywhere | statusBarsPadding portrait-only shield; **no imePadding anywhere except ProjectFileSearchPanel** | DIVERGES-BAD (U01 IME finding; landscape nav-bar insets unverified §6) |
| Rotation awareness | viewportClass observable re-eval | key(orientation) re-compose + #8 rotation fix | **MATCH** |

## §4 Specs (pending per-item approval; per-surface application)

- **SPEC-U3-1 — 44dp touch-target floor** *(AMENDED by U04, pending Wisdom re-confirm: adds compact-tier exemption).* Now: bare clickables below 44dp (20dp mic, 12–16dp row icons). After: every tappable ≥44dp target with centered content (IconButtons already pass at 48) — EXCEPT dense editor chrome, which keeps a 28–32dp compact tier (their `editor-tabs-compact-height` exception). Why: their enforced floor; mis-taps on-device.
- **SPEC-U3-2 — Bottom panel becomes a real sheet.** Now: square-cornered inline panel, height draggable but no affordance. After: 16dp rounded-top corners + 36×5dp centered drag handle (grabs = resize, existing bottomPanelHeight/maximized logic). Why: their panel pattern; the handle *shows* the resize affordance we already implemented but never advertised.
- **SPEC-U3-3 — Input text 16sp on phone-class layouts.** Now: chat input 13sp, panel searches 11sp. After: 16sp for chat composer + text-entry fields (NOT labels/rows). Why: their phone CSS forces all inputs to 16px — deliberate accessibility override of the 13px ramp; bigger entry text is the single most-used text on a phone.
- **SPEC-U3-4 — LOGGED-DELIBERATE: no edge-swipe nav** (system-back conflict, §3).
- **SPEC-U3-5 — imePadding audit across all bottom-anchored surfaces** (chat column already in SPEC-1; sweep other composers/search panels in their own polish turns). Why: one-file coverage (ProjectFileSearchPanel) proves the gap is app-wide.

## §5 Connections

- U01: SPEC-1 imePadding = first application of SPEC-U3-5's pattern. U02: SPEC-U3-3 is the phone override tier above the 10sp floor (SPEC-U2-2).
- U07 (overlays/palette/dialogs): inherits 44px rows + 50vh rules. U08: bottom-sheet pattern for terminal visuals.
- B12: shell structural map; this batch adds the responsive tier B12 didn't cover.

## §6 Open questions

1. Landscape: is the bottom of our shell inset for the gesture nav bar in landscape edge-to-edge mode? (on-device check: landscape terminal, check bottom row overlap)
2. `bottomPanelHeight` drag — confirm the gesture exists in UI (state is wired; affordance unknown).

## Status

**DONE** — 2026-09-15. SPEC-U3-1..5 logged pending per-item approval. Next: U04 editor surface.
