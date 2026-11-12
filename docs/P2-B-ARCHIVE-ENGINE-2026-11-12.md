# P2.B - one archive engine, and honest refusals

`zip_list` and `zip_extract` handled ZIP only. The rest of the requested format list was unimplemented
and undocumented, which is worse than unsupported: the tool said ".zip" and the user's `.tar.gz`
produced nothing useful.

## What is supported now, and by what

| Format | Status | Reader |
|---|---|---|
| ZIP, APK, JAR, AAB | supported | `ZipEngine` (unchanged, owns the Slip/bomb/ratio rules) |
| TAR | supported | commons-compress `TarArchiveInputStream` |
| TAR.GZ / TGZ | supported | gzip + tar |
| GZ, BZ2, XZ (single file) | supported | the matching compressor stream |
| 7Z | supported | commons-compress `SevenZFile` |
| RAR | supported as of P2.C - see `docs/P2-C-RAR-ZST-2026-11-12.md` | junrar (UnRAR licence, read-only) |
| ZST / TAR.ZST | supported as of P2.C - see `docs/P2-C-RAR-ZST-2026-11-12.md` | zstd-jni `@aar` (Android natives) |

`commons-compress 1.26.0` is already a project dependency (it backs the cloud backup tar), so this adds
no dependency and no download.

## Security

`ArchiveEngine` reuses `ZipEngine`'s rules rather than re-implementing them: `unsafeReason` for entry
names, `safeTarget` for canonical containment, `passesThroughSymlink`, and the same ceilings
(`MAX_ENTRIES`, `MAX_TOTAL_BYTES`, `MAX_ENTRY_BYTES`). Symlinks and hard links are skipped on extract,
never materialised. Overwrite requires an explicit flag. Selective extraction matches a path or a
directory prefix. A traversal entry is skipped and reported, not written.

Formats are recognised by **signature**, not extension: a renamed archive still opens, and an
unrecognised file says so instead of being guessed at.

## Wiring

Not a new orphan class: the existing `zip_list` and `zip_extract` dispatch in `AgentTools` now routes
through `ArchiveEngine` after sniffing the file, keeps ZIP on `ZipEngine`, and reports the archive
engine's outcomes through two bounded renderers (partial lists are labelled, skipped entries are named,
unsupported formats explain themselves). Tool descriptions no longer claim ".zip" only. The registry
stays at 118 names because no new tool name was invented for a format list that belongs on the same
tool.

## Tests

`ArchiveEngineTest` (11): tar recognised by signature, a gz holding a tar is flagged instead of being
offered as one anonymous file, a plain gzip or .zst is one file, an
unrecognised file says so, a tar lists its entries with sizes, extraction stays under the destination,
a traversal entry is skipped and nothing is written outside, an existing file is not overwritten
without force, selective extraction takes only the named entry, and a gzip payload decompresses to its
own name.

Full suite 2572 tests, one known `BackupTarTest` root-sandbox failure (green in CI).

## P2 status

- A. Documents (PDF/DOCX/PPTX/XLSX) from `83b062f`: extraction wired. Structure-aware reading (tables,
  slides, sheets as structured output) is **not** done; the readers return text. Listed as a limitation.
- B. Archive engine: this commit.
- C. Archive security: enforced through the reused `ZipEngine` rules, plus link skipping and
  signature-based detection.
