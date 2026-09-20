# AUDIT-CHUNK-3 — EDITOR + LSP (2026-09-20, audit only)

READ = verified in repo this session. Device status = #2854 results only.
Cross-refs: chunk 1 (identity/scrollToLine coupling), chunk 2 (B-β, buffers), PLAN A per-file store.

---

## E1. CodeEditor structure & remount model

- Entry: editor/CodeEditor.kt:513; composed under `key(activeId)` (EditorPane:2052, READ) — a tab switch REMOUNTS the whole editor (all `remember{}` state resets). That is the load-bearing assumption for every "does it survive a switch" question below.
- 64KB JVM rule: extracted effect files exist (CompletionFetchEffect, GhostTextEffect, SignatureHelpEffect, EditorViewStateEffects — READ ls, chunk 1). Confirmed practice in place; any new effect must land in a separate file (standing instruction).
- Device: remount behavior verified indirectly by A-6 (band persists DESPITE remount — see E2: the leak is upstream, in the jump state, not in CodeEditor memory).
- VS Code: one TextModel per resource, view state via CodeEditorWidget saveViewState (comparison (c) READ :245-268).
- Gap size: MEDIUM (remount-per-switch is heavy but safe; PLAN A replaces the leak paths instead of the remount model). Risk: perf (decorations recompute), UX (gold band E2).

## E2. Gold band — A-6 VERIFIED FAILING, root cause READ

- Chain: EditorPane :483 `scrollToLine` (NOT keyed by tab) → CodeEditor :523 `scrollToLine` param → :1078 LaunchedEffect → `highlightTargetLine` (:748, unkeyed) + cursor + scroll; band auto-dismiss 5s (:772-776) and 6s (:1116-1121) depending on path.
- On tab switch: CodeEditor REMOUNTS and the effect RE-FIRES with the still-set scrollToLineParam if the switch happens inside the 1s (jump handler :2202) / 6s windows → band + cursor land on the SAME line number in the NEW file. Unkeyed-by-file jump state = the crosstalk. PLAN A's per-file canonical store keys exactly this.
- VS Code: revealLineNumber uses a TRANSIENT decoration tied to the model (comparison (b), go-to-decorations); remounting a different model discards it.
- Gap size: MEDIUM. Risk: UX (verified device failure; no data risk).

## E3. Squiggles — A-6 residual, two READ-confirmed mechanisms

- EditorPane :418 `lspSquiggles`; BUG-B fix :1360-1382 clears on active-tab change + re-pulls cached diagnostics for the new tab (in #2854).
- Residual leak 1 — filename-fallback match (:1551-1560, READ): diagnostic push handler accepts `diagFile == ourFile` (basename-only). Two same-named files in different dirs (or a stale push arriving just after a switch) re-populates the NEW tab's squiggles with the OLD file's diagnostics.
- Residual leak 2 — CodeEditor merge effects :1925-1937 (READ): `lintErrors` remember{} + `LaunchedEffect(lspDiagnosticErrors)` re-merge runs on remount with whatever lspSquiggles holds; combined with leak 1, stale ranges survive the clear.
- Device: A-6 VERIFIED FAILING on #2854 (run-error squiggles + band persist across tabs) — consistent with both mechanisms.
- VS Code: markerService keys by resource (chunk-1 F-a, READ :27-34); diagnostics can never cross files.
- Gap size: MEDIUM. Fix class: URI-only match (drop basename fallback or make it verify canonical path), + PLAN A keyed store. Risk: UX.

## E4. Go-to-Definition — A-9 VERIFIED FAILING, root cause READ-CONFIRMED

- Chain (READ): EditorPane :2564 `onOpenFileAtLine?.invoke(defPath, defLine + 1)` — LSP 0-based → **1-based** passed up. ProjectShellScreen :4639 wrapper receives it and does `scrollTargetLine = line + 1` — the LINE-BASE-FIX comment (:4641) says "line is 0-BASED (OSC 7777 + tap detector)" — TRUE for terminal sources, FALSE for this call site. DOUBLE +1: def at LSP line 0 (line 1) → scrollTargetLine = 2 → band lands line 2. Exactly the #2854 A-9 symptom.
- Same-file branch :2561 `scrollToLine = defLine + 1` is correct (no wrapper) — which is why same-file jumps test clean and cross-file ones are off by one.
- VS Code: gotoCommands.ts passes editor position objects, never bare ints (comparison (b) READ :201-238) — no convention to get wrong.
- Gap size: SMALL code-wise, but it is the visible top bug. Fix: one line-convention rule at the EditorPane↔PSS boundary (EditorPane passes 0-based to onOpenFileAtLine, wrappers own the +1) or keyed per-source conversion. Risk: UX.

## E5. Line-convention matrix for onOpenFileAtLine (ALL call sites, READ)

| Call site | Passes | Wrapper +1? | Net |
|---|---|---|---|
| EditorPane go-to-def :2564 | 1-based | PSS :4639 +1 | **+1 BUG (A-9)** |
| EditorPane go-to-decl :2583 area | 1-based | PSS :4639 +1 | same BUG (untested on device) |
| Terminal OSC 7777 / tap :3529, :3558 | 0-based | +1 at PSS | correct |
| `ide open` :4646 wrapper comment | 0-based | +1 | correct |
| Problems panel :3538 onJumpToSource | ? | NO +1 (raw line) | SUSPECT — need AdvancedProblemsPanel base; if 0-based, Problems jumps land one line LOW (unverified on device; A-3 tested path, not line base) |
| File search :1850 | 0-based | +1 | correct |
| Search panel :1591 | ? | raw (scrollTargetLine = line) | SUSPECT, same question |
| Timeline/problems-band internal (EditorPane :2202) | line as given | scrollToLine = line | base = whatever caller passed |

Root rule for the fix plan: onOpenFileAtLine must have ONE documented base. Chunk-1 coupling map already flags scrollToLine as shared mutable state; E4/E5 completes the picture: shared state + mixed conventions.

## E6. Diagnostics pipeline

- EditorPane :2090 DiagnosticPublisher.publishLintDiagnostics(active.path, newText) — central store; ExplorerPane :267 reads it for badges (chunk 1); Problems panel reads guest/host paths via :3538 translation chain (READ :3533-3546: /host-files strip → direct → guestPathToHostFile → hostPathFromFileUri — 4-way fallback).
- LspManager.getDiagnostics cache + getServerGeneration guard (:1371-1382 READ).
- VS Code: markerService per-owner per-resource maps (READ, comparison (a)).
- Device: explorer problem badges + problems jump not re-verified on #2854 post-BUG-A-fix. Gap size: SMALL. Risk: UX.

## E7. Completion / signature help / ghost text

- CompletionFetchEffect (chunk-1 ls; memory): cancellation-ID off-by-one + 5s withTimeoutOrNull blocking — CLOSED, intentionally left (dot-completion closure 2026-09-06). Do not re-open.
- GhostTextEffect, SignatureHelpEffect: shipped, not in #2854 batch.
- VS Code: suggest model per editor (comparison (d) READ :400-440).
- Gap size: NONE this chunk (audit honors closure decision). Risk: none.

## E8. Hover / inlay hints

- EditorPane :1617 lspHoverContent (+parse), cleared :1708 on tab change; inlay hints CodeEditor :1944-1947 (600ms debounce after lint).
- Device: not in #2854 batch. VS Code: hoverParticipates via MarkdownRenderer (comparison MEMORY). Gap size: SMALL. Risk: UX cosmetic.

## E9. Save path

- Entry: EditorPane :271-314 (READ): `File(activeTab.path).writeText(activeTab.content)` + FileCache.invalidate + LspManager.didSave — direct write, no checkpoint (20s loop is the only safety net, and it is root-broken per chunk-2 W1 for prefs-less projects), no drift check (buffer IS the truth; correct for an editor).
- VS Code: textFileService save pipeline with backups (MEMORY); save participants.
- Gap size: SMALL (editor save is trusted by design). Risk: data-loss LOW (autosave-loop W1 caveat).

## E10. Local History dialog — B-α VERIFIED FAILING, root cause READ-CONFIRMED

- Entry: ExplorerPane :1685-1699 (READ): root discovery = walk UP from the file while no `.git` exists and parent name != "projects" → `p ?: f.parentFile`.
  - Non-git /sdcard project: walk runs to the storage root → v2DirFor(storageRoot, file) → snapshots were written under the REAL project root → EMPTY list ("no snapshots" or wrong list).
  - Git project with nested submodules: stops at the submodule root → wrong tree.
  - In-app project: heuristic accidentally works (parent "projects").
- The 20s loop (chunk-2 W1) writes under loadWorkspacePath(prefs-only) — the dialog reads under .git-walk heuristic — TWO different roots in one feature. TimelinePanel (W3) uses workspaceRoot — a THIRD resolution path in the same feature family.
- BONUS (B-β class): dialog Restore :2354-2362 does copyTo + refresh++ (Explorer only) — NO bumpExternalRestore → open editor tabs do NOT refresh after dialog restore either (same verified symptom class as W3).
- VS Code: local history restore works off resource URIs; no root re-derivation exists to get wrong (comparison (f)).
- Gap size: MEDIUM. Fix: all three surfaces take the root from ONE ProjectPathResolver call. Risk: data-loss adjacent (restore appears broken; user may re-edit on stale buffer).

## E11. Find/replace, multi-cursor, go-to-line

- Shipped features (memory #33-35): not in #2854 batch; no new READ this chunk (no reported failures; surface-close rule means any future feature work on these includes a re-polish pass).
- Gap size: NONE flagged. Risk: none.

---

## CHUNK-3 FINDINGS (ranked)

1. **E4/E5 line-convention bug (A-9)** — READ-CONFIRMED root cause: 1-based call sites + unconditional wrapper +1. Fix is a documented single convention; also check Problems/Search panel bases (E5 SUSPECT cells).
2. **E10 Local History dialog root discovery (B-α)** — READ-CONFIRMED: .git-walk heuristic ≠ writer root ≠ Timeline root; plus dialog restore skips appliedTick bump (B-β class).
3. **E2 gold band (A-6 half)** — re-fire on remount inside the 1s/6s window; PLAN A keyed store is the fix.
4. **E3 squiggle filename-fallback (A-6 other half)** — basename match can cross-apply diagnostics; URI-only match or canonical verify.
5. **E9/W1 interaction** — editor save has no checkpoint and the autosave loop may never run (prefs-only root); combined = real data-loss window for non-override projects.

## Device-verified ledger (chunk 3)

- A-9 go-to-def off-by-one: VERIFIED FAILING on #2854 → root cause READ (double +1 at PSS :4639).
- A-6 band + squiggles: VERIFIED FAILING on #2854 → E2/E3 mechanisms READ; remount model verified working (leak is upstream state).
- B-α dialog nested-restore: VERIFIED FAILING on #2854 → root cause READ (E10).
- A-3 problems-jump path: fix shipped #2844/#2854, retest PENDING; line base SUSPECT (E5).
- Everything else: UNVERIFIED on #2854 (or intentionally closed, E7).

Done: 3. Next: 4 — chat / AI / settings / models (workdir source for C-4, Mistral group gap, MK retests, endpoint registry).
