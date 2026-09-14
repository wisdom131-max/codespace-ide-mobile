# B07 — SOURCE CONTROL & GIT

> VS Code parity research, batch 7 of 14.
> VS Code: `microsoft/vscode` @ `main`, verified live 2026-09-14 (GitHub API listings).
> Ours: `codespace-ide-mobile` @ `239c149`, grep-verified.

## §0 Scope & sources inspected

VS Code: `workbench/contrib/scm/browser/` (scmViewPane, scmHistoryViewPane + scmHistory.ts, **scmHistoryChatContext.ts**, scmInput, scmRepositoriesViewPane, quickDiff.contribution + quickDiffDecorator/Model/Widget, menus.ts, workingSet.ts, scmAccessibilityHelp), `workbench/contrib/git/` + `workbench/contrib/gitBase/` (git extension: commit, staging, history, blame, timeline provider).

Ours: `scm/` package (GitService.kt 44 ops, ScmModels, ScmState, GitCommandExecutor), `ui/panes/SourceControlPane.kt` (1,918 lines), `ui/panes/DiffViewer.kt`, `chat/GitDiffAnalyzer` (R6 diff cards), editor blame (GitService/ScmState), TimelinePanel (.versionhistory).

## §1 What VS Code has (citations)

- **SCM view** (`scmViewPane.ts` + `scmInput.ts`): provider-model view (git is ONE provider — extensions can add others), commit input box w/ AI-generated message (Copilot button), staged/unstaged change lists w/ per-file actions, contributed context menus (`menus.ts`), multi-repo awareness (`scmRepositoriesViewPane`).
- **SCM history graph** (`scmHistoryViewPane.ts` + `scmHistory.ts`): visual graph of commits; **`scmHistoryChatContext.ts`** — SCM history is attachable to chat as structured context (new).
- **Quick diff** (`quickDiff*`): gutter changed-line indicators (modified vs HEAD, staged vs unstaged), click-to-revert-hunk widget (`quickDiffWidget.ts`).
- **Git extension** (`contrib/git`): stage/unstage/discard per file + per hunk, commit w/ signing/verification, sync/push/pull/fetch, branches/tags/stash, merge-conflict UI (conflict decorations + "current change" code lens), rebase/abort, blame annotations in-editor, **timeline provider** (git log feeds file timeline — B03 gap), commit hover actions, multi-repo, git-view contributions.
- **Working sets** (`workingSet.ts`): new experimental grouping of changes for review.

## §2 Architecture — shared state & connections

- SCM is provider-pluggable (`ISCMProvider`): the view/menus/history render against the abstraction, git registers as provider — third-party VCS possible.
- QuickDiff is an editor decoration driven by the diff machine — same gutter infra as blame; revert-hunk rides the editor edit stack (one-undo).
- Git timeline provider merges into the unified timeline (with local history) — one view, time-ordered, multi-source.
- Chat touchpoints: commit-message AI, history-as-context, inline-change explanations — SCM data feeds the AI layer.

## §3 What OUR app has (verified)

- **GitService (44 ops)**: stage/unstage, commit, push, pull, fetch, branches list/checkout/create/delete/rename, log (per-file), diffStaged, hasConflicts + conflictedFiles, merge, blame, stash (list/apply/drop), rebase, tags, remote ops via GitCommandExecutor.
- **SourceControlPane** (1,918 lines): commit history UI, stash management, branch mgmt, conflict resolution flow, .gitignore editor, tag management, 30s local version-history snapshots; **AI commit-message generation** wired in pane (I4, `commitMessage` path).
- **Diff machinery**: `DiffViewer.kt` (pane diff), `GitDiffAnalyzer` inline diff (R6 review cards), blame annotations in editor (blame toggle — PERSIST-A restores it).
- **TimelinePanel**: local .versionhistory only (B03: git commits not merged).
- **No** provider abstraction (git-only, hard-wired), no SCM history graph view, no quickDiff gutter (changed-line indicators — unverified §6), no discard-per-hunk, no commit signing/verification, no multi-repo (single project root — consistent w/ B03), no PR review UI (sessionPullRequestPill from integration map remains unimplemented).

## §4 Verdict table

| Feature (VS Code) | Verdict | Gap |
|---|---|---|
| Stage/unstage/commit/push/pull/fetch | HAVE | full set incl. stash, rebase, tags |
| Branch mgmt (create/checkout/delete/rename) | HAVE | — |
| Merge + conflict flow | PARTIAL | conflictedFiles + resolution flow; no in-editor conflict decorations/code-lens ("take current/incoming") |
| Blame in editor | HAVE | toggleable; state persisted (PERSIST-A) |
| Inline diff viewer | HAVE | DiffViewer pane + GitDiffAnalyzer inline (R6) |
| Stash mgmt | HAVE | list/apply/drop |
| Tag mgmt | HAVE | — |
| AI commit message (I4) | HAVE | pane-wired |
| SCM history graph view | PARTIAL | commit history list exists (no graph rendering) |
| Quick diff gutter (changed lines vs HEAD) | UNKNOWN | §6 — likely missing |
| Discard/revert per hunk | MISSING | file-level discard only? §6 |
| Provider-pluggable SCM (ISCMProvider) | MISSING | git hard-wired — fine for product scope |
| Git-as-timeline provider | MISSING | TimelinePanel local-only (B03 edge) |
| SCM history as chat context (scmHistoryChatContext) | PARTIAL | we attach diffs/dirty files to chat; history-as-context shape differs |
| Commit hover/details richness | PARTIAL | log entries w/ per-file filter |
| Multi-repo | MISSING | single-root by design (B03) |
| PR review (GH integration) | MISSING | sessionPullRequestPill parked (integration map) |
| Explorer git decorations | MISSING | B03 gap — modified/ignored badges |

## §5 Cross-subsystem connection edges

- SCM ↔ **R6/B13**: GitDiffAnalyzer is shared by chat review cards; scmHistoryChatContext edge → future "attach recent commits" picker row.
- blame/quickDiff ↔ **B01**: gutter infra shared; quickDiff absence = same family as missing folding-guides work.
- git-timeline ↔ **B03 TimelinePanel**: merging git log into timeline is a top-parity item (both batches point at it).
- conflict flow ↔ **B11 diagnostics**: conflict markers could appear in problems panel (unified "what needs me" surface).
- explorer decorations ↔ **B03**: git status badges in tree — most user-visible SCM gap.

## §6 Open questions / on-device verification

1. QuickDiff gutter: does CodeEditor show changed-vs-HEAD line indicators when a file is modified? (grep found none — likely MISSING)
2. Discard: is discard file-level or hunk-level in SourceControlPane?
3. Conflict resolution: what does the flow look like on-device — marker-based text editor or structured picker? (for parity doc precision)
4. Blame fetch latency on large files (it ran in dead split branch pre-#2722 — now live; on-device timing?)
5. Does the AI commit-message button (I4) include staged-diff context (structured) or transcript tail?

## Status

**DONE** — 2026-09-14. Next: B08 Debug & DAP.
