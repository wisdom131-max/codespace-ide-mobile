# Browser + preview: what is actually there, what breaks, and the plan

Source-verified 2026-10-31. Files: `ui/panes/PreviewPane.kt` (1358 lines),
`preview/LivePreviewServer.kt` (420 lines), `editor/MarkdownRenderer.kt`, manifest.
No device test backs any claim here.

## 1. Inventory: this is not a greenfield feature

Preview already exists as `BottomTab.PREVIEW` with four modes: **HTML, MARKDOWN, SVG, BROWSER**
(`PreviewMode`, PreviewPane.kt:57-62). It has an address bar with back navigation (319-332), a
shared WebView instance that survives tab switches and fullscreen (187-197), a file-chooser hook
for `<input type="file">` (`ValueCallback` + `ActivityResultContracts`), a fullscreen dialog keyed
on orientation, and a guide row for the empty state.

`LivePreviewServer` serves the active project over **port 5500**: loopback-only bind (VG01 is
fixed, 121), a MIME table, SSE client registry with `pushReload()` (181-190), and
`getPreviewUrl(filePath)` producing project-relative URLs with canonical containment (VG02-b
fixed). `usesCleartextTraffic="true"` in the manifest, so `http://localhost` from the app works.

So the hard part is not "build a browser". It is that the existing pieces are wired in ways that
produce silent, confusing failure, and the browser half shares a WebView with previews.

## 2. The six real problems, in the order they hurt

**P1. Wrong origin: previews run as opaque documents.**
Every render path uses `loadDataWithBaseURL` with `about:blank`, `null`, or a server base URL
(807, 867, 915, 1225, 1300, 1317). Under `about:blank`/`null` the document has an opaque origin:
`fetch`, `XMLHttpRequest`, ES module `import`, `localStorage`, `sessionStorage`, cookies, and
service workers all fail, often silently. A modern HTML file with `<script type="module">` or a
fetch to a JSON fixture renders as a blank page. The fix is not a flag; it is to stop using data
loads for anything with assets and serve the entry file from `LivePreviewServer` instead.

**P2. No console, no error surface.**
There is no `onConsoleMessage` handler and no `addJavascriptInterface` anywhere in the preview
code. When a script throws, the user sees an empty pane with no information, while the one thing
that would explain it (a `ReferenceError` on line 3) is being printed into a WebView console
nobody reads. This is the single highest-value fix in the whole area.

**P3. Live reload is dead code.**
`pushReload()` and the SSE client set exist, and **nothing calls `pushReload`** anywhere in the
codebase. Refresh is manual only (`webViewRef?.reload()` at 292 and 540). So the "live preview"
is live only if the user taps. It needs a caller on save, plus a `FileObserver` for writes that
land from the terminal or a proot dev server.

**P4. Preview and the browser share one privileged WebView.**
The settings block enables `allowFileAccess = true` and `allowFileAccessFromFileURLs = true`
(1110-1113). That is defensible for previewing a project. It is indefensible for BROWSER mode,
where the page is arbitrary internet content. Today both modes ride the same instance, so a
remote page in the browser half sits in a WebView configured to reach `file://`. It also inherits
the same cookie jar as previews. These have to become two instances with different policies.

**P5. No dev-server story.**
`initialPort` is a parameter that sets the address bar once (197). There is no port discovery, no
"your server is on 5173" affordance, and no reverse proxy. Proot shares the app's network
namespace, so `http://localhost:PORT` genuinely reaches a proot dev server, but the user has to
know and type the port, and nothing tells them it is up.

**P6. No download handler.**
No `setDownloadListener`. A download link in the browser mode does nothing visible. Uploads work;
downloads do not.

Lower priority but real: markdown preview renders through a WebView template with its own base
URL rather than the server (867), so relative images break; `MarkdownRenderer` and the chat
markdown renderer are separate implementations that will drift.

## 3. The plan

**Phase A — make preview honest (no new surfaces, highest value).**
Serve the entry file through `LivePreviewServer` and load that URL for HTML/Markdown/SVG modes so
the document has a real origin (kills P1). Add `onConsoleMessage`, capture `window.onerror` and
`unhandledrejection` via an injected script, and render a collapsible console strip under the
preview with error/warn/log counts (kills P2). Call `pushReload()` on save and add a project
`FileObserver` (P3). This phase alone turns "preview doesn't work" into "preview tells you why".

**Phase B — split the browser from the preview.**
Two WebView instances with separate policies: preview keeps file access (needed), browser has
`allowFileAccess=false`, `allowContentAccess=false`, no file-scheme navigation, and its own cookie
manager scope. Add back/forward, a real `setDownloadListener` that routes into the existing
Download/binary viewer path, and address-bar validation that refuses `file://` and `content://`.

**Phase C — dev servers.**
Port discovery from `/proc/net/tcp` (loopback listeners), chips for detected ports, auto-detect of
3000/4173/5173/8000/8080, and a keep-alive so a running server is not killed when the preview tab
closes. Optional: a per-session path prefix on the server so the browser half cannot reach the
preview server by accident.

**Phase D — viewers and Open With.**
Markdown preview routed through the viewer registry instead of a bespoke branch; binary files open
with the read-only banner; 2 MB truncation with an explicit "Load full file"; third-party viewer
registration. This consumes the existing roadmap P1-P6 items rather than duplicating them.

## 4. Verification per phase (device, cumulative batch)

A: open an HTML file that imports an ES module and another that throws on load — the first must
render with its module resolved and the second must show the error in the console strip; edit and
save and see the pane update without tapping reload. B: open a page that tries to navigate to a
`file://` URL and confirm it is refused; download a file and confirm it lands in Downloads. C:
start `python3 -m http.server` in the terminal and confirm the port chip appears and loads. D:
open a `.png`, a `.md`, and a 5 MB log and confirm banner, render, and truncation.

## 5. Decisions needed from the owner

1. Console strip placement: a collapsed one-line bar under the preview, or a tab next to
   Console/Problems in the same panel? (Changes the layout of a surface you have already
   approved, so I will not move anything without a word from you.)
2. Whether browser mode may ever load `http://` non-localhost sites (cleartext is currently
   allowed app-wide).
3. Whether a dev-server keep-alive is wanted, given the battery cost on this device.
