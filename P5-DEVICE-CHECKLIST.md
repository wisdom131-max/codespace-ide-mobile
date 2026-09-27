# P5-DEVICE-CHECKLIST.md — Consolidated P5 Device Verification Round (P0 → P4s)

**Scope:** all 245 closed audit rows, one tap-by-tap protocol. Covers every batch's recorded P5 sections (P0/P1/P2a-c/P3a-e, F1-F5, P4a-1 through P4s), consolidated by setup so state is built once. TP02's batched security verification is its own early step (Step 0), per owner ruling 2026-09-27.

**Source of truth for each step's expected behavior:** the batch P5 sections in AGENTS.md at the row's shipping entry. Row IDs in [brackets] map to MASTER-GAPS.md.

**Verified:** every closed row's code-on-disk was re-confirmed against its shipped claim on 2026-09-27 (245/245, 4 notes, zero regressions — see the verification pass in the 2026-09-27 changelog entry). This checklist verifies DEVICE behavior, which code inspection cannot.

**Prereq:** current debug APK (CI #36297682561 artifact, `codespace-ide-arm64-v8a`), fresh session, signed in.

**Status:** steps 1-30 are unchanged from the checklist delivered in chat on 2026-09-27 (confirmed byte-identical; see commit note). Steps 31 onward follow in this file.

---

## STEP 0 — TP02 Security Round (own early step, before anything else)

In the app terminal pane, fresh session after install:

1. `curl -s http://localhost:8765/tools` → **401 unauthorized** (no token, refused). [TP02]
2. `curl -s -H "Authorization: Bearer $AGENT_API_TOKEN" http://localhost:8765/tools` → tool list JSON. [TP02]
3. `agent_tools` → prints the tool list; `agent run_command '{"command":"echo ok"}'` → returns ok (legacy wrapper carries the token). [TP02]
4. From a second device on the same Wi-Fi: `curl -m 5 http://<phone-ip>:8765/tools` → **connection refused/timeout** (loopback-only). [TP02]
5. `echo $AGENT_API_TOKEN` (if needed for manual curls) → 64-hex token present. [TP02]
6. Untrusted project active → terminal AI tool call → 403 "project not trusted yet"; trust it → runs. [P2c]

## SETUP A — Fresh install + rootfs (one install pass)

7. Fresh rootfs install → progress shows "Verifying rootfs checksum...", completes. [TP04]
8. Check `.ubuntu_install_report`: files_written>0, sha256 matches pinned value, symlinks_unresolved=0. [TP04]
9. Existing-install upgrade path: open Ubuntu normally → boots, no unresolved-link failure. [TP04]
10. Terminal tab with `sleep 300 && echo done` → tap close X → "Close terminal?" dialog; Cancel keeps it alive. Quiet tab (no output 15s) → closes with no dialog. [TP06]
11. Minimize app 1 min, return → sessions persist (`ps` in a surviving tab). [TP07, TP14]
12. Idle tab 30s+ → no URL chip flicker/rebuild. Echo a URL → chip appears within ~2s, then idle → no churn. [TP08, PG04]
13. Project path with a space or `$(echo hi)` fragment → terminal task cd's correctly, no substitution in transcript. [TP11]
14. Run Backup Ubuntu → summary reports files; `tar -tzf <backup> | grep -E "root/.ssh|gitconfig"` → **no matches**; live rootfs `.ssh` still exists. [TP12]
15. Two projects in sequence → minimize, return, switch back → right session per project, no duplicate forks; force-stop → proot trees gone. [TP14]
16. Open two `MainActivity.kt` files in different folders, provoke diagnostics in one → other tab gets no squiggles. [LS06]
17. Package-manager install still streams output and reports success/failure correctly (TP03 seam regression). [TP03, XG05]

## SETUP B — Hostile fixtures (prepare: slippz.zip, ../-tar.gz, truncated .apk, >128MB file, corrupt settings.json)

18. Create the zip-slip zip (the recorded python recipe) → Extract Here → only `legit.txt` inside the output dir; no `../pwned.txt`, no absolute-path file, no crash. [EX05]
19. New File `../esc.txt` → refused; `sub/ok.txt` → nested inside; same for New Folder and Rename. [EX04]
20. Register root at `/storage/emulated/0` → Remove & delete → "directory NOT deleted (device-critical)"; registered subfolder root → deletes fine. [EX07]
21. Cloud-restore a crafted `../escaped.txt` tar → entry not created, restore completes, "Rejected tar entry" in logcat. [RG03, RG07]
22. File with >100-char relative path → backup → restore → filename intact. [RG04]
23. Entity named `../projects` → typed refusal; normal name works. [IG01]
24. In-project symlink to sibling dir → preview refuses; outside file → filename-fallback URL. [VG02]
25. Wizard project name `..` → refused at both create paths. [OG04]
26. Truncated/corrupt APK in the analyzer → no crash, partial manifest, app alive. [VG03, VG04]
27. >128MB file in Smali / Network / AndroidRuntime viewers → honest cap message; small dex/pcap still opens; ELF disassembly unchanged. [VG05, VG10]
28. DB with backtick/quoting-attack table name → "unsafe name" refusal; normal tables list rows. [VG06]
29. Archive extract onto existing Downloads file → Overwrite/Cancel dialog; Cancel changes nothing. [VG07]
30. (Optional) >1GB entry → quota abort, no partial file; >512MB DB → honest size error. [VG08]
31. Logcat panel → "adb not available" line, not a silent empty feed. [VG09]
32. APK with .RSA → "signature entry present … (presence only; not verified)". [VG11]
33. .pcap/.har/.oat/.vdex still dispatch to the right viewers. [VG12]
34. Corrupt settings.json (truncate) → relaunch → boots with defaults AND `settings.json.corrupt` exists; toggles persist through relaunch; keybinding edit persists. [SK01, SK02]
35. Restore a corrupted backup tarball → typed failure + fresh-install fallback; existing container still boots. [RG02, RG08]
36. Delete nested file → Trash → restore → returns to `src/sub/`, not trash root. [EX02]
37. Delete file on full/read-only storage → honest failure; bulk delete shows the real moved count. [EX01]
38. New Folder → appears in tree (normal-path EX06).

## SETUP C — Trusted polyglot project (Kotlin + Python + JS, git repo)

39. Kotlin `SomethingTest` class, unannotated `fun testing()` → NO Run lens; annotated @Test funs (incl. backticks) → lenses; @Nested → "Run Tests"; JS test/it/describe/skip all lens. [TG05/F1]
40. Run Test on a JVM file → logged command plain `./gradlew test`, no `2>/dev/null`, no `|| echo`. [TG06]
41. Tap Run Test on ONE test in a 3-test file → only that test runs (pytest node id / jest -t / gradle --tests); suite lens → container only. [TG02/F2]
42. Failing test → "FAILED — (exit N)" with real stderr visible; passing → PASSED (exit 0); missing runner → visible failure, no whole-file fallback. [TG01/F2]
43. Untrusted project → trust prompt once; refusal → "not trusted — run refused"; repeated taps never re-prompt. [F2]
44. Tap Run Test twice fast → second refuses BUSY. [F2]
45. Debug Test lens on Kotlin/Java → absent (honest supportsDebug=false); Python breakpoint via Debug Test → debugpy attaches, Continue → stops at bp, only that test runs; jest same under node --inspect-brk; assertion failure under debug → CRASHED→FAILED; pass → STOPPED→PASSED. [TG07/F5]
46. Failing test after a run → Problems panel shows a TEST-source row with the real message; tap → jumps to failing line; fix+rerun → stale row gone. [TG03/F3]
47. Gutter during run: blue dot → green check / red cross; suite tap → merged state; uncovered tests dim after a suite run. [TG03/F3]
48. No `.codespace-test-result.xml/json` left in project root after a run. [TG03/F3]
49. Multi-language project → Testing tab tree with per-file rows, chain indentation, Run All sequential, Run Failed only-failed, switching projects clears the tree. [TG04/F4]
50. Non-gradle project → Run Unit Tests routes the real pipeline with honest summary (no cryptic gradle failure). [TG08]
51. Two lenses on one line with an active server → single chip (dedupe). [TG09]
52. Type, pause 500ms, type → no per-keystroke rescan jank (regression feel-check). [TG10]
53. Run Unit Tests (gradle project) → Cancel mid-run → process actually dies, status stays CANCELLED. [PR01]
54. Failing build → FAILED even if a warning line says BUILD SUCCESSFUL; long output keeps first 10000 lines + truncation header. [PR03, PR01]
55. Python script printing `file.py:42: error:` at runtime → NO false BUILD row; real javac/gcc error → row appears; `npx tsc` TS error → TSC row; `npm install` → no rows. [PR06]
56. Failing gradle task → "Task FAILED" rows under "(build output)", tap does nothing; real file row still jumps. [PR08]
57. Explorer badge on a file with problems → only that file's rows + "File:" chip; chip tap → all files. [PR09]
58. Open LSP error file → squiggle AND panel row agree; insert lines above the error unsaved → both shift together; new error while panel open → appears live. [PR14]
59. RUN badge / Explorer badge / panel header counts all agree, no 3s lag. [PR02]
60. ⋯ menu → "Show Errors Only"/"Show All Problems" filter for real; "Focus Search" focuses the box. [PR07]
61. Unused-import row → "↳ first usage" related lines; tap jumps. [PR05]
62. Large file → no lint jank; fast tab switches → no duplicate lint passes. [PR11]
63. Build panel dropdown → all 8 catalogue tasks; pick one → exactly that task runs. [PR12]
64. Close task panel mid-run → tile FAILED "interrupted"; kill app mid-build, relaunch → FAILED "interrupted by app restart"; re-run allowed. [PR13]
65. `obj.m` + Tab-accept a member → only "m" replaced; snippet accept → tab stops engage, no raw `$1`; commit char → commits then appends. [IC01]
66. Auto-import completion accept → replaced span matches server intent, no leftovers. [IC02]
67. Popup + hardware Down/Down/Up → highlight moves; Tab accepts the highlighted row. [IC05]
68. Popup visible + Enter → selected item accepted, NO newline; popup closed + Enter → newline. [IC06]
69. TS member completions → server sortText order respected. [IC07]
70. "(" signature help + keep typing → popup opens → hint STAYS visible above. [IC09]
71. Kill the server, hover `val`/`fun`/`def` → curated doc popup. [IC10]
72. Arrow-highlight an LSP item → detail panel belongs to the selected item, same-label items don't cross. [IC11]
73. Accept "size" in Python, type prefix in Kotlin → no cross-language recency boost. [IC12]
74. Unusual identifier used in one function → typed elsewhere in file → document-word suggestion appears. [IC13]
75. `foo("` + "(" inside a string + identifier → signature boost only when genuinely in call parens. [IC14]
76. Accept completion with auto-import inserting a line above → text lands at right offset; failed auto-import → `[AutoImport]` line in Output. [IC04]
77. Squiggles on file A → tab B → tab A → ranges reappear without re-lint; gutter bookmark survives pane recreation. [PLAN A store, G03]
78. Canonical spelling: write the same file via a different path form in terminal → no dirty-state confusion. [G03]
79. Go to Line / Problems row / test result → gold band at the right line, 6s blink, 5s auto-clear; type during blink → no stutter; sticky-header jump → correct line. [PG01]
80. Two jumps ~2s apart → SECOND jump's band blinks the full 6s (earlier timer no longer kills it). [PG12]
81. Go to Line → palette stays open; Edit → Save File actually saves. [SR09]
82. Search `hello` (Aa off) → Replace All `goodbye` → lowercase hits replaced; one unreadable file → red failure line; open tab shows new content without reopen. [SR03, SR04]
83. Search hit line → jump lands on the hit the matcher found (regex/wildcard aware). [SR01]
84. Symbol search tap → file + line; `+` in path resolves right; indexer-warming search populates when indexing completes without retyping. [SR05, SR11, SR07]
85. Ctrl+T-style panel readiness → signal-driven, no stale remember. [SR06]
86. File A search "foo" → close → file B → reopen find → B's last query, not A's. [G08]
87. Split on one file, type in A, undo in B → A steps back (shared history). [G06]
88. Chat-apply to X → view via Explorer preview → post-apply content. [G07]
89. Chat apply → Undo last Apply → tab visibly reverts; same via .versionhistory snapshot restore (both surfaces). [G02]
90. Read-only file → File > Save → ERROR notification, dirty stays. [G01, G10]
91. chmod 444 a file → edit → ERROR "Disk write failed", tab stays dirty; chmod 644 → Save → dirty clears; kill mid-edit, relaunch → disk unchanged. [G01]
92. Paused at bp → tap different stack frame → lands ON the frame line; bp row → same; TODO row → file + line. [G05, DG09]
93. TextMate ON → Kotlin/Python/JS/JSON → Dark+ colors (comments #6A9955, strings #CE9178, keywords #569CD6); .ts (non-bundled) → built-in fallback clean. [TM03, TM05]
94. Toggle "TextMate Highlighting" off/on → switches on next edit. [TM06]
95. ~50-line files, scroll around → stable highlighting, no drift (TM01/TM04 regression).
96. Open a TS file with server missing → LSP install entry lands in Extensions → History; break it (airplane) → entry shows error detail. [XG07]

## SETUP D — Editor perf reads (same project, ~10 min)

97. Cold start → Output tab → `[perf]` startup chain with deltas, shell-first-frame total, MCP discovery duration on first chat; expand a big folder → tree-build [perf] line. **Send these lines back for ranking.** [PG08, PG09, PG10, PG13]
98. Idle 2+ min with edits → `.versionhistory` gains ONE snapshot per recent edit, not per 20s; nested source captured, hidden/dependency dirs not. [PG03]
99. Heavy project + terminal → status-bar RAM red when low → snapshots pause; RAM recovers → resume. [PG14]
100. Send chat messages + rate a reply in a multi-session project → sessions switch/rename/restore correctly, others untouched. [PG06]
101. Build (notification burst) → history persists after a few seconds; dismissed notification reappears after restart; toggles immediate. [PG07]
102. Long apt install → op strip scrolls per line, stays scrollable. [PG11]

## SETUP E — SCM torture project

103. Stage ONE of 3 changed files → Commit → `git log -1 --stat` shows ONLY it. Unstage all, 2+ pending → Commit → "Stage all and commit?" dialog with right count; Cancel aborts; confirm commits all. Clean tree → honest nothing-to-commit snackbar. [SG01]
104. Publish dialog shows the "stages ALL pending" line; publish works end-to-end. [SG01]
105. Timeline Restore → CONFIRM dialog; confirm → content restored AND a pre-restore capture appears; restore that → original back. Same in Explorer Local History. [SG03, SG02]
106. GIT panel open: editor save, Explorer delete, terminal git → pane updates without manual refresh; no spinner flicker; closed → no polling. [SG06]
107. Status loads in ~2 spawns (~1-2s); `git status` in terminal works concurrently (no index.lock fight). [SG07]
108. Timeline for a file in a subdirectory-of-outer-repo → git history rows appear; tap commit row → that commit's diff for THIS file; outside-root file → no foreign history. [SG08]
109. Branches: local `feature/test` → LOCAL; origin/… → remote. [SG09]
110. Publish on a repo that HAS origin → honest abort BEFORE any GitHub repo is created (check GitHub: no orphan). [SG10]
111. Staged rename diff → old path without a/ prefix. [SG11]
112. History dialog → latest commit HEAD badge; diverged Pull → honest failure, Merge button merges. [SG13]
113. Signed OUT → Commit → honest failure with fix instructions; signed in, legacy-fabricated config → upgraded in place to the account; repo config untouched if already set. [SG14]
114. Stage/Unstage mid-operation → "Wait for current operation" snackbar; row icons disabled while busy. [SG15]
115. Agent push → during it, `cat /proc/*/cmdline | grep -a git` → NO base64 Authorization, only the /tmp/.gitcred- helper; after → `ls /tmp/.gitcred-*` empty; push succeeds. [SG04]
116. Browse My Repos → private clone succeeds; same cmdline check clean. [SG16]
117. Restore on read-only dir → ERROR "file unchanged", file untouched; fix perms → SUCCESS + refresh. [SG02]
118. Unattended scheduled task in UNTRUSTED project → blocked at fire time with notification; trust → next occurrence runs; pre-update tasks grandfathered. [IG02]
119. Restore dialog dismiss tap → re-prompts on relaunch, files KEPT. [TB01]
120. Dirty tab → force-stop → relaunch → restore dialog → Restore → content + dirty restored. [TB01]

## SETUP F — Chat / AI surface

121. Attach via chip AND "#path" → send → force-close → reopen → thread restored; follow-up answer reflects the attached content. [CH04]
122. >4000-char command output / >8000-char file read → "[... TRUNCATED]" markers; >500-file search → scan-cap note. [CH06]
123. Streaming reply → send A then B → "Queued: A (+1 more)"; both deliver in order; × while queued → neither sends. [CH07]
124. Export with no project → honest failure; read-only .codespace/exports → error names the WRITE failure. [CH08]
125. MANUAL flow → approval card ignored 10 min → honest auto-timeout, not "rejected by user"; two gated calls back-to-back → second card appears only after first resolves. [CH09]
126. MCP server with bad command → Refresh → "Discovery failed: ..." inline; delete a discovered server → its mcp_* tools gone; re-add → return. [CH10]
127. Hung MCP tool call → Stop → turn ends before 90s; past-90s hang → honest timeout, chat usable. [CH11]
128. Custom key-slot label → restart → persists; no "labels" in plain chat_key_pool prefs. [CH12]
129. Multi-session send → no jank; delete session → stays deleted; pre-update session loads. [CH13]
130. Delete MCP server with live session → confirm dialog names session-stop + secret wipe; Cancel keeps it. [CH14]
131. Approval card with >160-char command → "Show all" toggle, scrollable, Trust button only for untrusted. [CH03]
132. AUTO flow → use_connector → approval card EVERY time with full service/method/endpoint. [IG15]
133. Ask agent to read a GUEST path (`/root/project/Main.kt`) → read succeeds, `[CH02]` line in Output; write_file to new guest path with existing parent → lands inside rootfs. [CH02]
134. Schedule task with bogus cron ("hourly") → "Unsupported cron form … NOT scheduled", absent from listTasks. [IG13]
135. Agent creates two entity records fast (same command twice) → BOTH exist; corrupt one record → update-all reports "Skipped 1 unreadable". [IG11]
136. Blocked staging dir → AGENT write_file → "write_file REFUSED — staging failed", disk unchanged, no card. [CH05]
137. AI apply batch → Undo → "Restored N file(s)"; forced checkpoint failure → per-file "NOT restored: (reason)" + retryable; fix perms → Undo again → restores. [CH01]
138. Generate an image with valid Gemini key → works (header auth, default model); set In-Project Settings → AI Agent → Gemini Image Model to a newer valid id → uses it; garbage → honest API error. [IM01, IM02, IM03]
139. Hub → connect a real Google/Slack connector → EXTERNAL browser opens (not in-app); finish → row flips Connected within ~5s (poll) without reopening the sheet; disconnect → reconnect works. [IG07]

## SETUP G — Settings, backup, scheduling, restore

140. Gear menu → "Settings (App-wide)" label everywhere; one search box indexes all three surfaces (section + surface per result). [SK07, SK10]
141. Keybinding rebind: Record chip → capture dialog → Esc cancels, key sets; conflicting combo → reassign dialog asks before stealing. [SK09, SK08]
142. Dark-mode toggle off→on → last PICKED dark theme restored. [SK11]
143. Settings Backup → Export to clipboard + Import from SAF → typed results. [SK12]
144. Clear All Data → clears keybindings + settings.json + notifications + workspace memory; dialog states exact scope. [SK05]
145. App-lock disable + GitHub sign-out → read-back persisted state before claiming success. [SK06]
146. Formatter Selection → cross-links the format_on_save rows. [SK14]
147. Create scheduled task → force-stop → relaunch → task present and fires; corrupted tasks.json → clean start, no crash. [IG03]
148. No DOWNLOADS tab anywhere; remaining bottom tabs route correctly. [IG04]
149. Force-stop mid busy chat-save → relaunch → agent memory + scheduled tasks intact. [IG10, IG16]
150. Backup & Restore → auto prefs-backup ran at every start; container prompt banner on version change/fresh/stale. [RG05]
151. Restore backed-up prefs → applied through live prefs API (toggles land). [RG06]
152. CloudBackupPanel signed out → plain missing-sign-in statement, buttons wired. [RG09]
153. Import a session blob → newer-schema refused with honest message; pre-update blob loads. [RG10, RG11]
154. Snapshot export → success message states no in-app restore yet + recovery path. [RG12]
155. SSH Manager → Known hosts Check with rootfs: trusted/not-recorded per profile; new host → trusted after connect; Forget → re-accepts; Clear all → all not-recorded; no rootfs → honest per-row message. [IG06]
156. Backend unreachable (airplane+WiFi/wrong API_BASE) → app works; Connectors Hub → readable "Sign in OFFLINE … sign in again" (not bare 401); sign out, reconnect, sign in → Hub works. [OG05]

## SETUP H — Package Manager / Extensions tab

157. ⟳ Refresh → op strip shows UPDATE-LISTS with live output + done/failed dot; appears in History. [XG06]
158. Search broad term ("lib") → capped at 40, smooth scroll. [XG14]
159. Browse empty search → category chips filter featured list; All resets; typing searches everything. [XG16]
160. Installed tab → real dpkg versions per row; upgradable package → Update button, runs via op strip. [XG10]
161. Start install → switch to Terminal mid-run → back → SAME op strip, still live. [XG11]
162. Two installs at once (LSP bootstrap + apt op) → loser shows the lock-busy hint. [XG12]
163. Remove on git → confirm dialog with package count from simulation; Cancel does nothing. [XG13, XG15]
164. Failed install → History entry carries the E:/Err: line. [XG08]
165. MCP/AGENT TOOLS card → tool count 32. [XG09]
166. Toolchain panel during heavy install → gray "?" UNKNOWN with "Probe failed" note; real values return after. [IG08]
167. Start dev server on a NON-standard port (`python3 -m http.server 7777`) → Ports panel within ~5s, no manual refresh; close → gone on later tick. [IG14]
168. Remove a package → list reflects it without manual refresh. [XG05]

## SETUP I — Debugging (Python + JS projects)

169. Set bp with hitCondition → kill app fully → reopen → bp still there with condition. [DG01]
170. Toggle/disable a bp while paused → Continue → doesn't stop next pass. [DG03]
171. Restart (Overflow + Explorer) with live session → responsive, no ANR; cold-project restart → responsive through boot wait. [DG05]
172. Failed launch (bad interpreter) → no phantom session-picker entry. [DG06]
173. js-debug reverse request → no hang, notSupported logged. [DG07]
174. Paused → VARIABLES tap guest-path frame → jumps to correct host file at exact line. [DG09]
175. Add watch in Explorer → appears in VARIABLES and vice versa; remove → gone from both; REPL echo in both. [DG10]
176. Two Python sessions → stop A, B unaffected (pause/step/evaluate fine). [DG12]
177. Stop mid-debug → debuggee gone from rootfs `ps`; failed launch leaves no tracked process. [DG13]
178. Two live sessions → edit bps in one → both rebind; Remove All clears store + live + persisted (reopen: empty); unverified bp dim with server message. [DG14]
179. Kotlin/C/Go file → Debug → NO session, console "No debugger available for <language> — use Run instead.", no fake RUNNING controls. [DG04]

## SETUP J — Tabs / splits / session state

180. Split into 4 views → close #2 → add split → fresh number, no shared id, closing one never closes another. [TB04]
181. Project A with splits → project B (no splits) → no A splits in B; back → A's return. [TB05]
182. Open+split a file → rename in Explorer → tab title updates, split survives, bookmarks/undo/folds/scroll/bps carry, no second tab. [TB06]
183. OPEN EDITORS → X → tab closes and stays closed, splits cascade, breadcrumb follows new active. [TB07]
184. Open file via differently-spelled path (LSP definition, relative/URL-decoded) → NO duplicate tab. [TB08]
185. Dirty tab → strip X → Save & Close / Discard & Close / Cancel; via Close Others/Close All too; clean tab closes directly; root-removal with dirty tab → typed save, failure keeps tab + ERROR. [TB03]
186. **TB02 race protocol (repeat twice):** rapidly alternate tab selection, panel switches, font-size changes in one session → force-close → relaunch → reopen project → ALL fields restore (active file, pins, panel, bottom tab, font size). Then open a project saved by the PREVIOUS app version → still restores once. [TB02]

## SETUP K — Telemetry (forced crash, debug build)

187. Force an uncaught exception → `filesDir/crash_logs/crash_<ts>.txt` exists with full trace. [RG01]
188. Relaunch online → CrashLog record persists on the current Superagent: app_package `com.codespace.ide.debug`, stack_trace present, app_version + git_hash populated, thread_name from either path ("recovered_on_next_launch" = retry path). **Report when the record lands — this closes RG01's standing note.**
189. Crash with airplane mode ON → local file persists, no record; reconnect + relaunch → retry uploads, local file consumed.

## SETUP L — Home / projects / cloud

190. Airplane mode: create a project → appears; reconnect + sync → SURVIVES, "+N local-only" in status. [OG01]
191. Fresh/emptied cloud account → list NOT wiped on sync. [OG01]
192. Wizard over an existing project folder → "Register Existing Folder" appears → registers, opens, files intact. [OG01]
193. Force-close after opening a project → relaunch → restores into the SAME project. [OG01]
194. Delete a project (soft) → create a NEW project with the SAME name → succeeds; `<name>.deleted-<ts>` sibling exists holding the old `.ide-trash`. [OG03]
195. Backend-unreachable project delete → warning; cloud copy may reappear on next sync (documented expected). [OG02]
196. Copy a FOLDER → paste → children present; cut a file into a same-name collision → error toast AND clipboard still usable; cut a file with its tab open → tab follows, content + dirty intact. [EX03]
197. Collapse all top-level folders → multi-select → All → files from INSIDE those folders listed; manual selection then All → no duplicates. [EX08]

## SETUP M — Live preview (two-device LAN)

198. Live preview on web project → serves + auto-reloads on the phone; logcat "port 5500 (LOOPBACK-ONLY bind)". [VG01]
199. Second device same Wi-Fi → `curl -m 5 http://<phone-ip>:5500/` → refused/timeout. [VG01]
200. `ss -tln` in proot → `127.0.0.1:5500`, not `0.0.0.0`/`[::]`. [VG01]

## FINAL SWEEP (no visible-surface rows — regression only)

201. Tabs restore after force-stop; app boots with zero new logcat errors from terminal. [TP05, TP09, TP10, TP15, TP16]
202. Migration: update over existing projects → no trust prompt on pre-existing projects; new wizard project prompts once on first gated action; delete + re-register same folder → no re-prompt. [P2c]
203. OG06 (comments only) + LS12/LS13 (comment + restart settings) → covered by normal use, no action.

---

*Committed 2026-09-27 alongside the 245-row code-verification pass. Steps 1-30 verified unchanged from the chat delivery; steps 31-203 complete the file.*
