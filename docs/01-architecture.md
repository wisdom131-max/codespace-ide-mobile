# 01 — Complete Application Architecture

VN Code is built as an **offline-first, modular, multi-layer system**. The
Android app is the primary runtime and can do most work fully on-device; a thin cloud
backend handles things a phone cannot or should not do directly (secret-safe AI proxying,
multi-device sync, long-lived PTY sessions, OAuth token exchange).

---

## 1. Design principles

1. **Offline-first.** Editing, browsing, local Git, and snippets work with zero network.
2. **Modular core.** Every major capability (editor, terminal, git, ai, sync, plugins) is
   an isolated Gradle/Compose module behind an interface, enabling the plugin system.
3. **Secrets never live in the client long-term.** OAuth/API keys are brokered and
   encrypted; AI provider keys can be user-supplied (BYOK) or proxied.
4. **Resource-aware.** Tuned for 3–8 GB RAM: lazy loading, virtualized lists, paged file
   trees, memory-mapped large files, and aggressive lifecycle-aware disposal.
5. **No artificial limits.** No file count, project size, or subscription gating in code.

---

## 2. Layered architecture (Android)

We use **Clean Architecture + MVVM + Unidirectional Data Flow**.

```
┌─────────────────────────────────────────────────────────────┐
│ PRESENTATION  (Jetpack Compose, Material 3)                   │
│  Screens · Composables · Navigation · Theme (dark/light)      │
│  ViewModels (StateFlow) · UI events                           │
├─────────────────────────────────────────────────────────────┤
│ DOMAIN  (pure Kotlin, no Android deps)                        │
│  Use cases · Entities · Repository interfaces                 │
├─────────────────────────────────────────────────────────────┤
│ DATA                                                          │
│  Repositories (impl) · Room/SQLite · Retrofit/OkHttp ·        │
│  WebSocket (terminal) · JGit · SSHJ · DataStore (prefs)       │
├─────────────────────────────────────────────────────────────┤
│ PLATFORM / NATIVE                                             │
│  File system · Keystore · WorkManager (sync/backup) ·         │
│  Foreground services (terminal, sync)                         │
└─────────────────────────────────────────────────────────────┘
```

### Module breakdown (Gradle modules)
| Module | Responsibility |
|--------|----------------|
| `:app` | Entry point, navigation graph, DI wiring |
| `:core:ui` | Material 3 design system, theme, shared composables |
| `:core:common` | Result types, dispatchers, error model, extensions |
| `:core:data` | Room DB, network clients, DataStore |
| `:feature:editor` | Multi-tab editor, syntax highlighting, split view |
| `:feature:explorer` | File tree, bookmarks, search & replace |
| `:feature:terminal` | PTY client, floating terminal, local command bridge |
| `:feature:git` | JGit operations, diff viewer, PR flows |
| `:feature:remote` | SSH/SFTP, Codespaces, Docker connections |
| `:feature:ai` | Provider abstraction, chat, code actions |
| `:feature:tools` | Browser/Markdown preview, DB/JSON viewer, API tester |
| `:feature:plugins` | Plugin host, marketplace, extension API |

---

## 3. Component responsibilities

### 3.1 Editor engine
- **Renderer:** Custom `BasicTextField`-based editor with a tokenizing highlighter.
  Large files use a **rope / piece-table** buffer + viewport virtualization.
- **Syntax highlighting:** TextMate grammars compiled to a lightweight tokenizer
  (`tm4e`-style) covering JS, TS, Python, HTML, CSS, JSON, Markdown, Java, C++, Go,
  Rust, PHP. Grammars are pluggable so new languages drop in as assets.
- **Tabs:** Each open file is a `DocumentSession` with independent undo stack and dirty
  state, persisted to Room so tabs survive process death.
- **Split-screen:** Two `EditorPane`s share the navigation host; on phones split is
  vertical by default, horizontal in landscape.
- **Format/lint:** Delegated to language servers when connected to a remote, otherwise to
  bundled formatters (Prettier via Node bridge, Black/ruff via Python bridge) run in the
  terminal sandbox or on the backend.

### 3.2 Terminal
- **Local:** A bundled BusyBox/proot or Termux-style execution bridge for on-device Bash,
  plus optional bundled Node & Python runtimes (downloaded on first use to keep APK lean).
- **Remote:** WebSocket → backend PTY, or direct SSH PTY via SSHJ.
- **Floating mode:** A draggable, resizable overlay (`Window` over the editor) so users can
  run commands while editing.
- **Streaming:** xterm-style VT100/ANSI parser renders output incrementally.

### 3.3 Git engine
- **JGit** for clone/pull/push/commit/branch/merge/diff fully on-device.
- **PRs / Codespaces** via the GitHub REST + GraphQL API through the backend (token-safe).
- **Diff viewer** renders unified and side-by-side diffs with syntax-aware coloring.

### 3.4 Remote & cloud
- **SSH/SFTP:** SSHJ with key + password + agent auth; SFTP browser mounts as a virtual FS.
- **Codespaces:** OAuth device flow → list/start/stop codespaces → tunnel terminal + files.
- **Docker:** Connect to a Docker host (TCP/TLS or via SSH) to exec into containers.

### 3.5 AI subsystem
- **Provider abstraction** (`AiProvider` interface) with adapters for OpenAI, Claude,
  Gemini, DeepSeek, Ollama (local/remote).
- **Two key modes:** BYOK (key stored in Android Keystore, calls go direct) or Proxy
  (key on backend, app calls backend `/ai/*`). Proxy enables usage metering & safety.
- **Context engine:** builds prompts from open files, selection, repo tree, and a
  retrieval index (embeddings stored locally in SQLite-vec) for "chat with project".

### 3.6 Plugin / extension system
- **Manifest-driven** (`plugin.json`): contributes commands, language grammars, themes,
  views, and AI tools.
- **Sandbox:** Plugins run in a restricted JS runtime (QuickJS/J2V8) with a capability-based
  API surface (`codespace.*`) mirroring a subset of the VS Code extension API.
- **Marketplace:** Backend-served registry with signed packages and version pinning.

---

## 4. Data flow examples

**Open & edit a file (offline):**
```
User taps file → ExplorerVM → OpenFileUseCase → FileRepository(Room+FS)
   → DocumentSession → EditorVM(StateFlow) → Compose recomposition
Edits → debounce → autosave to FS + Room (dirty tracking) → WorkManager backup
```

**AI "fix errors":**
```
EditorVM.selection + diagnostics → AiActionUseCase → AiProvider(adapter)
   → (BYOK direct) or (backend /ai/fix) → streamed patch → DiffPreview → apply
```

**Background sync:**
```
WorkManager periodic → SyncUseCase → diff local vs remote (GitHub/SSH/Docker)
   → resolve → push/pull → update Room → notify
```

---

## 5. Cross-cutting concerns
- **DI:** Hilt across all modules.
- **Concurrency:** Kotlin Coroutines + Flow; structured per-screen scopes.
- **Persistence:** Room (metadata, tabs, tasks, logs, snippets), DataStore (prefs),
  encrypted files (tokens) via Jetpack Security / Keystore.
- **Telemetry:** Opt-in, local-first crash + error log store (see deliverable 09).
- **Theming:** Material 3 dynamic color + hand-tuned dark/light IDE palettes.

See the companion docs for schema (02), API (03), and the concrete code in `android/`
and `backend/`.
