# Preview: PreviewPane read against VS Code, before anything is promised

Read 2026-10-31 against `ui/panes/PreviewPane.kt`, `editor/MarkdownRenderer.kt`,
`preview/LivePreviewServer.kt`, and VS Code's Markdown preview + Live Preview + integrated browser
documentation. Sources for the VS Code column are its own docs. Rows marked **verify** are things I
could not settle from source alone; they need a device check, not an opinion.

Ordering is now **preview first, browser last** (owner decision, 2026-10-31). Nothing below depends
on the browser rebuild.

## 1. What PreviewPane is today

Four exclusive modes — HTML, Markdown, SVG, Browser — in one bottom-panel surface, one shared
WebView per mode, pinch zoom enabled everywhere (`setSupportZoom` + `builtInZoomControls`, controls
hidden), a fullscreen dialog keyed on orientation, a `<input type="file">` picker, manual reload,
and a guide row as the empty state. Markdown renders through the local `MarkdownRenderer` (offline,
no CDN): headings, bold, italic, inline code, fenced code, links, images, lists, blockquotes, rules,
tables, and task-list styling. HTML/SVG render as inline strings via `loadDataWithBaseURL`.

## 2. The comparison

| Capability | VS Code | PreviewPane today | Feasible on Android WebView | Worth doing |
|---|---|---|---|---|
| Preview placement | Separate editor tab, opens to the side | Mode inside the bottom panel | n/a (surface is locked) | No change |
| Updates while typing | Yes, live | Markdown re-renders on content change; HTML re-renders on typing — **device-verified 2026-11-01** | Yes | Yes |
| Preview uses a real origin | Markdown preview is a webview with a CSP and no script execution; Live Preview serves over a localhost server | `loadDataWithBaseURL` with `about:blank`, `null`, and `file://<dir>/` for Markdown | Yes once served from `127.0.0.1:5500` | **Yes, first** |
| Relative images in Markdown | Resolved workspace- and file-relative | Works via `file://` base dir | Yes (also via served origin) | Yes |
| Remote images in Markdown | Allowed per preview security settings | **verify** — a `file://` document loading `http(s)` images is commonly blocked | Yes | Yes |
| Link clicks | Local links open in the editor, header anchors jump, external to browser | Links are inert | Yes | Yes |
| Scroll sync editor↔preview | Both directions (`markdown.preview.scrollPreviewWithEditor` / `scrollEditorWithPreview`) | None | Yes, but needs editor scroll position exposed | Later |
| Double-click preview to jump to editor | Yes (`markdown.preview.doubleClickToSwitchToEditor`) | None | Yes | Cheap, do it |
| Task list checkboxes | Rendered; not editable from the preview | Styled (`.task-list-item`) but interaction **verify** | Yes | Read-only only |
| Code block quality | Highlighted, copy affordance | Plain `<pre><code>`, no highlight, no copy | Yes (offline highlighter) | Yes |
| Math | KaTeX built in | None | Yes (bundled KaTeX, no CDN) | Optional |
| Mermaid | Not built in (extension) | None | Yes, heavier | Park |
| Custom CSS | `markdown.styles` | One hardcoded theme | Yes | Later |
| Console / runtime errors | Live Preview has no console; integrated browser has DevTools | Silent. No `onConsoleMessage` anywhere | Yes | **Yes, first** |
| Reload on save | Automatic (server + refresh) | Manual only; `pushReload()` exists with **zero callers** | Yes | **Yes, first** |
| File watching for external writes | Yes (server) | None | Yes (`FileObserver`) | Yes |
| Dev-server preview | Live Preview embeds a server, auto-refresh, and (1.101+) element selection | Server on 5500 exists; ports panel hands ports over | Yes | Yes |
| Zoom | Ctrl+scroll, settings | Pinch zoom (better for touch) | Already done | Keep |
| Print / export | Neither | Neither | — | No |

## 3. The batch this implies (preview first, browser last)

**P-A. Serve previews from the server (origin fix).** HTML/SVG/Markdown load
`http://127.0.0.1:5500/<file>` through the existing `getPreviewUrl(filePath)`. This is the row that
makes modules, `fetch`, `localStorage` and remote images behave, and it is the piece the browser
rebuild will reuse instead of inventing a second URL scheme.

**P-B. Console strip.** `onConsoleMessage` plus an injected `window.onerror` /
`unhandledrejection` collector, rendered as a collapsed one-line bar. Placement is the only thing
that touches an approved surface, so it waits for your word.

**P-C. Reload on save, and on external write.** Call `pushReload()` from the save path and add a
project `FileObserver` for writes that arrive from the terminal or a proot dev server.

**P-D. Markdown quality.** Code highlighting + copy, link clicks (headers, local files to editor,
external out), remote images under the served origin, double-click to jump to the editor. Task
lists stay read-only.

**P-E. Editing-affordance parity.** Scroll sync, then KaTeX if you want it. This is the batch that
needs editor scroll state exposed, so it comes last and separately.

**Verify on device before promising:** whether HTML re-renders while typing, whether Markdown task
checkboxes do anything when tapped, and whether remote images currently load at all. Those three
answers change P-A and P-D scope, so they are checked in the same cumulative round as P-A/P-B.

## 4. What this audit does not claim

It does not claim parity. Three rows (math, mermaid, custom CSS) are deliberately deferred, one
(surface placement) is locked by you, and two (scroll sync, task interaction) are cheap-but-later.
The only rows I would defend as non-negotiable are the two that cause silent failure: the origin fix
and the console.
