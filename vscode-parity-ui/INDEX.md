# VS CODE PARITY — UI/RESPONSIVE RESEARCH (U-series)

> UI/visual/layout research sweep, distinct from the feature-parity sweep in [`../vscode-parity/`](../vscode-parity/) (B01-B14 + S01 + FINAL-REVIEW).
> Question: how does VS Code's own UI adapt at narrow/mobile widths, and where does OUR layout diverge in ways that make it "feel off"?
> Rules: verdicts are MATCH / DIVERGES-BAD (fix) / DIVERGES-DELIBERATE (keep) / NO-COMPARISON. All specs await per-item approval; code follows the approved ordering plan (per-surface: features → polish → closed).

## Files

| Batch | Topic | File | Status |
|---|---|---|---|
| — | Source-extracted VS Code tokens (radius, chat values, spacing) | [MEASUREMENTS.md](MEASUREMENTS.md) | ✅ living |
| U01 | Chat panel UI (composer, buttons, spacing) | [U01-chat-panel-ui.md](U01-chat-panel-ui.md) | ✅ DONE 2026-09-15 |
| U02 | Design tokens & density foundation | — | ⬜ |
| U03 | Shell & navigation (narrow-width) | — | ⬜ |
| U04 | Editor surface (tabs, find bar, gutters) | — | ⬜ |
| U05 | Panels & list surfaces (explorer/SCM/problems rows) | — | ⬜ |
| U06 | Menus, dropdowns & popovers | — | ⬜ |
| U07 | Overlays (palette, dialogs, notifications) | — | ⬜ |
| U08 | Terminal & debug visuals | — | ⬜ |

## Method (per batch)

1. VS Code narrow-width behavior from source (CSS/TS citations; live vscode.dev renders where auth permits — chat is sign-in-gated, documented in U01 §1).
2. Our implementation read at line level (actual dp/px, not memory).
3. Verdict table; every DIVERGES-BAD → a numbered now→after→why SPEC awaiting per-item approval.
4. UI code changes stay MINIMAL; approved plan: per-surface features first, polish last, surfaces close + re-polish on reopen.

## Key findings so far

- **U01 (chat, the flagged surface):** VS Code ships a dedicated phone stylesheet (`chatInputMobile.css`) — toolbar row SEPARATE from input, borderless radius-large input box, 36dp FILLED rounded-square send (vs desktop ghost circle), bottom-sheet mode pickers. Our composer squeezes ~180dp of field between 48dp IconButtons in one row, uses a bordered OutlinedTextField, and a ghost send — that stack is the "off" feeling. SPEC-1..5 written, awaiting per-item approval after CW2/CW8.
