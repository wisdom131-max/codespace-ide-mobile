# FULL COMBINED RE-TEST BATCH — 2026-09-16
Target APK: **build #2835** artifact `codespace-ide-arm64-v8a` (contains everything below; last doc push after it is docs-only).
Protocol: run batches in order, report pass/fail per item number. Fixes land after ALL batches, then a re-test of only fixed items on the next green APK.

## VERIFIED NOT-BUILT (do NOT test; report if you disagree)
- **MC-3 tap-collapse chip** (tap the multi-cursor count chip to collapse) — NOT implemented. Tap-elsewhere collapse IS built and IS tested below (MC-4). MC-3 remains on the roadmap.
- **PERSIST-B audit** — pending internal audit, not a device test.
- **Batch J (Firebase/Supabase/n8n backend)** — deferred pending real credentials. Skip.
- Note: **I1–I6 ARE built** (shipped 2026-09-13) despite older notes saying "awaiting prioritization" — all six rounds are in this batch.

---

# PHASE 1 — FOUNDATIONAL

## MC — Multi-cursor chokepoint (McEditTransaction/McTapOverlay)
All in an open editor, Multi-cursor ON (MC chip in editor quick actions).
- **MC-1 Chip toggle:** editor strip quick-actions row → multi-cursor icon → mode ON; type → single cursor normal. Toggle again → OFF restores native behavior (handles/magnifier/word-select work).
- **MC-2 Double-tap add cursor:** with MC ON, double-tap a second location → cursor added there; type → text appears at BOTH cursors.
- **MC-3-original 3-delete breakpoint:** with 2+ cursors, press BACKSPACE/DELETE 4+ times → all cursors keep deleting in sync, no desync/crash at the old 3rd-delete break point.
- **MC-4 Tap-elsewhere collapse:** with 2+ cursors, single-tap a third location → collapses to ONE cursor there (this is built; the chip-tap variant MC-3 proper is NOT built).
- **MC-5 Undo/redo:** after a multi-cursor delete, toolbar undo → restores; redo → re-applies; no cursor-state corruption.
- **MC-6 Split-pane parity:** open split (2 views of same file), MC edit in one view → other view mirrors; no desync.
- **MC-7 Tripwire:** after the batch, Output panel → filter [lsp] → any `[MC-TRIPWIRE]` lines = FAIL (report the lines verbatim — means a raw writer bypassed the chokepoint).
- **MC-8 Select-drag:** with MC ON, long-press-drag selection works and doesn't fight the tap overlay.

## R6 — Staged edits (PendingChangesStore + ChatDiffReviewCard)
AGENT mode required (mode selector → AGENT).
- **R6-1 Stage without disk write:** ask agent to edit an existing file → NO file changed on disk; reply says "Staged…"; card appears at transcript bottom with file + +/- counts.
- **R6-2 Review card:** open the file → top strip shows staged stats + purple gutter bars only on affected lines; Review dialog shows colored line diff.
- **R6-3 Apply:** Apply in the card → temp+rename write; editor tab refreshes to new content; strip + bars gone; toolbar undo ONCE restores pre-apply content (single SnapshotUndo entry).
- **R6-4 Discard:** stage another edit → Discard → staged entry gone, bars gone, disk untouched.
- **R6-5 Apply All:** stage 2+ files → Apply All → all written.
- **R6-6 Manual ungated staging:** Settings → AI Agent → Permission Level = Manual → write_file staging still runs WITHOUT an approval card (staging is ungated by design); run_command STILL shows its approval card.
- **R6-7 Drift:** stage an edit, then edit that file on disk yourself (terminal), Apply → DRIFT row appears; DRIFT rebase/confirm path works; content never silently overwritten.
- **R6-8 Fail-closed:** stage an edit, make the file unreadable (chmod in terminal), Apply → BLOCKED row, no write; forceApply bypass works.
- **R6-9 Checkpoint:** after an Apply, Explorer → Timeline shows `<name>/<stamp>_prechat.bak`; Restore brings old content back into the open editor; card footer "Undo last apply" also restores.
- **R6-10 Buffer base:** stage an edit to a file that is OPEN with unsaved changes → staging uses the buffer content as base (no clobber).
- **R6-11 Diff budget:** stage a very large file (>1500 lines) → card shows summary only, no inline diff, Apply still works.

## PERSIST-A — Full restart persistence (PS-1..PS-9)
Every "restart" = kill the app fully, not tab switch.
- **PS-1 Multi-split:** split button (editor strip) tap 2× → "f 2" entry + 2nd synced view; 3×/4× → "f 3"/"f 4"; 5th tap → cap Toast.
- **PS-2 Per-view close:** close "f 2" via strip X → others intact; close primary → all views cascade.
- **PS-3 Scroll lock:** padlock icon in editor strip quick-actions (top row, next to split) → lock view 2, scroll view 1 → view 2 holds position; locked view ignores keyboard auto-scroll (focus it and type — no jump).
- **PS-4 Restart splits:** restart → split views return, ACTIVE view restored (not newest), labels keep numbers.
- **PS-5 Scroll/cursor:** scroll to line ~100, switch tab, back → position + cursor restored.
- **PS-6 Folds:** fold a function, restart → still folded (all views).
- **PS-7 Blame:** toggle blame (editor strip), restart → blame state returns.
- **PS-8 Lock persisted:** lock a view, restart → padlock restored.
- **PS-9 Find bar:** open find bar, type query + case toggle, restart, reopen → query + toggle restored.

## MK — Multi-key failover (ChatKeyPool/ChatKeyFailover)
Settings → AI Agent → provider key list.
- **MK-1 Add 2nd key:** "+ Add another key" → label + masked preview; Test → "live: N models".
- **MK-2 Invalid-primary failover:** garbage key in slot 1 + real key in slot 2 → chat still works via failover; `[KEY-FAILOVER]` in Output; picker models load.
- **MK-3 Delete primary:** delete slot-1 key with extra present → provider still listed, "Failover only" hint.
- **MK-4 429 same-key backoff:** a 429'd key retries the SAME key first (watch Output timing ~1.5s/4s), does not instantly burn other keys.
- **MK-5 No keys:** remove all → clean "No API key configured" message.
- **MK-6 Remove extra:** extra key row removed → gone, cooldowns cleared.
- **MK-7 Set active:** tap "Set active" on 2nd key → chat uses it, "● Active" badge + Active key line.
- **MK-8 Manual pick survives failover:** manually-picked key that 401s → auto-fails to next key WITHOUT changing your manual pick; manual pick retried first after restart.
- **MK-9 Delete active key:** active falls back to primary silently.

---

# PHASE 2 — CORE FEATURES

## PAD-1/PAD-2 — Scroll lock + multi-split
Covered by PS-1/PS-2/PS-3/PS-8 above (same subsystem — run once, count for both).

## CE — Custom endpoint fixes
- **CE-1** Custom endpoint with working /models → picker lists REAL models only, no "custom-model" row.
- **CE-2** Bad URL/key → picker shows "⚠ Custom Endpoint — no models: <reason>"; Settings live check shows the REAL vendor reason.
- **CE-3** Send to bogus model id → clean one-line error in chat bubble; "Show raw response" expands vendor JSON.
- **CE-4** Auto mode with custom active → resolves to a non-placeholder model.
- **F6 WAF classification:** Mistral-like endpoint behind Cloudflare → live-check shows firewall-block message (NOT "rejected"), keys NOT put in cooldown; chat error mentions the block, no key cooldown.
- **F6b Manual model escape hatch:** enter manual model ID (e.g. mistral-large-latest) in Settings → picker lists it with NO /models fetch → chat works through it.

## V/AU — Vision + audio
- **R8V-1** Paperclip → "Attach image from device" → system picker → JPEG/PNG attaches as image chip (Image icon).
- **R8V-2 Gemini vision (the fix):** attach a photo with clearly identifiable content (e.g. screenshot of text) → ask "what's in this image" → Gemini describes the ACTUAL content.
- **R8V-3 Two more providers:** same image on OpenAI + one other vision provider → both describe real content.
- **R8V-4 5MB cap:** pick a >5MB image → "Image is too large (max 5 MB)" toast, nothing attaches.
- **AU-1** Attach MP3/WAV (MusicNote chip) → "transcribe this audio" on OpenAI/Custom → model references the audio.
- **AU-2** Audio on Gemini → works (inline_data).
- **AU-3** Audio on Anthropic/xAI/DeepSeek → clear "Audio attachments are not supported by X" refusal — no silent failure.

## R7 — Plan review
- **R7-1** AGENT, multi-step ask ("refactor two files, 3 steps") → agent calls plan, turn STOPS, card shows steps.
- **R7-2** Approve → executes; statuses flip pending → in_progress → completed live.
- **R7-3** Revise → input pre-filled "Revise the plan: "; tweak → new plan stages.
- **R7-4** Card persists across restart.
- **R7-5** Follow-up chips under last reply; tap inserts text, NO auto-send.
- **R7-6** With staged edits pending, chips include "Review the staged changes".
- **R7-7** Thumbs up/down under replies; tap toggles; persists after restart.
- **R7-8 Find-in-chat:** Find icon (mode row, right) → typing filters transcript live; **typed text is VISIBLE** (F2 fix); match count ticks; x restores.
- **R7-9** Old pre-R7 sessions load, no crash on missing rating key.
- **R7-10** /tools lists plan. **R7-11** ASK mode unaffected. **R7-12** Stop cancels mid-agent cleanly.
- **R8-9/10 (plan edge):** Reject → card gone, "Plan rejected." entry, agent never resumes steps later; with unapproved plan + unrelated message → agent answers WITHOUT executing steps and reminds you the plan awaits review.

## R9 — Custom modes + skills
- **R9-1** Create `.codespace/modes/reviewer.agent.md` (frontmatter-lite: name/description) → mode selector shows it; switch → "what mode are you" → describes reviewer persona.
- **R9-2** Tools allowlist: mode with restricted tools → blocked tool rejected in transcript; allowed tool runs; FlowGate still prompts.
- **R9-3** Delete the file → reopen session → AGENT + notice line.
- **R9-4** /skills → picker with Skills section; tap "Write unit tests" with a live selection → input PREFILLED + selection chip, NOTHING sent until you press send.
- **R9-5** Project skill in `.codespace/skills/tests.md` → listed with description badge + "project" source tag.
- **R9-6** I6 prompt files still insert raw text (regression).
- **R9-7** Modes section rounded/padded (UI rule).
- **R9-8 D2-VERIFICATION:** BEFORE installing this APK you already did this — if not: create a session on an OLD build first, then reopen post-update (additive field check).
- **R9-9** Model pin prefills once; user override sticks.
- **R9-10** MCP server with prompts → Skills section shows its prompts with "mcp" badge → tap prefills; server without prompts → no crash, no entries.

---

# PHASE 3 — WORKFLOW/UX + NEW CHEAP WINS

## R1 — Chat basics
- **R1-1** Markdown answer (headers/list/table/code) renders.
- **R1-2** Code block Copy (toast) + Insert-at-cursor lands at caret.
- **R1-3** Long generation + Stop → stops within ~1 line, partial kept, no error bubble.
- **R1-4** Stop while FlowGate card is up → dialog dismisses, nothing orphaned.
- **R1-5** Retry after a reply → question re-sent, no duplicate bubbles.
- **R1-6** /help /tools /models /clear /new /rename + unknown command notice.
- **R1-7** Rename via session-row pencil icon → persists across restart.

## R2 — Auto-instructions
- **R2-1** AGENTS.md with style rule in project root → answers follow it.
- **R2-2** Chip above input lists AGENTS.md; toggle OFF → rule ignored; ON → followed.
- **R2-3** Rename to copilot-instructions.md → still detected.
- **R2-4** CLAUDE.md alongside → chip "AGENTS.md +1", both rules active.
- **R2-5** Toggle persists across restart. **R2-6** Other project without files → no chip. **R2-7** Terminal `agent_prompt | head -40` shows the block.

## R3 — Attach system
- **R3-1** Paperclip → picker lists project files; search filters; tap → chip with rel path.
- **R3-2** Send with chip → answer references real content.
- **R3-3** Chip X removes it; answer no longer has the content.
- **R3-4** Type "#src/Main.kt question" → file content attached without picker.
- **R3-5** Tree icon OFF (grey) → no workspace tree in effect; ON (accent) → it is.
- **R3-6** Toggle persists. **R3-7** >12KB file → truncation noted, no crash; 3 files → total cap respected. **R3-8** "fix #bug please" → NOT attached.
- **F4 Picker scrollable:** portrait, picker dialog scrolls all the way to search bar AND file list (fix verify).

## R4 — Entry kinds
- **R4-1** AGENT tool task → tools chip between message and reply; survives restart.
- **R4-2** Bad/missing key → red-bordered error bubble with warning icon, not plain text.
- **R4-3** Old error history renders as error bubbles.
- **R4-4** Select text in editor → paperclip → "Attach current editor selection" row with file + char count → answer references the selection.
- **R4-5** Selection chip shows file name; content NOT saved into session history.
- **R4-6** Collapse selection → row still shows LAST selection (by design).
- **R4-7** User bubbles/markdown/connect cards unchanged.

## R5 — Model menu + permissions
- **R5-1** Fresh state → chip reads "Auto"; send routes to active provider.
- **R5-2** Auto entry with checkmark; pick a model → checkmark moves; back to Auto → chip reads Auto.
- **R5-3** Pin model (picker footer) → starred Pinned section; persists after restart.
- **R5-4** ASK model A, AGENT model B → each mode restores its own.
- **R5-5** Permission Level Manual → tool approval card; "Always Allow <tool>" → next call ungated.
- **R5-6** Safe → read_file no card; write_file card.
- **R5-7** Settings allowlist chips → revoke → asks again.
- **R5-8** /models opens picker; gauge plausible on Auto.

## R8 — Queue/voice/import-export
- **R8-1** While streaming, send → "Queued:" chip; auto-sends at stream end.
- **R8-2** Queued chip x cancels. **R8-3** Stop then queued message still sends.
- **R8-4 Voice:** mic icon in composer → system speech sheet → speak → recognized text APPENDS to the input box (F3 fix verify; if silent, report the `[voice]` Output line + resultCode).
- **R8-5/6/7/8** Export chat (JSON) → toast with Downloads path, file parses; Import restores as new session; corrupt JSON → "Not a valid chat export" toast; ratings round-trip.

## I1–I6 — Integration rounds (ALL BUILT — verified)
- **I1-1..5** Staged-edit strip: purple gutter bars on affected lines only; Review dialog colored diff; Apply writes to disk + strip clears; Discard clears; Timeline `_prechat.bak` restore works; non-git project shows snapshots section.
- **I2-1..6** Terminal paste chip: single-line command paste → chip → tap opens chat with explain prompt; ✕ hides; multi-line paste → NO chip; three terminal rows in picker when sources exist; shell history attach = 5 commands; "terminal output (tail)" = real scrollback; fresh app → rows absent.
- **I3-1..5** "✨ AI commit message" (Source Control pane): fills Conventional Commits message; clean repo → "No changes to describe"; no key → readable unavailable message; git pill in chat shows branch + dirty count, tap → git-diff chip included in request; non-git → no pill.
- **I4-1..6** "Attach problems (N errors)" row: right count, file:line in request; no problems → row absent; debug console output row; clipboard text row; clipboard image row; empty clipboard → toast, no crash.
- **I5-1..4** Status bar "AI: Provider · model" (spark icon), tap opens chat; no keys → absent; forced 429 → bell notification once with retry guidance; bell spacing intact.
- **I6-1..3** `.github/prompts/review.md` → Prompts section with first-line preview; tap fills input; no prompt folders → absent; other rows still render.

## R10 — Status/settings/a11y
- **R10-1** Chat header dot: green with working key, red when key removed.
- **R10-2** Tap dot → sheet: real provider/model/key count + label + active slot, permission level, MCP counts.
- **R10-3** "Open Settings" row jumps to Settings.
- **R10-4** Deliberate 401 (bad key) → red "Cooling down" row appears, disappears ~10 min later.
- **R10-8** TalkBack: clear-chat, close, status dot announce.
- **R10-9** Type real project string in the attach picker search → "Attach search results" row → chip "search: <q>" → reply references matches.
- **R10-10** No-match query → "No content matches found" toast.
- **R10-11** "Attach screenshot of app" → screenshot chip → vision provider describes the UI.
- **R10-12** Screenshot with vision-incapable provider → expected reject notice.

## CW — New cheap wins
- **CW1 Problem matchers:** open terminal in a project with a build script; run a task that errors (e.g. a Kotlin/Gradle file with a deliberate error) → the error lands in the PROBLEMS panel with file:line + message (not just raw terminal text).
- **CW2 Git-history attach:** paperclip → "Attach git history (N commits)" row (History icon, after the problems row, only in a git repo) → tap → chip; send → reply references the real commit messages. Empty repo → row absent.
- **CW3 Conditional breakpoints:** tap gutter left of a loop line → solid red dot breakpoint. LONG-PRESS the dot → condition editor sheet opens; set condition `i>5`; the marker becomes a HOLLOW RING. Run with the debugger attached → it pauses only when `i>5` is true. Long-press again → edit/remove/log-message options work.
- **CW4 Output links:** in the Output panel, tap a line containing `path/File.kt:42` → the file opens at line 42.
- **CW5 Explorer badges:** introduce a syntax error in a file → its Explorer row gets a red count badge; warnings → amber; parent folders roll up child counts; TAP the badge → Problems panel opens pre-filtered to that file; fix the error → badge clears.
- **CW7 Snippets:** create `.codespace/snippets/kotlin.json` with `{"MyFun": {"prefix":"mfun","body":["fun myFun() {","\t$0","}"]}}` → type `mfun` in a .kt file → pack entry appears in completion (with pack source) → expands with $0 tabstop. Same-prefix pack entry overrides the built-in.
- **CW7b Language config:** create `.codespace/language-config.json` with `{"kotlin":{"comments":{"lineComment":"##"}}}` → Toggle Comment now inserts `## ` (remove the file → back to `// `); a brackets section (e.g. `[["<",">"]]`) adds that auto-close pair.
- **CW8 Retry + export:** after any reply, "Retry" chip at transcript bottom → pops the last turn and re-sends the same text. "Export .md" chip → writes `.codespace/exports/chat-<ts>.md` → Toast shows the rel path; open the file → real markdown with You/Copilot/Tools/System roles.

## SPEC — Chat panel polish (built WITHOUT per-item approval — flag anything off)
- **SPEC-1 IME fix (the original bug):** tap the input → keyboard opens → the SEND BUTTON stays fully visible above the keyboard; toolbar row (attach/tree/history icons) sits ABOVE the input as a separate row, not inside it.
- **SPEC-2 Borderless input:** input is flat (no outlined border) on a rounded-12 subtle box; placeholder "Ask Copilot…"; up to 4 lines then scrolls.
- **SPEC-3 Send button:** filled rounded-square accent button with white Send icon; grey/disabled when empty; while streaming it becomes a red STOP chip; sending during a stream still QUEUES (R8 semantics).
- **SPEC-4 Mic target:** mic icon ≥32dp padded target, comfortable to hit.
- **SPEC-5 Rounded top:** chat container has 12dp rounded top corners; consistent with panel rules.
- **General:** flag ANY spacing/icon-size/color inconsistency — these were self-directed per your grant; you review post-hoc.

---

# PHASE 4 — STANDING ITEMS

## PerfProbe
- **F5** Open a large file (~2k+ lines), type ~30s, scroll heavily → Output shows [perf] lines; after ~5s idle they STOP (one quiet line then silence); any STALL line has HH:MM:SS timestamp + worstFrame@time.

## Batch K — Provider cross-routing
- **K-1** Settings → switch ACTIVE provider to each configured one in turn → FIRST send after each switch gets a reply from THAT provider (no 4xx model-id mismatch, no silent no-op). Repeat for all configured providers, including the custom endpoint once F6 passes.

## Standing diagnostics (optional, report only if seen)
- **Emoji IME:** type an emoji in the terminal → check Output for `IME:` lines (the old diagnostic).
- **SPEC-U8-1 verdict + SPEC-U4-1 verdict** (U4 rides F1) — give your take after the run.

---
**Report format:** item number → PASS/FAIL + one line of detail on fails. Batches can be split across sessions; report per phase.
