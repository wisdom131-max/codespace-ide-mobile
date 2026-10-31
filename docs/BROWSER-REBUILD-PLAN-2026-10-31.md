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

## 5. Decisions I need before Step 3

1. Browser as its own bottom tab, or stay a mode inside the preview panel? (A separate tab is
   honest about the two policies; it also adds a 19th tab to a portrait row that already shows all.)
2. External links from the browser surface: open in Chrome, or ask once with a remember option?
3. Whether `http://` non-localhost is allowed at all in the panel browser.

## 6. What this plan does not claim

It does not claim preview parity with VS Code, and it does not claim the WebView can reproduce
VS Code's preview features. Step 0 exists precisely because that comparison has not been read yet.
