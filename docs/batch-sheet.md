# Batch sheet — builds after 623 (engine test repairs, chat A(a) token layer)

## Install target (latest, supersedes build 623)

- Workflow: Build Android APK — run 3379 (id 40571362733), commit f69dc73, GREEN.
- versionCode 627, versionName 1.0.0.
- Main APK artifact: `codespace-ide-arm64-v8a`, 77,103,104 bytes (zip; installed APK slightly smaller).
- Other ABIs: x86_64 75,439,698 / armeabi-v7a 75,383,853. Spike: `vn-spike-apk` 7,623,174 bytes.
- Settings-About stamp must read exactly: `Build 627 (f69dc73) — v1.0.0 — CI run 3379 @ ...`.
  Verify the hash and run number parts; BUILD_TIME is the CI wall clock (~19:5x UTC).

## What shipped since build 623 (each commit, CI verdict)

| Commit | What | CI |
|---|---|---|
| 228350e | A(a)-1 token layer: android/chat-tokens.json single source + generator -> ChatTokens.kt + chat-tokens.css; consistency tests | red 3376 (test bug, below) |
| 8df9ed9 | test fix: Kotlin String is lowercase(), not lower() | GREEN 3377 |
| 4e535b7 | A(a)-2 component library + ChatListRenderer interface (native default, WebView replaceable) | GREEN 3378 |
| f69dc73 | A(a)-3 design gallery, dev-only entry in Settings-About (debug builds only) | GREEN 3379 |

No Explorer/editor/SCM changes. No provider, panel, attachment, or key-migration code yet.

## Device check sheet (single install, build 627)

1. Install `codespace-ide-arm64-v8a` from run 3379; open Settings -> About and confirm the
   stamp reads `Build 627 (f69dc73) ... CI run 3379`.
2. New this build — design gallery (debug build only): Settings -> About -> "Open chat
   design gallery". One screen should show, in order: a user bubble, "Thinking 3s",
   streaming text with a block cursor, two attachment chips (tap removes one), a
   "Trimmed history" row, the over-budget card with Send anyway / Cancel, the 429 wait card,
   the offline card, the out-of-credits card, and an error card with Retry. All cards
   rounded 8-12dp with 12/10dp padding; nothing clipped; long names wrap.
3. Fixtures (paste block below) — create in your project root from the app's own terminal,
   then check tap and long-press per file.
4. Spike v2 (optional, does not block): install `vn-spike-apk`, tap through until "Done",
   paste the results block back.
5. Regression recheck: open a project, tap a normal text file (opens, editable, save),
   run one command in the terminal, switch editor tabs once.

STOP rules: if the gallery row is missing, or any card renders unstyled, stop and report
with a screenshot; if a fixture behaves differently from the table below, report which file
and what appeared.

## Fixture paste block (app terminal, run in the project root)

```
cd "<your project root>"
printf 'public class Plain { int x = 1; }\n' > Plain.java
printf 'PK\003\004' > Fake.java && head -c 22 /dev/urandom >> Fake.java
yes 'abcdefgh1234 ' | tr -d '\n' | head -c 2048 > Binary.java
head -c 512 /dev/zero >> Binary.java
head -c 512 /dev/urandom >> Binary.java
printf '\377\376' > Utf16.java
s="public class Utf16 { int y = 2; }"
i=0; while [ $i -lt ${#s} ]; do printf '%s\000' "${s:$i:1}" >> Utf16.java; i=$((i+1)); done
printf '\n\x00' >> Utf16.java
ls -l Plain.java Fake.java Binary.java Utf16.java
head -c 8 Fake.java | od -An -tx1
head -c 16 Utf16.java | od -An -tx1
```

If the app shell chokes on \x escapes (sizes look wrong), run the same block in the proot
Ubuntu terminal instead — bash printf handles them.

REAL sizes, verified under bash AND dash/sh in the CI sandbox 2026-10-28:
Plain.java 34 bytes, Fake.java 26 (octal escapes are shell-neutral - the old backslash-x
form printed literally under dash, making it 32), Binary.java 3072, Utf16.java 68
(starts `ff fe 70 00`). The Utf16 char loop needs bash or the app's own terminal
(mksh); under pure dash only that one line fails.

## Current-build expectations (honest baseline, cited; updated only after the proposal is approved)

| File | Tap today | Long-press today |
|---|---|---|
**CURRENT-BUILD expectations: the results below describe the build as shipped TODAY and will CHANGE after Explorer/editor P1-P4 land.**

| Plain.java | Text editor directly, no dialog (ExplorerPane.kt:1357 onOpenFile; not sniff-flagged) | Standard menu only — no "Open as Text"/strings/inspector rows (they require isHexBin/isElf/isArch, ExplorerPane.kt:1685) |
| Fake.java (zip magic) | Not extension-declared binary, so NOT hex-routed (isHexByExt false, :1305) -> onOpenFile -> shared-verdict gate: binary DIALOG with open-anyway/attach — no silent hex, no mojibake | Binary rows DO appear: Open as Text (:1685), Open as Strings, Binary Inspector — the long-press flag is still the sniff-inclusive isHexBin (:1656) |
| Binary.java (NULs after 2KB) | Same as Fake.java — NULs are inside the 8192-byte sniff window (AttachmentTypeSniffer.kt:54), so gate dialog on tap | Same as Fake.java (binary rows present) |
| Utf16.java (BOM) | onOpenFile -> gate BOM path decodes UTF-16LE -> opens as READABLE TEXT (the A1 fix) | The long-press sniff may still flag it (NUL-heavy bytes, :1656) — binary rows MAY appear; this tap-vs-long-press flag mismatch is a known residual, proposal P1 unifies it |

## Spike v2 sheet (optional)

Install `vn-spike-apk`, open, tap through cold start -> pane A alone -> pane B alone ->
input phases until "Done — select + paste the results block back". Paste the whole block;
it self-reports refresh_rate_hz, cold_start_ms, per-pane p50/p95/jank/dropped/frames,
captured PSS, input latency, memory pids, availMem delta, verdict (B PASSES / B FAILS /
NOT ENOUGH DATA), and installed size. Missing metrics print NOT MEASURED / NOT CAPTURED
instead of failing silently.

## Pending (not in this batch)

- A(b) providers on the engine (Gemini native first), (c) read-only key migration,
  (d) panel, (e) attachments, (f) chat-storage Settings row, (g) feature-map tiers.
- Read-only docs: rebuild-plan.md v2, ui-research.md.
- VS Code Explorer/Git design proposal: delivered read-only in
  docs/vscode-explorer-git-proposal.md — awaiting owner ruling; no code until then.
