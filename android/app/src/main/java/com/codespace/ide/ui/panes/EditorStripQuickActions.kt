package com.codespace.ide.ui.panes

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.codespace.ide.R
import com.codespace.ide.editor.FeatureToggleStore
import com.codespace.ide.editor.ViewScrollLockStore

/**
 * PAD-1 (2026-09-13): strip-row consolidation.
 *
 * The dedicated editor toolbar row in ProjectShellScreen (Find, Replace, zoom
 * −/$/+, word-wrap, inlay hints, Go-to-Line, nav ←/→) was removed — it held THREE
 * find/replace triggers for the same working find bar (its two icons plus the one
 * already in the tab strip). Its remaining controls are relocated HERE, at the
 * end of the scrollable tab strip row, so everything scrolls together as one row
 * (user decision D1: no fixed cluster, no overflow menu). Per-user decision the
 * relocated Go-to-Line and Find icons are DROPPED — the strip already has both.
 *
 * Extracted to its own file per the JVM 64KB method-size rule: EditorPane calls
 * this with a single line from inside its strip Row.
 *
 * Styling mirrors the removed row: compact 28dp tap targets with the same text
 * glyphs (−, $fs, +, ↵, ⊕, ←, →), active blue #007ACC on toggle-on, inactive
 * #858585 otherwise, 1dp separators.
 */
@Composable
internal fun EditorStripQuickActions(
    fontSize: Int,
    onFontSizeChange: (Int) -> Unit,
    wordWrap: Boolean,
    showInlayHints: Boolean,
    navCanGoBack: Boolean,
    navCanGoForward: Boolean,
    onNavBack: (() -> Unit)?,
    onNavForward: (() -> Unit)?,
    separatorColor: Color,
) {
    val active = Color(0xFF007ACC)
    val inactive = Color(0xFF858585)
    Row(verticalAlignment = Alignment.CenterVertically) {
        // ── Zoom out / size / zoom in ─────────────────────────────
        Box(
            Modifier.size(28.dp).clickable { onFontSizeChange((fontSize - 1).coerceAtLeast(8)) },
            contentAlignment = Alignment.Center,
        ) {
            Text("−", fontSize = 16.sp, color = inactive)
        }
        Text("\$fontSize", fontSize = 10.sp, color = inactive, modifier = Modifier.padding(horizontal = 2.dp))
        Box(
            Modifier.size(28.dp).clickable { onFontSizeChange((fontSize + 1).coerceAtMost(32)) },
            contentAlignment = Alignment.Center,
        ) {
            Text("+", fontSize = 16.sp, color = inactive)
        }
        StripActionDivider(separatorColor)
        // ── Word wrap / inlay hints toggles (FeatureToggleStore-backed) ──
        Box(
            Modifier.size(28.dp).clickable {
                FeatureToggleStore.set("word_wrap", !wordWrap)
            },
            contentAlignment = Alignment.Center,
        ) {
            Text("↵", fontSize = 14.sp, color = if (wordWrap) active else inactive)
        }
        Box(
            Modifier.size(28.dp).clickable {
                FeatureToggleStore.set("inlay_hints", !showInlayHints)
            },
            contentAlignment = Alignment.Center,
        ) {
            Text("⊕", fontSize = 13.sp, color = if (showInlayHints) active else inactive)
        }
        StripActionDivider(separatorColor)
        // ── P2-10 Nav back / forward ───────────────────────────────
        Box(
            Modifier.size(28.dp).clickable(enabled = navCanGoBack) { onNavBack?.invoke() },
            contentAlignment = Alignment.Center,
        ) {
            Text(
                "←", fontSize = 16.sp,
                color = if (navCanGoBack) inactive else inactive.copy(alpha = 0.25f),
            )
        }
        Box(
            Modifier.size(28.dp).clickable(enabled = navCanGoForward) { onNavForward?.invoke() },
            contentAlignment = Alignment.Center,
        ) {
            Text(
                "→", fontSize = 16.sp,
                color = if (navCanGoForward) inactive else inactive.copy(alpha = 0.25f),
            )
        }
    }
}

/** 1dp vertical separator between control groups — same look the removed row used. */
@Composable
private fun StripActionDivider(color: Color) {
    Spacer(Modifier.width(4.dp))
    Box(Modifier.width(1.dp).height(16.dp).background(color))
    Spacer(Modifier.width(4.dp))
}

/**
 * PAD-1: per-view scroll-lock padlock, rendered inside each tab/split strip
 * entry between the label and the close X. Genuine codicon lock/unlock pair;
 * active blue while locked, VS-Code-style monochrome otherwise. Tapping flips
 * that VIEW's lock (ViewScrollLockStore), which gates only the IME-AWARE-SCROLL
 * effect in CodeEditor — never the live content sync.
 */
@Composable
internal fun EditorViewLockIcon(viewKey: String, activeTint: Color, inactiveTint: Color) {
    val locked = ViewScrollLockStore.isLocked(viewKey)
    Icon(
        painter = painterResource(if (locked) R.drawable.ic_vs_lock else R.drawable.ic_vs_unlock),
        contentDescription = if (locked) "Scroll locked — tap to unlock scrolling" else "Scroll unlocked — tap to lock scrolling",
        tint = if (locked) activeTint else inactiveTint,
        modifier = Modifier
            .size(14.dp)
            .clickable { ViewScrollLockStore.toggle(viewKey) },
    )
}
