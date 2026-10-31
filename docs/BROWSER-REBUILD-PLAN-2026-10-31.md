# Browser rebuild: its own plan, because three surfaces ride on it

Source-verified 2026-10-31. This supersedes the browser/render phases in
`BROWSER-PREVIEW-RESEARCH-2026-10-31.md`; that document's six defects stand, but the browser half is
now its own project with its own ordering.

## 0. Two corrections to my earlier note

- **A Ports panel already exists.** `BottomTab.PORTS` -> `PortsPanel` (ProjectShellScreen.kt:3909),
  backed by `diagnostics/PortsScanner.kt`, which reads the kernel's LISTEN sockets from
  `/proc/net/tcp` + `tcp6`, probes the well-known six, accepts custom ports, and silently rescans
  every 5 s while open (IG14). It is better than I described, and it already hands a port to the
  preview: `onOpenInPreview = { port -> onPreviewPortChange(port); tab = PREVIEW }`.
- **Terminal links never reach the app's browser.** `TerminalPane.kt:401` and `:2669` fire
  `Intent.ACTION_VIEW`, so a `localhost:3000` link leaves the app for Chrome. Same for the
  connectors sheet.

So the substrate is not missing. It is spread across three owners that each assume a different
notion of "show this URL", and that is the actual thing to rebuild.

## 1. Why this needs its own plan (the dependency argument)

Today one WebView instance inside `PreviewPane` serves four jobs: HTML preview, Markdown preview,
SVG, and browsing. `LivePreviewServer` serves files **into** that instance. `PortsPanel` chooses a
port and pushes it **into** that instance. Terminal, chat and the connectors hub route around it
**entirely** via `ACTION_VIEW`.

Change the instance and all four assumptions change with it:

| Dependent | Assumption today | Must change after |
|---|---|---|
| `LivePreviewServer` | Its URL is loaded by a WebView that also has `file://` access | Served origin is the ONLY origin previews use; server gains per-session token, Range for media, `no-store`, and a real 404 |
| `PortsPanel` | Opens a port into "preview", which happens to be a browser | Ports open in the browser surface; file previews stay in preview. Two different verbs |
| `PreviewPane` | Owns the WebView, decides settings, loads data URIs | Requests a handle from the engine; its HTML/MD/SVG modes load URLs, not data |
| Terminal / chat / connectors | `ACTION_VIEW` -> external Chrome | `localhost`/`127.0.0.1` -> panel browser; external -> policy decision |

Doing any of these alone breaks the other three, which is exactly why the browser goes first and
nothing else in this area is touched until it lands.

## 2. The target, stated before any code

One **engine owner**, two **surfaces**, one **origin rule**.

- `BrowserEngine` (new, single owner): creates and recycles WebView instances, owns the console
  bus, downloads, history, UA and TLS-error policy. Nothing else calls `WebView(context)` directly.
- **Preview surface**: engine instance with `allowFileAccess=true` (SVG/local media need it), origin
  always `http://127.0.0.1:5500/...` when a project is open.
- **Browser surface**: engine instance with `allowFileAccess=false`, `allowContentAccess=false`,
  `file://` and `content://` navigation refused, its own address bar/back/forward, downloads wired.
- **Origin rule**: a preview document ALWAYS has an http origin (never `about:blank`, never `null`).
  This is the fix for the opaque-origin defect and it is non-negotiable, because a module script or
  a `fetch` in an otherwise correct HTML file fails silently under both other origins.

## 3. Order, with the gates made explicit

**Step 0 - preview parity audit (no code, report only).**
A line-by-line reading of `PreviewPane` (1358 lines) against VS Code's preview surfaces: Simple
Browser (address bar, history, "open in external", port links), built-in Markdown preview
(scroll sync, task list, relative images, code highlight, security), and the Live Preview
extension's server model. Output is a table of "we do / VS Code does / feasible on Android WebView
/ worth doing", for you to read before anything is promised. **No preview work is scoped until you
have that table and have said which rows matter.** Nothing in this plan assumes a parity outcome.

**Step 1 - extract the engine, behaviour unchanged.**
Move WebView creation, settings, lifecycle, fullscreen handling and the file chooser out of
`PreviewPane` into `BrowserEngine`, with both surfaces initially using the same policy it uses
today. Success criterion is "indistinguishable from now", verified on device: same four modes, same
fullscreen, same upload picker, same rotation behaviour.

**Step 2 - flip preview to a served origin.**
`LivePreviewServer` gains a per-session token and serves the entry file; HTML/MD/SVG modes load
that URL. Markdown keeps an inline string path only when there is no file on disk. This is where
the module/fetch/localStorage class of failures dies.

**Step 3 - split the browser into its own surface.**
Hardened policy on that instance, `file://`/`content://` refused in navigation, downloads routed to
the existing Downloads path, back/forward, and an explicit external-open action. PortsPanel's verb
becomes "Open in browser".

**Step 4 - rewire the matrix in §1.** Terminal/chat/connectors localhost links land in the panel
browser; external links follow the policy you pick; PortsPanel keeps "Open in preview" only for the
project's own files.

**Step 5 - console and reload.** Console bus surfaced where the audit says it belongs, and
`pushReload()` finally gets callers: on save, and from a project `FileObserver` for writes that
arrive from the terminal or a proot dev server.

Each step is a separate revertable commit. Device checks batch at the end of Step 2 and again at
the end of Step 5, per the standing batching rule, not one round per step.

## 4. Risks, named

- **Memory.** Two WebView instances on a 3 GB device is the real risk, not the code. Mitigation:
  recycle a single instance when the surfaces are not both live, destroy on close, `trimMemory`.
  Verification is a measured check, not an opinion.
- **Compose interop.** `AndroidView` recomposition re-creating the WebView is the usual source of
  "preview reloads when I type". Keep the instance in the engine keyed by surface, not in
  composition scope.
- **Bytecode.** All browser chrome composables are extracted (no inline bodies) and no non-local
  returns out of inline lambdas, per the two standing rules that have already cost red builds.
- **Cleartext.** `usesCleartextTraffic="true"` is app-wide today and stays needed for
  `localhost`. The policy question is whether non-localhost `http://` is ever allowed in the
  browser surface.

## 5. Decisions, locked (2026-10-31)

**D1 - the browser stays a MODE inside the preview panel.** It does not get a 19th bottom tab. That
keeps the existing portrait icon row untouched, and it means the two policies (preview vs browser)
are selected inside one surface rather than by which tab you are on. Step 3 therefore builds a mode
switch, not a new panel.

### D2 - external links: follow VS Code exactly, because it validates the split

VS Code's integrated browser (shipped, documented at
`code.visualstudio.com/docs/debugtest/integrated-browser`) does this, in its own words:

- Links that are `localhost`, `127.0.0.1`, `[::1]`, and all-interfaces forms `0.0.0.0` / `[::]`
  **open in the integrated browser by default**, from the terminal, chat, or anywhere else.
- That routing is a setting (`workbench.browser.openLocalhostLinks`); turning it off sends those
  links to the external browser instead.
- Anything else goes to the **external** browser, and there is an explicit "Open in External
  Browser" action for the case where you are already on a page.
- Local `file://` pages open in it too, but only through a deliberate action (Explorer right-click,
  editor-tab menu, or the globe button), never by navigation.

So the policy in this app becomes: **loopback goes in, the open internet goes out, both by rule and
both overridable by an explicit action.**

What that affects in this codebase, precisely:

1. `TerminalPane.kt:401` and `:2669` stop firing `Intent.ACTION_VIEW` unconditionally. They classify
   the URL first: loopback -> panel browser; everything else -> external, as today. This is the only
   behaviour the user will notice, and it is the one VS Code users already expect.
2. `PortsPanel` keeps its `onOpenInPreview` verb. It calls the same router, so a port cannot
   accidentally land in the preview (file) mode.
3. `PreviewPane` BROWSER mode becomes "local server viewer" by default, with an explicit
   "open here" for a non-loopback URL typed into the address bar.
4. Chat and the connectors hub keep `ACTION_VIEW`: both are about the outside world (OAuth consent
   pages, docs), and neither should ever render in a WebView that has file access.
5. Downloads need `setDownloadListener` regardless of this decision; a refused download looks like
   a dead button today.

Benefit: the app never runs an arbitrary internet page in a WebView configured for project files,
and logins keep living in Chrome where the password manager and existing sessions are. Cost: one
extra step to view a remote staging site in-panel, which is the same cost VS Code imposes.

### D3 - `http://` non-loopback: blocked in the browser mode, by a gate, not by the manifest

How VS Code handles it: the integrated browser is Chromium inside Electron on desktop, so cleartext
`http://` simply works and there is no per-surface policy; the protective layer there is the
permissions model (camera, mic, geolocation prompts) and session storage isolation, not transport.

That does not transfer. On Android the governing facts are different:

- Cleartext is allowed app-wide here because the manifest sets `usesCleartextTraffic="true"`, which
  the app genuinely needs: `CustomEndpointStore` accepts user-entered AI base URLs, and those are
  routinely plain `http://192.168.1.x:1234` on a LAN. Tightening the manifest with a
  network-security-config would break real users' model endpoints, which is a worse trade.
- WebView mixed content defaults and `usesCleartextTraffic` are app-scoped. There is no per-WebView
  "block cleartext" switch. So the block has to live in the navigation gate.

The fix, in the engine (Step 3), applied to the **browser mode only**:

1. `shouldOverrideUrlLoading` + `shouldInterceptRequest`: allow `https://` and loopback
   (`127.0.0.1`, `localhost`, `::1`, and `10.0.2.2` for completeness); refuse `http://` with a
   rendered interstitial that says what happened and offers "Open in Chrome".
2. `settings.mixedContentMode = MIXED_CONTENT_NEVER_ALLOW` on the browser instance, always.
3. The preview instance is exempt by construction: it only ever loads `http://127.0.0.1:5500/`.
4. Address bar validation mirrors the gate, so the refusal is explained before the load.
5. Navigator permission requests (camera, mic, geolocation) are denied by default with a one-time
   prompt, rather than silently granted, matching VS Code's explicit permission model.
6. Cookies: the cookie jar is shared process-wide, so the browser mode gets a "clear site data"
   action and does not silently inherit preview cookies.

What it affects: nothing outside the browser mode. Terminal `ACTION_VIEW`, AI endpoint calls,
connector OAuth and the app's own HTTPS backend all keep working unchanged, because none of them go
through the WebView gate.
