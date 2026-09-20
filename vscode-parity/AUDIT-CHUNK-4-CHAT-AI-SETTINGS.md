# AUDIT-CHUNK-4 — CHAT / AI / AGENT / SETTINGS (2026-09-20, audit only)

READ = verified in repo this session. Device status = #2854 results only.
Cross-refs: chunk 1 (F10 key pool, F11 endpoints), chunk 2 (W7/W8 write+workdir), parity scoreboard (CW items).

---

## C1. Agent tool dispatch model (answers C-4's "was a tools array even sent?")

- READ: tools are NOT a native `tools` JSON array in the request. `AgentTools.TOOLS_DESCRIPTION` is embedded in the system prompt (CopilotChatPanelOverlay:425); the model replies with `<tool>{...}</tool>` tags, parsed by AgentTools (:133-146 hasToolCalls/stripToolCalls + parse loop). BUILTIN_TOOL_NAMES :152-166 stays prompt-doc only.
- Consequence: tool reliability = model compliance. When a tool call fails (see C2), the model narrates "here are the commands to run" instead — exactly the #2854 C-4 symptom.
- VS Code: language-model tools are a structured request field (comparison (g) READ tool gating in vscode-copilot-chat — provider-side structured dispatch, no prompt parsing).
- Gap size: LARGE structurally (prompt-driven tools) but WORKING in practice for major providers; migration = own project (parity §4 did NOT include it; not recommended now). Risk: reliability (C-4 class).

## C2. C-4 root cause — agent git degraded in in-app project. READ-CONFIRMED END-TO-END

- Chain (READ): ProjectShellScreen:4898 passes `projectRootPath = ProjectPathResolver.resolveProjectRoot(...)` — a HOST path (chunk-1 F1) — into the chat panel; system prompt/workspace ctx presents it as the project root. Model uses it as run_command workdir → AgentTools.runCommand :222 → ProotInstaller.execOnce :1372 `[ -d "$workdir" ] && cd "$workdir"` runs in the GUEST — a host `filesDir/projects/...` path does not exist there (guest sees `/host-files/projects/...`), so the cd SILENTLY SKIPS and the command runs in guest HOME → git: "not a git repository" → model degrades to copy-paste suggestions.
- No hostToGuest translation anywhere on this path (grep: none between chat panel and execOnce).
- Device: C-4 VERIFIED FAILING on #2854; both ends of the chain now READ-confirmed. Fix candidate (already the plan's terminal-translation commit): translate workdir host→guest in runCommand (or have the prompt advertise the guest path alongside the host path). Timeline :99-104 already does this translation for SCM — same helper.
- VS Code: no analog. Gap size: MEDIUM (one translation point). Risk: UX + wrong-repo git ops.

## C3. Model picker — Mistral group missing (D-group, #2854)

- READ: custom endpoints render groups via buildCustomMenuGroups (CopilotChatPanelOverlay:265-282): a group appears ONLY if `availModels.filter { startsWith(pid+":") }` is non-empty. availModels comes from (a) manual models (CE-ESCAPE flat list :255-258, always present once entered) and (b) fetchLiveModelEntries :299-325, which iterates `ChatProviderRegistry.available(tokenStore)`.
- Wiring gap (READ): a custom endpoint whose provider `isAvailable` is false (no key saved in that endpoint's slot) is NOT in available() → never fetched → no live entries; if the user also has no manual models for it, the group renders NOTHING — no group, no "no models: <reason>" row (error rows are only produced inside fetchLiveModelEntries for providers it iterates).
- So "Mistral models missing group" = key-slot empty or fetch silently not attempted; the UI gives no signal either way. ALSO: fix-batch-3 memory notes Mistral raw-response paste from user still AWAITED for the model-rejection case (that is a different symptom: fetch OK, send 4xx "Invalid model").
- VS Code: Language Models editor lists every configured entry regardless of state (MK restructure precedent, MEMORY).
- Device: D-group VERIFIED FAILING (group absent). Gap size: MEDIUM (wire error/empty rows for unavailable custom endpoints). Risk: UX only.

## C4. Key pool / failover / secrets

- ChatKeyPool (chunk-1 F10 READ): slots + order + active key; ChatKeyFailover 401/403/429 policies (memory, shipped). AgentFlowGate :32-42 READ: allowlist fast-path + per-call deferred approval card; ChatPermissionStore allowTool persists ALWAYS-ALLOW.
- Device: MK-1..MK-9 retests NOT yet run on a #2854+ APK (fix-batch 3 green #2844; MK re-test after MK restructure ships — memory). D-1/D-2 UNVERIFIED.
- VS Code: ISecretStorageService (secrets.ts READ :88-101). Gap size: SMALL. Risk: low (storage verified; retest pending).

## C5. Pending edits staging in chat (R6) — chunk 2 covered apply/undo; here: the dispatch

- READ CopilotChatPanelOverlay:617-640: AGENT mode write_file stages ungated (:624), plan tool ungated (:629), everything else through AgentFlowGate. Allowlist (custom mode) restricts BEFORE gate.
- Remaining hole = W7 (chunk 2): AgentTools.writeFile direct + AgentApiServer /tool/write_file. The CHAT path is clean.
- Device: R6-1..R6-11 retests pending (same batch as MK). Gap size: SMALL on chat path. Risk: data-loss only via the W7 bypass.

## C6. Custom modes / skills / plan store

- CustomModeStore :77 tools allowlist from frontmatter; skills context injection :1042; ChatPlanStore.updateFromArgs (AgentTools "plan" branch :173). All READ-adjacent (grep hits), feature shipped pre-#2854.
- Device: not in #2854 batch. VS Code: prompt FILES with skills view (integration map I-rounds, backlog). Gap size: NONE this chunk. Risk: none.

## C7. Pending roadmap items surfaced by this chunk (parity scoreboard + fix batches)

- CW2 problems+SCM-history attach rows, CW8 retry/session export, chat polish (Phase 1 authority) — PENDING implementation.
- MK restructure A/B/C shipped in code (CustomEndpointStore per-endpoint registry READ; picker groups :265); re-test gated on next device batch.
- Voice STT, sessions-as-tabs, tokenization audit — backlog (parity §4), untouched.
- vs #2854 failures: no chat-surface failures OTHER than C-4 (tools) and C3 (picker group) were reported.

---

## CHUNK-4 FINDINGS (ranked)

1. **C2 workdir host/guest mismatch** — READ-confirmed end-to-end; single-translation fix already planned as terminal-translation commit.
2. **C3 custom-endpoint group silence** — unavailable endpoints render nothing; wire an explicit "no key / not fetched" row so the group is never silently absent.
3. **C1 prompt-driven tools** — structural, WORKING; record as known limitation, not a fix item this round.
4. **C4/C5 retest debt** — MK + R6 re-test batches are the gate for several shipped features; keep them with the #2854 re-test round.

## Device-verified ledger (chunk 4)

- C-4 agent git degraded (in-app project): VERIFIED FAILING on #2854 → root cause READ (C2). Fix = the planned /host-files translation commit.
- Mistral group missing: VERIFIED FAILING on #2854 → mechanism READ (C3: silent unavailable endpoint); PLUS user raw-response paste still awaited (separate model-rejection case, fix-batch 3).
- MK (D-1/D-2), R6 (R6-1..11), custom modes, skills: UNVERIFIED — re-test batch pending.

Done: 4. Next: 5 — UI shell/panes/bottom panels (Problems/Output/terminal A-5, Timeline guard verification, root lock, D-pad, split views, settings + backup) + final writer-matrix ledger.
