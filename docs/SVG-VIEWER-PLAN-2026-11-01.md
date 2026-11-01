# SVG viewer — plan and build (2026-11-01)

## What was there

`PreviewPane.SvgPreview` wrapped the file content in a tiny HTML page: dark background with a
checker, `max-width:100%`, JavaScript **off**, and the WebView's own pinch zoom. That is a
thumbnail. No zoom controls, no fit, no dimensions, no export, no error surface, no way to see the
source, and a malformed file rendered as a blank rectangle with nothing said.

## What the strong extensions actually do

Surveyed on the Marketplace, ranked by installs (2026-11-01):

| Extension | Installs | Ratings | Feature set that matters |
|---|---|---|---|
| `jock.svg` — SVG All-In-One | 2,514,136 | 38 | Live preview + **export PNG**, document symbol tree, MDN reference at cursor, colour picker, rename / go-to `id` and `url(#id)` references, **minify via SVGO**, completion + formatting |
| `SimonSiefke.svg-preview` | 1,556,231 | 25 | Live edit preview, **pan and zoom to 32767%**, open preview to the side, reload, auto-open, scale-to-fit toggle, custom/checkerboard background |
| `cssho.vscode-svgviewer` | legacy, still cited widely | — | Preview in an editor tab, ctrl+wheel zoom, **export PNG (and at an explicit size)**, **copy as data URI**, transparency grid, auto preview |

The consistent core of every serious viewer: render the file, **pan and zoom deliberately**, **fit
and 1:1**, **show the background honestly** (transparency must be visible), **export a raster**,
**hand back the data URI**, and **say something when the file will not render**. VS Code has no
built-in SVG viewer, so this is the whole feature set, not a subset.

Two things from `jock.svg` are deliberately not copied: the symbol tree and the SVGO minifier.
Symbols need a real document model we do not keep for SVG, and `jock.svg`'s own page warns that
minimisation breaks files and they are still hunting for a replacement for SVGO. Shipping a
sanitiser that mangles a user's artwork to save bytes is the wrong trade on a phone. The viewer
shows instead of rewriting.

## What was built

`ui/panes/SvgViewer.kt`, wired into `PreviewPane` where `SvgPreview` used to be.

- **Render** — the file is inlined into a viewer shell with JavaScript on (the previous shell was
  script-free, which is why nothing interactive was possible). Relative references still resolve:
  the shell is loaded against the served file's base URL when the live server is running.
- **Zoom** — toolbar `−` / `%` / `+`, pinch on the canvas, double-tap-free drag to pan. Range
  10% to 3200%, which covers SimonSiefke's 32767% for any real icon and keeps the transform
  arithmetic away from float noise.
- **Fit and 1:1** — fit scales the intrinsic size into the viewport; 1:1 is the file's own units.
- **Background** — checkerboard / dark / light / white, cycled from the toolbar. A transparent
  icon on a flat background is the single most common "is it broken" question.
- **Export PNG** — rasterised in the page at the SVG's intrinsic size (or the current zoom), handed
  back to Kotlin over a `JavascriptInterface`, written to the app's exports folder, and reported
  with the path. This is the `cssho` "export PNG" and `jock.svg` "export PNG" feature.
- **Copy data URI** — base64-encoded `data:image/svg+xml`, on the clipboard, which is what
  `cssho` ships for pasting straight into CSS.
- **Source toggle** — read-only source beside the render, so you can confirm what the viewer is
  actually reading.
- **Error surface** — the file is parsed with a real XML parser first. A malformed SVG shows the
  parser's message and line number instead of an empty canvas, using the same document the render
  would use.
- **Metrics** — intrinsic width×height, node count and byte size in the toolbar, which is what
  tells you whether an export will be the size you expect.

Not copied, on purpose: SVGO-style minification, MDN hover docs, completion, rename. Those belong
to the editor and the language server, not the viewer, and the minifier is documented as unsafe by
the extension that ships it.

## Next for this surface

- Raster export at an explicit size (the `cssho` `Alt+Shift+S X` behaviour) — needs a size dialog.
- Live update while editing the SVG, which currently needs a save because the preview reads disk.
- Symbol outline for `id`-bearing nodes, if a document model is added.
