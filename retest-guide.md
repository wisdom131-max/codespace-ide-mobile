# codespace-ide-mobile — Focused Retest Guide (33 items)

This guide covers only the tests that previously FAILED, were PARTIAL, or were FEATURE REQUESTS. All other tests from the 130-test round are assumed passing.

For each test that needs a file, **copy the exact block** and paste it — do not type it manually. Run the steps exactly as written. Report PASS / FAIL / PARTIAL for each.

---

## Test 9 — Terminal file creation (large file)

**File setup:** Open the Terminal tab and run this exact command (copy-paste it):

```
yes "This is a test line for large file handling" | head -5000 > test_large.txt
```

Then open `test_large.txt` from the file explorer.

**Steps:**

1. Open any project.
2. Tap the Terminal tab at the bottom.
3. Paste the command above and press Enter.
4. Switch to the file explorer and tap `test_large.txt` to open it.
5. Scroll up and down through the file.

**Expected:** The file opens with 5000 lines and scrolls smoothly without lag, freezing, or crashing. If the app freezes for more than 3 seconds or crashes, that is a FAIL.

**Cleanup:** Yes — delete `test_large.txt` after testing.

---

## Test 12 — Breakpoint red dot on line-number tap (PARTIAL)

**File setup:** Create a file named `test_bp_partial.py` and paste this content:

```
print("line 1")
print("line 2")
print("line 3")
print("line 4")
print("line 5")
```

**Steps:**

1. Open the file.
2. Tap directly on the line number **2** on the left side of the editor (not the text content, the number itself).
3. Look at line 2's gutter area.

**Expected:** A red dot appears on line 2 indicating a breakpoint. Tap the number **2** again and the dot should disappear. Tap number **4** and a dot should appear there. If the dot does not appear, appears on the wrong line, or does not toggle off, that is a FAIL.

**Cleanup:** Yes — delete `test_bp_partial.py` after testing.

---

## Test 15 — Kotlin snippet Tab expansion

**File setup:** Create a file named `test_snippet_kt.kt` (empty file, do not type anything inside).

**Steps:**

1. Open `test_snippet_kt.kt`.
2. Tap in the editor to focus it.
3. Type the word `fun` (lowercase, no quotes).
4. Find the **Tab** key on the extra-keys bar above the keyboard (not the autocomplete popup — use the physical Tab key in the key bar).
5. Press the Tab key.

**Expected:** The word `fun` expands into a Kotlin function template such as `fun name() { }` with the cursor positioned at `name` for editing. If nothing happens, the word `fun` stays unchanged, or the app crashes, that is a FAIL.

**Cleanup:** Yes — delete `test_snippet_kt.kt` after testing.

---

## Test 16 — Python snippet Tab expansion + entire extra-keys bar

This test has two parts. Test both.

### Part A: Python snippet Tab expansion

**File setup:** Create a file named `test_snippet_py.py` (empty file).

**Steps:**

1. Open `test_snippet_py.py`.
2. Tap in the editor to focus it.
3. Type the word `def` (lowercase).
4. Press the **Tab** key on the extra-keys bar above the keyboard.

**Expected:** The word `def` expands into a Python function template such as `def name():` with the cursor positioned at `name`. If nothing happens or the word stays unchanged, that is a FAIL.

### Part B: Editor extra-keys bar

**Steps:**

1. While the editor is focused (keyboard visible), look at the row of extra keys above the keyboard.
2. Verify the following keys are visible and tappable: **Tab**, **ESC**, **CTRL**, **arrow keys** (left/right/up/down), **pipe** (`|`), and at least one more key.
3. Tap the **Tab** key — it should insert a tab character or trigger snippet expansion.
4. Tap the **ESC** key — it should send an escape signal (may close a popup or do nothing visible, but should not crash).
5. Tap a **left arrow** — the cursor should move left.
6. Tap a **right arrow** — the cursor should move right.
7. Swipe left on the extra-keys row — more keys should appear from the right (e.g., ALT, CTRL, more symbols).
8. Swipe right to go back to the first set of keys.

**Expected:** All listed keys are visible, tappable, and perform their function. Swiping reveals additional keys. If the extra-keys bar is empty, missing, non-functional, or crashes the app, that is a FAIL.

**Cleanup:** Yes — delete `test_snippet_py.py` after testing.

---

## Test 17 — Find bar in the editor

**File setup:** Create a file named `test_find_v2.py` and paste this content:

```
value = 10
value = 20
value = 30
```

**Steps:**

1. Open `test_find_v2.py`.
2. Tap the three-dot overflow menu (top-right).
3. Tap **Edit** then **Find** (or tap the Find icon in the editor toolbar).
4. In the find bar that appears at the top, type `value`.
5. Look at the editor — matches of `value` should be highlighted (yellow/colored background).
6. Tap the **Next** arrow (down or right arrow) — the highlight should move to the second `value`.
7. Tap **Next** again — it should move to the third `value`.
8. Tap **Previous** arrow (up or left arrow) — it should move back to the second `value`.
9. Tap the **Replace** toggle (or replace icon) to show the replace field.
10. Type `number` in the replace field.
11. Tap **Replace All**.

**Expected:**
- Step 5: All three `value` occurrences are highlighted.
- Steps 6-8: Next/Previous navigation moves between matches and the editor scrolls to the current match.
- Step 11: All three instances change to `number`. The file now reads `number = 10`, `number = 20`, `number = 30`.

If matches are not highlighted, next/prev doesn't move, replace doesn't update the file, or the editor doesn't show the replaced text, that is a FAIL.

**Cleanup:** Yes — delete `test_find_v2.py` after testing.

---

## Test 18 — Go to Line: highlight + cursor move (PARTIAL)

**File setup:** Create a file named `test_gotoline_v2.py` and paste this content:

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

1. Open `test_gotoline_v2.py`.
2. Tap the three-dot overflow menu.
3. Tap **Go** then **Go to Line**.
4. Type `7` in the dialog.
5. Tap **OK**.

**Expected:**
- The editor scrolls to line 7.
- Line 7 is visually highlighted (a different background color or border on that line).
- The cursor is placed on line 7 (the blinking cursor should be at the start of `line7`).

If the cursor goes to a different line, line 7 is not highlighted, or the cursor doesn't move, that is a FAIL. If you type `999`, it should auto-clamp to line 10 (the last line) — that is correct.

**Cleanup:** Yes — delete `test_gotoline_v2.py` after testing.

---

## Test 19 — Multi-cursor editing (full test)

**File setup:** Create a file named `test_multi_v2.py` and paste this content:

```
apple = 1
apple = 2
apple = 3
```

### Part A: Select Next Occurrence

**Steps:**

1. Open `test_multi_v2.py`.
2. Long-press the word `apple` on line 1 (the word should get selected/highlighted).
3. Tap **Select Next Occurrence** from the context menu (or the Ctrl+D equivalent button).
4. Tap **Select Next Occurrence** again.
5. Tap it a third time.

**Expected:** Each tap adds a new cursor at the next occurrence of `apple`. After 3 taps you should have 3 cursors, one on each line. If only one cursor is active or the selection doesn't expand, that is a FAIL.

### Part B: Type with multiple cursors

**Steps:**

1. With 3 cursors active (from Part A), type `x`.

**Expected:** All three lines change simultaneously: `xpple = 1`, `xpple = 2`, `xpple = 3`. If only the first line changes or the cursors desync (some type, some don't), that is a FAIL.

### Part C: Add cursor above/below

**Steps:**

1. Close the file without saving and reopen it (to get the original content back).
2. Place the cursor on line 2.
3. Look for an **Add Cursor Above** or **Add Cursor Below** option in the context menu or extra-keys bar.
4. Tap **Add Cursor Below**.

**Expected:** A second cursor appears on line 3. Both cursors should blink. Type `# ` and both lines should get the `# ` prefix. If only one cursor appears, that is a FAIL. If the option doesn't exist at all, note that as PARTIAL.

**Cleanup:** Yes — delete `test_multi_v2.py` after testing.

---

## Test 30 — Cursor blink style: Solid and Expand (PARTIAL)

**File setup:** None needed.

**Steps:**

1. Open any file in the editor.
2. Go to **Project Settings** (gear icon or three-dot menu then Settings).
3. Find **Cursor Blink Style** setting.
4. Change it to **Solid**.
5. Return to the editor and look at the cursor.

**Expected:** The cursor becomes a solid, non-blinking line/bar. It should be continuously visible (not disappearing). If the cursor is invisible or still blinking, that is a FAIL.

6. Go back to Settings and change it to **Expand**.
7. Return to the editor.

**Expected:** The cursor should be visible and have an "expand" style (the cursor bar may be thicker or have a different rendering). If the cursor is invisible, that is a FAIL.

8. Go back and set it to **Blink** (or default).

**Cleanup:** No

---

## Test 32 — Wavy underline for lint errors (PARTIAL)

**File setup:** Create a file named `test_lint_v2.py` and paste this content:

```
def foo():
    undefined_variable_here = 1
    return undefined_variable_here
```

**Steps:**

1. Open `test_lint_v2.py`.
2. Wait 5-10 seconds for the language server to start.
3. Look at line 2, specifically under the text `undefined_variable_here`.

**Expected:** A wavy underline (squiggly line) appears under `undefined_variable_here`. The underline may be red (error) or yellow (warning). If no underline appears after 15 seconds, that is a FAIL. If the app crashes when the underline appears, that is a critical FAIL.

**Note:** This requires the Python LSP server to be running. If LSP is not installed/enabled, the underline will not appear — in that case, first enable LSP in Settings and retry.

**Cleanup:** Yes — delete `test_lint_v2.py` after testing.

---

## Test 33 — Problems panel: tap error moves cursor (PARTIAL)

**File setup:** Create a file named `test_problems_v2.py` and paste this content:

```
def foo():
    x = undefined_thing
    return x
```

**Steps:**

1. Open `test_problems_v2.py`.
2. Wait 5-10 seconds for LSP to analyze the file.
3. Open the bottom panel and tap the **Problems** tab (checklist or warning triangle icon).
4. The Problems panel should list an error about `undefined_thing`.
5. Tap the error entry.

**Expected:** Tapping the error moves the editor cursor to line 2 (where `undefined_thing` is) and the editor scrolls to that line. If the Problems panel is empty, tapping does nothing, or the cursor doesn't move, that is a FAIL.

**Cleanup:** Yes — delete `test_problems_v2.py` after testing.

---

## Test 34 — Fix with AI in lightbulb menu

**File setup:** Create a file named `test_lightbulb_v2.py` and paste this content:

```
def foo():
    x = undefined_var_here
    return x
```

**Steps:**

1. Open `test_lightbulb_v2.py`.
2. Wait 5-10 seconds for lint underlines to appear on line 2.
3. Look for a **lightbulb icon** in the gutter (left side) near line 2, or near the error underline.
4. Tap the lightbulb icon.

**Expected:** A small menu appears with at least one option, including **Fix with AI**. Tap **Fix with AI**. The AI Copilot chat panel should open with a pre-filled prompt describing the error and suggesting a fix. If no lightbulb appears (after 15 seconds with LSP running), that is a FAIL. If the lightbulb appears but no "Fix with AI" option exists, that is a FAIL. If tapping "Fix with AI" doesn't open the chat panel, that is a FAIL.

**Cleanup:** Yes — delete `test_lightbulb_v2.py` after testing.

---

## Test 35 — Cross-file Go to Definition

**File setup:** Create two files in the same project.

First, create `utils.py` and paste this content:

```
def helper_function():
    return 42
```

Then create `main.py` and paste this content:

```
from utils import helper_function
result = helper_function()
```

**Steps:**

1. Open `main.py`.
2. Long-press the word `helper_function` on line 2.
3. Tap **Go to Definition** from the context menu.

**Expected:** The app should navigate to `utils.py` and place the cursor on line 1 (where `helper_function` is defined). If nothing happens, only the current file is searched, or the app crashes, that is a FAIL. If a popup appears showing the definition location and you have to tap it to navigate, that is acceptable (note it as PASS with popup navigation).

**Cleanup:** Yes — delete `utils.py` and `main.py` after testing.

---

## Test 38 — Code Lens

**File setup:** Create a file named `test_lens_v2.kt` and paste this content:

```
fun myFunction() {
    println("hello")
}
```

**Steps:**

1. Open `test_lens_v2.kt`.
2. Wait 5-10 seconds for the Kotlin LSP to start.
3. Look at the end of line 1 (after the opening brace).

**Expected:** A small teal-colored or gray annotation appears at the end of line 1, such as `1 reference` or `0 references`. If no annotation appears after 15 seconds, that is a FAIL. If LSP is not installed, note that as the reason.

**Cleanup:** Yes — delete `test_lens_v2.kt` after testing.

---

## Test 40 — Outline: cursor at symbol (PARTIAL)

**File setup:** Create a file named `test_outline_v2.py` and paste this content:

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

1. Open `test_outline_v2.py`.
2. Wait a few seconds for LSP to index symbols.
3. Tap the **Outline** icon in the activity bar (left sidebar — looks like a tree or list icon).
4. The outline should show: `MyClass` with children `method_one` and `method_two`, and `standalone_func` as a separate entry.
5. Tap `method_two` in the outline.

**Expected:**
- The outline tree is populated with the correct structure.
- Tapping `method_two` scrolls the editor to line 4 AND places the cursor on line 4 (at `def method_two`). The target line should also be briefly highlighted.
- If the tree is empty, that is a FAIL. If tapping scrolls but doesn't place the cursor or highlight the line, that is a PARTIAL.

**Cleanup:** Yes — delete `test_outline_v2.py` after testing.

---

## Test 43 — Master LSP toggle

**File setup:** None needed.

**Steps:**

1. Open **Project Settings** (gear icon or three-dot menu then Settings).
2. Find the toggle for **Enable LSP Servers** (or "Master LSP Toggle").
3. Turn it **OFF**.
4. Open a Python file and type some code with an undefined variable.

**Expected:** No LSP squiggly underlines, no hover popups, no LSP-powered completions. Only basic keyword completions (if any) should appear. If LSP behavior continues after turning it off, that is a FAIL.

5. Go back to Settings and turn LSP **ON**.
6. Return to the Python file.

**Expected:** Within 5-10 seconds, LSP squiggles and completions should resume. If turning it back on doesn't restore LSP, that is a FAIL.

**Cleanup:** No

---

## Test 46 and 113 — Formatter auto-install (FEATURE REQUEST)

**File setup:** Create a file named `test_format_v2.py` and paste this content:

```
def  foo( ):
    x=1
    y    =2
    return x+y
```

**Steps:**

1. Open `test_format_v2.py`.
2. Look for a **Format** button in the editor toolbar (curly braces icon or a wand icon).
3. Tap the Format button.

**Expected:**
- The formatter auto-installs its tool (e.g., `autopep8` or `black`) on first run with zero manual steps — no "formatter not found" error, no manual install dialog.
- The code is reformatted to proper spacing: `def foo():`, `x = 1`, `y = 2`, `return x + y`.
- If the formatter fails with a "not installed" message or requires manual pip install, that is a FAIL.
- If formatting works but a progress/toast message says "installing formatter" that is acceptable (auto-install is the key — it should not require the user to do anything).

4. To test **Format on Save** (Test 113): Make the spacing bad again, then tap **Save** (or Ctrl+S equivalent).

**Expected:** The file saves AND the spacing is normalized automatically on save. If the file saves without formatting, that is a FAIL.

**Cleanup:** Yes — delete `test_format_v2.py` after testing.

---

## Test 47 — Git unstaging (PARTIAL)

**File setup:** None needed, but you need a project with a git repo.

**Steps:**

1. Open a project that has a git repository (if none exists, create one and run `git init` in the terminal).
2. Modify an existing file by adding a line (e.g., add `# test` at the end).
3. Tap the **Source Control** icon in the activity bar (branch/graph icon on the left sidebar).
4. Find the modified file in the **Changes** (unstaged) section.
5. Tap the **plus** (+) icon next to the file to stage it.

**Expected:** The file moves from the **Changes** section to the **Staged** section. If the plus icon does nothing or an error toast appears, that is a FAIL.

6. Tap the **minus** (–) icon next to the staged file to unstage it.

**Expected:** The file moves back from **Staged** to **Changes** (unstaged). If the unstage fails with an error (e.g., "git author identity not set" or "failed to unstage"), that is a FAIL.

**Cleanup:** No (but you may want to revert your test change afterward).

---

## Test 69 — Trash: individual item deletion (PARTIAL)

**File setup:** Create a test file you can delete.

1. In the terminal, run: `echo "test content" > test_trash_item.txt`

**Steps:**

1. In the file explorer, long-press `test_trash_item.txt`.
2. Tap **Delete** (or the trash icon).
3. Confirm the deletion if a dialog appears.
4. Open the **Recycle Bin** / **Trash** from the explorer overflow menu.
5. You should see `test_trash_item.txt` in the trash list.
6. Long-press `test_trash_item.txt` in the trash list.
7. Tap **Delete** (permanent delete) or look for a "Delete forever" option.

**Expected:**
- Step 3: The file moves to trash (not permanently deleted).
- Step 4-5: The trash list shows the deleted file.
- Step 7: The item is **actually deleted** from the trash — not just removed from the list. Reopen the trash to confirm the item is gone. If the item reappears after reopening trash, that is a FAIL (it was only cleared from the list, not actually deleted).

**Cleanup:** Yes — confirm the file is gone from both trash and the filesystem.

---

## Test 70 — HTML preview

**File setup:** Create a file named `test_html_v2.html` and paste this content:

```
<!DOCTYPE html>
<html>
<body>
<h1 style="color:blue;">Hello World</h1>
<p style="color:green;">This is a test paragraph.</p>
</body>
</html>
```

**Steps:**

1. Open `test_html_v2.html`.
2. Tap the **Preview** tab in the bottom panel (or a preview icon in the editor).

**Expected:** The preview shows a blue "Hello World" heading and a green paragraph below it, rendered as actual HTML (not raw code). If raw HTML tags are shown instead, that is a FAIL.

### Pinch-to-zoom and resize test

3. With the HTML preview visible, **pinch outward** with two fingers on the preview area.

**Expected:** The preview content zooms in (gets larger). If pinch-to-zoom does nothing, that is a FAIL.

4. **Pinch inward** to zoom back out.

**Expected:** The content zooms out (gets smaller).

5. Rotate the device to landscape orientation.

**Expected:** The preview resizes to fill the new width. If the preview stays in portrait dimensions or doesn't reflow, that is a PARTIAL.

**Cleanup:** Yes — delete `test_html_v2.html` after testing.

---

## Test 72 — SVG preview

**File setup:** Create a file named `test_svg_v2.svg` and paste this content:

```
<svg xmlns="http://www.w3.org/2000/svg" width="200" height="200">
<circle cx="100" cy="100" r="80" fill="red" stroke="black" stroke-width="3"/>
<text x="100" y="105" text-anchor="middle" fill="white" font-size="16">SVG</text>
</svg>
```

**Steps:**

1. Open `test_svg_v2.svg`.
2. Tap the **Preview** tab in the bottom panel.

**Expected:** The preview shows a red circle with a black border and the text "SVG" in white in the center. If raw SVG code appears instead of the rendered image, that is a FAIL.

### Pinch-to-zoom and resize test

3. **Pinch outward** with two fingers on the preview.

**Expected:** The SVG image zooms in. If pinch-to-zoom does nothing, that is a FAIL.

4. **Pinch inward** to zoom back out.

**Expected:** The image zooms out.

5. Rotate the device to landscape.

**Expected:** The preview resizes to fill the new dimensions.

**Cleanup:** Yes — delete `test_svg_v2.svg` after testing.

---

## Test 78 — New Text File appears in explorer after close (PARTIAL)

**File setup:** None needed.

**Steps:**

1. Tap the hamburger icon (three lines) or three-dot menu.
2. Tap **File** then **New Text File**.
3. An untitled editor tab should open (empty content).
4. Type `hello world` in the editor.
5. Tap **Save** (or Ctrl+S) — a save dialog should appear asking for a filename.
6. Type `test_newfile_visible.txt` as the filename and tap **Save** or **OK**.
7. Close the editor tab (tap the X on the tab, or swipe it away).
8. Look at the file explorer tree.

**Expected:** `test_newfile_visible.txt` appears in the file explorer tree after the tab is closed. If the file does not appear in the explorer (even after pulling to refresh or reopening the project), that is a FAIL. If the file appears but only after a manual refresh, that is a PARTIAL.

**Cleanup:** Yes — delete `test_newfile_visible.txt` after testing.

---

## Test 82 — Bottom panel drag-to-resize

**File setup:** None needed.

**Steps:**

1. Open any project and any file.
2. Locate the **divider line** between the editor area (top) and the bottom panel (bottom). It should be a thin horizontal bar.
3. Press and hold the divider.
4. **Drag it upward** slowly.

**Expected:** The bottom panel grows taller as you drag up. The editor area shrinks correspondingly. If the divider doesn't respond to touch or the panel doesn't resize, that is a FAIL.

5. **Drag it downward** slowly.

**Expected:** The bottom panel shrinks. If you drag it down below a minimum threshold, the panel should collapse (hide entirely or minimize). If it doesn't collapse, that is a PARTIAL.

6. Drag the divider back **upward** from the collapsed state.

**Expected:** The panel should restore to its previous size. If it stays collapsed and can't be dragged back up, that is a FAIL.

**Cleanup:** No

---

## Test 84 — Notification bell icon repositioned (FEATURE REQUEST)

**File setup:** None needed.

**Steps:**

1. Open any project.
2. Look at the bottom status bar of the screen.
3. Find the **bell icon** (notification bell).

**Expected:** The bell icon should be positioned near the **floating notification card** area — it should track or be adjacent to where notifications appear. If the bell icon is in a completely unrelated position (e.g., top-left corner far from notifications), that is a FAIL.

4. Tap the bell icon.

**Expected:** A notification drawer/panel opens showing notification history. If tapping does nothing, that is a FAIL.

5. Close the notification drawer.

**Cleanup:** No

---

## Test 88 — Breakpoint gutter markers (blocks Tests 89-91)

**File setup:** Create a file named `test_bp_v2.py` and paste this content:

```
print("line 1")
print("line 2")
print("line 3")
```

**Steps:**

1. Open `test_bp_v2.py`.
2. Tap the line number **2** on the left side of the editor.

**Expected:** A **red dot** appears in the gutter next to line 2, indicating a breakpoint. If no dot appears, the dot is the wrong color, or it appears on the wrong line, that is a FAIL.

3. Tap line number **2** again.

**Expected:** The red dot disappears (breakpoint removed). If it stays, that is a FAIL.

4. Tap line number **3**.

**Expected:** A red dot appears on line 3.

5. Tap line number **1**.

**Expected:** A red dot appears on line 1. You should now have dots on lines 1 and 3 (but not 2).

**Cleanup:** Yes — delete `test_bp_v2.py` after testing. If this test FAILS, do not proceed to Tests 89-91.

---

## Test 89 — Debug session (Python) — only if Test 88 PASSED

**File setup:** Create a file named `test_debug_v2.py` and paste this content:

```
print("start")
x = 1 + 2
print(f"result: {x}")
print("end")
```

**Steps:**

1. Open `test_debug_v2.py`.
2. Tap line number **2** to set a breakpoint (red dot should appear).
3. Go to the activity bar and tap the **Run and Debug** icon (play triangle with a bug icon, on the left sidebar).
4. Tap the **Start** or **Run** button in the debug panel.

**Expected:** The debug session starts and **pauses** at line 2 (the breakpoint). The variable inspector should show local variables (at minimum, you should see the debug panel update with session state). If the session doesn't start, doesn't pause, or crashes, that is a FAIL.

5. Tap the **Step Over** button (forward arrow or down-arrow icon).

**Expected:** Execution advances to line 3. The variable panel should update — `x` should now show `3`. If Step Over doesn't work or the app crashes, that is a FAIL.

**Cleanup:** Yes — delete `test_debug_v2.py` after testing.

---

## Test 90 — Debug restart button — only if Test 89 PASSED

**File setup:** None (continue from Test 89's session).

**Steps:**

1. While a debug session is running (from Test 89), look at the debug toolbar.
2. Find the **restart** button (green circular arrow or refresh icon).

**Expected:** A restart button exists. If no restart button is visible, that is a FAIL.

3. Tap the restart button.

**Expected:** The current debug session stops and a new one starts immediately. The new session should pause at the breakpoint (line 2) again. If the button does nothing, or the session stops without restarting, or the app crashes, that is a FAIL.

**Cleanup:** No

---

## Test 91 — Debug variable expansion — only if Test 90 PASSED

**File setup:** None (continue from Test 90's session, but modify the test file).

**Steps:**

1. Stop the debug session if running.
2. Open `test_debug_v2.py` and add this line after `print("start")`:

```
my_list = [1, 2, 3]
```

The file should now be:

```
print("start")
my_list = [1, 2, 3]
x = 1 + 2
print(f"result: {x}")
print("end")
```

3. Tap line number **3** to set a breakpoint on the `x = 1 + 2` line.
4. Start a new debug session and wait for it to pause at line 3.
5. Look at the **Variables** panel in the debug sidebar/panel.
6. Find `my_list` in the Variables panel.
7. Tap the **arrow** next to `my_list`.

**Expected:** The variable expands to show its child values: `0: 1`, `1: 2`, `2: 3` (list indices with values). If the arrow doesn't respond, doesn't expand, or no children appear, that is a FAIL.

8. Tap the arrow again to collapse it.

**Expected:** The children hide and the variable shows just its summary again.

**Cleanup:** Yes — delete `test_debug_v2.py` after testing.

---

## Test 95 — Project creation wizard (FEATURE REQUEST)

**File setup:** None needed.

**Steps:**

1. Go to the home screen (close or back out of any open project).
2. Tap the **plus button** or **FAB** (floating action button).
3. A menu should appear with options.

**Expected:** The menu offers **New local project** and **Clone from GitHub**.

4. Tap **New local project**.

**Expected:** A **project wizard** opens (not just a simple text dialog) with:
- A **project type selector** showing at least some of: Android, Flutter, React Native, Web, Node.js, Python, Empty.
- A **project name** input field.
- A **Create** or **Next** button.

5. Select **Python** as the type.
6. Type `test_wizard_project` as the name.
7. Tap **Create**.

**Expected:** The project is created and opens. A basic Python project structure should exist (at minimum, an empty project folder). If only a simple text input appeared instead of a full wizard with type selection, that is a FAIL.

**Cleanup:** Yes — delete `test_wizard_project` after testing.

---

## Test 114 — Replace in Files keyboard/typing

**File setup:** Create two files.

Create `test_replace_a.txt` and paste this content:

```
findme = 1
other = 2
```

Create `test_replace_b.txt` and paste this content:

```
findme = hello
something = world
```

**Steps:**

1. Tap the **Search** icon in the activity bar (magnifying glass on the left sidebar).
2. In the search input, type `findme`.
3. Results should show both files: `test_replace_a.txt` (line 1) and `test_replace_b.txt` (line 1).
4. Look for a **Replace** field or a replace toggle/amber-colored chip below or next to the search field.
5. Tap into the replace field and type `replaced`.
6. Tap **Replace All** (or an equivalent button).

**Expected:**
- Both files are updated: `findme` becomes `replaced` in both `test_replace_a.txt` and `test_replace_b.txt`.
- A confirmation message/snackbar appears stating how many replacements were made (e.g., "Replaced 2 occurrences in 2 files").
- If you cannot type in the replace field (keyboard doesn't open, text doesn't appear), that is a FAIL.
- If Replace All does nothing, that is a FAIL.

**Cleanup:** Yes — delete `test_replace_a.txt` and `test_replace_b.txt` after testing.

---

## Test 117 — Workspace file search

**File setup:** Create a file named `test_workspace_search.py` and paste this content:

```
def unique_search_target():
    return "found me"
```

**Steps:**

1. Tap the **Search** icon in the activity bar (magnifying glass, left sidebar).
2. In the search input, type `unique_search_target`.

**Expected:** Results appear showing `test_workspace_search.py` with line 1 highlighted. The result should show the file name and the matching line content. If no results appear despite the word existing in the file, that is a FAIL.

3. Tap the result.

**Expected:** The file opens in the editor and the cursor is placed on line 1 (or the editor scrolls to the matching line). If tapping does nothing, that is a FAIL.

**Cleanup:** Yes — delete `test_workspace_search.py` after testing.

---

## Test 118 — Symbol search / Go to Symbol

**File setup:** Create a file named `test_symbol_search.py` and paste this content:

```
def alpha_function():
    pass
def beta_function():
    pass
class GammaClass:
    def method_inside(self):
        pass
```

**Steps:**

1. Open `test_symbol_search.py`.
2. Wait a few seconds for LSP to index symbols.
3. Tap the three-dot overflow menu (top-right).
4. Tap **Go** then **Go to Symbol** (or look for a symbol search icon in the activity bar).

**Expected:** A symbol search dialog/input appears. If no such option exists, that is a FAIL.

5. Type `beta` in the symbol search field.

**Expected:** A list appears showing `beta_function` (from line 2 of the current file or from across the workspace). If the list is empty despite the function existing, that is a FAIL.

6. Tap `beta_function` in the results.

**Expected:** The cursor jumps to line 2 in the editor (where `beta_function` is defined). If tapping does nothing, that is a FAIL.

**Cleanup:** Yes — delete `test_symbol_search.py` after testing.

---

## Test 124 — Diagnostics Report menu entry

**File setup:** None needed.

**Steps:**

1. Tap the three-dot overflow menu (top-right).
2. Scroll through the menu items and look for **Diagnostics Report**.

**Expected:** A "Diagnostics Report" entry is visible in the menu. If it is missing, that is a FAIL.

3. Tap **Diagnostics Report**.

**Expected:** A report is generated containing device info, crash logs (if any), and terminal output. A share sheet or dialog should appear allowing you to share or save the report. If nothing happens or the app crashes, that is a FAIL.

**Cleanup:** No

---

## Test 125 — Connectors Hub menu entry

**File setup:** None needed.

**Steps:**

1. Tap the three-dot overflow menu (top-right).
2. Look for **Connectors** (or "Connectors Hub") in the menu items.

**Expected:** A "Connectors" entry is visible in the menu. If it is missing, that is a FAIL.

3. Tap **Connectors**.

**Expected:** A connectors hub sheet/panel appears showing available third-party services (e.g., Google, Slack, GitHub, etc.) with connect/disconnect buttons. If the sheet doesn't appear, is empty, or the app crashes, that is a FAIL.

4. Close the connectors hub.

**Cleanup:** No

---

## Test 128 — Recent search history

**File setup:** Create a file named `test_search_history.py` and paste this content:

```
def history_test_func():
    return "history"
```

**Steps:**

1. Tap the **Search** icon in the activity bar (magnifying glass).
2. Type `history_test_func` in the search field and wait for results.
3. Close the search panel (tap back, tap the Search icon again, or tap outside).
4. Reopen the search panel (tap the Search icon again).

**Expected:** The previous search query `history_test_func` should appear in a **search history** section or be pre-filled in the search field. If the search field is completely blank with no history visible anywhere, that is a FAIL.

5. Tap the history entry (if shown as a separate list item).

**Expected:** The search runs again with the previous query. If tapping the history entry does nothing, that is a PARTIAL.

**Cleanup:** Yes — delete `test_search_history.py` after testing.

---

## Test 130 — YouTube video playback in preview browser

**File setup:** None needed.

**Steps:**

1. Open any project.
2. Tap the **Preview** tab in the bottom panel (or open the preview browser).
3. Look for an **address bar** or URL input in the preview browser.
4. Type or paste this URL: `https://www.youtube.com/watch?v=dQw4w9WgXcQ`
5. Tap **Go** (or the enter/arrow button).

**Expected:** The YouTube page loads. The video should begin playing with **video** (not just audio). You should see the video frame/visual content. If:
- Only audio plays with a black screen, that is a FAIL.
- The page loads but the video doesn't play at all, that is a FAIL.
- A sign-in prompt or "insecure browser" warning blocks playback, that is a FAIL.
- The video plays with both audio and video, that is a PASS.

6. Tap the video to see playback controls (play/pause, seek bar).

**Expected:** Playback controls appear and are functional. If controls don't appear or aren't responsive, that is a PARTIAL.

**Cleanup:** No

---

## Summary checklist

Copy this section and fill in your results:

| Test | Category | Result (PASS/FAIL/PARTIAL) | Notes |
|------|----------|--------------------------|-------|
| 9 | Failed | | |
| 12 | Partial | | |
| 15 | Failed | | |
| 16 | Failed | | |
| 17 | Failed | | |
| 18 | Partial | | |
| 19 | Failed | | |
| 30 | Partial | | |
| 32 | Partial | | |
| 33 | Partial | | |
| 34 | Failed | | |
| 35 | Failed | | |
| 38 | Failed | | |
| 40 | Partial | | |
| 43 | Failed | | |
| 46 and 113 | Feature Request | | |
| 47 | Partial | | |
| 69 | Partial | | |
| 70 | Failed | | |
| 72 | Failed | | |
| 78 | Partial | | |
| 82 | Failed | | |
| 84 | Feature Request | | |
| 88 | Failed | | |
| 89 | Failed (gated by 88) | | |
| 90 | Failed (gated by 89) | | |
| 91 | Failed (gated by 90) | | |
| 95 | Feature Request | | |
| 114 | Failed | | |
| 117 | Failed | | |
| 118 | Failed | | |
| 124 | Failed | | |
| 125 | Failed | | |
| 128 | Failed | | |
| 130 | Failed | | |

---

End of retest guide. Report results per item and I'll fix any that fail.
