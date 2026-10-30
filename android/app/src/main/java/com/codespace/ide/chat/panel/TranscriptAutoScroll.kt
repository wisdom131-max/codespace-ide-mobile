package com.codespace.ide.chat.panel

import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.codespace.ide.chat.ui.ChatColors
import com.codespace.ide.chat.ui.ChatRowModel
import com.codespace.ide.chat.ui.ChatTokens

/**
 * Batch 3 — TRANSCRIPT AUTO-SCROLL (owner mission part 3).
 *
 * The transcript is a weighted Column with verticalScroll, so "scrolling" is driving that
 * ScrollState. What was wrong: nothing drove it. The Streamed assistant row grew taller
 * every delta while scroll.value stayed put, so a streamed answer walked off the bottom of
 * the screen and the user had to chase it by hand.
 *
 * The rule here is the one every chat client uses, and the reason it needs a rule at all is
 * that the naive version (always scroll to max) fights the user: the moment you scroll up to
 * re-read something, the next delta yanks you back down.
 *
 *  - FOLLOW while the user is at the bottom (or was, and has not moved).
 *  - STOP following the moment the user drags/flings AWAY from the bottom.
 *  - RESUME following only when they come back to the bottom themselves.
 *  - PROGRAMMATIC scrolls (ours) never change the follow flag: only user movement does, which
 *    is why the policy keys on isScrollInProgress rather than on scroll position alone.
 *
 * The decision is a pure function ([AutoScrollPolicy]) so the behaviour is unit-tested
 * without a device; the composable only feeds it real state.
 */
/**
 * Item 3: the follow flag, lifted out of the composable so the CHAT can see it.
 *
 * "Never yank the user back" is only half a feature: once following stops, nothing on screen
 * says so and the only way back is to drag to the bottom by hand. This holder is the single
 * source of truth for "am I pinned to the bottom", so the screen can offer one obvious way
 * back and reset it exactly when the user takes it.
 */
internal class FollowFlag {

    var following by mutableStateOf(true)
        private set

    fun recordUserFollow(value: Boolean) {
        if (following != value) following = value
    }

    /** Back to the newest message, and following again. */
    suspend fun jumpToLatest(scroll: ScrollState) {
        scroll.scrollTo(scroll.maxValue)
        following = true
    }
}

internal object AutoScrollPolicy {

    /** Distance from the bottom still treated as "at the bottom" (a finger is not exact). */
    const val SLOP_PX = 24

    /**
     * What the follow flag becomes after a scroll signal.
     * null = leave it alone (not a user-driven scroll).
     */
    fun followAfterUserScroll(moving: Boolean, remainingPx: Int, slop: Int = SLOP_PX): Boolean? {
        if (!moving) return null
        return remainingPx <= slop
    }

    /** Scroll only when the user is following AND there is somewhere to go. */
    fun shouldAutoScroll(following: Boolean, remainingPx: Int): Boolean =
        following && remainingPx > 0
}

/**
 * Growth key for the transcript: changes exactly when new content arrives (a new row, more
 * streamed text, a re-rendered last row) and stays stable across unrelated recompositions,
 * so the auto-scroll effect fires on content growth only.
 */
internal fun transcriptGrowthKey(rows: List<ChatRowModel>): Int {
    val last = rows.lastOrNull()
    val lastSize = when (last) {
        is ChatRowModel.AssistantStream -> last.text.length
        is ChatRowModel.Thinking -> last.seconds
        is ChatRowModel.InfoNote -> last.text.length
        null -> 0
        else -> 0
    }
    return rows.size * 1_000_003 + lastSize
}

/**
 * Keeps the newest content in view while the user is following it, and never moves the view
 * once they have scrolled away. Emits nothing; call it inside the scrolling container's
 * parent.
 */
@Composable
internal fun TranscriptAutoScroll(scroll: ScrollState, growthKey: Int, follow: FollowFlag) {

    // Only USER-driven movement (drag/fling) may change the follow flag.
    LaunchedEffect(scroll, follow) {
        snapshotFlow { scroll.isScrollInProgress to (scroll.maxValue - scroll.value) }
            .collect { (moving, remaining) ->
                AutoScrollPolicy.followAfterUserScroll(moving, remaining)?.let { follow.recordUserFollow(it) }
            }
    }

    LaunchedEffect(growthKey) {
        // Let the new content be measured before asking where the bottom is; scrolling on
        // the same frame as the append would aim at the OLD maxValue.
        withFrameNanos { }
        val remaining = scroll.maxValue - scroll.value
        if (AutoScrollPolicy.shouldAutoScroll(follow.following, remaining)) {
            scroll.scrollTo(scroll.maxValue)
        }
    }
}

/**
 * Item 3: the visible way back. Shown only while the user is reading older messages, so it
 * never covers content they are actually following, and it disappears the moment it is used.
 */
@Composable
internal fun JumpToLatestButton(colors: ChatColors, onClick: () -> Unit) {
    Box(
        Modifier.fillMaxWidth().padding(vertical = ChatTokens.NAV_ROW_PAD_VERTICAL.dp),
        contentAlignment = Alignment.Center,
    ) {
        Box(
            Modifier
                .clip(RoundedCornerShape(ChatTokens.RADIUS_CHIP.dp))
                .background(colors.surfaceAlt)
                .clickable(onClickLabel = "Jump to latest message", onClick = onClick)
                .padding(horizontal = 10.dp, vertical = 3.dp),
        ) {
            Text("\u2193 Latest", color = colors.accent, fontSize = ChatTokens.FONT_MICRO.sp)
        }
    }
}
