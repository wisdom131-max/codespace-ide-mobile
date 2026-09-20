# VSCODE-SOURCE-COMPARISON — build #2854 bug audit vs upstream (2026-09-20)

Legend: [READ] = verified against source in this workspace. Upstream clones:
- microsoft/vscode @ 832cf23c5887351668f61c8648eb9c2ec6ee7d23
- microsoft/vscode-copilot-chat @ 5863f5a7088958050792b5dccbe8b46c6e13eccc

## (a) Diagnostics survive tab close/reopen — MARKER SERVICE IS RESOURCE-KEYED

VS Code:
- `src/vs/platform/markers/common/markerService.ts` — `MarkerService._byResource = new ResourceMap<...>` (:27-34). Markers are stored per RESOURCE URI, independent of any editor/viewer lifetime. Cleared only by owner via `deleteByResource`.
- `src/vs/editor/common/services/markerDecorationsService.ts` — `MarkerDecorationsService._markerDecorations = new ResourceMap<MarkerDecorations>` (:31); constructor hooks `modelService.onModelAdded/onModelRemoved` + `markerService.onMarkerChanged` (:41-46). `MarkerDecorations.update(markers)` (:145+) converts markers into model decorations via `model.deltaDecorations`.
- Consequence READ: when a file is reopened, `onModelAdded` fires, and decorations are rebuilt FROM THE STILL-STORED MARKERS. Squiggles return with no server re-push, and they can never appear in another file's editor because the lookup key is the resource.

Ours (CodeSpace):
- Squiggles live in EditorPane `lspSquiggles` remember{} keyed by nothing, merged by CodeEditor into whatever file is active. Cross-tab leakage (A-6) and loss on reopen are both direct consequences of NOT keying by canonical path.
- Fix direction = PLAN A per-file store, exactly the markerService pattern: state keyed by canonical path; per-tab CodeEditor reads only its own bucket.

## (b) Gold band = decoration on the TARGET MODEL, 350ms, selection-based jump

VS Code `src/vs/editor/contrib/gotoSymbol/browser/goToCommands.ts`, `_openReference` (:201-238) [READ]:
- Jump = `editorService.openCodeEditor({ resource, options: { selection: collapsedRange, selectionRevealType: NearTopIfOutsideViewport, selectionSource: JUMP } })`. The durable artifact is the SELECTION (part of per-file view state), not a highlight.
- Highlight = `targetEditor.createDecorationsCollection([{ range, options: { description: 'symbol-navigate-action-highlight', className: 'symbolHighlight' } }])` then `setTimeout(..., 350)` with a model-identity guard (`if (targetEditor.getModel() === modelNow)`). Decorations live ON THE MODEL: impossible to render in a different file; impossible to leak via shared UI state.
- Range preference: `targetSelectionRange` first, `reference.range` fallback — the symbol's own range, which is the A-9 line-precision question upstream answer.
- There is NO long-lived band in VS Code. Our 5s/6s band on a shared `scrollToLine` int is a CodeSpace invention with no upstream analog.

## (c) Per-file view state — keyed by normalized resource string

VS Code [READ]:
- `src/vs/workbench/browser/parts/editor/editorWithViewState.ts` — `AbstractEditorWithViewState` holds an `IEditorMemento`; saves on close (`onWillCloseEditor`), keeps mementos for N editors (limit 100 for text editors, :46).
- `src/vs/workbench/browser/parts/editor/editorPane.ts` — `EditorMemento.saveEditorState` (:245-268): cache keyed by `resource.toString()` (the model URI — already normalized through URI.file and the resolver), nested per group id, plus an optional SHARED slot (`workbench.editor.sharedState`).
- Same principle as our PERSIST maps (path-string key), but the upstream key is a canonical resource URI. Our raw-path-string keys break when one file has two spellings (/sdcard vs /storage/emulated/0, proot prefixes) — the A-14 path-mismatch class.

## (d) Disk change while open; restore; conflict

VS Code [READ]:
- `src/vs/workbench/services/textfile/common/textFileEditorModel.ts` — ONE model per resource. External change = resolve with `reload` / `forceReadFromFile: true` (:677 `forceResolveFromFile`) → `doUpdateTextModel(content.value, EditSources.reloadFromDisk())` (:543) → every view updates because views observe the model.
- CONFLICT: if the file changed on disk while the model is dirty, the model enters CONFLICT state; `save()` (:744-753) REFUSES autosave/FOCUS_CHANGE/WINDOW_CHANGE saves while in CONFLICT — only explicit manual save proceeds. This is the exact guard we lack.
- Local History `src/vs/workbench/contrib/localHistory/browser/localHistoryCommands.ts`, `restore()` (:250-300): (1) confirm dialog, (2) soft-revert all dirty working copies of the resource, (3) `fileService.cloneFile(entry.location, entry.workingCopy.resource)` — restore goes THROUGH THE FILE SERVICE so watchers fire, (4) `workingCopy.revert({ force: true })` for every working copy — open editors refresh immediately. Also registers `restoreSaveSource` so the restore itself is undoable.
- Local History storage: service-side, entries fetched by resource (`workingCopyHistoryService.getEntries(resource, token)` in localHistoryTimeline.ts :136). NO directory-walk root discovery exists upstream — B-α has no VS Code analog; it is a CodeSpace-specific invention to drop.

Ours:
- Bug B-β: we copy the file but never force-refresh open buffers (appliedTick observer only refreshes `lastAppliedPaths`), and `onContentChange` writes every keystroke to disk, so the first edit in a stale tab overwrites the restored file. Both missing pieces are upstream steps (2)/(4) + the conflict-save refusal.
- Bug B-α: Local History dialog walk-up finds wrong root for nested non-git files (`f.parentFile` fallback) — delete the walk-up entirely in favor of one canonical resolver.

## (e) Terminal links: whole-line parse with line/col SUFFIX GRAMMAR

VS Code [READ]:
- `src/vs/workbench/contrib/terminalContrib/links/browser/terminalLinkParsing.ts`, `generateLinkSuffixRegex` (:44-134): one regex with 3 clause families. Clause 2 explicitly covers Python's format: `"foo", line 339`, `"foo": line 339`, `"foo" on line 339`, `"foo", line 339, character 12`, ranges `lines 339-341` — quotes optional (`['"]?`), `lines?` plural. Clause 1 covers `foo:339`, `foo 339`, `"foo",339`. Clause 3 covers `foo(339, 12)`.
- `detectLinks(line)` (:196-201): parse suffixes FIRST, then everything before the suffix is the path candidate; `linkWithSuffixPathCharacters` regex (:213) for the path body. Spaces inside suffix phrases also accept non-breaking space (:131-133).
- `terminalLocalLinkDetector.ts` `detect()` (:95-160): candidates resolved against cwd (CommandDetection capability) or initial cwd; each candidate VALIDATED against the file system; trailing `[\[\]"'\.]` chars trimmed iteratively with a trim-range map (:140-157) so the underline stays correct. MaxResolvedLinkLength cap.
- KEY: the tap does NOT extract a word. Links are pre-parsed per buffer line; the tap only selects within pre-computed ranges.

Ours:
- A-5: `getWordAtLocation` whitespace-delimited tap extraction → spaced paths impossible; `File "…"` quote never trimmed; no line/col suffix grammar. Porting the suffix-regex approach (clause 1+2 at minimum) replaces the word-tap entirely.

## (f) Agent tools: per-model capability flags gate agent mode; loop rounds

VS Code [READ]:
- `src/vs/workbench/contrib/chat/common/languageModels.ts` — `ILanguageModelChatMetadata.capabilities` (:283-288): `{ vision?, toolCalling?, agentMode?, editTools? }`. `ILanguageModelChatMetadata.suitableForAgentMode` (:367-370): agent mode offered only when `agentMode !== false && capabilities.toolCalling === true`. Picker filters; VS Code does not send tools to a model that never declared toolCalling.
- vscode-copilot-chat `src/extension/intents/node/toolCallingLoop.ts` — a loop class driving multi-round tool calls (`toolCallLimit` option :66, `ToolCallRound` accumulation, `validateToolMessagesCore` :1719 strips orphaned tool calls before re-send). `ensureAutopilotTools` (:432-446) re-appends `task_complete` if the user filtered it out.
- Implication for the missing-Mistral-tools report: upstream never hits "tools silently unavailable" for an undeclared model — the UI hides agent mode instead.

## (g) Stored model selection: pending-then-rebind, never a stale-ID request

VS Code [READ]:
- `src/vs/workbench/contrib/chat/browser/widget/input/chatInputModelSelectionController.ts`, `initialize(rememberedModelId)` (:169-212): resolution order = conversation's own pick → remembered id from storage → `chat.defaultModel` (new conversations only) → default/FirstAvailable.
- Crucial: if the remembered id is NOT in the live catalog, selection kind = 'pending': the DEFAULT model is displayed meanwhile, and `_restoreRememberedModel` claims the stored model AS SOON AS IT IS PUBLISHED. No request is ever sent with a stale id.
- `chatModelConfigurationStore.ts` (:80-130): config resolution = in-memory snapshot → scoped (location, sessionType) bucket → profile-global fallback → schema defaults; writes mirror to profile-global to avoid stuck stale values.
- This is the upstream pattern for our custom-endpoint stale-model-ID bug (MK plan part B): stored manual IDs must pend and re-bind on endpoint refetch, not 404.

## (h) Snippets: service registry + FS watch, no restart needed

VS Code [READ] `src/vs/workbench/contrib/snippets/browser/snippetsService.ts`:
- `_files = new ResourceMap<SnippetFile>()` (:212) — registry keyed by location URI, lazy `SnippetFile.load()`.
- Sources: extension contributions, user profile snippets, workspace `.vscode/snippets`.
- `_initWorkspaceFolderSnippets` (:431-451): if the folder doesn't exist yet, it WATCHES for the ADDED change event (`fileService.onDidFilesChange`, `FileChangeType.ADDED`) and initializes then; `watch()` helper (:121-124) wraps `fileService.watch` for user snippets dirs. `onDidChangeWorkspaceFolders`/`onDidChangeWorkbenchState` re-scan (:434-441).
- Trigger: completion-provider prefix match (SnippetController2), not a separate command. So "snippet packs" upstream = user/workspace snippet files + registry service, hot-reloaded.

## (i) files.exclude: defaults are few; everything else shows

VS Code [READ] `src/vs/workbench/contrib/files/browser/files.contribution.ts`:
- `files.exclude` default (:157): `{ '**/.git': true, '**/.svn': true, '**/.hg': true, '**/.jj': true, '**/.DS_Store': true, '**/Thumbs.db': true }` — glob-keyed, per-entry boolean enable/disable, optional `when` sibling condition (:176).
- .gitignore-based hiding is a SEPARATE opt-in setting (`explorer.excludeGitIgnore`, referenced in the description); `search.exclude` defaults cover `.git/objects/**` etc. (:302-306).
- Dotfiles other than that default list ARE shown in the Explorer by default. Our E-group finding (dotfile visibility config) should converge to this model: a glob list with per-entry toggles, not a UI tab flip.

## Cross-cutting conclusions

1. Every upstream subsystem that we audited keys per-file state by RESOURCE (markerService, markerDecorationsService, EditorMemento view states, workingCopyHistory, snippetsService). PLAN A is not just a fix for A-6 — it is the upstream shape for ALL per-file UI state (squiggles, bands, scroll, cursor, PERSIST maps).
2. VS Code has NO shared cross-file "scrollToLine"-style navigation int. Navigation = open editor with selection options; transient feedback = decoration on the target model with a 350ms timer.
3. Restore correctness comes from going THROUGH the file service + forcing working-copy reverts, not from copying bytes and hoping views notice.
4. Never send a request built from stale IDs: pending-then-rebind (models), capability gating (tools).
