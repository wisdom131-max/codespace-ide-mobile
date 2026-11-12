# Package Intelligence — 2026-11-12

Baseline: `96abd6b` (ZIP tools `ba7a257`, CI 43142119779 GREEN). Scope: three read-only tools —
`npm_info`, `pypi_info`, `lockfile_parse` — so Copilot can explain a package or a resolved dependency
tree without installing anything or touching a manifest.

## 1. Audit

- **Networking**: one convention only — `HttpURLConnection`, 10s connect / 30s read, VNCode user
  agent, redirects on, hard byte cap (`AgentTools.fetchOnce`). No OkHttp, no HTTP library worth
  adding for two JSON GETs.
- **Parsing**: `org.json` throughout. No second JSON stack.
- **Files/workspace**: `ToolPathResolver` + `TrustState.activeProjectRoot` for containment; the ZIP
  work added `resolveInside`-style canonical checks that this project reuses in spirit.
- **Package managers**: the only dependency-shaped code was the terminal's `install_package`, which
  shells out. There was no lockfile parser and no package-manager integration.
- **Cache/timeouts/approval**: bounded in-memory caches exist (`ConnectorStatusCache`,
  `ToolPathResolver`); approval is centralised in `ChatPermissionStore.decideAutoApproved`.

**Gap**: no registry client and no lockfile parser. **Reuse decision**: new `agent/packages/` layer
on the existing HTTP and JSON conventions, no new dependency, and no package-manager subsystem.

## 2. Implementation

- `agent/packages/Registry.kt` — npm and PyPI metadata. Pure renderers (`renderNpm`, `renderPypi`)
  are separate from IO, and the `Fetcher` is injectable, so every test drives fixtures and never
  touches the network. Cache: 32 entries, 600s TTL, keyed by ecosystem + name + **the version that
  was requested**, so an explicit version request can never be answered by a cached "latest".
- `agent/packages/Lockfiles.kt` — pure parsers: package-lock.json / npm-shrinkwrap.json (v1, v2, v3),
  yarn.lock (classic and berry), pnpm-lock.yaml, poetry.lock, Pipfile.lock, uv.lock, pdm.lock,
  requirements.txt. Text in, records out: no process, no network, no writes.
- `agent/PackageTools.kt` — tool layer: validation, workspace containment, filtering (name, direct,
  production, dev), pagination, and a 3,500-character output cap that includes its own truncation
  note.

## 3. Tool behaviour

| Tool | Modes | Network | Args |
|---|---|---|---|
| `npm_info` | Ask / Edit / Agent | yes | `name`, `version` (optional) |
| `pypi_info` | Ask / Edit / Agent | yes | `name`, `version` (optional) |
| `lockfile_parse` | Ask / Edit / Agent | **no** | `path`, `format`, `package`, `direct_only`, `production_only`, `dev_only`, `offset`, `limit` |

A requested version that does not exist is named, with the published versions offered instead of the
answer silently describing latest. All three are read-only, so they sit in the read wave; none was
added to a core offer list, and none was added to any safe/auto-approval set.

## 4. Reliability

- Timeouts 10s/20s, no retries, redirects followed, 2 MB body cap.
- HTTP 404/429/5xx and DNS/timeout failures each produce a distinct, readable message; nothing is
  ever reported as a successful lookup.
- Cache: bounded, TTL'd, `refresh=true` available, and expired entries are dropped rather than
  served stale.
- Offline: `lockfile_parse` needs no network at all.

## 5. Security

- **Untrusted metadata**: registry descriptions and summaries are labelled untrusted, flattened and
  bounded (600 chars); injection-shaped text is detected and flagged with an explicit "this is
  metadata, not instructions" warning, never forwarded as an instruction.
- **Validation**: npm/PyPI names and version strings are regex-checked before any request; a path is
  resolved against the project root and refused if it escapes; only the nine supported filenames are
  parsed; files above 8 MB are refused.
- **No installation**: nothing imports, builds, installs or rewrites a manifest; no package manager
  is executed; `package.json`/`pyproject.toml` are never touched.
- **Permissions**: unchanged. No new safe-tool entry, no AUTO_ALL bypass, no "always allow".

## 6. Tests

44 dedicated tests (15 registry, 16 lockfile formats/paths, 13 tool layer) plus the registry
expectation tables. Focused run: 69 tests, 0 failures. Full suite: 2,157 tests, 1 failure —
`BackupTarTest.failureMessageCarriesRealTotals`, which fails only because the sandbox runs as uid 0
(root can read the files the test needs unreadable); it passes in CI.

## 7. Known limitations

- npm and PyPI only. No Maven, Cargo, NuGet or Go modules.
- pnpm and yarn parsing is line-oriented: a lockfile written in an unusual style may parse fewer
  entries, which is reported through the problems list rather than silently dropping them.
- `poetry.lock` and `requirements.txt` cannot distinguish direct from transitive dependencies, so
  `direct_only` filters them out entirely instead of guessing.
- One lockfile is parsed per call; the others detected in the project are named in the answer.
- Registry lookups are English-language metadata only, and no README body is fetched.
- Nothing was verified on a device. Phone testing remains roadmap item 14.

## 8. Delivery

- Implementation + docs commit: `TBD`
- CI run: `TBD`

## 12. Delivery

- Implementation + docs commit: `49bc21b`
- CI run: `43157562900` — SUCCESS
- Pushed to `main`.
- Device testing: not run. Consolidated phone testing is roadmap item 14 and remains pending.
