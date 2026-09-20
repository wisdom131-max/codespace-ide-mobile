# AUDIT-CHUNK-5 — TERMINAL / PANELS / SHELL + MASTER LEDGER (2026-09-20, audit only)

READ = verified in repo this session. Device status = #2854 results only.
This chunk closes the #2854 audit: master ledger at the bottom consolidates chunks 1-5.

---

## T1. Terminal file-link tap — A-5 VERIFIED FAILING, root cause READ-CONFIRMED

- Chain (READ): TerminalPane :306-319 — tap → `emulator.screen.getWordAtLocation(col,row)` → `linkToken = word.trim()` → IdeTerminalBridge.resolveTappedFileLink (:168-179, then Unscoped :181-210: 4-step resolution + [TAP] diagnostics).
- Root cause: getWordAtLocation is Termux's WHITESPACE-delimited word extraction. A path with a space ("My Project/src/Main.kt:42") is cut at the space — only the last chunk reaches the resolver, resolution fails, tap does nothing. The resolver itself is fine (its 4 branches log every attempt via [TAP] lines).
- Fix class: span-based extraction (from tap position, scan the full row buffer for a path:line token allowing spaces), or at minimum detect "token ends mid-path" and retry with the full row. VS Code: xterm.js link providers match against the whole buffer line (comparison (e)).
- Device: A-5 VERIFIED FAILING on #2854 ("space-delimited bug") — root cause READ. Gap size: MEDIUM. Risk: UX.

## T2. Locked-root + OSC 7777 — CLEAN (READ)

- Locked-root wrapper :152-179 READ: canonical-path containment, fail-closed, [TAP] LOCKED log lines. Terminal locked to root A refuses root B files. Matches retest expectation ([LOCK] cwd echo, prior batch).
- OSC 7777: ide CLI installed idempotently (TerminalPane :826-833, :978-979), help text :283-284; line base 0-based → +1 at PSS wrappers = correct (chunk-3 E5 row 3).
- Device: [LOCK] retest pending from earlier batch (not a #2854 failure). Gap size: NONE. Risk: none.

## T3. Problems panel line base — RESOLVED CORRECT (chunk-3 E5 SUSPECT closed)

- READ: DiagnosticConverter :217 `startLine = LSP.line + 1` → central store is 1-BASED. AdvancedProblemsPanel :244 passes it raw → PSS :3538 `onJumpToSourceWithPath(hostPath, line)` raw → EditorPane scrollToLine 1-based. Correct end-to-end.
- ProjectFileSearchPanel :74 documents onOpenFileAtLine as 1-BASED and passes 1-based (:551); PSS :1591 wrapper raw = correct.
- Remaining base question: CodeEditor internal jumps :4528/:4570/:4612 (outline/peek) go through the SAME EditorPane param as go-to-def — verify their base in the E4 fix commit (if 0-based they are correct through the +1 wrapper; if 1-based they share the A-9 double-+1).
- BONUS (READ): CodeEditor :3563 `onOpenFileAtLine?.invoke(target, 0)` — intent "open, no line", but the +1 wrapper turns 0 into scrollTargetLine=1 → spurious line-1 band + cursor move. Fold into the E4 convention fix.

## T4. Output panel, zero-tab quiet

- Earlier-batch fix (zero-tab Output quiet) — retest pending, no #2854 failure reported. No new READ needed. Gap size: NONE.

## T5. Settings / backup surfaces

- CloudBackupPanel (ui/panels/CloudBackupPanel.kt :35 READ): loadBackups/doBackup/doRestore/doSyncSession; requires projectId+backendUrl (memory #53). Railway backend (memory #28). No #2854 failures in this family. 
- ProjectSettingsStore (lspEnabled etc.) drives LSP master toggle (chunk-3 E6 path). Settings screens: not audited file-by-file this round (no failures reported; surface-close rule applies).
- Gap size: NONE this chunk. Risk: none.

## T6. D-pad / keyboard navigation

- READ: D-pad handled ONLY in TerminalPane :382-383 (DPAD_LEFT/RIGHT = prev/next terminal tab). No D-pad focus traversal in menus/editor/panels. Not a #2854-reported failure; recorded as known gap (parity item, cosmetic tier).
- VS Code: full keyboard focus navigation. Gap size: MEDIUM (feature gap), priority LOW (no device complaint). Risk: none.

## T7. Split views / view state

- SplitViewStore + PAD-1/PAD-2 viewKey threading (chunk-3 E1 READ: EditorPane :2099-2105 viewKey=activeId, mount-restore from per-view scroll/cursor maps). No #2854 failures. Gap size: NONE. Risk: none.

## T8. Local API server (AgentApiServer) — chunk-2 W8

- /tool/write_file ungated (READ chunk 2). No device test in #2854 batch; fix planned with W7 in the Restore-guard commit family. Gap size: MEDIUM. Risk: data-loss (documented chunk 2).

---

# MASTER LEDGER — #2854 audit consolidation (chunks 1-5)

Fix plan = the 4 approved commits + PLAN A (per-file canonical store), plus re-test batches.

| # | Symptom (device #2854) | Root cause | Where READ | Fix lands in | Status |
|---|---|---|---|---|---|
| A-3 | Problems jump → "Could not read file: denied" | guestToHostPath missing /sdcard case (pre-#2844) + bottom-panel path un-canonicalized | chunk 2 (W-family) | shipped #2844/#2854 | FIX SHIPPED, retest pending |
| A-5 | Terminal tap fails on paths with spaces | getWordAtLocation whitespace word-split cuts the token | T1 | new: terminal link span-extraction commit (fold into terminal-translation commit) | ROOT-CAUSED |
| A-6 | Gold band + run-error squiggles cross tabs | unkeyed jump state re-fires on remount (band); basename-fallback diagnostic match (squiggles) | E2/E3 | PLAN A P1/P2 + URI-only match | ROOT-CAUSED |
| A-9 | Go-to-def at line 1 lands line 2 | EditorPane passes 1-based; PSS wrapper adds +1 assuming 0-based (double +1) | E4/E5 + T3 | LSP clear-on-switch commit scope: line-convention rule at EditorPane↔PSS boundary; also verify CodeEditor internal jump bases + :3563 zero-line case | ROOT-CAUSED |
| A-14 | (output paste never provided) | — | — | — | AWAITING USER PASTE |
| B-α | Local History restore fails on nested files | dialog root = .git-walk heuristic ≠ writer root ≠ Timeline root (3 roots, 1 feature) | E10 | Timeline clear + Restore-guard commit: single ProjectPathResolver root for all 3 surfaces | ROOT-CAUSED |
| B-β | Open tab doesn't refresh after Restore | EditorPane observer refreshes only via lastAppliedPaths(); Timeline bumps appliedTick but not appliedPaths; dialog bumps nothing | chunk 2 W3 + E10 | same commit: observer keys on appliedTick, dialog restore calls bumpExternalRestore | ROOT-CAUSED |
| C-4 | Agent git degrades to "run these commands" in in-app project | chat advertises HOST project root as workdir; execOnce runs it raw in GUEST → silent cd skip | C2 | /host-files terminal-translation commit: translate workdir host→guest in runCommand | ROOT-CAUSED |
| D-Mistral | Custom endpoint models missing from picker group | group renders only if entries exist; endpoint with no key → never fetched → NO group, NO error row (silent) | C3 | settings/model-picker commit family: explicit "no key / not fetched" row | ROOT-CAUSED (UI wiring) |
| D-Mistral-2 | Send fails "Invalid model" (one attempt) | Mistral auth-first: key accepted, model string rejected; raw response paste still needed | fix-batch 3 memory | — | AWAITING USER PASTE |
| E-8/E-10 | (pastes never provided) | — | — | — | AWAITING USER PASTE |
| S-1 (new) | Multi-select delete permanent, no trash | ExplorerPane :876 deleteRecursively, single delete trashes | chunk 2 | batch with Restore-guard commit | ROOT-CAUSED (not device-tested; risk-level fix) |
| S-2 (new) | Autosave loop silently off for most projects | prefs-only workspace root resolver; no workspace override = no snapshots | chunk 2 W1 | batch with Restore-guard commit (single resolver) | ROOT-CAUSED |
| S-3 (new) | Ungated write bypass (AgentTools + AgentApiServer) | no FlowGate on those paths | chunk 2 W7/W8 | containment commit (after CW7 per plan) | ROOT-CAUSED |

## Re-test batch proposal (after the 4 commits + PLAN A P1/P2)

- RT-1 jump lines: Problems click (line base), go-to-def cross-file (line 1 target), `ide open file:42`, terminal tap WITHOUT spaces.
- RT-2 markers: band/squiggle across tab switches (A-6), problems badges, no band on plain "open file" (:3563 case).
- RT-3 history: Local History restore on nested file (git project + /sdcard project), Timeline restore refreshes open tab, 20s snapshot presence on a NON-override project.
- RT-4 agent: in-app project AGENT git status/diff/commit (C-4), write staging still ungated-correct, force-apply still drift-scoped.
- RT-5 picker: Mistral endpoint with key + without key (group/error row), manual model entry, MK key pool retests (MK-1..9) if #2854+ APK.
- RT-6 carried retests from earlier batches: [LOCK] cwd echo, zero-tab Output quiet, emoji-typing IME lines (chunk-2 M-family, already written).

## Open user inputs (blocking)

1. A-14 paste. 2. E-8 paste. 3. E-10 paste. 4. Mistral raw response paste. Nothing else blocks the fix commits.

## VS Code comparison doc status

(a) markerService, (b) gotoCommands, (c) TextModel/viewState, (d) suggest model, (e) xterm link providers, (f) local history, (g) structured tools — all READ-cited in chunks 1-5; the doc + writer matrix live in this repo's vscode-parity/.

AUDIT COMPLETE: 5/5 chunks, all #2854 device findings root-caused or explicitly awaiting pastes. Commits: chunk1 → (prior), chunk2 ad54df1, chunk3 66cbcee, chunk4 55b4c34, chunk5 this commit.
