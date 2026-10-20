# C12 Stage 3 — U4 (PDF attachments) on-device test sheet

> Build: CI GREEN 39404630179 @ 8580adc (supersedes 416f417 for the device round)
> Test location: `/sdcard/Download/zz_stage3/` (alongside the U1-U3 fixtures)

## What shipped

- Text PDFs extract via the pinned Apache-2.0 port `com.tom-roush:pdfbox-android:2.0.27.0`
  (BouncyCastle EXCLUDED; CI re-verifies the AAR SHA-256 every build).
- Scanned / no-text PDFs render up to 3 page images via the built-in Android
  PdfRenderer — only when the active provider accepts images — else the honest
  metadata fallback with the reason.
- Encrypted/password PDFs refuse honestly ("Encrypted or password-protected —
  not supported yet"). No JS execution, no link following, embedded
  files/form actions never touched.

## Fixture generator (paste into Termux at /sdcard/Download/zz_stage3/)

```python
import os

def make_pdf(path, text_lines, blank=False):
    """Minimal but VALID PDF 1.4: one page, Helvetica text. Hand-built xref."""
    content = "BT /F1 24 Tf 72 700 Td\n"
    for line in text_lines:
        esc = line.replace("\\", r"\\").replace("(", r"\(").replace(")", r"\)")
        content += f"({esc}) Tj 0 -30 Td\n"
    content += "ET"
    if blank:
        content = ""
    objs = {}
    objs[1] = b"<< /Type /Catalog /Pages 2 0 R >>"
    objs[2] = b"<< /Type /Pages /Kids [3 0 R] /Count 1 >>"
    objs[3] = b"<< /Type /Page /Parent 2 0 R /MediaBox [0 0 612 792] /Resources << /Font << /F1 5 0 R >> >> /Contents 4 0 R >>"
    stream = content.encode("latin-1")
    objs[4] = b"<< /Length " + str(len(stream)).encode() + b" >>\nstream\n" + stream + b"\nendstream"
    objs[5] = b"<< /Type /Font /Subtype /Type1 /BaseFont /Helvetica >>"
    out = bytearray(b"%PDF-1.4\n")
    offsets = {}
    for n in sorted(objs):
        offsets[n] = len(out)
        out += f"{n} 0 obj\n".encode() + objs[n] + b"\nendobj\n"
    xref_pos = len(out)
    out += b"xref\n0 6\n0000000000 65535 f \n"
    for n in range(1, 6):
        out += f"{offsets[n]:010d} 00000 n \n".encode()
    out += b"trailer\n<< /Size 6 /Root 1 0 R >>\nstartxref\n" + str(xref_pos).encode() + b"\n%%EOF"
    open(path, "wb").write(out)

make_pdf("text.pdf", ["Hello from the C12 U4 fixture.", "Second line has 42 tokens.", "sk-ABCDEFGHIJKLMNOPQRSTUVWXYZ12 rides for redaction."])
make_pdf("blank.pdf", [], blank=True)
print("generated")
```

Verified in-sandbox against upstream PDFBox 2.0.27 parsing rules (pypdf):
text.pdf = 1 page, not encrypted, 3 text lines; blank.pdf = 1 page, zero
extractable text (the scanned-path proxy; a real scanned PDF behaves the same).

## Checks (attach each in the Chat panel, watch the chip BEFORE sending)

| # | Input | Expected |
|---|---|---|
| U4-1 | `text.pdf` | Chip shows PDF with extraction; extraction completes <5 s; capability/verdict line says PDF text will be extracted. Send block contains all 3 lines; `sk-ABC...12` shows as `[REDACTED]` + "secrets redacted" note. |
| U4-2 | `blank.pdf` (scanned proxy) with an IMAGE provider active (Gemini/OpenAI) | Refusal-free: note "scanned PDF — no extractable text" + "3 page image(s) rendered for the model" (1 page here) + send block notes the page image; send succeeds with the PNG part. |
| U4-3 | `blank.pdf` with a NON-image provider (e.g. Anthropic) | Honest refusal: "No extractable text (scanned or image-only PDF) and the active provider does not accept page images — showing file info only." No page images sent; send does NOT fail. |
| U4-4 | Any real password-protected PDF from the wild | "Encrypted or password-protected PDF — not supported yet. Showing file info only." — no crash, no junk text, no password prompt. |
| U4-5 | A real-world multi-page PDF from Downloads | Text extracts (layout may read interleaved on multi-column — stated limitation); >50 pages shows the "showing pages 1-50 of N" marker. |
| U4-6 | `pdf` opened from a terminal link | Unchanged BATCH12 behavior — regression only. |

## Notes

- U4-2/U4-3 exercise the provider switch: extraction runs at attach time with
  the then-active provider; a provider switch before send re-gates at send
  (page images dropped instead of a failed send).
- The 5 s extraction timeout backs a 4.5 s internal deadline — a slow PDF
  returns PARTIAL text + the honest "showing pages" marker, not a bare timeout.
