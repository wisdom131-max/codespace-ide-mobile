# Round 1 regression checks: items 1, 2 and 3 only

This is a targeted regression round, not the full P5 round. Items 4-9 and XG work remain parked. Android CI and the standalone Kotlin harness are not evidence of a device pass.

## Build verified

Final Android code: `b032295` (includes all Round 1 fixes). Android CI [#3021](https://github.com/wisdom131-max/codespace-ide-mobile/actions/runs/36845010343) GREEN. Use this build's `codespace-ide-arm64-v8a` artifact for the usual arm64 debug installation. Update the existing debug app without uninstalling or clearing data.

### Startup prerequisite

After installing, open Android Settings > Apps > CodeSpace IDE > Force stop, then launch CodeSpace IDE from its icon. Repeat three times. Expected: it opens normally each time. If launch still crashes, stop this round, report the new stack trace and mark dependent cases BLOCKED. Do not erase app data to make the test appear to pass.

## Report format

For each ID below, report `PASS`, `FAIL` or `BLOCKED`, plus the first incorrect behavior. Include the installed APK's commit, orientation and screenshot/log if applicable. Do not label a test PASS when its prerequisite was unavailable.

## Safe setup

1. Install the final Round 1 APK linked in the accompanying report. Open a disposable or already-trusted test project. Keep your existing approval/trust settings. This round does not require changing trust policy.
2. Open Terminal and run `pwd`. Verify that this is the current project's **guest** directory. If not, change to that project's actual guest path first. Do not guess a path or run the fixture command in an unrelated project.
3. The following creates only a new folder named `round1_regression`. If that folder already exists and contains work you want, use another new folder name consistently instead. The command overwrites files within this scratch folder.

```sh
mkdir -p round1_regression/a round1_regression/b
{
  printf '%s\n' 'export const marker_a: number = "A_WRONG_TYPE";'
  i=2
  while [ "$i" -le 80 ]; do
    printf '// A line %s\n' "$i"
    i=$((i + 1))
  done
} > round1_regression/a/same.ts
{
  printf '%s\n' 'export const marker_b: number = 2;'
  i=2
  while [ "$i" -le 80 ]; do
    printf '// B line %s\n' "$i"
    i=$((i + 1))
  done
} > round1_regression/b/same.ts
printf '%s\n' 'ORIGINAL_CONTENT' > round1_regression/existing.txt
: > round1_regression/empty.txt
```

4. Open `a/same.ts` and `b/same.ts` in the editor. If the Explorer cannot display the newly created files, reopen the project to reload the tree. If they remain inaccessible, mark fixture-dependent tests BLOCKED. Do not investigate item 5 as part of this round.
5. Diagnostics tests need the already-configured TypeScript language server to show a type error on A's first line and no corresponding type error on B. If that initial condition is absent, mark the diagnostics tests BLOCKED. Do not install or troubleshoot language servers in this round.

## Item 1: ranked settings headers

### R1-01: portrait

1. Open the project's settings dialog using the existing project settings control, not the separate app-wide Settings screen.
2. Select **Commonly Used**. Scroll from top to bottom and back twice.
3. Visit at least two different settings categories and scroll their rows into view. Merely displaying rows records usage; you do not need to change settings.
4. Return to **Commonly Used**, close the dialog, reopen it, then scroll the ranked list again.

PASS: no duplicate-key crash, no missing settings rows, usage-based ranking remains available. FAIL: a crash such as `header_p_AI_AGENT was already used` or another repeated section-header key.

### R1-02: landscape and orientation change

Repeat R1-01 in landscape. Rotate while the dialog is open, then reopen Commonly Used and scroll again.

PASS: neither orientation crashes. A repeated category heading is allowed when ranking splits that category into separate sections; its key must be unique.

## Item 2: actual tool execution, evidence and Apply

Use the currently configured custom OpenAI-compatible/Qwen endpoint. Do not paste credentials into the chat. For execution tests select **Agent**, not Ask. If an approval card appears, approve only the harmless requested operation. If a plan appears instead of execution, this is not yet a tool-execution pass; follow the existing plan approval flow or report BLOCKED.

### R1-03: real command and native/Qwen loop

Send:

```text
Run exactly this one shell command using run_command: printf 'R1_NATIVE_OK\n'
Do not make a plan. Report the exact output, not an assumed result.
```

PASS: an actual `run_command` execution is recorded and the tool result contains `R1_NATIVE_OK`. The loop accepts the endpoint's native function calls or Qwen/legacy fallback calls without returning raw unexecuted tool tags as the final action.

This device check proves a working execution path for the selected endpoint. It does not prove that every call format was exercised. Native IDs, multiple calls, Qwen/legacy parsing and fragmented native calls also have standalone helper fixtures.

### R1-04: Ask mode cannot claim an executed action

Switch to **Ask**. Send `Install nano and tell me when it is installed.` Do not run a separate installation command for this test.

PASS: the reply says **NOT EXECUTED** and does not present its prose as proof of installation. Nothing should install through Ask mode. A model may still generate an unverified suggestion or statement; it must remain clearly labeled as unexecuted model text.

### R1-05: approval denial

If the current flow requests approval for `run_command`, repeat R1-03 and reject that operation.

PASS: evidence says NOT EXECUTED due to denial, not done or success. If your current flow does not present an approval card, mark this case BLOCKED rather than changing approval policy as part of this round.

### R1-06: output beyond the old 200-character slice

Return to Agent. Send:

```text
Run this exact command in one run_command call:
printf 'R1_START\n'; printf '%0300d\n' 0; printf 'R1_OUTPUT_TAIL\n'
Then quote the last nonempty line from the actual tool result. Do not make a plan.
```

PASS: the model can read and quote `R1_OUTPUT_TAIL`, which occurs after character 200. The display verbosity setting must not alter the result supplied to the model. Very large outputs may still have an explicit truncation marker; this deliberately short fixture should not be capped.

### R1-07: relative staged path and normal Apply

1. Send in Agent:

```text
Use write_file to replace round1_regression/existing.txt with exactly UPDATED_CONTENT followed by a newline. Do not run a shell write and do not make a plan.
```

2. Before tapping Apply, inspect the review card's full path. It must point at the current project's real Android/host file, not the assistant process's working directory or an unmapped guest path.
3. Read the file using the Terminal or another disk reader, not a staged read_file overlay. It must still contain `ORIGINAL_CONTENT`.
4. Open the existing file in the editor before Apply, then tap **Apply** on that file's review row. Read the disk file again and confirm the already-open tab refreshes. Tap editor Undo once to check its pre-Apply snapshot. Host path aliases must not prevent the review strip, refresh or one-shot undo gate from matching the staged file.

PASS: the row is staged-only before Apply; after Apply the disk contains `UPDATED_CONTENT`. No ENOENT for a raw guest path, no unrelated file written. The already-open tab refreshes; one editor Undo restores the pre-Apply `ORIGINAL_CONTENT`.

### R1-08: guest path resolves to the same file

1. In Terminal, get the exact guest project directory with `pwd`.
2. Ask Agent to stage `write_file` for that absolute guest path plus `/round1_regression/existing.txt`, with content `GUEST_PATH_CONTENT` and a newline. Use the actual path, not an example path.
3. Inspect the review row's host path, then tap Apply and read the disk file.

PASS: the guest and relative forms resolve to the same canonical host file. Force apply, if explicitly needed for a blocked case, uses the same review entry's resolved host path. Do not use Force apply just to hide an unexpected error; report that error first.

### R1-09: new file and nested parent

Ask Agent to stage `write_file` for `round1_regression/new_parent/new.txt`, with content `NEW_FILE_CONTENT` and a newline. Do not create this target in advance.

PASS: the review row is labeled NEW and STAGED ONLY. Tap Apply. The absent target is created with the proposed content and necessary parent directory. Missing-at-staging is not misreported as an unreadable existing file.

### R1-10: existing empty file is not NEW

Stage a write to `round1_regression/empty.txt` with content `EMPTY_WAS_EXISTING` and a newline.

PASS: the existing zero-length file is not labeled NEW. Apply writes the new content normally.

### R1-11: new target appears before Apply

1. Stage `write_file` for a new `round1_regression/appeared.txt`, proposing `AI_PROPOSAL` and a newline.
2. Before Apply, create that disk file in Terminal with:

```sh
printf '%s\n' 'OTHER_WRITER' > round1_regression/appeared.txt
```

3. Tap Apply once. Do not immediately tap Apply again or Apply anyway.

PASS: the first Apply reports a drift/conflict and leaves `OTHER_WRITER` unchanged. **Apply anyway** is a separate explicit decision after reviewing the conflict. **Force apply** is the separate manual option for a BLOCKED entry.

## Item 3: rapid tab-switch rendering

Tab titles may have the same basename. Confirm which file is active using its path/breadcrumb and the A/B comment text. A cross-file squiggle or gold band is a failure even if it disappears after 500 ms.

### R1-12: same-name diagnostics, including the debounce window

1. Confirm A has its expected first-line type error and B does not.
2. Tap A, then B immediately. Alternate the tabs 20 times, including switches faster than half a second.
3. On A, edit a comment near the end. Switch to B within half a second, before the local lint debounce finishes.
4. Pause on B for two seconds. Return to A. Repeat five times.

PASS: B never receives A's squiggle or diagnostic tooltip. A retains its own expected error when revisited. Diagnostics coming from a delayed worker or server update never paint on the wrong file.

### R1-13: transient Go to Line and same-name switch

1. Activate A. Open **Go > Go to Line**, enter `40`, tap **Go**. Confirm A reveals line 40 and its temporary gold band.
2. While the band is visible, immediately tap B. B must not inherit a line-40 band or a new jump from A. B's own previous viewport may already be at that line; that alone is not evidence of leakage.
3. Return to A without requesting another jump. The old request must not replay as a fresh reveal.
4. Repeat ten times. Repeat in landscape.

PASS: only the requested canonical file receives the reveal; changing models clears the transient highlight. Returning to a file does not resurrect a consumed request.

### R1-14: newer jump cannot be cleared by the old timer

On A, request Go to Line `40`, then quickly request `60` before the first request's old one-second expiry would finish. Also repeat the same requested line twice.

PASS: the newer request lands correctly; an earlier timer does not reset it. Each request has its own identity even when the line number repeats.

### R1-15: combined race

1. On A, edit a comment and immediately request Go to Line `40`.
2. Tap B within half a second. Wait two seconds, then switch A/B ten times.
3. Rotate and repeat.

PASS: no A diagnostic, tooltip or gold reveal appears in B. No crash during rapid changes. If the diagnostic prerequisite is unavailable, the highlight portion can be reported separately and the diagnostic portion remains BLOCKED.

## Verification boundaries

The executable harness at `tools/round1-regression/run.py` compiles actual Kotlin protocol, evidence, path, staging and request-state helpers. Android Context, Compose lifecycle, proot bridge, buffers/cache and checkpoint infrastructure are stubbed. It also compiles the file-tool forwarding helper with its actual four caller argument lists and rejects a second direct-write remapping path. The current harness passes 59 executable assertions. It exercises real temporary-file staging/Apply behavior, but not Android permission enforcement, actual HTTP servers, coroutine scheduling, Compose rendering or backup behavior.

Source reference patterns: VS Code `src/vs/editor/common/services/markerDecorationsService.ts` reads markers for `model.uri`; `src/vs/workbench/browser/codeeditor.ts`, `RangeHighlightDecorations`, restricts reveals to the target resource and removes highlights on model changes. Copilot Chat's `src/extension/intents/node/toolCallingLoop.ts` and `src/extension/prompts/node/panel/toolCalling.tsx` provide call/result pairing and explicitly marked budget truncation. Our unverified-prose labeling is deliberately stricter, not a claim that VS Code has a universal action-success verifier.

### Source snapshots and harness reproduction

Reference checkout commits: VS Code `832cf23c`; Copilot Chat archive `5863f5a`. These are pinned source snapshots used for the comparison, not a claim about every future VS Code release.

With an existing Java 17+ runtime, Kotlin 1.9 compiler distribution and org.json jar, set `JAVA_HOME`, `KOTLIN_HOME` and `JSON_JAR` to their real local locations, then run:

```sh
python3 tools/round1-regression/run.py
```

Expected final line: `PASS: 59 executable assertions against actual Kotlin helpers.` Additional compiler warnings from intentionally unused infrastructure stubs are not failures. The runtime/infrastructure limitations described above still apply.
