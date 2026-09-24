# MASTER-CONNECTIONS — cross-group wiring ledger

Companion to MASTER-GAPS.md (which ranks findings; this explains how the groups are WIRED together). Synthesizes the CONNECTIONS/edges sections of all 18 group audits plus the TextMate (Editor) and ImageGen (Chat) addenda. Every row traces to a group doc's own ledger (C-XX / numbered edges) or to source-verified evidence cited there. Purpose: a fix in one group often lands on a seam owned by another group — this ledger is where those couplings live so the fix plan can batch them.

## 1. Shared state stores (who writes, who reads, where the seam breaks)

| Store | Owner / location | Writers | Readers | Seam risk |
|---|---|---|---|---|
| SharedPreferences `projects` (JSON index) | HomeScreen | HomeScreen (sync replace-all, OG01/OG02), ProjectShellScreen (:1365 quick-create appends), Settings ("Projects" clear :185), cloud restore (EX/Recovery) | Everything that opens a project (by id→name/path); ProjectPathResolver; session restore | OG01: replace-all drops unpushed locals from list AND index — files orphaned with no recovery entry point |
| JsonSettingsStore (filesDir JSON) | Settings (SK01) | Settings screens, editor settings, FeatureToggleStore-adjacent | Editor, Terminal, Preview (md_preview_auto), every feature with a preference | SK01 non-atomic writeText + corrupt-parse→defaults resets ALL settings — write-safety is a prerequisite for every other fix's persistence (owner ruling) |
| SecureTokenStore (EncryptedSharedPreferences) | 18b auth | AuthScreen via CodeSpaceApp (tokens), Settings (BYOK keys, PIN) | AppModule OkHttp interceptor (401 auto-refresh), AgentConnectorManager, ConnectorsApiClient | OG05 fallback stores a fake refresh token; OG06 doc says memory-only but persists |
| `copilot_chat` prefs | Chat | Chat surfaces, AgentApiServer terminal snapshots (Integration) | Chat restore | Third writer with its own JSON serialization; S01-family write checks apply |
| EditorBufferStore | Editor | EditorPane sync of every open tab | Chat PendingChangesStore.stage reads buffer-first, then disk | C03: staged-vs-disk divergence; C-CH09 path-keyed staging survives tab switches only if keys canonicalize (PLAN A) |
| PendingChangesStore (staged chat edits) | Chat | Agent write_file (staged), Apply (flush) | searchFiles overlay read-through; SR03/SR04 Replace All writes disk directly AROUND it | C-CH06: agent that replaces via UI tool then reads via read_file sees disk truth while its own edits sit staged — staged overlay must be honored by EVERY disk-writing surface |
| BackupManager rootfs + RG05 prefs backup | Recovery | Installer, restore paths (RG02 unchecked "✓ Restored"), this user's uninstall-rebuild cycle | Terminal (proot runtime), ShellHistory (.bash_history), RG05 backup list | RG05 selective survival: projects.xml survives uninstall; crash logs and token store don't (re-login cost only) |
| CrashLog entity (Base44 backend) | Integration (reportCrash) | App crash reporter (posts to Superagent) | Nothing reads it — VERIFIED 0 records (RG01) | The whole crash pipeline reports to a sink nobody consumes; months of crashes are unretained |
| FlowGate (terminal exec gating) | Terminal | User consent grants | run_command, SCM, Build, DAP installs | IG02: schedule_task bypasses it structurally (ungated unattended execOnce, persisted); IG15 use_connector lacks per-call consent — both must fold into the redesigned gate |
| AppOutputLog / Output tab | Problems | [BAND-DIAG], [DAP], [TestLens], build output | Problems parsing (PR03 cap eats failures past 2000 lines), users | Shared sink means parsing bugs in one producer corrupt others' evidence |
| NotificationStore | Recovery/Notifications | Chat quota errors (C-CH04), SG04 token-bearing text, debug events | Notification drawer | SG04 fix must scrub AI-source text too (chat is an SG04 producer) |
| ScmState host↔guest choke point | SCM | One place converts host→guest (GOOD pattern) | Terminal, DAP launch | DG02 class: translation exists but is applied on SOME paths (DAP live-update, agent file tools CH02) and omitted on others — every path-taking feature must route through ONE translator |
| TimelinePanel / 20s snapshot loop | SCM/Editor | Snapshot walkTopDown (PG03 no change gate) | Chat checkpoints share the same v2 dirs (C-CH02) | Grouped retention (SC16) protects `_prechat.bak`; SG02 restore discards the overlay before copying back — restore paths must be fixed together |
| TextMateEngineHolder singleton | Editor addendum | Initialize (assets), latent loadGrammarFromPath | IncrementalHighlighter/TmHighlighter | TM04 plain maps behind synchronized holder; TM02 latent untrusted-grammar API |

## 2. Path identity & translation network (the single most cross-cutting wiring in the app)

Three separate problems share one root: **paths appear in multiple dialects (host absolute, guest/proot, relative, tab key) and code matches them by different rules.**

1. **Containment (security):** 10 mandatory call sites in MASTER-GAPS' cross-cutting item (EX05, EX04, RG03, RG04, RG07, IG01, VG02, VG02-b, WorkspaceContextProvider:281, OG04). One utility, `ProotInstaller.kt:107` as the reference boundary form.
2. **Dialect translation (correctness):** ScmState converts at ONE choke point (good); run_command converts workdir at ONE choke point (good); DAP converts at launch but NOT at live-update (DG02); agent file tools convert NOWHERE (CH02); Problems-panel jump exact-string-matches tab paths (pathname-mismatch → new tab → "Could not read file: denied"); PB14's canonical jump chain (guest→host, canonical tab match) is the correct pattern to reuse everywhere.
3. **Key canonicalization (state integrity):** tabs keyed by raw path strings, staged edits keyed by path (C-CH09), buffer store by path, breakpoints in TWO representations (DG08: UDM map vs EditorPane local). PLAN A (per-file canonical store keyed by canonical path) is the owner-approved design that fixes the whole family — EditorPane tab identity, PendingChangesStore keys, and the debugger's breakpoint state should ALL derive from it.

## 3. Systemic pattern families (fix together, not as isolated patches)

| Family | Member gap IDs | Fix direction |
|---|---|---|
| **S01 unchecked operation success** (report success without verifying) | G01, EX01, EX02, EX06, SR03, SR04, SG05, SG16, SK02, IG05, RG02, OG02, XG05 (TP03 prose-match upstream), TG01 (decorative) | One typed-result design; ScmModels GitResult is the in-tree model; TP03 ProotResult prose-classification is the upstream seam to replace first — XG05's install prose-prefix and SG16's "fatal:" contains both inherit it |
| **Canonical-path containment** | EX05, EX04, RG03, RG04, RG07, IG01, VG02, VG02-b, WorkspaceContextProvider:281, OG04 | One utility; MASTER-GAPS cross-cutting item is the canonical list; ProotInstaller.kt:107 = spec |
| **Doc-vs-code honesty** | IG07, VG01, OG06, TM06, SK07 | Audit docs against code in the same pass as each fix |
| **Duplicate/orphaned implementations** | VG04 (inline AXML duplicate), VG10 (second ELF parser), IG06 (SSHJ+TOFU stack), IG03 (restoreAll never called), IG04 (download() orphaned) | Delete-and-wire-to-the-live-path before Recovery/startup work (IG06 ruling precedent) |
| **Placebo/dead UI** | TG01 (TestLens writes a log line, returns), SK09 (keybinding recording never assigned), IG05 (cancel/retry overwritten), VG09 (adb logcat, no adb in PATH — BI25), TM03 (theme pipeline, no loader caller — TE07) | One device check each before delete-or-fix; several are cheap wins to FIX, not remove |
| **Fixed-cadence polling (should be events/flows)** | PG02 (2s LSP recovery poll), PG04/TP08 (chip loop), PG05 (McpPanel 5s poll) | StateFlow/event conversion; measure-first rule (no gap joins top tier without a PM row) |
| **Two-registry drift** | VG12 (media dispatch vs FileDetector), TM05 (TmIntegration enum vs grammar fileTypes) | Single registry per concept |
| **Crash on untrusted input (EX05-depth class)** | VG03, VG04 (AXML OOM), VG05 (uncapped readBytes x4), RG03 (tar header sizes), RG04 (name truncation) | Shared capped-read helper (AxmlDecoder.readBytesStreaming pattern) + allocation-before-validation sweep |
| **Secrets in observable surfaces** | SG16 (token inside bash command string → proot argv + timeout echo), SG04 (token in timeout text), IM01 (key= URL query param) | Typed GitService.clone with token param; header auth; scrub pass on the shared NotificationStore |
| **Data-loss chains** | G01→TB03→TB01 (coupled top-tier chain); OG01+OG02 (compound pair: active project vanishes while deleted project resurrects in ONE sync); OG03 (name-reuse destroys trashed predecessor) | Chain-level fixes: a TB03 dialog alone cannot recover an already-deleted backup; OG01's fix needs an "add existing folder" recovery path, not just merge logic |

## 4. Group-to-group edge index (top edges; full ledgers live in each GROUP doc)

| Edge | Coupling (source doc: C-id) |
|---|---|
| Editor ↔ Tabs ↔ Shell | C01/C02 (GROUP-EDITOR): canonical tab identity, jump 0-based vs 1-based convention, ProjectShellScreen state threading |
| Editor ↔ Chat | C03, C-CH01, C-CH02, C-CH09: buffer-first staging, undo gate, snapshot-dir sharing, path-keyed staging |
| Chat ↔ Problems/SCM | C-CH04, C-CH05: quota errors → NotificationStore; CW2 attach rows can TELL the model PR01/PR03 false build status as truth |
| Chat ↔ Terminal/Agent | C-CH06, C-CH08: staged-overlay vs disk-truth split across agent tools; execOnce spawn cost; MCP long-lived processes outside per-call hygiene (deliberate) |
| Debugger ↔ LSP/Terminal/Editor | C-DB01..C-DB09: shared spawn path (IdeEnvironment.forSubprocess), guest-path duplication (DG02), gutter/breakpoint dual state (DG08), DAP/LSP share the missing "request" protocol branch (LS01 class) |
| Explorer ↔ Archive/Viewers ↔ Downloads | VIEWERS edges 2, 10: untrusted APK entry into decoders; extraction containment by construction (basename) |
| HomeScreen ↔ Cloud ↔ Trash | APPSHELL edges 1-9: OG01/OG02 sync pair, trash interplay OG03, token store outside prefs-backup |
| Recovery ↔ Shell ↔ Terminal | APPSHELL edge 1, RECOVERY: safe-mode entry handoff; RG05 selective backup survival across this user's uninstall-rebuild cycle |
| Settings ↔ Everything | SK01 write-safety prerequisite (owner ruling); OG01's fix itself persists state → must come after SK01/SK02 |
| Performance ↔ All pollers | PG02/PG04/PG05 conversions touch LSP, Terminal, Extensions panels — do them in one pass |

## 5. Fix sequencing implications (what the wiring forces)

1. **SK01/SK02 first** (owner ruling): every other fix persists config/preferences through or alongside the settings store; a truncated write can reset any fix's own settings. OG01's merge fix and the future S01 typed-result design BOTH persist state → both inherit SK01 risk until it lands.
2. **TP03 typed ProotResult early** within the S01 pass: it is the upstream prose-classifier that SG16, XG05, RG02-class restore messages, and (per C-PG07) other prose checks inherit from.
3. **Containment utility before the file-flow features** that add new file paths (OG04 wizard, viewers' extract, chat attach): each new surface is a new call site; ship the utility first so new code consumes it.
4. **PLAN A canonical store before CH02/DG02/EditorPane-jump fixes**: the path-dialect family should collapse into one canonicalization point, not four per-surface patches.
5. **TG01 is a first-wave one-tap device check** (decorative TestLens) — not because it's severe, but because it is the cheapest way to confirm whether "button does nothing" symptoms the user sees are this class.
6. **OG01+OG02 in one pass** (compound pair note) — merge logic preserving unpushed locals AND checked cloud delete feeding a re-sync; include the "add existing folder" recovery entry point in the same change.
7. **Delete-the-duplicate pass** (VG04, VG10, IG06, IG03, IG04) can run parallel to everything — it reduces the codebase the rest of the fixes must reason about.

*Ledger generated 2026-09-24 from the 18 group audits + TextMate/ImageGen addenda. Rankings and gap tables live in MASTER-GAPS.md; this file records wiring only.*
