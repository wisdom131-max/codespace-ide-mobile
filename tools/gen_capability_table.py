#!/usr/bin/env python3
"""
gen_capability_table.py — G-E-a bundled capability table generator (COMMITTED).

Trims BerriAI/LiteLLM's model_prices_and_context_window.json (MIT) down to
the providers the app offers + their common models, and emits
android/app/src/main/assets/capability-table.json.

Dev-time tool: run manually when refreshing the table (the generated asset is
committed; CI never runs this). Record the date in the header — the UI shows
it. Size cap: the output MUST stay <= 64 KiB (asserted; refuse to write over).

Usage:
    python3 tools/gen_capability_table.py \
        [--source /path/to/model_prices_and_context_window.json]

If --source is absent the script downloads the current catalog from GitHub.
"""
import argparse
import datetime
import json
import os
import sys
import urllib.request

SOURCE_URL = (
    "https://raw.githubusercontent.com/BerriAI/litellm/main/"
    "model_prices_and_context_window.json"
)
OUT_PATH = os.path.join(
    os.path.dirname(os.path.dirname(os.path.abspath(__file__))),
    "android", "app", "src", "main", "assets", "capability-table.json",
)
SIZE_LIMIT_BYTES = 64 * 1024

# LiteLLM provider -> the app's provider ids (ProviderBootstrap ids).
# openrouter is EXCLUDED on purpose: its own live /models response carries the
# richest metadata, so G-E-b feeds it through the LIVE source; a bundled copy
# would only rot.
PROVIDER_MAP = {
    "openai": "openai",
    "anthropic": "claude",
    "gemini": "gemini",
    "deepseek": "deepseek",
    "xai": "xai",
}

# Curated common models per app provider id. Prefix-matched against the
# vendor model id (LiteLLM ids like "gemini/gemini-2.0-flash" are stripped of
# their provider prefix first), so versioned variants match their base row.
CURATED = {
    "openai": [
        "gpt-5", "gpt-4.1", "gpt-4o", "gpt-4-turbo", "gpt-4", "gpt-3.5-turbo",
        "o4-mini", "o3", "o1", "o3-mini",
    ],
    "claude": [
        "claude-opus-4", "claude-sonnet-4", "claude-haiku-4",
        "claude-3-7-sonnet", "claude-3-5-sonnet", "claude-3-5-haiku",
        "claude-3-opus", "claude-3-5-sonnet",
    ],
    "gemini": [
        "gemini-2.5-pro", "gemini-2.5-flash", "gemini-2.0-flash",
        "gemini-2.0-flash-lite", "gemini-1.5-pro", "gemini-1.5-flash",
    ],
    "deepseek": [
        "deepseek-chat", "deepseek-reasoner", "deepseek-coder",
    ],
    "xai": ["grok-4", "grok-3", "grok-3-mini", "grok-2"],
}


def main() -> int:
    ap = argparse.ArgumentParser()
    ap.add_argument("--source", default=None)
    args = ap.parse_args()

    if args.source:
        with open(args.source, "r", encoding="utf-8") as f:
            catalog = json.load(f)
    else:
        req = urllib.request.Request(SOURCE_URL, headers={"User-Agent": "gen_capability_table"})
        with urllib.request.urlopen(req, timeout=60) as r:
            catalog = json.loads(r.read().decode("utf-8"))

    rows = 0
    providers_out = {}
    # TWO RULES learned from a real mis-trim (gpt-4o matched the catalog's
    # gpt-4o-TRANSCRIBE row, 16k ctx, images=False):
    #   1. CHAT MODE ONLY — audio_transcription / realtime / embedding /
    #      image_generation rows are not chat models and their capability
    #      fields mean something else.
    #   2. EXACT vendor-id match WINS before any prefix match; a prefix match
    #      on a special variant (-transcribe, -realtime, -audio, -search)
    #      must never override the base model's row.
    for vendor_id, row in catalog.items():
        if not isinstance(row, dict):
            continue
        if row.get("mode") != "chat":
            continue
        provider = PROVIDER_MAP.get(row.get("litellm_provider"))
        if provider is None:
            continue
        model = vendor_id.split("/")[-1]
        bucket = providers_out.setdefault(provider, [])
        if model in CURATED[provider]:
            match = model
        else:
            match = next((c for c in CURATED[provider] if model.startswith(c)), None)
            if match is None:
                continue
            # never let a special variant overwrite the base row
            if any(b["match"] == match for b in bucket):
                continue
        entry = {
            "match": match,
            # supports_vision -> images; never guessed when absent
            "images": bool(row.get("supports_vision", False)),
            "tools": bool(row.get("supports_function_calling", False)),
            "maxContext": row.get("max_input_tokens"),
            "pdf": bool(row.get("supports_pdf_input", False)),
            "streaming": True,
        }
        # exact base row replaces a variant-derived placeholder if present
        if model in CURATED[provider]:
            bucket[:] = [b for b in bucket if b["match"] != match]
        bucket.append(entry)
        rows = sum(len(b) for b in providers_out.values())

    table = {
        "version": 1,
        "source": "BerriAI/LiteLLM model_prices_and_context_window.json",
        "source_url": SOURCE_URL,
        "source_license": "MIT",
        "generated": datetime.date.today().isoformat(),
        "row_count": rows,
        "providers": providers_out,
    }
    blob = json.dumps(table, indent=1, sort_keys=True)
    if len(blob.encode("utf-8")) > SIZE_LIMIT_BYTES:
        print(f"REFUSED: table is {len(blob)} bytes, over the {SIZE_LIMIT_BYTES} cap", file=sys.stderr)
        return 1
    os.makedirs(os.path.dirname(OUT_PATH), exist_ok=True)
    with open(OUT_PATH, "w", encoding="utf-8") as f:
        f.write(blob + "\n")
    print(f"wrote {OUT_PATH}: {rows} rows, {len(blob)} bytes "
          f"({100 * len(blob) / SIZE_LIMIT_BYTES:.1f}% of the cap)")
    for p, es in sorted(providers_out.items()):
        print(f"  {p}: {len(es)} rows")
    return 0


if __name__ == "__main__":
    sys.exit(main())
