package com.codespace.ide.agent.facts

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * STAGE 1 (2026-11-19) — how a stored timestamp becomes text, in ONE place.
 *
 * Two rules are encoded here rather than left to each call site:
 *  - a missing date reads "Not recorded" and never becomes a different date. The panel must not
 *    print the memory creation time where the conversation date belongs;
 *  - storage stays UTC epoch millis and only the DISPLAY is localised, so a device timezone change
 *    cannot rewrite history.
 *
 * [conversation] exists as its own function on purpose: when the transcript link arrives (stage 4)
 * it will read a different field, and every existing call site stays honest meanwhile because it
 * returns "Not recorded" for a null source time.
 */
object FactDates {

    const val NOT_RECORDED = "Not recorded"

    /** Local-time rendering of a stored epoch-millis value; "Not recorded" when absent. */
    fun local(ms: Long?): String {
        if (ms == null || ms <= 0L) return NOT_RECORDED
        return try {
            SimpleDateFormat("d MMM yyyy, HH:mm", Locale.getDefault()).format(Date(ms))
        } catch (_: Throwable) {
            NOT_RECORDED
        }
    }

    /**
     * The date of the conversation the fact came from. NEVER falls back to the creation time:
     * "saved on Tuesday" is not "we discussed this on Tuesday", and the panel must not imply it is.
     */
    fun conversation(ms: Long?): String = local(ms)

    /** True when a stored value is a real date rather than an absent one. */
    fun known(ms: Long?): Boolean = ms != null && ms > 0L
}
