# Design report — minimap, large-file viewer, gallery, harness (2026-11-08)

Written against the code as it stands at `b787459`. Every claim below is checked against the tree;
anything not measured is marked **UNVERIFIED**.

## 1. Gallery: `.nomedia` is not needed

`chat/ui/ChatDesignGalleryActivity.kt` renders every sample with Compose (`GalleryContent()`,
`sampleState()`); it reads no assets and writes **no files to shared storage**. There is nothing for
the phone's gallery to index, so a `.nomedia` file would be ceremony. The check stays in the device
package as a one-line confirmation (the Gallery opens and no `VNCode` album appears in the system
gallery), not as a code change.

If the Gallery later exports PNGs, the rule is: write them under
`getExternalFilesDir(DIRECTORY_PICTURES)` and drop a `.nomedia` in that directory once at creation.

## 2. Minimap design

Today the editor draws a minimap from `LineWidthMeasurer` widths. For a 13,000-line file the map is
computed per line, which is why the preview paging matters more than the map.

Design (not implemented):

- **Source of truth**: the same page the text field holds. In a preview the minimap must map the
  PAGE, and label the scrubber with absolute lines (`5281-10560`), so a tap means the same line the
  banner names. Reuse `PreviewNavigation.absoluteLineFor(pageStart, pageLine)` — it is already tested,
  including the 5,280 / 5,281 boundary.
- **Density**: downsample to the viewport's pixel height once, on `Dispatchers.Default`, keyed on
  (content hash, width, font size). Never per line at paint time: that is the ANR path the memory
  rules already forbid for word highlighting and search overlays.
- **Colour**: one accent for the viewport thumb, `Color(0xFF33333A)` for content. No syntax colours in
  the map: at that size they read as noise and cost a second pass.
- **Interaction**: drag scrolls; a single tap on a preview minimap opens the page holding that
  absolute line, through `PreviewJump.plan`, exactly like search and terminal links.
- **Refusal**: the minimap is read-only everywhere, so it needs no write guard. It must never be the
  thing that scrolls a read-only tab into a stale page: the stale-preview poll already reloads the
  page, and the minimap redraws from the reloaded content.

## 3. Large-file viewer

What exists: files over the height budget open as a read-only preview with paging, a banner naming
the absolute range, navigation that converts absolute lines to pages, stale detection with reload,
and every writer refused. What does **not** exist yet is a viewer for files that are large but
structured (CSV, JSON, logs) where "page 3 of 8" is worse than "first 2,000 lines, search, jump".

Plan: one screen, three panes' worth of behaviour in one column: a search box that runs on
`Dispatchers.Default`, a result list capped at 2,000 hits, and a page jump. It reuses `PreviewJump`
and `TextRenderLimits` rather than adding a second offset model. **UNVERIFIED** on device; no code
written.

## 4. Harness

Goal: catch the class of failures this work is about (Constraints overflow, restore loops, preview
writes) without a device round. The pure decisions are already tested (1,678 tests): layout fit,
layout edit gate, jump planning, staleness, restore decision table, autosave slice refusal, render
limits. The gap is the **wiring**: no test yet drives `EditorPane` itself, because it is Compose
inside a screen.

Plan: a Robolectric screen harness that opens a temp file through the real composable, asserts the
tab's `readOnly`/`previewNote`/page fields, then attempts a save through `writeTabToDisk` and asserts
the file bytes are unchanged. That turns the device-only claims into CI claims for the cases that do
not need real layout. Marked **UNVERIFIED** until it runs green.
