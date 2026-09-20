# FIX-PLAN-VSCODE-ALIGNED v2 — phased fixes from the #2854 audit + upstream comparison (2026-09-20)

Direction agreed with Wisdom 2026-09-20. NO CODE until Wisdom approves phases.
Source evidence: VSCODE-SOURCE-COMPARISON.md (vscode @ 832cf23, copilot-chat @ 5863f5a).

## Per-file state store — DESIGN RULE (applies to Phase 3)

Key = (canonicalPath, owner). Owners are fixed strings: "lsp" (squiggles),
"run" (run-output/error markers), "jump" (target-line highlight), future: "search", "timeline".

    PerFileStateStore: MutableMap<StoreKey, Any>
    data class StoreKey(canonicalPath: String, owner: String)

- WRITE path keys by the canonical path OF THE FILE THAT PRODUCED the data (LSP reply carries its
  own URI; run output carries the run's file), NEVER by the currently-active tab.
- READ path keys by the ACTIVE TAB's canonical path: store[key(activePath, "lsp")] ?: emptyList().
- Housekeeping (optional, not correctness): drop buckets for closed tabs, EditorMemento-style limit.

Why this fixes A-6 with ZERO clear-on-switch code: the old file's ranges live under a different
key. On a tab switch nothing is cleared — the lookup address simply changes, so the new file's
bucket (or empty) is what renders. Stale data can never be drawn because rendering is addressed by
the same identity that selects the file. Write-side races are also safe: a slow LSP reply for
file A writes into A's bucket regardless of which tab is active. This is the upstream
markerService shape (src/vs/platform/markers/common/markerService.ts _byResource +
markerDecorationsService per-model rebuilds) — VS Code has no clear-on-switch code either.

Canonicalization = one resolver (ProjectPathResolver): File(path).canonicalFile for ".." and
symlink resolution, /sdcard <-> /storage/emulated/0 mapping (ProotInstaller.guestToHostPath
special case), proot guest->host prefix strip. One function, used at BOTH write and read key time.

## Phase 1 — Restore correctness (A1+A2+A3) + open-tab refresh after Restore

- A1 (= audit B-α): Local History dialog resolves entry paths through ONE canonical resolver;
  delete the parentFile walk-up fallback entirely.
- A2 (= audit B-β): after Restore copies the file, force-refresh EVERY open tab whose canonical
  path == restored path (extend the appliedTick observer from lastAppliedPaths to full-tab
  reload). Upstream analog: localHistoryCommands.ts restore() steps 2+4
  (soft-revert dirty copies -> copy -> revert({force:true})).
- A3 (stale-tab overwrite): the A2 refresh is what makes the tab current, so the first keystroke
  after a Restore can no longer write stale buffer content over the restored file. The general
  disk-change-since-load guard is Phase 7 (smallest slice below).
- Acceptance: restore a nested non-git file -> correct content in every open tab showing it;
  type immediately after restore -> edits land on the RESTORED content, not the pre-restore buffer.

## Phase 2 — Palette repair (dispatch only, no new commands)

Reconciled finding (2026-09-20): in #2854 source the ">" palette list
(ProjectShellScreen.kt :2281-2291) dispatches via handleMenuAction (:1029-1242). Verified by
grep over the whole function: "Toggle Word Wrap", "Open Folder", "Collapse All in Explorer",
"Refresh Explorer", "Save File", "Select Color Theme", "Command Palette", "Format Document",
"Close Editor", "Close All Editors", Git:* — NONE have a branch; all fall to else -> {} (:1241).
The three commands Wisdom saw "work" DO work — but via their OWN surfaces, not the palette:
- Toggle Word Wrap: EditorStripQuickActions.kt:76 (FeatureToggleStore.set("word_wrap", ...)).
- Open Folder: ExplorerPane.kt:1053 (real folder picker).
- Collapse All: ExplorerPane overflow :959 (real collapse). TRAP: the Explorer hamburger's
  "Collapse All" (ProjectShellScreen.kt:5234) only SHOWS a notification and collapses nothing —
  a false "worked" signal if the tree was already collapsed.
So the second click path exists on those surfaces; the palette path itself is dead in #2854.
Decisive on-device check before this phase: word wrap OFF -> open ">" palette -> tap
"Toggle Word Wrap" -> text does NOT reflow (palette just closes). If it DOES reflow on your
device, paste the [Output] lines and I re-audit for a second dispatcher (none exists in source:
:2302 handleMenuAction(item) is the only dispatch).
- Fix: give every listed string a real branch (or remove liars from the list). Hard requirement:
  before/after check — the palette shows EVERY one of its current commands, same labels, none
  dropped, all still dispatch; additions may only append at the end of the list.
- Acceptance: each of the 35 labels taps to a visible effect or an honest "not available" notice;
  zero silent no-ops.

## Phase 3 — Per-file store: diagnostics + jump highlight (replaces shared scrollToLine int)

- Move lspSquiggles + run-error markers into PerFileStateStore (owners "lsp", "run").
- Replace the shared scrollToLine Int with owner "jump": target line stored under
  (canonicalPath, "jump"); CodeEditor consumes it for the file it is rendering; band clears by a
  350ms-style timer with model-identity guard (upstream goToCommands.ts _openReference), not 6s.
- Delete EditorPane's duplicate shadow state (:480-488) as part of this.
- Acceptance: two files with diagnostics, tab switch -> no cross-tab squiggles; reopen ->
  squiggles re-derived from store with no LSP re-push; jump band appears in the TARGET file only
  and self-clears; no clear-on-switch code added anywhere.

## Phase 4 — Terminal link parser (pure function + ported tests)

- Port upstream terminalLinkParsing.ts generateLinkSuffixRegex clauses 1+2
  (foo:339 / foo 339 / "foo",339 / "foo", line 339[, character 12] / ranges) and the
  trailing [\[\]"'.] trim loop (terminalLocalLinkDetector.ts :140-157) as a PURE function
  (input: line text, cwd; output: candidate paths + line/col + trim ranges).
- Port upstream's test cases (terminalContrib/links/test) as device-runnable checks:
  spaced paths, Python File "...", line N, quote/bracket trimming, line+col forms.
- Candidates FS-validated before underlining; /host-files translated via the same canonical
  resolver as the Problems panel. Tap no longer extracts a word.
- Acceptance: tap `File "src/main py", line 2` -> opens exactly that file/line.

## Phase 5 — Model-selection fallback (stale-ID never sent)

- Upstream pattern chatInputModelSelectionController.ts initialize() (:169-212): if the stored
  model id is not in the live catalog -> selection 'pending': show default meanwhile,
  re-bind the stored id the moment it publishes. No request is ever sent with a stale id.
- Apply to custom-endpoint manual model IDs (MK restructure part B): stored IDs pend and re-bind
  on endpoint refetch; never 404 a send.

## Phase 6 — Agent tool capability hint

- Upstream: languageModels.ts capabilities { toolCalling, agentMode } (:283-288);
  suitableForAgentMode (:367-370) hides agent mode for models that never declared toolCalling.
- Smallest slice: per-provider/model capability flag in our model registry; when absent/false,
  agent-mode tool UI shows an explicit "model may not support tools" hint instead of silent
  missing-tools behavior (the Mistral group gap).

## Phase 7 — Conflict guard: don't overwrite a file that changed on disk since the tab loaded

- Upstream: textFileEditorModel.ts save() (:744-753) REFUSES autosave while a model is in
  CONFLICT state; only explicit user save proceeds.
- Smallest safe slice for us (no diff/merge/model system):
  1. Per-tab fingerprint = File.lastModified() captured at buffer load/refresh; our own writes
     update the fingerprint after writing.
  2. In the onContentChange write path, BEFORE writing: if the file's current lastModified()
     differs from the tab's fingerprint -> skip the write, mark the tab diverged, show a
     two-choice notice ("Reload" / "Keep mine"). Both choices clear the state; writes resume.
  3. Phase 1's Restore refresh and this guard share the fingerprint field.
- Depends on Phase 3 (fingerprint lives on the tab/store). Runs AFTER Phase 1 so restore
  refreshes are already covered.
- Acceptance: with file open, change it externally (root shell), type in the stale tab ->
  prompt appears, no silent overwrite; Reload shows external content; Keep mine writes once
  and re-fingerprints.

## A-9 evidence (jump target line) — answered from source

- The jump target is the DEFINITION line from the LSP response, never the press line:
  EditorPane.kt :2552 defLine = loc.range.start.line (0-based) -> :2556/2558 scrollToLine =
  defLine + 1; band renders at that 1-based line (CodeEditor.kt :3282+, consistent -1 math).
  The press/cursor position is only the REQUEST parameter (:2546 cursorLine/cursorCol passed to
  textDocument/definition).
- Regex FALLBACK (when LSP fails, CodeEditor.kt :3880-3891) also targets the first DECLARATION
  match, not the press line.
- Wisdom's discriminating retest (usage on line 5, def on line 1): lands line 1 = path correct;
  lands line 5 = press line used somewhere (would contradict source; would mean a shadow/param
  bug like the one fixed at :480); lands line 2 = LSP server returned range.start.line = 1
  (points one row off / at body) — the Phase 3 fail-loud commit logs the raw LSP range once per
  jump so we can tell immediately.

## Pending user inputs (blocked items)

- A-2 model used ___ / C-4 model used ___ / E-10 saw ___ (placeholders still blank).
- Palette word-wrap check result (Phase 2 gate).
- A-9 retest with usage on line 5.

## Phase gating

NO CODE until Wisdom approves each phase. Proposed order 1->7; Phase 4 is independent (pure
function) and can run parallel to 3; Phase 7 needs 3; Phase 6 needs nothing.
