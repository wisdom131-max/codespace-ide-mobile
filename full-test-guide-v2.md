# codespace-ide-mobile — Full App Test Guide v2 (160 tests)

This is a complete, fresh test guide covering **every feature in the current build**. For each test that needs a file, **copy the exact block** and paste it — do not type it manually.

**Legend:**
- 🟢 **SAFE** — just viewing/tapping, no files created or modified
- 🟡 **MODIFIES** — creates, edits, or deletes a file; clean up after if noted
- 🔴 **SYSTEM** — changes app/project settings; you may want to revert after

---

## What's changed since the last full test round

- **Extra keys bar** above the editor keyboard has been rebuilt with full symbol set + Tab + Esc
- **Find bar** in editor has been overhauled (dark theme, next/prev, replace)
- **Preview pane** now supports HTML, SVG, Markdown, pinch-to-zoom, and resize
- **Notification system** has been restructured (floating card + bell icon + drawer)
- **Git auto-identity** setup (ensureIdentity) now runs before commits
- **Connectors Hub** and **Diagnostics Report** added to File menu
- **Trash/recycle bin** for deleted files
- **Project Wizard** with project type selection (Step 1: type grid, Step 2: name + create)
- **DAP-based debugger** with breakpoint verification, variable expansion, step controls
- **Formatter auto-install** for Python (black/autopep8) and other languages
- **Multi-cursor** editing (Select Next Occurrence, Add Cursor Above/Below)
- **Code Lens** annotations above function declarations
- **Snippets** for Kotlin and Python (Tab expansion)
- **Outline panel** in the activity bar
- **Symbol search** (Go to Symbol)
- **File search** (Go to File / command palette)
- **Find in Files** with replace
- **Recent search history**
- **Zen Mode** with draggable exit button
- **Pin Lock** security feature
- **SSH Manager** for remote connections
- **Repo Browser** for GitHub cloning
- **MCP panel** in Extensions sidebar
- **TODO Explorer** and **Test Explorer** bottom panels
- **Code Analysis** panel (dead code, duplicates, complexity)
- **Build Panel** and **Build History**
- **Toolchain Panel**
- **Artifact Panel** and **Download Center**
- **Cloud Backup** panel
- **Variable Inspector** panel
- **Logcat** viewer
- **Split Terminal**
- **Ports** panel
- **Image Generation** (AI, from Explorer context menu)
- **Video/Audio player** for media files
- **Hex/DEX/ELF/Smali/SQLite/PDF viewers** for binary files
- **Timeline** panel
- **Shell History Search**

---

## Section 1: App Launch & Home Screen

## Test 1 — App launches without crashing 🟢

**File:** none.

**Steps:**
1. Open the app from your home screen.
2. Watch the screen as the app loads.

**Expected:** The app opens and shows either the home screen with project list or the last open project. If a crash dialog appears, that is a FAIL.

---

## Test 2 — Safe mode on repeated crashes 🟢

**File:** none.

**Steps:**
1. Force-close the app 3 times in a row from Recent Apps.
2. On the 4th launch, watch for a dialog.

**Expected:** A dialog with Continue or Enter Safe Mode. If the app crashes with no dialog, FAIL. Tap Continue to proceed.

---

## Test 3 — Home screen shows project list 🟢

**Steps:**
1. Tap back to go to the home screen.
2. Look at the screen — project cards should be shown.

**Expected:** Project cards with names appear. If no projects exist, a prompt "Tap 'New project' to create one." appears. Blank screen or crash = FAIL.

---

## Test 4 — New project FAB menu 🟢

**Steps:**
1. On the home screen, tap the **New project** button.
2. A dropdown menu should appear.

**Expected:** Two options: **New local project** and **Clone from GitHub**. Missing options or no menu = FAIL.

---

## Test 5 — Project creation wizard 🔴

**Steps:**
1. Tap **New project** → **New local project**.
2. A wizard dialog should open with a project type grid (Android, Flutter, React Native, Web, Node.js, Python, Empty).
3. Tap **Empty** (or Python).
4. Enter `test_wizard_proj` as the name.
5. Tap **Create**.

**Expected:** Project is created and the workspace opens. If only a simple text dialog appears without type selection, FAIL.

**Cleanup:** Delete `test_wizard_proj` from the home screen (long-press → Delete).

---

## Test 6 — Clone from GitHub menu 🟢

**Steps:**
1. Tap **New project** → **Clone from GitHub**.
2. A repo browser sheet should appear.

**Expected:** GitHub repository browser with repos or URL input. Nothing appears or crash = FAIL. Close without cloning.

---

## Test 7 — Open existing project 🟢

**Steps:**
1. On the home screen, tap any existing project card.

**Expected:** The app opens the project workspace with editor area, file explorer, and top bar. Crash or blank = FAIL.

---

## Test 8 — Settings screen from home 🟢

**Steps:**
1. On the home screen, look for a settings/gear icon.
2. Tap it.

**Expected:** Settings screen with Appearance (theme toggle), Security (Pin Lock), and other options. No settings screen = FAIL.

---

## Test 9 — Dark/light theme toggle 🔴

**Steps:**
1. In Settings → Appearance, tap the dark/light theme toggle.
2. Observe the screen.

**Expected:** Theme switches between dark and light immediately. No change = FAIL. Toggle back to preferred setting.

---

## Test 10 — Pin Lock setup 🔴

**Steps:**
1. In Settings → Security, find Pin Lock.
2. Tap **Set Pin**.
3. Enter `1234` and confirm.
4. Close and reopen the app.

**Expected:** A PIN entry screen appears on reopen. Enter `1234` to unlock. No PIN screen = FAIL.

**Cleanup:** Disable Pin Lock after testing.

---

## Section 2: Terminal

## Test 11 — Terminal native PTY shell 🟢

**Steps:**
1. Open a project. Tap the **Terminal** tab at the bottom.
2. Wait 3 seconds for the shell to start.
3. Type `echo hello` and press Enter.

**Expected:** `hello` appears on the next line. Blank screen after 5s or permission error = FAIL.

---

## Test 12 — Terminal multi-tab 🟢

**Steps:**
1. In Terminal, tap the **plus** icon to create a second tab.
2. Type `pwd` in the first tab.
3. Switch to the second tab.
4. Type `whoami`.

**Expected:** Each tab has its own independent session. Both tabs share same output or second is blank = FAIL.

---

## Test 13 — Terminal session restore 🟢

**Steps:**
1. Create 2 terminal tabs with commands.
2. Force-close the app.
3. Reopen and open the same project.

**Expected:** After ~8 seconds, terminal tabs reappear with names preserved. Zero tabs or crash on restore = FAIL.

---

## Test 14 — Terminal extra keys row 🟢

**Steps:**
1. In Terminal, look at the extra keys row above the keyboard (Tab, ESC, CTRL, arrows, pipe).
2. Tap **ESC** — sends escape signal.
3. Tap a **left arrow** — cursor moves left.
4. Swipe left to reveal more keys.

**Expected:** Extra keys are visible and functional. Swiping reveals more keys. Empty or non-functional = FAIL.

---

## Test 15 — Terminal color scheme picker 🟢

**Steps:**
1. In Terminal, tap the three-dot overflow menu.
2. Tap **Color Scheme**.
3. Select **Dracula**.

**Expected:** Terminal colors change to Dracula theme (dark purple). No change or no list = FAIL. Switch back afterward.

---

## Test 16 — Terminal file creation 🟡

**Steps:**
1. In the Terminal, paste: `echo "test content" > test_terminal_file.txt`
2. Press Enter.
3. Switch to file explorer and look for the file.

**Expected:** File appears in explorer and opens to show `test content`. File doesn't appear = FAIL.

**Cleanup:** Delete `test_terminal_file.txt`.

---

## Test 17 — Terminal large file handling 🟡

**Steps:**
1. In Terminal, paste: `yes "This is a test line for large file handling" | head -5000 > test_large.txt`
2. Press Enter.
3. Open `test_large.txt` from the file explorer.
4. Scroll up and down.

**Expected:** File opens with 5000 lines, scrolls smoothly. Freeze >3s or crash = FAIL.

**Cleanup:** Delete `test_large.txt`.

---

## Test 18 — Split terminal 🟢

**Steps:**
1. In the bottom panel, tap the tab selector and choose **Split**.
2. Two terminal panes should appear.
3. Type `echo left` in the first, `echo right` in the second.

**Expected:** Each pane has its own session. No split, shared session, or crash = FAIL.

---

## Test 19 — Terminal clear 🟢

**Steps:**
1. Run a few commands in Terminal.
2. Tap the three-dot overflow menu → **Clear**.

**Expected:** Terminal output is cleared, fresh prompt. Not cleared = FAIL.

---

## Test 20 — Shell history search 🟢

**Steps:**
1. Type several commands (ls, pwd, whoami).
2. Tap the three-dot overflow menu.
3. Look for **Shell History** or a search icon.
4. Tap it.

**Expected:** A search overlay showing recent commands. Doesn't appear = FAIL. Close after.

---

## Section 3: File Explorer

## Test 21 — File explorer tree view 🟢

**Steps:**
1. Tap the **Explorer** icon in the activity bar (left sidebar).

**Expected:** File tree showing project files and folders. Empty or no tree = FAIL.

---

## Test 22 — Open file from explorer 🟢

**Steps:**
1. In the explorer tree, tap any file.

**Expected:** File opens in the editor with a new tab. Doesn't open or crash = FAIL.

---

## Test 23 — Create new file from menu 🟡

**Steps:**
1. Tap three-dot menu → **File** → **New Text File**.
2. Type `hello` in the untitled tab.
3. Tap **Save**. Type `test_newfile.txt` as filename.
4. Tap **Save/OK**.
5. Close the tab.
6. Look at the explorer tree.

**Expected:** `test_newfile.txt` appears in the explorer after closing the tab. Not visible = FAIL.

**Cleanup:** Delete `test_newfile.txt`.

---

## Test 24 — Create new folder from menu 🟡

**Steps:**
1. Tap three-dot menu → **File** → **New Folder**.
2. Enter `test_folder`.
3. Confirm.
4. Look at the explorer tree.

**Expected:** `test_folder` appears in the tree. Not created = FAIL.

**Cleanup:** Delete `test_folder`.

---

## Test 25 — File long-press context menu 🟢

**Steps:**
1. Long-press any file in the explorer.

**Expected:** Context menu with Delete, Rename, Copy, Move, etc. No menu = FAIL.

---

## Test 26 — File rename 🟡

**Steps:**
1. Create `test_rename.txt` (terminal: `echo "rename test" > test_rename.txt`).
2. Long-press it in the explorer.
3. Tap **Rename**. Type `test_renamed.txt`. Confirm.

**Expected:** File renamed in the explorer. Old name persists or rename fails = FAIL.

**Cleanup:** Delete `test_renamed.txt`.

---

## Test 27 — Delete to trash 🟡

**Steps:**
1. Create `test_trash.txt` (terminal: `echo "trash test" > test_trash.txt`).
2. Long-press it in the explorer.
3. Tap **Delete**. Confirm if dialog appears.

**Expected:** File moves to trash, disappears from explorer. Permanently deleted with no trash = FAIL.

---

## Test 28 — Trash / Recycle Bin view 🟢

**Steps:**
1. In the explorer overflow menu, tap **Recycle Bin** or **Trash**.

**Expected:** List of deleted files including `test_trash.txt`. Empty or no view = FAIL.

---

## Test 29 — Restore from trash 🟡

**Steps:**
1. In the trash view, find `test_trash.txt`.
2. Tap **Restore**.

**Expected:** File reappears in the explorer at its original location. Restore fails = FAIL.

**Cleanup:** Delete `test_trash.txt` after.

---

## Test 30 — Permanent delete from trash 🟡

**Steps:**
1. Create `test_perm_delete.txt` (terminal: `echo "perm" > test_perm_delete.txt`).
2. Delete to trash (long-press → Delete).
3. Open the Trash view.
4. Long-press `test_perm_delete.txt` in the trash list.
5. Tap **Delete forever** (or permanent delete).

**Expected:** Item is actually deleted from trash. Reopen trash — it should be gone. If it reappears, FAIL.

---

## Test 31 — File info dialog 🟢

**Steps:**
1. Long-press any file in the explorer.
2. Look for **File Info** or **Properties**.
3. Tap it.

**Expected:** Dialog with file name, size, path, modified date. No dialog = FAIL.

---

## Section 4: Editor — Basics

## Test 32 — Syntax highlighting 🟡

**File setup:** Create `test_syntax.py` and paste:

```
def hello_world():
    print("Hello, World!")
    x = 42
    return x
```

**Steps:**
1. Open `test_syntax.py`.
2. Look at text coloring.

**Expected:** Keywords (`def`, `return`, `print`) colored differently from strings and numbers. All one color = FAIL.

**Cleanup:** Delete `test_syntax.py`.

---

## Test 33 — Line numbers 🟢

**Steps:**
1. Open any file. Look at the left side of the editor.

**Expected:** Line numbers visible (1, 2, 3...). No numbers = FAIL.

---

## Test 34 — Font size adjustment 🔴

**Steps:**
1. Open any file. Go to In-Project Settings.
2. Find font size setting. Change it to a larger value.
3. Return to the editor.

**Expected:** Font size increases. No change = FAIL. Change back afterward.

---

## Test 35 — Word wrap toggle 🔴

**Steps:**
1. Open a file with a very long line.
2. In Settings, find **Word wrap**. Turn it ON.
3. Return to the editor.

**Expected:** Long lines wrap instead of horizontal scroll. Still scrolls = FAIL. Toggle back off.

---

## Test 36 — Minimap toggle 🔴

**Steps:**
1. In Settings, find **Minimap**. Turn it OFF.
2. Return to the editor.

**Expected:** Minimap (code overview strip) disappears. Still visible = FAIL. Turn back on if desired.

---

## Test 37 — Editor extra keys bar 🟢

**Steps:**
1. Open any file so keyboard appears.
2. Look at the key bar above the keyboard.
3. Verify keys: `{`, `}`, `[`, `]`, `(`, `)`, `=`, `+`, `-`, `*`, `/`, `:`, `;`, `|`, `Tab`, `Esc`.
4. Tap **Tab** — inserts tab or triggers snippet.
5. Tap **Esc** — no crash.
6. Swipe left for more keys.

**Expected:** All keys visible and tappable. Swiping reveals more. Empty/missing/non-functional = FAIL.

---

## Test 38 — Snippet Tab expansion (Python) 🟡

**File setup:** Create empty file `test_snippet_py.py`.

**Steps:**
1. Open `test_snippet_py.py`.
2. Type `def`.
3. Press **Tab** on the extra-keys bar.

**Expected:** `def` expands to `def name():` with cursor at `name`. Nothing happens = FAIL.

**Cleanup:** Delete `test_snippet_py.py`.

---

## Test 39 — Snippet Tab expansion (Kotlin) 🟡

**File setup:** Create empty file `test_snippet_kt.kt`.

**Steps:**
1. Open `test_snippet_kt.kt`.
2. Type `fun`.
3. Press **Tab** on the extra-keys bar.

**Expected:** `fun` expands to `fun name() { }` with cursor at `name`. Nothing happens = FAIL.

**Cleanup:** Delete `test_snippet_kt.kt`.

---

## Test 40 — Auto-indent 🟡

**File setup:** Create `test_indent.py` and paste:

```
def foo():
    x = 1
```

**Steps:**
1. Open `test_indent.py`.
2. Place cursor at end of line 2. Press Enter.

**Expected:** New line is indented to 4 spaces (matching line 2). Starts at column 0 = FAIL.

**Cleanup:** Delete `test_indent.py`.

---

## Test 41 — Bracket auto-close 🟡

**File setup:** Create empty file `test_brackets.py`.

**Steps:**
1. Open `test_brackets.py`.
2. Type `print(`.

**Expected:** Closing `)` is auto-inserted. Cursor between `(` and `)`. No closing bracket = FAIL. Test `{` and `[` too.

**Cleanup:** Delete `test_brackets.py`.

---

## Test 42 — Cursor blink — Solid 🔴

**Steps:**
1. In Settings, change **Cursor Blink Style** to **Solid**.
2. Return to the editor.

**Expected:** Cursor is solid, non-blinking, continuously visible. Invisible or still blinking = FAIL. Set back to Blink.

---

## Test 43 — Cursor blink — Expand 🔴

**Steps:**
1. Change **Cursor Blink Style** to **Expand**.
2. Return to the editor.

**Expected:** Cursor is visible with expand style. Invisible = FAIL. Set back to Blink.

---

## Test 44 — Cursor mode (In-App vs System) 🔴

**Steps:**
1. In Settings, find **Cursor Mode**.
2. Change to **System**.
3. Return to editor, tap to place cursor.

**Expected:** Cursor behavior changes. No change or crash = FAIL. Set back to In-App.

---

## Section 5: Editor — Find & Replace

## Test 45 — Find in editor 🟡

**File setup:** Create `test_find.py` and paste:

```
value = 10
value = 20
value = 30
```

**Steps:**
1. Open `test_find.py`.
2. Tap Find/Replace icon or Edit → Find.
3. Type `value`.
4. Tap **Next** arrow (↓). Tap again. Tap **Previous** (↑).

**Expected:** All 3 matches highlighted. Next/Previous navigates and scrolls. Not highlighted or navigation broken = FAIL.

**Cleanup:** Delete `test_find.py`.

---

## Test 46 — Replace in editor 🟡

**File setup:** Create `test_replace.py` and paste:

```
value = 10
value = 20
value = 30
```

**Steps:**
1. Open `test_replace.py`.
2. Open Find/Replace bar.
3. Find: `value`. Toggle replace mode.
4. Replace: `number`. Tap **Replace All**.

**Expected:** All `value` → `number`. File reads `number = 10`, `number = 20`, `number = 30`. Replace broken = FAIL.

**Cleanup:** Delete `test_replace.py`.

---

## Test 47 — Find in Files 🟡

**File setup:** Create `test_fif_a.txt` with:
```
searchtarget = 1
other = 2
```
Create `test_fif_b.txt` with:
```
searchtarget = hello
something = world
```

**Steps:**
1. Tap **Search** icon in activity bar.
2. Type `searchtarget`.
3. Tap a result.

**Expected:** Both files show in results with matching lines. Tapping opens the file. No results = FAIL.

**Cleanup:** Delete both files.

---

## Test 48 — Replace in Files 🟡

**File setup:** Create `test_rif_a.txt` with:
```
findme = 1
other = 2
```
Create `test_rif_b.txt` with:
```
findme = hello
something = world
```

**Steps:**
1. Tap **Search** icon. Type `findme`.
2. Find the Replace field/toggle. Tap into it and type `replaced`.
3. Tap **Replace All**.

**Expected:** Both files updated. `findme` → `replaced` in both. Confirmation snackbar appears. Can't type in replace field or nothing happens = FAIL.

**Cleanup:** Delete both files.

---

## Test 49 — Recent search history 🟢

**Steps:**
1. Tap **Search** icon. Type any word and search.
2. Close the search panel.
3. Reopen it.

**Expected:** Previous query appears in history or is pre-filled. Blank with no history = FAIL.

---

## Section 6: Editor — Multi-cursor

## Test 50 — Select Next Occurrence 🟡

**File setup:** Create `test_multi.py` and paste:

```
apple = 1
apple = 2
apple = 3
```

**Steps:**
1. Open `test_multi.py`.
2. Long-press `apple` on line 1.
3. Tap **Select Next Occurrence**. Tap again. Tap a third time.

**Expected:** 3 cursors, one per line after 3 taps. Only 1 cursor = FAIL.

**Cleanup:** Delete `test_multi.py`.

---

## Test 51 — Multi-cursor typing 🟡

**File setup:** Same `test_multi.py`, or recreate.

**Steps:**
1. Select all 3 `apple` occurrences.
2. Type `x`.

**Expected:** All 3 lines change simultaneously to `xpple = ...`. Only one changes or desync = FAIL.

**Cleanup:** Delete `test_multi.py`.

---

## Test 52 — Add Cursor Above/Below 🟡

**File setup:** Create `test_cursor_ab.py` and paste:

```
line one
line two
line three
```

**Steps:**
1. Open it. Place cursor on line 2.
2. Find **Add Cursor Below** (context menu or Selection menu).
3. Tap it. Type `# `.

**Expected:** Second cursor on line 3. Both lines get `# ` prefix. Only one cursor or no option = FAIL/PARTIAL.

**Cleanup:** Delete `test_cursor_ab.py`.

---

## Section 7: Editor — Navigation

## Test 53 — Go to Line 🟡

**File setup:** Create `test_gotoline.py` and paste:

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
1. Open it. Tap three-dot menu → **Go** → **Go to Line**.
2. Type `7`. Tap OK.

**Expected:** Scrolls to line 7, line 7 highlighted, cursor on line 7. Wrong line or no highlight = FAIL. `999` should clamp to line 10.

**Cleanup:** Delete `test_gotoline.py`.

---

## Test 54 — Command Palette / Go to File 🟢

**Steps:**
1. Tap the command field in the top bar (rounded rectangle with search icon).
2. Type the name of any file in your project.

**Expected:** Matching files appear. Tapping opens the file. No palette or no files = FAIL.

---

## Test 55 — Symbol search / Go to Symbol 🟡

**File setup:** Create `test_symbols.py` and paste:

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
1. Open it. Wait for LSP.
2. Tap three-dot menu → **Go** → **Go to Symbol**.
3. Type `beta`.

**Expected:** List shows `beta_function`. Tap it → cursor jumps to line 2. Empty list or no option = FAIL.

**Cleanup:** Delete `test_symbols.py`.

---

## Test 56 — Document Symbol Outline 🟡

**File setup:** Create `test_outline.py` and paste:

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
1. Open it. Wait for LSP.
2. Tap the **Outline** icon in the activity bar.
3. Tap `method_two`.

**Expected:** Tree shows correct structure. Tapping scrolls to line 4, cursor placed, line highlighted. Empty tree or no cursor move = FAIL.

**Cleanup:** Delete `test_outline.py`.

---

## Section 8: LSP Features

## Test 57 — LSP autocomplete 🟡

**File setup:** Create `test_autocomplete.py` and paste:

```
import o
```

**Steps:**
1. Open it. Wait 5-10s for LSP.
2. Place cursor after `o` in `import o`.

**Expected:** Dropdown with `os`, `os.path`, `operator`, etc. No dropdown (with LSP enabled) = FAIL. Tap `os` to complete.

**Cleanup:** Delete `test_autocomplete.py`.

---

## Test 58 — LSP hover docs 🟡

**File setup:** Create `test_hover.py` and paste:

```
import os
os.getcwd()
```

**Steps:**
1. Open it. Wait for LSP.
2. Long-press `getcwd` on line 2.

**Expected:** Popup with documentation for `os.getcwd()`. No popup after 15s = FAIL.

**Cleanup:** Delete `test_hover.py`.

---

## Test 59 — Go to Definition (same file) 🟡

**File setup:** Create `test_def_same.py` and paste:

```
def my_function():
    return 42
result = my_function()
```

**Steps:**
1. Open it. Wait for LSP.
2. Long-press `my_function` on line 3.
3. Tap **Go to Definition**.

**Expected:** Cursor jumps to line 1. Nothing happens = FAIL.

**Cleanup:** Delete `test_def_same.py`.

---

## Test 60 — Go to Definition (cross-file) 🟡

**File setup:** Create `utils.py` with:
```
def helper_function():
    return 42
```
Create `main.py` with:
```
from utils import helper_function
result = helper_function()
```

**Steps:**
1. Open `main.py`. Wait for LSP.
2. Long-press `helper_function` on line 2.
3. Tap **Go to Definition**.

**Expected:** Navigates to `utils.py`, cursor on line 1. Nothing or only same file searched = FAIL.

**Cleanup:** Delete both files.

---

## Test 61 — Find References 🟡

**File setup:** Create `test_refs.py` and paste:

```
def my_func():
    return 1
a = my_func()
b = my_func()
```

**Steps:**
1. Open it. Wait for LSP.
2. Long-press `my_func` on line 1.
3. Tap **Find References**.

**Expected:** List/peek shows all references (lines 1, 2, 3). No references = FAIL.

**Cleanup:** Delete `test_refs.py`.

---

## Test 62 — Find References peek panel 🟡

**File setup:** Same as Test 61, or recreate `test_refs.py`.

**Steps:**
1. Long-press `my_func` → Find References.
2. If peek panel appears, tap a reference entry.

**Expected:** Tapping navigates to that location. No peek or tapping doesn't navigate = FAIL.

**Cleanup:** Delete `test_refs.py`.

---

## Test 63 — Wavy underline for errors 🟡

**File setup:** Create `test_lint.py` and paste:

```
def foo():
    undefined_variable_here = 1
    return undefined_variable_here
```

**Steps:**
1. Open it. Wait 5-10s for LSP.
2. Look at line 2 under `undefined_variable_here`.

**Expected:** Wavy underline (red/yellow). No underline after 15s (with LSP enabled) = FAIL.

**Cleanup:** Delete `test_lint.py`.

---

## Test 64 — Problems panel 🟡

**File setup:** Create `test_problems.py` and paste:

```
def foo():
    x = undefined_thing
    return x
```

**Steps:**
1. Open it. Wait for LSP.
2. Open bottom panel → **Problems** tab.
3. Tap the error entry.

**Expected:** Error listed. Tapping moves cursor to line 2 and scrolls. Empty panel or no cursor move = FAIL.

**Cleanup:** Delete `test_problems.py`.

---

## Test 65 — Lightbulb quick-fix menu 🟡

**File setup:** Create `test_lightbulb.py` and paste:

```
def foo():
    x = undefined_var_here
    return x
```

**Steps:**
1. Open it. Wait for lint underlines on line 2.
2. Tap the **lightbulb icon** in the gutter near line 2.

**Expected:** Menu with **Fix with AI**, Explain Code, Generate Documentation, etc. No lightbulb after 15s = FAIL.

**Cleanup:** Delete `test_lightbulb.py`.

---

## Test 66 — Fix with AI from lightbulb 🟡

**File setup:** Same as Test 65, or recreate.

**Steps:**
1. Tap lightbulb on line 2.
2. Tap **Fix with AI**.

**Expected:** AI Copilot chat panel opens with a pre-filled fix prompt. Doesn't open = FAIL.

**Cleanup:** Delete `test_lightbulb.py`.

---

## Test 67 — Code Lens 🟡

**File setup:** Create `test_lens.kt` and paste:

```
fun myFunction() {
    println("hello")
}
```

**Steps:**
1. Open it. Wait 5-10s for Kotlin LSP.
2. Look at end of line 1.

**Expected:** Small annotation like `1 reference` or `0 references`. Nothing after 15s = FAIL.

**Cleanup:** Delete `test_lens.kt`.

---

## Test 68 — Inlay Hints 🟡

**File setup:** Create `test_inlay.kt` and paste:

```
val x = true
fun isReady() = x
```

**Steps:**
1. Open it. Wait a few seconds.
2. Look at line 1 around `true`.

**Expected:** Inline hints like `: Boolean` type annotations. If inlay hints enabled and none appear = FAIL.

**Cleanup:** Delete `test_inlay.kt`.

---

## Test 69 — Signature help 🟡

**File setup:** Create `test_sig.py` and paste:

```
print(
```

**Steps:**
1. Open it. Wait for LSP.
2. Place cursor inside `print(`.

**Expected:** Popup showing `print()` signature with parameter hints. No popup after 15s = FAIL.

**Cleanup:** Delete `test_sig.py`.

---

## Test 70 — Master LSP toggle 🔴

**Steps:**
1. In Settings, find **Enable LSP Servers**. Turn OFF.
2. Open a Python file with undefined variable.

**Expected:** No squiggles, no hover, no LSP completions. LSP continues = FAIL.

3. Turn LSP ON. Return to the Python file.

**Expected:** Within 5-10s, LSP resumes. Doesn't restore = FAIL.

---

## Test 71 — LSP servers list 🟢

**Steps:**
1. In Settings, find **LSP Servers** section. Tap it.

**Expected:** List of installed/available LSP servers (pylsp, pyright, typescript-language-server, etc.). Empty or no section = FAIL.

---

## Test 72 — Rename symbol 🟡

**File setup:** Create `test_rename_lsp.py` and paste:

```
def my_function():
    return 1
result = my_function()
```

**Steps:**
1. Open it. Wait for LSP.
2. Long-press `my_function` on line 1.
3. Tap **Rename Symbol**. Type `renamed_func`. Confirm.

**Expected:** All occurrences (lines 1, 3) renamed. Only one changes or fails = FAIL.

**Cleanup:** Delete `test_rename_lsp.py`.

---

## Test 73 — Sticky scroll 🟢

**Steps:**
1. Open a file with nested functions/classes (30+ lines).
2. Scroll down past a function header.

**Expected:** Current scope header pinned at top while scrolling. Nothing pinned = FAIL. (Requires Sticky Scroll enabled.)

---

## Test 74 — Error lens 🟢

**Steps:**
1. Open a Python file with an error.
2. Wait for LSP to show the error.
3. Look at the end of the error line.

**Expected:** Inline error message at end of line (not just underline). Only underline with no text = FAIL. (Requires Error Lens enabled.)

---

## Section 9: Formatting

## Test 75 — Format Document (Python) 🟡

**File setup:** Create `test_format.py` and paste:

```
def  foo( ):
    x=1
    y    =2
    return x+y
```

**Steps:**
1. Open it. Tap **Format** button in editor toolbar.

**Expected:** Formatter auto-installs if needed, reformats: `def foo():`, `x = 1`, `y = 2`, `return x + y`. "Not installed" error requiring manual install = FAIL.

**Cleanup:** Delete `test_format.py`.

---

## Test 76 — Format on Save 🟡

**File setup:** Create `test_format_save.py` and paste:

```
def  bar( ):
    a=1
    return a
```

**Steps:**
1. In Settings → Formatting, enable **Format on Save**.
2. Open the file. Tap **Save**.

**Expected:** File saves with spacing normalized. Saves without formatting = FAIL.

**Cleanup:** Delete `test_format_save.py`. Disable Format on Save if not desired.

---

## Test 77 — Formatter selection 🔴

**Steps:**
1. In Settings → Formatting, find formatter dropdown for Python.
2. Change from `black` to `autopep8`.
3. Open a Python file with bad spacing. Tap Format.

**Expected:** Selected formatter is used. No dropdown or no effect = FAIL.

---

## Section 10: Git / Source Control

## Test 78 — Source Control panel 🟢

**Steps:**
1. Tap the **Source Control** icon in the activity bar (branch/graph icon).

**Expected:** Panel shows git status (branch name, staged/unstaged). Blank or no panel = FAIL. (Need git repo — `git init` in terminal if none.)

---

## Test 79 — Git stage 🟡

**Steps:**
1. Modify a file (add a comment line).
2. Tap Source Control icon. Find the file in **Changes** section.
3. Tap the **+** icon next to it.

**Expected:** File moves to **Staged** section. Plus icon does nothing or error = FAIL.

---

## Test 80 — Git unstage 🟡

**Steps:**
1. In Source Control, find the staged file.
2. Tap the **–** icon next to it.

**Expected:** File moves back from Staged to Changes. Unstage fails with error = FAIL.

---

## Test 81 — Git commit 🟡

**Steps:**
1. Stage a modified file.
2. Type a commit message in the input field.
3. Tap **Commit**.

**Expected:** Commit succeeds. "git author identity not set" error or any failure = FAIL.

---

## Test 82 — Git diff view 🟡

**Steps:**
1. Modify a file (add a new line).
2. In Source Control, tap the file name (not the + icon).

**Expected:** Diff view opens showing changes (additions green, deletions red). No diff = FAIL.

---

## Test 83 — Git branch list 🟢

**Steps:**
1. In Source Control, tap the branch name at the top.

**Expected:** List of branches appears. No list or branch not shown = FAIL.

---

## Test 84 — Git checkout branch 🟡

**Steps:**
1. Create a branch first if needed (terminal: `git branch test-branch`).
2. In branch list, tap `test-branch`.

**Expected:** Active branch changes. Branch name updates. Checkout fails = FAIL.

**Cleanup:** Switch back to original branch. Delete `test-branch` if created.

---

## Test 85 — Git push 🟡

**Steps:**
1. Make a commit. In Source Control, look for **Push** or **Sync** button.
2. Tap it.

**Expected:** Push succeeds (if remote configured). No push button = PARTIAL. Push fails with no remote = expected (not FAIL).

---

## Test 86 — Git pull 🟡

**Steps:**
1. In Source Control, look for **Pull** option (may be in overflow menu).
2. Tap it.

**Expected:** Pull succeeds (if remote configured). No pull option = PARTIAL.

---

## Test 87 — Git blame 🟢

**Steps:**
1. Open a file tracked by git with commit history.
2. Long-press a line of code.
3. Look for **Blame** option. Tap it.

**Expected:** Blame info appears (author, date, commit message). No option or nothing shown = FAIL.

---

## Test 88 — Merge conflict inline editor 🟡

**File setup:** Create `test_merge.txt` and paste:

```
some text
<<<<<<< HEAD
our changes
=======
their changes
>>>>>>> feature-branch
more text
```

**Steps:**
1. Open `test_merge.txt`.
2. Conflict markers should be highlighted with resolve buttons.
3. Tap **Accept Current** (or "ours").

**Expected:** Conflict resolved — markers removed, only "our changes" remains. No resolve buttons or don't work = FAIL.

**Cleanup:** Delete `test_merge.txt`.

---

## Section 11: Debugger

## Test 89 — Breakpoint gutter markers 🟡

**File setup:** Create `test_bp.py` and paste:

```
print("line 1")
print("line 2")
print("line 3")
```

**Steps:**
1. Open it.
2. Tap line number **2**.

**Expected:** Red dot appears on line 2. Tap again — disappears. Tap line 3 — dot appears there. No dot = FAIL.

**Cleanup:** Delete `test_bp.py`.

---

## Test 90 — Debug session start (Python) 🟡

**File setup:** Create `test_debug.py` and paste:

```
print("start")
x = 1 + 2
print(f"result: {x}")
print("end")
```

**Steps:**
1. Open it. Tap line number 2 for breakpoint.
2. Tap **Run & Debug** icon in activity bar.
3. Tap **Start Debugging** or **Run**.

**Expected:** Session starts and pauses at line 2. Debug panel shows session state. Doesn't start or pause = FAIL.

**Cleanup:** Delete `test_debug.py`.

---

## Test 91 — Debug Step Over 🟢

**Steps:**
1. While paused at breakpoint, tap **Step Over** (forward/down arrow icon).

**Expected:** Advances to line 3. Variable panel shows `x = 3`. Doesn't work or crash = FAIL.

---

## Test 92 — Debug restart 🟢

**Steps:**
1. While session is running, tap **restart** button (green circular arrow).

**Expected:** Session stops and new one starts, pausing at breakpoint again. No button or doesn't restart = FAIL.

---

## Test 93 — Debug variable expansion 🟡

**File setup:** Create `test_debug_vars.py` and paste:

```
my_list = [1, 2, 3]
x = 1 + 2
print(f"result: {x}")
```

**Steps:**
1. Open it. Set breakpoint on line 2.
2. Start debugging. When paused, find `my_list` in Variables panel.
3. Tap the **arrow** next to `my_list`.

**Expected:** Expands to show children: `0: 1`, `1: 2`, `2: 3`. Tap again to collapse. Arrow doesn't respond = FAIL.

**Cleanup:** Delete `test_debug_vars.py`.

---

## Test 94 — Debug stop 🟢

**Steps:**
1. While session running, tap **Stop** (red square/stop icon).

**Expected:** Session stops. Panel shows idle state. Doesn't work = FAIL.

---

## Test 95 — Run without debugging 🟡

**File setup:** Create `test_run.py` and paste:

```
print("running without debug")
x = 10
print(f"x = {x}")
```

**Steps:**
1. Open it. In Run & Debug panel, find **Run** (without debugging) option.
2. Tap it.

**Expected:** File runs in terminal/output. Shows `running without debug` and `x = 10`. Doesn't run = FAIL.

**Cleanup:** Delete `test_run.py`.

---

## Test 96 — Debug configuration 🟢

**Steps:**
1. Tap **Run & Debug** icon. Look for configuration dropdown or gear icon.

**Expected:** Can see or create a debug configuration (Python file, Attach, etc.). No config options = FAIL.

---

## Section 12: AI Copilot

## Test 97 — AI Copilot panel open 🟢

**Steps:**
1. Tap the **AI Chat** icon in the activity bar (copilot/robot icon).

**Expected:** Chat panel opens on right side with input field and mode selector (Ask, Agent, Plan). No panel = FAIL.

---

## Test 98 — AI chat — Ask mode 🟡

**Steps:**
1. Open AI Copilot panel. Set mode to **Ask**.
2. Type `What is Python?` and send.

**Expected:** A response appears. No response or error = FAIL. (Requires API key configured.)

---

## Test 99 — AI chat — multiple sessions 🟢

**Steps:**
1. In AI panel, tap **New Chat** (+ icon).
2. Send a message.
3. Tap New Chat again.
4. Switch back to first session.

**Expected:** Multiple sessions maintained separately. History preserved. Sessions merge or lost = FAIL.

---

## Test 100 — AI chat history persistence 🟢

**Steps:**
1. Send a message in the AI panel.
2. Close the panel.
3. Reopen it.

**Expected:** Previous conversation still visible. History cleared on reopen = FAIL.

---

## Test 101 — AI model selection 🟢

**Steps:**
1. In AI panel, look for model selector dropdown.
2. Tap it.

**Expected:** List of available AI models. No selector = PARTIAL.

---

## Test 102 — AI Explain Code from lightbulb 🟡

**File setup:** Create `test_explain.py` and paste:

```
def complex_func(a, b):
    result = 0
    for i in range(a):
        result += b * i
    return result
```

**Steps:**
1. Open it. Wait for LSP.
2. Tap lightbulb on line 1.
3. Tap **Explain Code**.

**Expected:** AI panel opens with explain prompt, response generated. Option missing or panel doesn't open = FAIL.

**Cleanup:** Delete `test_explain.py`.

---

## Section 13: Preview & Media

## Test 103 — HTML preview 🟡

**File setup:** Create `test_html.html` and paste:

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
1. Open it. Tap the **Preview** tab in the bottom panel.

**Expected:** Blue "Hello World" heading and green paragraph rendered as HTML. Raw tags shown = FAIL.

**Cleanup:** Delete `test_html.html`.

---

## Test 104 — HTML preview pinch-to-zoom 🟡

**File setup:** Same `test_html.html`, or recreate.

**Steps:**
1. With HTML preview visible, pinch outward. Pinch inward.
2. Rotate device to landscape.

**Expected:** Pinch-to-zoom works. Rotating resizes preview. Pinch does nothing = FAIL.

**Cleanup:** Delete `test_html.html`.

---

## Test 105 — SVG preview 🟡

**File setup:** Create `test_svg.svg` and paste:

```
<svg xmlns="http://www.w3.org/2000/svg" width="200" height="200">
<circle cx="100" cy="100" r="80" fill="red" stroke="black" stroke-width="3"/>
<text x="100" y="105" text-anchor="middle" fill="white" font-size="16">SVG</text>
</svg>
```

**Steps:**
1. Open it. Tap **Preview** tab.

**Expected:** Red circle with black border and white "SVG" text rendered. Raw SVG code = FAIL.

**Cleanup:** Delete `test_svg.svg`.

---

## Test 106 — SVG preview pinch-to-zoom 🟡

**File setup:** Same `test_svg.svg`, or recreate.

**Steps:**
1. Pinch outward on SVG preview. Pinch inward.

**Expected:** Zoom works. Does nothing = FAIL.

**Cleanup:** Delete `test_svg.svg`.

---

## Test 107 — Markdown preview 🟡

**File setup:** Create `test_md.md` and paste:

```
# Heading 1
## Heading 2

This is **bold** and *italic* text.

- Item 1
- Item 2
- Item 3

[Link text](https://example.com)
```

**Steps:**
1. Open it. Tap **Preview** tab.

**Expected:** Rendered Markdown: headings, bold/italic, bulleted list, clickable link. Raw Markdown = FAIL.

**Cleanup:** Delete `test_md.md`.

---

## Test 108 — Preview browser address bar 🟢

**Steps:**
1. Tap **Preview** tab. Look for an address bar/URL input.

**Expected:** Address bar visible where you can type a URL. No address bar = FAIL.

---

## Test 109 — YouTube video playback 🟢

**Steps:**
1. In Preview tab, type in the address bar: `https://www.youtube.com/watch?v=dQw4w9WgXcQ`
2. Tap **Go**.

**Expected:** YouTube loads, video plays with **video** (not just audio). Audio only with black screen = FAIL. Sign-in/insecure warning blocking playback = FAIL.

---

## Test 110 — Image file viewer 🟡

**Steps:**
1. In the file explorer, tap an image file (`.png`, `.jpg`, `.jpeg`, `.webp`).

**Expected:** Image opens in an image viewer showing the rendered image. Doesn't display or crash = FAIL.

---

## Test 111 — Video file player 🟡

**Steps:**
1. In the file explorer, tap a video file (`.mp4`, `.webm`).

**Expected:** Video player opens with play/pause and seek bar. Doesn't play or no player = FAIL.

---

## Test 112 — Audio file player 🟡

**Steps:**
1. In the file explorer, tap an audio file (`.mp3`, `.wav`).

**Expected:** Audio player with play/pause controls. Doesn't play = FAIL.

---

## Test 113 — PDF viewer 🟡

**Steps:**
1. In the file explorer, tap a `.pdf` file.

**Expected:** PDF opens in a viewer showing the document content. Doesn't render or crash = FAIL.

---

## Test 114 — Hex viewer 🟡

**File setup:** In terminal: `head -c 256 /dev/urandom > test_binary.dat`

**Steps:**
1. Tap `test_binary.dat` in the explorer.

**Expected:** Hex viewer opens showing bytes in hex format. Text garbage or crash = FAIL.

**Cleanup:** Delete `test_binary.dat`.

---

## Test 115 — Archive viewer 🟡

**File setup:** In terminal: `zip test_archive.zip test_binary.dat` (or any existing file)

**Steps:**
1. Tap `test_archive.zip` in the explorer.

**Expected:** Archive viewer lists contents. Doesn't open or crash = FAIL.

**Cleanup:** Delete `test_archive.zip`.

---

## Section 14: Bottom Panel — Other Tabs

## Test 116 — Output panel 🟢

**Steps:**
1. Open bottom panel. Tap **Output** tab.

**Expected:** Output panel showing app output logs. Blank or doesn't exist = FAIL.

---

## Test 117 — Logcat viewer 🟢

**Steps:**
1. Open bottom panel. Tap **Logcat** tab.

**Expected:** Logcat viewer showing Android system logs. Blank or doesn't exist = FAIL.

---

## Test 118 — Ports panel 🟢

**Steps:**
1. Open bottom panel. Tap **Ports** tab.

**Expected:** Ports panel showing forwarded ports or running local servers. Doesn't exist = FAIL.

---

## Test 119 — Build panel 🟢

**Steps:**
1. Open bottom panel. Tap **Build** tab.

**Expected:** Build panel with build button or build history. Doesn't exist = FAIL.

---

## Test 120 — Build history 🟢

**Steps:**
1. Open bottom panel. Tap **History** tab.

**Expected:** Build history panel. Doesn't exist = FAIL.

---

## Test 121 — Toolchain panel 🟢

**Steps:**
1. Open bottom panel. Tap **Toolchain** tab.

**Expected:** Toolchain panel showing installed tools (python, node, git). Doesn't exist = FAIL.

---

## Test 122 — TODO Explorer 🟡

**File setup:** Create `test_todo.py` and paste:

```
# TODO: Fix this function
def incomplete():
    pass
# FIXME: Another issue
# TODO: Third item
```

**Steps:**
1. Open bottom panel. Tap **TODO** tab.
2. Wait for scanning.

**Expected:** All TODO/FIXME items shown with file names and line numbers. Empty = FAIL.

**Cleanup:** Delete `test_todo.py`.

---

## Test 123 — Test Explorer 🟡

**File setup:** Create `test_test_explorer.py` and paste:

```
def test_something():
    assert True

def test_another():
    assert 1 + 1 == 2
```

**Steps:**
1. Open bottom panel. Tap **Tests** tab.
2. Wait for scanning.

**Expected:** `test_test_explorer.py` with test functions listed. Empty = FAIL.

**Cleanup:** Delete `test_test_explorer.py`.

---

## Test 124 — Code Analysis panel 🟡

**File setup:** Create `test_analysis.py` and paste:

```
def unused_function():
    pass
def duplicate_code():
    x = 1
    y = 2
    return x + y
def similar_code():
    x = 1
    y = 2
    return x + y
```

**Steps:**
1. Open bottom panel. Tap **Analysis** tab.
2. Wait for analysis.

**Expected:** Code Analysis shows findings (dead code, duplicates, complexity). Empty or doesn't exist = FAIL.

**Cleanup:** Delete `test_analysis.py`.

---

## Test 125 — Variables inspector 🟢

**Steps:**
1. Open bottom panel. Tap **Variables** tab.

**Expected:** Variable inspector panel appears. Doesn't exist = FAIL.

---

## Test 126 — Artifacts panel 🟢

**Steps:**
1. Open bottom panel. Tap **Artifacts** tab.

**Expected:** Artifacts panel appears. Doesn't exist = FAIL.

---

## Test 127 — Download Center 🟢

**Steps:**
1. Open bottom panel. Tap **Downloads** tab.

**Expected:** Download center panel appears. Doesn't exist = FAIL.

---

## Test 128 — Cloud Backup 🟢

**Steps:**
1. Open bottom panel. Tap **Backup** tab.

**Expected:** Cloud backup panel with backup button. Doesn't exist = FAIL.

---

## Test 129 — Timeline panel 🟢

**Steps:**
1. Open bottom panel. Tap **Timeline** tab.

**Expected:** Timeline panel appears. Doesn't exist = FAIL.

---

## Test 130 — Bottom panel drag-to-resize 🟢

**Steps:**
1. Locate the divider between editor area and bottom panel.
2. Drag it **upward**. Then drag **downward**.

**Expected:** Panel grows when dragging up, shrinks when dragging down. Drag below minimum = collapses. Drag back up = restores. Doesn't respond = FAIL.

---

## Section 15: Menu System

## Test 131 — File menu 🟢

**Steps:**
1. Tap three-dot overflow menu → **File**.

**Expected:** Submenu with: New File, New Folder, Open File, Open Folder, Save, Save As, Auto Save, Create Snapshot, Diagnostics Report, Connectors Hub, Preferences, Exit. Missing items = FAIL.

---

## Test 132 — Edit menu 🟢

**Steps:**
1. Tap three-dot menu → **Edit**.

**Expected:** Undo, Redo, Cut, Copy, Paste, Find, Replace, Find in Files. Missing items = FAIL.

---

## Test 133 — Selection menu 🟢

**Steps:**
1. Tap three-dot menu → **Selection**.

**Expected:** Select All, Expand Selection, Shrink Selection, Add Cursor Above, Add Cursor Below. Missing items = FAIL.

---

## Test 134 — View menu 🟢

**Steps:**
1. Tap three-dot menu → **View**.

**Expected:** Explorer, Search, Source Control, Run & Debug, Extensions, Terminal, Problems, Output, Toggle Sidebar, Toggle Zen Mode, Zoom In, Zoom Out. Missing items = FAIL.

---

## Test 135 — Go menu 🟢

**Steps:**
1. Tap three-dot menu → **Go**.

**Expected:** Go to File, Go to Symbol, Go to Line, Go to Definition. Missing items = FAIL.

---

## Test 136 — Run menu 🟢

**Steps:**
1. Tap three-dot menu → **Run**.

**Expected:** Run Program, Start Debugging, Stop, Restart, Add Breakpoint. Missing items = FAIL.

---

## Test 137 — Terminal menu 🟢

**Steps:**
1. Tap three-dot menu → **Terminal**.

**Expected:** New Terminal, Split Terminal, Kill Terminal, Clear. Missing items = FAIL.

---

## Test 138 — Help menu 🟢

**Steps:**
1. Tap three-dot menu → **Help**.

**Expected:** Documentation, Keyboard Shortcuts, Release Notes, About. Missing items = FAIL.

---

## Test 139 — Diagnostics Report 🟢

**Steps:**
1. Tap three-dot menu → **File** → **Diagnostics Report**.
2. Wait for it to generate.

**Expected:** Report generated with device info, crash logs, terminal output. Share sheet appears. Nothing happens = FAIL.

---

## Test 140 — Connectors Hub 🟢

**Steps:**
1. Tap three-dot menu → **File** → **Connectors Hub**.

**Expected:** Connectors hub sheet showing third-party services (Gmail, Calendar, Drive, Slack) with connect/disconnect buttons. Doesn't appear = FAIL.

---

## Section 16: Activity Bar & Layout

## Test 141 — Toggle Sidebar 🟢

**Steps:**
1. Tap the sidebar toggle icon in top bar (or Ctrl+B).

**Expected:** Left sidebar hides and shows. Doesn't toggle = FAIL.

---

## Test 142 — Toggle bottom panel 🟢

**Steps:**
1. Tap the bottom panel toggle icon in top bar.

**Expected:** Bottom panel hides and shows. Doesn't toggle = FAIL.

---

## Test 143 — Toggle secondary sidebar (AI Chat) 🟢

**Steps:**
1. Tap the secondary sidebar toggle icon in top bar (right-side panel icon).

**Expected:** AI Copilot chat panel hides and shows. Doesn't toggle = FAIL.

---

## Test 144 — Zen Mode 🔴

**Steps:**
1. Tap View → **Toggle Zen Mode** (or Ctrl+K Z).

**Expected:** Editor goes full-screen zen mode — sidebars, panels, menus hidden. Floating exit button appears. Tap it to exit. No zen mode or no exit = FAIL.

---

## Test 145 — Zen Mode exit button draggable 🔴

**Steps:**
1. Enter Zen Mode.
2. Long-press the floating exit button and drag it to a different position.
3. Release.

**Expected:** Button moves to new position. Can't drag or jumps back = FAIL. Tap to exit Zen Mode.

---

## Test 146 — Customize Layout dropdown 🟢

**Steps:**
1. In top bar, look for a layout/customize icon near the toggle icons.
2. Tap it.

**Expected:** Dropdown with options to toggle sidebar, panel, secondary sidebar. No dropdown = FAIL.

---

## Test 147 — Sidebar drag-to-resize 🟢

**Steps:**
1. With sidebar open, locate the divider between sidebar and editor.
2. Press and drag it left/right.

**Expected:** Sidebar width changes. Doesn't respond = FAIL.

---

## Section 17: Notifications

## Test 148 — Notification floating card 🟢

**Steps:**
1. Trigger an action that creates a notification (start terminal, run a command).
2. Look for a floating notification card at the bottom of the screen.

**Expected:** Floating card with notification message. Auto-dismisses after a few seconds or manually dismissible. No card = FAIL.

---

## Test 149 — Notification bell icon 🟢

**Steps:**
1. Look for a **bell icon** in the status bar / bottom area.
2. Tap it.

**Expected:** Notification drawer opens showing notification history. No bell icon or tapping does nothing = FAIL.

---

## Test 150 — Notification drawer 🟢

**Steps:**
1. With notification drawer open, look at the notification list.
2. Check for filter chips (All, Terminal, Build, LSP).
3. Tap a filter chip.
4. Look for a clear/clear-all option.

**Expected:** Drawer shows notifications with filter options. Tapping a filter narrows the list. Clear option exists. Empty despite prior notifications or no filters = PARTIAL.

---

## Section 18: Extensions & Advanced

## Test 151 — Extensions panel 🟢

**Steps:**
1. Tap the **Extensions** icon in the activity bar.

**Expected:** Extensions panel showing available or installed extensions. Blank or no panel = FAIL.

---

## Test 152 — MCP panel 🟢

**Steps:**
1. With Extensions panel open, scroll down.
2. Look for an **MCP** section below the extensions list.

**Expected:** MCP panel showing MCP server configurations or status. Doesn't exist = FAIL.

---

## Test 153 — SSH Manager 🟢

**Steps:**
1. Look for SSH option in the terminal overflow menu or explorer overflow menu.
2. Tap **SSH Manager**.

**Expected:** SSH manager sheet with options to add/edit SSH profiles. Doesn't appear = FAIL.

---

## Test 154 — Image Generation (AI) 🟡

**Steps:**
1. In file explorer, long-press a folder.
2. Look for **Generate Image** or **AI Image Gen** in the context menu.
3. Tap it. Type `a red circle on a blue background` and tap Generate.

**Expected:** AI-generated image appears after a few seconds. No dialog, generation fails, or no API key = PARTIAL.

**Cleanup:** Delete generated image if one is created.

---

## Test 155 — In-Project Settings search 🟢

**Steps:**
1. Open In-Project Settings (three-dot menu → Preferences).
2. Find the search bar.
3. Type `font` in the search field.

**Expected:** Settings rows matching "font" are filtered and shown. No search bar or doesn't filter = FAIL.

---

## Test 156 — Snapshot creation 🟡

**Steps:**
1. Tap three-dot menu → **File** → **Create Snapshot**.
2. Wait for completion.

**Expected:** Snapshot created, confirmation notification appears. Nothing happens or error = FAIL.

---

## Test 157 — Unsupported file types 🟡

**File setup:** In terminal: `echo "test" > test_unknown.xyz`

**Steps:**
1. Tap `test_unknown.xyz` in the explorer.

**Expected:** File opens as text or a relevant viewer. Should NOT show "Unsupported" prominently. Crash = FAIL.

**Cleanup:** Delete `test_unknown.xyz`.

---

## Test 158 — Open File picker (system) 🟢

**Steps:**
1. Tap three-dot menu → **File** → **Open File**.

**Expected:** System file picker opens. Doesn't appear = FAIL. Cancel without selecting.

---

## Test 159 — Open Folder 🟢

**Steps:**
1. Tap three-dot menu → **File** → **Open Folder**.

**Expected:** Folder picker opens. Doesn't appear = FAIL. Cancel without selecting.

---

## Test 160 — About dialog 🟢

**Steps:**
1. Tap three-dot menu → **Help** → **About**.

**Expected:** About dialog with app name, version, and other info. Nothing appears = FAIL.

---

---

## Summary checklist

Copy this table and fill in your results:


| # | Test | Category | Result | Notes |
|---|------|----------|--------|-------|
| 1 | App launches | SAFE | | |
| 2 | Safe mode | SAFE | | |
| 3 | Home screen project list | SAFE | | |
| 4 | New project FAB menu | SAFE | | |
| 5 | Project creation wizard | SYSTEM | | |
| 6 | Clone from GitHub | SAFE | | |
| 7 | Open existing project | SAFE | | |
| 8 | Settings screen | SAFE | | |
| 9 | Dark/light theme | SYSTEM | | |
| 10 | Pin Lock setup | SYSTEM | | |
| 11 | Terminal PTY shell | SAFE | | |
| 12 | Terminal multi-tab | SAFE | | |
| 13 | Terminal session restore | SAFE | | |
| 14 | Terminal extra keys | SAFE | | |
| 15 | Terminal color scheme | SAFE | | |
| 16 | Terminal file creation | MODIFIES | | |
| 17 | Terminal large file | MODIFIES | | |
| 18 | Split terminal | SAFE | | |
| 19 | Terminal clear | SAFE | | |
| 20 | Shell history search | SAFE | | |
| 21 | File explorer tree | SAFE | | |
| 22 | Open file from explorer | SAFE | | |
| 23 | Create new file | MODIFIES | | |
| 24 | Create new folder | MODIFIES | | |
| 25 | File context menu | SAFE | | |
| 26 | File rename | MODIFIES | | |
| 27 | Delete to trash | MODIFIES | | |
| 28 | Trash view | SAFE | | |
| 29 | Restore from trash | MODIFIES | | |
| 30 | Permanent delete from trash | MODIFIES | | |
| 31 | File info dialog | SAFE | | |
| 32 | Syntax highlighting | MODIFIES | | |
| 33 | Line numbers | SAFE | | |
| 34 | Font size | SYSTEM | | |
| 35 | Word wrap toggle | SYSTEM | | |
| 36 | Minimap toggle | SYSTEM | | |
| 37 | Editor extra keys bar | SAFE | | |
| 38 | Snippet Tab (Python) | MODIFIES | | |
| 39 | Snippet Tab (Kotlin) | MODIFIES | | |
| 40 | Auto-indent | MODIFIES | | |
| 41 | Bracket auto-close | MODIFIES | | |
| 42 | Cursor blink — Solid | SYSTEM | | |
| 43 | Cursor blink — Expand | SYSTEM | | |
| 44 | Cursor mode | SYSTEM | | |
| 45 | Find in editor | MODIFIES | | |
| 46 | Replace in editor | MODIFIES | | |
| 47 | Find in Files | MODIFIES | | |
| 48 | Replace in Files | MODIFIES | | |
| 49 | Recent search history | SAFE | | |
| 50 | Select Next Occurrence | MODIFIES | | |
| 51 | Multi-cursor typing | MODIFIES | | |
| 52 | Add Cursor Above/Below | MODIFIES | | |
| 53 | Go to Line | MODIFIES | | |
| 54 | Command Palette | SAFE | | |
| 55 | Symbol search | MODIFIES | | |
| 56 | Document Outline | MODIFIES | | |
| 57 | LSP autocomplete | MODIFIES | | |
| 58 | LSP hover docs | MODIFIES | | |
| 59 | Go to Definition (same file) | MODIFIES | | |
| 60 | Go to Definition (cross-file) | MODIFIES | | |
| 61 | Find References | MODIFIES | | |
| 62 | Find References (peek) | MODIFIES | | |
| 63 | Wavy underline | MODIFIES | | |
| 64 | Problems panel | MODIFIES | | |
| 65 | Lightbulb menu | MODIFIES | | |
| 66 | Fix with AI | MODIFIES | | |
| 67 | Code Lens | MODIFIES | | |
| 68 | Inlay Hints | MODIFIES | | |
| 69 | Signature help | MODIFIES | | |
| 70 | Master LSP toggle | SYSTEM | | |
| 71 | LSP servers list | SAFE | | |
| 72 | Rename symbol | MODIFIES | | |
| 73 | Sticky scroll | SAFE | | |
| 74 | Error lens | SAFE | | |
| 75 | Format Document | MODIFIES | | |
| 76 | Format on Save | MODIFIES | | |
| 77 | Formatter selection | SYSTEM | | |
| 78 | Source Control panel | SAFE | | |
| 79 | Git stage | MODIFIES | | |
| 80 | Git unstage | MODIFIES | | |
| 81 | Git commit | MODIFIES | | |
| 82 | Git diff view | MODIFIES | | |
| 83 | Git branch list | SAFE | | |
| 84 | Git checkout | MODIFIES | | |
| 85 | Git push | MODIFIES | | |
| 86 | Git pull | MODIFIES | | |
| 87 | Git blame | SAFE | | |
| 88 | Merge conflict editor | MODIFIES | | |
| 89 | Breakpoint markers | MODIFIES | | |
| 90 | Debug session start | MODIFIES | | |
| 91 | Debug Step Over | SAFE | | |
| 92 | Debug restart | SAFE | | |
| 93 | Variable expansion | MODIFIES | | |
| 94 | Debug stop | SAFE | | |
| 95 | Run without debugging | MODIFIES | | |
| 96 | Debug configuration | SAFE | | |
| 97 | AI Copilot panel | SAFE | | |
| 98 | AI chat — Ask mode | MODIFIES | | |
| 99 | AI chat — sessions | SAFE | | |
| 100 | AI chat persistence | SAFE | | |
| 101 | AI model selection | SAFE | | |
| 102 | AI Explain Code | MODIFIES | | |
| 103 | HTML preview | MODIFIES | | |
| 104 | HTML pinch-to-zoom | MODIFIES | | |
| 105 | SVG preview | MODIFIES | | |
| 106 | SVG pinch-to-zoom | MODIFIES | | |
| 107 | Markdown preview | MODIFIES | | |
| 108 | Preview address bar | SAFE | | |
| 109 | YouTube video | SAFE | | |
| 110 | Image viewer | MODIFIES | | |
| 111 | Video player | MODIFIES | | |
| 112 | Audio player | MODIFIES | | |
| 113 | PDF viewer | MODIFIES | | |
| 114 | Hex viewer | MODIFIES | | |
| 115 | Archive viewer | MODIFIES | | |
| 116 | Output panel | SAFE | | |
| 117 | Logcat viewer | SAFE | | |
| 118 | Ports panel | SAFE | | |
| 119 | Build panel | SAFE | | |
| 120 | Build history | SAFE | | |
| 121 | Toolchain panel | SAFE | | |
| 122 | TODO Explorer | MODIFIES | | |
| 123 | Test Explorer | MODIFIES | | |
| 124 | Code Analysis | MODIFIES | | |
| 125 | Variables inspector | SAFE | | |
| 126 | Artifacts panel | SAFE | | |
| 127 | Download Center | SAFE | | |
| 128 | Cloud Backup | SAFE | | |
| 129 | Timeline panel | SAFE | | |
| 130 | Bottom panel resize | SAFE | | |
| 131 | File menu | SAFE | | |
| 132 | Edit menu | SAFE | | |
| 133 | Selection menu | SAFE | | |
| 134 | View menu | SAFE | | |
| 135 | Go menu | SAFE | | |
| 136 | Run menu | SAFE | | |
| 137 | Terminal menu | SAFE | | |
| 138 | Help menu | SAFE | | |
| 139 | Diagnostics Report | SAFE | | |
| 140 | Connectors Hub | SAFE | | |
| 141 | Toggle Sidebar | SAFE | | |
| 142 | Toggle bottom panel | SAFE | | |
| 143 | Toggle secondary sidebar | SAFE | | |
| 144 | Zen Mode | SYSTEM | | |
| 145 | Zen Mode exit draggable | SYSTEM | | |
| 146 | Customize Layout | SAFE | | |
| 147 | Sidebar resize | SAFE | | |
| 148 | Notification floating card | SAFE | | |
| 149 | Notification bell icon | SAFE | | |
| 150 | Notification drawer | SAFE | | |
| 151 | Extensions panel | SAFE | | |
| 152 | MCP panel | SAFE | | |
| 153 | SSH Manager | SAFE | | |
| 154 | Image Generation | MODIFIES | | |
| 155 | Settings search | SAFE | | |
| 156 | Snapshot creation | MODIFIES | | |
| 157 | Unsupported file types | MODIFIES | | |
| 158 | Open File picker | SAFE | | |
| 159 | Open Folder | SAFE | | |
| 160 | About dialog | SAFE | | |

---

End of test guide. Go through each test in order and report results. I'll group them into PASS/FAIL/PARTIAL when you're done.