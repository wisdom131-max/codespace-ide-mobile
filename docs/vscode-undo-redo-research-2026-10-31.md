# VS Code Undo/Redo — researched before touching VN Code's stack

Date: 2026-10-31. Item 19 (persistent undo history).

## What was actually read

| Source | What it is | Licence |
|---|---|---|
| `microsoft/vscode` → `src/vs/platform/undoRedo/common/undoRedo.ts` (raw file on `main`, fetched this session) | The undo/redo service interface and its element model | MIT (header in the file: "Copyright (c) Microsoft Corporation… Licensed under the MIT License") |
| `https://code.visualstudio.com/docs/agents/reference/workspace-context` | Workspace context / semantic search docs (read for the earlier memory research) | Docs, not code |
| VS Code docs navigation (`/docs/copilot/reference/copilot-vscode-features`) | Confirms sections named Sessions, Memory, Session History exist | Docs |

**Not read:** the editor's text-model undo stack (`textModel.ts` and the editor undo/redo implementation). Those files were not fetched in this session, so nothing below claims to describe them. Anything not traceable to the file above is marked *inference*.

## Verified findings (from `undoRedo.ts`)

1. **The unit of undo is an element with callbacks.** `IResourceUndoRedoElement { resource: URI; label; code; confirmBeforeUndo?; undo(): Promise|void; redo(): Promise|void }`. The "history" is a list of closures, not data.
2. **Stacks are per resource.** Every element names its `resource: URI`; the service exposes `canUndo(resource | source)`, `undo(resource)`, `getElements(resource)`.
3. **Multi-resource operations are first-class.** `IWorkspaceUndoRedoElement { resources: readonly URI[]; … split?(): IResourceUndoRedoElement[] }` — one operation spanning files, optionally decomposable into per-file elements.
4. **A new element destroys redo.** `pushElement()` is documented: "Add a new element to the `undo` stack. This will destroy the `redo` stack."
5. **Grouping and ordering are explicit.** `UndoRedoGroup` / `UndoRedoSource` carry an id and a monotonically increasing `order`, so elements pushed as one user action can be ordered relative to each other.
6. **Elements can be invalidated and removed.** `setElementsValidFlag(resource, isValid, filter)` and `removeElements(resource)` exist precisely because a file can be closed, deleted or changed underneath the stack.
7. **Snapshots and shared stacks exist.** `createSnapshot`/`restoreSnapshot` capture and restore a resource's element ids; `registerUriComparisonKeyComputer` lets several URIs share one stack.
8. **Some undos need confirmation and preparation.** `confirmBeforeUndo` and `prepareUndoRedo()` (called before `undo()`/`redo()` "to prepare everything such that the calls… are synchronous").
9. **Nothing here persists.** The interface has no serialisation, no storage, no "reload the stack" call. Because elements are closures over live objects, **this source does not establish that VS Code's undo history survives an application restart**, and it provides no mechanism that would make that possible without a different design. I therefore make no claim that it does.

## VS Code → VN Code comparison

| Area | VS Code (verified) | VN Code (as it is) | Gap | Recommended adaptation |
|---|---|---|---|---|
| Unit of undo | Element with undo/redo callbacks | `AgentWriteJournal.ChangeSet` = list of `Entry(path, before, after)`; editor has `SnapshotUndoManager` per file | none conceptually | keep the data form: it is *serialisable*, which the closure form is not |
| Stack ownership | Per resource URI | Journal sets carry `sessionId`; editor stack is per canonical file path | VS Code keys by file, VN Code keys by session for agent writes | keep both: file-level for text edits, session-level for agent operations |
| Grouping | `UndoRedoGroup` + `order` | `beginOperation()` opens one set per tool round | equivalent | unchanged |
| Multi-file operation | `IWorkspaceUndoRedoElement.resources[]` + `split()` | one `ChangeSet` with many entries | no `split()` equivalent | not needed until the UI wants per-file undo of one agent operation |
| Redo invalidation on new work | `pushElement` destroys redo | a new `record()` creates/uses a new unsettled set; `reviewable()` only offers unsettled sets | equivalent in effect | unchanged; tests pin it |
| Closed/changed target | `setElementsValidFlag`, `removeElements` | `undoEntry` re-reads the file and refuses unless it still equals `after`; missing file → "no longer there" | VN Code is stronger: validity is *checked*, not flagged | keep the check; it is what makes disk persistence safe |
| Confirmation before undo | `confirmBeforeUndo` | nothing | minor | not needed now; the review row already shows the diff |
| Lifecycle | Elements dropped with the resource | `forgetSession()` drops a deleted session's sets (now also its file) | none | unchanged |
| Persistence across restart | **not established, no mechanism in this source** | new: `JournalStore` writes each session's sets to disk and `activate()` reloads them | VN Code goes further than the verified VS Code design | keep, because the requirement is explicitly "survives restart", and because the before/after images make it verifiable |

## Licensing

`undoRedo.ts` is MIT, so studying it and reusing its *ideas* is fine. No code was copied: VN Code's journal predates this research and differs in representation (data vs closures). If any VS Code code is ever lifted, its MIT notice must be preserved in the file.

## What this means for persistence (the design conclusion)

The reason VS Code's stack cannot be persisted is the same reason a naive "save the undo stack" fails here: a closure cannot be written to disk. VN Code's journal happens to store *content images* instead, so persistence is possible without inventing anything — the set is reloaded, and its safety is re-established at the moment of undo by comparing the file on disk with `after`. That is why the implementation reuses the existing undo path rather than adding a second one.
