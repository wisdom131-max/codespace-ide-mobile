#!/usr/bin/env python3
"""A(a) chat token generator: android/chat-tokens.json is the SINGLE source.
Emits (deterministically, checked in, never hand-edited):
  - android/app/src/main/java/com/codespace/ide/chat/ui/ChatTokens.kt  (Compose)
  - android/app/src/main/assets/chat-tokens.css                        (CSS custom properties)
Run from anywhere: python3 scripts/generate_chat_tokens.py
"""
import json
import os

HERE = os.path.dirname(os.path.abspath(__file__))
ROOT = os.path.dirname(HERE)
SRC = os.path.join(ROOT, "android", "chat-tokens.json")
KT_OUT = os.path.join(ROOT, "android", "app", "src", "main", "java", "com",
                      "codespace", "ide", "chat", "ui", "ChatTokens.kt")
CSS_OUT = os.path.join(ROOT, "android", "app", "src", "main", "assets", "chat-tokens.css")

# Field order is FIXED so the outputs are byte-stable.
COLOR_FIELDS = ["bg", "surface", "surfaceAlt", "border", "text", "textSecondary",
                "accent", "success", "warning", "error", "errorBg", "warningBg",
                "streamCursor", "input"]

def compose_color(hex_str):
    h = hex_str.lstrip("#")
    if len(h) == 8:
        return "Color(0x" + h.upper() + ")"
    if len(h) == 6:
        return "Color(0xFF" + h.upper() + ")"
    raise ValueError("bad hex token: " + hex_str)

def palette_block(name, pal):
    lines = ["    val " + name + " = ChatColors("]
    for k in COLOR_FIELDS:
        lines.append("        " + k + " = " + compose_color(pal[k]) + ",")
    lines.append("    )")
    return "\n".join(lines)

KT_TEMPLATE = """// GENERATED from android/chat-tokens.json by scripts/generate_chat_tokens.py — DO NOT EDIT BY HAND.
// Regenerate: python3 scripts/generate_chat_tokens.py (from the repo root)
package com.codespace.ide.chat.ui

import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color

/** Chat surface colors, generated from the single token source (A(a)). */
data class ChatColors(
    val bg: Color,
    val surface: Color,
    val surfaceAlt: Color,
    val border: Color,
    val text: Color,
    val textSecondary: Color,
    val accent: Color,
    val success: Color,
    val warning: Color,
    val error: Color,
    val errorBg: Color,
    val warningBg: Color,
    val streamCursor: Color,
)

/** A(a) chat design tokens — android/chat-tokens.json is the single source. */
object ChatTokens {

@DARK_BLOCK@

@LIGHT_BLOCK@

    val LocalChatColors = staticCompositionLocalOf { dark }

    // Radius (dp) — MANDATORY UI RULE: panels/chips 8-12dp rounded.
    const val RADIUS_PANEL = @RADIUS_PANEL@
    const val RADIUS_CHIP = @RADIUS_CHIP@

    // Padding (dp) — MANDATORY UI RULE: 12dp horizontal, 10dp vertical.
    const val PAD_HORIZONTAL = @PAD_H@
    const val PAD_VERTICAL = @PAD_V@

    const val GAP = @GAP@
    const val GAP_TIGHT = @GAP_TIGHT@

    // Typography (sp)
    const val FONT_MICRO = @F_MICRO@
    const val FONT_SMALL = @F_SMALL@
    const val FONT_BODY = @F_BODY@
    const val FONT_MONO = @F_MONO@
}
"""

def kt_text(t):
    pal = t["palette"]
    r = t["radius"]; sp = t["spacing"]; ty = t["typography"]
    out = KT_TEMPLATE
    out = out.replace("@DARK_BLOCK@", palette_block("dark", pal["dark"]))
    out = out.replace("@LIGHT_BLOCK@", palette_block("light", pal["light"]))
    out = out.replace("@RADIUS_PANEL@", str(r["panel"]))
    out = out.replace("@RADIUS_CHIP@", str(r["chip"]))
    out = out.replace("@PAD_H@", str(sp["padHorizontal"]))
    out = out.replace("@PAD_V@", str(sp["padVertical"]))
    out = out.replace("@GAP@", str(sp["gap"]))
    out = out.replace("@GAP_TIGHT@", str(sp["gapTight"]))
    out = out.replace("@F_MICRO@", str(ty["fontMicro"]))
    out = out.replace("@F_SMALL@", str(ty["fontSmall"]))
    out = out.replace("@F_BODY@", str(ty["fontBody"]))
    out = out.replace("@F_MONO@", str(ty["fontMono"]))
    return out

def css_text(t):
    pal = t["palette"]
    def block(sel, p):
        lines = [sel + " {"]
        for k in COLOR_FIELDS:
            lines.append("  --chat-" + k.lower() + ": " + p[k] + ";")
        lines.append("}")
        return "\n".join(lines)
    header = ("/* GENERATED from android/chat-tokens.json by scripts/generate_chat_tokens.py - DO NOT EDIT BY HAND */\n")
    return (header + block(":root,\n.chat-dark", pal["dark"]) + "\n\n" +
            block(".chat-light", pal["light"]) + "\n")

def main():
    with open(SRC, "r", encoding="utf-8") as f:
        t = json.load(f)
    os.makedirs(os.path.dirname(KT_OUT), exist_ok=True)
    os.makedirs(os.path.dirname(CSS_OUT), exist_ok=True)
    with open(KT_OUT, "w", encoding="utf-8") as f:
        f.write(kt_text(t))
    with open(CSS_OUT, "w", encoding="utf-8") as f:
        f.write(css_text(t))
    print("wrote " + KT_OUT)
    print("wrote " + CSS_OUT)

if __name__ == "__main__":
    main()
