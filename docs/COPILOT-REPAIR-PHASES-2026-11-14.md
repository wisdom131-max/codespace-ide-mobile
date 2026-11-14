# Copilot repair programme — phase record

Owner-approved 2026-11-14. One row per phase: what shipped, the commit, the CI run, what it
does not cover. A phase is only recorded here once CI has returned a verdict for its commit.

| Phase | Scope | Commit | CI | Known gaps |
|---|---|---|---|---|
| 1 | Git approval checkpoints: three separate approvals, explicit paths, protected-branch second confirmation, decline keeps the commit | `b537e61` | GREEN 43632852707 | Approval cards are the existing question surface, not a dedicated count/target card; the old `message`-only call still works but stages tracked modified files rather than everything |
| 2 | Runtime ownership and lifecycle | — | — | not started |
| 3 | Transcript storage ownership | — | — | not started |
| 4 | WritePolicyGate | — | — | not started |
| 5 | WorkspacePaths | — | — | not started |
| 6 | Memory router and local Skills | — | — | not started |
| 7 | Plus button and attachment handoff | — | — | not started |
| 8 | Remaining reliability and experience fixes | — | — | not started |

## Phase 1 detail

Files: `agent/GitSafety.kt` (new), `agent/AgentTools.kt`, `chat/panel/ToolRegistry.kt`,
`app/src/test/java/com/codespace/ide/agent/GitSafetyTest.kt` (new, 11 cases).

What changed in behaviour:

- `git_commit_push` takes an optional `paths` array. Named paths are staged exactly with
  `git add -- <paths>`; the tool description says it never stages the whole tree.
- A call that names no paths stages **tracked modified files only**, parsed from
  `git status --porcelain`. Untracked files are never swept in, and credential-looking names
  (`.env`, `id_rsa`, `*.jks`, `google-services.json`, `local.properties`) are refused with a reason.
- Wildcards, absolute paths and `..` traversal are refused by name.
- Three questions, in order: stage-and-commit (shows file count, first 12 paths, the real subject),
  push (shows `remote/branch` read back from `git remote -v`), and — only for `main`/`master` — a
  second confirmation that states it cannot be undone.
- Declining the push returns "Pushed nothing. The commit is kept locally, ready to push to
  origin/main." Nothing is reset, amended or force-pushed.
- No remote configured is reported as a local commit, not a failure.

Not verified by this phase: a real device run of the three approval cards, and a disposable-repo
integration test (the unit tests cover the rules, not git itself).
