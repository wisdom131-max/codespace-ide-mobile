# Group 18a — Viewers & Binary Inspection

Source audit of the viewer/binary-parsing cluster (~9.4k Kotlin lines), 2026-09-24. Scope per owner's 18a ruling: LivePreviewServer, PreviewPane, MarkdownPreviewRouter, MediaViewers, PdfViewerDialog, PowerUserPanels, AxmlDecoder, ArchiveViewer, ApkAnalyzerDialog, the 13 binary-viewer dialogs, LogcatPanel, FileDetector, FileInfoDialog — at **EX05-depth security scrutiny**: every parser checked for unbounded reads, malformed-input crashes, path traversal in archive extraction, and out-of-scope read/write from untrusted inputs (downloaded APKs, cloned-repo archives, device files). Untrusted-input entry points confirmed: every viewer opens files from the filesystem this app did not create — downloaded APKs in `/storage/emulated/0/Download`, `.so`/`dex`/`pcap` files pulled from anywhere, DB files inside cloned repos.

**Verdict up front:** this cluster contains the audit's densest concentration of malformed-input crash surfaces (two AXML decoders with unvalidated file-controlled allocations, four uncapped whole-file reads), plus one genuine network exposure (the live-preview server binds to all interfaces while its own doc comment claims localhost-only). Against that: several viewers (Hex, BinaryInspector, Strings, BinaryDiff, AiModel) are textbook windowed/bounded readers and should be the pattern the rest copy.

## Features (BV01-BV22)

| ID | Feature | Source |
|---|---|---|
| BV01 | Archive (ZIP/APK) viewer: tree of entries, text preview (5MB streamed cap), manifest decode via AXML, extract-to-Downloads (streamed copy, typed result + Toast) | `ui/panes/ArchiveViewer.kt:63-107,128,155,187` |
| BV02 | APK analyzer: manifest decode → package/permissions/components/features, entry categorization by type, signing presence via `META-INF/*.RSA` | `ui/panes/ApkAnalyzerDialog.kt:235-320` |
| BV03 | Shared AXML decoder utility: streaming 8MB cap, chunk-bounds checks, per-chunk try/catch, known-attribute map | `util/AxmlDecoder.kt:53,117,226` |
| BV04 | Inline duplicate AXML decoder inside the APK analyzer (strCount/offsets parse, same format, different code) | `ui/panes/ApkAnalyzerDialog.kt:78-135` |
| BV05 | Live preview server: static file serving from project root on port 5500, SSE `/__live_reload__` endpoint, html-file fallback walk | `preview/LivePreviewServer.kt:95,113,186,237` |
| BV06 | Preview pane + in-app browser: one shared WebView (JS + DOM storage enabled), address bar, back history, desktop-mode toggle; browser mode defaults `http://localhost:3000` | `ui/panes/PreviewPane.kt:73,183,326,765-773` |
| BV07 | Markdown preview routing: isMarkdown, auto-preview toggle via FeatureToggleStore, readme-at-root auto-open | `ui/panes/MarkdownPreviewRouter.kt:27-46` |
| BV08 | Media viewers: image, video (VideoView + MediaController), audio (MediaPlayer + Compose transport) — all Android system codecs | `ui/panes/MediaViewers.kt:127,180` |
| BV09 | PDF viewer: `PdfRenderer` pages → Bitmap list (system parser, no hand-rolled PDF parsing) | `ui/panes/PdfViewerDialog.kt:4-6` |
| BV10 | SQLite viewer: copies DB to a cache temp file, opens READONLY, lists tables via sqlite_master, 200-row preview; cache file deleted | `ui/panes/SqliteViewerDialog.kt:48,67,91` |
| BV11 | ELF viewer: header/sections/symbols, 128MB size cap, error-string result on failure | `ui/panes/ElfViewerDialog.kt:230-240,315,386` |
| BV12 | Hex viewer: windowed RandomAccessFile read | `ui/panes/HexViewerDialog.kt:49-50` |
| BV13 | Strings viewer: 8KB-window streaming ASCII scan | `ui/panes/StringsViewerDialog.kt:122-129` |
| BV14 | DEX viewer: 64MB cap, header/class listing | `ui/panes/DexViewerDialog.kt:147-170` |
| BV15 | Disassembly viewer: own ELF parse + pseudo-disassembly of the text section | `ui/panes/DisassemblyViewerDialog.kt:85-90,233,294-330` |
| BV16 | Smali viewer: DEX → pseudo-smali class stubs (cap 2000 classes, coerced string reads) | `ui/panes/SmaliViewerDialog.kt:112,150,160` |
| BV17 | Network capture viewer: manual pcap parser (bounded packet lengths) + HAR JSON | `ui/panes/NetworkViewerDialog.kt:116,150-155` |
| BV18 | Binary diff viewer: windowed slices, per-offset compare | `ui/panes/BinaryDiffViewerDialog.kt:80-81` |
| BV19 | Android runtime viewer: OAT/VDEX/ART/APEX format detect + parse | `ui/panes/AndroidRuntimeViewerDialog.kt:382,412` |
| BV20 | Binary inspection trio: BinaryInspectorDialog (windowed reads), FileDetector (magic bytes via bounded RandomAccessFile + encoding detect), FileInfoDialog | `BinaryInspectorDialog.kt:155-167`, `FileDetector.kt:181,270-288`, `FileInfoDialog.kt` |
| BV21 | AI model file viewer: GGUF/GGML-style header walk via RandomAccessFile; 1MB cap on metadata strings | `ui/panes/AiModelViewerDialog.kt:60-76` |
| BV22 | Power-user explorer panels: TodoExplorer + TestExplorer (regex test-file discovery, 7 frameworks) | `ui/panes/PowerUserPanels.kt:26,113,195-203` |

## Cross-group edges (10)

1. **EX05/RG03 shared-fix family:** ArchiveViewer's extract path is contained by construction (basename-only into Downloads), but the cluster confirms the codebase-wide absence of a canonical containment utility — LivePreviewServer's own guard (VG02) is the third independent `startsWith` re-implementation.
2. **EditorPane↔ArchiveViewer:** archive entries open as text previews through the pane's rendering path; AXML OOM surfaces (VG03/VG04) are reachable from the Explorer "view archive" action on any downloaded APK.
3. **PreviewPane↔LivePreviewServer:** pane owns the server lifecycle (start on project active, stop on leave) — the wildcard bind (VG01) is live whenever a project is open.
4. **Browser mode compound (VG01):** the in-app browser renders arbitrary internet pages in the same app process; any page it loads can `fetch("http://localhost:5500/…")` — with the wildcard bind the server answers, giving a malicious page a read channel into project files (CORS limits read, not reachability; SSE and same-origin subresources still probe).
5. **Settings↔MarkdownPreviewRouter:** `md_preview_auto` is a FeatureToggleStore toggle (write path subject to SK01 settings-store safety).
6. **Testing group (TG01)↔BV22:** TestExplorerPanel's regex discovery is a second, parallel test-discovery mechanism beside the decorative TestLens — same files, two lists, no linkage.
7. **Output/Problems↔LogcatPanel:** a third output-ish surface (raw process stream) beside Output pane and Problems.
8. **Terminal/proot↔viewers (positive):** the entire binary suite is pure Kotlin — no proot, no subprocess except LogcatPanel's `adb`, i.e. the kernel-restriction-safe architecture applied consistently.
9. **DiffViewer (Editor group)↔BinaryDiffViewer:** two diff implementations (text vs binary) with separate scrolling/scroll-sync code.
10. **CloudBackup/Download inputs:** downloaded artifacts (APKs from chat/browser/Downloads) are the primary untrusted-input source feeding BV01-BV04 — the same files RG03's restore path ingests.

## VS Code comparisons (8)

1. **Binary-file philosophy:** VS Code core refuses to hand-parse binaries — `BaseBinaryResourceEditor extends EditorPlaceholder` (`src/vs/workbench/browser/parts/editor/binaryEditor.ts:25`, with `BinaryEditorModel` at :9) shows a placeholder + "open in place"; this app built a ~6.4k-line native parser suite (11 format-specific viewers) inside core.
2. **Extension delegation (verified by find in this clone):** no `hexEditor`, no `mediaPreview`, no `simpleBrowser` exist under `src/vs/workbench/contrib/` — hex/media/pdf/pcap/sqlite viewing in VS Code is delegated to extensions; the app implements all of it in-process.
3. **Markdown preview engine:** VS Code renders previews webview-side via the marked-based `markdownDocumentRenderer.ts` (`src/vs/workbench/contrib/markdown/browser/markdownDocumentRenderer.ts:7-16`, verified in clone; full preview manager lives in the markdown-language-features extension); the app routes to its own preview pane with an auto-preview toggle.
4. **Webview isolation:** VS Code webviews are sandboxed per-view with resources loaded through a service (`OverlayWebview` at `src/vs/workbench/contrib/webview/browser/overlayWebview.ts:28`, `resourceLoading.ts` in the same dir); the app's preview/browser is ONE shared JS-enabled WebView used both for project preview and arbitrary internet browsing (DOM storage shared between both uses).
5. **Output channel:** VS Code `OutputChannel`/`OutputViewPane` (`src/vs/workbench/contrib/output/browser/outputServices.ts:36`, `outputView.ts:63`) is a persisted, bounded store; LogcatPanel is a raw process stream with a process lifetime.
6. **No core static server:** VS Code core ships no file server (Live Server is an extension); the app's LivePreviewServer is a hand-rolled ServerSocket + HTTP parser in core (and carries VG01).
7. **Binary vs text model only:** core's own file model just distinguishes text/binary (binaryEditorModel); format detection is `files.associations`/language service — the app's FileDetector does magic-byte sniffing in-process (appropriate for an IDE without an extension host).
8. **No core media/PDF viewers:** same verified absence as (2) — VS Code has no in-core image/video/audio/pdf preview; the app chose system codecs (VideoView/MediaPlayer/PdfRenderer) instead of hand-parsing, which is exactly the right delegation for those formats.

## Device checks pending (BI01-BI36)

Sample scratch-root/scratch-download files only. BI06 and BI10 are the priority pair for VG01/VG02.

- **BI01** open a 100MB+ zip in ArchiveViewer (tree builds without OOM)
- **BI02** extract an entry whose filename already exists in Downloads (silent overwrite? — VG07)
- **BI03** open a malformed `.apk` (renamed corrupt zip) in the analyzer (error message, not crash)
- **BI04** scratch-built tiny AXML with huge declared stringCount/UTF-16 length (confirm VG03 crash signature = OOM, not caught error)
- **BI05** open a real APK's AndroidManifest via ArchiveViewer (BV03 decode works)
- **BI06** **with the app serving a project on Wi-Fi, from a SECOND device hit `http://<phone-ip>:5500/`** (confirms VG01 wildcard exposure — the single most important check in this group)
- **BI07** in-app browser loads `http://localhost:5500/` (preview works through the pane's own WebView)
- **BI08** edit + save a served HTML file (SSE reload fires in the open browser)
- **BI09** request `/../../data/local/tmp/` variants from the server (403 everywhere)
- **BI10** scratch project containing a symlink to a sibling directory outside the root; request the symlink (VG02 escape check)
- **BI11** open a repo with README.md → auto-preview opens (toggle on)
- **BI12** play mp4/seek; play mp3/seek (system codec path)
- **BI13** multi-page PDF renders; corrupt PDF → graceful error
- **BI14** sqlite viewer on a real DB; table with >200 rows caps at 200
- **BI15** sqlite viewer on a corrupt/empty file (error, no crash)
- **BI16** ELF viewer on device `libc.so`; then a >128MB file (clean "too large" message)
- **BI17** hex viewer on a ~1GB file (windowed read stays responsive)
- **BI18** DEX viewer on a real classes.dex (header/classes list)
- **BI19** disassembly viewer on a large `.so` (VG05 OOM watch)
- **BI20** pcap viewer on a downloaded capture (bounded packet list)
- **BI21** binary diff two different-size files (windowed compare stable)
- **BI22** Android runtime viewer on a device oat/vdex file
- **BI23** rename a binary to `.txt` and open (FileDetector flags binary → viewer not text editor)
- **BI24** AI model viewer on a real GGUF header file (1MB string cap works)
- **BI25** Logcat panel on-device (does `adb` even resolve in PATH? expected: blank/error — VG09)
- **BI26** TestExplorer discovers `*Test.kt` in a scratch project
- **BI27** TodoExplorer lists `// TODO` comments
- **BI28** strings viewer on a ~500MB file (streams without OOM)
- **BI29** archive entry named `..` / segment dots (extraction fails safely, nothing written outside Downloads)
- **BI30** scratch zip-bomb (small archive, huge inflated entry) — extraction disk-fill watch (VG08)
- **BI31** scratch sqlite with a table name containing a backtick (VG06 injection behavior)
- **BI32** pdf viewer on a scratch malicious PDF (PdfRenderer robustness)
- **BI33** HAR file parse (JSON path of BV17)
- **BI34** smali viewer on a large dex (VG05 OOM watch)
- **BI35** sqlite viewer on a >1GB DB (cache copy cost — unbounded `copyTo`)
- **BI36** untrusted video file in media viewer (codec crash contained by system player)

## Gaps (VG01-VG12)

| Gap | Priority | Behavior / risk | Evidence and check |
|---|---|---|---|
| **VG01** | **TOP SECURITY TIER (2026-09-24 owner elevation, effective immediately — no-address ServerSocket is unconditionally all-interfaces in Java; BI06 is the confirming check, not the gate). Exposure can exceed other top-tier findings: Wi-Fi proximity alone, or none at all via the browser-mode compound path** | LivePreviewServer binds `ServerSocket(5500)` — the NO-address constructor binds **all interfaces (0.0.0.0)**, while the class's own doc comment says "Binds to localhost only — never exposed beyond the device". On any shared Wi-Fi, every device on the network can read the entire active project. **Compound with browser mode:** a malicious page opened in the app's own in-app browser can fetch `http://localhost:5500/` from inside the app's process (the server answers the loopback request too), giving an internet page a read channel into project files. Fix is one line: `ServerSocket(5500, backlog, InetAddress.getByName("127.0.0.1"))`. Same doc-vs-code family as IG07. | `preview/LivePreviewServer.kt:27` (doc), `:113` (bind); BI06/BI07; edge 4. |
| **VG02** | MEDIUM-HIGH, security (EX05 family; TWO sites in this file — resolveSafeFile :329 and getPreviewUrl :155, both listed as mandatory call sites in MASTER-GAPS' cross-cutting containment item) | resolveSafeFile rejects any `..` outright (good) but containment is `startsWith(rootCanonical)` WITHOUT a path-separator boundary — a symlink inside the project resolving to a sibling directory (`/x/myapp-evil` when root is `/x/myapp`) passes the prefix check and serves files outside the project. Third independent `startsWith` re-implementation found in this audit; belongs as a 5th call site on the EX05/RG03 shared canonical-containment utility. | `preview/LivePreviewServer.kt:resolveSafeFile`; BI10. |
| **VG03** | **HIGH in group, crash on untrusted input (no adversarial input needed — a malformed APK suffices)** | Shared AxmlDecoder validates chunk bounds well, but `parseStringPool` allocates `IntArray(stringCount)` straight from a file u32 and `readUtf16String` allocates `CharArray(len)` from a file-controlled 2-byte varint (~2¹ values, up to GBs) BEFORE bounds validation — a crafted manifest declares a huge count → OutOfMemoryError, which is an **Error, not an Exception, so the per-chunk catch does not save it** → hard app crash from opening a malicious APK. | `util/AxmlDecoder.kt:227,262-267`; BI04. |
| **VG04** | **HIGH in group, same crash class + dead-code family** | ApkAnalyzerDialog carries a SECOND, inline AXML decoder (a duplicate of util/AxmlDecoder with different code): `IntArray(strCount)` unvalidated (:113), `ByteArray(chunkSize - …)` from file (:115), AND `zip.getInputStream(manifestEntry).readBytes()` with NO 8MB cap (:264 — the shared decoder's whole point). Uncapped OOM on a downloaded APK. Fix: delete the duplicate object, call `util/AxmlDecoder` (same ruling class as IG06 SSHJ deletion). | `ui/panes/ApkAnalyzerDialog.kt:78,113,115,264`; BI03/BI04. |
| **VG05** | HIGH in group, systemic memory discipline | Inconsistent whole-file loads: SmaliViewer, DisassemblyViewer, NetworkViewer, AndroidRuntimeViewer call `file.readBytes()` with NO size cap — while the SAME formats are capped elsewhere (ElfViewer 128MB :236, DexViewer 64MB :147). Opening a ~1GB dex/so/pcap from Downloads on a 3GB device = OOM. One shared `readFileCapped` helper (the `AxmlDecoder.readBytesStreaming` pattern already in the codebase) closes all four. | `SmaliViewerDialog.kt:112`, `DisassemblyViewerDialog.kt:233`, `NetworkViewerDialog.kt:117`, `AndroidRuntimeViewerDialog.kt:412`; BI19/BI20/BI34. |
| **VG06** | MEDIUM, injection into a readonly copy | SqliteViewer builds `` SELECT * FROM `$table` LIMIT 200 `` by interpolating a table name that comes from the UNTRUSTED DB's own sqlite_master — a crafted table name (backticks/LIMIT splice) alters the executed query inside the readonly cache copy (LIMIT removal → full-table OOM; cross-table UNION reads). Blast radius small (readonly, cache copy); fix = escape or reject non-identifier names. | `ui/panes/SqliteViewerDialog.kt:91`; BI31. |
| **VG07** | MEDIUM, silent overwrite | Archive extract always writes to `/storage/emulated/0/Download/<basename>` with NO overwrite confirmation — extracting `build.gradle` silently replaces the user's existing Downloads file of the same name. | `ui/panes/ArchiveViewer.kt:191-193`; BI02. |
| **VG08** | MEDIUM-LOW, DoS | Extraction streams with no total-size quota (zip-bomb inflates until storage fills); sqlite viewer's `input.copyTo(output)` cache copy is likewise unbounded for >1GB DBs. | `ArchiveViewer.kt:196-203`, `SqliteViewerDialog.kt:50-53`; BI30/BI35. |
| **VG09** | LOW, probably-dead feature | LogcatPanel shells out to `adb logcat` — this device has no adb binary in the app's PATH outside proot (the panel predates the proot architecture); expected result is a blank panel or exec exception. Needs BI25 before calling it dead. | `ui/panes/LogcatPanel.kt:72`; BI25. |
| **VG10** | LOW | DisassemblyViewer re-implements ELF parsing independently of ElfViewer (same format, separate code — VG04's duplicate-parser family; drift risk, different bounds behavior already visible: one caps, one doesn't). | `DisassemblyViewerDialog.kt:233` vs `ElfViewerDialog.kt:236`. |
| **VG11** | LOW, honesty-of-UI | APK analyzer reports "V1 (JAR) signed" when any `META-INF/*.RSA` entry exists — presence ≠ valid signature (no cryptographic verification, not even a cert parse); label overstates. | `ui/panes/ApkAnalyzerDialog.kt:258-260`. |
| **VG12** | LOW | MediaViewers routes `.pcap/.har` to the network viewer via filename sniffing (:363) but FileDetector's format registry and the viewer dispatch live in separate tables — extension additions can drift (a format added to one and not the other silently opens in the wrong viewer). | `ui/panes/MediaViewers.kt:363` vs `FileDetector.kt:181`. |

**Strengths:** the shared AxmlDecoder is genuinely well-designed for untrusted input (8MB stream cap, chunk-size validation, per-chunk exception skip, `getOrNull` discipline) — it is the model the duplicate should have been; Hex/BinaryInspector/Strings/BinaryDiff/AiModel viewers are textbook windowed or capped readers (RandomAccessFile windows, 1MB metadata cap) — the pattern VG05's helper generalizes; PDF and media go through system parsers (PdfRenderer/VideoView/MediaPlayer) instead of hand-rolled codecs — exactly the right delegation; ArchiveViewer's extract is contained by construction (basename-only) and its result typing (boolean → Toast) avoids the S01 family; resolveSafeFile's outright `..` rejection plus canonical check is more than most homegrown servers do. **Cross-links:** VG01 → IG07 (doc-vs-code) + TP04 family (network surfaces on device); VG02 → EX05/RG03/RG07 shared containment utility (5th call site); VG03/VG04 → EX05-depth scrutiny mandate (crash from untrusted archive input), VG04 → IG06 (delete-the-duplicate ruling class); VG05 → the capped-reader pattern already in-tree; VG06 → readonly-copy blast-radius reasoning (adversarial input needed → below VG01/VG03 tiers per CH03 reasoning); VG11 → TP03 prose-match family (presence asserted as property).

*No code changes were made; this is a source audit. All device checks BI01-BI36 pending. Gaps are recorded for a later user-authorized fix plan.*
