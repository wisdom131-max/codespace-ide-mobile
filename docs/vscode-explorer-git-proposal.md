# VS Code Explorer/Git research and the VN Code editor-registry proposal (read-only deliverable)

Status: RESEARCH + ONE DESIGN PROPOSAL. No Explorer, editor, or Source Control code has
been changed for this; nothing below is implemented until the owner rules on it.

## A. What VS Code actually does (from microsoft/vscode source, cited)

1. **Every file type opens in its own editor.** Resolution goes through
   `src/vs/workbench/services/editor/common/editorResolverService.ts`. Viewers register with a
   `RegisteredEditorPriority` enum: `default | builtin | option | exclusive`
   (editorResolverService.ts:130-134). An `exclusive` match takes over resolution
   (`hasExclusiveMatch`, editorResolverService.ts:271).
2. **User associations override defaults.** The `workbench.editorAssociations` setting maps
   glob patterns to editor ids and has "precedence over the default behavior"
   (editorResolverService.ts:88; the docs example is `"*.hex": "hexEditor.hexedit"`).
3. **Binary files get a deliberate gate, never garbage.** `BinaryFileEditor` is "an
   implementation of editor for binary files that cannot be displayed"
   (src/vs/workbench/contrib/files/browser/editors/binaryFileEditor.ts:23-25), extending
   `BaseBinaryResourceEditor` whose placeholder offers an "Open Anyway" action
   (src/vs/workbench/browser/parts/editor/binaryEditor.ts:67). "Open With..." comes from
   `OpenWithEditorPicker` (imported at binaryFileEditor.ts:17).
4. **Large files refuse with a notice.** The text editor handles "a file that is too large to
   open without confirmation" and shows "The file is not displayed in the text editor because
   it is very large ({size})" (src/vs/workbench/contrib/files/browser/editors/textFileEditor.ts:195-201;
   the heap budget is the `files.maxMemoryForLargeFilesMB` setting).
5. **Extensions register custom editors per glob.**
   `vscode.window.registerCustomEditorProvider(viewType, provider, ...)` and the
   `CustomEditorProvider` interface (vscode.d.ts:11791 and :10570). Media preview
   (image/audio/video) is itself an extension (extensions/media-preview); Markdown preview is
   a registered editor that appears in the default association list
   (`vscode.markdown.preview.editor`, editorResolverService.ts:114).
6. **Explorer opens on a single click** through the tree's `onDidOpen` handler, which calls
   `editorService.openEditor` with pinned/preserveFocus options
   (src/vs/workbench/contrib/files/browser/views/explorerView.ts:600+). Preview mode (opening
   without pinning) is the `workbench.editor.enablePreview` default.
7. **Hex editor**: microsoft/vscode-hexeditor ships under the MIT License (LICENSE, copyright
   Microsoft Corporation). It registers as an option-priority editor for binary/hex globs and
   the user opts a file type to it via the association setting.
8. **SCM + commit message.** The git extension owns the commit-message lifecycle with
   `git.commitMessageAccept` / `git.commitMessageDiscard` commands
   (extensions/git/package.json:461-467). The "Generate Commit Message" button on the SCM
   input box is contributed by Copilot on top of that host hook: the HOST provides the
   pipeline; the AI feature plugs into it.

## B. ONE design proposal for VN Code

**Core: an EditorViewerRegistry** — one resolver, every open goes through it.

- `ViewerDescriptor(id, label, priority: DEFAULT | BUILTIN | OPTION | EXCLUSIVE, supports(file): Boolean)`.
  Resolution: extension/content-sniff -> ordered candidate list (EXCLUSIVE first, then user
  association, then DEFAULT, then BUILTIN/OPTION) -> default viewer used on tap, rest offered
  under "Open With...". Per-extension user association map stored as JSON (same spirit as
  `workbench.editorAssociations`).
- Viewers worth having (all but one exist today): code text editor (DEFAULT for text), hex
  viewer, image, audio/video, archive, PDF, SQLite, DEX/ELF inspectors, strings viewer, and a
  new cheap Markdown preview. Font/CSV-table/PDF-form viewers defer.

**Tap (single) per type, matching the owner's rules:**
- Directories expand (unchanged). Image/PDF/video/audio/SQLite/DEX/ELF/archive/APK open
  their existing previewer (ExplorerPane.kt:1315-1352 unchanged).
- Extension-declared binaries (.class/.o/.db...) open the hex viewer directly (the
  `isHexByExt` branch, ExplorerPane.kt:1305).
- Text sniff-clean files open the text editor on tap with NO dialog (owner rule).
- Content that sniffs as binary opens READ-ONLY with a thin banner "Binary content —
  read-only" offering Hex view / Enable editing — because saving a garbled decode would
  corrupt the file (VS Code's BinaryFileEditor gate, mapped to our already-shipped dialog).
- Above a size threshold (proposed 2 MB): truncated read-only preview with an explicit
  "showing first N KB" notice (textFileEditor.ts pattern). No open path may decode the whole
  file first.
- Long-press NEVER auto-opens anything (unchanged); its menu gains an "Open With..." sub-list
  fed by the registry.
- No auto-opening from the long-press menu: unchanged.

**Guards so nothing can freeze the editor:** the content sniff stays bounded
(SNIFF_WINDOW_BYTES = 8192, AttachmentTypeSniffer.kt:54), size checks run before ANY decode,
the hex viewer stays virtualized, and the text editor refuses (read-only truncated mode)
above the threshold instead of attempting a full load.

**Extension path (future, XG-track):** the registry is the same seam
`registerCustomEditorProvider` covers in VS Code — a manifest-contributed viewer registers a
descriptor with a glob; activation stays out of scope until XG01-04 rulings.

**Smallest scope, phased:**
- P1: registry interface + migrate the existing tap routing behind it, BEHAVIOR-IDENTICAL
  (no user-visible change), plus unify the residual: the long-press menu still uses the
  sniff-inclusive `isHexBin` flag (ExplorerPane.kt:1656) while tap uses `isHexByExt` (1305) —
  P1 makes both consult the registry's shared verdict.
- P2: "Open With..." in the long-press menu + the per-extension user association map.
- P3: binary read-only banner + "Enable editing" (edit session explicit).
- P4: Markdown preview viewer.
- P5: large-file truncated read-only notice.

**Regression tests:** registry resolution unit tests (the A1 fixture set becomes the
contract: Plain.java -> text editor, no dialog; Fake.java zip-magic -> gate dialog; Binary.java
NUL-past-2KB -> gate dialog, proving the 8KB sniff window; Utf16.java BOM -> readable text),
long-press menu assertions per fixture, size-threshold tests, and the existing Explorer tap
suite kept green.

**Git/SCM (findings folded in, no code now):** SourceControlPane already covers commit,
history, stash, branch ops. The VS Code pattern worth adopting when the new chat ships: the
SCM pane owns the commit-message lifecycle (accept/discard like git.commitMessageAccept /
git.commitMessageDiscard), and an "AI: generate" action is a thin plug-in on top — no chat
dependency inside SCM.
