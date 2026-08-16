# codespace-ide-mobile — Full Feature Test Guide (130 tests)

For each test that needs a file, copy the exact block below instead of typing it — typing risks corrupting the content mid-test (this happened before). Paste the block as-is, run the steps, then delete the test file afterward if noted.

---

## Test 1 — App launches without crashing

**File:** none needed.

**Steps:**

1. Open the app from your home screen.
2. Watch the screen as the app loads.

**Expected:** The app opens and shows either the home screen with project list or the last open project. If a crash dialog appears saying the app keeps stopping, that is a bug. If a safe mode dialog appears offering Continue or Enter Safe Mode, that means the app detected 3 or more previous crashes.

**Cleanup:** No

---

## Test 2 — Safe mode on repeated crashes

**File:** none needed.

**Steps:**

1. Force-close the app 3 times in a row from Recent Apps.
2. On the 4th launch, watch for a dialog.

**Expected:** A dialog should appear with options Continue or Enter Safe Mode. If the app just crashes again with no dialog, that is a bug. If the dialog appears, tap Continue to proceed normally.

**Cleanup:** No

---

## Test 3 — Terminal native PTY shell works

**File:** none needed.

**Steps:**

1. Open any project.
2. Tap the Terminal tab at the bottom.
3. Wait 3 seconds for the shell to start.
4. Type echo hello and press Enter.

**Expected:** The text hello appears on the next line. If you see a blank screen with no prompt, wait longer. If you see an error about permission denied or process failed, that is a bug. The prompt should show something like root or ~.

**Cleanup:** No

---

## Test 4 — Terminal multi-tab

**File:** none needed.

**Steps:**

1. In the Terminal tab, tap the plus icon at the top of the terminal to create a second tab.
2. Type pwd in the first tab.
3. Switch to the second tab by tapping its number.
4. Type whoami.

**Expected:** Each tab has its own independent session. The first tab shows a directory path, the second shows root or a username. If both tabs show the same output or the second tab is blank and unresponsive, that is a bug.

**Cleanup:** No

---

## Test 5 — Terminal session restore after restart

**File:** none needed.

**Steps:**

1. Open a project, go to Terminal, create 2 tabs, type some commands in each.
2. Force-close the app completely.
3. Reopen the app and open the same project.

**Expected:** After about 8 seconds, the terminal tabs should reappear with their names preserved. If the terminal shows zero tabs or crashes on restore, that is a bug.

**Cleanup:** No

---

## Test 6 — Terminal key bar swipe

**File:** none needed.

**Steps:**

1. In the Terminal tab, look at the row of extra keys above the keyboard (Tab, ESC, CTRL, arrows, pipe).
2. Swipe left on that row.

**Expected:** More keys appear from the right side. If swiping does nothing or the row is empty, that is a bug. You should see keys like CTRL, ALT, TAB, arrow keys, and pipe character.

**Cleanup:** No

---

## Test 7 — Terminal color scheme picker

**File:** none needed.

**Steps:**

1. In the Terminal tab, tap the three-dot overflow menu at the top.
2. Tap Color Scheme.
3. Select Dracula from the list.

**Expected:** The terminal background and text colors change immediately to the Dracula theme (dark purple background). If the colors do not change or the list does not appear, that is a bug.

**Cleanup:** No

---

## Test 8 — File creation without permission error

**File:** none needed.

**Steps:**

1. In the file explorer tree on the left, long-press the project folder name.
2. Tap New File in the context menu.
3. Type test_file.py and tap OK.

**Expected:** The file appears in the tree immediately. If you see an error message saying operation not permitted or permission denied, that is a bug.

**Cleanup:** No

---

## Test 9 — Large file handling

**File:** none needed.

**Steps:**

1. Open the Terminal tab.
2. Run this command:.

**Expected:** The file opens and scrolls smoothly without lag or crash. If the app freezes, crashes, or takes more than 3 seconds to open, that is a bug.

**Cleanup:** No

---

## Test 10 — Syntax highlighting

**File setup:** Create a file named test_syntax.kt and paste this content:

```
fun main() {
    val name = "World"
    println("Hello, $name!")
    if (name.length > 3) {
        println("Long name")
    }
}
```

**Steps:**

1. Tap the file to open it in the editor.
2. Look at the colors of the text.

**Expected:** Keywords like fun, val, if should be one color (usually blue or orange). String literals in quotes should be another color (usually green or yellow). If everything is the same white color with no highlighting, that is a bug.

**Cleanup:** No

---

## Test 11 — Language auto-detection

**File setup:** Create a file named test_auto.js and paste this content:

```
const x = 42;
function add(a, b) { return a + b; }
```

**Steps:**

1. Open the file in the editor.

**Expected:** The syntax highlighting should apply JavaScript colors (const and function as keywords, numbers highlighted). If the file shows Kotlin or Python highlighting, that is a bug.

**Cleanup:** No

---

## Test 12 — Line numbers gutter

**File:** none needed.

**Steps:**

1. Open any file in the editor.
2. Look at the left side of the editor area.

**Expected:** Line numbers should be visible on the left side (1, 2, 3, etc.). If no numbers are visible, that is a bug. Tapping a line number should toggle a red breakpoint dot.

**Cleanup:** No

---

## Test 13 — Font size adjustment

**File:** none needed.

**Steps:**

1. Open any file.
2. Tap the three-dot overflow menu.
3. Look for a font size control or slider.
4. Change the font size to a larger value.

**Expected:** The editor text size changes visibly. If the text stays the same size, that is a bug.

**Cleanup:** No

---

## Test 14 — Bracket auto-close

**File setup:** Create a file named test_brackets.py and paste nothing (empty file)

**Steps:**

1. Open the file.
2. Type an opening parenthesis ( on a blank line.
3. Then type an opening brace {. Then type an opening bracket [.

**Expected:** Each opening bracket should automatically insert its closing counterpart ( ) after (, } after {, ] after [). If closing brackets do not appear, that is a bug.

**Cleanup:** Yes — delete test_brackets.py after testing

---

## Test 15 — Snippet Tab expansion (Kotlin)

**File setup:** Create a file named test_snippet.kt and paste nothing (empty file)

**Steps:**

1. Open the file.
2. Type the word fun and press the Tab key (not the autocomplete popup — use the Tab key on the key bar).

**Expected:** The text fun expands into a function template like fun name() { } or fun name(): Return Type { }. If nothing happens or the word fun stays as is, that is a bug. If the app crashes, that is a critical bug.

**Cleanup:** Yes — delete test_snippet.kt after testing

---

## Test 16 — Snippet Tab expansion (Python)

**File setup:** Create a file named test_snippet.py and paste nothing (empty file)

**Steps:**

1. Open the file.
2. Type the word def and press the Tab key.

**Expected:** The text def expands into def name(): or a function definition template. If nothing happens, that is a bug.

**Cleanup:** Yes — delete test_snippet.py after testing

---

## Test 17 — Find and Replace in editor

**File setup:** Create a file named test_find.py and paste this content:

```
value = 10
value = 20
value = 30
```

**Steps:**

1. Open the file.
2. Tap the three-dot overflow menu.
3. Tap Edit then Find.
4. In the Find bar that appears at the top, type value.
5. Tap the replace icon or toggle replace mode.
6. Type number in the replace field.
7. Tap Replace All.

**Expected:** All three instances of value change to number. The file should now read number = 10, number = 20, number = 30. If the Find bar text is invisible or the replace does not work, that is a bug.

**Cleanup:** Yes — delete test_find.py after testing

---

## Test 18 — Go to Line

**File setup:** Create a file named test_gotoline.py and paste this content:

```
line1
line2
line3
line4
line5
line6
line7
line8
line9
line10
```

**Steps:**

1. Open the file.
2. Tap the three-dot overflow menu.
3. Tap Go then Go to Line.
4. Type 7 in the dialog.
5. Tap OK.

**Expected:** The cursor moves to line 7. If the cursor goes to a different line or the dialog does not appear, that is a bug. If you type 999 and it goes to line 10 (the last line) instead of crashing, that is correct behavior (auto-clamping).

**Cleanup:** Yes — delete test_gotoline.py after testing

---

## Test 19 — Multi-cursor editing

**File setup:** Create a file named test_multi.py and paste this content:

```
apple = 1
apple = 2
apple = 3
```

**Steps:**

1. Open the file.
2. Long-press the word apple on line 1.
3. Tap Select Next Occurrence from the context menu.
4. Tap it again.
5. Tap it a third time.

**Expected:** Each tap adds a new cursor at the next occurrence of apple. You should end up with 3 cursors, one on each line. Type x and all three lines should change to xpple simultaneously. If only one cursor responds, that is a bug.

**Cleanup:** Yes — delete test_multi.py after testing

---

## Test 20 — Select All Occurrences

**File setup:** Create a file named test_allocc.py and paste this content:

```
test_var = 1
test_var = 2
test_var = 3
```

**Steps:**

1. Open the file.
2. Long-press test_var on line 1.
3. Tap Select All Occurrences from the context menu.

**Expected:** All three instances of test_var get cursors at once. If only one or two get cursors, that is a bug.

**Cleanup:** Yes — delete test_allocc.py after testing

---

## Test 21 — Sticky scroll

**File setup:** Create a file named test_sticky.py and paste this content on many lines:

```
def function_one():
    pass
def function_two():
    pass
def function_three():
    pass
def function_four():
    pass
def function_five():
    pass
def function_six():
    pass
def function_seven():
    pass
def function_eight():
    pass
```

**Steps:**

1. Open the file.
2. Scroll down past function_four.

**Expected:** The nearest function header (def function_four or def function_five) should pin at the top of the editor while you scroll, acting like a sticky header. If no header pins or the header is wrong, that is a bug.

**Cleanup:** Yes — delete test_sticky.py after testing

---

## Test 22 — Ghost text inline suggestions

**File setup:** Create a file named test_ghost.py and paste nothing (empty file)

**Steps:**

1. Open the file.
2. Type the letter i.
3. Wait for the autocomplete popup to appear.

**Expected:** Above or in the popup, you should see grey ghost text suggesting a completion like import. If no ghost text appears, that may be normal if LSP is not running. If the popup appears with items, that confirms completions work.

**Cleanup:** Yes — delete test_ghost.py after testing

---

## Test 23 — Completion popup item count

**File setup:** Create a file named test_completion.py and paste nothing (empty file)

**Steps:**

1. Open the file.
2. Type import m on a blank line.
3. Wait for the popup.

**Expected:** The completion popup should show 18 or more items (like math, mmap, multiprocessing, msvcrt, etc.). If fewer than 18 items appear or the popup does not appear at all, that is a bug.

**Cleanup:** Yes — delete test_completion.py after testing

---

## Test 24 — Import completion does not clear import

**File setup:** Create a file named test_import.py and paste nothing (empty file)

**Steps:**

1. Open the file.
2. Type import o on a blank line.
3. Wait for the popup.
4. Tap the item os from the popup list.

**Expected:** The line should read import os. The word import should still be there. If import disappears and only os remains, that is a bug.

**Cleanup:** Yes — delete test_import.py after testing

---

## Test 25 — Hover docs

**File setup:** Create a file named test_hover.kt and paste this content:

```
val x = LaunchedEffect
```

**Steps:**

1. Open the file.
2. Long-press or tap and hold the word LaunchedEffect on line 1.

**Expected:** A small popup should appear with a description of LaunchedEffect. If no popup appears, hover docs may not be working. If a popup appears but the description is empty, that is a bug.

**Cleanup:** Yes — delete test_hover.kt after testing

---

## Test 26 — Code folding

**File setup:** Create a file named test_fold.py and paste this content:

```
def outer():
    x = 1
    y = 2
    z = 3
    return x + y + z
```

**Steps:**

1. Open the file.
2. Look at the gutter on the left for small arrow icons (chevrons) next to the def line.

**Expected:** A downward-pointing arrow should appear next to line 1. Tapping it should fold the function body, showing a dot dot dot placeholder. If no arrows appear, that is a bug. If tapping the arrow does nothing, that is a bug.

**Cleanup:** Yes — delete test_fold.py after testing

---

## Test 27 — Minimap

**File setup:** Create a file named test_minimap.py with 50 lines of text

**Steps:**

1. Open the file.
2. Look at the right side of the editor for a small overview panel showing a tiny version of the code.

**Expected:** A minimap should be visible on the right side showing a scaled-down view of the file. Tapping a position in the minimap should scroll the editor to that location. If no minimap appears, it may be toggled off — go to Project Settings and toggle Minimap on.

**Cleanup:** Yes — delete test_minimap.py after testing

---

## Test 28 — Split editor

**File:** none needed.

**Steps:**

1. Open any file.
2. Look for a split editor button in the editor toolbar (an icon that looks like two panels side by side).
3. Tap it.

**Expected:** The editor splits into two side-by-side panes, each showing the file. If no split button exists or tapping it does nothing, that is a bug.

**Cleanup:** No

---

## Test 29 — Editor feature toggles

**File:** none needed.

**Steps:**

1. Open any file.
2. Tap the three-dot overflow menu.
3. Tap View or Settings.
4. Find toggles for Minimap, Word Wrap, Ghost Text.
5. Toggle Minimap off.

**Expected:** The minimap disappears immediately. Toggle it back on and it reappears. If changes require an app restart to take effect, that is a bug.

**Cleanup:** No

---

## Test 30 — Cursor blink style

**File:** none needed.

**Steps:**

1. Open any file.
2. Go to Project Settings.
3. Find Cursor Blink Style.
4. Change it to Solid.

**Expected:** The cursor in the editor changes from blinking to a solid non-blinking line. If the cursor style does not change, that is a bug. Try other styles like Phase, Smooth, Expand.

**Cleanup:** No

---

## Test 31 — Cursor mode In-App vs System

**File:** none needed.

**Steps:**

1. Open any file.
2. Go to Project Settings.
3. Find Cursor Type or Cursor Mode.
4. Change from In-App to System.

**Expected:** The cursor changes from a custom thick overlay to a thin native system caret. If the cursor looks the same after switching, that is a bug.

**Cleanup:** No

---

## Test 32 — Lint checker inline markers

**File setup:** Create a file named test_lint.py and paste this content:

```
def foo():
    undefined_variable_here = 1
    return undefined_variable_here
```

**Steps:**

1. Open the file.
2. Look at line 2 for wavy underlines under the text.

**Expected:** The word undefined_variable_here should have a wavy underline (red or yellow). If no underlines appear, LSP or lint may not be running. If the app crashes when underlines appear, that is a critical bug.

**Cleanup:** Yes — delete test_lint.py after testing

---

## Test 33 — Problems panel

**File setup:** Create a file named test_problems.py and paste this content:

```
def foo():
    x = undefined_thing
    return x
```

**Steps:**

1. Open the file.
2. Tap the bottom panel and select the Problems tab (icon looks like a checklist or warning triangle).

**Expected:** The Problems panel should list errors from the file. If the panel is empty despite errors in the file, that is a bug. Tapping an error in the Problems panel should jump the editor cursor to the error line. If tapping does nothing, that is a bug.

**Cleanup:** Yes — delete test_problems.py after testing

---

## Test 34 — Lightbulb fix with AI

**File setup:** Create a file named test_lightbulb.py and paste this content:

```
def foo():
    x = undefined_var_here
    return x
```

**Steps:**

1. Open the file.
2. Wait for lint underlines to appear.
3. Look for a lightbulb icon in the gutter or near the error.
4. Tap the lightbulb.

**Expected:** A small menu should appear with an option like Fix with AI. Tapping it should open the AI Copilot chat panel with a fix prompt. If no lightbulb appears, that may mean lint is not running. If the lightbulb appears on the wrong line, that is a bug.

**Cleanup:** Yes — delete test_lightbulb.py after testing

---

## Test 35 — LSP Go to Definition (cross-file)

**File setup:** Create two files. First create utils.py with this content:

```
def helper_function():
    return 42
```

_Then create main.py with this content:_

```
from utils import helper_function
result = helper_function()
```

**Steps:**

1. Open main.py.
2. Long-press the word helper_function on line 2.
3. Tap Go to Definition from the context menu.

**Expected:** A popup or navigation should show that helper_function is defined in utils.py. Tapping the result should open utils.py at the definition line. If nothing happens or only the same file is searched, that is a bug.

**Cleanup:** Yes — delete utils.py and main.py after testing

---

## Test 36 — LSP Find References

**File setup:** Create a file named test_refs.py and paste this content:

```
def my_func():
    pass
my_func()
my_func()
```

**Steps:**

1. Open the file.
2. Long-press my_func on line 1.
3. Tap Find References from the context menu.

**Expected:** A list or bottom sheet should appear showing all locations where my_func is referenced (lines 2 and 3). If nothing appears or the list is empty, that is a bug.

**Cleanup:** Yes — delete test_refs.py after testing

---

## Test 37 — LSP Rename Symbol (cross-file)

**File setup:** Create a file named test_rename.py and paste this content:

```
def old_name():
    pass
old_name()
```

**Steps:**

1. Open the file.
2. Long-press old_name on line 1.
3. Tap Rename Symbol from the context menu.
4. Type new_name in the dialog.
5. Confirm.

**Expected:** All occurrences of old_name change to new_name throughout the file. If only the definition changes but the call on line 3 stays old_name, that is a bug.

**Cleanup:** Yes — delete test_rename.py after testing

---

## Test 38 — LSP Code Lens

**File setup:** Create a file named test_lens.kt and paste this content:

```
fun myFunction() {
    println("hello")
}
```

**Steps:**

1. Open the file.
2. Wait a few seconds for LSP to start.
3. Look at the end of line 1 for small teal-colored text.

**Expected:** A small annotation like 1 reference or 0 references should appear at the end of the function declaration line. If no annotations appear after waiting 10 seconds, LSP may not be running. That is not necessarily a bug if LSP is not installed.

**Cleanup:** Yes — delete test_lens.kt after testing

---

## Test 39 — LSP Inlay Hints

**File setup:** Create a file named test_inlay.py and paste this content:

```
def add(a, b):
    return a + b
result = add(1, 2)
```

**Steps:**

1. Open the file.
2. Wait for LSP to start.
3. Look at the function call on line 2 for small grey text.

**Expected:** Small grey hints should appear showing parameter names like a: and b: next to the arguments 1 and 2. If no hints appear after 10 seconds, LSP may not be running.

**Cleanup:** Yes — delete test_inlay.py after testing

---

## Test 40 — Document Symbol Outline

**File setup:** Create a file named test_outline.py and paste this content:

```
class MyClass:
    def method_one(self):
        pass
    def method_two(self):
        pass
def standalone_func():
    pass
```

**Steps:**

1. Open the file.
2. Tap the activity bar icon that looks like a tree or list (Outline view).
3. It should be on the left sidebar.

**Expected:** A tree should appear showing MyClass with method_one and method_two as children, and standalone_func as a separate item. Clicking a symbol should jump to that line in the editor. If the tree is empty, that is a bug.

**Cleanup:** Yes — delete test_outline.py after testing

---

## Test 41 — Type Definition peek

**File setup:** Create a file named test_type.py and paste this content:

```
x: int = 42
```

**Steps:**

1. Open the file.
2. Long-press the word int on line 1.
3. Look for Go to Type Definition in the context menu.
4. Tap it.

**Expected:** A peek overlay should appear showing the definition of int. If the menu item does not exist or nothing happens, that may mean LSP is not running. If the app crashes, that is a bug.

**Cleanup:** Yes — delete test_type.py after testing

---

## Test 42 — LSP code folding with LSP ranges

**File setup:** Create a file named test_lspfold.py and paste this content:

```
class MyClass:
    def __init__(self):
        self.value = 0
    def get_value(self):
        return self.value
    def set_value(self, v):
        self.value = v
```

**Steps:**

1. Open the file.
2. Wait for LSP.
3. Look for folding chevrons in the gutter.

**Expected:** Folding arrows should appear for the class and each method. Tapping a chevron should fold the block. If folding only uses indent-based detection and misses methods, LSP folding is not working. That is not critical but indicates LSP folding ranges are not being received.

**Cleanup:** Yes — delete test_lspfold.py after testing

---

## Test 43 — Master LSP toggle

**File:** none needed.

**Steps:**

1. Open Project Settings.
2. Find the toggle for Enable LSP Servers.
3. Turn it OFF.
4. Open a Python file and type some code.

**Expected:** No LSP squiggles, no hover popups, no completions from LSP. Only basic keyword completions should appear. Turn it back ON and LSP should resume. If turning off does not stop LSP behavior, that is a bug.

**Cleanup:** No

---

## Test 44 — LSP servers list

**File:** none needed.

**Steps:**

1. Open Project Settings.
2. Look for an LSP Servers section or list.

**Expected:** Approximately 21 language servers should be listed (TypeScript, Python, Kotlin, Go, Java, C/C++, Rust, PHP, JSON, HTML, CSS, YAML, etc.). If the list is empty or shows fewer than 10, that is a bug.

**Cleanup:** No

---

## Test 45 — Pyright LSP auto-install

**File:** none needed.

**Steps:**

1. Open Project Settings.
2. Find Pyright in the LSP section.
3. Open a Python file.

**Expected:** If pyright is not installed, the app should attempt to install it automatically. The Output tab should show installation logs. After installation, squiggles should appear in Python files. If installation fails silently, that is a bug.

**Cleanup:** No

---

## Test 46 — Format Document

**File setup:** Create a file named test_format.py and paste this content:

```
def  foo( ):
    x=1
    y    =2
    return x+y
```

**Steps:**

1. Open the file.
2. Look for a Format button in the editor toolbar (an icon with curly braces or a wand).
3. Tap it.

**Expected:** The code should be reformatted with proper spacing: def foo():, x = 1, y = 2, return x + y. If nothing changes or an error toast appears, that is a bug. If the format button does not exist, the feature may not be visible for this file type.

**Cleanup:** Yes — delete test_format.py after testing

---

## Test 47 — Git stage and unstage

**File:** none needed.

**Steps:**

1. Open a project that has a git repository.
2. Modify a file by adding a line.
3. Tap the Source Control icon in the activity bar (looks like a branch or circle graph).
4. Find the modified file in the Changes section.
5. Tap the plus icon next to it to stage.

**Expected:** The file moves from the Changes (unstaged) section to the Staged section. If the plus icon does nothing or an error appears, that is a bug. Tap the minus icon to unstage it back.

**Cleanup:** No

---

## Test 48 — Git commit

**File:** none needed.

**Steps:**

1. In the Source Control panel, stage a file.
2. Type a commit message in the text field at the top.
3. Tap the Commit button (checkmark icon).

**Expected:** A success message appears. If an error about git config or user.name appears, you need to set git config first. If the commit succeeds, the staged changes should clear.

**Cleanup:** No

---

## Test 49 — Git commit history

**File:** none needed.

**Steps:**

1. In the Source Control panel, tap the three-dot overflow menu.
2. Tap History.

**Expected:** A dialog should appear listing recent commits with their hash, author, date, and message. If the dialog is empty or does not appear, that is a bug.

**Cleanup:** No

---

## Test 50 — Git stash

**File:** none needed.

**Steps:**

1. In the Source Control panel, tap the three-dot overflow menu.
2. Tap Stash.
3. Type a message and confirm.

**Expected:** The current changes are stashed and the working directory reverts to the last commit. If an error appears, that is a bug. Tap the overflow menu again and tap Pop Stash to restore the changes.

**Cleanup:** No

---

## Test 51 — Git branch graph

**File:** none needed.

**Steps:**

1. In the Source Control panel, tap the three-dot overflow menu.
2. Look for Branch Graph or GRAPH tab.

**Expected:** A dialog should appear showing an ASCII-style branch graph with commit hashes and messages. If the graph is blank or does not appear, that is a bug.

**Cleanup:** No

---

## Test 52 — Git inline diff viewer

**File:** none needed.

**Steps:**

1. In the Source Control panel, tap a modified file name in the Changes section.

**Expected:** An inline diff should appear showing added lines in green, removed lines in red, and context lines in white. If no diff appears or the diff is blank, that is a bug.

**Cleanup:** No

---

## Test 53 — Git tags

**File:** none needed.

**Steps:**

1. In the Source Control panel, tap the three-dot overflow menu.
2. Tap Tags.

**Expected:** A dialog should appear listing existing tags with options to create or delete tags. If the dialog does not appear, that is a bug.

**Cleanup:** No

---

## Test 54 — Git .gitignore editor

**File:** none needed.

**Steps:**

1. In the Source Control panel, tap the three-dot overflow menu.
2. Look for .gitignore Editor.

**Expected:** A dialog should appear showing the contents of .gitignore with the ability to edit it. If the dialog does not appear, that is a bug.

**Cleanup:** No

---

## Test 55 — Git blame

**File:** none needed.

**Steps:**

1. Open a file in a git repository.
2. Look for a blame toggle in the editor toolbar or overflow menu.
3. Enable it.

**Expected:** Blame annotations should appear in the gutter showing author and commit info for each line. If no blame info appears, that is a bug. The line numbers should be correct (1-based, not 0-based).

**Cleanup:** No

---

## Test 56 — Source Control no dubious ownership

**File:** none needed.

**Steps:**

1. Open a project and go to the Source Control panel.
2. Perform a git operation (stage, commit, or status).

**Expected:** No error message saying fatal: detected dubious ownership should appear. If this error appears, that is a bug.

**Cleanup:** No

---

## Test 57 — GitHub sign-in (device code flow)

**File:** none needed.

**Steps:**

1. In the Source Control panel, if not signed in, look for a Sign in with GitHub button.
2. Tap it.

**Expected:** A dialog should appear with a device code in monospace text and a URL like github.com/login/device. If the dialog does not appear, the feature may not be wired. If it appears, you can cancel without completing the flow.

**Cleanup:** No

---

## Test 58 — Image file preview

**File:** none needed.

**Steps:**

1. Put any image file (PNG, JPG, or WebP) in your project folder.
2. Tap the image file in the file explorer.

**Expected:** An image preview popup should appear showing the image. If the file opens as text with garbage characters, that is a bug.

**Cleanup:** No

---

## Test 59 — PDF viewer

**File:** none needed.

**Steps:**

1. Put a PDF file in your project folder.
2. Tap the PDF file in the file explorer.

**Expected:** The PDF should open in a viewer with page navigation (Prev and Next buttons) and pinch-to-zoom. If the PDF does not open or the viewer is blank, that is a bug.

**Cleanup:** No

---

## Test 60 — Archive viewer (ZIP)

**File:** none needed.

**Steps:**

1. Put a ZIP file in your project folder.
2. Tap the ZIP file in the file explorer.

**Expected:** An archive viewer should open showing the contents of the ZIP, allowing you to browse files inside. If the ZIP opens as binary text, that is a bug. Long-press the ZIP and tap Extract Here to extract it to a folder.

**Cleanup:** No

---

## Test 61 — Video player

**File:** none needed.

**Steps:**

1. Put a short video file (MP4) in your project folder.
2. Tap the video file in the file explorer.

**Expected:** A video player dialog should appear with play, pause, and seek controls. If the video does not play or no player appears, that is a bug.

**Cleanup:** No

---

## Test 62 — Audio player

**File:** none needed.

**Steps:**

1. Put an audio file (MP3 or WAV) in your project folder.
2. Tap the audio file in the file explorer.

**Expected:** An audio player dialog should appear with play, pause, and a seek bar. If no player appears, that is a bug.

**Cleanup:** No

---

## Test 63 — SQLite viewer

**File:** none needed.

**Steps:**

1. Put a .db or .sqlite file in your project folder.
2. Tap the database file in the file explorer.

**Expected:** A SQLite viewer should open showing a list of tables. Tapping a table should show the rows in a scrollable grid with SELECT * LIMIT 200. If the viewer does not appear or shows garbage, that is a bug.

**Cleanup:** No

---

## Test 64 — Hex viewer

**File:** none needed.

**Steps:**

1. Put any binary file (such as a .so or .dex file) in your project folder.
2. Tap the binary file in the file explorer.

**Expected:** A hex viewer should open showing hex values and ASCII characters in columns. If the file opens as plain text with garbage, that is a bug. The hex viewer should cap at 256KB.

**Cleanup:** No

---

## Test 65 — Long-press context menu

**File:** none needed.

**Steps:**

1. In the file explorer, long-press any file name.

**Expected:** A context menu should appear with options including Open, Rename, Copy, Cut, Paste, Duplicate, Delete, Copy Path, Share, Open in Terminal, New File Here, and New Folder Here. If the menu is missing options or does not appear, that is a bug.

**Cleanup:** No

---

## Test 66 — Compress to zip

**File:** none needed.

**Steps:**

1. In the file explorer, long-press any file or folder.
2. Tap Compress in the context menu.

**Expected:** A dialog should appear allowing you to name the ZIP file. After confirming, a ZIP file should be created in the same directory. If no ZIP is created, that is a bug.

**Cleanup:** No

---

## Test 67 — File permissions viewer

**File:** none needed.

**Steps:**

1. In the file explorer, long-press any file.
2. Tap Permissions in the context menu.

**Expected:** A dialog should appear showing read, write, and execute permissions with a toggle for the executable bit. If the dialog does not appear, that is a bug.

**Cleanup:** No

---

## Test 68 — Local version history

**File:** none needed.

**Steps:**

1. In the file explorer, long-press a file.
2. Tap Local History in the context menu.

**Expected:** A list of recent snapshots should appear with timestamps. If the list is empty and the file has been edited recently, wait 30 seconds and check again. If no Local History option exists, that is a bug.

**Cleanup:** No

---

## Test 69 — Trash and restore

**File:** none needed.

**Steps:**

1. In the file explorer, long-press a file and tap Delete.
2. The file should move to trash, not permanently delete.
3. Look for a Recycle Bin or Trash option in the explorer overflow menu.
4. Tap it.

**Expected:** A list of deleted files should appear. Find the file you deleted and tap Restore. The file should reappear in its original location. If the file is permanently deleted with no trash, that is a bug.

**Cleanup:** No

---

## Test 70 — HTML preview

**File setup:** Create a file named test_preview.html and paste this content:

```
<!DOCTYPE html>
<html><body><h1 style="color:blue;">Hello World</h1></body></html>
```

**Steps:**

1. Open the file.
2. Tap the Preview tab in the bottom panel.

**Expected:** The preview should show a blue Hello World heading rendered as HTML. If the preview shows raw HTML code instead of rendered HTML, that is a bug.

**Cleanup:** Yes — delete test_preview.html after testing

---

## Test 71 — Markdown preview

**File setup:** Create a file named test_md.md and paste this content:

```
# Heading 1
## Heading 2
**Bold text** and *italic text*
- List item 1
- List item 2
```

**Steps:**

1. Open the file.
2. Tap the Preview tab in the bottom panel.

**Expected:** The preview should show rendered markdown with a large Heading 1, smaller Heading 2, bold and italic text, and a bulleted list. If raw markdown text appears instead, that is a bug.

**Cleanup:** Yes — delete test_md.md after testing

---

## Test 72 — SVG preview

**File setup:** Create a file named test_svg.svg and paste this content:

```
<svg xmlns="http://www.w3.org/2000/svg" width="100" height="100"><circle cx="50" cy="50" r="40" fill="red"/></svg>
```

**Steps:**

1. Open the file.
2. Tap the Preview tab in the bottom panel.

**Expected:** The preview should show a red circle. If raw SVG code appears instead of the rendered circle, that is a bug.

**Cleanup:** Yes — delete test_svg.svg after testing

---

## Test 73 — Browser mode in preview

**File:** none needed.

**Steps:**

1. Tap the Preview tab in the bottom panel.
2. Look for a Browser mode option.
3. If available, type a URL like http://example.com in the address bar and tap Go.

**Expected:** A WebView should load the webpage. If the page does not load or the browser mode is not available, that may be a limitation. If the page loads but JavaScript does not work, that is a bug.

**Cleanup:** No

---

## Test 74 — Fullscreen preview toggle

**File setup:** Create a file named test_fullscreen.html and paste this content:

```
<h1>Fullscreen Test</h1>
<p>This is a test page for fullscreen toggle.</p>
```

**Steps:**

1. Open the file and go to Preview.
2. Look for a fullscreen icon (arrows pointing outward).
3. Tap it.

**Expected:** The preview should go fullscreen. Tap the exit button or back to leave fullscreen. After exiting fullscreen, the preview should NOT reload or refresh. If the page reloads after exiting fullscreen, that is a bug.

**Cleanup:** Yes — delete test_fullscreen.html after testing

---

## Test 75 — Zen Mode keyboard

**File:** none needed.

**Steps:**

1. Open any file in the editor.
2. Tap the grid icon in the top bar.
3. Tap Zen Mode.
4. Tap anywhere in the code area.

**Expected:** The keyboard should appear immediately when you tap the code. If the keyboard does not appear, that is a bug.

**Cleanup:** No

---

## Test 76 — Zen Mode exit

**File:** none needed.

**Steps:**

1. While in Zen Mode, look for a floating circular button in the top-right corner.
2. Tap it.

**Expected:** The full IDE interface returns with all panels, activity bar, and bottom panel visible. If the button does not exist or tapping it does nothing, that is a bug.

**Cleanup:** No

---

## Test 77 — Hamburger menu

**File:** none needed.

**Steps:**

1. Look at the top of the activity bar on the left side.
2. Find the three-line hamburger icon.
3. Tap it.

**Expected:** A dropdown menu should appear with categories: File, Edit, Selection, View, Go, Run, Terminal, Help. If the menu does not appear, that is a bug.

**Cleanup:** No

---

## Test 78 — File submenu

**File:** none needed.

**Steps:**

1. Tap the hamburger icon.
2. Tap File.
3. Look for items in the submenu.

**Expected:** Items should include New Text File, New File, New Folder, Open File, Open Folder, Open Recent, and New Window with Profile. If these items are missing, that is a bug. Tap New Text File to create an untitled file.

**Cleanup:** No

---

## Test 79 — Open File picker

**File:** none needed.

**Steps:**

1. Tap the hamburger icon.
2. Tap File.
3. Tap Open File.

**Expected:** The Android file picker should open, allowing you to select a file from your device. If nothing happens or an error appears, that is a bug.

**Cleanup:** No

---

## Test 80 — Activity bar VS Code icons

**File:** none needed.

**Steps:**

1. Look at the activity bar on the left side.
2. Observe the icons.

**Expected:** Icons should match VS Code style: explorer (file/document shape), search (magnifying glass), source control (three-circle branch graph), run and debug (play triangle with bug), extensions (puzzle piece), hamburger (three lines). If generic Material Icons are shown instead of custom VS Code icons, that is a bug.

**Cleanup:** No

---

## Test 81 — Landscape overflow

**File:** none needed.

**Steps:**

1. Rotate your device to landscape mode.
2. Look at the activity bar.

**Expected:** The activity bar should show the Explorer icon, the currently active panel icon, and a three-dot overflow icon. Tapping the three-dot should show hidden panels (Search, Source Control, Run, Extensions). Selecting one should swap it into the visible slot. In portrait mode, all 5 icons should be visible. If all icons show in landscape with no overflow, that is not necessarily a bug but means landscape overflow is not active.

**Cleanup:** No

---

## Test 82 — Bottom panel drag resize

**File:** none needed.

**Steps:**

1. Look for the divider line between the editor area and the bottom panel.
2. Press and drag it upward.

**Expected:** The bottom panel should grow taller as you drag up. Drag down and it should shrink. If you drag it very low (below a threshold), the panel should collapse. Dragging it back up should restore it. If the divider does not respond to touch, that is a bug.

**Cleanup:** No

---

## Test 83 — Notification floating card

**File:** none needed.

**Steps:**

1. Trigger a notification by switching between panels rapidly (go to Problems, then Terminal, then Output quickly).
2. Look at the bottom-right corner of the screen.

**Expected:** Notifications should appear as floating cards in the bottom-right corner, not as top banners. If notifications appear at the top, that is a bug.

**Cleanup:** No

---

## Test 84 — Notification bell icon

**File:** none needed.

**Steps:**

1. Look at the status bar at the bottom of the screen for a bell icon.
2. Tap it.

**Expected:** A notification drawer should open showing notification history. If no bell icon exists or tapping it does nothing, that is a bug.

**Cleanup:** No

---

## Test 85 — Output panel clear

**File:** none needed.

**Steps:**

1. Go to the Output tab in the bottom panel.
2. Look for a trash can icon.
3. Tap it.

**Expected:** All output lines should be cleared. If the output remains, that is a bug.

**Cleanup:** No

---

## Test 86 — Output panel save

**File:** none needed.

**Steps:**

1. Go to the Output tab.
2. Look for a save icon (floppy disk or download icon).
3. Tap it.

**Expected:** A file should be saved to your Downloads folder or the app's exports directory. If nothing saves or an error appears, that is a bug.

**Cleanup:** No

---

## Test 87 — Output panel channels

**File:** none needed.

**Steps:**

1. Go to the Output tab.
2. Look for channel filter buttons (All, Build, Git, Debug, Lsp, Terminal).

**Expected:** All 6 channels should be visible. Tapping a channel should filter the output to only that channel. If only 4 channels appear, that is a bug (LSP and Terminal channels were previously hidden).

**Cleanup:** No

---

## Test 88 — Breakpoint gutter markers

**File setup:** Create a file named test_breakpoint.py and paste this content:

```
print("line 1")
print("line 2")
print("line 3")
```

**Steps:**

1. Open the file.
2. Tap the line number 2 on the left side of the editor.

**Expected:** A red dot should appear on line 2 indicating a breakpoint. Tap it again to remove it. If no red dot appears, that is a bug.

**Cleanup:** Yes — delete test_breakpoint.py after testing

---

## Test 89 — Debug session (Python)

**File setup:** Create a file named test_debug.py and paste this content:

```
print("start")
x = 1 + 2
print(f"result: {x}")
print("end")
```

**Steps:**

1. Open the file.
2. Tap line number 2 to set a breakpoint.
3. Go to the activity bar and tap the Run and Debug icon (play triangle with bug).
4. Tap the Start or Run button.

**Expected:** The debug session should start and pause at line 2. The variable inspector should show x or local variables. Step Over button should advance to line 3. If the debug session does not start or does not pause, that is a bug.

**Cleanup:** Yes — delete test_debug.py after testing

---

## Test 90 — Debug restart button

**File:** none needed.

**Steps:**

1. While a debug session is running (from Test 89), look for a green restart button (circular arrow or refresh icon) in the debug toolbar.
2. Tap it.

**Expected:** The current debug session should stop and a new one should start. If the button does not exist or does nothing, that is a bug.

**Cleanup:** No

---

## Test 91 — Debug variable expansion

**File:** none needed.

**Steps:**

1. While paused at a breakpoint in a debug session, look at the Variables panel in the debug sidebar.
2. Tap the arrow next to a variable that has children (like a list or dict).

**Expected:** The variable should expand to show its child values. If the arrow does not respond or no children appear, that is a bug.

**Cleanup:** No

---

## Test 92 — Logcat viewer

**File:** none needed.

**Steps:**

1. Go to the bottom panel.
2. Look for a LOGCAT tab.
3. Tap it.

**Expected:** If adb is available on the device, logcat output should stream in with color-coded log levels. If adb is not available, a message should say adb is not found. If the panel is completely blank with no message, that is a bug.

**Cleanup:** No

---

## Test 93 — Memory monitor in status bar

**File:** none needed.

**Steps:**

1. Look at the status bar at the bottom of the screen for a RAM or memory indicator.

**Expected:** A number showing available RAM should be visible (something like 874MB or 2.8GB). If no memory indicator is visible, that is a bug. The number should update periodically.

**Cleanup:** No

---

## Test 94 — Code metrics in status bar

**File:** none needed.

**Steps:**

1. Open any file.
2. Look at the status bar for file metrics.

**Expected:** The status bar should show line count, file size, or similar metrics for the open file. The current cursor position (line and column) should also be visible. If none of these appear, that is a bug.

**Cleanup:** No

---

## Test 95 — Project creation wizard

**File:** none needed.

**Steps:**

1. Go to the home screen.
2. Tap the plus button or FAB.
3. A menu should appear with New local project and Clone from GitHub.

**Expected:** Tapping New local project should open a project wizard with project type selection (Android, Flutter, React Native, Web, Node.js, Python, Empty) and a name input. If only a simple text dialog appears, the wizard may not be fully implemented.

**Cleanup:** No

---

## Test 96 — Toolchain panel

**File:** none needed.

**Steps:**

1. Open a project.
2. Go to the bottom panel overflow.
3. Look for a TOOLCHAIN tab.
4. Tap it.

**Expected:** A panel should appear listing detected tools (JDK, Gradle, Android SDK, Node.js, Python, etc.) with their versions and status. If the panel is empty or does not appear, that is a bug.

**Cleanup:** No

---

## Test 97 — Task runner

**File:** none needed.

**Steps:**

1. Open a project.
2. Go to the bottom panel overflow.
3. Look for a TASKS tab.
4. Tap it.

**Expected:** A panel should appear with one-tap task buttons like Build APK, Clean Project, Run Tests. If the panel is empty, that is a bug.

**Cleanup:** No

---

## Test 98 — Build history

**File:** none needed.

**Steps:**

1. Open a project.
2. Go to the bottom panel overflow.
3. Look for a HISTORY tab.
4. Tap it.

**Expected:** A panel should appear listing previous builds with their status, timestamp, and log. If the panel is empty and no builds have been run, that is expected. If builds have been run but history is empty, that is a bug.

**Cleanup:** No

---

## Test 99 — Cloud backup

**File:** none needed.

**Steps:**

1. Open a project.
2. Go to the bottom panel.
3. Look for a BACKUP tab.
4. Tap it.
5. Look for a Backup Now button.

**Expected:** A backup panel should appear with options to backup or restore the project. If the panel is empty or does not appear, that is a bug. Do not tap Backup Now unless you want to test the actual backup.

**Cleanup:** No

---

## Test 100 — Sync status indicator

**File:** none needed.

**Steps:**

1. Look at the status bar at the bottom of the screen for a sync indicator (a small dot or icon).

**Expected:** A sync status indicator should be visible showing Idle (grey), Syncing (blue), Success (green), or Error (red). If no sync indicator exists, that is a bug.

**Cleanup:** No

---

## Test 101 — AI Copilot panel

**File:** none needed.

**Steps:**

1. Look for a bot or chat icon in the activity bar or top bar.
2. Tap it.

**Expected:** A sliding chat panel should appear from the right side. There should be a text input field at the bottom and a provider selector. If the panel does not appear, that is a bug.

**Cleanup:** No

---

## Test 102 — AI Copilot multi-session

**File:** none needed.

**Steps:**

1. In the AI Copilot panel, look for a session selector or new chat button.
2. Tap it to create a new session.
3. Type a message in the new session.
4. Go back to the previous session.

**Expected:** Each session should have its own independent chat history. If switching sessions shows the same messages, that is a bug.

**Cleanup:** No

---

## Test 103 — Settings search

**File:** none needed.

**Steps:**

1. Open Project Settings.
2. Look for a search bar at the top.
3. Type cursor.

**Expected:** The settings list should filter to show only cursor-related settings (Cursor Blink Style, Cursor Type, etc.). If the search bar does not filter, that is a bug.

**Cleanup:** No

---

## Test 104 — Biometric lock

**File:** none needed.

**Steps:**

1. Open Settings.
2. Look for a Biometric toggle.
3. Turn it on.
4. Close the app and reopen it.

**Expected:** A fingerprint or face recognition prompt should appear on app launch. If no prompt appears, the biometric lock may not be working. If you cannot access settings because of the biometric lock, disable it from Settings after authentication.

**Cleanup:** No

---

## Test 105 — MCP status green

**File:** none needed.

**Steps:**

1. Open the app and open a project.
2. Without opening the terminal, look for an MCP status indicator (green dot).

**Expected:** The MCP status should show green without needing to open the terminal first. If it stays red or does not appear, that is a bug.

**Cleanup:** No

---

## Test 106 — Package manager (Extensions panel)

**File:** none needed.

**Steps:**

1. Tap the Extensions icon in the activity bar (puzzle piece shape).
2. Wait for the package list to load.

**Expected:** A list of packages should appear with Install and Remove buttons. If the list is empty or loading never completes, that is a bug. There should be around 35 featured packages.

**Cleanup:** No

---

## Test 107 — Package cancel mid-install

**File:** none needed.

**Steps:**

1. In the Extensions panel, tap Install on any package.
2. While it is installing, look for a Cancel button.
3. Tap it.

**Expected:** The installation should stop. If the cancel button does nothing, that is a bug.

**Cleanup:** No

---

## Test 108 — SSH manager

**File:** none needed.

**Steps:**

1. Look for an SSH Manager option in the three-dot overflow menu or activity bar.
2. Tap it.

**Expected:** A sheet should appear for managing SSH connection profiles (host, port, user, key or password). If the sheet does not appear, that is a bug.

**Cleanup:** No

---

## Test 109 — Quick command palette (terminal)

**File:** none needed.

**Steps:**

1. Open the Terminal tab.
2. Look for a lightning bolt icon or Cmds button.
3. Tap it.

**Expected:** A command palette should appear showing command history or quick commands. Tapping a command should execute it in the terminal. If nothing appears, that is a bug.

**Cleanup:** No

---

## Test 110 — Terminal notification channel

**File:** none needed.

**Steps:**

1. Trigger a terminal notification (start a long-running process).
2. Check the notification in the Android notification shade.

**Expected:** The notification channel should show as VN Code (not Codespace IDE or generic app name). If it shows a different name, that is a bug.

**Cleanup:** No

---

## Test 111 — Terminal notification toggle

**File:** none needed.

**Steps:**

1. Open Settings.
2. Find Terminal Notifications toggle.
3. Turn it off.
4. Trigger a terminal process.

**Expected:** No terminal notification should appear in the notification shade. Turn it back on and the notification should appear again. If toggling does nothing, that is a bug.

**Cleanup:** No

---

## Test 112 — Flow Mode persists

**File:** none needed.

**Steps:**

1. Open Settings.
2. Find Flow Mode.
3. Change it to Manual.
4. Close the app and reopen it.
5. Go back to Settings.

**Expected:** Flow Mode should still be set to Manual. If it reverts to a different value, that is a bug.

**Cleanup:** No

---

## Test 113 — Format on save

**File:** none needed.

**Steps:**

1. Open Settings.
2. Enable a formatter for Python.
3. Open a Python file with bad spacing.
4. Save the file.

**Expected:** The spacing should be normalized on save. If the file saves without formatting, that is a bug.

**Cleanup:** No

---

## Test 114 — Replace in Files

**File:** none needed.

**Steps:**

1. Open the file search panel (Find in Files).
2. Look for a Replace option or amber-colored Replace chip.
3. Type a search word and a replacement word.
4. Tap Replace All.

**Expected:** All matching files should have the text replaced. A snackbar should confirm how many replacements were made. If nothing happens, that is a bug.

**Cleanup:** No

---

## Test 115 — Merge conflict inline editor

**File setup:** Create a file named test_conflict.txt and paste this content:

```
<<<<<<< HEAD
our change
=======
their change
>>>>>>> feature-branch
```

**Steps:**

1. Open the file in the editor.

**Expected:** The conflict markers should be highlighted with colors (red for ours, green for theirs). Buttons should appear for Accept Ours, Accept Theirs, or Accept Both. Tapping one should resolve the conflict by removing the markers and keeping the chosen version. If no buttons appear, that is a bug.

**Cleanup:** Yes — delete test_conflict.txt after testing

---

## Test 116 — Peek Definition

**File setup:** Create a file named test_peek.py and paste this content:

```
def my_func():
    return 42
result = my_func()
```

**Steps:**

1. Open the file.
2. Long-press my_func on line 3.
3. Tap Peek Definition from the context menu.

**Expected:** An inline peek overlay should appear showing the definition of my_func without leaving the current file. There should be an X button to close the peek. If the X button is not visible in portrait mode, that is a bug. If no peek overlay appears, that is a bug.

**Cleanup:** Yes — delete test_peek.py after testing

---

## Test 117 — Workspace file search

**File:** none needed.

**Steps:**

1. Tap the Search icon in the activity bar (magnifying glass).
2. Type a word that exists in your project files.

**Expected:** Results should appear listing files and lines where the word was found. Tapping a result should open the file at that line. If no results appear despite the word existing in files, that is a bug.

**Cleanup:** No

---

## Test 118 — Symbol search (Go to Symbol)

**File:** none needed.

**Steps:**

1. Tap the three-dot overflow menu.
2. Tap Go then Go to Symbol, or look for a symbol search icon.
3. Type a function or class name.

**Expected:** A list of symbols matching the name should appear from across the workspace. Tapping a result should navigate to the symbol. If the list is empty, that is a bug.

**Cleanup:** No

---

## Test 119 — Multiple editor tabs

**File:** none needed.

**Steps:**

1. Open 3 different files from the file explorer.
2. Look at the tab bar at the top of the editor.

**Expected:** Each file should have its own tab. Tapping a tab should switch to that file. If tabs do not appear or switching does not work, that is a bug.

**Cleanup:** No

---

## Test 120 — Session restore (editor)

**File:** none needed.

**Steps:**

1. Open 3 files in the editor.
2. Force-close the app.
3. Reopen the app and open the same project.

**Expected:** The same 3 files should reopen in the editor with their tabs restored. If the tabs are lost, that is a bug.

**Cleanup:** No

---

## Test 121 — Bookmark gutter

**File:** none needed.

**Steps:**

1. Open any file.
2. Tap in the gutter area next to a line number (but not on the number itself, which toggles breakpoints).
3. Look for a bookmark icon to appear.

**Expected:** A bookmark icon should appear in the gutter. The icon should be readable against the background color. If no bookmark appears, that is a bug.

**Cleanup:** No

---

## Test 122 — Autosave and restore dialog

**File setup:** Create a file named test_autosave.py and paste this content:

```
print("hello")
```

**Steps:**

1. Open the file.
2. Type some additional text without saving.
3. Wait 30 seconds.
4. Force-close the app.
5. Reopen the app and open the project.

**Expected:** A dialog should appear offering to Restore or Discard the unsaved changes. If no dialog appears and the changes are lost, that is a bug. Tap Restore to recover the text.

**Cleanup:** Yes — delete test_autosave.py after testing

---

## Test 123 — Workspace snapshot

**File:** none needed.

**Steps:**

1. Tap the three-dot overflow menu.
2. Tap File then Create Snapshot or look for a Snapshot option.

**Expected:** The app should create a ZIP file of the current project in the Downloads/CodespaceIDE/ folder. If nothing happens or an error appears, that is a bug.

**Cleanup:** No

---

## Test 124 — Diagnostics report

**File:** none needed.

**Steps:**

1. Tap the three-dot overflow menu.
2. Look for Diagnostics Report.
3. Tap it.

**Expected:** A report should be generated with device info, crash logs, and terminal output. A share sheet should appear allowing you to share or save the report. If nothing happens, that is a bug.

**Cleanup:** No

---

## Test 125 — Connectors hub

**File:** none needed.

**Steps:**

1. Look for a Connectors option in the three-dot overflow menu or activity bar.
2. Tap it.

**Expected:** A connectors hub sheet should appear showing available third-party services (Google, Slack, etc.) with connect buttons. If the sheet does not appear, that is a bug.

**Cleanup:** No

---

## Test 126 — Image generation dialog

**File:** none needed.

**Steps:**

1. Look for an image generation feature in the AI Copilot panel or overflow menu.
2. If available, tap it.

**Expected:** A dialog should appear allowing you to enter a prompt for AI image generation. If the feature does not exist, that may mean it was not fully wired. If the dialog appears but generation fails, that is a bug.

**Cleanup:** No

---

## Test 127 — Device quick-access folders

**File:** none needed.

**Steps:**

1. In the file explorer, look for quick-access buttons or entries for Pictures, DCIM, Downloads, Documents, Music, or Movies.

**Expected:** Tapping one should navigate to that device folder. If the folders do not appear or navigation fails, that is a bug.

**Cleanup:** No

---

## Test 128 — Recent search history

**File:** none needed.

**Steps:**

1. Open the Search panel.
2. Type a word and search.
3. Close the search panel.
4. Reopen the search panel.

**Expected:** The previous search query should appear in the search history. If the search field is blank with no history, that is a bug.

**Cleanup:** No

---

## Test 129 — Completion popup drag resize

**File:** none needed.

**Steps:**

1. Open a file.
2. Type a few characters to trigger the autocomplete popup.
3. Press and drag the edge of the popup.

**Expected:** The popup should resize as you drag. If the popup does not respond to dragging, that is a bug.

**Cleanup:** No

---

## Test 130 — YouTube video in preview

**File:** none needed.

**Steps:**

1. Go to the Preview tab.
2. Type a YouTube URL in the browser address bar.
3. Tap Go.

**Expected:** The video should play with video, not just audio. If only audio plays or the screen is black, that is a bug. If a sign-in prompt appears calling the browser insecure, that is also a bug.

**Cleanup:** No

---
