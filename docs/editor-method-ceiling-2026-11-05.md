# The editor's 64KB method ceiling, measured

Date: 2026-11-05. Applies to `com.codespace.ide.editor.CodeEditorKt.CodeEditor`.

## Why this file exists

CI 3552 failed with `Method too large: CodeEditorKt.CodeEditor(...)`. The fix at the time moved
new work out of the body, and the summary that followed implied there was headroom afterwards.
There was not. Nothing measured the method, so nobody could tell.

## The measurement

Taken from the `prodDebug` classes, with `tools/check_method_size.py` (which parses each class
file's `Code` attribute and reads the real `code_length`):

| Point | Bytes | Share of 65,535 |
|---|---|---|
| `ed44bf0` (before this fix) | 65,453 | **99.9%** |
| after wrapping the overlay stack | 41,999 | **64.1%** |

Eighty-two bytes separated the editor from a build failure at `ed44bf0`.

## What was extracted, and why that shape

The overlay stack (inlay hints, diagnostic tooltip, blame, merge conflicts, block lines, search
matches, extra cursors, debug arrow, target-line highlight, LSP highlights, colour swatches, error
lens, code lens, inlay hints, document links, peek results, rename, the goto/find bars, lightbulb,
signature help, ghost text, hover, snippet choices, completion popup) now lives in a composable
lambda inside `CodeEditor`.

Emission code is what fills a Compose method: group bookkeeping plus argument evaluation at every
call site, roughly 88 instructions per site across the 273 sites the method contained. A composable
lambda moves all of that into its own class and method, and Kotlin captures the locals it reads
automatically - no parameter list to thread, no call site to rewrite, no change to the tree, the
order of emission or the state objects. That is why the change is behaviour-neutral.

## The guard

`tools/check_method_size.py` runs in `.github/workflows/android-build.yml` ("Build Android APK")
as the step "Method size guard (JVM 65,535-byte limit, 90% threshold)". It prints the five largest
methods and fails the build above 58,981 bytes. Current top five:

    39163 bytes   59.8%  com/codespace/ide/ui/panes/ExplorerPaneKt.class  ExplorerSidePanel-EjSl8yc
    37262 bytes   56.9%  com/codespace/ide/ui/panes/TerminalPaneKt.class  TerminalPane
    35758 bytes   54.6%  com/codespace/ide/ui/panes/EditorPaneKt.class  EditorPane
    34729 bytes   53.0%  com/codespace/ide/ui/screens/ProjectShellScreenKt.class  ProjectShellScreen
OK: largest method is 64.1% of the limit

## Rule for the next change

Start from the measurement, not from a feeling. Run the script after any change to the editor, and
if `CodeEditor` has crept up, extract before adding. The 90% gate is a tripwire, not a target: a
method at 85% is already unmaintainable and one inline addition can end it.
