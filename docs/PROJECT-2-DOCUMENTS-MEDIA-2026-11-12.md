# Project 2 - Documents & Media Intelligence

## What was missing

`read_file` did `file.readText()` for everything. A PDF, DOCX, PPTX or XLSX therefore arrived as
mojibake: the bytes decoded as UTF-8, which is neither readable nor honest. The archive side was
already covered by `ZipEngine` + the `zip_*` tools (path traversal, symlink, bomb and overwrite
protections, 28 tests), so this project's real gap was documents, not archives.

## What was added

`agent/docs/DocText.kt` - a bounded, dependency-free document reader:

| Format | How the text is recovered |
| --- | --- |
| PDF | FlateDecode streams inflated with `java.util.zip.Inflater`, then the literal strings of the `Tj` / `TJ` text operators, including octal and escaped parentheses |
| DOCX | the container is a ZIP: `word/document.xml`, tags stripped, `</w:p>` becomes a paragraph break |
| PPTX | every `ppt/slides/slide*.xml` |
| XLSX | `xl/worksheets/sheet1.xml`, row breaks preserved |
| text-like | passed through unchanged |

Wired into the single place documents are read, `AgentTools.readFile`: a document now returns
`[document text — best effort, no OCR]` followed by the extracted text, and a non-document binary is
refused by name instead of decoded.

## Honest failure, not empty success

- An encrypted PDF says it is encrypted and returns no text. The password is never guessed.
- A PDF with no text layer (a scan) says so and says there is no OCR here, so the caller knows the
  page images would have to be viewed instead of assuming the document is empty.
- A DOCX with no `word/document.xml` says it contains no readable text part.
- A broken container is reported with its reason, never thrown.
- Over-limit input is refused with the limit quoted.

## Bounds (TECNO Pop 9)

6 MB of input, 16,000 characters of output, inflate guard of 512 buffers, ZIP entry guard of 4,000.
Nothing is memory-mapped and nothing is accumulated without a cap.

## Tests

13 tests in `DocTextTest`, using real ZIP containers built in the test and real PDF syntax for the
text operators, plus the encrypted and no-text-layer cases. Full suite 2476 tests, one known
`BackupTarTest` root-sandbox failure (green in CI).

## Not done here, and why

- OCR, image understanding and video: out of scope for a device this size, and `view_image` already
  exists for looking at a picture.
- Archive browsing UI (ZArchiver / MT Manager style): the engine and the protections exist and are
  tested; a browse surface is UI work and belongs with the file manager, not with chat.
- The archive engine was NOT rewritten. Duplicating `ZipEngine` to look busy would be worse than
  leaving a tested implementation alone.
