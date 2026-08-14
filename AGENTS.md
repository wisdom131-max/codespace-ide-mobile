
### ⚠️ RULES REMINDER (read before doing ANY work in this repo):
1. TWO-REPO: Main IDE → codespace-ide-mobile | Proot/Ubuntu/rootfs → ubuntu-proot-test ONLY
2. CHANGE LOG: After every commit, add entry at BOTTOM of AGENTS.md with timestamp, commit SHA, CI build number+pass/fail, what was fixed, files touched, next on roadmap (ALL pending items).
3. TAGS: Use [BUILD-FIX], [LSP], [INTELLISENSE], [DOCS], [UI], [CRASH], [DAP], [GIT], etc.
4. CURRENT STATE: Update the "Current State" table at top with latest green build + commit SHA.
5. NEVER re-do work already marked done in CHANGE LOG or phase tables.
6. ROADMAP CONTINUITY: Every "Next on roadmap" MUST list ALL pending items — not just the immediate next step.
7. **NEW UI RULE: ALL menus/popups/dropdowns must use rounded corners (RoundedCornerShape 8-12dp). Save to memory.**

### [2026-08-14 10:55 WAT] — AI Agent: Claude (Base44 Superagent)
**Commit:** bb53a37 | **CI Build:** pending
**Tags:** [UI], [RESTRUCTURE]
**What was fixed:** VS Code-style UI restructuring — 7 items implemented:
1. Workspace bar: search pill now has 12dp rounded corners + border, back button moved closer (28dp box instead of 44dp) — matches VS Code layout where arrow sits right next to the pill.
2. Status bar: Ln/Col/UTF-8 now only show when a file is open in the editor (activeEditorTab != null) — previously showed on welcome screen too.
3. Activity bar: icons wrapped in two rounded Card containers (top group: Explorer/Search/Git/Run/Extensions, bottom group: Account/Settings) with 8dp rounded corners.
4. Empty editor area: rounded corners on all 4 sides (8dp) with clip.
5. Activity bar icons: increased from 24dp to 26dp.
6. Side panel (Explorer): rounded corners on topEnd + bottomEnd (8dp) with clip.
7. Gear menu: widened to 280dp, centered left, 12dp rounded corners, divider lines between sections, Themes row with chevron (>) that opens submenu showing Color Theme / File Icon Theme / Product Icon Theme (to the right of gear menu, VS Code style).
8. Explorer 3-dot menu: removed black scrim (was full-screen black overlay), now transparent, 12dp rounded corners, only closes via 3-dot tap (removed full-screen clickable dismiss).
**New rule added to AGENTS.md:** ALL menus/popups/dropdowns must use rounded corners.
**Files touched:** android/app/src/main/java/com/codespace/ide/ui/screens/ProjectShellScreen.kt, AGENTS.md
**Next on roadmap:** Verify CI green. Device retest all 57 tests. User wants to see VS Code-style UI on device before proceeding. Remaining UI items if needed: outline-style activity bar icons (VS Code sketch style), bottom panel rounded corners, chat panel rounded corners.

