# U01 — CHAT PANEL UI (composer, buttons, spacing, layout)

> UI-sweep batch 1 of 8 — the flagged surface. 2026-09-15.
> VS Code: `microsoft/vscode` @ `main` — sessions chat CSS (verified live; full extraction in [MEASUREMENTS.md](MEASUREMENTS.md)).
> Ours: `CopilotChatPanelInline` in `ui/screens/CopilotChatPanelOverlay.kt` @ `7cce8dc`, line-verified.

## §1 VS Code's narrow-width chat UI (source-verified)

**The headline: VS Code has a dedicated phone chat stylesheet.** `src/vs/sessions/contrib/chat/browser/media/chatInputMobile.css` (52 lines, read in full) — imported after desktop `chatInput.css` under `.agent-sessions-workbench.phone-layout` (the web sessions workbench's phone class). Their chat on a narrow screen is a **deliberately designed surface**, not desktop-shrunk:

- **Input structure = stacked, not one row.** A rounded-top input container holds: a compact **toolbar row** (padding `4px 6px 6px 6px`, 4px gaps, 22px compact icon pickers with 30px min-width touch targets, `touch-action: manipulation`) and beneath it the **input box** (radius-large on ALL corners, flat `agentsChatInput` background, **borderless**, Monaco editor min-height 50px, ~64px running; phone adds `padding-top: 8px / bottom: 6px`).
- **Send button (phone): 36x36 filled rounded square** — solid primary fill, radius-small — vs desktop's ghost circle. Deliberate: phone gets a bigger, filled, accent target.
- **Mode/model pickers on phone don't open dropdowns** — they route to a unified **bottom sheet** (the `MobileChatInputConfigPicker` contribution; the CSS file explicitly says so).
- **Message column is centered, max-width 950px**, 32px horizontal padding; messages render flat on `session-view-background` — **no bubbles**.
- Voice toolbar exists in the sessions UI (mic/stop/settings/disconnect, 36px+ targets).

Live-render caveat: vscode.dev's chat is GitHub-sign-in-gated, so no screenshot was obtainable headless (attempt logged 2026-09-15); `chatInputMobile.css` + `chatInput.css`/`chatView.css`/`chatWidget.css` are the authoritative spec — arguably better than pixels-from-screenshots since these ARE the values.

## §2 Our composer (line-verified)

`CopilotChatPanelInline` input area (~lines 1841-1926): **one flat `Row`, 8dp padding**, containing in horizontal order: AttachFile `IconButton` (18dp icon in M3's 48dp touch target), AccountTree (workspace-ctx) `IconButton`, History `IconButton`, then `OutlinedTextField(weight(1f), maxLines=4)` — M3 **outlined** look: visible border (divider unfocused / accent focused), filled `inputBg` container — then a bare 20dp Mic `Icon` (clickable, not IconButton), then Send `IconButton` (accent ghost) — plus a Stop `IconButton` while streaming. Pre-input stack above (error, queue bar, context gauge, repo pill, auto-instructions chip, attachment chips) is full-width rows — structurally fine.

**The arithmetic that explains the "off" feeling:** at 412dp portrait minus panel margins, THREE 48dp IconButtons (~144dp) + Mic 20dp + Send 48dp ≈ **212dp of chrome around a field that gets ~180dp** — barely enough for a sentence, and the input visually reads as an afterthought squeezed between buttons. VS Code's phone layout gives the input the full row minus ONE 36dp send button, with all other controls in a compact separate toolbar.

## §3 Verdict table

| UI item | VS Code phone | Ours | Verdict |
|---|---|---|---|
| Composer structure | toolbar row ABOVE input box | 6 controls inline in one row | **DIVERGES-BAD** (primary "off" driver) |
| Input box style | borderless flat surface, radius-large | M3 OutlinedTextField w/ visible border | **DIVERGES-BAD** (the "clean" look = no border) |
| Input field width | ~full row minus send | ~180dp on 412dp screen | **DIVERGES-BAD** (follows from row layout) |
| Send button | 36dp filled rounded square, primary | 48dp ghost IconButton, accent icon | **DIVERGES-BAD** |
| Toolbar controls | 22px pickers / 30px targets, compact | 48dp IconButtons w/ 18dp icons | **DIVERGES-BAD** (row bloat, sparse look) |
| Mic | voice toolbar, 36px+ target | bare 20dp icon | **DIVERGES-BAD** (touch target; RP6 must fit) |
| Mode/model picker | bottom sheet (phone) | header dropdown menu | DIVERGES-DELIBERATE→MATCH-able (bottom sheet matches our existing sheet patterns) |
| Input height | min 50px, ~64px running | OutlinedTextField ~56dp, grows to 4 lines | MATCH (close enough) |
| Container radius | rounded-top radius-large (~10px) | flat, no rounded-top container | DIVERGES-BAD (minor) |
| Message list | flat, no bubbles, centered column | assistantBubble-colored messages | DIVERGES-DELIBERATE (mobile chat convention; keep unless flagged) |
| Pre-input stack (gauge/pill/chips) | full-width compact rows | same pattern | MATCH |
| Keyboard handling | input stack pinned above IME | no `imePadding` found in file (§6) | UNKNOWN → verify on device |

## §4 Spec items — now → after → why (awaiting per-item approval; code lands in Phase 1 AFTER CW2/CW8)

- **REVISED 2026-09-15 (U02 exact tokens):** radii corrected to their scale (8dp box/container, 4dp send); SPEC-6 (bottom-sheet model picker) SKIPPED per Wisdom's decision — header dropdown stays. IME finding added to SPEC-1. **APPROVED 2026-09-15 (Wisdom, per-item): SPEC-1..5 ALL — approved-pending-its-turn, queued behind CW2/CW8 per Phase 1 ordering. NOT implemented.**

**SPEC-1 — Composer split into toolbar + input rows.** Now: one Row, 6 controls inline. After: a compact toolbar Row above the input (attach, workspace-ctx, input-history — 20dp icons in 32dp clickable targets, 4dp gaps, 6dp container padding), and an input Row (field weight(1f) + send). Why: restores ~370dp field width on your device; matches phone-layout structure; `touch-action: manipulation` analog = compact targets.

**IME finding (2026-09-15, static):** `enableEdgeToEdge()` is ON (MainActivity:65) and NO `imePadding` exists anywhere in the chat panel host path — the ONLY file in the app with `imePadding` is ProjectFileSearchPanel.kt. Statically the composer does NOT lift above the IME; SPEC-1 must include `.imePadding()` on the chat column. On-device confirmation still pending (focus bring-into-view may partially mask it).
- **SPEC-2 — Input box goes flat & borderless.** Now: OutlinedTextField w/ divider/accent border, inputBg. After: BasicTextField in a `RoundedCornerShape(8.dp)` surface, inputBg fill, **no border**, 12dp inner horizontal padding, min height 48dp, placeholder textSecondary. Why: radius-large borderless box is literally their "simple and clean".
- **SPEC-3 — Send button becomes 36dp filled rounded square.** Now: 48dp ghost IconButton. After: 36dp Box, `RoundedCornerShape(4.dp)`, accent fill, white Send icon (16dp), 40% alpha when disabled; Stop stays 48dp ghost (destructive action stays prominent). Why: phone-layout's one deliberate divergence — bigger-looking, filled, unmistakable primary action.
- **SPEC-4 — Mic gets a real touch target.** Now: bare 20dp Icon. After: 32dp clickable target (same compact style as toolbar), mic reserved slot in toolbar row so RP6's full voice UI has a home. Why: 20dp is under touch-guideline minimum; future-proofing per approved plan.
- **SPEC-5 — Rounded-top input container.** Now: flat bottom edge. After: wrap the input stack in a surface with `RoundedCornerShape(topStart = 8.dp, topEnd = 8.dp)` on the container. Why: the phone input container rounds its top corners — softens the transition from transcript to composer.
- **SPEC-6 — (deferred candidate) Mode/model picker → bottom sheet** to match MobileChatInputConfigPicker. Defer: our header dropdown works and this is a bigger change; listed for completeness, recommend NOT doing it in the minimal pass.

Message bubbles, message-list spacing, and header row: NOT in the minimal pass (bubbles = deliberate mobile convention; list spacing measured in U02's token pass).

## §5 Connections

- U02 (tokens): SPEC values all cite MEASUREMENTS.md — radius/spacing/font extraction is that batch's foundation.
- B13/RP6: mic slot reservation; voice toolbar shape when RP6 lands.
- CW2/CW8: attach-picker rows + retry/export — these add toolbar/content items, hence features FIRST (approved plan), polish after.
- B12: bottom-sheet pattern precedent (MobileChatInputConfigPicker ↔ our sheet surfaces).

## §6 Open questions / on-device checks

1. Keyboard: does the panel resize above the IME on your device (adjustResize) or is the composer covered? If covered → imePadding goes into SPEC-1.
2. Message-list vertical rhythm vs VS Code's flat list — measure in U02 (needs our message composable spacing read).
3. Gauge/pill/chip stack order vs VS Code's toolbar layout — reorder candidates go through per-item approval, not bundled.

## Status

**DONE** — 2026-09-15 (research). SPEC-1..5 APPROVED 2026-09-15 (per-item) — queued behind CW2/CW8; on-device IME tap-test result pending from Wisdom. U02 done. Next research batch: U04 editor surface.
