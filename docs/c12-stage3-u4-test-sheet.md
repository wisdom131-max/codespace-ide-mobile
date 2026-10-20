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

The FULL advisor set (2026-10-20) — every fixture below was built and
verified in-sandbox against pypdf parsing rules before landing here:

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

def make_multipage(path, n, lines):
    """Sequential object numbering (no xref gaps, no number collisions)."""
    objs = {}
    objs[1] = b"<< /Type /Catalog /Pages 2 0 R >>"
    first_page, first_content, font_num = 3, 3 + n, 3 + 2 * n
    kids = " ".join(f"{first_page + j} 0 R" for j in range(n))
    objs[2] = f"<< /Type /Pages /Kids [ {kids} ] /Count {n} >>".encode()
    for j in range(n):
        stream = f"BT /F1 18 Tf 72 720 Td (Page {j+1} of {n}: {lines[j % len(lines)]}) Tj ET".encode()
        objs[first_page + j] = (f"<< /Type /Page /Parent 2 0 R /MediaBox [0 0 612 792] /Resources << /Font << /F1 {font_num} 0 R >> >> /Contents {first_content + j} 0 R >>").encode()
        objs[first_content + j] = b"<< /Length " + str(len(stream)).encode() + b" >>\nstream\n" + stream + b"\nendstream"
    objs[font_num] = b"<< /Type /Font /Subtype /Type1 /BaseFont /Helvetica >>"
    out = bytearray(b"%PDF-1.4\n")
    offsets = {}
    for num in sorted(objs):
        offsets[num] = len(out)
        out += f"{num} 0 obj\n".encode() + objs[num] + b"\nendobj\n"
    xref_pos = len(out)
    total = max(objs) + 1
    out += f"xref\n0 {total}\n0000000000 65535 f \n".encode()
    for num in sorted(objs):
        out += f"{offsets[num]:010d} 00000 n \n".encode()
    out += f"trailer\n<< /Size {total} /Root 1 0 R >>\nstartxref\n{xref_pos}\n%%EOF".encode()
    open(path, "wb").write(out)

# 1. text fixture + 2. scanned proxy (blank)
make_pdf("text.pdf", ["Hello from the C12 U4 fixture.", "Second line has 42 tokens.", "sk-ABCDEFGHIJKLMNOPQRSTUVWXYZ12 rides for redaction."])
make_pdf("blank.pdf", [], blank=True)
# 3. multi-page (10 pages)
make_multipage("multipage-10.pdf", 10, ["line one alpha", "line two beta"])
# 4. very large page count (200 pages, ~57 KB — over the 50-page cap)
make_multipage("many-pages-200.pdf", 200, ["tiny page"])

# 5. password-protected (needs pypdf: pip install pypdf)
from pypdf import PdfWriter
w = PdfWriter(clone_from="text.pdf")
w.encrypt(user_password="secret", owner_password="ownerpass")
with open("encrypted.pdf", "wb") as f:
    w.write(f)

# 6. corrupted: valid %PDF header + junk body
open("corrupted.pdf", "wb").write(
    b"%PDF-1.4\n1 0 obj\n<< /Type /Catalog /Root 9 0 R >>\ngarbage that never parses \x00\x01\x02\n" + b"junk" * 100 + b"\n%%EOF")

# 7. truncated: first 60% of the valid text.pdf bytes
data = open("text.pdf", "rb").read()
open("truncated.pdf", "wb").write(data[: int(len(data) * 0.6)])
print("generated 7 fixtures")
```

**Sandbox verification (2026-10-20, pypdf):** text.pdf = 1 page, 3 text lines;
blank.pdf = 1 page, zero text; multipage-10.pdf = 10 pages; many-pages-200.pdf
= 200 pages, 57.8 KB; encrypted.pdf = AES-128, correct password opens it;
corrupted.pdf = PARSE-FAIL "startxref not found" (intended); truncated.pdf =
PARSE-FAIL "Stream has ended unexpectedly" (intended).

**What the container can make** (READ/verified vs listed): `python3 + pypdf`
(VERIFIED here — makes the encrypted fixture and everything above; smallest
footprint; Termux `pip install pypdf` or the proot container's
`apt install python3-pypdf`/pip). `qpdf` (Termux `pkg install qpdf` / proot
`apt install qpdf` — AVAILABLE but NOT exercised here: encrypt via
`qpdf --encrypt in.pdf out.pdf 256 --user-password=secret`, corrupt/truncate
with `head -c`). LibreOffice (proot `apt install libreoffice`, ~700 MB —
HEAVY on mobile data; only if a GUI-exported wild PDF is needed — NOT
recommended for this round).

**Where the attach picker reaches them:** all fixtures go to
`/sdcard/Download/zz_stage3/` — the Chat attach picker browses device
storage (same folder as the U1-U3 fixtures). **Cleanup:**
`rm -rf /sdcard/Download/zz_stage3/` after the round (or keep only text.pdf
for future regression rounds).

## Checks (attach each in the Chat panel, watch the chip BEFORE sending)

| # | Input | Expected |
|---|---|---|
| U4-1 | `text.pdf` | Chip shows PDF with extraction; extraction completes <5 s; capability/verdict line says PDF text will be extracted. Send block contains all 3 lines; `sk-ABC...12` shows as `[REDACTED]` + "secrets redacted" note. |
| U4-2 | `blank.pdf` (scanned proxy) with an IMAGE provider active (Gemini/OpenAI) | Refusal-free: note "scanned PDF — no extractable text" + "3 page image(s) rendered for the model" (1 page here) + send block notes the page image; send succeeds with the PNG part. |
| U4-3 | `blank.pdf` with a NON-image provider (e.g. Anthropic) | Honest refusal: "No extractable text (scanned or image-only PDF) and the active provider does not accept page images — showing file info only." No page images sent; send does NOT fail. |
| U4-4 | Any real password-protected PDF from the wild | "Encrypted or password-protected PDF — not supported yet. Showing file info only." — no crash, no junk text, no password prompt. |
| U4-5 | A real-world multi-page PDF from Downloads | Text extracts (layout may read interleaved on multi-column — stated limitation); >50 pages shows the "showing pages 1-50 of N" marker. |
| U4-6 | `pdf` opened from a terminal link | Unchanged BATCH12 behavior — regression only. |
| U4-7 | `multipage-10.pdf` | All 10 pages' text extracts; send block contains "Page 7 of 10" style lines. |
| U4-8 | `encrypted.pdf` (password `secret`, wrong/none entered) | Clean "Encrypted or password-protected PDF" refusal, no crash, no password prompt, no junk text. |
| U4-9 | `corrupted.pdf` then `truncated.pdf` | "Not a readable PDF (...)" refusal each — honest, no crash. |
| U4-10 | `many-pages-200.pdf` | Extraction completes with the "showing pages 1-50 of 200" marker; send block has page-50 text, never page-51. |

## Notes

- U4-2/U4-3 exercise the provider switch: extraction runs at attach time with
  the then-active provider; a provider switch before send re-gates at send
  (page images dropped instead of a failed send).
- The 5 s extraction timeout backs a 4.5 s internal deadline — a slow PDF
  returns PARTIAL text + the honest "showing pages" marker, not a bare timeout.
