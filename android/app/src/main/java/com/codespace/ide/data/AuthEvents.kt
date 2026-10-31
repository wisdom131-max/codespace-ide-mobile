package com.codespace.ide.data

import androidx.compose.runtime.mutableStateOf

/**
 * One sign-in, every panel (2026-10-31, owner report: "when you sign up an account from one flow
 * it automatically connects to all the panels that needs it").
 *
 * Panels that show account state read the token once, at composition, and never look again. That
 * is why signing in from Settings left Source Control, the Explorer chips and the AI panels
 * showing "not signed in" until the app was restarted: nothing told them the token had changed.
 *
 * This is the smallest thing that fixes it. Any write to a provider token bumps [epoch]; a panel
 * that keys its remembered state on [epoch] recomposes with the new truth. Providers beyond
 * GitHub get the same treatment by bumping here too, which is the pattern the connector work
 * should follow: write the token once, bump once, every reader updates.
 */
object AuthEvents {

    /** Bumped on any credential write. Read it as a `remember` key. */
    val epoch = mutableStateOf(0)

    fun bump() {
        epoch.value++
    }
}
