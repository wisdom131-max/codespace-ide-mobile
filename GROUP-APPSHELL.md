# Group 18b — App Shell & Onboarding

Source audit of the app-shell/onboarding cluster (~3.0k Kotlin lines), 2026-09-24. Scope per owner's 18a/18b split ruling: HomeScreen, AuthScreen, ProjectWizard, ProjectTemplates, CodeSpaceApplication (shell/startup surface), MainActivity (shell glue), CodeSpaceApp nav, AppModule, WorkspaceShapes, ShellHistorySearchOverlay, TextExpansionSheet — normal depth (not EX05-depth). The crash/safe-mode pipeline inside CodeSpaceApplication/MainActivity was already audited in Recovery; this group covers the remaining shell behavior.

**Verdict up front:** the auth stack is the best-engineered part of this audit (Credential Manager → Firebase → backend JWT exchange, EncryptedSharedPreferences, memory-vs-persistent token separation that mostly matches its docs), and the scaffold returns a typed result. The soft spots are the HomeScreen sync model (cloud-authoritative replace-all that can silently drop offline-created local projects from the index) and one more path-segment containment call site for the cross-cutting pattern (wizard name regex allows `..`).

## Features (AS01-AS18)

| ID | Feature | Source |
|---|---|---|
| AS01 | Google sign-in: Credential Manager → Firebase → backend JWT exchange (`POST /auth/google`), typed error surface, backend-unreachable fallback to raw Firebase token | `ui/screens/AuthScreen.kt:56-82,98-165` |
| AS02 | SecureTokenStore: EncryptedSharedPreferences (Keystore-backed) for refresh token, role, BYOK keys, PIN hash; access token warm-start | `data/SecureTokenStore.kt:18-108` |
| AS03 | Auth routing + session restore: refreshToken null → AUTH; lastProjectId → last project route | `ui/CodeSpaceApp.kt:93-96,106-114` |
| AS04 | HomeScreen project list: local prefs store + cloud auto-sync on launch ("Synced"/"Offline" status) | `ui/screens/HomeScreen.kt:39-56,163-178` |
| AS05 | Project delete → trash (P29) + cloud delete | `ui/screens/HomeScreen.kt:332-339` |
| AS06 | Project rename pushes to cloud | `ui/screens/HomeScreen.kt:407` |
| AS07 | ProjectWizardDialog: 3 steps (type → location → name), name regex `[a-zA-Z0-9_\-. ]+`, non-empty-exists pre-check, typed ScaffoldResult | `project/ProjectWizard.kt:39,133-185,336-374` |
| AS08 | ProjectTemplates.scaffold: 7 types (Android/Flutter/RN/Web/Node/Python/Empty), trash-only dir cleanup for name reuse, typed result | `project/ProjectTemplates.kt:37-73` |
| AS09 | Biometric/PIN launch lock (once per process launch) | `ui/CodeSpaceApp.kt:70-73`, `SecureTokenStore.kt:48-53` |
| AS10 | Theme persistence (saveTheme) | `ui/CodeSpaceApp.kt:64-66` |
| AS11 | Safe-mode entry handoff (`safeMode` param from the Recovery crash-loop breaker) | `ui/CodeSpaceApp.kt:52` |
| AS12 | Battery-optimization exemption prompt, asked once (flag in `app_prefs`) with OEM fallbacks | `MainActivity.kt:340-360` |
| AS13 | AppModule DI: Json, OkHttpClient with auth interceptor + 401 auto-refresh, Retrofit, ApiService | `di/AppModule.kt:25-117` |
| AS14 | ShellHistorySearchOverlay: command palette over prefs history + proot `.bash_history` | `ui/panes/ShellHistorySearchOverlay.kt:45-57` |
| AS15 | TextExpansionSheet: shortcut CRUD for text expansions | `ui/panes/TextExpansionSheet.kt:29-55` |
| AS16 | WorkspaceShapes: shell background shapes | `ui/WorkspaceShapes.kt` |
| AS17 | MainActivity shell glue: activity launch, intent/settings handoffs, crash-log relay to upload (Recovery-covered) | `MainActivity.kt` |
| AS18 | CodeSpaceApp route shell: Routes object, lock gate, sync status wiring | `ui/CodeSpaceApp.kt:43-120` |

## Cross-group edges (10)

1. **→ Recovery (Safe Mode):** MainActivity crash threshold hands `safeMode` into CodeSpaceApp — the 18b entry point of the crash-loop breaker audited there.
2. **→ Settings SK01:** HomeScreen persists the project index in a THIRD store (SharedPreferences `projects`) beside JsonSettingsStore (filesDir) and `app_prefs` — settings write-safety applies to all of them; RG05's prefs-backup covers `projects.xml` but which of these land in that file needs RT21 re-verification.
3. **→ Integration/ConnectorsApiClient:** the backend-unreachable fallback stores a raw Firebase ID token as `refreshToken` — the documented cause of the past 401-retry failure (AuthScreen's own comment explains the refresh can never work until next real login).
4. **→ CROSS-CUTTING CONTAINMENT PATTERN:** wizard project name → path segment (regex allows `..`/`.`; see OG04 — new mandatory call site #10 in MASTER-GAPS' cross-cutting item).
5. **→ Trash (P29):** AS05 deletes via WorkspaceManager.moveProjectToTrash; AS08's name-reuse cleanup destroys a soft-deleted predecessor's trash (OG03).
6. **→ Notifications:** VS Code-parity welcome banner role is served by NotificationStore (Recovery group); no separate shell banner.
7. **→ Terminal/rootfs:** ShellHistorySearchOverlay reads `filesDir/ubuntu-rootfs/root/.bash_history` — depends on the proot container's presence (RG05 backup scope).
8. **→ Editor:** TextExpansionStore expansions feed the editor insert pipeline.
9. **→ Cloud backup (RG):** projects index is in the RG05 prefs-backup list; access/refresh tokens are in EncryptedSharedPreferences, which prefs-backup does NOT cover (tokens lost on forced uninstall — but they're re-minted at next sign-in, so impact is one re-login).
10. **→ AgentApiServer:** terminal AI snapshots write into the same `copilot_chat` prefs the chat surfaces read (Integration group); HomeScreen is upstream of both.

## VS Code comparisons (8)

1. **Getting Started:** `GettingStartedPage extends EditorPane` (`src/vs/workbench/contrib/welcomeGettingStarted/browser/gettingStarted.ts:123`) — onboarding is a first-class editor with walkthrough categories; the app's equivalent is the HomeScreen project list plus the wizard (no walkthrough content).
2. **Onboarding experiments:** `welcomeOnboarding/browser/onboardingVariationA.ts` exists in the clone — VS Code ships controlled onboarding VARIATIONS; the app ships one static first-run path (battery prompt once).
3. **Walkthroughs:** `welcomeWalkthrough` contrib (walkThrough.contribution.ts) provides guided step content; the app has none (learning delegated to the user).
4. **Authentication:** `AuthenticationService extends Disposable implements IAuthenticationService` (`src/vs/workbench/services/authentication/browser/authenticationService.ts:93`) — VS Code abstracts providers (sessions minted by extensions, secrets delegated to the OS keychain); the app's Google→Firebase→backend-JWT chain with EncryptedSharedPreferences is architecturally comparable — the strongest parity in this group.
5. **App state:** `class StateService` (`src/vs/platform/state/node/stateService.ts:185`) — ONE persisted app-state service; the app scatters state across SharedPreferences stores (`projects`, `app_prefs`, `terminal_history`, `copilot_chat`) plus JsonSettingsStore files — the SK01 batch should consolidate the write path even if the stores stay separate.
6. **Storage abstraction:** `IStorageService` (`src/vs/platform/storage/common/storage.ts:61`) — one typed key-value abstraction for ALL persistence; the app's stores each hand-roll their own JSON serialization.
7. **Welcome banner:** `welcomeBanner` contrib exists in the clone (call-to-action surface); the app's analog is the notification drawer, not a shell banner — honest absence.
8. **Agent sessions in the welcome area:** `welcomeAgentSessions` contrib exists — VS Code surfaces agent-session resumption at welcome time; the app's parallel is lastProjectId session restore (AS03) — same intent (resume where you were), smaller scope.

## Device checks pending (OB01-OB24)

- **OB01** sign-in flow end-to-end on device (Credential Manager sheet → backend exchange → projects load)
- **OB02** backend-unreachable sign-in (airplane mode mid-login) → app still enters degraded; Connectors Hub shows its sign-in error; next online login mints real pair (OG05 self-heal)
- **OB03** cold start with valid refresh token → straight to last project (no auth flash)
- **OB04** PIN/biometric lock: enable → kill app → relaunch → lock shown once per process
- **OB05** create a project OFFLINE (airplane mode) → appears locally ("Saved locally (offline)")
- **OB06** then go online and let auto-sync run → **does the offline-created project disappear from the home list? (OG01 — the group's key check)**
- **OB07** delete a project offline → trash; cloud copy still listed after next sync? (OG02)
- **OB08** wizard: name `..` in an empty parent dir (OG04 escape attempt; scratch dirs only)
- **OB09** wizard: name reuse after a soft-deleted project with the same name → trash destroyed without warning (OG03)
- **OB10** each of the 7 template types scaffolds and opens
- **OB11** scaffold into a dir containing only `.ide-trash` → cleaned and reused (AS08)
- **OB12** scaffold result failure surface (full disk / bad path → error message, not crash)
- **OB13** battery-optimization prompt appears exactly once; OEM fallback path on this device
- **OB14** shell history overlay lists palette commands + real `.bash_history` entries from proot
- **OB15** text expansion: create `;gs` → `git status`, trigger in editor
- **OB16** theme change persists across relaunch
- **OB17** safe-mode entry after 3 crashes → shell opens without auto-open project (with Recovery RT08)
- **OB18** 401 auto-refresh interceptor: expire access token → next backend call refreshes silently (AppModule:53)
- **OB19** sync status strings render for 0/1/N projects ("Synced (1 project)" singular form)
- **OB20** rename project offline → next sync behavior (does cloud keep old name? OG-adjacent)
- **OB21** clear app data → sign-in again → cloud projects repopulate (index rebuild from cloud)
- **OB22** forced-uninstall cycle: which shell state survives via prefs-backup (RG05 list) vs tokens (re-login)
- **OB23** ShellHistorySearchOverlay with rootfs absent (fresh install) → no crash, palette history only
- **OB24** HomeScreen with 20+ projects: list scrolls, sync status doesn't stutter

## Gaps (OG01-OG06)

| Gap | Priority | Behavior / risk | Evidence and check |
|---|---|---|---|
| **OG01** | **HIGH in group, silent index loss (S01 family)** | Auto-sync is cloud-authoritative REPLACE-ALL: `projects.clear(); projects.addAll(cloud); saveProjectsLocal(context, cloud)` — any locally-created project not yet pushed to cloud (offline creation, push failure) is dropped from the in-memory list AND the persisted index on the next successful sync, silently. Files survive on disk but become invisible/ unreachable from the shell until the user re-discovers the folder. Cloud-empty edge: a fresh/emptied cloud account wipes the visible project list entirely. | `ui/screens/HomeScreen.kt:166-176`; OB05/OB06 (the group's key device check). |
| **OG02** | MEDIUM, S01 family | HomeScreen launch- and delete-time `deleteProjectFromCloud(accessToken, id)` returns a Boolean that is never checked — cloud delete failure is silent; the cloud copy resurrects into the visible list on the next sync (with OG01's replace-all, a "deleted" project can reappear). | `ui/screens/HomeScreen.kt:126-143,332-339`; OB07. |
| **OG03** | MEDIUM-LOW, silent data-loss-adjacent (TB01 family) | ProjectTemplates name-reuse cleanup: a directory containing ONLY `.ide-trash` (the soft-deleted predecessor with the same name) is `deleteRecursively()`'d without warning — the trash copy of the old project is destroyed the moment a new project reuses its name; no "restore old project?" prompt. | `project/ProjectTemplates.kt:44-50`; OB09. |
| **OG04** | MEDIUM-LOW, security (containment pattern, call site #10) | Wizard name regex `[a-zA-Z0-9_\-. ]+` allows `..`, `.`, and `...` as project names; `File(parentDir, name)` and `ProjectTemplates.scaffold`'s `File(rootParent, projectName)` use the name as a path segment. Escapes are blocked only INCIDENTALLY (grandparent/parent normally non-empty → "already exists" error), not by validation. Add to the cross-cutting containment utility's mandatory call sites; also reject `.`/`..`-equal names in the regex step. | `project/ProjectWizard.kt:173,336`; `project/ProjectTemplates.kt:43`; OB08. |
| **OG05** | LOW, documented degraded mode | Backend-unreachable fallback stores the raw Firebase ID token as BOTH accessToken and refreshToken — the stored "refresh" token can never refresh (POST /auth/refresh 401s on it); Connectors Hub stays dead until the NEXT successful login re-exchanges. Self-healing by design and honestly commented, but the user-visible symptom (Hub 401s) gives no hint that a re-login is the fix. | `ui/screens/AuthScreen.kt:132-165`; OB02. |
| **OG06** | LOW, doc-vs-code (IG07/VG01 family) | SecureTokenStore's class doc says "Access tokens are kept in memory only" and CodeSpaceApp:106 comments "kept in memory only (not persisted)" — but `lastAccessToken` IS persisted in EncryptedSharedPreferences (SecureTokenStore.kt:105-108) for warm-start. Storage itself is the right kind (encrypted, keystore-backed); the two comments are false and should be corrected before they mislead a future change. | `data/SecureTokenStore.kt:105-108`, `ui/CodeSpaceApp.kt:106`. |

**Strengths:** the auth chain is genuinely well-built — Credential Manager (no raw password handling), backend JWT exchange with typed failure, EncryptedSharedPreferences with Keystore master key, and a memory-vs-persistent token split that mostly matches its docs; ScaffoldResult is a TYPED result (the S01 family's target shape) with a clear failure message path; the battery-optimization prompt's ask-once + OEM-fallback design shows real device experience; the wizard's non-empty pre-check blocks most accidental overwrites; scaffold templates are pure in-process file writes (no network, no proot). **Cross-links:** OG01/OG02 → S01 typed-results pass and each other (delete + replace-all compound resurrection); OG03 → TB01/P29 trash interplay; OG04 → cross-cutting containment item (row 10); OG05 → Integration ConnectorsApiClient refresh; OG06 → IG07/VG01 doc-vs-code family.

*No code changes were made; this is a source audit. All device checks OB01-OB24 pending. Gaps are recorded for a later user-authorized fix plan.*
